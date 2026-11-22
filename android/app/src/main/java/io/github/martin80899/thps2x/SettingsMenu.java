package io.github.martin80899.thps2x;

import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * THPS 2X's settings: a full-screen panel over the game with tabs on the left (Graphics, Display, Camera,
 * Gameplay, Cheats - the game's own RECOMP OPTIONS values, recomp_manual.c - then Controls and System).
 * Opened by the phone's back button, the gear, or a controller's Back + Start. Works with touch and with
 * a controller (D-pad / stick to move, A to change, left / right to step, LB / RB for tabs, B to close).
 */
public class SettingsMenu extends Dialog {
    static native int nativeOptCount();
    static native String nativeOptGroup(int i);
    static native String nativeOptName(int i);
    static native String nativeOptDesc(int i);
    static native String nativeOptValue(int i);
    static native void nativeOptStep(int i, int dir);

    private static final int ACCENT = 0xFF8CC63F, ACCENT_SOFT = 0x338CC63F;
    private static final int CARD = 0xF20F1510, RAIL = 0xFF0A0F0A, TEXT = 0xFFF2F4F8, MUTED = 0xFF8A93A3;

    /** Advanced tests: env.txt lines in THPS2X/data, read when the game starts (host_android.c). */
    private static final String[][] TESTS = {
        { "THPS2X_ONE_CORE=1", "Run the game on one CPU core" },
        { "RECOMP_VK_INSTANCE=0", "Instancing off" },
        { "RECOMP_VK_NO_DYN_VERTEX_INPUT=0", "Dynamic vertex input on (colour flashes on Adreno)" },
        { "RECOMP_PAGE_TRACK=0", "Page tracking off" },
    };

    /** One line of the menu. */
    interface Item {
        String name();
        String desc();
        String value();
        void step(int dir);
        boolean changes();          // false: information only
    }

    static final class Tab {
        final String name, desc;
        final List<Item> items = new ArrayList<>();
        Tab(String name, String desc) { this.name = name; this.desc = desc; }
    }

    private final Activity act;
    private final TouchControls touch;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Tab> tabs = new ArrayList<>();
    private final List<TextView> tabViews = new ArrayList<>();
    private final List<Item> shownItems = new ArrayList<>();
    private final List<TextView> shownValues = new ArrayList<>();
    private LinearLayout list;
    private ScrollView scroll;
    private TextView title, subtitle, stats;
    private int current;
    private static int lastTab;

