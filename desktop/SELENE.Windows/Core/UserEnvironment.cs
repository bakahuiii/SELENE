using Microsoft.Win32;

namespace Selene.Windows.Core;

internal static class UserEnvironment
{
    private const string EnvironmentKeyPath = "Environment";

    public static bool SetPathIfChanged(string name, string path)
    {
        var normalized = Path.GetFullPath(path);
        var current = Environment.GetEnvironmentVariable(name, EnvironmentVariableTarget.User);
        Environment.SetEnvironmentVariable(name, normalized, EnvironmentVariableTarget.Process);
        if (PathsEqual(current, normalized)) return false;

        using var key = Registry.CurrentUser.CreateSubKey(EnvironmentKeyPath, writable: true)
            ?? throw new InvalidOperationException("无法打开当前用户环境变量注册表项。");
        key.SetValue(name, normalized, RegistryValueKind.String);

        // The registry value is durable immediately. Notify other processes in the
        // background because a hung top-level window can delay this broadcast.
        _ = Task.Run(() => NativeMethods.BroadcastEnvironmentChanged());
        return true;
    }

    private static bool PathsEqual(string? left, string right)
    {
        if (string.IsNullOrWhiteSpace(left)) return false;
        try
        {
            return Path.TrimEndingDirectorySeparator(Path.GetFullPath(left))
                .Equals(Path.TrimEndingDirectorySeparator(right), StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }
}
