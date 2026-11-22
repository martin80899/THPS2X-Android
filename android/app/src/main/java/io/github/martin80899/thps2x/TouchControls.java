package io.github.martin80899.thps2x;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * The on-screen Xbox pad, drawn over the game. Its state goes to the native side on every change
 * (src/platform/touch_android.c), which merges it on top of player 1's port. Hidden while a
 * physical game controller is connected.
 */
public class TouchControls extends View implements InputManager.InputDeviceListener {
    static native void nativeState(int buttons, float lx, float ly, float rx, float ry);
    static native void nativeOff();
    static native int nativeGetFps();
    static native void nativeSetFps(int mode);
    static native String nativeDataDir();

    /** Graphics tests: env.txt lines in THPS2X/data, read when the game starts (host_android.c). */
    private static final String[][] TESTS = {
        { "THPS2X_ONE_CORE=1", "Test: game on one CPU core" },
        { "RECOMP_VK_INSTANCE=0", "Test: instancing off" },
        { "RECOMP_VK_NO_DYN_VERTEX_INPUT=1", "Test: dynamic vertex input off" },
        { "RECOMP_PAGE_TRACK=0", "Test: page tracking off" },
    };
    private static final String[] FPS_NAMES = { "OFF", "FPS", "FPS + FRAME TIME", "FRAME TIME" };

    // Button bits: keep in step with touch_android.c
    static final int A = 1, B = 1 << 1, X = 1 << 2, Y = 1 << 3, BLACK = 1 << 4, WHITE = 1 << 5,
            LT = 1 << 6, RT = 1 << 7, UP = 1 << 8, DOWN = 1 << 9, LEFT = 1 << 10, RIGHT = 1 << 11,
            START = 1 << 12, BACK = 1 << 13, LTHUMB = 1 << 14, RTHUMB = 1 << 15;

    /** A round button: centre and radius as fractions of the view's height (x of its width). */
    static final class Btn {
        final int bit; final String label; final int color; final float fx, fy, fr;
        float cx, cy, r;
        Btn(int bit, String label, int color, float fx, float fy, float fr) {
            this.bit = bit; this.label = label; this.color = color; this.fx = fx; this.fy = fy; this.fr = fr;
        }
        boolean hit(float x, float y, float slack) {
            float dx = x - cx, dy = y - cy;
            return dx * dx + dy * dy <= (r * slack) * (r * slack);
        }
    }

    private final Btn[] buttons = {
        new Btn(A, "A", 0xFF3FB34F, 0.86f, 0.80f, 0.075f),
        new Btn(B, "B", 0xFFD9443B, 0.94f, 0.64f, 0.075f),
        new Btn(X, "X", 0xFF3B7BD9, 0.78f, 0.64f, 0.075f),
        new Btn(Y, "Y", 0xFFE0B83A, 0.86f, 0.48f, 0.075f),
        new Btn(WHITE, "W", 0xFFE8E8E8, 0.70f, 0.40f, 0.050f),
        new Btn(BLACK, "Bk", 0xFF505050, 0.76f, 0.30f, 0.050f),
        new Btn(LT, "L", 0xFFB0B0B0, 0.07f, 0.16f, 0.080f),
        new Btn(RT, "R", 0xFFB0B0B0, 0.93f, 0.16f, 0.080f),
        new Btn(BACK, "Back", 0xFF909090, 0.42f, 0.08f, 0.050f),
        new Btn(START, "Start", 0xFF909090, 0.58f, 0.08f, 0.050f),
        new Btn(UP, "▲", 0xFFA0A0A0, 0.29f, 0.62f, 0.050f),
        new Btn(DOWN, "▼", 0xFFA0A0A0, 0.29f, 0.86f, 0.050f),
        new Btn(LEFT, "◀", 0xFFA0A0A0, 0.245f, 0.74f, 0.050f),
        new Btn(RIGHT, "▶", 0xFFA0A0A0, 0.335f, 0.74f, 0.050f),
    };

    // Left stick: centre (fractions) and travel radius (fraction of height)
    private static final float STICK_FX = 0.12f, STICK_FY = 0.74f, STICK_FR = 0.16f;
    private float stickCx, stickCy, stickR;
    private float lx, ly;
    private int stickPointer = -1;

    private final SparseArray<Integer> pointerButtons = new SparseArray<>();
    private int pressed, sent = -1;
    private float sentLx = 2, sentLy = 2;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final InputManager inputManager;

    // Settings (gear button between Back and Start): size, see-through level, buttons shown or not
    private final SharedPreferences prefs;
    private float scale = 1f, opacity = 1f;
    private boolean shown = true;
    private float gearX, gearY, gearR;
    private int gearPointer = -1;

