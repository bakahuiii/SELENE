namespace Selene.Windows.Core;

public sealed class DesktopCollector
{
    private const string Version = "0.3.0";
    private readonly ForegroundSessionTracker foreground = new();
    private readonly ImmutableSnapshotWriter writer = new(Version);
    private readonly SemaphoreSlim captureGate = new(1, 1);
    private DateTimeOffset? previousCaptureAt;

    public void ObserveForeground() => foreground.Observe();

    public async Task<SnapshotWriteResult> CaptureAsync(string exportDirectory)
    {
        await captureGate.WaitAsync().ConfigureAwait(false);
        try
        {
            return await Task.Run(() => Capture(exportDirectory)).ConfigureAwait(false);
        }
        finally
        {
            captureGate.Release();
        }
    }

    private SnapshotWriteResult Capture(string exportDirectory)
    {
        var capturedAt = DateTimeOffset.Now;
        var startAt = previousCaptureAt ?? capturedAt;
        if (startAt > capturedAt) startAt = capturedAt;
        var sessions = foreground.CutAndDrain(capturedAt);
        try
        {
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

        var result = writer.WriteWindowsSnapshot(exportDirectory, events);
        previousCaptureAt = capturedAt;
        return result;
        }
        catch
        {
            foreground.Restore(sessions);
            throw;
        }
    }
}
