package io.github.martin80899.thps2x;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * The on-screen Xbox pad, drawn over the game, with the FPS badge and the settings gear. Its state goes
 * to the native side on every change (src/platform/touch_android.c), which merges it on top of player 1's
 * port. While a physical game controller is connected only the FPS badge stays.
 */
public class TouchControls extends View implements InputManager.InputDeviceListener {
    static native void nativeState(int buttons, float lx, float ly, float rx, float ry);
    static native void nativeOff();
    static native int nativeGetFps();
    static native void nativeSetFps(int mode);
    static native String nativeDataDir();
    static native float[] nativeFrameStats();

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

    // Look: size, see-through level, buttons shown or not (SettingsMenu's Controls tab)
    private final SharedPreferences prefs;
    float scale = 1f, opacity = 1f;
    boolean shown = true;
    private float gearX, gearY, gearR;
    private int gearPointer = -1;
    boolean padConnected;

    // FPS badge (top left, in from the edge): the renderer's numbers, read twice a second
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int fpsMode;
    float statFps, statMs;
    private final Paint badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF badge = new RectF();
    private final Runnable statsPoll = new Runnable() {
        @Override public void run() {
            try {
                int m = nativeGetFps();
                float[] v = nativeFrameStats();
                float f = v != null ? v[0] : 0, ms = v != null ? v[1] : 0;
                if (m != fpsMode || Math.round(f) != Math.round(statFps) || Math.abs(ms - statMs) > 0.05f) {
                    fpsMode = m; statFps = f; statMs = ms;
                    invalidate();
                }
            } catch (Throwable ignored) {
                // the game library is not loaded yet
            }
            ui.postDelayed(this, 500);
        }
    };
    private SettingsMenu menu;

    public TouchControls(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4f);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        badgeText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        prefs = context.getSharedPreferences("touch_controls", Context.MODE_PRIVATE);
        scale = prefs.getFloat("scale", 1f);
        opacity = prefs.getFloat("opacity", 1f);
        shown = prefs.getBoolean("shown", true);
        inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(this, new Handler(Looper.getMainLooper()));
        updateVisibility();
        ui.postDelayed(statsPoll, 1000);
    }

    /** Opens the settings menu, or closes it (the phone's back button, the gear, a controller's Back + Start). */
    void toggleSettings() {
        if (menu != null && menu.isShowing()) { menu.dismiss(); return; }
        release();
        menu = new SettingsMenu((Activity) getContext(), this);
        menu.show();
    }

    void pollStatsNow() { ui.removeCallbacks(statsPoll); ui.post(statsPoll); }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        relayout();
    }

    void relayout() {
        int w = getWidth(), h = getHeight();
        for (Btn b : buttons) { b.cx = b.fx * w; b.cy = b.fy * h; b.r = b.fr * h * scale; }
        stickCx = STICK_FX * w; stickCy = STICK_FY * h; stickR = STICK_FR * h * scale;
        text.setTextSize(0.045f * h * scale);
        gearX = 0.5f * w; gearY = 0.08f * h; gearR = 0.035f * h;
        invalidate();
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
        c.drawText("⚙", gearX, gearY + gearR * 0.38f, text);
        text.setTextSize(size);
        text.setColor(Color.WHITE);
    }

    /** The FPS badge: a dark rounded pill with a coloured dot (green smooth, amber, red slow). */
    private void drawBadge(Canvas c) {
        if (fpsMode == 0 || (statFps <= 0 && statMs <= 0)) return;
        float h = getHeight(), w = getWidth();
        float bh = 0.068f * h, x = 0.135f * w, y = 0.035f * h, r = bh / 2;
        String big, small;
        int col;
        if (fpsMode == 3) {
            big = String.format(Locale.US, "%.1f", statMs); small = "ms";
            col = statMs <= 18f ? 0xFF4CD964 : statMs <= 36f ? 0xFFFFC23D : 0xFFFF5A5A;
        } else {
            big = String.valueOf(Math.round(statFps));
            small = fpsMode == 2 ? String.format(Locale.US, "FPS  %.1f ms", statMs) : "FPS";
            col = statFps >= 55f ? 0xFF4CD964 : statFps >= 28f ? 0xFFFFC23D : 0xFFFF5A5A;
        }
        badgeText.setTextSize(bh * 0.62f);
        float bigW = badgeText.measureText(big);
        badgeText.setTextSize(bh * 0.36f);
        float smallW = badgeText.measureText(small);
        float dotR = bh * 0.13f, padX = bh * 0.38f;
        float width = padX + dotR * 2 + bh * 0.22f + bigW + bh * 0.14f + smallW + padX;
        badge.set(x, y, x + width, y + bh);
        badgeBg.setStyle(Paint.Style.FILL);
        badgeBg.setColor(0xB8101418);
        c.drawRoundRect(badge, r, r, badgeBg);
        badgeBg.setStyle(Paint.Style.STROKE);
        badgeBg.setStrokeWidth(Math.max(1.5f, bh * 0.035f));
        badgeBg.setColor((col & 0x00FFFFFF) | 0x70000000);
        c.drawRoundRect(badge, r, r, badgeBg);
        badgeBg.setStyle(Paint.Style.FILL);
        badgeBg.setColor(col);
        float cx = x + padX + dotR;
        c.drawCircle(cx, y + bh / 2, dotR, badgeBg);
        float tx = cx + dotR + bh * 0.22f, base = y + bh * 0.72f;
        badgeText.setTextAlign(Paint.Align.LEFT);
        badgeText.setTextSize(bh * 0.62f);
        badgeText.setColor(Color.WHITE);
        c.drawText(big, tx, base, badgeText);
        badgeText.setTextSize(bh * 0.36f);
        badgeText.setColor(0xFFB8C0CC);
        c.drawText(small, tx + bigW + bh * 0.14f, base, badgeText);
    }

    void save() {
        prefs.edit().putFloat("scale", scale).putFloat("opacity", opacity).putBoolean("shown", shown).apply();
    }

    void release() {
        pressed = 0; lx = 0; ly = 0; stickPointer = -1; gearPointer = -1; pointerButtons.clear();
        send();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        drawBadge(c);
        if (padConnected) return;                      // a controller: no gear, no buttons
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
        if (padConnected) return false;                // a controller: touches are the game's
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
                    toggleSettings();
                    return true;
                }
                if (id == stickPointer) { stickPointer = -1; lx = 0; ly = 0; }
                pointerButtons.remove(id);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                stickPointer = -1; lx = 0; ly = 0; gearPointer = -1;
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

    // ---- a physical controller: only the badge stays ----

    static boolean isGamepad(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private void updateVisibility() {
        boolean pad = false;
        for (int id : InputDevice.getDeviceIds()) if (isGamepad(InputDevice.getDevice(id))) pad = true;
        padConnected = pad;
        if (pad) {
            pressed = 0; lx = 0; ly = 0; stickPointer = -1; gearPointer = -1; pointerButtons.clear();
            sent = -1;
            try { nativeOff(); } catch (UnsatisfiedLinkError ignored) { }
        }
        invalidate();
    }

    @Override public void onInputDeviceAdded(int deviceId) { updateVisibility(); }
    @Override public void onInputDeviceRemoved(int deviceId) { updateVisibility(); }
    @Override public void onInputDeviceChanged(int deviceId) { updateVisibility(); }
}
