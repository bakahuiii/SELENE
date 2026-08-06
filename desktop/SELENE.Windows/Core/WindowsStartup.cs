using Microsoft.Win32;

namespace Selene.Windows.Core;

public static class WindowsStartup
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "SELENE";

    public static bool CanConfigure
    {
        get
        {
            var path = Environment.ProcessPath;
            return !string.IsNullOrWhiteSpace(path)
                && path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
                && !Path.GetFileName(path).Equals("dotnet.exe", StringComparison.OrdinalIgnoreCase);
        }
    }

    public static void Apply(bool enabled)
    {
        if (!CanConfigure) return;
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKeyPath, writable: true);
            if (enabled)
            {
                key.SetValue(ValueName, $"\"{Environment.ProcessPath}\" --minimized", RegistryValueKind.String);
            }
            else
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
        }
        catch (Exception exception)
        {
            AppLogger.Error("startup_setting_failed", exception);
        }
    }
}
