namespace Selene.Windows.Core;

public sealed record DesktopSettings(
    string? ExportDirectory,
    int CaptureIntervalMinutes,
    bool AutomaticCollection,
    bool StartWithWindows
)
{
    public static DesktopSettings Default => new(null, 15, true, false);

    public DesktopSettings Normalize()
    {
        var interval = CaptureIntervalMinutes is 5 or 15 or 30 or 60 ? CaptureIntervalMinutes : 15;
        var directory = string.IsNullOrWhiteSpace(ExportDirectory) ? null : Path.GetFullPath(ExportDirectory);
        return this with { ExportDirectory = directory, CaptureIntervalMinutes = interval };
    }
}
