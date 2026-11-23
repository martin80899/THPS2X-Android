# Android (arm64)

The Android port targets 64-bit ARM phones with Android 12 or later and Vulkan 1.3.
This is a bring-your-own-game source release. Each player builds their APK locally from
their own USA Xbox disc image or its extracted `default.xbe`. The build uses the executable
to generate native ARM64 code. Once the resulting APK is installed, the disc picker imports
the remaining game files from the player's ISO.

## How it fits together

- **The game code** is the same generated C as on Windows (`tools/lift.sh` on the player's own
  `default.xbe`), compiled for arm64 into `libmain.so`. Guest memory is reached through
  `g_xbox_mem_offset`, so the 32-bit Xbox addresses work unchanged in a 64-bit process.
- **Platform layer:** `src/platform/host_android.c` (paths, log, faults, All files access) replaces
  `host_win32.c`. The fault handler forwards page-tracking faults to the toolkit like the Windows VEH.
- **Memory order:** the Xbox has one CPU and titles rely on it. arm64 reorders plain stores between
  threads, so Android runs with the toolkit's guest CPU lock on (`RECOMP_GUEST_LOCK=1`, one guest
  thread at a time). Set `RECOMP_GUEST_LOCK=0` to compare.
- **Window, audio, pads:** SDL3 (its `SDLActivity` starts the game's `SDL_main`). Renderer: the
  toolkit's Vulkan 1.3 back end.
- **Touch:** `TouchControls.java` draws an Xbox pad over the game and sends its state to
  `src/platform/touch_android.c`, which the toolkit merges on top of port 0
  (`xbox_InputTouchState`, `patches/xboxrecomp-android.patch`). It hides while a physical
  controller is connected.
- **Toolkit changes** for Android are in `patches/xboxrecomp-android.patch` (bionic has no
  `pthread_cancel` / `explicit_bzero`; the touch pad input).

## The player's files

```
/storage/emulated/0/THPS2X/game    default.xbe and the disc's folders, or the disc image (.iso)
/storage/emulated/0/THPS2X/media   optional: boot.mpg, soundtracks, shader packs
/storage/emulated/0/THPS2X/saves   saves and settings.ini
/storage/emulated/0/THPS2X/data    thps2x.log (send this with bug reports)
```

When no game is installed, THPS 2X opens Android's file picker. Choose your original Xbox
THPS 2X (USA) disc image from Downloads, an SD card, or another location shown in the picker.
The port copies the image into temporary storage, checks the game's version, and extracts the
game files. Allow enough free space for both the temporary ISO and the extracted files. The
selected ISO stays where you picked it. Later launches use the extracted game automatically.
If you cancel the picker, reopen THPS 2X to try again.

The layout above is used when the app has "All files access", including existing installs there.
With a fresh install that uses the picker without that permission, the same layout is kept in
app-specific storage.

After a run, use **Save Career → Xbox Hard Disk** and wait for the success message.
Cheat toggles are restored when you return to the main menu after launch. For cheats
that change Career progress, use the game's save menu to keep those changes.

## Building your own APK

Run the build on a Linux PC or in WSL on Windows. Use Python 3 with `capstone`, CMake,
Ninja, and the Android NDK. Use the same checkout for the shell and the native game build.

1. **Shell APK** (SDL3 Java + `libSDL3.so` + `libc++_shared.so`, no game code): GitHub Actions
   workflow `android-shell` (`.github/workflows/android.yml`), artifact `thps2x-android-shell`.
   Download the latest [shell APK](https://github.com/martin80899/THPS2X-Android/raw/refs/heads/shell-apk/thps2x-android-shell.apk)
   for the source revision on `main`. The workflow can also be run on a fork with Actions enabled.
   It builds the shell from source. Use the downloaded shell APK as input to step 2.
2. **Game library and APK**. Check out [xboxrecomp-vulkan](https://github.com/almost-drowned-rat/xboxrecomp-vulkan)
   in `../xboxrecomp` or set `XBOXRECOMP_DIR` to its location. Keep your ISO on your computer:

```sh
export ANDROID_NDK=/path/to/ndk          # r27 or later
export ANDROID_DEPS=/path/to/prefix      # SDL3 3.4.18 + glslang 16.6.0 built for android-arm64, installed here
export CMAKE_EXTRA="-DSDL3_DIR=$ANDROID_DEPS/lib/cmake/SDL3 -Dglslang_DIR=$ANDROID_DEPS/lib/cmake/glslang"
tools/android_build.sh "/path/to/THPS 2X (USA).iso" /path/to/thps2x-android-shell.apk THPS2X.apk
```

`tools/android_pack.py` adds `libmain.so` to the shell and signs it with `../private/android-key.json`
(made on first use; keep it, so later builds install over earlier ones). Install the resulting `THPS2X.apk` on your phone and open it to select your ISO.

## Sharing the project

Share the repository and these instructions. The shell APK is a build input; the personal
APK contains executable code translated from your disc. Keep your ISO, extracted game
files, generated code, personal APK, and signing key on your own devices.