    public TouchControls(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4f);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        prefs = context.getSharedPreferences("touch_controls", Context.MODE_PRIVATE);
        scale = prefs.getFloat("scale", 1f);
        opacity = prefs.getFloat("opacity", 1f);
        shown = prefs.getBoolean("shown", true);
        inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(this, new Handler(Looper.getMainLooper()));
        updateVisibility();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layout(w, h);
    }

    private void layout(int w, int h) {
        for (Btn b : buttons) { b.cx = b.fx * w; b.cy = b.fy * h; b.r = b.fr * h * scale; }
        stickCx = STICK_FX * w; stickCy = STICK_FY * h; stickR = STICK_FR * h * scale;
        text.setTextSize(0.045f * h * scale);
        gearX = 0.5f * w; gearY = 0.08f * h; gearR = 0.035f * h;
    }

    private int alpha(int a) { return Math.max(0, Math.min(255, Math.round(a * opacity))); }
    private int withAlpha(int color, int a) { return (color & 0x00FFFFFF) | (alpha(a) << 24); }

    private void drawGear(Canvas c) {
        fill.setColor(0x50000000);
        c.drawCircle(gearX, gearY, gearR, fill);
        ring.setColor(0xA0FFFFFF);
        c.drawCircle(gearX, gearY, gearR, ring);
        float size = text.getTextSize();
        text.setTextSize(gearR * 1.1f);
        text.setColor(0xE0FFFFFF);
        c.drawText("\u2699", gearX, gearY + gearR * 0.38f, text);
        text.setTextSize(size);
        text.setColor(Color.WHITE);
    }

    private void openSettings() {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        Switch show = new Switch(ctx);
        show.setText("Show touch controls");
        show.setChecked(shown);
        show.setOnCheckedChangeListener((v, on) -> { shown = on; save(); if (!on) release(); invalidate(); });
        box.addView(show);

        final TextView sizeLabel = new TextView(ctx);
        box.addView(sizeLabel);
        SeekBar size = new SeekBar(ctx);
        size.setMax(90);                                   // 60% .. 150%
        size.setProgress(Math.round(scale * 100) - 60);
        sizeLabel.setText("Button size: " + Math.round(scale * 100) + "%");
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                scale = (60 + p) / 100f; sizeLabel.setText("Button size: " + (60 + p) + "%");
                layout(getWidth(), getHeight()); invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { save(); }
        });
        box.addView(size);

        final TextView opLabel = new TextView(ctx);
        box.addView(opLabel);
        SeekBar op = new SeekBar(ctx);
        op.setMax(80);                                     // 20% .. 100%
        op.setProgress(Math.round(opacity * 100) - 20);
        opLabel.setText("Visibility: " + Math.round(opacity * 100) + "%");
        op.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                opacity = (20 + p) / 100f; opLabel.setText("Visibility: " + (20 + p) + "%"); invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { save(); }
        });
        box.addView(op);

        // FPS counter: the game's own setting, at once
        final Button fps = new Button(ctx);
        fps.setAllCaps(false);
        final int[] fpsMode = { 0 };
        try { fpsMode[0] = nativeGetFps(); } catch (Throwable ignored) { }
        fps.setText("FPS counter: " + FPS_NAMES[fpsMode[0] & 3]);
        fps.setOnClickListener(v -> {
            fpsMode[0] = (fpsMode[0] + 1) & 3;
            fps.setText("FPS counter: " + FPS_NAMES[fpsMode[0]]);
            try { nativeSetFps(fpsMode[0]); } catch (Throwable ignored) { }
        });
        box.addView(fps);

        // Graphics tests: env.txt switches, after the game is opened again
        TextView tests = new TextView(ctx);
        tests.setText("\nGraphics tests (close the game and open it again to apply):");
        box.addView(tests);
        final File env = envFile();
        final List<String> on = readEnv(env);
        for (String[] t : TESTS) {
            Switch sw = new Switch(ctx);
            sw.setText(t[1]);
            sw.setChecked(on.contains(t[0]));
            sw.setOnCheckedChangeListener((v, c) -> {
                if (c) { if (!on.contains(t[0])) on.add(t[0]); } else on.remove(t[0]);
                writeEnv(env, on);
            });
            box.addView(sw);
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(box);
        new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Touch controls")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .setNegativeButton("Close game", (d, w) -> {
                if (getContext() instanceof android.app.Activity) ((android.app.Activity) getContext()).finishAffinity();
                System.exit(0);
            })
            .setNeutralButton("Reset", (d, w) -> {
                scale = 1f; opacity = 1f; shown = true; save(); layout(getWidth(), getHeight()); invalidate();
            })
            .show();
    }

    private static File envFile() {
        String dir = null;
        try { dir = nativeDataDir(); } catch (Throwable ignored) { }
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
            StringBuilder b = new StringBuilder("# THPS 2X test switches (touch menu -> graphics tests)\n");
            for (String l : lines) b.append(l).append('\n');
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(b.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) { }
    }

    private void save() {
        prefs.edit().putFloat("scale", scale).putFloat("opacity", opacity).putBoolean("shown", shown).apply();
    }

    private void release() {
        pressed = 0; lx = 0; ly = 0; stickPointer = -1; pointerButtons.clear();
        send();
    }

    @Override
    protected void onDraw(Canvas c) {
        drawGear(c);
        if (!shown) return;
        // stick base and knob
        fill.setColor(withAlpha(0xFFFFFF, 0x40));
        c.drawCircle(stickCx, stickCy, stickR, fill);
        ring.setColor(withAlpha(0xFFFFFF, 0x80));
        c.drawCircle(stickCx, stickCy, stickR, ring);
        fill.setColor(withAlpha(0xFFFFFF, stickPointer >= 0 ? 0xC0 : 0x80));
        c.drawCircle(stickCx + lx * stickR, stickCy + ly * stickR, stickR * 0.45f, fill);
        text.setAlpha(alpha(255));
        for (Btn b : buttons) {
            boolean on = (pressed & b.bit) != 0;
            fill.setColor(withAlpha(b.color, on ? 0xD0 : 0x60));
            c.drawCircle(b.cx, b.cy, b.r, fill);
            ring.setColor(withAlpha(0xFFFFFF, on ? 0xFF : 0x90));
            c.drawCircle(b.cx, b.cy, b.r, ring);
            float size = text.getTextSize();
            if (b.label.length() > 2) text.setTextSize(size * 0.6f);
            c.drawText(b.label, b.cx, b.cy + text.getTextSize() * 0.35f, text);
            text.setTextSize(size);
        }
        text.setAlpha(255);
    }

    private int buttonAt(float x, float y) {
        for (Btn b : buttons) if (b.hit(x, y, 1.15f)) return b.bit;
        return 0;
    }

    private boolean inStick(float x, float y) {
        float dx = x - stickCx, dy = y - stickCy;
        return dx * dx + dy * dy <= (stickR * 1.6f) * (stickR * 1.6f);
    }

    private void moveStick(float x, float y) {
        float dx = (x - stickCx) / stickR, dy = (y - stickCy) / stickR;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1f) { dx /= len; dy /= len; }
        lx = dx; ly = dy;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = e.getPointerId(idx);
                float x = e.getX(idx), y = e.getY(idx);
                float gx = x - gearX, gy = y - gearY;
                if (gx * gx + gy * gy <= gearR * gearR * 2.2f) {
                    gearPointer = id;
                    return true;
                }
                if (!shown) return true;
                if (stickPointer < 0 && inStick(x, y)) {
                    stickPointer = id;
                    moveStick(x, y);
                } else {
                    pointerButtons.put(id, buttonAt(x, y));
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    if (id == stickPointer) moveStick(e.getX(i), e.getY(i));
                    else if (pointerButtons.indexOfKey(id) >= 0) pointerButtons.put(id, buttonAt(e.getX(i), e.getY(i)));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(idx);
                if (id == gearPointer) {
                    gearPointer = -1;
                    openSettings();
                    return true;
                }
                if (id == stickPointer) { stickPointer = -1; lx = 0; ly = 0; }
                pointerButtons.remove(id);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                stickPointer = -1; lx = 0; ly = 0;
                pointerButtons.clear();
                break;
            default:
                return true;
        }
        int p = 0;
        for (int i = 0; i < pointerButtons.size(); i++) p |= pointerButtons.valueAt(i);
        pressed = p;
        send();
        invalidate();
        return true;
    }

    private void send() {
        if (pressed == sent && lx == sentLx && ly == sentLy) return;
        sent = pressed; sentLx = lx; sentLy = ly;
        try {
            nativeState(pressed, lx, ly, 0f, 0f);
        } catch (UnsatisfiedLinkError ignored) {
            // the game library is not loaded yet
        }
    }

    // ---- hide while a physical controller is connected ----

    private static boolean isGamepad(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private void updateVisibility() {
        boolean pad = false;
        for (int id : InputDevice.getDeviceIds()) if (isGamepad(InputDevice.getDevice(id))) pad = true;
        setVisibility(pad ? GONE : VISIBLE);
        if (pad) {
            pressed = 0; lx = 0; ly = 0; stickPointer = -1; pointerButtons.clear();
            sent = -1;
            try { nativeOff(); } catch (UnsatisfiedLinkError ignored) { }
        }
    }

    @Override public void onInputDeviceAdded(int deviceId) { updateVisibility(); }
    @Override public void onInputDeviceRemoved(int deviceId) { updateVisibility(); }
    @Override public void onInputDeviceChanged(int deviceId) { updateVisibility(); }
}
