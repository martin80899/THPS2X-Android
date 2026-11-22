/* touch_android.c - the on-screen pad (android/.../TouchControls.java) into the game's port 0.
 *
 * The Java overlay sends its whole state on every change; it becomes an Xbox gamepad state that the
 * toolkit merges on top of port 0 (xbox_InputTouchState, like the keyboard), so a Bluetooth or USB
 * pad on port 0 and the touch pad work together.
 */
#include <jni.h>
#include <string.h>
#include <SDL3/SDL_system.h>
#include "xinput_xbox.h"
#include "settings.h"
#include "nv2a_vk.h"

extern const char *host_android_data_dir(void);

/* Button bits of TouchControls.java (keep in step) */
enum {
    TB_A = 1 << 0, TB_B = 1 << 1, TB_X = 1 << 2, TB_Y = 1 << 3,
    TB_BLACK = 1 << 4, TB_WHITE = 1 << 5, TB_LT = 1 << 6, TB_RT = 1 << 7,
    TB_UP = 1 << 8, TB_DOWN = 1 << 9, TB_LEFT = 1 << 10, TB_RIGHT = 1 << 11,
    TB_START = 1 << 12, TB_BACK = 1 << 13, TB_LTHUMB = 1 << 14, TB_RTHUMB = 1 << 15,
};

static short axis(float v)
{
    if (v > 1.0f) v = 1.0f;
    if (v < -1.0f) v = -1.0f;
    return (short)(v * 32767.0f);
}

JNIEXPORT void JNICALL
Java_io_github_martin80899_thps2x_TouchControls_nativeState(JNIEnv *env, jclass cls, jint b,
                                                            jfloat lx, jfloat ly, jfloat rx, jfloat ry)
{
    XBOX_GAMEPAD g;
    int i;
    (void)env; (void)cls;
    memset(&g, 0, sizeof g);
    if (b & TB_UP)     g.wButtons |= XBOX_GAMEPAD_DPAD_UP;
    if (b & TB_DOWN)   g.wButtons |= XBOX_GAMEPAD_DPAD_DOWN;
    if (b & TB_LEFT)   g.wButtons |= XBOX_GAMEPAD_DPAD_LEFT;
    if (b & TB_RIGHT)  g.wButtons |= XBOX_GAMEPAD_DPAD_RIGHT;
    if (b & TB_START)  g.wButtons |= XBOX_GAMEPAD_START;
    if (b & TB_BACK)   g.wButtons |= XBOX_GAMEPAD_BACK;
    if (b & TB_LTHUMB) g.wButtons |= XBOX_GAMEPAD_LEFT_THUMB;
    if (b & TB_RTHUMB) g.wButtons |= XBOX_GAMEPAD_RIGHT_THUMB;
    for (i = 0; i < 8; i++)                      /* A B X Y Black White LT RT = bAnalogButtons order */
        if (b & (1 << i)) g.bAnalogButtons[i] = 255;
    g.sThumbLX = axis(lx);
    g.sThumbLY = axis(-ly);                      /* screen y points down, the Xbox's up */
    g.sThumbRX = axis(rx);
    g.sThumbRY = axis(-ry);
    xbox_InputTouchState(&g);
}

JNIEXPORT void JNICALL
Java_io_github_martin80899_thps2x_TouchControls_nativeOff(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    xbox_InputTouchState(NULL);
}

/* The touch menu's FPS COUNTER line: the game's own setting (RECOMP OPTIONS -> FRAME RATE -> FPS COUNTER),
 * 0 off, 1 fps, 2 fps and frame time, 3 frame time. */
JNIEXPORT jint JNICALL
Java_io_github_martin80899_thps2x_TouchControls_nativeGetFps(JNIEnv *env, jclass cls)
{
    const char *v = settings_get("fps_counter", "off");
    (void)env; (void)cls;
    return !strcmp(v, "fps") ? 1 : !strcmp(v, "fps_frametime") ? 2 : !strcmp(v, "frametime") ? 3 : 0;
}

JNIEXPORT void JNICALL
Java_io_github_martin80899_thps2x_TouchControls_nativeSetFps(JNIEnv *env, jclass cls, jint mode)
{
    static const char *const val[4] = { "off", "fps", "fps_frametime", "frametime" };
    static const int hud[4] = { NV2A_VK_HUD_OFF, NV2A_VK_HUD_FPS, NV2A_VK_HUD_BOTH, NV2A_VK_HUD_MS };
    (void)env; (void)cls;
    if (mode < 0 || mode > 3) mode = 0;
    settings_set("fps_counter", val[mode]);
    nv2a_vk_set_fps_hud(hud[mode]);
}

/* Where data/env.txt lives (the touch menu's graphics tests write it; host_android.c reads it at start). */
JNIEXPORT jstring JNICALL
Java_io_github_martin80899_thps2x_TouchControls_nativeDataDir(JNIEnv *env, jclass cls)
{
    (void)cls;
    return (*env)->NewStringUTF(env, host_android_data_dir());
}

