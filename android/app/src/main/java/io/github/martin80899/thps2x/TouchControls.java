package io.github.martin80899.thps2x;

import android.content.Context;
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
import android.view.View;

/**
 * The on-screen Xbox pad, drawn over the game. Its state goes to the native side on every change
 * (src/platform/touch_android.c), which merges it on top of player 1's port. Hidden while a
 * physical game controller is connected.
 */
public class TouchControls extends View implements InputManager.InputDeviceListener {
    static native void nativeState(int buttons, float lx, float ly, float rx, float ry);
    static native void nativeOff();

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

    public TouchControls(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4f);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(this, new Handler(Looper.getMainLooper()));
        updateVisibility();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        for (Btn b : buttons) { b.cx = b.fx * w; b.cy = b.fy * h; b.r = b.fr * h; }
        stickCx = STICK_FX * w; stickCy = STICK_FY * h; stickR = STICK_FR * h;
        text.setTextSize(0.045f * h);
    }

    @Override
    protected void onDraw(Canvas c) {
        // stick base and knob
        fill.setColor(0x40FFFFFF);
        c.drawCircle(stickCx, stickCy, stickR, fill);
        ring.setColor(0x80FFFFFF);
        c.drawCircle(stickCx, stickCy, stickR, ring);
        fill.setColor(stickPointer >= 0 ? 0xC0FFFFFF : 0x80FFFFFF);
        c.drawCircle(stickCx + lx * stickR, stickCy + ly * stickR, stickR * 0.45f, fill);
        for (Btn b : buttons) {
            boolean on = (pressed & b.bit) != 0;
            fill.setColor((b.color & 0x00FFFFFF) | (on ? 0xD0000000 : 0x60000000));
            c.drawCircle(b.cx, b.cy, b.r, fill);
            ring.setColor(on ? 0xFFFFFFFF : 0x90FFFFFF);
            c.drawCircle(b.cx, b.cy, b.r, ring);
            float size = text.getTextSize();
            if (b.label.length() > 2) text.setTextSize(size * 0.6f);
            c.drawText(b.label, b.cx, b.cy + text.getTextSize() * 0.35f, text);
            text.setTextSize(size);
        }
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
