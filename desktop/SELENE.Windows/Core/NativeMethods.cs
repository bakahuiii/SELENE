using System.Runtime.InteropServices;

namespace Selene.Windows.Core;

internal static class NativeMethods
{
    [DllImport("user32.dll")]
    internal static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    internal static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool GetLastInputInfo(ref LastInputInfo lastInputInfo);

    [StructLayout(LayoutKind.Sequential)]
    internal struct LastInputInfo
    {
        public uint cbSize;
        public uint dwTime;
    }
}
