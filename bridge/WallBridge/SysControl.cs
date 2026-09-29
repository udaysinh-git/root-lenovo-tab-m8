using System.Management;
using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using Windows.Devices.Radios;

namespace WallBridge;

/// <summary>
/// Windows controls for the tablet's "windows" section:
///   sound output (pick the default device), volume, mute, mic mute, Bluetooth radio, laptop screen brightness,
///   power mode (efficiency / balanced / performance), lock, sleep.
/// </summary>
static class SysControl
{
    // ------------------------------------------------------------------ state

    public static async Task<object> StateAsync()
    {
        var a = Audio.Get();
        object mic = null;
        try { if (a.Mic != null) mic = new { name = a.MicName, muted = a.Mic.AudioEndpointVolume.Mute }; } catch { Audio.Invalidate(); }
        int volume = -1;
        bool muted = false;
        try
        {
            if (a.Def != null)
            {
                volume = (int)Math.Round(a.Def.AudioEndpointVolume.MasterVolumeLevelScalar * 100);
                muted = a.Def.AudioEndpointVolume.Mute;
            }
        }
        catch { Audio.Invalidate(); }
        return new
        {
            outputs = a.Outputs.Select(o => new { id = o.id, name = o.name, @default = o.id == a.DefId }),
            volume,
            muted,
            mic,
            bluetooth = await BluetoothStateAsync(),
            brightness = GetBrightness(),
            power_mode = GetPowerMode(),
        };
    }

    /// <summary>
    /// Audio devices, cached. Enumerating endpoints and reading their names opens each device's property store, which
    /// cost ~170 ms of CPU per read; now it happens only when Windows reports a device or default change.
    /// </summary>
    sealed class Audio : NAudio.CoreAudioApi.Interfaces.IMMNotificationClient
    {
        static readonly object Lock = new();
        static Audio _cached;
        static MMDeviceEnumerator _en;
        static Audio _listener;

        public List<(string id, string name)> Outputs = new();
        public MMDevice Def, Mic;
        public string DefId = "", MicName = "";

