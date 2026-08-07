using System.Diagnostics;
using System.Text;

namespace Selene.Windows.Core;

public sealed record ForegroundObservation(
    string Application,
    string? WindowTitle,
    string? ExecutablePath,
    string? BrowserUrl
);

public sealed record ForegroundSession(
    Guid Id,
    ForegroundObservation Observation,
    DateTimeOffset StartAt,
    DateTimeOffset EndAt
)
{
    public long DurationSeconds => Math.Max(0, (long)(EndAt - StartAt).TotalSeconds);

    public ForegroundSession Sanitize(DesktopCaptureProfile profile) => this with
    {
        Observation = Observation with
        {
            WindowTitle = profile.WindowTitles ? Observation.WindowTitle : null,
            ExecutablePath = profile.ExecutablePaths ? Observation.ExecutablePath : null,
            BrowserUrl = profile.BrowserUrls ? Observation.BrowserUrl : null,
        },
    };
}

public sealed class ForegroundSessionTracker
{
    private readonly object sync = new();
    private readonly List<ForegroundSession> completed;
    private readonly List<ForegroundSession> inFlight = [];
    private readonly ForegroundSessionStore store;
    private readonly string ownProcessName = Process.GetCurrentProcess().ProcessName;
    private ForegroundObservation? activeObservation;
    private DateTimeOffset activeSince;
    private DesktopCaptureProfile profile = DesktopCaptureProfile.Default;

    public ForegroundSessionTracker(ForegroundSessionStore? store = null)
    {
        this.store = store ?? new ForegroundSessionStore();
        completed = this.store.Load().ToList();
    }

    public void Observe(DesktopCaptureProfile captureProfile, DateTimeOffset? observedAt = null)
    {
        lock (sync)
        {
            var now = observedAt ?? DateTimeOffset.Now;
            if (!Equals(profile, captureProfile))
            {
                ObserveCore(now);
                profile = captureProfile.Normalize();
                if (!profile.ForegroundApplications)
                {
                    completed.Clear();
                }
                else
                {
                    for (var index = 0; index < completed.Count; index += 1)
                    {
                        completed[index] = completed[index].Sanitize(profile);
                    }
                }
                PersistPending();
            }
            ObserveCore(now);
        }
    }

    public IReadOnlyList<ForegroundSession> CutAndDrain(DateTimeOffset now)
    {
        lock (sync)
        {
            ObserveCore(now);
            CloseActive(now);
            if (completed.Count == 0) return [];
            var output = completed.ToArray();
            completed.Clear();
            inFlight.AddRange(output);
            return output;
        }
    }

    /** Clears only sessions whose immutable snapshot has been written and flushed. */
    public void Acknowledge(IEnumerable<ForegroundSession> sessions)
    {
        lock (sync)
        {
            var ids = sessions.Select(item => item.Id).ToHashSet();
            inFlight.RemoveAll(item => ids.Contains(item.Id));
            PersistPending();
        }
    }

    /** Requeues sessions when the immutable snapshot write fails. */
    public void Restore(IEnumerable<ForegroundSession> sessions)
    {
        lock (sync)
        {
            var ids = sessions.Select(item => item.Id).ToHashSet();
            var failed = inFlight.Where(item => ids.Contains(item.Id)).ToArray();
            inFlight.RemoveAll(item => ids.Contains(item.Id));
            completed.InsertRange(0, failed);
            completed.Sort((left, right) => left.StartAt.CompareTo(right.StartAt));
            PersistPending();
        }
    }

    private void ObserveCore(DateTimeOffset now)
    {
        var observation = CurrentForegroundObservation();
        if (Equals(observation, activeObservation)) return;
        CloseActive(now);
        if (observation is not null)
        {
            activeObservation = observation;
            activeSince = now;
        }
    }

    private void CloseActive(DateTimeOffset now)
    {
        if (activeObservation is not null && now > activeSince)
        {
            completed.Add(new ForegroundSession(Guid.NewGuid(), activeObservation, activeSince, now));
            PersistPending();
        }
        activeObservation = null;
    }

    private void PersistPending() => store.Save(completed.Concat(inFlight));

    private ForegroundObservation? CurrentForegroundObservation()
    {
        if (!profile.ForegroundApplications) return null;
        try
        {
            var window = NativeMethods.GetForegroundWindow();
            if (window == IntPtr.Zero) return null;
            NativeMethods.GetWindowThreadProcessId(window, out var processId);
            if (processId == 0) return null;
            using var process = Process.GetProcessById((int)processId);
            var name = process.ProcessName.Trim();
            if (string.IsNullOrWhiteSpace(name) || string.Equals(name, ownProcessName, StringComparison.OrdinalIgnoreCase)) return null;
            if (name.Length > 96) name = name[..96];
            var title = profile.WindowTitles ? WindowTitle(window) : null;
            var executablePath = profile.ExecutablePaths ? ExecutablePath(process) : null;
            var browserUrl = profile.BrowserUrls ? BrowserPageReader.TryReadAddress(window, name) : null;
            return new ForegroundObservation(name, title, executablePath, browserUrl);
        }
        catch
        {
            return null;
        }
    }

    private static string? WindowTitle(IntPtr window)
    {
        var length = NativeMethods.GetWindowTextLength(window);
        if (length <= 0) return null;
        var buffer = new StringBuilder(Math.Min(length + 1, 2_049));
        NativeMethods.GetWindowText(window, buffer, buffer.Capacity);
        var title = buffer.ToString().Trim();
        return string.IsNullOrWhiteSpace(title) ? null : title;
    }

    private static string? ExecutablePath(Process process)
    {
        try
        {
            var path = process.MainModule?.FileName?.Trim();
            if (string.IsNullOrWhiteSpace(path)) return null;
            return path.Length <= 2_048 ? path : path[..2_048];
        }
        catch
        {
            return null;
        }
    }
}
