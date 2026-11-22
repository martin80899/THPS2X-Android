package io.github.martin80899.thps2x;

import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

/** SDL3's activity: loads libSDL3.so and libmain.so (the recompiled game) and runs its SDL_main. */
public class THPS2XActivity extends SDLActivity {
    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "c++_shared", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (mLayout != null) {   // SDL's layout holding the game's surface: the on-screen pad goes on top
            mLayout.addView(new TouchControls(this), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }
}
