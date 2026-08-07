namespace Selene.Windows.Core;

public sealed class DesktopCollector
{
    private const string Version = "0.5.2";
    private readonly ForegroundSessionTracker foreground = new();
    private readonly ImmutableSnapshotWriter writer = new(Version);
    private readonly SemaphoreSlim captureGate = new(1, 1);
    private DateTimeOffset? previousCaptureAt;

    public void ObserveForeground(DesktopCaptureProfile profile) => foreground.Observe(profile);

    public async Task<SnapshotWriteResult> CaptureAsync(string exportDirectory, DesktopCaptureProfile profile)
    {
        await captureGate.WaitAsync().ConfigureAwait(false);
        try
        {
            return await Task.Run(() => Capture(exportDirectory, profile.Normalize())).ConfigureAwait(false);
        }
        finally
        {
            captureGate.Release();
        }
    }

    private SnapshotWriteResult Capture(string exportDirectory, DesktopCaptureProfile profile)
    {
        var capturedAt = DateTimeOffset.Now;
        var startAt = previousCaptureAt ?? capturedAt;
        if (startAt > capturedAt) startAt = capturedAt;
        foreground.Observe(profile, capturedAt);
        var sessions = foreground.CutAndDrain(capturedAt);
        try
        {
        var events = new List<SeleneEvent>();
        var capturedAtIso = ImmutableSnapshotWriter.Iso(capturedAt);
        var startAtIso = ImmutableSnapshotWriter.Iso(startAt);
        var exportedSessions = profile.ForegroundApplications
            ? sessions.Select(item => item.Sanitize(profile)).ToArray()
            : [];
        if (profile.ForegroundApplications)
        {
            events.Add(new SeleneEvent(
                $"SELENE-windows-screen-{startAt.ToUnixTimeMilliseconds()}",
                1,
                "screen-time",
                "selene",
                startAtIso,
                "Desktop activity snapshot",
                new Dictionary<string, object?>
                {
                    ["foregroundSeconds"] = exportedSessions.Sum(item => item.DurationSeconds),
                    ["activeAppCount"] = exportedSessions.Select(item => item.Observation.Application).Distinct(StringComparer.OrdinalIgnoreCase).Count(),
                    ["windowSeconds"] = Math.Max(0, (long)(capturedAt - startAt).TotalSeconds),
                },
                capturedAtIso,
                "coarse",
                ImmutableSnapshotWriter.Iso(capturedAt)
            ));
        }

        foreach (var session in exportedSessions.Where(item => item.DurationSeconds >= 5))
        {
            var values = new Dictionary<string, object?>
            {
                ["application"] = session.Observation.Application,
                ["durationSeconds"] = session.DurationSeconds,
                ["detail"] = "foreground-session",
            };
            if (!string.IsNullOrWhiteSpace(session.Observation.WindowTitle)) values["windowTitle"] = session.Observation.WindowTitle;
            if (!string.IsNullOrWhiteSpace(session.Observation.ExecutablePath)) values["executablePath"] = session.Observation.ExecutablePath;
            if (!string.IsNullOrWhiteSpace(session.Observation.BrowserUrl)) values["browserUrl"] = session.Observation.BrowserUrl;
            var privacy = session.Observation.WindowTitle is not null || session.Observation.ExecutablePath is not null || session.Observation.BrowserUrl is not null
                ? "sensitive"
                : "coarse";
            events.Add(new SeleneEvent(
                $"SELENE-windows-activity-{session.Id:N}",
                1,
                "activity",
                "selene",
                ImmutableSnapshotWriter.Iso(session.StartAt),
                $"App activity: {session.Observation.Application}",
                values,
                capturedAtIso,
                privacy,
                ImmutableSnapshotWriter.Iso(session.EndAt)
            ));
        }

        events.Add(CollectionProfileEvent(startAtIso, capturedAtIso, profile));
        if (profile.DeviceState)
        {
            events.Add(new SeleneEvent(
                $"SELENE-windows-device-{startAt.ToUnixTimeMilliseconds()}",
                1,
                "device",
                "selene",
                startAtIso,
                "Device state snapshot",
                SystemSnapshotCollector.DeviceValues(),
                capturedAtIso,
                "coarse",
                ImmutableSnapshotWriter.Iso(capturedAt)
            ));
        }
        if (profile.NetworkState)
        {
            events.Add(new SeleneEvent(
                $"SELENE-windows-network-{startAt.ToUnixTimeMilliseconds()}",
                1,
                "device",
                "selene",
                startAtIso,
                "Network state snapshot",
                SystemSnapshotCollector.NetworkValues(),
                capturedAtIso,
                "coarse",
                ImmutableSnapshotWriter.Iso(capturedAt)
            ));
        }

        var result = writer.WriteWindowsSnapshot(exportDirectory, events);
        foreground.Acknowledge(sessions);
        previousCaptureAt = capturedAt;
        return result;
        }
        catch
        {
            foreground.Restore(sessions);
            throw;
        }
    }

    private static SeleneEvent CollectionProfileEvent(string startAt, string capturedAt, DesktopCaptureProfile profile) => new(
        $"SELENE-windows-profile-{DateTimeOffset.Parse(startAt).ToUnixTimeMilliseconds()}",
        1,
        "collection-profile",
        "selene",
        startAt,
        "Desktop collection profile",
        new Dictionary<string, object?>
        {
            ["foregroundApplications"] = profile.ForegroundApplications,
            ["windowTitles"] = profile.WindowTitles,
            ["executablePaths"] = profile.ExecutablePaths,
            ["browserUrls"] = profile.BrowserUrls,
            ["deviceState"] = profile.DeviceState,
            ["networkState"] = profile.NetworkState,
        },
        capturedAt,
        profile.WindowTitles || profile.ExecutablePaths || profile.BrowserUrls ? "sensitive" : "coarse",
        capturedAt
    );
}
