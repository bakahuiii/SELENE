using System.Text.Json;

namespace Selene.Windows.Core;

/**
 * Stores unacknowledged foreground sessions locally so a crash only risks the
 * currently active sampling interval. Snapshot writes acknowledge sessions
 * only after the immutable JSON file has been flushed to disk.
 */
public sealed class ForegroundSessionStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = false };
    private readonly string path;

    public ForegroundSessionStore(string? path = null)
    {
        var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SELENE");
        this.path = path ?? Path.Combine(root, "pending-foreground-sessions.json");
    }

    public IReadOnlyList<ForegroundSession> Load()
    {
        try
        {
            if (!File.Exists(path)) return [];
            var sessions = JsonSerializer.Deserialize<List<ForegroundSession>>(File.ReadAllText(path), JsonOptions) ?? [];
            return sessions
                .Where(item => item.EndAt >= item.StartAt)
                .GroupBy(item => item.Id)
                .Select(group => group.First())
                .OrderBy(item => item.StartAt)
                .ToArray();
        }
        catch (Exception exception)
        {
            AppLogger.Error("foreground_session_restore_failed", exception);
            return [];
        }
    }

    public void Save(IEnumerable<ForegroundSession> sessions)
    {
        try
        {
            var directory = Path.GetDirectoryName(path)!;
            Directory.CreateDirectory(directory);
            var temporary = $"{path}.tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(sessions.OrderBy(item => item.StartAt), JsonOptions));
            File.Move(temporary, path, true);
        }
        catch (Exception exception)
        {
            AppLogger.Error("foreground_session_checkpoint_failed", exception);
        }
    }
}
