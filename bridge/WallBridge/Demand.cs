namespace WallBridge;

/// <summary>
/// "Is anyone looking?" Endpoints touch a topic when the tablet asks; background loops only work while their topic
/// was touched recently, so WallBridge idles at ~zero whenever the tablet is showing something else or is unplugged.
/// </summary>
static class Demand
{
    static readonly Dictionary<string, DateTime> Seen = new();

    public static void Touch(string topic) { lock (Seen) Seen[topic] = DateTime.UtcNow; }

    public static bool Active(string topic, double seconds = 15)
    {
        lock (Seen) return Seen.TryGetValue(topic, out var t) && (DateTime.UtcNow - t).TotalSeconds < seconds;
    }
}
