namespace Selene.Windows.Core;

/** Explicit, local-only choices controlling which desktop metadata can leave the collector. */
public sealed record DesktopCaptureProfile(
    bool ForegroundApplications,
    bool WindowTitles,
    bool ExecutablePaths,
    bool BrowserUrls,
    bool DeviceState,
    bool NetworkState
)
{
    public static DesktopCaptureProfile Default => new(
        ForegroundApplications: true,
        WindowTitles: false,
        ExecutablePaths: false,
        BrowserUrls: false,
        DeviceState: true,
        NetworkState: true
    );

    public DesktopCaptureProfile Normalize() => this with
    {
        WindowTitles = ForegroundApplications && WindowTitles,
        ExecutablePaths = ForegroundApplications && ExecutablePaths,
        BrowserUrls = ForegroundApplications && WindowTitles && BrowserUrls,
    };
}
