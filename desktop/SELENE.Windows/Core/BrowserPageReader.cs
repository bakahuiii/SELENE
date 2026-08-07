using System.Windows.Automation;

namespace Selene.Windows.Core;

/** Best-effort browser address-bar reader. It never reads page body content. */
internal static class BrowserPageReader
{
    private static readonly HashSet<string> BrowserProcesses = new(StringComparer.OrdinalIgnoreCase)
    {
        "chrome", "msedge", "firefox", "brave", "opera", "vivaldi",
    };

    public static string? TryReadAddress(IntPtr window, string processName)
    {
        if (!BrowserProcesses.Contains(processName)) return null;
        try
        {
            var root = AutomationElement.FromHandle(window);
            var edits = root.FindAll(
                TreeScope.Descendants,
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit)
            );
            foreach (AutomationElement edit in edits)
            {
                var name = edit.Current.Name ?? string.Empty;
                if (!name.Contains("address", StringComparison.OrdinalIgnoreCase)
                    && !name.Contains("search", StringComparison.OrdinalIgnoreCase)
                    && !name.Contains("网址", StringComparison.OrdinalIgnoreCase)
                    && !name.Contains("地址", StringComparison.OrdinalIgnoreCase)) continue;
                if (edit.TryGetCurrentPattern(ValuePattern.Pattern, out var pattern)
                    && pattern is ValuePattern valuePattern)
                {
                    return Normalize(valuePattern.Current.Value);
                }
                return Normalize(name);
            }
        }
        catch
        {
            // Browser accessibility trees are version- and policy-dependent.
        }
        return null;
    }

    private static string? Normalize(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        var output = value.Trim();
        return output.Length <= 2_048 ? output : output[..2_048];
    }
}
