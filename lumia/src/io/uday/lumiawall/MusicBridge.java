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
 * Polls /state, fetches /art on track change, streams /spectrum only while asked to, sends /cmd/*.
 */
final class MusicBridge {
    static final String BASE = "http://127.0.0.1:8770";

    static final class State {
        boolean connected, playing;
        String title = "", artist = "", album = "", app = "";
        long positionMs, durationMs;
        int artId = -1;
    }

    interface Listener {
        void onState(State s);
        void onArt(Bitmap art);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final VisView vis;
    private volatile boolean polling, spectrumWanted;
    private Thread pollThread, specThread;
    private int lastArtId = -1;
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
            sleep(1000);
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
