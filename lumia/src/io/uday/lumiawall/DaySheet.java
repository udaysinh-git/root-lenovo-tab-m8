package io.uday.lumiawall;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pull up from the bottom edge: the day at a glance.
 *   left 2/3   Google Keep: note list + the open note (tap a note, or swipe the open note sideways to move through them)
 *   right 1/3  Google Calendar: today (past events dimmed, the current one marked "now") and tomorrow
 * Data comes from the laptop (WallBridge /keep and /calendar, synced by keepcal.py); refreshed on open and every minute.
 */
final class DaySheet extends FrameLayout {
    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler bg;
    private final View scrim;
    private final LinearLayout sheet;
    private final LinearLayout rail, detail, agenda;
    private final ScrollView railScroll, detailScroll, agendaScroll;
    private final TextView notesMeta, todayDate;
    private float openness;
    private ValueAnimator anim;

    private final List<JSONObject> notes = new ArrayList<>();
    private Glyph refreshBtn;
    private android.animation.ObjectAnimator spin;
    private String selectedId;
    private String lastKeep = "", lastCal = "";

    // pull gesture
    private float downX, downY;
    private boolean tracking, pulling;
    private VelocityTracker vt;
    private final int slop;

    DaySheet(Activity a) {
        super(a);
        act = a;
        slop = ViewConfiguration.get(a).getScaledTouchSlop();
        HandlerThread t = new HandlerThread("day-sheet");
        t.start();
        bg = new Handler(t.getLooper());

        scrim = new View(a);
        scrim.setBackgroundColor(0xFF000000);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(-1, -1));

        sheet = new LinearLayout(a);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(0xFF101010);
        sheet.setClickable(true);

        View handle = new View(a);
        handle.setBackgroundColor(Metro.TEXT_FAINT);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(Metro.dp(44), Metro.dp(3));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.topMargin = Metro.dp(8);
        sheet.addView(handle, hl);

        LinearLayout cols = new LinearLayout(a);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setPadding(Metro.dp(22), Metro.dp(4), Metro.dp(22), Metro.dp(14));

        // ---- left 2/3: keep
        LinearLayout keep = new LinearLayout(a);
        keep.setOrientation(LinearLayout.VERTICAL);
        LinearLayout kh = header(a, "notes");
        notesMeta = (TextView) kh.getChildAt(1);
        keep.addView(kh);
        LinearLayout kbody = new LinearLayout(a);
        kbody.setOrientation(LinearLayout.HORIZONTAL);
        rail = vertical(a);
        railScroll = scroller(a, rail);
        kbody.addView(railScroll, new LinearLayout.LayoutParams(0, -1, 0.36f));
        detail = vertical(a);
        detail.setPadding(Metro.dp(22), 0, Metro.dp(10), Metro.dp(20));
        detailScroll = scroller(a, detail);
        kbody.addView(detailScroll, new LinearLayout.LayoutParams(0, -1, 0.64f));
        keep.addView(kbody, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, -1, 2f);
        kl.rightMargin = Metro.dp(22);
        cols.addView(keep, kl);

