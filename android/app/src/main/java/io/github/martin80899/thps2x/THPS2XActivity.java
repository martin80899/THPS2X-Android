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
     *  A game controller's own buttons still go to the game. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && touch != null) {
            InputDevice d = e.getDevice();
            int src = e.getSource();
            boolean pad = d != null && !d.isVirtual()
                && ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
            if (!pad) {
                if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) touch.toggleSettings();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
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
