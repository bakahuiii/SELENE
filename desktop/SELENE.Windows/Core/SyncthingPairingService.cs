using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Selene.Windows.Core;

public sealed record SyncthingEnrollmentResult(string DeviceId, DateTimeOffset PairedAt);

public sealed record SyncthingPairingOffer(
    string Code,
    DateTimeOffset ExpiresAt,
    IReadOnlyList<string> Endpoints,
    Task<SyncthingEnrollmentResult> Completion
);

/** One-use, certificate-pinned enrollment bridge between Android SELENE and local Syncthing. */
public sealed partial class SyncthingPairingService : IDisposable
{
    public const string FolderId = "selene-inbox-v1";
    private const int MaximumRequestBytes = 32 * 1024;
    private readonly object stateLock = new();
    private readonly SemaphoreSlim preparationLock = new(1, 1);
    private CancellationTokenSource? enrollmentCancellation;
    private TcpListener? listener;
    private string? cachedSyncthingBinary;
    private string? cachedDeviceId;
    private string? preparedRequestedInbox;
    private string? preparedEffectiveInbox;
    private DateTimeOffset preparedAt;

    public async Task<string> EnsureWindowsSyncAsync(string inboxDirectory, CancellationToken cancellationToken = default)
    {
        var inbox = Path.GetFullPath(inboxDirectory);
        await preparationLock.WaitAsync(cancellationToken);
        try
        {
            if (preparedRequestedInbox is not null && preparedEffectiveInbox is not null &&
                PathsEqual(preparedRequestedInbox, inbox) &&
                DateTimeOffset.UtcNow - preparedAt < TimeSpan.FromMinutes(5))
            {
                return preparedEffectiveInbox;
            }

            Directory.CreateDirectory(inbox);
            var syncthing = ResolveSyncthingBinary()
                ?? throw new InvalidOperationException("未找到 Syncthing。请先运行 THEIA 的 setup-selene-p2p.ps1 -InstallSyncthing，或设置 SELENE_SYNCTHING_PATH。");
            await EnsureProcessStartedAsync(syncthing, cancellationToken);
            await WaitUntilReadyAsync(syncthing, cancellationToken);
            var effectiveInbox = await EnsureInboxFolderAsync(syncthing, inbox, cancellationToken);
            Directory.CreateDirectory(effectiveInbox);
            UserEnvironment.SetPathIfChanged("THEIA_SELENE_INBOX", effectiveInbox);
            preparedRequestedInbox = inbox;
            preparedEffectiveInbox = effectiveInbox;
            preparedAt = DateTimeOffset.UtcNow;
            return effectiveInbox;
        }
        finally
        {
            preparationLock.Release();
        }
    }

    public async Task<SyncthingPairingOffer> BeginPairingAsync(
        string inboxDirectory,
        CancellationToken cancellationToken = default)
    {
        CancelActivePairing();
        if (ResolveSyncthingBinary() is null)
        {
            await InstallSyncthingAsync(cancellationToken);
            cachedSyncthingBinary = FindSyncthingBinary();
        }
        await EnsureWindowsSyncAsync(inboxDirectory, cancellationToken);
        var syncthing = ResolveSyncthingBinary()
            ?? throw new InvalidOperationException("Syncthing 在准备配对后不可用。");
        var windowsDeviceId = cachedDeviceId ?? (await RunAsync(syncthing, ["device-id"], cancellationToken)).Trim();
        if (!DeviceIdPattern().IsMatch(windowsDeviceId)) throw new InvalidOperationException("Windows Syncthing 设备 ID 无效。");
        cachedDeviceId = windowsDeviceId;

        var certificate = CreateCertificate();
        var fingerprint = Convert.ToHexString(certificate.GetCertHash(HashAlgorithmName.SHA256)).ToLowerInvariant();
        var token = Base64Url(RandomNumberGenerator.GetBytes(32));
        var expiresAt = DateTimeOffset.UtcNow.AddMinutes(5);
        var pairingListener = new TcpListener(IPAddress.Any, 0);
        pairingListener.Start();
        var port = ((IPEndPoint)pairingListener.LocalEndpoint).Port;
        var endpoints = FindLanAddresses()
            .Select(address => $"https://{address}:{port}/enroll")
            .ToArray();
        if (endpoints.Length == 0)
        {
            pairingListener.Stop();
            certificate.Dispose();
            throw new InvalidOperationException("没有可供手机访问的局域网 IPv4 地址。");
        }

        var payload = JsonSerializer.Serialize(new
        {
            schema = "selene-pair/v1",
            windowsDeviceId,
            folderId = FolderId,
            endpoints,
            token,
            certificateSha256 = fingerprint,
            expiresAt = expiresAt.ToString("O"),
            windowsName = Environment.MachineName,
        });
        var code = $"selene-pair:v1:{Base64Url(Encoding.UTF8.GetBytes(payload))}";
        var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        linked.CancelAfter(expiresAt - DateTimeOffset.UtcNow);
        lock (stateLock)
        {
            enrollmentCancellation = linked;
            listener = pairingListener;
        }
        var completion = AcceptEnrollmentAsync(
            pairingListener,
            certificate,
            syncthing,
            token,
            expiresAt,
            linked.Token);
        return new SyncthingPairingOffer(code, expiresAt, endpoints, completion);
    }

