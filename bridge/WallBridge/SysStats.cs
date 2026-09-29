using System.Net.NetworkInformation;
using System.Runtime.InteropServices;

namespace WallBridge;

/// <summary>
/// Live laptop vitals for the tablet's "windows" section: CPU (total + per core), RAM, NVIDIA GPU (NVML), battery,
/// network. Sampled once a second, but only while the tablet has asked within the last 10 s, so it costs nothing
/// when nobody is looking.
/// </summary>
static class SysStats
{
    static readonly object Lock = new();
    static object _latest = new { };
    static DateTime _lastAsked = DateTime.MinValue;
    static bool _running;

    public static object Latest()
    {
        lock (Lock)
        {
            _lastAsked = DateTime.UtcNow;
            if (!_running) { _running = true; _ = Task.Run(Loop); }
            return _latest;
        }
    }

    static async Task Loop()
    {
        var cpu = new CpuSampler();
        var net = new NetSampler();
        Nvml.Init();
        while (true)
        {
            try
            {
                var (total, cores) = cpu.Sample();
                var (down, up) = net.Sample();
                var mem = new MEMORYSTATUSEX { dwLength = (uint)Marshal.SizeOf<MEMORYSTATUSEX>() };
                GlobalMemoryStatusEx(ref mem);
                GetSystemPowerStatus(out var pw);
                var snap = new
                {
                    cpu = Math.Round(total, 1),
                    cores = cores.Select(c => (int)Math.Round(c)).ToArray(),
                    ram_used_mb = (long)((mem.ullTotalPhys - mem.ullAvailPhys) / 1048576),
                    ram_total_mb = (long)(mem.ullTotalPhys / 1048576),
                    gpu = Nvml.Sample(),
                    battery = pw.BatteryFlag == 128 ? null : new
                    {
                        pct = pw.BatteryLifePercent == 255 ? -1 : pw.BatteryLifePercent,
                        plugged = pw.ACLineStatus == 1,
                        charging = (pw.BatteryFlag & 8) != 0,
                        minutes = pw.BatteryLifeTime == -1 ? -1 : pw.BatteryLifeTime / 60,
                    },
                    net_down_bps = down,
                    net_up_bps = up,
                    uptime_s = Environment.TickCount64 / 1000,
                    host = Environment.MachineName.ToLowerInvariant(),
                };
                lock (Lock) _latest = snap;
            }
            catch { /* one bad sample is fine */ }
            await Task.Delay(1000);
            lock (Lock)
            {
                if ((DateTime.UtcNow - _lastAsked).TotalSeconds > 10) { _running = false; return; }   // nobody watching
            }
        }
    }

    // ---- CPU: per-logical-processor times from the kernel (what Task Manager's graphs are built on)

    sealed class CpuSampler
    {
        [StructLayout(LayoutKind.Sequential)]
        struct SPPI { public long Idle, Kernel, User, Dpc, Interrupt; public uint InterruptCount; uint _pad; }

        [DllImport("ntdll.dll")]
        static extern int NtQuerySystemInformation(int cls, [Out] SPPI[] info, int len, out int ret);

        SPPI[] _prev;

        public (double total, double[] cores) Sample()
        {
            int n = Environment.ProcessorCount;
            var cur = new SPPI[n];
            NtQuerySystemInformation(8, cur, n * Marshal.SizeOf<SPPI>(), out _);   // SystemProcessorPerformanceInformation
            var cores = new double[n];
            double busySum = 0, allSum = 0;
            if (_prev != null)
            {
                for (int i = 0; i < n; i++)
                {
                    long idle = cur[i].Idle - _prev[i].Idle;
                    long all = (cur[i].Kernel - _prev[i].Kernel) + (cur[i].User - _prev[i].User);   // kernel includes idle
                    cores[i] = all > 0 ? Math.Clamp(100.0 * (all - idle) / all, 0, 100) : 0;
                    busySum += all - idle; allSum += all;
                }
            }
            _prev = cur;
            return (allSum > 0 ? 100.0 * busySum / allSum : 0, cores);
        }
    }

    // ---- network: bytes/s across real, connected adapters

    sealed class NetSampler
    {
        long _rx, _tx;
        DateTime _at, _listAt;
        NetworkInterface[] _nics = Array.Empty<NetworkInterface>();

