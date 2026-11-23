package io.github.martin80899.thps2x;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** SDL3's activity: loads libSDL3.so and libmain.so (the recompiled game) and runs its SDL_main. */
public class THPS2XActivity extends SDLActivity {
    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "c++_shared", "main" };
    }

    private static final int PICK_GAME_IMAGE = 0x2A10;
    // The picker and dialog belong to the UI thread; the SDL thread waits and copies the bytes.
    private CompletableFuture<Uri> gameImageResult;
    private AlertDialog importDialog;
    private volatile boolean destroyed;

    /** Called on the SDL thread by host_android.c. Returns a temporary path owned by the installer. */
    public String pickGameImage() throws IOException {
        CompletableFuture<Uri> selection = new CompletableFuture<>();
        runOnUiThread(() -> {
            if (isFinishing() || destroyed) {
                selection.complete(null);
                return;
            }
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                gameImageResult = selection;
                startActivityForResult(picker, PICK_GAME_IMAGE);
            } catch (RuntimeException e) {
                gameImageResult = null;
                selection.completeExceptionally(e);
            }
        });

        Uri image;
        try {
            image = selection.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            throw new IOException("Could not open Android's file picker", e.getCause());
        }
        if (image == null || destroyed) return null;

        runOnUiThread(() -> {
            if (isFinishing() || destroyed) return;
            importDialog = new AlertDialog.Builder(this)
                    .setTitle("Importing disc image")
                    .setMessage("Keep THPS 2X open while it reads your ISO.")
                    .setCancelable(false)
                    .show();
        });
        File staged = null;
        boolean copied = false;
        try {
            staged = File.createTempFile("thps2x-disc-", ".iso", getCacheDir());
            try (InputStream input = getContentResolver().openInputStream(image);
                 FileOutputStream output = new FileOutputStream(staged)) {
                if (input == null) throw new IOException("The disc image could not be opened");
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while (!destroyed && (count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
            if (destroyed) return null;
            copied = true;
            return staged.getAbsolutePath();
        } finally {
            if (!copied && staged != null) staged.delete();
            runOnUiThread(this::dismissImportDialog);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_GAME_IMAGE && gameImageResult != null) {
            gameImageResult.complete(resultCode == RESULT_OK && data != null ? data.getData() : null);
            gameImageResult = null;
        }
    }

    private void dismissImportDialog() {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (gameImageResult != null) {
            gameImageResult.complete(null);
            gameImageResult = null;
        }
        dismissImportDialog();
        super.onDestroy();
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