        public static Audio Get()
        {
            lock (Lock)
            {
                if (_cached != null) return _cached;
                if (_en == null)
                {
                    _en = new MMDeviceEnumerator();
                    _listener = new Audio();
                    _en.RegisterEndpointNotificationCallback(_listener);
                }
                var a = new Audio();
                try
                {
                    a.Outputs = _en.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active)
                        .Select(d => (d.ID, d.FriendlyName)).ToList();
                }
                catch { }
                try { a.Def = _en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia); a.DefId = a.Def.ID; } catch { }
                try { a.Mic = _en.GetDefaultAudioEndpoint(DataFlow.Capture, Role.Communications); a.MicName = a.Mic.FriendlyName; } catch { }
                _cached = a;
                return a;
            }
        }

        public static void Invalidate() { lock (Lock) _cached = null; }

        public void OnDeviceStateChanged(string deviceId, DeviceState newState) => Invalidate();
        public void OnDeviceAdded(string pwstrDeviceId) => Invalidate();
        public void OnDeviceRemoved(string deviceId) => Invalidate();
        public void OnDefaultDeviceChanged(DataFlow flow, Role role, string defaultDeviceId) => Invalidate();
        public void OnPropertyValueChanged(string pwstrDeviceId, PropertyKey key) { }   // fires constantly; names rarely change
    }

    // ------------------------------------------------------------------ commands

    public static async Task<bool> CommandAsync(string cmd, string arg)
    {
        switch (cmd)
        {
            case "output":
                bool ok = SetDefaultOutput(arg);
                Audio.Invalidate();
                return ok;
            case "volume":
                return WithDefault(d => d.AudioEndpointVolume.MasterVolumeLevelScalar = Math.Clamp(int.Parse(arg), 0, 100) / 100f);
            case "mute": return WithDefault(d => d.AudioEndpointVolume.Mute = arg == "1");
            case "micmute":
            {
                var m = Audio.Get().Mic;
                if (m == null) return false;
                m.AudioEndpointVolume.Mute = arg == "1";
                return true;
            }
            case "bluetooth": return await SetBluetoothAsync(arg == "1");
            case "brightness": return SetBrightness(Math.Clamp(int.Parse(arg), 0, 100));
            case "power": return SetPowerMode(arg);
            case "lock": return LockWorkStation();
            case "sleep":
                _ = Task.Run(async () => { await Task.Delay(800); SetSuspendState(false, false, false); });   // reply first
                return true;
        }
        return false;
    }

    static bool WithDefault(Action<MMDevice> f)
    {
        var d = Audio.Get().Def;
        if (d == null) return false;
        f(d);
        return true;
    }

    // ------------------------------------------------------------------ default output device
    // Windows has no public API for this; IPolicyConfig is the COM interface the Sound settings use (Win7-11).

    [ComImport, Guid("f8679f50-850a-41cf-9c72-430f290290c8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPolicyConfig
    {
        int GetMixFormat(); int GetDeviceFormat(); int ResetDeviceFormat(); int SetDeviceFormat();
        int GetProcessingPeriod(); int SetProcessingPeriod(); int GetShareMode(); int SetShareMode();
        int GetPropertyValue(); int SetPropertyValue();
        [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string id, int role);
        int SetEndpointVisibility();
    }

    [ComImport, Guid("870af99c-171d-4f9e-af0d-e63df40c2bc9")] class PolicyConfigClient { }

    static bool SetDefaultOutput(string id)
    {
        if (string.IsNullOrEmpty(id)) return false;
        var pc = (IPolicyConfig)new PolicyConfigClient();
        bool ok = true;
        for (int role = 0; role < 3; role++) ok &= pc.SetDefaultEndpoint(id, role) == 0;   // console, multimedia, communications
        Marshal.ReleaseComObject(pc);
        return ok;
    }

    // ------------------------------------------------------------------ Bluetooth radio

    static Radio _bt;
    static bool _btLooked;

    static async Task<Radio> BluetoothRadioAsync()
    {
        if (_btLooked) return _bt;                            // the Radio object stays live: its State updates itself
        if (await Radio.RequestAccessAsync() != RadioAccessStatus.Allowed) return null;
        _bt = (await Radio.GetRadiosAsync()).FirstOrDefault(r => r.Kind == RadioKind.Bluetooth);
        _btLooked = true;
        return _bt;
    }

    static async Task<string> BluetoothStateAsync()
    {
        try
        {
            var r = await BluetoothRadioAsync();
            return r == null ? "none" : r.State == RadioState.On ? "on" : "off";
        }
        catch { return "none"; }
    }

    static async Task<bool> SetBluetoothAsync(bool on)
    {
        var r = await BluetoothRadioAsync();
        return r != null && await r.SetStateAsync(on ? RadioState.On : RadioState.Off) == RadioAccessStatus.Allowed;
    }

    // ------------------------------------------------------------------ built-in screen brightness (WMI)

    // WMI is the expensive part of a state read (the work happens in WmiPrvSE), so brightness is cached for 30 s
    // and updated directly when the tablet sets it.
    static int _brightness = -2;
    static DateTime _brightnessAt;

    static int GetBrightness()
    {
        if (_brightness != -2 && (DateTime.UtcNow - _brightnessAt).TotalSeconds < 30) return _brightness;
        _brightness = -1;
        try
        {
            using var s = new ManagementObjectSearcher(@"root\wmi", "SELECT CurrentBrightness FROM WmiMonitorBrightness");
            foreach (ManagementObject o in s.Get()) { _brightness = Convert.ToInt32(o["CurrentBrightness"]); break; }
        }
        catch { }
        _brightnessAt = DateTime.UtcNow;
        return _brightness;
    }

    static bool SetBrightness(int v)
    {
        using var s = new ManagementObjectSearcher(@"root\wmi", "SELECT * FROM WmiMonitorBrightnessMethods");
        foreach (ManagementObject o in s.Get())
        {
            o.InvokeMethod("WmiSetBrightness", new object[] { (uint)1, (byte)v });
            _brightness = v;
            _brightnessAt = DateTime.UtcNow;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ power mode (Settings > Power > Power mode)

    static readonly Dictionary<string, Guid> Modes = new()
    {
        ["efficiency"] = new Guid("961cc777-2547-4f9d-8174-7d86181b8a7a"),
        ["balanced"] = Guid.Empty,
        ["performance"] = new Guid("ded574b5-45a0-4f42-8737-46345c09c238"),
    };

    [DllImport("powrprof.dll")] static extern uint PowerGetEffectiveOverlayScheme(out Guid g);
    [DllImport("powrprof.dll")] static extern uint PowerSetActiveOverlayScheme(Guid g);
    [DllImport("powrprof.dll")] static extern bool SetSuspendState(bool hibernate, bool force, bool disableWake);
    [DllImport("user32.dll")] static extern bool LockWorkStation();

    static string GetPowerMode()
    {
        try
        {
            if (PowerGetEffectiveOverlayScheme(out var g) != 0) return "unknown";
            foreach (var kv in Modes) if (kv.Value == g) return kv.Key;
        }
        catch { }
        return "unknown";
    }

    static bool SetPowerMode(string mode) => Modes.TryGetValue(mode, out var g) && PowerSetActiveOverlayScheme(g) == 0;
}
