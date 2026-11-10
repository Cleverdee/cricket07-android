# Android build

A native arm64 APK: the translated game code, `runtime/` and `host/` compiled with the NDK, SDL2 for
the app shell, controllers and audio, and wgpu-native on Vulkan for graphics. No emulator, no WebView.
The game data stays out of the APK; the app reads it from the phone's app storage.

## Building

1. Copy the contents of your installed game folder into `game\` at the repository root, so that
   `game\cricket07.exe` sits next to the `Data` and `Audio` folders. The exe has to be unprotected
   (see "Exe check" below).
2. Double-click `android\Build.bat`.
   - The first run fetches the tools into `third_party\tools`: Python packages, JDK 21, Ghidra 12,
     and the Android SDK with NDK r27. That's about 4 GB. The SDK installer shows Google's licence
     terms and asks you to accept them.
   - runs Ghidra (10-30 min), translates to C, and compiles (20-60 min).
     The Ghidra and translation results are cached by the exe's hash, so later builds only recompile.
3. The APK lands in `android\output\Cricket07.apk`.

Most people should use `Cricket07Builder.exe` at the repository root instead (see the main README).

Options: `-GameDir <folder>` and `-OutputDir <folder>` choose the folders; `-Bundle` puts the game inside the APK; `-Rosters` adds the 2026 rosters; `Build.bat -Install` installs over USB (USB debugging on); `-PushGame` also copies `game\` to
the phone; `-Retranslate` redoes Ghidra and translation; `-Opt "-O3"` and `-Lto` for speed tests;
`-Debug` builds at -O1 for chasing crashes.

## Game files on the phone

Without `-Bundle` the app asks for them on first launch: the player picks a .zip of the installed game folder,
or the folder itself, with Android's file picker, and the app copies it in (`SetupActivity.java`). The import
warns when `cricket07.exe` isn't the one the code was translated from (`RC_EXE_HASH`, set by build.ps1).

They end up in **`Android/data/com.cleverdee.cricket07/files/game/`**, with `cricket07.exe` directly
inside. To put them there from a PC instead, install the APK and open it once first, so Android creates the folder.
Then either:

- run `Build.bat -PushGame`, or
- copy the folder over USB with a PC file manager. On Android 11 and later the phone's own file
  manager usually can't open `Android/data`.

Saves and settings go in `files/userdata/` next to it.

## Settings without rebuilding: files/env.txt

`Android/data/com.cleverdee.cricket07/files/env.txt` holds one `NAME=value` per line:

| Line | Effect |
|---|---|
| `RC_SHOW_FPS=1` | frame-rate readout, top left |
| `RC_LOG=1` | host log (files, registry, threads) to logcat |
| `RC_LOG_FPS=1` | frame rate to logcat |
| `RC_NO_AUDIO=1` | mute |
| `RC_PADS=2` | expose two controllers to the game |

Read the log with `adb logcat -s cricket07`.

## Controls

A physical controller works through SDL, as on the desktop build. While none is connected, a touch
overlay drives pad 0: a floating stick (left half of the screen) and the PlayStation-style buttons
the game expects (✕ ○ □ △, L1/R1/L2/R2, Select/Start). The phone's Back button acts as Escape.


## Where the Android code lives

- `android/host/window_android.c` replaces `host/window.c` and `host/watch.c`. It handles the
  surface handoff from Java, controllers, the touch pad, logcat and `env.txt`.
- `android/profile_cricket07.h` holds the exe name, the guest install path and the registry values.
- `android/app/` holds the Gradle app (`GameActivity`, `GameSurface`, `TouchOverlay`) and
  `jni/CMakeLists.txt`.
- Upstream files have small `#ifdef __ANDROID__` blocks:
  - `host/render_wgpu.c`: Vulkan surface from the ANativeWindow, recreated after the app returns
    from the background.
  - `host/dsound.c`: SDL audio output.
  - `host/main.c`: the game folder in app storage.

When the app goes to the background, the game thread waits at its next present until the surface
comes back, so the game pauses rather than crashing.
