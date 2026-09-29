using Windows.Media.Control;
using Windows.Storage.Streams;

namespace WallBridge;

/// <summary>
/// What Windows' media overlay knows (SMTC): works for Spotify, browsers, most players.
/// Refreshed on SMTC events plus a 1 s poll, because events are occasionally missed.
/// </summary>
sealed class NowPlaying
{
    public sealed record State(bool Playing, string Title, string Artist, string Album, string App,
                               long PositionMs, long DurationMs, int ArtId);

    /// <summary>How stale the data is and why: a hung WinRT call used to freeze updates silently.</summary>
    public DateTime LastRefreshUtc { get; private set; } = DateTime.MinValue;
    public string LastError { get; private set; } = "";

    GlobalSystemMediaTransportControlsSessionManager _mgr;
    readonly AudioActivity _audio = new();
    readonly object _lock = new();
    State _state = new(false, "", "", "", "", 0, 0, 0);
    byte[] _art = Array.Empty<byte>();
    string _artKey = "";
    int _artId;
    DateTime _positionAt = DateTime.UtcNow;
    string _trackKey = "";
    bool _ownClock;

    public async Task StartAsync()
    {
        _mgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
        _mgr.CurrentSessionChanged += (_, _) => _ = RefreshAsync();
        // Meter peaks are momentary, so sample often (cheap) and let the refresh ask "any sound lately?".
        _ = Task.Run(async () =>
        {
            while (true) { _audio.SampleBrowsers(); await Task.Delay(150); }
        });
        _ = Task.Run(async () =>
        {
            while (true)
            {
                try
                {
                    // SMTC calls can hang forever (e.g. a browser tab closing mid-query); never let one freeze the loop.
                    await RefreshAsync().WaitAsync(TimeSpan.FromSeconds(3));
                    LastRefreshUtc = DateTime.UtcNow;
                    LastError = "";
                }
                catch (Exception e) { LastError = e.GetType().Name + ": " + e.Message; }
                await Task.Delay(700);
            }
        });
    }

    public State Current
    {
        get
        {
            lock (_lock)
            {
                // Extrapolate between timeline updates (players report position rarely), but only from a fresh
                // anchor: if refreshes stall, report the last known position instead of counting on forever.
                bool loopHealthy = (DateTime.UtcNow - LastRefreshUtc).TotalSeconds < 5;
                if (!_state.Playing || (_state.DurationMs == 0 && !_ownClock) || !loopHealthy) return _state;
                return _state with { PositionMs = Extrapolated() };
            }
        }
    }

    /// <summary>True when the player publishes no timeline and the position is our own count from the track start.</summary>
    public bool Estimated { get { lock (_lock) return _ownClock; } }

    long Extrapolated()   // caller holds _lock
    {
        if (!_state.Playing) return _state.PositionMs;
        long pos = _state.PositionMs + (long)(DateTime.UtcNow - _positionAt).TotalMilliseconds;
        return _state.DurationMs > 0 ? Math.Min(_state.DurationMs, pos) : pos;
    }

    public byte[] Art { get { lock (_lock) return _art; } }

    GlobalSystemMediaTransportControlsSession Session()
    {
        var s = _mgr?.GetCurrentSession();
        // Prefer Spotify when several players are open.
        foreach (var x in _mgr?.GetSessions() ?? (IReadOnlyList<GlobalSystemMediaTransportControlsSession>)Array.Empty<GlobalSystemMediaTransportControlsSession>())
            if (x.SourceAppUserModelId.Contains("Spotify", StringComparison.OrdinalIgnoreCase)) return x;
        return s;
    }

