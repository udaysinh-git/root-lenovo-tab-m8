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

    GlobalSystemMediaTransportControlsSessionManager _mgr;
    readonly object _lock = new();
    State _state = new(false, "", "", "", "", 0, 0, 0);
    byte[] _art = Array.Empty<byte>();
    string _artKey = "";
    int _artId;
    DateTime _positionAt = DateTime.UtcNow;

    public async Task StartAsync()
    {
        _mgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
        _mgr.CurrentSessionChanged += (_, _) => _ = RefreshAsync();
        _ = Task.Run(async () =>
        {
            while (true)
            {
                try { await RefreshAsync(); } catch { /* player closing mid-query */ }
                await Task.Delay(1000);
            }
        });
    }

    public State Current
    {
        get
        {
            lock (_lock)
            {
                // extrapolate position between SMTC updates (Spotify updates the timeline rarely)
                if (!_state.Playing || _state.DurationMs == 0) return _state;
                long pos = Math.Min(_state.DurationMs,
                    _state.PositionMs + (long)(DateTime.UtcNow - _positionAt).TotalMilliseconds);
                return _state with { PositionMs = pos };
            }
        }
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
            // Only re-anchor the extrapolation when SMTC actually reported a new position.
            if (pos != _state.PositionMs || playing != _state.Playing) _positionAt = tl.LastUpdatedTime.UtcDateTime;
            if (_positionAt > DateTime.UtcNow || _positionAt.Year < 2000) _positionAt = DateTime.UtcNow;
            _state = new State(playing, props.Title ?? "", props.Artist ?? "", props.AlbumTitle ?? "", app,
                               pos, (long)tl.EndTime.TotalMilliseconds, _artId);
        }
    }

    public async Task<bool> CommandAsync(string cmd)
    {
        var s = Session();
        if (s == null) return false;
        return cmd switch
        {
            "playpause" => await s.TryTogglePlayPauseAsync(),
            "next" => await s.TrySkipNextAsync(),
            "prev" => await s.TrySkipPreviousAsync(),
            _ => false,
        };
    }
}