/* The Android settings menu: the game's options in tabs (recomp_manual.c), read and stepped. */
extern int thps_android_opt_count(void);
extern const char *thps_android_opt_group(int i);
extern const char *thps_android_opt_name(int i);
extern const char *thps_android_opt_desc(int i);
extern void thps_android_opt_value(int i, char *out, size_t n);
extern void thps_android_opt_step(int i, int dir);

JNIEXPORT jint JNICALL
Java_io_github_martin80899_thps2x_SettingsMenu_nativeOptCount(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return thps_android_opt_count();
}

JNIEXPORT jstring JNICALL
Java_io_github_martin80899_thps2x_SettingsMenu_nativeOptGroup(JNIEnv *env, jclass cls, jint i)
{
    (void)cls;
    return (*env)->NewStringUTF(env, thps_android_opt_group(i));
}

JNIEXPORT jstring JNICALL
Java_io_github_martin80899_thps2x_SettingsMenu_nativeOptName(JNIEnv *env, jclass cls, jint i)
{
    (void)cls;
    return (*env)->NewStringUTF(env, thps_android_opt_name(i));
}

JNIEXPORT jstring JNICALL
Java_io_github_martin80899_thps2x_SettingsMenu_nativeOptDesc(JNIEnv *env, jclass cls, jint i)
{
    (void)cls;
    return (*env)->NewStringUTF(env, thps_android_opt_desc(i));
}

JNIEXPORT jstring JNICALL
Java_io_github_martin80899_thps2x_SettingsMenu_nativeOptValue(JNIEnv *env, jclass cls, jint i)
{
    char v[96];
    (void)cls;
    thps_android_opt_value(i, v, sizeof v);
    return (*env)->NewStringUTF(env, v);
}

JNIEXPORT void JNICALL
Java_io_github_martin80899_thps2x_SettingsMenu_nativeOptStep(JNIEnv *env, jclass cls, jint i, jint dir)
{
    (void)env; (void)cls;
    thps_android_opt_step(i, dir);
}

/* The FPS badge (TouchControls draws it): frames per second and the longest frame of the last half
 * second, and whether the FPS COUNTER setting asks for it (0 off, 1 fps, 2 both, 3 frame time). */
extern void nv2a_vk_frame_stats(float *fps, float *ms);
JNIEXPORT jfloatArray JNICALL
Java_io_github_martin80899_thps2x_TouchControls_nativeFrameStats(JNIEnv *env, jclass cls)
{
    float v[2] = { 0, 0 };
    jfloatArray a = (*env)->NewFloatArray(env, 2);
    (void)cls;
    nv2a_vk_frame_stats(&v[0], &v[1]);
    if (a) (*env)->SetFloatArrayRegion(env, a, 0, 2, v);
    return a;
}

/* Rumble: the game's motor speeds for player 1 go to TouchControls.onRumble (the phone's vibrator, under
 * touch controls) from the game thread - SDL's thread, attached to the VM; the class is the one Java
 * handed over in nativeRumbleSetup, since FindClass on a native thread sees only the system classes. */
static jclass s_tc_class;
static jmethodID s_tc_rumble;
static unsigned s_last_rumble = 0xFFFFFFFFu;

static void rumble_hook(DWORD port, WORD low, WORD high)
{
    JNIEnv *env;
    unsigned v = ((unsigned)low << 16) | high;
    if (port != 0 || v == s_last_rumble || !s_tc_class || !s_tc_rumble)
        return;
    s_last_rumble = v;
    env = (JNIEnv *)SDL_GetAndroidJNIEnv();
    if (!env)
        return;
    (*env)->CallStaticVoidMethod(env, s_tc_class, s_tc_rumble, (jint)low, (jint)high);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
}

JNIEXPORT void JNICALL
Java_io_github_martin80899_thps2x_TouchControls_nativeRumbleSetup(JNIEnv *env, jclass cls, jint pad_percent)
{
    if (!s_tc_class) {
        s_tc_class = (jclass)(*env)->NewGlobalRef(env, cls);
        s_tc_rumble = (*env)->GetStaticMethodID(env, cls, "onRumble", "(II)V");
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); s_tc_rumble = NULL; }
        xbox_rumble_hook = rumble_hook;
    }
    xbox_rumble_pad_percent = pad_percent;
}

/* Start on player 1 through the touch state (the pause on coming back, recomp_manual.c); let go = all
 * released, which a connected controller's own state is merged over unchanged */
void touch_android_start(int down)
{
    XBOX_GAMEPAD g;
    memset(&g, 0, sizeof g);
    if (down)
        g.wButtons = XBOX_GAMEPAD_START;
    xbox_InputTouchState(&g);
}
