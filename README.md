# Cricket 07 for Android

Play EA Sports Cricket 07 natively on an Android phone, built from **your own copy of the PC game**. This is a
recompilation, not an emulator: the game's code is translated to C and compiled for ARM, and graphics run on Vulkan.

**No game files are included here.** You need your own installed copy of the PC game.

## What you get

- Touch controls that change with what's happening (batting, bowling, fielding, menus), plus a classic controller layout
- Batting: choose your shot on the stick without moving the batsman, using separate move buttons (or joystick only, in settings)
- Physical controller support
- 2026 teams (optional): national sides, English counties, IPL, PSL, Big Bash and CPL, with ratings worked out from player stats
- Local multiplayer: two phones on the same Wi-Fi or hotspot
- Performance work for mid-range phones: tested on a Samsung Galaxy A17 at around 60 FPS

## Phone only: install the app, then add your game files

1. Download `Cricket07.apk` from [Releases](../../releases) on your phone and open it to install (Android asks you to
   allow installing from your browser or file manager). You need a 64-bit phone on Android 10 or later with Vulkan.
2. On your PC, zip the folder Cricket 07 is installed in (usually `C:\Program Files (x86)\EA SPORTS\Cricket 07`):
   right-click it, then *Send to > Compressed (zipped) folder*. Copy the .zip to your phone any way you like: USB
   cable, Google Drive, a memory card.
3. Open the app, tap **Choose a .zip file** and pick it. The app copies the game in (about 1.2 GB), then you can
   delete the .zip. If you copied the folder over without zipping it, use **Choose a folder** instead.

You can add, replace or delete the game files later from **Game files** on the start screen. The ready-made APK has
the original 2006 teams: the 2026 rosters are added when you build the APK yourself (below).

## Mods

**Mods** on the start screen adds other people's versions of game files: a `data.gob` with new rosters, kits,
commentary in `Audio/`. Add a mod as a .zip laid out like the game folder (`Data/data.gob`, ...) or as a single game
file. Each mod can be switched on and off or removed; your original files are set aside and come back. When two mods
change the same file, the one added last wins. The app won't let a mod replace `cricket07.exe`, checks archives are
complete before using them, and offers to switch mods off if the game doesn't reach its menu. For multiplayer both
phones need the same mods.

## Or build it yourself

You need Windows 10 or 11, [Python 3](https://www.python.org/downloads/) ("Add python.exe to PATH" ticked), about
15 GB of free disk space, and your installed game.

1. Download this project and put it in a short folder with no spaces, such as `C:\cricket07`.
2. Double-click **`Cricket07Builder.exe`**. If Windows SmartScreen warns about an unknown publisher, choose
   *More info*, then *Run anyway*. The source is in [`builder/`](builder/Cricket07Builder.cs), and
   `builder\compile.ps1` compiles it yourself if you prefer.
3. Pick your game folder and where to save the APK, then click **Build**.

Building it yourself gives you the 2026 rosters, works with other versions of the game, and puts the game files
inside the APK, so there's nothing to add on the phone.

The first build downloads about 4 GB of tools (JDK, Ghidra, Android SDK and NDK) and takes about an hour, longer on slower PCs. Those results
are kept, so later builds are much faster. The APK is about 1.2 GB and needs about 1.2 GB more on the phone, where it
unpacks its files the first time you open it.

For building from a command line, see [`android/README.md`](android/README.md).

## Install a built APK

Copy `Cricket07.apk` to your phone and open it (Android will ask you to allow installing from that source), or tick
*Install on my phone* in the builder with the phone connected over USB and USB debugging on. You need a 64-bit phone
on Android 10 or later with Vulkan.

## Multiplayer

Both phones have to be on the same Wi-Fi or hotspot and run **the same APK** with the same game files. The game checks
this when connecting, and separate builds may not match, so build once and install that APK on your own two phones
(or both install the release APK and add the same game files). One taps **Host a
match**, and the other taps **Join a match** and picks it (or types its address).

Multiplayer is new. It has been tested on one phone against a PC stand-in, but not yet between two real phones, so
expect problems such as the games drifting out of sync.

## Known limits

- Tested on a Samsung Galaxy A17, Xiaomi Mi 10T Pro and Redmi 9. Other phones may differ.
- Tested with one version of the game. The builder tells you if yours differs; it may then build but not play correctly.
- Building is Windows only for now.
- In the touch-control settings (the gear), **Screen** picks the picture shape: Widescreen (the default: fills the
  screen and shows a wider view), Stretch, or the original 4:3 with black bars. Menus and the scoreboard are always
  stretched in the first two. **Button opacity** makes the touch buttons more see-through.
- Many player stats are estimates, so ratings are approximate, and new players use generic faces. Squads are as of
  October 2026; the IPL ones predate the 2027 retentions.
- The game has 63 team slots, so the leagues replace the Australian states, A sides, invitation and World XIs, and
  the smallest nations. Game modes that used those teams may show league sides instead.
- League kits are painted from one template in approximately the teams' colours, and only the coloured kit changes.
  Real team logos aren't included: put your own images in `rosters/logos/`, named after the team
  (`Chennai Super Kings.png`), and the build adds them. Teams without one keep the old logo.

## How it works

`translator/` turns the game's x86 code into C using Ghidra's analysis. `runtime/` and `host/` provide the CPU state
and a small Windows, Direct3D 8 and DirectSound layer on top of wgpu and AAudio, and `android/` is the app.
[`rosters/`](rosters/) patches a copy of the game's archive, leaving your original alone.

## Credits and legal

Built on [cricket07-browser](https://github.com/Dhaiwat10/cricket07-browser) by Dhaiwat Pandya, which did the
recompiler and the Windows and Direct3D layer. Its own guide is in [`README.browser.md`](README.browser.md). See
[`LICENSE`](LICENSE) for the code's licence.

This is an unofficial fan project, not affiliated with or endorsed by Electronic Arts. EA SPORTS and Cricket 07 are
trademarks of their owners. The licence covers the code here only: the game, its code and its data remain the
property of their owners. APKs you build with the game files inside contain the game, so keep them to yourself and
don't share them.
