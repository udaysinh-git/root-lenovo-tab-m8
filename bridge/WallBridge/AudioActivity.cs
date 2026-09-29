using System.Diagnostics;
using NAudio.CoreAudioApi;

namespace WallBridge;

/// <summary>
/// "Is this app actually making sound?" from Windows' per-app audio meters (the volume mixer's).
/// Needed because many websites never tell the browser (and so SMTC) that they paused: the session keeps saying
/// "Playing" with a frozen position. Real silence for a few seconds is the reliable signal there.
/// </summary>
sealed class AudioActivity
{
    static readonly string[] Browsers = { "zen", "firefox", "chrome", "msedge", "brave", "opera", "vivaldi", "floorp", "librewolf" };
    MMDeviceEnumerator _enum;
    DateTime _lastAudible = DateTime.MinValue;

    /// <summary>Sample the meters of every browser audio session; call ~every 0.7 s.</summary>
    public void SampleBrowsers()
    {
        try
        {
            _enum ??= new MMDeviceEnumerator();
            using var dev = _enum.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            var sessions = dev.AudioSessionManager.Sessions;
            for (int i = 0; i < sessions.Count; i++)
            {
                var s = sessions[i];
                if (s.State != NAudio.CoreAudioApi.Interfaces.AudioSessionState.AudioSessionStateActive) continue;
                string name;
                try { name = Process.GetProcessById((int)s.GetProcessID).ProcessName.ToLowerInvariant(); }
                catch { continue; }
                if (!Browsers.Contains(name)) continue;
                if (s.AudioMeterInformation.MasterPeakValue > 0.0015f) { _lastAudible = DateTime.UtcNow; return; }
            }
        }
        catch { _enum = null; /* device changed (headphones plugged etc.): rebuild next time */ }
    }

    /// <summary>True if a browser made sound within the hold window (bridges quiet moments in a scene).</summary>
    public bool BrowserAudible(double holdSeconds = 2.5) => (DateTime.UtcNow - _lastAudible).TotalSeconds < holdSeconds;
}
