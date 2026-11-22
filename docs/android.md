# Android (arm64)

Status: **first build, not yet run on a phone.** The toolkit, SDL3 and the game side compile for
`aarch64-linux-android31`; nothing has booted yet.

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

The first start asks for "All files access" to read that folder.

## Building

1. **Shell APK** (SDL3 Java + `libSDL3.so` + `libc++_shared.so`, no game code): GitHub Actions
   workflow `android-shell` (`.github/workflows/android.yml`), artifact `thps2x-android-shell`.
2. **Game library and APK**, on a machine with your `default.xbe`:

```sh
export ANDROID_NDK=/path/to/ndk          # r27 or later
export ANDROID_DEPS=/path/to/prefix      # SDL3 3.4.18 + glslang 16.6.0 built for android-arm64, installed here
tools/android_build.sh /path/to/default.xbe app-debug.apk
```

`tools/android_pack.py` adds `libmain.so` to the shell and signs it with `../private/android-key.json`
(made on first use; keep it, so later builds install over earlier ones). The APK contains code
built from your own game: do not share it.
