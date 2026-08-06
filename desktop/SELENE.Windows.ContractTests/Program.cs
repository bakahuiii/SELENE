using System.Text.Json;
using Selene.Windows.Core;

var root = Path.Combine(Path.GetTempPath(), $"selene-contract-{Guid.NewGuid():N}");
Directory.CreateDirectory(root);
try
{
    var timestamp = new DateTimeOffset(2026, 8, 6, 12, 0, 0, TimeSpan.Zero);
    var writer = new ImmutableSnapshotWriter("0.3.0", () => timestamp);
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
    Console.WriteLine("SELENE Windows contract tests passed.");
}
finally
{
    Directory.Delete(root, recursive: true);
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
