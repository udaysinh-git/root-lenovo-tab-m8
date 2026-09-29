package io.uday.lumiawall;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/**
 * Client for the laptop's WallBridge, reached through `adb reverse tcp:8770` (so it's 127.0.0.1 here).
 * Polls /state, fetches /art and /lyrics on track change, streams /spectrum only while asked to, sends /cmd/*.
 */
final class MusicBridge {
    static final String BASE = "http://127.0.0.1:8770";

    static final class State {
        boolean connected, playing, estimated;   // estimated: player gave no timeline, bridge counts itself
        String title = "", artist = "", album = "", app = "";
        long positionMs, durationMs;
        int artId = -1;
    }

    /** Lyrics for one track. {@code source}: loading | synced | plain | none | error. */
    static final class Lyrics {
        String key = "", source = "none", plain = "";
        long[] times = new long[0];
        String[] lines = new String[0];
    }

    interface Listener {
        void onState(State s);
        void onArt(Bitmap art);
        void onLyrics(Lyrics l);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final VisView vis;
    private volatile boolean polling, spectrumWanted;
    private Thread pollThread, specThread;
    private int lastArtId = -1;
    private String lyricsKey = "", lyricsSource = "";
    volatile State last = new State();

    MusicBridge(Listener l, VisView vis) {
        this.listener = l;
        this.vis = vis;
    }

    void start() {
        if (polling) return;
        polling = true;
        pollThread = new Thread(this::pollLoop, "music-poll");
        pollThread.start();
    }

    void stop() {
        polling = false;
        setSpectrum(false);
    }

    /** Spectrum streaming costs a little on both ends, so it only runs while something is playing and we're visible. */
    void setSpectrum(boolean on) {
        if (on == spectrumWanted) return;
        spectrumWanted = on;
        vis.setLive(on);
        if (on) {
            specThread = new Thread(this::spectrumLoop, "music-spectrum");
            specThread.start();
        }
    }

    void command(final String cmd) {
        new Thread(() -> {
            try { Probes.get(BASE + "/cmd/" + cmd, 3000); } catch (Exception ignored) {}
        }, "music-cmd").start();
    }

    /** Jump the laptop's player; {@code onRefused} runs on the UI thread if the player can't seek (some sites). */
    void seek(final long ms, final Runnable onRefused) {
        new Thread(() -> {
            try { Probes.get(BASE + "/cmd/seek?ms=" + ms, 3000); }
            catch (Exception e) { ui.post(onRefused); }
        }, "music-seek").start();
    }

    private void pollLoop() {
        while (polling) {
            final State s = new State();
            try {
                JSONObject j = new JSONObject(Probes.get(BASE + "/state", 2500));
                s.connected = true;
                s.playing = j.optBoolean("playing");
                s.title = j.optString("title");
                s.artist = j.optString("artist");
                s.album = j.optString("album");
                s.app = j.optString("app");
                s.positionMs = j.optLong("position_ms");
                s.durationMs = j.optLong("duration_ms");
                s.artId = j.optInt("art_id", -1);
                s.estimated = j.optBoolean("estimated");
            } catch (Exception e) {
                s.connected = false;
            }
            last = s;
            ui.post(() -> listener.onState(s));
            if (s.connected && s.artId != lastArtId) {
                lastArtId = s.artId;
                final Bitmap art = fetchArt();
                ui.post(() -> listener.onArt(art));
            }
            // Lyrics: once per track; while the laptop is still looking them up, ask again each poll.
            String key = s.title + "|" + s.artist;
            if (!s.connected || s.title.isEmpty()) lyricsKey = "";   // the screen went idle: resend on return
            if (s.connected && !s.title.isEmpty()
                    && (!key.equals(lyricsKey) || lyricsSource.equals("loading") || lyricsSource.equals("error"))) {
                final Lyrics l = fetchLyrics(key);
                if (l != null && !(l.key.equals(lyricsKey) && l.source.equals(lyricsSource))) {
                    lyricsKey = l.key;
                    lyricsSource = l.source;
                    ui.post(() -> listener.onLyrics(l));
                }
            }
            sleep(1000);
        }
    }

    private Lyrics fetchLyrics(String key) {
        try {
            JSONObject j = new JSONObject(Probes.get(BASE + "/lyrics", 4000));
            Lyrics l = new Lyrics();
            l.key = j.optString("key");
            // The bridge answers for whatever plays *now*; if the track changed under us, it's still loading ours.
            l.source = l.key.equals(key) ? j.optString("source", "none") : "loading";
            l.key = key;
            l.plain = j.optString("plain");
            org.json.JSONArray a = j.optJSONArray("lines");
            if (a != null && l.source.equals("synced")) {
                l.times = new long[a.length()];
                l.lines = new String[a.length()];
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    l.times[i] = o.optLong("t");
                    l.lines[i] = o.optString("text");
                }
            }
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap fetchArt() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/art").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void spectrumLoop() {
        byte[] frame = new byte[32];
        while (spectrumWanted) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(BASE + "/spectrum").openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(3000);
                InputStream in = c.getInputStream();
                while (spectrumWanted) {
                    int got = 0;
                    while (got < 32) {
                        int n = in.read(frame, got, 32 - got);
                        if (n < 0) throw new java.io.EOFException();
                        got += n;
                    }
                    vis.push(frame);
                }
            } catch (Exception e) {
                sleep(1500);       // bridge not running / cable unplugged: retry quietly
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
