using System.Net.Http.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace WallBridge;

/// <summary>
/// Free, keyless lyrics from LRCLIB (https://lrclib.net). Prefers time-synced (LRC) lyrics.
/// Looked up once per track and cached.
/// </summary>
sealed class Lyrics
{
    public sealed record Line(long T, string Text);
    public sealed record Result(string Key, List<Line> Synced, string Plain, string Source);

    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(8) };
    static readonly Regex LrcLine = new(@"^\[(\d+):(\d+(?:\.\d+)?)\](.*)$", RegexOptions.Compiled);

    readonly Dictionary<string, Result> _cache = new();
    readonly object _lock = new();
    Result _current = new("", new(), "", "");
    DateTime _failedAt;

    static Lyrics()
    {
        Http.DefaultRequestHeaders.UserAgent.ParseAdd("WallBridge/1.0 (Lumia Wall tablet dashboard)");
    }

    public Result Current { get { lock (_lock) return _current; } }

    public async Task UpdateAsync(string title, string artist, string album, long durationMs)
    {
        string key = $"{title}|{artist}";
        lock (_lock)
        {
            bool retry = _current.Source == "error" && DateTime.UtcNow - _failedAt > TimeSpan.FromSeconds(30);
            if (_current.Key == key && !retry) return;
            if (_cache.TryGetValue(key, out var hit)) { _current = hit; return; }
            _current = new Result(key, new(), "", "loading");
        }
        Result r = new(key, new(), "", "none");
        bool failed = false;
        if (!string.IsNullOrWhiteSpace(title) && !string.IsNullOrWhiteSpace(artist))
        {
            try { r = await LookupAsync(key, title, artist, album, durationMs) ?? r; }
            catch { r = new(key, new(), "", "error"); failed = true; }   // offline: retried later, never cached
        }
        lock (_lock)
        {
            if (failed) _failedAt = DateTime.UtcNow; else _cache[key] = r;
            if (_current.Key == key) _current = r;
        }
    }

    async Task<Result> LookupAsync(string key, string title, string artist, string album, long durationMs)
    {
        // 1) exact match (LRCLIB tolerates +-2 s on duration)
        var q = $"track_name={Uri.EscapeDataString(title)}&artist_name={Uri.EscapeDataString(artist)}";
        if (!string.IsNullOrWhiteSpace(album)) q += $"&album_name={Uri.EscapeDataString(album)}";
        if (durationMs > 0) q += $"&duration={durationMs / 1000}";
        var resp = await Http.GetAsync("https://lrclib.net/api/get?" + q);
        Entry e = resp.IsSuccessStatusCode ? await resp.Content.ReadFromJsonAsync<Entry>() : null;

        // 2) fuzzy search, preferring an entry that has synced lyrics; then again without Spotify-style
        //    suffixes ("- Remastered 2009", "(feat. X)", "- Live") that other databases leave out
        foreach (var t in new[] { title, Clean(title) }.Distinct())
        {
            if (e != null && (!string.IsNullOrEmpty(e.SyncedLyrics) || !string.IsNullOrEmpty(e.PlainLyrics))) break;
            var list = await Http.GetFromJsonAsync<List<Entry>>(
                $"https://lrclib.net/api/search?track_name={Uri.EscapeDataString(t)}&artist_name={Uri.EscapeDataString(PrimaryArtist(artist))}");
            e = list?.FirstOrDefault(x => !string.IsNullOrEmpty(x.SyncedLyrics)) ?? list?.FirstOrDefault();
        }
        if (e == null) return null;

        var synced = new List<Line>();
        foreach (var raw in (e.SyncedLyrics ?? "").Split('\n'))
        {
            var m = LrcLine.Match(raw.Trim());
            if (!m.Success) continue;
            long t = (long)((int.Parse(m.Groups[1].Value) * 60 + double.Parse(m.Groups[2].Value,
                              System.Globalization.CultureInfo.InvariantCulture)) * 1000);
            synced.Add(new Line(t, m.Groups[3].Value.Trim()));
        }
        return new Result(key, synced, e.PlainLyrics ?? "", synced.Count > 0 ? "synced" : "plain");
    }

    static string Clean(string title)
    {
        var t = Regex.Replace(title, @"\s*[\(\[](feat|ft|with|from)\b[^\)\]]*[\)\]]", "", RegexOptions.IgnoreCase);
        t = Regex.Replace(t, @"\s+-\s+.*(remaster|live|version|edit|mix|mono|stereo|acoustic).*$", "", RegexOptions.IgnoreCase);
        return t.Trim();
    }

    static string PrimaryArtist(string artist) => artist.Split(new[] { ", ", " & ", " feat" }, StringSplitOptions.None)[0];

    sealed class Entry
    {
        [JsonPropertyName("syncedLyrics")] public string SyncedLyrics { get; set; }
        [JsonPropertyName("plainLyrics")] public string PlainLyrics { get; set; }
    }
}
