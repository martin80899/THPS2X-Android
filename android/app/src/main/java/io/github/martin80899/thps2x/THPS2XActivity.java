package io.github.martin80899.thps2x;

import android.os.Bundle;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

/** SDL3's activity: loads libSDL3.so and libmain.so (the recompiled game) and runs its SDL_main. */
public class THPS2XActivity extends SDLActivity {
    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "c++_shared", "main" };
    }

    /** The panel's highest refresh rate at its current resolution (120 Hz on most newer phones), so the
     *  game's FRAME RATE 120 option is shown at 120 and 60 fps frames pace evenly. Phones with adaptive
     *  refresh otherwise often keep a game at 60 Hz. */
    private void requestFastestRefresh() {
        try {
            Display d = getDisplay();
            if (d == null) return;
            Display.Mode cur = d.getMode(), best = cur;
            for (Display.Mode m : d.getSupportedModes())
                if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()
                        && m.getRefreshRate() > best.getRefreshRate())
                    best = m;
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredDisplayModeId = best.getModeId();
            lp.preferredRefreshRate = best.getRefreshRate();
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {
        }
    }

    private TouchControls touch;

    /** The phone's back (gesture, navigation bar or key) opens the settings instead of leaving the game.
     *  A controller: Back + Start pressed together (opened when both are let go, so the game sees them
     *  released) or the Guide button, where Android passes it on. */
    private boolean padSelect, padStart, padCombo;

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        InputDevice d = e.getDevice();
        int src = e.getSource();
        boolean pad = d != null && !d.isVirtual()
            && ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
        if (touch == null) return super.dispatchKeyEvent(e);
        if (code == KeyEvent.KEYCODE_BACK && !pad) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) touch.toggleSettings();
            return true;
        }
        if (pad && code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (e.getAction() == KeyEvent.ACTION_UP) touch.toggleSettings();
            return true;
        }
        if (pad && (code == KeyEvent.KEYCODE_BUTTON_SELECT || code == KeyEvent.KEYCODE_BACK
                    || code == KeyEvent.KEYCODE_BUTTON_START)) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (code == KeyEvent.KEYCODE_BUTTON_START) padStart = down; else padSelect = down;
            if (padSelect && padStart) padCombo = true;
            boolean r = super.dispatchKeyEvent(e);
            if (padCombo && !padSelect && !padStart) {
                padCombo = false;
                touch.toggleSettings();
            }
            return r;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onPause() {
        if (touch != null) touch.stopRumble();       // no phone vibration left running in the background
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (touch != null) touch.toggleSettings();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestFastestRefresh();
        if (mLayout != null) {   // SDL's layout holding the game's surface: the on-screen pad goes on top
            touch = new TouchControls(this);
            mLayout.addView(touch, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }
}
