package io.github.martin80899.thps2x;

import android.os.Bundle;
import android.view.Display;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestFastestRefresh();
        if (mLayout != null) {   // SDL's layout holding the game's surface: the on-screen pad goes on top
            mLayout.addView(new TouchControls(this), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }
}
