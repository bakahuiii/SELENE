using System.Text.Json;

namespace Selene.Windows.Core;

public sealed class SettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private readonly string settingsPath;

    public SettingsStore()
    {
        var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SELENE");
        settingsPath = Path.Combine(root, "desktop-settings.json");
    }

    public DesktopSettings Load()
    {
        try
        {
            if (!File.Exists(settingsPath)) return DesktopSettings.Default;
            return JsonSerializer.Deserialize<DesktopSettings>(File.ReadAllText(settingsPath), JsonOptions) ?? DesktopSettings.Default;
        }
        catch (Exception exception)
        {
            AppLogger.Error("settings_load_failed", exception);
            return DesktopSettings.Default;
        }
    }

    public void Save(DesktopSettings settings)
    {
        var directory = Path.GetDirectoryName(settingsPath)!;
        Directory.CreateDirectory(directory);
        var temporaryPath = $"{settingsPath}.tmp";
        File.WriteAllText(temporaryPath, JsonSerializer.Serialize(settings.Normalize(), JsonOptions));
        File.Move(temporaryPath, settingsPath, true);
    }
}
