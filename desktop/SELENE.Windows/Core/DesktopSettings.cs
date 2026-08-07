namespace Selene.Windows.Core;

public sealed record DesktopSettings(
    string? ExportDirectory,
    string? SyncInboxDirectory,
    int CaptureIntervalMinutes,
    bool AutomaticCollection,
    bool StartWithWindows,
    DesktopCaptureProfile? CaptureProfile = null
)
{
    public static DesktopSettings Default => new(null, DefaultSyncInboxDirectory(), 15, true, false, DesktopCaptureProfile.Default);

    public DesktopSettings Normalize()
    {
        var interval = CaptureIntervalMinutes is 5 or 15 or 30 or 60 ? CaptureIntervalMinutes : 15;
        var directory = string.IsNullOrWhiteSpace(ExportDirectory) ? null : Path.GetFullPath(ExportDirectory);
        var inbox = string.IsNullOrWhiteSpace(SyncInboxDirectory)
            ? DefaultSyncInboxDirectory()
            : Path.GetFullPath(SyncInboxDirectory);
        return this with
        {
            ExportDirectory = directory,
            SyncInboxDirectory = inbox,
            CaptureIntervalMinutes = interval,
            CaptureProfile = (CaptureProfile ?? DesktopCaptureProfile.Default).Normalize(),
        };
    }

    public static string DefaultSyncInboxDirectory() => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "SELENE",
        "Inbox"
    );
}
