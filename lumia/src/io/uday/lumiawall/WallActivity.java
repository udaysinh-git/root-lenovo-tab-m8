package io.uday.lumiawall;

import android.app.Activity;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Lumia Wall: a Windows Phone 8 style panorama home for the wall-mounted tablet. */
public class WallActivity extends Activity {
    // Where things live (see the project docs).
    static final String GRACE = "192.168.1.10";
    static final double LAT = 18.52, LON = 73.86;          // Pune

    static final String PKG_SPACEDESK = "ph.spacedesk.beta";
    static final String PKG_KOREADER = "org.koreader.launcher";
    static final String PKG_MIHON = "app.mihon";

    // Tile grid: 1x1 unit + gap, in dp. 4 rows fit under the panorama headers at 800 px tall.
    static final int UNIT = 86, GAP = 6;

    // Panorama section order.
    // "windows" sits left of home, like a WP panorama you can swipe back from; grace wall stays the home section.
    static final int SEC_WINDOWS = 0, SEC_HOME = 1, SEC_MUSIC = 2, SEC_LYRICS = 3, SEC_STATUS = 4;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Handler bg;
    private final Random rnd = new Random();
    private final List<View> tiles = new ArrayList<>();
    private final List<Tile> flippers = new ArrayList<>();
    private Panorama pano;
    private ActionCenter center;
    private DaySheet sheet;
    private boolean panelGesture;
    private boolean launching;

