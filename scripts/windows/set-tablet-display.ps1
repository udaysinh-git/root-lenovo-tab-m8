# Set the spacedesk (tablet) display on Windows to a given resolution and orientation.
# Usage: set-tablet-display.ps1 [-Width 1280] [-Height 800] [-Orientation 2]   (0 = landscape, 2 = landscape flipped)
param([int]$Width = 1280, [int]$Height = 800, [int]$Orientation = 2)

Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class TabletDisplay {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct DEVMODE {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
        public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
        public int dmFields, dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput;
        public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
        public short dmLogPixels;
        public int dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
        public int dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct DISPLAY_DEVICE {
        public int cb;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceString;
        public int StateFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceID;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceKey;
    }
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool EnumDisplayDevices(string dev, int i, ref DISPLAY_DEVICE dd, int flags);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool EnumDisplaySettings(string dev, int mode, ref DEVMODE dm);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int ChangeDisplaySettingsEx(string dev, ref DEVMODE dm, IntPtr hwnd, int flags, IntPtr lParam);

    public static string List() {
        var sb = new System.Text.StringBuilder();
        for (int i = 0; i < 16; i++) {
            var dd = new DISPLAY_DEVICE(); dd.cb = Marshal.SizeOf(dd);
            if (!EnumDisplayDevices(null, i, ref dd, 0)) break;
            sb.AppendFormat("{0} flags=0x{1:X} {2}\n", dd.DeviceName, dd.StateFlags, dd.DeviceString);
        }
        return sb.ToString();
    }

    public static string Apply(int w, int h, int orient) {
        string target = null;
        for (int i = 0; i < 16; i++) {
            var dd = new DISPLAY_DEVICE(); dd.cb = Marshal.SizeOf(dd);
            if (!EnumDisplayDevices(null, i, ref dd, 0)) break;
            if (dd.DeviceString.IndexOf("spacedesk", StringComparison.OrdinalIgnoreCase) >= 0 && (dd.StateFlags & 1) != 0) { target = dd.DeviceName; break; }
        }
        if (target == null) return "spacedesk display not attached";
        var dm = new DEVMODE(); dm.dmSize = (short)Marshal.SizeOf(dm);
        EnumDisplaySettings(target, -1, ref dm);
        string before = string.Format("{0}x{1} orientation={2}", dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayOrientation);
        dm.dmPelsWidth = w; dm.dmPelsHeight = h; dm.dmDisplayOrientation = orient;
        dm.dmFields = 0x80000 | 0x100000 | 0x80;   // PELSWIDTH | PELSHEIGHT | DISPLAYORIENTATION
        int r = ChangeDisplaySettingsEx(target, ref dm, IntPtr.Zero, 0x1, IntPtr.Zero);   // CDS_UPDATEREGISTRY
        EnumDisplaySettings(target, -1, ref dm);
        return string.Format("{0}: {1} -> {2}x{3} orientation={4} (result {5}; 0 = ok)", target, before, dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayOrientation, r);
    }
}
'@

if ($args -contains '-List') { [TabletDisplay]::List(); exit }
[TabletDisplay]::Apply($Width, $Height, $Orientation)
