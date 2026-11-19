# Otto TV

Turn an old PC or laptop into a fast, plug-and-play Android TV for any TV.
Flash a USB stick, plug the computer into the TV over HDMI, try it live, then
install it.

> Status: **v0.6.2, early development.** Tested on one machine so far:
> [Acer HM77 / Ivy Bridge](docs/hardware/acer-hm77-ivybridge.md).

**What it needs:** a 64-bit PC with SSE4.2 (Android 14 requires it: Intel
Core i3/i5/i7 from 2008-2010 on, AMD from 2011 on; Core 2 Duo, older Pentiums
and Athlon II/Phenom II won't boot), 2 GB of RAM (4 GB is comfortable) and an
8 GB USB stick. Installing takes the whole disk.

## How it works

Otto TV is built on [LineageOS TV x86](https://github.com/LineageOS-TV-x86)
(Android TV 14 for PCs). Instead of rebuilding Android, `build.sh` remasters the
upstream ISO: the 2+ GB system image is reused as is, and we only add small
files that plug into extension points upstream already has.

```
USB stick
 ├─ GRUB menu (/boot/grub/grub.cfg)          ← ours: menu, Otto theme, boot args
 ├─ /cmdline.txt                              ← ours: boot args for installed systems
 ├─ initrd (upstream's + a second archive)    ← ours: quiet /init with Otto in
 │                                               ASCII (tools/patch-initrd.sh)
 ├─ initrd: sources /scripts/* from the boot
 │   media and calls post_detect()
 │   (defined nowhere)                        ← ours: iso/scripts/9-otto
 │      ├─ live/installed boot:
 │      │    ├─ bind-mounts init.sh with our hooks prepended
 │      │    ├─ overlays /system/etc/user_app (add/remove first-boot apps)
 │      │    ├─ bind-mounts key layouts where Esc sends Back
 │      │    ├─ bind-mounts Otto's boot animation over LineageOS's
 │      │    │  (on a first boot, one that says it takes minutes)
 │      │    ├─ bind-mounts /otto on /boot/otto (Android only sees
 │      │    │  the media's /boot folder, so that's where it finds us)
 │      │    ├─ hides upstream's setup wizard, updater and home screen
 │      │    │  (whiteouts; Otto TV has its own)
 │      │    └─ starts the boot prefetcher (installed systems)
 │      └─ install mode: makes Calamares Otto TV's installer
 │           ├─ runs it from RAM (the stick can come out any time after)
 │           ├─ 4 steps, Otto's branding, Portuguese, GRUB without asking
 │           ├─ installed boot menu: Otto's entries and theme
 │           ├─ a step that copies /scripts and /otto to the installed system
 │           └─ at the end, asks for the stick to come out, then restarts
 └─ Android: /system/etc/init.sh calls
     post_init / post_bootcomplete
     (defined nowhere)                        ← ours: iso/otto/init-hooks.sh
        ├─ redraws Otto in text once the GPU driver has the screen
        ├─ first boot: "Almost ready" over the still-empty home screen
        ├─ finds the HDMI output with a TV attached and routes audio to it
        │  (unless the quick panel sent the sound to the laptop)
        ├─ makes Otto TV's launcher the home screen, with its permissions
        ├─ compiles installed apps ahead of time, once the boot settles
        ├─ picks the on-screen keyboard (none with a physical keyboard)
        └─ starts otto-remote, the phone remote   ← ours: remote/ (Go)
```

### Phone remote

```
 phone browser ──── http + WebSocket on the home Wi-Fi ────▶ otto-remote (root)
 (iPhone or Android,                                          ├─ serves the remote page
  nothing to install)                                         ├─ pairs phones (QR or PIN)
                                                              └─ /dev/uinput: virtual keyboard
 TV: "Connect phone" app ── WebView on 127.0.0.1 ──▶ QR code      and mouse that Android
     (tvapp/)                                                     treats like USB ones
 TV: home screen ────────── /app/ on 127.0.0.1 ─────▶ sound, laptop screen, restart,
     (tvapp/, home/)        (only this app: checked     system details, QR code
                             by uid in /proc/net/tcp)
```

Android won't let an app type into other apps, but the boot hooks run as
root, so `otto-remote` creates virtual input devices through uinput
instead. To Android they look like a USB keyboard and mouse, so the remote
works on every screen, system settings and dialogs included.

Pairing needs someone in front of the TV: the QR code carries a 256-bit token
in the URL fragment (browsers never send fragments to servers, so it stays
out of logs), and the PIN alternative only works while the pairing screen is
open, with a few guesses before it changes. The pairing screen itself is only
served to the TV, checked by address and Host header, so a web page can't
reach it through DNS rebinding.

**A name that stays: `otto-tv.local`.** Routers hand out addresses that
change, and a remote saved on the phone's home screen under the old one
stops working. So `otto-remote` also answers multicast DNS for
`otto-tv.local` (`remote/mdns`: that one name, no service discovery;
`otto-tv-2.local` and so on when another machine has it, kept between
boots). The QR code still opens the TV by address, which every phone can
reach; the page then checks whether this phone reaches the same TV under the
name (`/api/id`) and moves there, so what gets saved is the name. Phones
that can't resolve `.local` names stay on the address.

**Live and installed systems behave the same.** The upstream installer only
copies the system image, kernel, initrd and recovery to disk, so on its own it
would leave our files behind. Two things carry them over: our installer step
copies `/scripts` and `/otto` next to the installed kernel (where the
installed initrd looks for `/scripts`), and the installer writes the first
line of `/cmdline.txt` into the installed boot entries.

Nothing on the system image changes, so builds are small and fast, and
moving to a new upstream release is mostly bumping the ISO hash.

## Features

- **Otto TV's own home screen.** Favorites up top (big banners, each
  tinted with the app's own color), recently used apps below, and every app
  a click away, sorted by name. Hold OK (or press Menu) on an app to pin,
  move, uninstall or see its info, with Android's own navigation sounds.
  A Wi-Fi notice when the TV is offline,
  the time, and the phone remote's status at a glance. Drawn natively at
  1080p with the design's Inter typeface; it replaces upstream's launcher
  (`OTTO_LAUNCHER=upstream` brings that one back). If it ever crashes twice
  in a row, it opens as a plain list of apps instead of leaving the TV on a
  black screen.
- **Quick panel (F2, Ctrl+Alt+Del, the phone remote's Panel button) and
  shortcuts (F1).** Over any app:
  where the sound goes (TV or laptop speakers, remembered across boots), the
  laptop screen, Wi-Fi, connecting another phone, suspend/restart/turn off
  and the system's details (CPU temperature and use, memory, IP, adb).
  F1 lists the keyboard and mouse shortcuts, with the phone remote's QR
  code. Small notices confirm what happened ("Celular conectado", the app
  that Alt+F4 closed, the laptop screen going dark).
- **Phone remote, no app to install.** Scan the QR code on the TV and the
  phone's browser becomes a remote: directional pad, OK, back, home, volume,
  play/pause, a touchpad and a keyboard that types on the TV (accents
  included). Works on iPhone and Android, and can be added to the home
  screen like an app. Holding a button repeats, like a real remote. The
  saved remote finds the TV by name (`otto-tv.local`), so it keeps working
  when the router gives the TV a new address.
- **Laptop keyboards that make sense on a TV.** Esc goes back (there is no
  Back key on a PC keyboard, and Android TV apps rely on one), the Windows
  key goes home, Alt+F4 closes the app on screen (on the home screen it
  opens the power options, like on a Windows desktop), Ctrl+Alt+Del opens
  the power options, F1 the shortcuts and F2 the quick panel. The shortcuts come from the phone remote's service, which
  reads the keyboard without taking keys away from Android. And the
  on-screen keyboard stays out of the way when a physical keyboard is
  plugged in. Android TV's keyboard ignores that setting on purpose, so
  Otto TV switches to its own invisible keyboard, where Enter does what the TV
  keyboard's "›" button does (screens like the Wi-Fi password only continue
  on it). Upstream maps the big Enter key to the remote's OK, which is great
  in menus and does nothing in a text field, so in text fields that key is
  turned back into Enter; the remote's own OK is left alone. Remote-only
  setups keep the TV keyboard. Key decisions are logged:
  `adb logcat -s OttoKeyboard`.
- **The laptop screen goes dark when you want.** A button on the phone
  remote (and the laptop's own display Fn key, where it sends one) turns
  the laptop's screen dark while the TV is the screen. The choice is saved,
  and the screen always lights up again when no TV is connected, or while
  the TV isn't showing the picture yet.
- **The TV shows the picture as soon as it's plugged in.** Android 14 keeps
  an HDMI screen dark until someone answers "Mirror to display?", asked on
  the laptop's own screen (unseen with that screen dark or the lid closed).
  Otto TV skips the question; `OTTO_MIRROR=ask` brings it back.
- **Faster boots from a hard disk** (77 s to 42 s on the Acer). The boot
  spent most of its time waiting for the disk: Android reads several
  hundred MB in a scattered order, one seek each time. Once the home screen
  is up, Otto TV notes which parts of the system image and `/data` the boot
  read (in the order they sit on the disk); on the next boot the initrd
  reads them in long sequential reads, holding the boot for up to 12
  seconds while it reads alone on the disk, then alongside Android, with a
  larger read-ahead while the boot lasts. SSDs are left alone. The list is
  made again every three weeks; `OTTO_PREFETCH=off` turns it off.
  Installed apps are compiled ahead of time once the boot settles, so their
  first opens don't run interpreted code (`OTTO_COMPILE=off`).
- **A home screen light on old GPUs.** Its backdrop is drawn once per color
  into a GPU layer, and shadows and the focus glow are blurred once into
  masks: frames copy them instead of computing five full-screen gradients
  and blurs each time. Intel GPUs also get RP1 (their most efficient clock)
  as their lowest one: they start each burst of work at the bottom, and a
  200 ms animation ends before they ramp up (on the Acer: 8 ms of GPU per
  frame at 350 MHz, 5 ms at 650).
- **Compressed memory (zram) that actually turns on.** Upstream's zram
  script dies on the `BOOT_IMAGE=(hd96)/kernel` GRUB adds to the command
  line, so no machine got it. Otto TV fixes the script at boot; it matters
  most on laptops with 2 to 4 GB of RAM.
- **Ready when the home screen shows.** The Home key, the keyboard and the
  phone remote come up first thing at boot complete, before upstream
  installs its bundled apps (about 30 seconds in live mode). Our app is a
  system app so it is there from the start.
- **HDMI audio that just works.** Reads the HDMI jack sensors on every sound
  card and routes sound to the one with a TV attached, retrying after boot in
  case the TV was still waking up. No hard-coded device numbers, so it is
  meant to cover Intel, AMD and Nvidia HDMI audio (verified on Intel so far).
  See the
  [investigation](docs/hardware/acer-hm77-ivybridge.md#the-hdmi-audio-investigation).
- **Apps ready on first boot:** F-Droid (open-source app store), Aurora Store
  (Play Store catalog, no Google account) and DuckDuckGo (browser). `apps.txt`
  lists them by package name; the build fetches the current version from
  F-Droid and records exact versions and hashes in `apps.lock`. Only
  open-source apps whose licenses allow redistribution go in the ISO.
- **Leaner first boot.** Drops upstream apps a TV doesn't need (root manager,
  camera, game key mapper).
- **Straight to the point on first boot.** Upstream's setup wizard (a
  Bluetooth accessory search, then a LineageOS features page) is hidden.
  Otto TV's own first-run screen asks for Wi-Fi when the TV is offline, then
  shows the phone remote's QR code; Bluetooth pairing is one button away
  instead of a step everyone has to sit through.
- **Brazilian defaults.** Portuguese and `America/Sao_Paulo`, both
  configurable in `config.env`.
- **Compatibility mode** boot entry (software rendering) for GPUs the
  hardware drivers can't drive.
- **Fixes an upstream GRUB bug:** the "Disable Hardware Acceleration" toggle
  never reached the kernel (`$hwacccel` typo, plus quoting that turned
  multi-word options into one argument).

## Build

Needs Linux (WSL works) with `xorriso`, `curl`, `python3`, Go, a JDK and a
slice of the Android SDK (for Otto TV's own app, about 400 KB: the home
screen, the "Connect phone" screen and the physical-keyboard keyboard):

```sh
sudo apt install xorriso curl python3 golang openjdk-17-jdk-headless zip
tools/setup-android-sdk.sh && export ANDROID_HOME=~/android-sdk
./build.sh path/to/upstream.iso     # or set UPSTREAM_ISO_URL in config.env
```

The TV app is built without Gradle (`tools/build-tv-app.sh`: aapt2, javac,
d8, apksigner), and signed with a key kept in `cache/otto.keystore`, with
its password in `cache/otto.keystore.pass`. Both are made on the first build
and never leave `cache/` (ignored by git): updates of the app only install
when they are signed with the same key, so keep a backup of the pair.
`OTTO_PREBUILT=<dir>` takes `otto-remote` and `app.ottotv.connect.apk` from a
folder instead of compiling them.

The result is `out/otto-<version>.iso`, its `.sha256` and its `.apps.lock`.
Write the ISO to a USB stick with Rufus, balenaEtcher or `dd`, or copy it to a
Ventoy stick.

The CI workflow lints and tests on every push, and builds the ISO when run by
hand (Actions → CI → Run workflow).

The images (boot menu, boot animation, installer, app icons) are committed.
`node tools/brand/make-assets.js` and `python3 tools/brand/grub-theme.py`
regenerate them from `art/brand/otto-geo.js` (the design's Otto, drawn in
JavaScript) and `art/fonts` (Inter, SIL OFL). The SVG files in `art/brand`
are rebuilt from `otto-geo.js` too: change the geometry there, not in them.

## Boot options

Uppercase `VAR=value` pairs on the kernel command line become shell variables
in Android's `init.sh`, which both upstream and our hooks read.
`EXTRA_KERNEL_ARGS` in `config.env` sets them for the live menu and, through
`/cmdline.txt`, for installed systems:

| Option            | Default             | Effect |
|-------------------|---------------------|--------|
| `OTTO_AUDIO`      | `auto`              | `off` skips HDMI audio detection |
| `OTTO_LOCALE`     | `pt-BR`             | Default language (a language the user picks wins) |
| `OTTO_MIRROR`     | `auto`              | `ask` keeps Android's "Mirror to display?" question (from the next boot) |
| `OTTO_ESC`        | `back`              | `escape` keeps Esc as Esc |
| `OTTO_IME`        | `auto`              | `tv` always uses the TV keyboard, `hard` never shows one |
| `OTTO_REMOTE`     | `on`                | `off` doesn't start the phone remote (nor the keyboard shortcuts) |
| `OTTO_SETUP`      | `ours`              | `wizard` keeps upstream's setup wizard |
| `OTTO_LAUNCHER`   | `ours`              | `upstream` keeps upstream's home screen (and leaves the home screen choice alone) |
| `OTTO_PREFETCH`   | `on`                | `off` doesn't read ahead what the boot needs |
| `OTTO_COMPILE`    | `on`                | `off` doesn't compile installed apps ahead of time |
| `OTTO_GPU`        | `auto`              | `off` keeps Intel GPUs' lowest clock as the floor (by default it is RP1) |
| `SET_TZ_LOCATION` | `America/Sao_Paulo` | Time zone (upstream option) |

To try one without rebuilding, press `e` on a menu entry, append it to the
`linux` line and boot with Ctrl+X.

## Development

```sh
tests/run.sh
```

Covers HDMI detection against real device dumps, `post_init` with Android's
commands mocked, keyboard detection and the on-screen keyboard choice, the
Esc key layout patch, GRUB patching and rendering, the installer patch
against the real upstream `settings.conf`, the installer job against a fake
Calamares, the F-Droid helper, the TV app's Enter key logic, and the phone
remote (`remote/`, `go test`:
typing, held keys, pairing, the TV-only routes and a WebSocket session
against a fake keyboard; the launcher's routes, who may call them, the
sound switch and the shortcuts). The launcher is drawn by hand, so its
layout math (`tvapp/test/HomeMathTest.java`) runs here too.

`tools/device/input-test.sh` collects input diagnostics from a running box
and tries the keyboard fixes live, without a new ISO.
`tools/device/update.sh` updates an installed system to a new build over
ADB, without reinstalling: the build writes `out/otto-<version>-update.tar`
with just our files. `tools/device/baseline.sh` measures a box (boot time, RAM, app start times,
home screen smoothness, CPU, storage and Wi-Fi), so every optimization is
compared against numbers from the same machine; `baseline.sh video` measures
what playback costs while a video plays. `tools/device/trace.sh` records a
Perfetto trace of the home screen in use (`home`) or of a whole boot
(`boot`, then `boot-done`), for ui.perfetto.dev or trace_processor.

Debugging on a device goes through ADB over the network: enable developer
options, rooted debugging and network debugging, then
`adb connect <ip>:5555 && adb root`. Our hooks log to the kernel log
(`adb shell dmesg | grep otto`) and to logcat (`adb logcat -s otto`).

## Known gaps

- **Installation:** first run on real hardware done (the Acer: BIOS, HDD),
  and the installed system boots with our hooks. Measurements of installed
  boots are next.
- **App verification:** apps come over HTTPS from f-droid.org, picked through
  F-Droid's v1 API. F-Droid recommends its signed index for fetching apps;
  verifying against it is on the roadmap.
- **The home screen is new in v0.5:** checked screen by screen against the
  design off-device; its first run on the Acer is next.
- **Wi-Fi and keyboard layout** are set by hand in Settings for now (Wi-Fi
  passwords can be typed from the phone remote).
- **The phone remote is new in v0.3** and its uinput side has only run in
  tests so far (no uinput in CI): the first run on the Acer is next.
- **Phone remote on plain HTTP:** traffic stays on the home network, but it
  is not encrypted, so someone already on that Wi-Fi could read the token.
  The TV's address can also change; a fixed `.local` name is next.
- **Typing from the phone** goes through a US layout with Alt dead keys, so
  Portuguese accents work in standard text fields; emoji and other scripts
  are skipped (the phone says so).
- **The laptop's own screen** can only be turned dark (its backlight), so
  the GPU still draws for it. Switching the panel itself off while the
  system boots (v0.3.7) crashed the GPU driver on the Acer (Ivy Bridge) and
  left it rebooting in a loop, with no crash log. An "only the TV" mode
  with `video=LVDS-1:d` on the kernel command line is still to be tried.
- **Right mouse button as Back** isn't there yet: Esc, the phone and a
  two-finger tap on the phone's touchpad cover it.
- **Installed menu branding** still comes from upstream's installer.
- **Old Intel Macs:** upstream also ships an HFS+ copy of the boot media for
  Macs. libisofs 1.5.6 fails to lay it out next to the EFI partition once
  files change ("GPT partitions overlap"), so the build drops it. PCs boot
  through BIOS or UEFI as before; Macs that only boot from HFS+ won't.
- **Release hosting:** the ISO is ~2.4 GB and GitHub release assets are capped
  at 2 GB, so CI builds land as short-lived workflow artifacts for now.

## Credits

Built on [LineageOS TV x86](https://github.com/LineageOS-TV-x86), which builds
on LineageOS, [Bliss OS](https://blissos.org) and Android-x86. Otto TV only adds
boot-time configuration on top of their work. Bundled apps come from
[F-Droid](https://f-droid.org) under their own open-source licenses.