    // live views
    private TextView clockTime, clockDate, clockBackDay, clockBackWeek;
    private TextView wTemp, wText, wBackHiLo, wBackHum;
    private TextView camBack, graceBack, battBack;
    private Glyph battGlyph;
    private TextView stTablet, stCamera, stGrace, stWifi;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Metro.density = getResources().getDisplayMetrics().density;
        Metro.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Start the camera streamer while we're in front: a camera foreground service started from the
        // foreground keeps camera access later, when spacedesk or a reader is on screen.
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && ActionCenter.cameraEnabled(this)) {                // it can be switched off in the action center
            CameraStreamService.start(this);
        }

        EdgeService.start(this);                                  // top-edge swipe = notifications (status bar is hidden)

        HandlerThread t = new HandlerThread("probes");
        t.start();
        bg = new Handler(t.getLooper());

        pano = new Panorama(this, "da wall");
        windows = new WindowsSection(this, pano.addSection("windows", 820));
        buildStart(pano.addSection("grace wall", 8 * UNIT + 7 * GAP + 40));
        buildMusic(pano.addSection("music", 780));
        buildLyrics(pano.addSection("lyrics", 700));
        buildStatus(pano.addSection("status", 600));
        buildApps(pano.addSection("apps", 520));
        FrameLayout root = new FrameLayout(this);
        root.addView(pano, new FrameLayout.LayoutParams(-1, -1));
        View grab = new View(this);                                // hint: pull down here for the action center
        grab.setBackgroundColor(0x33FFFFFF);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(Metro.dp(40), Metro.dp(3), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        gl.topMargin = Metro.dp(6);
        root.addView(grab, gl);
        View grabBottom = new View(this);                          // ...and pull up here for the day sheet
        grabBottom.setBackgroundColor(0x33FFFFFF);
        FrameLayout.LayoutParams gb = new FrameLayout.LayoutParams(Metro.dp(40), Metro.dp(3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        gb.bottomMargin = Metro.dp(6);
        root.addView(grabBottom, gb);
        center = new ActionCenter(this);
        root.addView(center, new FrameLayout.LayoutParams(-1, -1));
        sheet = new DaySheet(this);
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        showState(new MusicBridge.State());   // after all sections exist: it feeds music, lyrics and the tile
        pano.onScroll = () -> { updateSpectrum(); updateWindows(); };
        pano.homeSection = SEC_HOME;                               // open on grace wall, not the leftmost section
    }

    // ---------------------------------------------------------------- start

    private void buildStart(FrameLayout body) {
        // clock (wide)
        Tile clock = place(body, new Tile(this, Metro.COBALT, 4, 2, "clock"), 0, 0);
        LinearLayout cs = Tile.stack(this, clock.front);
        clockTime = Tile.text(this, "", 58, Metro.light, Metro.TEXT);
        clockDate = Tile.text(this, "", 18, Metro.semilight, Metro.TEXT_DIM);
        cs.addView(clockTime); cs.addView(clockDate);
        LinearLayout cb = Tile.stack(this, clock.back);
        clockBackDay = Tile.text(this, "", 34, Metro.light, Metro.TEXT);
        clockBackWeek = Tile.text(this, "", 16, Metro.semilight, Metro.TEXT_DIM);
        cb.addView(clockBackDay); cb.addView(clockBackWeek);
        clock.flippable = true; flippers.add(clock);

        // weather (medium)
        Tile weather = place(body, new Tile(this, Metro.TEAL, 2, 2, "pune"), 4, 0);
        LinearLayout ws = Tile.stack(this, weather.front);
        wTemp = Tile.text(this, "--°", 46, Metro.light, Metro.TEXT);
        wText = Tile.text(this, "", 15, Metro.semilight, Metro.TEXT_DIM);
        ws.addView(wTemp); ws.addView(wText);
        LinearLayout wb = Tile.stack(this, weather.back);
        wBackHiLo = Tile.text(this, "", 22, Metro.light, Metro.TEXT);
        wBackHum = Tile.text(this, "", 15, Metro.semilight, Metro.TEXT_DIM);
        wb.addView(wBackHiLo); wb.addView(wBackHum);
        weather.flippable = true; flippers.add(weather);
        weather.setOnClickListener(v -> refreshWeather());

        // display (medium) -> spacedesk
        Tile display = place(body, new Tile(this, Metro.CRIMSON, 2, 2, "display"), 6, 0);
        Tile.centredGlyph(this, display.front, Glyph.MONITOR, 58);
        display.setOnClickListener(v -> launch(PKG_SPACEDESK));

        // music (wide) -> the music section; goes live (cover + track) while the laptop plays
        Tile music = place(body, new Tile(this, Metro.VIOLET, 4, 2, "music"), 0, 2);
        musicTileArt = new android.widget.ImageView(this);
        musicTileArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        musicTileArt.setAlpha(0.45f);
        music.front.addView(musicTileArt, new FrameLayout.LayoutParams(-1, -1));
        musicTileGlyph = Tile.centredGlyph(this, music.front, Glyph.MUSIC, 58);
        LinearLayout mt = Tile.stack(this, music.front);
        musicTileTitle = Tile.text(this, "", 24, Metro.light, Metro.TEXT);
        musicTileTitle.setMaxLines(2);
        musicTileArtist = Tile.text(this, "", 15, Metro.semilight, Metro.TEXT_DIM);
        musicTileArtist.setSingleLine(true);
        mt.addView(musicTileTitle); mt.addView(musicTileArtist);
        music.setOnClickListener(v -> pano.scrollToSection(SEC_MUSIC));

        // reader (medium) -> KOReader
        Tile reader = place(body, new Tile(this, Metro.EMERALD, 2, 2, "reader"), 4, 2);
        Tile.centredGlyph(this, reader.front, Glyph.BOOK, 58);
        reader.setOnClickListener(v -> launch(PKG_KOREADER));

        // small tiles: manga, camera, grace, battery
        Tile manga = place(body, new Tile(this, Metro.AMBER, 1, 1, "manga"), 6, 2);
        Tile.centredGlyph(this, manga.front, Glyph.MANGA, 40);
        manga.setOnClickListener(v -> launch(PKG_MIHON));

        Tile cam = place(body, new Tile(this, Metro.STEEL, 1, 1, "camera"), 7, 2);
        Tile.centredGlyph(this, cam.front, Glyph.CAMERA, 40);
        camBack = smallBack(cam);
        cam.flippable = true; flippers.add(cam);
        cam.setOnClickListener(v -> pano.scrollToSection(SEC_STATUS));

        Tile grace = place(body, new Tile(this, Metro.MAUVE, 1, 1, "grace"), 6, 3);
        Tile.centredGlyph(this, grace.front, Glyph.SERVER, 40);
        graceBack = smallBack(grace);
        grace.flippable = true; flippers.add(grace);
        grace.setOnClickListener(v -> pano.scrollToSection(SEC_STATUS));

        Tile batt = place(body, new Tile(this, Metro.TAUPE, 1, 1, "battery"), 7, 3);
        battGlyph = (Glyph) Tile.centredGlyph(this, batt.front, Glyph.BATTERY, 44);
        battBack = smallBack(batt);
        batt.flippable = true; flippers.add(batt);
        batt.setOnClickListener(v -> pano.scrollToSection(SEC_STATUS));
    }

    private Tile place(FrameLayout body, Tile t, int col, int row) {
        int w = t.cols * UNIT + (t.cols - 1) * GAP, h = t.rows * UNIT + (t.rows - 1) * GAP;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Metro.dp(w), Metro.dp(h));
        lp.leftMargin = Metro.dp(col * (UNIT + GAP));
        lp.topMargin = Metro.dp(row * (UNIT + GAP));
        body.addView(t, lp);
        tiles.add(t);
        return t;
    }

    private TextView smallBack(Tile t) {
        TextView v = Tile.text(this, "", 15, Metro.semilight, Metro.TEXT);
        v.setGravity(Gravity.CENTER);
        t.back.addView(v, new FrameLayout.LayoutParams(-1, -1));
        return v;
    }

    // ---------------------------------------------------------------- music (laptop now playing via WallBridge)

    private android.widget.ImageView musicTileArt, npArt;
    private View musicTileGlyph, npArtGlyph;
    private TextView musicTileTitle, musicTileArtist;
    private TextView npTitle, npArtist, npMeta, npTime;
    private View npProgressFill;
    private FrameLayout npProgress;
    private Glyph npPlayGlyph;
    private VisView vis;
    private MusicBridge bridge;
    private boolean resumed;

    private void buildMusic(FrameLayout body) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        // cover
        FrameLayout artBox = new FrameLayout(this);
        artBox.setBackgroundColor(Metro.STEEL);
        npArt = new android.widget.ImageView(this);
        npArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        artBox.addView(npArt, new FrameLayout.LayoutParams(-1, -1));
        npArtGlyph = Tile.centredGlyph(this, artBox, Glyph.MUSIC, 70);
        row.addView(artBox, new LinearLayout.LayoutParams(Metro.dp(214), Metro.dp(214)));

        // text + transport
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        npTitle = Tile.text(this, "", 34, Metro.light, Metro.TEXT);
        npTitle.setMaxLines(2);
        npArtist = Tile.text(this, "", 20, Metro.semilight, Metro.TEXT_DIM);
        npArtist.setSingleLine(true);
        npMeta = Tile.text(this, "", 14, Metro.semilight, Metro.TEXT_FAINT);
        npMeta.setSingleLine(true);
        col.addView(npTitle);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = Metro.dp(4);
        col.addView(npArtist, al);
        col.addView(npMeta, new LinearLayout.LayoutParams(-1, -2));

        npProgress = new FrameLayout(this);
        npProgress.setBackgroundColor(Metro.TEXT_FAINT);
        npProgressFill = new View(this);
        npProgressFill.setBackgroundColor(Metro.VIOLET | 0xFF000000);
        npProgressFill.setPivotX(0);
        npProgressFill.setScaleX(0f);
        npProgress.addView(npProgressFill, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, Metro.dp(3));
        pl.topMargin = Metro.dp(16);
        col.addView(npProgress, pl);
        npTime = Tile.text(this, "", 13, Metro.semilight, Metro.TEXT_FAINT);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = Metro.dp(4);
        col.addView(npTime, tl);

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.addView(roundButton(Glyph.PREV, () -> bridge.command("prev")));
        View play = roundButton(Glyph.PLAY, () -> bridge.command("playpause"));
        npPlayGlyph = (Glyph) ((FrameLayout) play).getChildAt(0);
        transport.addView(play);
        transport.addView(roundButton(Glyph.NEXT, () -> bridge.command("next")));
        LinearLayout.LayoutParams trl = new LinearLayout.LayoutParams(-2, -2);
        trl.topMargin = Metro.dp(12);
        col.addView(transport, trl);

        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = Metro.dp(22);
        row.addView(col, cl);
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));

        vis = new VisView(this, Metro.VIOLET | 0xFF000000);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-1, Metro.dp(104));
        vl.topMargin = Metro.dp(18);
        root.addView(vis, vl);

        body.addView(root, new FrameLayout.LayoutParams(-1, -1));

        bridge = new MusicBridge(new MusicBridge.Listener() {
            @Override public void onState(MusicBridge.State s) { showState(s); }
            @Override public void onArt(android.graphics.Bitmap art) { showArt(art); }
            @Override public void onLyrics(MusicBridge.Lyrics l) { showLyrics(l); }
        }, vis);
    }

    /** WP8 transport button: outlined circle with a glyph. */
    private View roundButton(int glyph, final Runnable action) {
        FrameLayout f = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setStroke(Metro.dp(2), Metro.TEXT);
        f.setBackground(d);
        Glyph g = new Glyph(this, glyph);
        f.addView(g, new FrameLayout.LayoutParams(Metro.dp(26), Metro.dp(26), Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Metro.dp(54), Metro.dp(54));
        lp.rightMargin = Metro.dp(18);
        f.setLayoutParams(lp);
        f.setOnTouchListener(Metro.TILT);
        f.setOnClickListener(v -> action.run());
        return f;
    }

    private void showState(MusicBridge.State s) {
        boolean has = s.connected && !s.title.isEmpty();
        if (!s.connected) {
            npTitle.setText("laptop not connected");
            npArtist.setText("start WallBridge on the laptop (it runs at sign-in)");
            npMeta.setText("");
        } else if (!has) {
            npTitle.setText("nothing playing");
            npArtist.setText("play something on the laptop");
            npMeta.setText("");
        } else {
            npTitle.setText(s.title.toLowerCase(Locale.getDefault()));
            npArtist.setText(s.artist.isEmpty() ? s.app : s.artist.toLowerCase(Locale.getDefault()));
            StringBuilder meta = new StringBuilder();
            if (!s.album.isEmpty()) meta.append(s.album.toLowerCase(Locale.getDefault()));
            if (!s.app.isEmpty() && !s.artist.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append(s.app);
            npMeta.setText(meta.toString());
        }
        float frac = s.durationMs > 0 ? Math.min(1f, (float) s.positionMs / s.durationMs) : 0f;
        npProgressFill.setScaleX(frac);
        npTime.setText(s.durationMs > 0 ? fmt(s.positionMs) + " / " + fmt(s.durationMs) : "");
        npPlayGlyph.setKind(s.playing ? Glyph.PAUSE : Glyph.PLAY);
        lyrics.setPosition(s.positionMs, s.playing);
        if (s.estimated != lyricsEstimated) { lyricsEstimated = s.estimated; refreshLyricsLabel(); }
        if (!has) showLyrics(null);

        // live music tile on "grace wall"
        musicTileTitle.setText(has ? s.title.toLowerCase(Locale.getDefault()) : "");
        musicTileArtist.setText(has ? (s.artist.isEmpty() ? s.app : s.artist.toLowerCase(Locale.getDefault())) : "");
        musicTileGlyph.setVisibility(has ? View.INVISIBLE : View.VISIBLE);
        musicTileArt.setVisibility(has ? View.VISIBLE : View.INVISIBLE);

        lastState = s;
        updateSpectrum();
    }

    private WindowsSection windows;
    private final android.graphics.Rect winRect = new android.graphics.Rect();

    /** The laptop controls poll only while their section is actually in view. */
    private void updateWindows() {
        View col = (View) pano.sections.getChildAt(SEC_WINDOWS);
        windows.setActive(resumed && col.getGlobalVisibleRect(winRect) && winRect.width() > Metro.dp(120));
    }

    private MusicBridge.State lastState = new MusicBridge.State();
    private final android.graphics.Rect visRect = new android.graphics.Rect();

    /** The visualiser (and the laptop's capture feeding it) runs only while music plays AND its section is in view. */
    private void updateSpectrum() {
        bridge.setSpectrum(resumed && lastState.connected && lastState.playing && vis.getGlobalVisibleRect(visRect));
    }

    private void showArt(android.graphics.Bitmap art) {
        npArt.setImageBitmap(art);
        musicTileArt.setImageBitmap(art);
        npArtGlyph.setVisibility(art == null ? View.VISIBLE : View.INVISIBLE);
    }

    // ---------------------------------------------------------------- lyrics (LRCLIB via WallBridge)

    private LyricsView lyrics;
    private TextView lyricsTrack;
    private boolean lyricsIdle, lyricsEstimated;
    private String lyricsLabel = "";

    private void refreshLyricsLabel() {
        lyricsTrack.setText(lyricsLabel.isEmpty() ? "" : lyricsEstimated
                ? lyricsLabel + "  ·  timing estimated, tap a line to sync" : lyricsLabel);
    }

    private void buildLyrics(FrameLayout body) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        lyricsTrack = Tile.text(this, "", 16, Metro.semilight, Metro.TEXT_FAINT);
        lyricsTrack.setSingleLine(true);
        root.addView(lyricsTrack, new LinearLayout.LayoutParams(-1, -2));
        lyrics = new LyricsView(this, Metro.VIOLET);
        lyrics.setOnSeek(ms -> bridge.seek(ms, () ->
                Toast.makeText(this, "this player can't jump to a line", Toast.LENGTH_SHORT).show()));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 0, 1f);
        ll.topMargin = Metro.dp(8);
        ll.rightMargin = Metro.dp(24);
        root.addView(lyrics, ll);
        body.addView(root, new FrameLayout.LayoutParams(-1, -1));
        showLyrics(null);
    }

    /**
     * {@code null} = nothing playing / not connected. The lyrics column exists only while the song has lyrics:
     * it's hidden for "none", "error" and when idle, and emptied (not hidden, to avoid a flicker) while loading.
     */
    private void showLyrics(MusicBridge.Lyrics l) {
        String source = l == null ? "idle" : l.source;
        boolean has = source.equals("synced") || (source.equals("plain") && !l.plain.trim().isEmpty());
        if (l == null) {
            if (lyricsIdle) return;
            lyricsIdle = true;
        } else {
            lyricsIdle = false;
        }
        if (has) {
            String[] parts = l.key.split("\\|", 2);
            lyricsLabel = (parts[0] + (parts.length > 1 && !parts[1].isEmpty() ? " · " + parts[1] : ""))
                    .toLowerCase(Locale.getDefault());
            refreshLyricsLabel();
            if (source.equals("synced")) lyrics.setLyrics(l.times, l.lines, true, "");
            else lyrics.setLyrics(new long[0], l.plain.split("\r?\n", -1), false, "");
        } else {
            lyricsLabel = "";
            refreshLyricsLabel();
            lyrics.setLyrics(new long[0], new String[0], false, "");
        }
        if (!source.equals("loading")) setLyricsShown(has);
    }

    /**
     * Shows/hides the lyrics column without yanking the view: sections after it would shift by its width, so if
     * one of those is on screen the scroll moves with it; if the lyrics themselves were on screen, glide to music.
     */
    private void setLyricsShown(boolean show) {
        final View col = (View) lyrics.getParent().getParent().getParent();   // root -> body -> section column
        if ((col.getVisibility() == View.VISIBLE) == show) return;
        final int w = col.getLayoutParams().width;
        final int left = pano.sections.getChildAt(SEC_MUSIC).getRight();
        if (!pano.isLaidOut() || left == 0) {          // first layout hasn't happened: nothing on screen to keep steady
            col.setVisibility(show ? View.VISIBLE : View.GONE);
            return;
        }
        final int x = pano.getScrollX();
        final int delta = show ? (x >= left ? w : 0) : (x >= left + w / 2 ? -w : 0);
        final boolean backToMusic = !show && x > left - w / 2 && x < left + w / 2;
        col.setVisibility(show ? View.VISIBLE : View.GONE);
        pano.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                pano.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (delta != 0) pano.scrollTo(x + delta, 0);
                else if (backToMusic) pano.scrollToSection(SEC_MUSIC);
            }
        });
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ---------------------------------------------------------------- status

    private void buildStatus(FrameLayout body) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        stTablet = statusRow(l, "tablet");
        stCamera = statusRow(l, "camera server");
        stGrace = statusRow(l, "grace");
        stWifi = statusRow(l, "wi-fi");
        body.addView(l, new FrameLayout.LayoutParams(-1, -2));
    }

    private TextView statusRow(LinearLayout parent, String title) {
        parent.addView(Tile.text(this, title, 24, Metro.light, Metro.TEXT));
        TextView detail = Tile.text(this, "…", 16, Metro.semilight, Metro.TEXT_DIM);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Metro.dp(16);
        lp.topMargin = Metro.dp(2);
        parent.addView(detail, lp);
        return detail;
    }

    // ---------------------------------------------------------------- apps (WP8 app list)

    private LinearLayout appList;
    private int appCount = -1;
    private final List<View> appRows = new ArrayList<>();

    private void buildApps(FrameLayout body) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appList.setPadding(0, 0, 0, Metro.dp(24));
        scroll.addView(appList, new FrameLayout.LayoutParams(-1, -2));
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Rebuilds the list when the set of launchable apps changed (installs/uninstalls). */
    private void refreshApps() {
        final android.content.pm.PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        if (apps.size() == appCount) return;
        appCount = apps.size();

        final java.text.Collator col = java.text.Collator.getInstance();
        java.util.Collections.sort(apps, (a, b) -> col.compare(
                String.valueOf(a.loadLabel(pm)).toLowerCase(Locale.getDefault()),
                String.valueOf(b.loadLabel(pm)).toLowerCase(Locale.getDefault())));

        appList.removeAllViews();
        tiles.removeAll(appRows);
        appRows.clear();
        String lastLetter = "";
        for (final android.content.pm.ResolveInfo ri : apps) {
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;   // not ourselves
            String name = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.getDefault());
            String letter = name.isEmpty() ? "#" : name.substring(0, 1);
            if (!Character.isLetter(letter.charAt(0))) letter = "#";
            if (!letter.equals(lastLetter)) {
                appList.addView(letterTile(letter));
                lastLetter = letter;
            }
            appList.addView(appRow(ri, name, pm));
        }
    }

    /** WP8 jump-list header: a small outlined accent square with the letter bottom-left. */
    private View letterTile(String letter) {
        FrameLayout f = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0x00000000);
        d.setStroke(Metro.dp(2), Metro.COBALT);
        f.setBackground(d);
        TextView t = Tile.text(this, letter, 24, Metro.light, Metro.TEXT);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        tl.setMargins(Metro.dp(6), 0, 0, Metro.dp(2));
        f.addView(t, tl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Metro.dp(44), Metro.dp(44));
        lp.setMargins(0, Metro.dp(14), 0, Metro.dp(8));
        f.setLayoutParams(lp);
        return f;
    }

    private View appRow(final android.content.pm.ResolveInfo ri, String name, android.content.pm.PackageManager pm) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // Icon on an accent square, like WP8's small app tiles.
        FrameLayout sq = new FrameLayout(this);
        sq.setBackgroundColor(Metro.COBALT);
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageDrawable(ri.loadIcon(pm));
        sq.addView(icon, new FrameLayout.LayoutParams(Metro.dp(34), Metro.dp(34), Gravity.CENTER));
        row.addView(sq, new LinearLayout.LayoutParams(Metro.dp(44), Metro.dp(44)));

        TextView label = Tile.text(this, name, 22, Metro.light, Metro.TEXT);
        label.setSingleLine(true);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.leftMargin = Metro.dp(14);
        row.addView(label, ll);

        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, Metro.dp(52));
        row.setLayoutParams(rl);
        row.setOnTouchListener(Metro.TILT);
        row.setOnClickListener(v -> launch(ri.activityInfo.packageName));
        tiles.add(row);   // rows swing out with the turnstile too
        appRows.add(row);
        return row;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onResume() {
        super.onResume();
        hideSystemBars();
        launching = false;
        resumed = true;
        pano.post(this::updateWindows);
        bridge.start();
        ui.post(tick);
        ui.postDelayed(flipper, 6000);
        bg.post(this::probeAll);
        // the camera service may still be binding its port right after a (re)start
        ui.postDelayed(() -> bg.post(this::probeAll), 4000);
        refreshWeather();
        refreshApps();
        // swing the tiles in
        pano.post(() -> { Metro.resetTurnstile(tiles); Metro.turnstile(tiles, true, null); });
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(flipper);
        resumed = false;
        windows.setActive(false);
        bridge.stop();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /**
     * A downward drag that starts in the title band (above the section content) pulls down the action center;
     * an upward drag from the bottom edge pulls up the day sheet (Keep + Calendar).
     */
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        boolean pulled = center != null && !sheet.isOpen() && center.interceptPull(e, Metro.dp(150));
        if (!pulled && sheet != null && !center.isOpen())
            pulled = sheet.interceptPull(e, getWindow().getDecorView().getHeight() - Metro.dp(56));
        if (pulled) {
            if (!panelGesture) {                                   // take the gesture away from the panorama/tiles
                panelGesture = true;
                android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
                c.setAction(android.view.MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(c);
                c.recycle();
            }
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) panelGesture = false;
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    @Override public void onBackPressed() {
        if (center.isOpen()) { center.close(); return; }
        if (sheet.isOpen()) { sheet.close(); return; }
        pano.scrollToSection(SEC_HOME);   // home screen: back just returns to grace wall
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        pano.scrollToSection(SEC_HOME);   // pressing home while home = back to grace wall
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ---------------------------------------------------------------- periodic work

    private final SimpleDateFormat fTime = new SimpleDateFormat("H:mm", Locale.getDefault());
    private final SimpleDateFormat fDate = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault());
    private final SimpleDateFormat fDay = new SimpleDateFormat("EEEE", Locale.getDefault());
    private final SimpleDateFormat fWeek = new SimpleDateFormat("'week' w · 'day' D", Locale.getDefault());
    private long lastProbe;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            clockTime.setText(fTime.format(now));
            clockDate.setText(fDate.format(now).toLowerCase(Locale.getDefault()));
            clockBackDay.setText(fDay.format(now).toLowerCase(Locale.getDefault()));
            clockBackWeek.setText(fWeek.format(now));
            if (System.currentTimeMillis() - lastProbe > 60_000) bg.post(WallActivity.this::probeAll);
            ui.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000));
        }
    };

    /** Every 6–11 s one random live tile flips, like a WP start screen at rest. */
    private final Runnable flipper = new Runnable() {
        @Override public void run() {
            if (!flippers.isEmpty() && !launching) flippers.get(rnd.nextInt(flippers.size())).flip();
            ui.postDelayed(this, 6000 + rnd.nextInt(5000));
        }
    };

    private long lastWeather;

    private void refreshWeather() {
        if (System.currentTimeMillis() - lastWeather < 15 * 60_000 && wTemp.getText().length() > 3) return;
        lastWeather = System.currentTimeMillis();
        bg.post(() -> {
            final Probes.Weather w = Probes.weather(LAT, LON);
            ui.post(() -> {
                if (w == null) { wText.setText("no connection"); lastWeather = 0; return; }
                wTemp.setText(w.temp + "°");
                wText.setText(w.summary());
                wBackHiLo.setText("↑ " + w.high + "°   ↓ " + w.low + "°");
                wBackHum.setText("humidity " + w.humidity + "%");
            });
        });
    }

    /** Runs on the background thread. */
    private void probeAll() {
        lastProbe = System.currentTimeMillis();
        final Probes.Battery b = Probes.battery(this);
        String camState;
        try {
            camState = new org.json.JSONObject(Probes.get("http://127.0.0.1:8080/status.json", 1500)).optString("camera", "idle");
        } catch (Exception e) {
            camState = "down";
        }
        final String camFinal = camState;
        final boolean komga = Probes.open(GRACE, 25600, 1500);
        final boolean jelly = Probes.open(GRACE, 8096, 1500);
        final boolean ssh = Probes.open(GRACE, 22, 1500);
        final String ip = wifiIp();
        ui.post(() -> {
            String state = b.charging ? "charging" : (b.plugged ? "holding (charge limit 50–60%)" : "on battery");
            stTablet.setText(b.percent + "% · " + state);
            battGlyph.setLevel(b.percent / 100f);
            battBack.setText(b.percent + "%");

            switch (camFinal) {
                case "open": stCamera.setText("live · the laptop is using the front camera"); camBack.setText("live"); break;
                case "idle": stCamera.setText("ready · front camera starts when the laptop asks"); camBack.setText("ready"); break;
                default:
                    stCamera.setText(ActionCenter.cameraEnabled(this) ? "not running" : "off · switched off in the action center");
                    camBack.setText("off");
            }

            boolean any = ssh || komga || jelly;
            stGrace.setText(!any ? "unreachable" :
                    (ssh ? "ssh ✓" : "ssh ✗") + "   " + (komga ? "komga ✓" : "komga ✗") + "   " + (jelly ? "jellyfin ✓" : "jellyfin ✗"));
            graceBack.setText(any ? "up" : "down");

            stWifi.setText(ip == null ? "not connected" : ip);
        });
    }

    @SuppressWarnings("deprecation")
    private String wifiIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int a = wm.getConnectionInfo().getIpAddress();
            if (a == 0) return null;
            return (a & 0xff) + "." + (a >> 8 & 0xff) + "." + (a >> 16 & 0xff) + "." + (a >> 24 & 0xff);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- launching

    private void launch(String pkg) {
        final Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { Toast.makeText(this, pkg + " isn't installed", Toast.LENGTH_SHORT).show(); return; }
        if (launching) return;
        launching = true;
        Metro.turnstile(tiles, false, () -> {
            startActivity(i);
            overridePendingTransition(0, 0);
        });
    }
}
