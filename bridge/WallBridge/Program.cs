using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace WallBridge;

/// <summary>
/// Tiny loopback HTTP server for Lumia Wall (raw sockets: HttpListener rejects Host "127.0.0.1" without a urlacl).
///   GET /state            now playing JSON
///   GET /art              cover art bytes (jpeg/png)
///   GET /cmd/{playpause|next|prev}
///   GET /spectrum         endless stream of 32-byte frames (~30 fps); capture runs only while connected
/// </summary>
static class Program
{
    const int Port = 8770;
    static readonly NowPlaying Np = new();
    static readonly Spectrum Spec = new();

    static async Task Main()
    {
        using var single = new Mutex(true, @"Local\WallBridge", out bool first);
        if (!first) return;                                   // already running

        await Np.StartAsync();
        var listener = new TcpListener(IPAddress.Loopback, Port);
        listener.Start();
        while (true)
        {
            var client = await listener.AcceptTcpClientAsync();
            _ = Task.Run(() => Handle(client));
        }
    }

    static async Task Handle(TcpClient client)
    {
        using var c = client;
        c.NoDelay = true;
        try
        {
            var stream = c.GetStream();
            string path = await ReadPath(stream);
            if (path.StartsWith("/state"))
            {
                var s = Np.Current;
                var json = JsonSerializer.Serialize(new
                {
                    playing = s.Playing, title = s.Title, artist = s.Artist, album = s.Album, app = s.App,
                    position_ms = s.PositionMs, duration_ms = s.DurationMs, art_id = s.ArtId,
                });
                await Send(stream, 200, "application/json", Encoding.UTF8.GetBytes(json));
            }
            else if (path.StartsWith("/art"))
            {
                var art = Np.Art;
                bool png = art.Length > 4 && art[0] == 0x89 && art[1] == 0x50;
                await Send(stream, art.Length > 0 ? 200 : 404, png ? "image/png" : "image/jpeg", art);
            }
            else if (path.StartsWith("/cmd/"))
            {
                bool ok = await Np.CommandAsync(path.Substring(5).Split('?')[0]);
                await Send(stream, ok ? 200 : 409, "text/plain", Encoding.ASCII.GetBytes(ok ? "ok" : "no"));
            }
            else if (path.StartsWith("/spectrum"))
            {
                await StreamSpectrum(stream);
            }
            else
            {
                await Send(stream, 404, "text/plain", Encoding.ASCII.GetBytes("not found"));
            }
        }
        catch { /* client went away */ }
    }

    static async Task StreamSpectrum(NetworkStream stream)
    {
        var head = Encoding.ASCII.GetBytes("HTTP/1.0 200 OK\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n");
        await stream.WriteAsync(head);
        Spec.Subscribe();
        try
        {
            var next = DateTime.UtcNow;
            while (true)
            {
                await stream.WriteAsync(Spec.Frame());
                next = next.AddMilliseconds(33);
                var wait = next - DateTime.UtcNow;
                if (wait > TimeSpan.Zero) await Task.Delay(wait); else next = DateTime.UtcNow;
            }
        }
        finally { Spec.Unsubscribe(); }
    }

    static async Task<string> ReadPath(NetworkStream s)
    {
        var sb = new StringBuilder();
        var one = new byte[1];
        string first = null;
        var line = new StringBuilder();
        while (await s.ReadAsync(one) == 1)
        {
            char ch = (char)one[0];
            if (ch == '\n')
            {
                if (first == null) first = line.ToString().Trim();
                if (line.ToString().Trim().Length == 0) break;
                line.Clear();
            }
            else if (ch != '\r') line.Append(ch);
        }
        var parts = (first ?? "GET /").Split(' ');
        return parts.Length > 1 ? parts[1] : "/";
    }

    static async Task Send(NetworkStream s, int code, string type, byte[] body)
    {
        var head = $"HTTP/1.0 {code} {(code == 200 ? "OK" : "Error")}\r\nContent-Type: {type}\r\nContent-Length: {body.Length}\r\nConnection: close\r\n\r\n";
        await s.WriteAsync(Encoding.ASCII.GetBytes(head));
        await s.WriteAsync(body);
    }
}
