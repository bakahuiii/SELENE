using System.Text.Json;
using System.Net.Http;
using Selene.Windows.Core;

var root = Path.Combine(Path.GetTempPath(), $"selene-contract-{Guid.NewGuid():N}");
Directory.CreateDirectory(root);
try
{
    var timestamp = new DateTimeOffset(2026, 8, 6, 12, 0, 0, TimeSpan.Zero);
    var writer = new ImmutableSnapshotWriter("0.5.2", () => timestamp);
    var first = writer.WriteWindowsSnapshot(root, [Event("first", timestamp)]);
    var firstPath = Path.Combine(first.SnapshotDirectory, "context-events.json");
    var firstContent = File.ReadAllText(firstPath);

    using var document = JsonDocument.Parse(firstContent);
    Expect(document.RootElement.GetProperty("schema").GetString() == "selene-context-events/v1", "schema");
    Expect(document.RootElement.GetProperty("device").GetProperty("platform").GetString() == "windows", "device platform");
    Expect(document.RootElement.GetProperty("producer").GetProperty("name").GetString() == "SELENE", "producer");
    Expect(document.RootElement.GetProperty("events")[0].GetProperty("source").GetString() == "selene", "event source");
    Expect(document.RootElement.GetProperty("generatedAt").GetString() == ImmutableSnapshotWriter.Iso(timestamp), "system timezone timestamp");
    Expect(!firstContent.Contains('\n'), "compact JSON");

    var second = writer.WriteWindowsSnapshot(root, [Event("second", timestamp.AddMinutes(1))]);
    Expect(!string.Equals(first.SnapshotDirectory, second.SnapshotDirectory, StringComparison.Ordinal), "immutable unique directory");
    Expect(File.ReadAllText(firstPath) == firstContent, "first snapshot unchanged");

    var profile = new DesktopCaptureProfile(
        ForegroundApplications: false,
        WindowTitles: true,
        ExecutablePaths: true,
        BrowserUrls: true,
        DeviceState: true,
        NetworkState: true
    ).Normalize();
    Expect(!profile.WindowTitles && !profile.ExecutablePaths && !profile.BrowserUrls, "profile parent boundary");

    var storedSessionPath = Path.Combine(root, "pending-sessions.json");
    var store = new ForegroundSessionStore(storedSessionPath);
    var pending = new ForegroundSession(
        Guid.NewGuid(),
        new ForegroundObservation("browser", "Private title", Path.Combine("fixtures", "apps", "browser.exe"), "https://example.test/private"),
        timestamp,
        timestamp.AddMinutes(3)
    );
    store.Save([pending]);
    var restored = store.Load();
    Expect(restored.Count == 1 && restored[0].Id == pending.Id, "pending foreground session restored");
    var sanitized = restored[0].Sanitize(DesktopCaptureProfile.Default);
    Expect(sanitized.Observation.WindowTitle is null && sanitized.Observation.ExecutablePath is null && sanitized.Observation.BrowserUrl is null, "profile sanitizes pending metadata");
    store.Save([]);
    Expect(store.Load().Count == 0, "pending session acknowledged");
    Console.WriteLine("SELENE Windows contract tests passed.");
}
finally
{
    Directory.Delete(root, recursive: true);
}

if (args.Contains("--pairing-smoke", StringComparer.OrdinalIgnoreCase))
{
    await RunPairingSmokeAsync();
}

static SeleneEvent Event(string id, DateTimeOffset at) => new(
    id,
    1,
    "device",
    "selene",
    ImmutableSnapshotWriter.Iso(at),
    "Test event",
    new Dictionary<string, object?> { ["value"] = 1 },
    ImmutableSnapshotWriter.Iso(at),
    "coarse"
);

static void Expect(bool condition, string name)
{
    if (!condition) throw new InvalidOperationException($"Assertion failed: {name}");
}

static async Task RunPairingSmokeAsync()
{
    var inbox = Environment.GetEnvironmentVariable("SELENE_PAIRING_SMOKE_INBOX");
    if (string.IsNullOrWhiteSpace(inbox)) throw new InvalidOperationException("SELENE_PAIRING_SMOKE_INBOX is required.");
    using var pairing = new SyncthingPairingService();
    var offer = await pairing.BeginPairingAsync(inbox);
    const string prefix = "selene-pair:v1:";
    Expect(offer.Code.StartsWith(prefix, StringComparison.Ordinal), "pairing code prefix");
    Expect(offer.Code.Length < 2_000, "pairing code fits a practical QR");
    var encoded = offer.Code[prefix.Length..].Replace('-', '+').Replace('_', '/');
    encoded = encoded.PadRight(((encoded.Length + 3) / 4) * 4, '=');
    using var payload = JsonDocument.Parse(Convert.FromBase64String(encoded));
    var json = payload.RootElement;
    Expect(json.GetProperty("schema").GetString() == "selene-pair/v1", "pairing schema");
    Expect(json.GetProperty("folderId").GetString() == SyncthingPairingService.FolderId, "pairing folder");
    Expect(json.GetProperty("token").GetString()?.Length >= 24, "pairing token entropy");
    Expect(json.GetProperty("certificateSha256").GetString()?.Length == 64, "pairing certificate pin");
    Expect(json.GetProperty("endpoints").GetArrayLength() > 0, "pairing endpoint");
    Expect(!offer.Code.Contains("apikey", StringComparison.OrdinalIgnoreCase), "pairing excludes GUI API key");
    var endpoint = json.GetProperty("endpoints")[0].GetString() ?? throw new InvalidOperationException("Pairing endpoint is empty.");
    var expectedPin = json.GetProperty("certificateSha256").GetString();
    using var handler = new HttpClientHandler
    {
        UseProxy = false,
        ServerCertificateCustomValidationCallback = (_, certificate, _, _) =>
            certificate is not null &&
            Convert.ToHexString(certificate.GetCertHash(System.Security.Cryptography.HashAlgorithmName.SHA256))
                .Equals(expectedPin, StringComparison.OrdinalIgnoreCase),
    };
    using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(5) };
    var invalidEnrollment = JsonSerializer.Serialize(new
    {
        schema = "selene-enroll/v1",
        token = "wrong-" + Guid.NewGuid().ToString("N"),
        deviceId = json.GetProperty("windowsDeviceId").GetString(),
        folderId = SyncthingPairingService.FolderId,
    });
    using var response = await client.PostAsync(endpoint, new StringContent(invalidEnrollment, System.Text.Encoding.UTF8, "application/json"));
    Expect(response.StatusCode == System.Net.HttpStatusCode.Forbidden, "wrong pairing token rejected");
    pairing.CancelActivePairing();
    try
    {
        await offer.Completion;
        throw new InvalidOperationException("Cancelled pairing unexpectedly completed.");
    }
    catch (OperationCanceledException)
    {
        Console.WriteLine("SELENE Windows pairing smoke test passed.");
    }
}
