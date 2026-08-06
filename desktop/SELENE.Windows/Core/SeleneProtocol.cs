using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Selene.Windows.Core;

public sealed record SeleneEvent(
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("version")] int Version,
    [property: JsonPropertyName("kind")] string Kind,
    [property: JsonPropertyName("source")] string Source,
    [property: JsonPropertyName("startAt")] string StartAt,
    [property: JsonPropertyName("title")] string Title,
    [property: JsonPropertyName("values")] Dictionary<string, object?> Values,
    [property: JsonPropertyName("capturedAt")] string CapturedAt,
    [property: JsonPropertyName("privacy")] string Privacy,
    [property: JsonPropertyName("endAt")] string? EndAt = null
);

public sealed record SnapshotWriteResult(string SnapshotDirectory, int EventCount);

public sealed class ImmutableSnapshotWriter
{
    public const string Schema = "selene-context-events/v1";
    private readonly string version;
    private readonly Func<DateTimeOffset> utcNow;
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public ImmutableSnapshotWriter(string version, Func<DateTimeOffset>? utcNow = null)
    {
        this.version = version;
        this.utcNow = utcNow ?? (() => DateTimeOffset.UtcNow);
    }

    public SnapshotWriteResult WriteWindowsSnapshot(string exportRoot, IReadOnlyList<SeleneEvent> events)
    {
        if (string.IsNullOrWhiteSpace(exportRoot)) throw new ArgumentException("SELENE export folder is required.", nameof(exportRoot));
        Directory.CreateDirectory(exportRoot);
        var generatedAt = utcNow().ToUniversalTime();
        var snapshotDirectory = CreateSnapshotDirectory(exportRoot, generatedAt);
        var document = new
        {
            schema = Schema,
            device = new { platform = "windows" },
            generatedAt = Iso(generatedAt),
            producer = new { name = "SELENE", version, layout = "immutable-snapshot-v1" },
            events,
        };
        var target = Path.Combine(snapshotDirectory, "context-events.json");
        using (var stream = new FileStream(target, FileMode.CreateNew, FileAccess.Write, FileShare.None))
        {
            JsonSerializer.Serialize(stream, document, JsonOptions);
            stream.Flush(flushToDisk: true);
        }
        AppLogger.Info("snapshot_written", $"events={events.Count}; folder={Path.GetFileName(snapshotDirectory)}");
        return new SnapshotWriteResult(snapshotDirectory, events.Count);
    }

    private static string CreateSnapshotDirectory(string exportRoot, DateTimeOffset generatedAt)
    {
        for (var attempt = 0; attempt < 1000; attempt += 1)
        {
            var timestamp = generatedAt.AddMilliseconds(attempt).ToString("yyyyMMdd'T'HHmmssfff'Z'", CultureInfo.InvariantCulture);
            var candidate = Path.Combine(exportRoot, $"SELENE-v1-{timestamp}");
            if (Directory.Exists(candidate)) continue;
            Directory.CreateDirectory(candidate);
            return candidate;
        }
        throw new IOException("Unable to allocate a new immutable SELENE snapshot directory.");
    }

    public static string Iso(DateTimeOffset value) => value.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);

    public static string StableToken(string value)
    {
        var digest = SHA256.HashData(Encoding.UTF8.GetBytes(value));
        return Convert.ToHexString(digest.AsSpan(0, 6)).ToLowerInvariant();
    }
}
