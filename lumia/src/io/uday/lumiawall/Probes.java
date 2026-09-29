package io.uday.lumiawall;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import org.json.JSONObject;

/** Blocking data fetchers. Call from a background thread only. */
final class Probes {
    private Probes() {}

    // ---- weather (Open-Meteo, no key) ----

    static final class Weather {
        int temp, high, low, humidity, code;
        String summary() { return describe(code); }
    }

    static Weather weather(double lat, double lon) {
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,relative_humidity_2m,weather_code"
                    + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto";
            JSONObject j = new JSONObject(get(url, 8000));
            JSONObject cur = j.getJSONObject("current");
            JSONObject day = j.getJSONObject("daily");
            Weather w = new Weather();
            w.temp = (int) Math.round(cur.getDouble("temperature_2m"));
            w.humidity = cur.getInt("relative_humidity_2m");
            w.code = cur.getInt("weather_code");
            w.high = (int) Math.round(day.getJSONArray("temperature_2m_max").getDouble(0));
            w.low = (int) Math.round(day.getJSONArray("temperature_2m_min").getDouble(0));
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    /** WMO weather code -> WP-style lowercase words. */
    static String describe(int code) {
        if (code == 0) return "clear";
        if (code <= 2) return "partly cloudy";
        if (code == 3) return "cloudy";
        if (code == 45 || code == 48) return "fog";
        if (code >= 51 && code <= 57) return "drizzle";
        if (code >= 61 && code <= 67) return "rain";
        if (code >= 71 && code <= 77) return "snow";
        if (code >= 80 && code <= 82) return "showers";
        if (code >= 95) return "thunderstorm";
        return "—";
    }

    // ---- reachability ----

    /** True if a TCP connection to host:port succeeds within the timeout. */
    static boolean open(String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    // ---- battery ----

    static final class Battery {
        int percent;
        boolean charging, plugged;
    }

    static Battery battery(Context c) {
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        Battery b = new Battery();
        if (i == null) return b;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        b.percent = level < 0 ? -1 : Math.round(100f * level / scale);
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        b.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
        b.plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        return b;
    }

    // ---- http ----

    static String get(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
