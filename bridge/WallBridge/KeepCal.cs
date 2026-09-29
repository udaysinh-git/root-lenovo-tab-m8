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

    /// <summary>
    /// Tablet edits: /keep/check?note=&amp;item=&amp;checked=0|1, /keep/add?note=&amp;text=, /keep/refresh?note=all. One JSON file each
    /// into keep-cmds; keepcal.py applies them within a second, syncs to Google and republishes keep.json.
    /// </summary>
    public static bool Queue(string path)
    {
        int q = path.IndexOf('?');
        if (q < 0) return false;
        var p = System.Web.HttpUtility.ParseQueryString(path[(q + 1)..]);
        string note = p["note"];
        if (string.IsNullOrEmpty(note)) return false;
        object cmd = path.StartsWith("/keep/check") ? new { op = "check", note, item = p["item"], @checked = p["checked"] == "1" }
            : path.StartsWith("/keep/add") ? new { op = "add", note, text = p["text"] ?? "" }
            : (object)new { op = "refresh", note };                                // sync Keep + Calendar now
        var dir = Path.Combine(Data, "keep-cmds");
        Directory.CreateDirectory(dir);
        var name = $"{DateTime.UtcNow:yyyyMMddHHmmssfff}-{Guid.NewGuid():N}";
        File.WriteAllText(Path.Combine(dir, name + ".tmp"), System.Text.Json.JsonSerializer.Serialize(cmd));
        File.Move(Path.Combine(dir, name + ".tmp"), Path.Combine(dir, name + ".json"));   // appears whole
        return true;
    }

    /// <summary>
    /// keepcal.py syncs every minute only while the tablet has looked at the day sheet in the last few minutes
    /// (it reads this file's timestamp); otherwise every 15 minutes. Touched at most every 30 s.
    /// </summary>
    public static void Watched()
    {
        var f = Path.Combine(Data, "keep-watch");
        try
        {
            if (!File.Exists(f)) File.WriteAllText(f, "");
            else if ((DateTime.UtcNow - File.GetLastWriteTimeUtc(f)).TotalSeconds > 30) File.SetLastWriteTimeUtc(f, DateTime.UtcNow);
        }
        catch { }
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