    SettingsMenu(Activity act, TouchControls touch) {
        super(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.act = act;
        this.touch = touch;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.getResources().getDisplayMetrics()));
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    /** "FIELD OF VIEW" -> "Field of view"; FSR, HUD, PS2, X, 16:9 ... stay as they are */
    static String nice(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) {
            boolean keep = w.length() <= 1 || w.matches(".*\\d.*") || w.equals("FSR") || w.equals("FOV")
                || w.equals("HUD") || w.equals("THPS") || w.equals("MSAA") || w.equals("FXAA") || w.equals("SMAA");
            if (b.length() > 0) b.append(' ');
            if (keep) b.append(w);
            else if (b.length() == 0) b.append(w.charAt(0)).append(w.substring(1).toLowerCase(Locale.US));
            else b.append(w.toLowerCase(Locale.US));
        }
        return b.toString();
    }

    // ---- the tabs ----

    private void buildTabs() {
        String[][] meta = {
            { "Graphics", "Resolution, upscaling and image quality" },
            { "Display", "Screen shape, brightness and on-screen info" },
            { "Camera", "Field of view and the free camera" },
            { "Gameplay", "The PC port's gameplay options" },
            { "Cheats", "The game's button cheats, one tap each. Use them from the main menus, not in a level" },
        };
        for (String[] m : meta) tabs.add(new Tab(m[0], m[1]));
        int n = 0;
        try { n = nativeOptCount(); } catch (Throwable ignored) { }
        for (int i = 0; i < n; i++) {
            final int k = i;
            String g = nativeOptGroup(i);
            Tab tab = null;
            for (Tab t : tabs) if (t.name.equals(g)) tab = t;
            if (tab == null) { tab = new Tab(g, ""); tabs.add(tab); }
            final String name = nice(nativeOptName(i)), desc = nativeOptDesc(i);
            tab.items.add(new Item() {
                public String name() { return name; }
                public String desc() { return desc; }
                public String value() { return nativeOptValue(k); }
                public void step(int dir) { nativeOptStep(k, dir); }
                public boolean changes() { return true; }
            });
        }

        Tab controls = new Tab("Controls", "On-screen buttons and controllers");
        controls.items.add(new Item() {
            public String name() { return "Touch controls"; }
            public String desc() { return touch.padConnected ? "Hidden while a controller is connected" : "The on-screen buttons"; }
            public String value() { return touch.shown ? "ON" : "OFF"; }
            public void step(int dir) { touch.shown = !touch.shown; touch.save(); touch.release(); }
            public boolean changes() { return true; }
        });
        controls.items.add(new Item() {
            public String name() { return "Button size"; }
            public String desc() { return "How big the on-screen buttons are"; }
            public String value() { return Math.round(touch.scale * 100) + "%"; }
            public void step(int dir) {
                touch.scale = Math.max(0.6f, Math.min(1.5f, Math.round(touch.scale * 10 + dir) / 10f));
                touch.save(); touch.relayout();
            }
            public boolean changes() { return true; }
        });
        controls.items.add(new Item() {
            public String name() { return "Button visibility"; }
            public String desc() { return "How see-through the on-screen buttons are"; }
            public String value() { return Math.round(touch.opacity * 100) + "%"; }
            public void step(int dir) {
                touch.opacity = Math.max(0.2f, Math.min(1f, Math.round(touch.opacity * 10 + dir) / 10f));
                touch.save(); touch.invalidate();
            }
            public boolean changes() { return true; }
        });
        controls.items.add(new Item() {
            public String name() { return "Reset on-screen buttons"; }
            public String desc() { return "Size, visibility and shown back to the start"; }
            public String value() { return "RESET"; }
            public void step(int dir) { touch.scale = 1f; touch.opacity = 1f; touch.shown = true; touch.save(); touch.relayout(); }
            public boolean changes() { return true; }
        });
        controls.items.add(new Item() {
            public String name() { return "Controller rumble"; }
            public String desc() { return "Bluetooth and USB controllers that can vibrate"; }
            public String value() { return touch.padRumble ? "ON" : "OFF"; }
            public void step(int dir) { touch.padRumble = !touch.padRumble; touch.save(); touch.applyRumble(); }
            public boolean changes() { return true; }
        });
        controls.items.add(new Item() {
            public String name() { return "Phone vibration"; }
            public String desc() { return "The phone rumbles with the game while you play on the touch controls"; }
            public String value() { return touch.phoneRumble ? "ON" : "OFF"; }
            public void step(int dir) { touch.phoneRumble = !touch.phoneRumble; touch.save(); touch.applyRumble(); }
            public boolean changes() { return true; }
        });
        controls.items.add(new Item() {
            public String name() { return "Rumble strength"; }
            public String desc() { return "For the controller and the phone"; }
            public String value() { return Math.round(touch.rumbleStrength * 100) + "%"; }
            public void step(int dir) {
                touch.rumbleStrength = Math.max(0.25f, Math.min(1f, Math.round(touch.rumbleStrength * 4 + dir) / 4f));
                touch.save(); touch.applyRumble();
            }
            public boolean changes() { return true; }
        });
        controls.items.add(new Item() {
            public String name() { return "Button haptics"; }
            public String desc() { return "A light tap when you press an on-screen button"; }
            public String value() { return touch.haptics ? "ON" : "OFF"; }
            public void step(int dir) { touch.haptics = !touch.haptics; touch.save(); }
            public boolean changes() { return true; }
        });
        controls.items.add(info("Controller", () -> touch.padConnected ? "CONNECTED" : "NONE",
            "Open this menu with Back + Start together, or the phone's back"));
        tabs.add(controls);

        Tab system = new Tab("System", "This phone, the game's folder and troubleshooting");
        system.items.add(info("Device", () -> (Build.MANUFACTURER + " " + Build.MODEL).toUpperCase(Locale.US), ""));
        system.items.add(info("Chip", () -> Build.SOC_MODEL.toUpperCase(Locale.US), "Android " + Build.VERSION.RELEASE));
        String ver = "";
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            ver = pi.versionName;
        } catch (Exception ignored) { }
        final String version = ver == null ? "" : ver.toUpperCase(Locale.US);
        system.items.add(info("App version", () -> version, "Statically recompiled to arm64"));
        final File env = envFile();
        system.items.add(info("Game folder", () -> "THPS2X", env.getParentFile().getParent()));
        final List<String> on = readEnv(env);
        for (String[] t : TESTS) {
            final String line = t[0], label = t[1], key = t[0].substring(0, t[0].indexOf('=') + 1);
            system.items.add(new Item() {
                public String name() { return label; }
                public String desc() { return "Troubleshooting. Applies after you reopen the game"; }
                public String value() { return on.contains(line) ? "ON" : "OFF"; }
                public void step(int dir) {
                    boolean was = on.contains(line);
                    on.removeIf(l -> l.startsWith(key));
                    if (!was) on.add(line);
                    writeEnv(env, on);
                }
                public boolean changes() { return true; }
            });
        }
        tabs.add(system);
        // tabs the game did not fill (it is still starting): leave them out
        tabs.removeIf(t -> t.items.isEmpty());
    }

    interface Text { String get(); }

    private static Item info(String name, Text value, String desc) {
        return new Item() {
            public String name() { return name; }
            public String desc() { return desc; }
            public String value() { return value.get(); }
            public void step(int dir) { }
            public boolean changes() { return false; }
        };
    }

    // ---- the panel ----

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.55f;
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            lp.setBlurBehindRadius(dp(18));
            w.setAttributes(lp);
        }
        buildTabs();

        FrameLayout root = new FrameLayout(act);
        root.setPadding(dp(28), dp(18), dp(28), dp(18));
        root.setOnClickListener(v -> dismiss());               // outside the card: close

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable cardBg = round(CARD, dp(22));
        cardBg.setStroke(dp(1), 0x22FFFFFF);
        card.setBackground(cardBg);
        card.setClipToOutline(true);
        card.setClickable(true);                               // taps inside do not close
        root.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // left rail: name, tabs, resume / quit
        LinearLayout rail = new LinearLayout(act);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackground(round(RAIL, dp(22)));
        rail.setPadding(dp(16), dp(20), dp(16), dp(16));
        card.addView(rail, new LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView brand = text("THPS 2X", 26, TEXT, true);
        brand.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
        brand.setPadding(dp(6), 0, 0, 0);
        rail.addView(brand);
        View bar = new View(act);
        bar.setBackground(round(ACCENT, dp(2)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(42), dp(4));
        barLp.setMargins(dp(7), dp(4), 0, dp(6));
        rail.addView(bar, barLp);
        TextView tag = text("ANDROID PORT · ARM64", 10, MUTED, true);
        tag.setLetterSpacing(0.14f);
        tag.setPadding(dp(6), 0, 0, dp(14));
        rail.addView(tag);

        ScrollView tabScroll = new ScrollView(act);
        tabScroll.setVerticalScrollBarEnabled(false);
        LinearLayout tabBox = new LinearLayout(act);
        tabBox.setOrientation(LinearLayout.VERTICAL);
        tabScroll.addView(tabBox);
        rail.addView(tabScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        for (int i = 0; i < tabs.size(); i++) {
            final int k = i;
            TextView t = text(tabs.get(i).name, 15, MUTED, true);
            t.setPadding(dp(14), dp(10), dp(14), dp(10));
            t.setFocusable(true);
            t.setOnClickListener(v -> selectTab(k));
            t.setOnFocusChangeListener((v, has) -> { if (has) selectTab(k); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(3);
            tabBox.addView(t, lp);
            tabViews.add(t);
        }

        TextView resume = text("RESUME", 14, 0xFF0B1A06, true);
        resume.setGravity(Gravity.CENTER);
        resume.setLetterSpacing(0.08f);
        resume.setBackground(pressable(ACCENT, 0xFFA8DC5E, dp(12)));
        resume.setPadding(0, dp(11), 0, dp(11));
        resume.setFocusable(true);
        resume.setOnClickListener(v -> dismiss());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        rail.addView(resume, rlp);
        TextView quit = text("QUIT GAME", 13, 0xFFFF6B6B, true);
        quit.setGravity(Gravity.CENTER);
        quit.setLetterSpacing(0.08f);
        GradientDrawable qBg = round(Color.TRANSPARENT, dp(12));
        qBg.setStroke(dp(1), 0x55FF6B6B);
        quit.setBackground(qBg);
        quit.setPadding(0, dp(9), 0, dp(9));
        quit.setFocusable(true);
        quit.setOnClickListener(v -> { act.finishAffinity(); System.exit(0); });
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.topMargin = dp(8);
        rail.addView(quit, qlp);

        // right: title, live numbers, the lines
        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(26), dp(20), dp(26), dp(12));
        card.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        title = text("", 24, TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stats = text("", 12, TEXT, true);
        stats.setBackground(round(0x14FFFFFF, dp(14)));
        stats.setPadding(dp(12), dp(6), dp(12), dp(6));
        head.addView(stats);
        content.addView(head);
        subtitle = text("", 13, MUTED, false);
        subtitle.setPadding(0, dp(2), 0, dp(12));
        content.addView(subtitle);

        scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = text(touch.padConnected
                ? "Move with the D-pad   ·   A change   ·   ◀ ▶ step   ·   LB / RB tabs   ·   B close"
                : "Tap a setting to change it   ·   ‹ › step back and forth   ·   back closes", 11, 0xFF6E7685, false);
        hint.setPadding(0, dp(8), 0, 0);
        content.addView(hint);

        setContentView(root);
        selectTab(Math.min(lastTab, tabs.size() - 1));
    }

    private static StateListDrawable pressable(int normal, int pressed, float radius) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable f = round(pressed, radius);
        f.setStroke(4, Color.WHITE);
        s.addState(new int[] { android.R.attr.state_focused }, f);
        s.addState(new int[] { android.R.attr.state_pressed }, round(pressed, radius));
        s.addState(new int[] {}, round(normal, radius));
        return s;
    }

    private void selectTab(int k) {
        if (k < 0 || k >= tabs.size()) return;
        current = lastTab = k;
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean on = i == k;
            t.setTextColor(on ? TEXT : MUTED);
            t.setBackground(on ? round(ACCENT_SOFT, dp(12)) : tabBg());
        }
        Tab tab = tabs.get(k);
        title.setText(tab.name);
        subtitle.setText(tab.desc);
        list.removeAllViews();
        shownItems.clear();
        shownValues.clear();
        for (Item it : tab.items) addRow(it);
        scroll.scrollTo(0, 0);
    }

    private StateListDrawable tabBg() {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable f = round(0x14FFFFFF, dp(12));
        f.setStroke(dp(2), ACCENT);
        s.addState(new int[] { android.R.attr.state_focused }, f);
        s.addState(new int[] { android.R.attr.state_pressed }, round(0x14FFFFFF, dp(12)));
        s.addState(new int[] {}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    private void addRow(final Item it) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(11), dp(10), dp(11));
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = round(0x228CC63F, dp(14));
        focused.setStroke(dp(2), ACCENT);
        bg.addState(new int[] { android.R.attr.state_focused }, focused);
        bg.addState(new int[] { android.R.attr.state_pressed }, round(0x16FFFFFF, dp(14)));
        bg.addState(new int[] {}, round(0x0AFFFFFF, dp(14)));
        r.setBackground(bg);

        LinearLayout words = new LinearLayout(act);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(it.name(), 15, TEXT, true));
        String d = it.desc();
        if (!TextUtils.isEmpty(d)) {
            TextView dt = text(d, 12, MUTED, false);
            dt.setPadding(0, dp(1), dp(8), 0);
            words.addView(dt);
        }
        r.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = text(it.value(), 13, it.changes() ? ACCENT : TEXT, true);
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setMaxWidth(dp(260));
        value.setMinWidth(dp(it.changes() ? 128 : 0));
        value.setPadding(dp(12), dp(6), dp(12), dp(6));
        value.setBackground(round(it.changes() ? 0x1A8CC63F : 0x10FFFFFF, dp(10)));
        if (it.changes()) {
            TextView less = arrow("‹"), more = arrow("›");
            less.setOnClickListener(v -> step(it, -1));
            more.setOnClickListener(v -> step(it, 1));
            value.setOnClickListener(v -> step(it, 1));
            r.addView(less);
            r.addView(value);
            r.addView(more);
            r.setFocusable(true);
            r.setOnClickListener(v -> step(it, 1));
            r.setOnKeyListener((v, code, e) -> {
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN) step(it, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            });
        } else {
            r.addView(value);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(7);
        list.addView(r, lp);
        shownItems.add(it);
        shownValues.add(value);
    }

    private TextView arrow(String s) {
        TextView a = text(s, 24, MUTED, true);
        a.setGravity(Gravity.CENTER);
        a.setPadding(dp(12), 0, dp(12), dp(3));
        a.setBackground(pressBg());
        return a;
    }

    private StateListDrawable pressBg() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_pressed }, round(0x22FFFFFF, dp(10)));
        s.addState(new int[] {}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    private void step(Item it, int dir) {
        try { it.step(dir); } catch (Throwable ignored) { }
        touch.pollStatsNow();
        ui.removeCallbacks(refresh);
        ui.postDelayed(refresh, 60);                           // the game takes it at its next frame
    }

    /** The values follow the game; the numbers in the corner too. */
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!isShowing()) return;
            for (int i = 0; i < shownItems.size(); i++) {
                String v;
                try { v = shownItems.get(i).value(); } catch (Throwable t) { continue; }
                TextView tv = shownValues.get(i);
                if (v != null && !v.contentEquals(tv.getText())) {
                    tv.setText(v);
                    tv.setScaleX(1.08f); tv.setScaleY(1.08f);
                    tv.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                }
            }
            if (touch.statFps > 0) {
                int col = touch.statFps >= 55f ? 0xFF4CD964 : touch.statFps >= 28f ? 0xFFFFC23D : 0xFFFF5A5A;
                stats.setText(String.format(Locale.US, "●  %d FPS  ·  %.1f ms", Math.round(touch.statFps), touch.statMs));
                stats.setTextColor(col);
                stats.setVisibility(View.VISIBLE);
            } else {
                stats.setVisibility(View.GONE);
            }
            ui.postDelayed(this, 200);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        Window w = getWindow();
        if (w != null) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        touch.stopRumble();
        ui.post(refresh);
        if (touch.padConnected && list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(refresh);
        super.onStop();
    }

    /** A controller: A = select, B / Start / Back / Guide = close, LB / RB = tabs. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return super.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(),
                    KeyEvent.KEYCODE_DPAD_CENTER, e.getRepeatCount(), e.getMetaState(), e.getDeviceId(),
                    e.getScanCode(), e.getFlags(), e.getSource()));
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_MODE:
                if (e.getAction() == KeyEvent.ACTION_UP) dismiss();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    selectTab((current + (code == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : tabs.size() - 1)) % tabs.size());
                    if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    // ---- env.txt (the advanced tests) ----

    static File envFile() {
        String dir = null;
        try { dir = TouchControls.nativeDataDir(); } catch (Throwable ignored) { }
        if (dir == null || dir.isEmpty()) dir = "/storage/emulated/0/THPS2X/data";
        return new File(dir, "env.txt");
    }

    private static List<String> readEnv(File f) {
        List<String> out = new ArrayList<>();
        try {
            for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                l = l.trim();
                if (!l.isEmpty() && !l.startsWith("#")) out.add(l);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void writeEnv(File f, List<String> lines) {
        try {
            if (lines.isEmpty()) { f.delete(); return; }
            StringBuilder b = new StringBuilder("# THPS 2X test switches (settings -> System)\n");
            for (String l : lines) b.append(l).append('\n');
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(b.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) { }
    }
}
