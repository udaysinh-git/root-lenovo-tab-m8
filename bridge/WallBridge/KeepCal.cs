using System.Diagnostics;

namespace WallBridge;

/// <summary>
/// Runs bridge/keepcal/keepcal.py (Google Keep + Calendar sync, Python because gkeepapi is) as a child process and
/// serves the JSON it writes. It only starts once `keepcal.py login` has been done; checked every minute so a
/// fresh login or a crash is picked up without restarting WallBridge.
/// </summary>
static class KeepCal
{
    static readonly string Data = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "WallBridge");
    static readonly string Dir = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "keepcal"));
    static Process _proc;

    public static void Start()
    {
        _ = Task.Run(async () =>
        {
            while (true)
            {
                try { EnsureRunning(); } catch { /* python missing etc.: try again later */ }
                await Task.Delay(TimeSpan.FromMinutes(1));
            }
        });
    }

    static void EnsureRunning()
    {
        if (_proc is { HasExited: false }) return;
        if (!File.Exists(Path.Combine(Data, "keepcal-config.json"))) return;      // not logged in yet
        string py = Path.Combine(Dir, ".venv", "Scripts", "python.exe");
        if (!File.Exists(py)) return;
        var psi = new ProcessStartInfo(py, $"-u keepcal.py loop --parent {Environment.ProcessId}")
        {
            WorkingDirectory = Dir,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        var log = Path.Combine(Data, "keepcal.log");
        if (File.Exists(log) && new FileInfo(log).Length > 1_000_000) File.Delete(log);
        _proc = Process.Start(psi);
        void Append(string line) { if (line != null) lock (Dir) File.AppendAllText(log, $"{DateTime.Now:HH:mm:ss} {line}\n"); }
        _proc.OutputDataReceived += (_, e) => Append(e.Data);
        _proc.ErrorDataReceived += (_, e) => Append(e.Data);
        _proc.BeginOutputReadLine();
        _proc.BeginErrorReadLine();
    }

    /// <summary>"keep" or "calendar": the last JSON written, or a not-configured stub.</summary>
    public static byte[] Json(string which)
    {
        var f = Path.Combine(Data, which + ".json");
        try { if (File.Exists(f)) return File.ReadAllBytes(f); } catch { /* being replaced: next poll */ }
        bool configured = File.Exists(Path.Combine(Data, "keepcal-config.json"));
        return System.Text.Encoding.UTF8.GetBytes(configured
            ? "{\"ok\":false,\"error\":\"syncing…\"}"
            : "{\"ok\":false,\"error\":\"not signed in\"}");
    }
}
