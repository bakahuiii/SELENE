using System.Diagnostics;
using System.Runtime.InteropServices;

namespace Selene.Windows.Core;

public sealed record ForegroundSession(string Application, DateTimeOffset StartAt, DateTimeOffset EndAt)
{
    public long DurationSeconds => Math.Max(0, (long)(EndAt - StartAt).TotalSeconds);
}

public sealed class ForegroundSessionTracker
{
    private readonly List<ForegroundSession> completed = [];
    private readonly string ownProcessName = Process.GetCurrentProcess().ProcessName;
    private string? activeApplication;
    private DateTimeOffset activeSince;

    public void Observe(DateTimeOffset? observedAt = null)
    {
        var now = observedAt ?? DateTimeOffset.UtcNow;
        var application = CurrentForegroundApplication();
        if (string.Equals(application, activeApplication, StringComparison.OrdinalIgnoreCase)) return;
        CloseActive(now);
        if (!string.IsNullOrWhiteSpace(application))
        {
            activeApplication = application;
            activeSince = now;
        }
    }

    public IReadOnlyList<ForegroundSession> CutAndDrain(DateTimeOffset now)
    {
        Observe(now);
        if (!string.IsNullOrWhiteSpace(activeApplication) && now > activeSince)
        {
            completed.Add(new ForegroundSession(activeApplication, activeSince, now));
            activeSince = now;
        }
        var output = completed.ToArray();
        completed.Clear();
        return output;
    }

    private void CloseActive(DateTimeOffset now)
    {
        if (!string.IsNullOrWhiteSpace(activeApplication) && now > activeSince)
        {
            completed.Add(new ForegroundSession(activeApplication, activeSince, now));
        }
        activeApplication = null;
    }

    private string? CurrentForegroundApplication()
    {
        try
        {
            var window = NativeMethods.GetForegroundWindow();
            if (window == IntPtr.Zero) return null;
            NativeMethods.GetWindowThreadProcessId(window, out var processId);
            if (processId == 0) return null;
            using var process = Process.GetProcessById((int)processId);
            var name = process.ProcessName.Trim();
            if (string.IsNullOrWhiteSpace(name) || string.Equals(name, ownProcessName, StringComparison.OrdinalIgnoreCase)) return null;
            return name.Length > 96 ? name[..96] : name;
        }
        catch
        {
            return null;
        }
    }
}