        public (long down, long up) Sample()
        {
            // Listing adapters is the expensive call (tens of ms with Tailscale, Hyper-V etc.): do it every 30 s and
            // only read the byte counters of the ones we kept each second.
            if ((DateTime.UtcNow - _listAt).TotalSeconds > 30)
            {
                _nics = NetworkInterface.GetAllNetworkInterfaces()
                    .Where(ni => ni.OperationalStatus == OperationalStatus.Up
                                 && ni.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel))
                    .ToArray();
                _listAt = DateTime.UtcNow;
                _at = default;                               // the set may have changed: don't compute a rate across it
            }
            long rx = 0, tx = 0;
            foreach (var ni in _nics)
            {
                try
                {
                    var s = ni.GetIPv4Statistics();
                    rx += s.BytesReceived; tx += s.BytesSent;
                }
                catch { _listAt = default; }                  // adapter vanished: re-list next time
            }
            var now = DateTime.UtcNow;
            double dt = (now - _at).TotalSeconds;
            (long d, long u) r = _at == default || dt <= 0 ? (0, 0)
                : ((long)Math.Max(0, (rx - _rx) / dt), (long)Math.Max(0, (tx - _tx) / dt));
            _rx = rx; _tx = tx; _at = now;
            return r;
        }
    }

    // ---- NVIDIA GPU via NVML (ships with the driver: C:\Windows\System32\nvml.dll)

    static class Nvml
    {
        [StructLayout(LayoutKind.Sequential)] struct Util { public uint Gpu, Memory; }
        [StructLayout(LayoutKind.Sequential)] struct Mem { public ulong Total, Free, Used; }

        [DllImport("nvml.dll", EntryPoint = "nvmlInit_v2")] static extern int Init_();
        [DllImport("nvml.dll", EntryPoint = "nvmlDeviceGetHandleByIndex_v2")] static extern int Handle(uint i, out IntPtr h);
        [DllImport("nvml.dll", EntryPoint = "nvmlDeviceGetName")] static extern int Name(IntPtr h, byte[] name, uint len);
        [DllImport("nvml.dll", EntryPoint = "nvmlDeviceGetUtilizationRates")] static extern int Utilization(IntPtr h, out Util u);
        [DllImport("nvml.dll", EntryPoint = "nvmlDeviceGetMemoryInfo")] static extern int Memory(IntPtr h, out Mem m);
        [DllImport("nvml.dll", EntryPoint = "nvmlDeviceGetTemperature")] static extern int Temp(IntPtr h, int sensor, out uint t);
        [DllImport("nvml.dll", EntryPoint = "nvmlDeviceGetPowerUsage")] static extern int Power(IntPtr h, out uint mw);
        [DllImport("nvml.dll", EntryPoint = "nvmlDeviceGetClockInfo")] static extern int Clock(IntPtr h, int type, out uint mhz);

        static IntPtr _h;
        static string _name = "";
        static bool _ok;

        public static void Init()
        {
            if (_ok) return;
            try
            {
                if (Init_() != 0 || Handle(0, out _h) != 0) return;
                var b = new byte[96];
                Name(_h, b, (uint)b.Length);
                _name = System.Text.Encoding.ASCII.GetString(b).TrimEnd('\0').Replace("NVIDIA GeForce ", "").Replace(" Laptop GPU", "");
                _ok = true;
            }
            catch (DllNotFoundException) { }
        }

        public static object Sample()
        {
            if (!_ok) return null;
            Utilization(_h, out var u);
            Memory(_h, out var m);
            Temp(_h, 0, out var t);
            Power(_h, out var p);
            Clock(_h, 0, out var clk);
            return new
            {
                name = _name, util = u.Gpu, temp = t, power_w = Math.Round(p / 1000.0, 1), clock_mhz = clk,
                mem_used_mb = (long)(m.Used / 1048576), mem_total_mb = (long)(m.Total / 1048576),
            };
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    struct MEMORYSTATUSEX
    {
        public uint dwLength, dwMemoryLoad;
        public ulong ullTotalPhys, ullAvailPhys, ullTotalPageFile, ullAvailPageFile, ullTotalVirtual, ullAvailVirtual, ullAvailExtendedVirtual;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct SYSTEM_POWER_STATUS
    {
        public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag;
        public int BatteryLifeTime, BatteryFullLifeTime;
    }

    [DllImport("kernel32.dll")] static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX m);
    [DllImport("kernel32.dll")] static extern bool GetSystemPowerStatus(out SYSTEM_POWER_STATUS s);
}