        // ---- right 1/3: calendar
        LinearLayout cal = new LinearLayout(a);
        cal.setOrientation(LinearLayout.VERTICAL);
        LinearLayout ch = header(a, "today");
        todayDate = (TextView) ch.getChildAt(1);
        LinearLayout.LayoutParams tdl = (LinearLayout.LayoutParams) todayDate.getLayoutParams();
        tdl.width = 0;
        tdl.weight = 1f;
        // refresh: asks the laptop to sync with Google now (not just re-read its cache); spins until fresh data lands
        refreshBtn = new Glyph(a, Glyph.REFRESH);
        refreshBtn.setOnTouchListener(Metro.TILT);
        refreshBtn.setOnClickListener(v -> forceRefresh());
        LinearLayout.LayoutParams rb = new LinearLayout.LayoutParams(Metro.dp(30), Metro.dp(30));
        rb.bottomMargin = Metro.dp(9);
        ch.addView(refreshBtn, rb);
        cal.addView(ch);
        agenda = vertical(a);
        agendaScroll = scroller(a, agenda);
        cal.addView(agendaScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        cols.addView(cal, new LinearLayout.LayoutParams(0, -1, 1f));

        sheet.addView(cols, new LinearLayout.LayoutParams(-1, 0, 1f));

        // swipe the open note sideways to move through notes
        final GestureDetector swipe = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || Math.abs(vx) < 900 || Math.abs(vx) < Math.abs(vy) * 1.5f) return false;
                step(vx < 0 ? 1 : -1);
                return true;
            }
        });
        detailScroll.setOnTouchListener((v, e) -> { swipe.onTouchEvent(e); return false; });

        LayoutParams sl = new LayoutParams(-1, -1, Gravity.BOTTOM);
        sl.topMargin = Metro.dp(44);                              // a strip of the home screen stays visible above
        addView(sheet, sl);
        setVisibility(GONE);
        applyOpenness(0);
        showKeep(null, "");
        showCalendar(null, "");
    }

    // ------------------------------------------------------------------ layout helpers

    private static LinearLayout header(Context c, String title) {
        LinearLayout h = new LinearLayout(c);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.BOTTOM);
        h.addView(Tile.text(c, title, 34, Metro.light, Metro.TEXT));
        TextView meta = Tile.text(c, "", 15, Metro.semilight, Metro.TEXT_FAINT);
        meta.setSingleLine(true);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.leftMargin = Metro.dp(12);
        ml.bottomMargin = Metro.dp(7);
        h.addView(meta, ml);
        h.setPadding(0, 0, 0, Metro.dp(8));
        return h;
    }

    private static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static ScrollView scroller(Context c, View content) {
        ScrollView s = new ScrollView(c);
        s.setVerticalScrollBarEnabled(false);
        s.setOverScrollMode(OVER_SCROLL_NEVER);
        s.setFadingEdgeLength(Metro.dp(24));
        s.setVerticalFadingEdgeEnabled(true);
        s.addView(content, new LayoutParams(-1, -2));
        return s;
    }

    // ------------------------------------------------------------------ opening / closing

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Pull-up detection for the home screen; {@code bandTopPx} = where the bottom band starts. */
    boolean interceptPull(MotionEvent e, float bandTopPx) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = !isOpen() && e.getY() > bandTopPx;
                pulling = false;
                downX = e.getX(); downY = e.getY();
                if (tracking) { vt = VelocityTracker.obtain(); vt.addMovement(e); }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false;
                vt.addMovement(e);
                float dy = downY - e.getY(), dx = e.getX() - downX;
                if (!pulling && dy > slop && dy > Math.abs(dx) * 1.5f) {
                    pulling = true;
                    setVisibility(VISIBLE);
                    refresh();
                }
                if (pulling) applyOpenness(Math.max(0f, Math.min(1f, dy / Math.max(1, sheet.getHeight()))));
                return pulling;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean was = pulling;
                if (pulling) {
                    vt.computeCurrentVelocity(1000);
                    float v = -vt.getYVelocity() / Metro.density;
                    if (v > 250 || (v > -250 && openness > 0.3f)) open(); else close();
                }
                tracking = pulling = false;
                if (vt != null) { vt.recycle(); vt = null; }
                return was;
        }
        return false;
    }

    void open() {
        if (!isOpen()) { setVisibility(VISIBLE); refresh(); }
        animateTo(1f, 420, Metro.ENTER);
        ui.removeCallbacks(periodic);
        ui.postDelayed(periodic, 60_000);
    }

    void close() {
        ui.removeCallbacks(periodic);
        animateTo(0f, 260, new DecelerateInterpolator());
    }

    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            if (!isOpen()) return;
            refresh();
            ui.postDelayed(this, 60_000);
        }
    };

    private void animateTo(float target, long ms, android.animation.TimeInterpolator interp) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(openness, target);
        anim.setDuration(ms);
        anim.setInterpolator(interp);
        anim.addUpdateListener(v -> applyOpenness((Float) v.getAnimatedValue()));
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                if (target == 0f && openness == 0f) setVisibility(GONE);
            }
        });
        anim.start();
    }

    private void applyOpenness(float f) {
        openness = f;
        int h = sheet.getHeight() > 0 ? sheet.getHeight() : Metro.dp(500);
        sheet.setTranslationY(h * (1f - f));
        scrim.setAlpha(0.6f * f);
    }

    // Drag the sheet down by its top strip (handle + headers) to close; the lists inside keep their own scrolling.
    private float pDownY;
    private boolean pDragging;

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pDownY = e.getY();
                pDragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                boolean onTopStrip = pDownY < sheet.getTop() + sheet.getTranslationY() + Metro.dp(70);
                if (onTopStrip && e.getY() - pDownY > slop) { pDragging = true; return true; }
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!pDragging) return super.onTouchEvent(e);
        float dy = Math.max(0, e.getY() - pDownY);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                applyOpenness(Math.max(0f, 1f - dy / Math.max(1, sheet.getHeight())));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pDragging = false;
                if (openness < 0.8f) close(); else open();
                return true;
        }
        return true;
    }

    // ------------------------------------------------------------------ data

    private void forceRefresh() {
        if (spin != null && spin.isRunning()) return;
        final long asked = System.currentTimeMillis();
        spin = android.animation.ObjectAnimator.ofFloat(refreshBtn, View.ROTATION, 0f, 360f);
        spin.setDuration(900);
        spin.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        spin.setInterpolator(new android.view.animation.LinearInterpolator());
        spin.start();
        bg.post(() -> {
            try { Probes.get(MusicBridge.BASE + "/keep/refresh?note=all", 4000); }
            catch (Exception e) {
                ui.post(() -> {
                    stopSpin();
                    android.widget.Toast.makeText(act, "laptop not reachable", android.widget.Toast.LENGTH_SHORT).show();
                });
                return;
            }
            // wait until both files are newer than the request (a Google round-trip takes a few seconds)
            for (int i = 0; i < 20; i++) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                try {
                    long k = new JSONObject(Probes.get(MusicBridge.BASE + "/keep", 3000)).optLong("at");
                    long c = new JSONObject(Probes.get(MusicBridge.BASE + "/calendar", 3000)).optLong("at");
                    if (k >= asked - 2000 && c >= asked - 2000) break;
                } catch (Exception ignored) {}
            }
            ui.post(() -> { refresh(); stopSpin(); });
        });
    }

    private void stopSpin() {
        if (spin == null) return;
        spin.cancel();
        refreshBtn.animate().rotation(0).setDuration(200).start();
    }

    void refresh() {
        bg.post(() -> {
            String k = "", c = "";
            try { k = Probes.get(MusicBridge.BASE + "/keep", 4000); } catch (Exception ignored) {}
            try { c = Probes.get(MusicBridge.BASE + "/calendar", 4000); } catch (Exception ignored) {}
            final String fk = k, fc = c;
            ui.post(() -> {
                if (!fk.equals(lastKeep)) { lastKeep = fk; showKeep(parse(fk), fk.isEmpty() ? "laptop not connected" : ""); }
                if (!fc.equals(lastCal)) { lastCal = fc; showCalendar(parse(fc), fc.isEmpty() ? "laptop not connected" : ""); }
                todayDate.setText(new SimpleDateFormat("EEEE d", Locale.getDefault()).format(new Date()).toLowerCase(Locale.getDefault()));
            });
        });
    }

    private static JSONObject parse(String s) {
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ keep

    private void showKeep(JSONObject j, String problem) {
        notes.clear();
        rail.removeAllViews();
        JSONArray arr = j == null ? null : j.optJSONArray("notes");
        if (arr == null || arr.length() == 0) {
            String err = j == null ? problem : j.optString("error", "");
            notesMeta.setText("");
            detail.removeAllViews();
            detail.addView(Tile.text(getContext(),
                    err.equals("not signed in") ? "sign in once on the laptop (keepcal.py login, see docs/09)"
                            : err.isEmpty() ? "no notes" : err, 17, Metro.semilight, Metro.TEXT_DIM));
            return;
        }
        for (int i = 0; i < arr.length(); i++) notes.add(arr.optJSONObject(i));
        String err = j.optBoolean("ok", true) ? "" : "  ·  offline, showing last sync";
        notesMeta.setText(notes.size() + (notes.size() == 1 ? " note" : " notes") + err);
        boolean found = false;
        for (JSONObject n : notes) if (n.optString("id").equals(selectedId)) found = true;
        if (!found) selectedId = notes.get(0).optString("id");
        for (JSONObject n : notes) rail.addView(railRow(n));
        showNote(selectedId, 0);
    }

    private View railRow(final JSONObject n) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setTag(n.optString("id"));
        View bar = new View(getContext());
        bar.setBackgroundColor(keepColor(n.optString("color")));
        row.addView(bar, new LinearLayout.LayoutParams(Metro.dp(4), -1));
        LinearLayout txt = vertical(getContext());
        txt.setPadding(Metro.dp(10), Metro.dp(7), Metro.dp(8), Metro.dp(8));
        TextView title = Tile.text(getContext(), (n.optBoolean("pinned") ? "• " : "") + titleOf(n), 17,
                Metro.semilight, Metro.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView snip = Tile.text(getContext(), snippet(n), 13, Metro.semilight, Metro.TEXT_FAINT);
        snip.setSingleLine(true);
        snip.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(title);
        txt.addView(snip);
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.bottomMargin = Metro.dp(4);
        row.setLayoutParams(rl);
        row.setOnTouchListener(Metro.TILT);
        row.setOnClickListener(v -> showNote(n.optString("id"), 0));
        return row;
    }

    /** Move through notes from the open one: +1 next, -1 previous. */
    private void step(int dir) {
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).optString("id").equals(selectedId)) {
                int k = i + dir;
                if (k >= 0 && k < notes.size()) showNote(notes.get(k).optString("id"), dir);
                return;
            }
        }
    }

    private void showNote(String id, int dir) {
        JSONObject n = null;
        for (JSONObject x : notes) if (x.optString("id").equals(id)) n = x;
        if (n == null) return;
        selectedId = id;
        for (int i = 0; i < rail.getChildCount(); i++) {
            View r = rail.getChildAt(i);
            boolean sel = id.equals(r.getTag());
            r.setBackgroundColor(sel ? 0xFF1F1F1F : 0);
            r.setAlpha(sel ? 1f : 0.72f);
            if (sel && dir != 0) {
                final View target = r;
                railScroll.post(() -> railScroll.smoothScrollTo(0, Math.max(0, target.getTop() - Metro.dp(60))));
            }
        }
        detail.removeAllViews();
        TextView title = Tile.text(getContext(), titleOf(n), 30, Metro.light, Metro.TEXT);
        detail.addView(title);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
        gap.topMargin = Metro.dp(10);
        JSONArray items = n.optJSONArray("items");
        if (items != null) {
            // Keep's own layout: unchecked first, then the checked ones, dimmed
            LinearLayout list = vertical(getContext());
            int checked = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    boolean c = it.optBoolean("checked");
                    if (c != (pass == 1)) continue;
                    if (c) checked++;
                    if (c && checked == 1) {
                        TextView h = Tile.text(getContext(), "checked", 13, Metro.semilight, Metro.TEXT_FAINT);
                        h.setPadding(0, Metro.dp(12), 0, Metro.dp(4));
                        list.addView(h);
                    }
                    list.addView(checkRow(n, it, c));
                }
                if (pass == 0) list.addView(addRow(n));
            }
            detail.addView(list, gap);
        } else {
            TextView body = Tile.text(getContext(), n.optString("text"), 18, Metro.semilight, Metro.TEXT);
            body.setLineSpacing(Metro.dp(4), 1f);
            body.setTextIsSelectable(false);
            detail.addView(body, gap);
        }
        TextView when = Tile.text(getContext(), "edited " + ago(n.optLong("updated")), 13, Metro.semilight, Metro.TEXT_FAINT);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
        wl.topMargin = Metro.dp(18);
        detail.addView(when, wl);
        detailScroll.scrollTo(0, 0);
        // WP-style slide: the new note comes in from the swipe direction
        detail.setTranslationX(dir * Metro.dp(60));
        detail.setAlpha(dir == 0 ? 1f : 0f);
        detail.animate().translationX(0).alpha(1f).setDuration(320).setInterpolator(Metro.ENTER).start();
    }

    /** One checklist item. Tapping it ticks/unticks right away here and in Keep (via the laptop) a moment later. */
    private View checkRow(final JSONObject note, final JSONObject item, final boolean checked) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.TOP);                                  // box beside the first line, not mid-paragraph
        r.setPadding(Metro.dp(item.optInt("indent") * 24), Metro.dp(6), 0, Metro.dp(6));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Metro.dp(20), Metro.dp(20));
        bl.topMargin = Metro.dp(3);
        r.addView(new CheckBox(getContext(), checked), bl);
        TextView t = Tile.text(getContext(), item.optString("text"), 18, Metro.semilight, checked ? Metro.TEXT_FAINT : Metro.TEXT);
        if (checked) t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = Metro.dp(12);
        r.addView(t, tl);
        r.setOnTouchListener(Metro.TILT);
        r.setOnClickListener(v -> {
            try { item.put("checked", !checked); } catch (Exception ignored) {}
            showNote(note.optString("id"), 0);                      // re-sort: ticked items drop to "checked"
            send("/keep/check?note=" + enc(note.optString("id")) + "&item=" + enc(item.optString("id"))
                    + "&checked=" + (checked ? "0" : "1"));
        });
        return r;
    }

    /** "+ add item": becomes a text field; Done adds it to the list (here at once, in Keep a moment later). */
    private View addRow(final JSONObject note) {
        final FrameLayout box = new FrameLayout(getContext());
        final TextView hint = Tile.text(getContext(), "+  add item", 17, Metro.semilight, Metro.TEXT_FAINT);
        hint.setPadding(Metro.dp(2), Metro.dp(8), 0, Metro.dp(8));
        box.addView(hint);
        hint.setOnClickListener(v -> {
            final android.widget.EditText in = new android.widget.EditText(getContext());
            in.setTypeface(Metro.semilight);
            in.setTextSize(18);
            in.setTextColor(Metro.TEXT);
            in.setHintTextColor(Metro.TEXT_FAINT);
            in.setHint("new item");
            in.setSingleLine(true);
            in.setBackgroundColor(0xFF1F1F1F);
            in.setPadding(Metro.dp(10), Metro.dp(8), Metro.dp(10), Metro.dp(8));
            in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            box.removeAllViews();
            box.addView(in, new LayoutParams(-1, -2));
            in.requestFocus();
            final android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            in.post(() -> imm.showSoftInput(in, 0));
            in.setOnEditorActionListener((tv, action, ev) -> {
                String text = in.getText().toString().trim();
                imm.hideSoftInputFromWindow(in.getWindowToken(), 0);
                if (!text.isEmpty()) {
                    try {
                        JSONObject it = new JSONObject().put("id", "pending").put("text", text).put("checked", false);
                        JSONArray items = note.optJSONArray("items");
                        JSONArray nu = new JSONArray();
                        for (int i = 0; i < items.length(); i++) nu.put(items.optJSONObject(i));
                        nu.put(it);
                        note.put("items", nu);
                    } catch (Exception ignored) {}
                    send("/keep/add?note=" + enc(note.optString("id")) + "&text=" + enc(text));
                }
                showNote(note.optString("id"), 0);
                return true;
            });
        });
        return box;
    }

    /** Fire an edit at the laptop, then pick up the synced result (it's applied within ~2 s). */
    private void send(final String pathAndQuery) {
        bg.post(() -> {
            try { Probes.get(MusicBridge.BASE + pathAndQuery, 4000); }
            catch (Exception e) {
                ui.post(() -> android.widget.Toast.makeText(act, "laptop not reachable, edit not saved",
                        android.widget.Toast.LENGTH_SHORT).show());
            }
        });
        ui.postDelayed(this::refresh, 3000);
        ui.postDelayed(this::refresh, 7000);
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    /** Outlined square; checked = filled accent with a tick. */
    private static final class CheckBox extends View {
        private final boolean checked;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        CheckBox(Context c, boolean checked) { super(c); this.checked = checked; }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), s = Metro.dp(1.5f);
            if (checked) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Metro.STEEL);
                c.drawRect(0, 0, w, w, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 1.3f);
                p.setColor(Metro.TEXT_DIM);
                c.drawLine(w * 0.22f, w * 0.52f, w * 0.42f, w * 0.72f, p);
                c.drawLine(w * 0.42f, w * 0.72f, w * 0.8f, w * 0.3f, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s);
                p.setColor(Metro.TEXT_DIM);
                c.drawRect(s / 2, s / 2, w - s / 2, w - s / 2, p);
            }
        }
    }

    private static String titleOf(JSONObject n) {
        String t = n.optString("title").trim();
        if (!t.isEmpty()) return t;
        String s = snippet(n);
        return s.isEmpty() ? "untitled" : s;
    }

    private static String snippet(JSONObject n) {
        JSONArray items = n.optJSONArray("items");
        if (items != null) {
            int open = 0;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it.optBoolean("checked")) continue;
                open++;
                if (sb.length() < 60) sb.append(sb.length() > 0 ? ", " : "").append(it.optString("text"));
            }
            return open == 0 ? "all done" : sb.toString();
        }
        return n.optString("text").replace('\n', ' ').trim();
    }

    // Keep's colours, deepened like the tiles so nothing glares.
    private static int keepColor(String c) {
        switch (c) {
            case "RED": return 0xFF8C3A3A;
            case "ORANGE": return 0xFF8E5F0E;
            case "YELLOW": return 0xFF8C7A1E;
            case "GREEN": return 0xFF1B6E2E;
            case "TEAL": return 0xFF0B6F6E;
            case "BLUE": return 0xFF2E5E8C;
            case "CERULEAN": return 0xFF1C47B0;
            case "PURPLE": return 0xFF4E2A8C;
            case "PINK": return 0xFF7E1747;
            case "BROWN": return 0xFF625844;
            case "GRAY": return 0xFF46535F;
            default: return 0xFF3A3A3A;
        }
    }

    // ------------------------------------------------------------------ calendar

    /** Today first (always, even if empty), then each following day that has something, for the next week. */
    private void showCalendar(JSONObject j, String problem) {
        agenda.removeAllViews();
        JSONArray ev = j == null ? null : j.optJSONArray("events");
        if (ev == null) {
            String err = j == null ? problem : j.optString("error", "");
            agenda.addView(Tile.text(getContext(), err.isEmpty() ? "\u2026" : err, 16, Metro.semilight, Metro.TEXT_DIM));
            return;
        }
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        long midnight = cal.getTimeInMillis();
        int shownDay = 0, todayCount = 0;
        for (int i = 0; i < ev.length(); i++) {
            JSONObject e = ev.optJSONObject(i);
            long s = e.optLong("start");
            // all-day events that started earlier but are still running belong to today
            int day = (int) Math.max(0, Math.floor((s - midnight) / 86_400_000.0));
            if (day == 0) todayCount++;
            if (day > 0 && shownDay == 0 && todayCount == 0)
                agenda.addView(Tile.text(getContext(), "nothing planned", 17, Metro.semilight, Metro.TEXT_FAINT));
            if (day != shownDay) {
                shownDay = day;
                String label = day == 1 ? "tomorrow" : new SimpleDateFormat("EEEE d", Locale.getDefault())
                        .format(new Date(midnight + day * 86_400_000L)).toLowerCase(Locale.getDefault());
                TextView h = Tile.text(getContext(), label, 22, Metro.light, Metro.TEXT_DIM);
                h.setPadding(0, Metro.dp(16), 0, Metro.dp(6));
                agenda.addView(h);
            }
            agenda.addView(eventRow(e, now));
        }
        if (todayCount == 0 && shownDay == 0)
            agenda.addView(Tile.text(getContext(), "nothing planned this week", 17, Metro.semilight, Metro.TEXT_FAINT));
        if (!j.optBoolean("ok", true)) {
            TextView off = Tile.text(getContext(), "offline, showing last sync", 13, Metro.semilight, Metro.TEXT_FAINT);
            off.setPadding(0, Metro.dp(14), 0, 0);
            agenda.addView(off);
        }
    }

    private View eventRow(JSONObject e, long now) {
        long s = e.optLong("start"), f = e.optLong("end");
        boolean allDay = e.optBoolean("all_day"), past = !allDay && f <= now, current = !allDay && s <= now && now < f;
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        View bar = new View(getContext());
        bar.setBackgroundColor(darken(e.optString("color")));
        row.addView(bar, new LinearLayout.LayoutParams(Metro.dp(4), -1));
        LinearLayout txt = vertical(getContext());
        txt.setPadding(Metro.dp(10), Metro.dp(4), 0, Metro.dp(6));
        SimpleDateFormat hm = new SimpleDateFormat("H:mm", Locale.getDefault());
        String when = allDay ? "all day" : hm.format(new Date(s)) + " – " + hm.format(new Date(f));
        TextView time = Tile.text(getContext(), current ? "now  ·  until " + hm.format(new Date(f)) : when, 14,
                Metro.semilight, current ? Metro.TEXT : Metro.TEXT_DIM);
        if (current) time.setTypeface(Metro.regular, Typeface.NORMAL);
        TextView title = Tile.text(getContext(), e.optString("title"), 19, Metro.semilight, Metro.TEXT);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(time);
        txt.addView(title);
        String where = e.optString("location");
        if (!where.isEmpty()) {
            TextView w = Tile.text(getContext(), where, 13, Metro.semilight, Metro.TEXT_FAINT);
            w.setSingleLine(true);
            w.setEllipsize(TextUtils.TruncateAt.END);
            txt.addView(w);
        }
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setAlpha(past ? 0.4f : 1f);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.bottomMargin = Metro.dp(8);
        row.setLayoutParams(rl);
        return row;
    }

    private static int darken(String hex) {
        try {
            int c = android.graphics.Color.parseColor(hex);
            return 0xFF000000 | ((int) ((c >> 16 & 0xFF) * 0.7f) << 16) | ((int) ((c >> 8 & 0xFF) * 0.7f) << 8)
                    | (int) ((c & 0xFF) * 0.7f);
        } catch (Exception e) {
            return Metro.VIOLET;
        }
    }

    private static String ago(long ms) {
        long m = (System.currentTimeMillis() - ms) / 60_000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 60 * 24) return (m / 60) + " h ago";
        if (m < 60 * 24 * 30) return (m / 1440) + (m / 1440 == 1 ? " day ago" : " days ago");
        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(ms)).toLowerCase(Locale.getDefault());
    }
}
