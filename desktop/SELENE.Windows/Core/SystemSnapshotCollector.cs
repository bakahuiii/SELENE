using System.Net.NetworkInformation;
using System.Windows.Forms;

namespace Selene.Windows.Core;

public static class SystemSnapshotCollector
{
    public static Dictionary<string, object?> DeviceValues()
    {
        var power = SystemInformation.PowerStatus;
        var values = new Dictionary<string, object?>
        {
            ["idleSeconds"] = IdleSeconds(),
            ["powerLine"] = power.PowerLineStatus == PowerLineStatus.Online,
            ["charging"] = power.BatteryChargeStatus.HasFlag(BatteryChargeStatus.Charging),
        };
        if (power.BatteryLifePercent is >= 0 and <= 1) values["batteryPercent"] = (int)Math.Round(power.BatteryLifePercent * 100);
        return values;
    }

    public static Dictionary<string, object?> NetworkValues()
    {
        var transports = NetworkInterface.GetAllNetworkInterfaces()
            .Where(item => item.OperationalStatus == OperationalStatus.Up && item.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .Select(MapTransport)
            .Where(item => item is not null)
            .Distinct(StringComparer.Ordinal)
            .Order(StringComparer.Ordinal)
            .ToArray();
        return new Dictionary<string, object?>
        {
            ["connected"] = NetworkInterface.GetIsNetworkAvailable(),
            ["transport"] = transports.Length == 0 ? "none" : string.Join(",", transports),
        };
    }

    private static int IdleSeconds()
    {
        var input = new NativeMethods.LastInputInfo { cbSize = (uint)System.Runtime.InteropServices.Marshal.SizeOf<NativeMethods.LastInputInfo>() };
        if (!NativeMethods.GetLastInputInfo(ref input)) return 0;
        var now = unchecked((uint)Environment.TickCount);
        return (int)(unchecked(now - input.dwTime) / 1000);
    }

    private static string? MapTransport(NetworkInterface item) => item.NetworkInterfaceType switch
    {
        NetworkInterfaceType.Wireless80211 => "wifi",
        NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet or NetworkInterfaceType.FastEthernetFx or NetworkInterfaceType.FastEthernetT => "ethernet",
        NetworkInterfaceType.Ppp or NetworkInterfaceType.Tunnel => "vpn",
        _ => "other",
    };
}
