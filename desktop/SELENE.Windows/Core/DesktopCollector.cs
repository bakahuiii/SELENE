namespace Selene.Windows.Core;

public sealed class DesktopCollector
{
    private const string Version = "0.2.0";
    private readonly ForegroundSessionTracker foreground = new();
    private readonly ImmutableSnapshotWriter writer = new(Version);
    private DateTimeOffset? previousCaptureAt;

    public void ObserveForeground() => foreground.Observe();

    public Task<SnapshotWriteResult> CaptureAsync(string exportDirectory) => Task.Run(() => Capture(exportDirectory));

    private SnapshotWriteResult Capture(string exportDirectory)
    {
        var capturedAt = DateTimeOffset.UtcNow;
        var startAt = previousCaptureAt ?? capturedAt;
        if (startAt > capturedAt) startAt = capturedAt;
        var sessions = foreground.CutAndDrain(capturedAt);
        previousCaptureAt = capturedAt;
        var events = new List<SeleneEvent>();
        var capturedAtIso = ImmutableSnapshotWriter.Iso(capturedAt);
        var startAtIso = ImmutableSnapshotWriter.Iso(startAt);
        var foregroundSeconds = sessions.Sum(item => item.DurationSeconds);

        events.Add(new SeleneEvent(
            $"SELENE-windows-screen-{startAt.ToUnixTimeMilliseconds()}",
            1,
            "screen-time",
            "selene",
            startAtIso,
            "Desktop activity snapshot",
            new Dictionary<string, object?>
            {
                ["foregroundSeconds"] = foregroundSeconds,
                ["activeAppCount"] = sessions.Select(item => item.Application).Distinct(StringComparer.OrdinalIgnoreCase).Count(),
                ["windowSeconds"] = Math.Max(0, (long)(capturedAt - startAt).TotalSeconds),
            },
            capturedAtIso,
            "coarse",
            ImmutableSnapshotWriter.Iso(capturedAt)
        ));

        foreach (var session in sessions.Where(item => item.DurationSeconds >= 5))
        {
            events.Add(new SeleneEvent(
                $"SELENE-windows-activity-{ImmutableSnapshotWriter.StableToken(session.Application)}-{session.StartAt.ToUnixTimeMilliseconds()}",
                1,
                "activity",
                "selene",
                ImmutableSnapshotWriter.Iso(session.StartAt),
                $"App activity: {session.Application}",
                new Dictionary<string, object?>
                {
                    ["application"] = session.Application,
                    ["durationSeconds"] = session.DurationSeconds,
                    ["detail"] = "foreground-session",
                },
                capturedAtIso,
                "coarse",
                ImmutableSnapshotWriter.Iso(session.EndAt)
            ));
        }

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

        return writer.WriteWindowsSnapshot(exportDirectory, events);
    }
}
