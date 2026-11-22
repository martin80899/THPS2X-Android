/* touch_android.c - the on-screen pad (android/.../TouchControls.java) into the game's port 0.
 *
 * The Java overlay sends its whole state on every change; it becomes an Xbox gamepad state that the
 * toolkit merges on top of port 0 (xbox_InputTouchState, like the keyboard), so a Bluetooth or USB
 * pad on port 0 and the touch pad work together.
 */
#include <jni.h>
#include <string.h>
#include "xinput_xbox.h"

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