    async Task RefreshAsync()
    {
        var s = Session();
        if (s == null)
        {
            lock (_lock) _state = new State(false, "", "", "", "", 0, 0, _artId);
            return;
        }
        var props = await s.TryGetMediaPropertiesAsync();
        var info = s.GetPlaybackInfo();
        var tl = s.GetTimelineProperties();
        bool playing = info.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;

        // Browsers often keep reporting "Playing" after the site pauses. For browser sessions, trust the audio meter:
        // "Playing" + silence for a couple of seconds = paused. Native players (Spotify) report state properly.
        bool isBrowserSession = System.Text.RegularExpressions.Regex.IsMatch(s.SourceAppUserModelId, "^[0-9A-F]{16}$")
            || s.SourceAppUserModelId.Contains("firefox", StringComparison.OrdinalIgnoreCase)
            || s.SourceAppUserModelId.Contains("chrome", StringComparison.OrdinalIgnoreCase)
            || s.SourceAppUserModelId.Contains("msedge", StringComparison.OrdinalIgnoreCase);
        if (isBrowserSession && playing) playing = _audio.BrowserAudible();

        string key = $"{props.Title}|{props.Artist}|{props.AlbumTitle}";
        if (key != _artKey)
        {
            byte[] art = Array.Empty<byte>();
            if (props.Thumbnail != null)
            {
                using IRandomAccessStreamWithContentType st = await props.Thumbnail.OpenReadAsync();
                using var ms = new MemoryStream();
                await st.AsStreamForRead().CopyToAsync(ms);
                art = ms.ToArray();
            }
            lock (_lock) { _art = art; _artKey = key; _artId++; }
        }

        string app = s.SourceAppUserModelId;
        if (app.Contains("Spotify", StringComparison.OrdinalIgnoreCase)) app = "spotify";
        else if (app.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) app = Path.GetFileNameWithoutExtension(app).ToLowerInvariant();
        else if (System.Text.RegularExpressions.Regex.IsMatch(app, "^[0-9A-F]{16}$")) app = "browser";   // hashed AUMID (e.g. Firefox/Zen)
        else if (app.Contains('!')) app = app.Split('!')[0].Split('.').Last().ToLowerInvariant();

        lock (_lock)
        {
            long pos = (long)tl.Position.TotalMilliseconds;
            string track = $"{props.Title}|{props.Artist}";
            bool trackChanged = track != _trackKey;
            _trackKey = track;
            if (tl.EndTime <= TimeSpan.Zero && pos == 0 && !string.IsNullOrEmpty(props.Title))
            {
                // No timeline (Spotify sometimes publishes 0/0 for a whole track after auto-advancing, and seeks or
                // pause/play don't revive it): count ourselves from the track start, freezing on pause.
                // A seek from the tablet re-anchors it exactly (see CommandAsync).
                if (trackChanged) { pos = 0; _positionAt = DateTime.UtcNow; }
                else if (!_ownClock || playing != _state.Playing) { pos = Extrapolated(); _positionAt = DateTime.UtcNow; }
                else pos = _state.PositionMs;
                _ownClock = true;
            }
            // Re-anchor when the player reports a new position or the play state flips.
            else if (pos != _state.PositionMs || playing != _state.Playing || _ownClock)
            {
                // Native players (Spotify) stamp their position accurately, which matters for lyric sync.
                // Browsers' LastUpdatedTime is unreliable (it produced 50-second jumps), so use our own clock there.
                var stamp = tl.LastUpdatedTime.UtcDateTime;
                bool stampOk = !isBrowserSession && stamp <= DateTime.UtcNow && (DateTime.UtcNow - stamp).TotalSeconds < 30;
                _positionAt = stampOk ? stamp : DateTime.UtcNow;
                _ownClock = false;
            }
            _state = new State(playing, props.Title ?? "", props.Artist ?? "", props.AlbumTitle ?? "", app,
                               pos, (long)tl.EndTime.TotalMilliseconds, _artId);
        }
    }

    /// <summary>Raw view of every SMTC session, for diagnosing "which session is the real one".</summary>
    public async Task<object> DebugAsync()
    {
        var list = new List<object>();
        var cur = _mgr?.GetCurrentSession();
        foreach (var s in _mgr?.GetSessions() ?? (IReadOnlyList<GlobalSystemMediaTransportControlsSession>)Array.Empty<GlobalSystemMediaTransportControlsSession>())
        {
            var p = await s.TryGetMediaPropertiesAsync();
            var info = s.GetPlaybackInfo();
            var tl = s.GetTimelineProperties();
            list.Add(new
            {
                app = s.SourceAppUserModelId,
                current = cur != null && cur.SourceAppUserModelId == s.SourceAppUserModelId,
                title = p.Title,
                status = info.PlaybackStatus.ToString(),
                can_pause = info.Controls.IsPauseEnabled,
                can_play = info.Controls.IsPlayEnabled,
                position_ms = (long)tl.Position.TotalMilliseconds,
                updated = tl.LastUpdatedTime.ToString("HH:mm:ss.fff"),
            });
        }
        return list;
    }

    public async Task<bool> CommandAsync(string cmd, long arg = 0)
    {
        var s = Session();
        if (s == null) return false;
        bool ok = cmd switch
        {
            "playpause" => await s.TryTogglePlayPauseAsync(),
            "next" => await s.TrySkipNextAsync(),
            "prev" => await s.TrySkipPreviousAsync(),
            "seek" => await s.TryChangePlaybackPositionAsync(arg * TimeSpan.TicksPerMillisecond),
            _ => false,
        };
        if (ok && cmd == "seek")
        {
            // Reflect the jump immediately instead of waiting for the player to report it.
            lock (_lock) { _state = _state with { PositionMs = arg }; _positionAt = DateTime.UtcNow; }
        }
        return ok;
    }
}
