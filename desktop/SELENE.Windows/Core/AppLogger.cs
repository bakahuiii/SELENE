using System.Text;

namespace Selene.Windows.Core;

public static class AppLogger
{
    private static readonly object Sync = new();

    public static void Info(string eventName, string? detail = null) => Write("INFO", eventName, detail);

    public static void Error(string eventName, Exception exception) => Write("ERROR", eventName, $"{exception.GetType().Name}: {exception.Message}");

    private static void Write(string level, string eventName, string? detail)
    {
        try
        {
            var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SELENE", "logs");
            Directory.CreateDirectory(root);
            var path = Path.Combine(root, $"selene-{DateTime.UtcNow:yyyyMMdd}.log");
            var line = $"{DateTimeOffset.UtcNow:O} [{level}] {eventName}{(string.IsNullOrWhiteSpace(detail) ? string.Empty : $" {detail}")}{Environment.NewLine}";
            lock (Sync) File.AppendAllText(path, line, Encoding.UTF8);
        }
        catch
        {
            // Logging must never prevent local collection.
        }
    }
}