    public void CancelActivePairing()
    {
        lock (stateLock)
        {
            enrollmentCancellation?.Cancel();
            enrollmentCancellation?.Dispose();
            enrollmentCancellation = null;
            listener?.Stop();
            listener = null;
        }
    }

    public void Dispose() => CancelActivePairing();

    private async Task<SyncthingEnrollmentResult> AcceptEnrollmentAsync(
        TcpListener pairingListener,
        X509Certificate2 certificate,
        string syncthing,
        string expectedToken,
        DateTimeOffset expiresAt,
        CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested && DateTimeOffset.UtcNow < expiresAt)
            {
                using var client = await pairingListener.AcceptTcpClientAsync(cancellationToken);
                if (client.Client.RemoteEndPoint is not IPEndPoint remote || !IsPrivateAddress(remote.Address))
                {
                    continue;
                }
                using var tls = new SslStream(client.GetStream(), false);
                using var requestTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                requestTimeout.CancelAfter(TimeSpan.FromSeconds(10));
                var requestToken = requestTimeout.Token;
                try
                {
                    await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                    {
                        ServerCertificate = certificate,
                        ClientCertificateRequired = false,
                        EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                    }, requestToken);
                    var request = await ReadRequestAsync(tls, requestToken);
                    if (!request.Method.Equals("POST", StringComparison.OrdinalIgnoreCase) || request.Path != "/enroll")
                    {
                        await WriteResponseAsync(tls, 404, "{\"ok\":false}", requestToken);
                        continue;
                    }
                    using var json = JsonDocument.Parse(request.Body);
                    var root = json.RootElement;
                    var schema = root.TryGetProperty("schema", out var schemaValue) ? schemaValue.GetString() : null;
                    var token = root.TryGetProperty("token", out var tokenValue) ? tokenValue.GetString() : null;
                    var deviceId = root.TryGetProperty("deviceId", out var deviceValue) ? deviceValue.GetString() : null;
                    var folderId = root.TryGetProperty("folderId", out var folderValue) ? folderValue.GetString() : null;
                    if (schema != "selene-enroll/v1" || folderId != FolderId ||
                        !FixedTimeEquals(token, expectedToken) || deviceId is null || !DeviceIdPattern().IsMatch(deviceId))
                    {
                        await WriteResponseAsync(tls, 403, "{\"ok\":false}", requestToken);
                        continue;
                    }

                    await AddAndroidDeviceAsync(syncthing, deviceId, requestToken);
                    await WriteResponseAsync(tls, 200, "{\"ok\":true}", requestToken);
                    return new SyncthingEnrollmentResult(deviceId, DateTimeOffset.UtcNow);
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception error)
                {
                    // A malformed or unrelated connection does not consume the one-use offer.
                    AppLogger.Error("syncthing_pairing_request_failed", error);
                }
            }
            throw new TimeoutException("配对码已过期，请生成新的二维码。");
        }
        catch (Exception error) when (cancellationToken.IsCancellationRequested &&
            error is OperationCanceledException or SocketException or ObjectDisposedException)
        {
            throw new OperationCanceledException("配对已取消。", error, cancellationToken);
        }
        finally
        {
            certificate.Dispose();
            pairingListener.Stop();
            lock (stateLock)
            {
                if (ReferenceEquals(listener, pairingListener)) listener = null;
            }
        }
    }

    private static async Task AddAndroidDeviceAsync(string syncthing, string deviceId, CancellationToken cancellationToken)
    {
        using var config = JsonDocument.Parse(await RunAsync(syncthing, ["cli", "config", "dump-json"], cancellationToken));
        var devices = config.RootElement.GetProperty("devices");
        var known = devices.EnumerateArray().Any(item =>
            item.TryGetProperty("deviceID", out var id) && id.GetString() == deviceId);
        if (!known)
        {
            await RunAsync(syncthing,
                ["cli", "config", "devices", "add", $"--device-id={deviceId}", "--name=SELENE Android"],
                cancellationToken);
        }

        using var folder = JsonDocument.Parse(await RunAsync(
            syncthing,
            ["cli", "config", "folders", FolderId, "dump-json"],
            cancellationToken));
        var folderHasDevice = folder.RootElement.GetProperty("devices").EnumerateArray().Any(item =>
            item.TryGetProperty("deviceID", out var id) && id.GetString() == deviceId);
        if (!folderHasDevice)
        {
            await RunAsync(syncthing,
                ["cli", "config", "folders", FolderId, "devices", "add", $"--device-id={deviceId}"],
                cancellationToken);
        }
    }

    private static async Task<string> EnsureInboxFolderAsync(string syncthing, string inbox, CancellationToken cancellationToken)
    {
        using var config = JsonDocument.Parse(await RunAsync(syncthing, ["cli", "config", "dump-json"], cancellationToken));
        JsonElement? existing = null;
        foreach (var folder in config.RootElement.GetProperty("folders").EnumerateArray())
        {
            if (folder.TryGetProperty("id", out var id) && id.GetString() == FolderId)
            {
                existing = folder.Clone();
                break;
            }
        }
        if (existing is null)
        {
            await RunAsync(syncthing,
                ["cli", "config", "folders", "add", $"--id={FolderId}", "--label=SELENE Inbox", $"--path={inbox}", "--type=receiveonly"],
                cancellationToken);
            return inbox;
        }
        var currentPath = Path.GetFullPath(existing.Value.GetProperty("path").GetString() ?? string.Empty);
        var type = existing.Value.GetProperty("type").GetString();
        if (type != "receiveonly")
        {
            throw new InvalidOperationException($"Syncthing 文件夹 {FolderId} 不是 Receive Only，请先在 Syncthing 中修正。");
        }
        return currentPath;
    }

    private static async Task WaitUntilReadyAsync(string syncthing, CancellationToken cancellationToken)
    {
        var deadline = DateTimeOffset.UtcNow.AddSeconds(30);
        Exception? lastError = null;
        while (DateTimeOffset.UtcNow < deadline)
        {
            cancellationToken.ThrowIfCancellationRequested();
            try
            {
                await RunAsync(syncthing, ["cli", "config", "dump-json"], cancellationToken);
                return;
            }
            catch (Exception error)
            {
                lastError = error;
                await Task.Delay(400, cancellationToken);
            }
        }
        throw new InvalidOperationException("Syncthing 在 30 秒内没有就绪。", lastError);
    }

    private static async Task EnsureProcessStartedAsync(string syncthing, CancellationToken cancellationToken)
    {
        using var readiness = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        readiness.CancelAfter(TimeSpan.FromSeconds(2));
        try
        {
            await RunAsync(syncthing, ["cli", "config", "dump-json"], readiness.Token);
            return;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            // The default instance is not ready; start it below.
        }
        catch (InvalidOperationException)
        {
            // The default instance is not ready; start it below.
        }
        Process.Start(new ProcessStartInfo
        {
            FileName = syncthing,
            UseShellExecute = false,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
            ArgumentList = { "serve", "--no-browser", "--no-console" },
        });
    }

    private static string? FindSyncthingBinary()
    {
        var configured = Environment.GetEnvironmentVariable("SELENE_SYNCTHING_PATH");
        if (!string.IsNullOrWhiteSpace(configured) && File.Exists(configured)) return Path.GetFullPath(configured);
        var path = Environment.GetEnvironmentVariable("PATH").OrEmpty().Split(Path.PathSeparator);
        foreach (var directory in path)
        {
            if (string.IsNullOrWhiteSpace(directory)) continue;
            var candidate = Path.Combine(directory.Trim(), "syncthing.exe");
            if (File.Exists(candidate)) return Path.GetFullPath(candidate);
        }
        try
        {
            var packages = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Microsoft", "WinGet", "Packages");
            if (Directory.Exists(packages))
            {
                return Directory.EnumerateFiles(packages, "syncthing.exe", SearchOption.AllDirectories)
                    .FirstOrDefault(file => file.Contains("Syncthing.Syncthing_", StringComparison.OrdinalIgnoreCase));
            }
        }
        catch
        {
            // A package directory can be inaccessible; the explicit override remains available.
        }
        return null;
    }

    private string? ResolveSyncthingBinary()
    {
        if (!string.IsNullOrWhiteSpace(cachedSyncthingBinary) && File.Exists(cachedSyncthingBinary))
        {
            return cachedSyncthingBinary;
        }
        cachedSyncthingBinary = FindSyncthingBinary();
        return cachedSyncthingBinary;
    }

    private static bool PathsEqual(string left, string right) =>
        Path.TrimEndingDirectorySeparator(Path.GetFullPath(left))
            .Equals(Path.TrimEndingDirectorySeparator(Path.GetFullPath(right)), StringComparison.OrdinalIgnoreCase);

    private static async Task InstallSyncthingAsync(CancellationToken cancellationToken)
    {
        var winget = FindOnPath("winget.exe")
            ?? throw new InvalidOperationException("未找到 Syncthing，也无法使用 winget 自动安装。");
        await RunAsync(winget,
            ["install", "--id", "Syncthing.Syncthing", "--exact", "--source", "winget",
             "--accept-source-agreements", "--accept-package-agreements", "--disable-interactivity"],
            cancellationToken);
        if (FindSyncthingBinary() is null) throw new InvalidOperationException("Syncthing 安装完成但尚未找到可执行文件，请重新打开 SELENE。");
    }

    private static string? FindOnPath(string executableName)
    {
        foreach (var directory in Environment.GetEnvironmentVariable("PATH").OrEmpty().Split(Path.PathSeparator))
        {
            if (string.IsNullOrWhiteSpace(directory)) continue;
            var candidate = Path.Combine(directory.Trim(), executableName);
            if (File.Exists(candidate)) return Path.GetFullPath(candidate);
        }
        return null;
    }

    private static async Task<string> RunAsync(string executable, IReadOnlyList<string> arguments, CancellationToken cancellationToken)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = executable,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        foreach (var argument in arguments) startInfo.ArgumentList.Add(argument);
        using var process = Process.Start(startInfo) ?? throw new InvalidOperationException("无法启动 Syncthing。");
        var outputTask = process.StandardOutput.ReadToEndAsync(cancellationToken);
        var errorTask = process.StandardError.ReadToEndAsync(cancellationToken);
        try
        {
            await process.WaitForExitAsync(cancellationToken);
        }
        catch (OperationCanceledException)
        {
            if (!process.HasExited) process.Kill(entireProcessTree: true);
            throw;
        }
        var output = await outputTask;
        var error = await errorTask;
        if (process.ExitCode != 0) throw new InvalidOperationException($"Syncthing 命令失败：{Sanitize(error)}");
        return output;
    }

    private static async Task<HttpRequest> ReadRequestAsync(Stream stream, CancellationToken cancellationToken)
    {
        using var reader = new StreamReader(stream, Encoding.UTF8, false, 4096, true);
        var requestLine = await reader.ReadLineAsync(cancellationToken)
            ?? throw new InvalidDataException("请求为空。");
        var parts = requestLine.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length < 2) throw new InvalidDataException("请求行无效。");
        var contentLength = 0;
        while (true)
        {
            var line = await reader.ReadLineAsync(cancellationToken)
                ?? throw new InvalidDataException("请求头不完整。");
            if (line.Length == 0) break;
            if (line.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase))
            {
                _ = int.TryParse(line["Content-Length:".Length..].Trim(), out contentLength);
            }
        }
        if (contentLength <= 0 || contentLength > MaximumRequestBytes) throw new InvalidDataException("请求正文大小无效。");
        var buffer = new char[contentLength];
        var read = 0;
        while (read < buffer.Length)
        {
            var count = await reader.ReadAsync(buffer.AsMemory(read, buffer.Length - read), cancellationToken);
            if (count == 0) throw new EndOfStreamException("请求正文不完整。");
            read += count;
        }
        return new HttpRequest(parts[0], parts[1], new string(buffer));
    }

    private static async Task WriteResponseAsync(Stream stream, int status, string body, CancellationToken cancellationToken)
    {
        var bytes = Encoding.UTF8.GetBytes(body);
        var reason = status == 200 ? "OK" : status == 403 ? "Forbidden" : "Not Found";
        var headers = Encoding.ASCII.GetBytes(
            $"HTTP/1.1 {status} {reason}\r\nContent-Type: application/json\r\nContent-Length: {bytes.Length}\r\nConnection: close\r\n\r\n");
        await stream.WriteAsync(headers, cancellationToken);
        await stream.WriteAsync(bytes, cancellationToken);
        await stream.FlushAsync(cancellationToken);
    }

    private static X509Certificate2 CreateCertificate()
    {
        using var key = RSA.Create(2048);
        var request = new CertificateRequest("CN=SELENE one-time pairing", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
        using var generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-1), DateTimeOffset.UtcNow.AddMinutes(10));
        return X509CertificateLoader.LoadPkcs12(
            generated.Export(X509ContentType.Pfx),
            null,
            X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);
    }

    private static IEnumerable<string> FindLanAddresses() => NetworkInterface.GetAllNetworkInterfaces()
        .Where(item => item.OperationalStatus == OperationalStatus.Up && item.NetworkInterfaceType != NetworkInterfaceType.Loopback)
        .Select(item => new { Interface = item, Properties = item.GetIPProperties() })
        .SelectMany(item => item.Properties.UnicastAddresses.Select(unicast => new
        {
            unicast.Address,
            Priority = (item.Properties.GatewayAddresses.Any(gateway => gateway.Address.AddressFamily == AddressFamily.InterNetwork) ? 0 : 4) +
                (item.Interface.NetworkInterfaceType is NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.Ethernet ? 0 : 1),
        }))
        .Where(item => item.Address.AddressFamily == AddressFamily.InterNetwork && IsPrivateAddress(item.Address))
        .OrderBy(item => item.Priority)
        .Select(item => item.Address.ToString())
        .Distinct(StringComparer.OrdinalIgnoreCase)
        .Take(4);

    private static bool IsPrivateAddress(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        if (address.AddressFamily != AddressFamily.InterNetwork) return false;
        var bytes = address.GetAddressBytes();
        return bytes[0] == 10 ||
               bytes[0] == 127 ||
               bytes[0] == 192 && bytes[1] == 168 ||
               bytes[0] == 172 && bytes[1] is >= 16 and <= 31 ||
               bytes[0] == 169 && bytes[1] == 254;
    }

    private static bool FixedTimeEquals(string? actual, string expected)
    {
        if (actual is null) return false;
        var actualBytes = Encoding.UTF8.GetBytes(actual);
        var expectedBytes = Encoding.UTF8.GetBytes(expected);
        return actualBytes.Length == expectedBytes.Length && CryptographicOperations.FixedTimeEquals(actualBytes, expectedBytes);
    }

    private static string Base64Url(byte[] value) => Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static string Sanitize(string value)
    {
        var text = value.Replace('\r', ' ').Replace('\n', ' ').Trim();
        return text.Length <= 240 ? text : text[..240];
    }

    private sealed record HttpRequest(string Method, string Path, string Body);

    [GeneratedRegex("^[A-Z2-7]{7}(-[A-Z2-7]{7}){7}$", RegexOptions.CultureInvariant)]
    private static partial Regex DeviceIdPattern();
}

file static class StringExtensions
{
    public static string OrEmpty(this string? value) => value ?? string.Empty;
}
