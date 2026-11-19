# Otto TV: technical details

The [README](../README.md) says what Otto TV is. This page is for people who
want to know how it works, build it or change it.

- [The idea](#the-idea)
- [What happens at boot](#what-happens-at-boot)
- [Where things are](#where-things-are)
- [The phone remote](#the-phone-remote)
- [Updates from the TV](#updates-from-the-tv)
- [Making an old laptop feel fast](#making-an-old-laptop-feel-fast)
- [Keyboard, sound and screens](#keyboard-sound-and-screens)
- [Build](#build)
- [Boot options](#boot-options)
- [Tests and device tools](#tests-and-device-tools)
- [Known gaps](#known-gaps)
- [Credits](#credits)

## The idea

Otto TV doesn't build Android. It takes the
[LineageOS TV x86](https://github.com/LineageOS-TV-x86) ISO (Android TV 14 for
PCs), keeps its 2+ GB system image exactly as it is, and adds a few small
files in places where upstream already looks for extra scripts. So builds
take a minute, and moving to a new upstream release is mostly changing a
hash in `config.env`.

## What happens at boot

```
USB stick or installed disk
 ├─ GRUB menu                      ours: Otto's menu and theme, boot options
 ├─ initrd                         upstream's, plus a quiet boot screen with
 │                                 Otto drawn in text
 │   └─ runs iso/scripts/9-otto    ours, before Android starts:
 │        ├─ layers our files over Android's (boot animation, key layouts,
 │        │  first-boot apps, our app) without touching the system image
 │        ├─ hides upstream's setup wizard, updater and home screen
 │        ├─ reads ahead what the boot needs (hard disks only)
 │        └─ in install mode: turns the installer into Otto TV's installer
 └─ Android
     └─ runs iso/otto/init-hooks.sh   ours, as root:
          ├─ sends the sound to the HDMI output that has a TV on it
          ├─ makes Otto TV's app the home screen
          ├─ picks the right on-screen keyboard
          ├─ starts otto-remote (the phone remote and more)
          └─ once things settle: compiles apps ahead of time
```

Live and installed systems behave the same: our installer step copies the
Otto TV files next to the installed kernel, where the installed boot looks
for them.

## Where things are

| Folder | What's in it |
|---|---|
| `iso/scripts/9-otto` | The initrd hook (shell), before Android starts |
| `iso/otto/` | What Android uses: `init-hooks.sh`, boot animations, the installer's pieces |
| `remote/` | `otto-remote` (Go, runs as root): phone remote, `otto-tv.local`, updates, boot read-ahead |
| `tvapp/` | The TV app (Java): home screen, quick panel, "Connect phone", keyboard |
| `tools/` | Build scripts, and `tools/device/` with scripts that run on a TV over ADB |
| `tests/` | `tests/run.sh` runs everything |
| `art/` | Otto's drawing and fonts; the images are generated from here |
| `native/movbe/` | MOVBE in software, for Widevine on older CPUs (C, no libc) |
| `docs/hardware/` | Notes on each machine it has run on |

## The phone remote

Android doesn't let an app type into other apps. `otto-remote` runs as root,
so it creates a virtual keyboard and mouse instead (through uinput). To
Android they're just a USB keyboard and mouse, so the phone works on every
screen, settings included.

```
 phone browser ─── http + WebSocket on the home Wi-Fi ───▶ otto-remote
 (nothing to install)                                       ├─ serves the remote page
                                                            ├─ pairs phones (QR or PIN)
 TV app ─── 127.0.0.1, only this app ──────────────────────▶ └─ virtual keyboard and mouse
 (QR code, sound, laptop screen, restart, details)
```

**Pairing needs someone in front of the TV.** The QR code carries a random
token after the `#` in the address, a part browsers never send to servers.
The PIN only works while the "Connect phone" screen is open, and a few wrong
guesses change it. The screen that shows the token only answers the TV
itself.

**`otto-tv.local`.** Routers change addresses, and a remote saved on the
phone's home screen would stop working. So `otto-remote` also answers
multicast DNS for `otto-tv.local` (`remote/mdns`: just that name, nothing
else; `otto-tv-2.local` if another machine already has it). The QR code
still opens the TV by its address, since every phone can reach that. The
page then checks whether this phone also reaches the same TV by name, and
moves there if it does, so what gets saved is the name. Phones that can't
resolve `.local` names just stay on the address.

The remote runs over plain HTTP on the home network. It isn't encrypted, so
someone already on your Wi-Fi could read the token (see Known gaps).

## Updates from the TV

Installed TVs update from this repository's GitHub releases.

```
 TV app (home/Updates.java)                    otto-remote (remote/update)
  looks at the latest release ──────────────▶  is the manifest signed? newer?
  shows "Update to version X" in the panel
  streams the update archive ───────────────▶  checks size and SHA-256,
                                               installs it, asks for a restart
```

Every release carries two files: the update archive
(`otto-tv-<version>-update.tar`, the same one `tools/device/update.sh`
installs over ADB) and `otto-tv-update.json`, a manifest with the archive's
size and SHA-256, signed with Ed25519. The public key is built into
`otto-remote` (`remote/update/key.go`). The private one,
`cache/otto-update.key`, only exists on the build machine, so nobody else,
GitHub included, can get a TV to install something. The TV app does the
downloading (Android handles DNS, TLS and proxies) and `otto-remote` only
installs what the signature vouches for. Live systems (the USB stick) don't
update.

The TV looks right after boot and then every 6 hours (if the network isn't
up yet, it tries again a minute later). When it finds one, the home screen
shows "Update available" at the top until it's installed.

**Publishing a release:**

1. Bump `VERSION` in `config.env` and build. With `cache/otto-update.key`
   there, the build writes `out/otto-tv-<version>-update.tar` and the signed
   `out/otto-tv-update.json`.
2. Tag the commit `v<version>` and push the tag.
3. On GitHub, create a release from that tag, attach both files and publish.
   Leave it as a normal release: TVs ignore pre-releases.

`UPDATE_REPO` in `config.env` names the repository (empty turns updates off).

**Keep backups of the keys.** Without `cache/otto-update.key`, TVs can't take
new releases. Without `cache/otto.keystore` and its `.pass`, the app can't be
updated (Android only installs an update signed with the same key).

## Making an old laptop feel fast

Measured on the Acer (i5-3230M, Intel HD 4000, 6 GB, hard drive):

| | Before | Now |
|---|---|---|
| Boot to the home screen | 77 s | 40 s |
| Janky frames on the home screen | 30% | 4% |
| Back to an app that was open | 1.1 s | 0.06 s |

What does it:

- **Boot read-ahead.** On a hard disk the boot spends most of its time
  waiting for seeks. Once the home screen is up, `otto-remote` notes which
  parts of the system image and `/data` the boot used, sorted by where they
  sit on the disk. On the next boot the initrd reads them in long sequential
  reads before Android asks for them. SSDs are skipped. The list is made
  again every three weeks (`remote/prefetch`).
- **Apps compiled ahead of time**, three minutes after boot, so their first
  opens don't run interpreted code.
- **A lighter home screen.** The backdrop is drawn once per color into a GPU
  layer, and shadows and the focus glow are blurred once into masks, instead
  of being computed on every frame.
- **Intel GPUs start at their efficient clock (RP1).** They start each burst
  of work at the lowest clock, and a short animation would end before they
  speed up.
- **zram that actually turns on.** Upstream's script failed on every machine
  because of an argument GRUB adds; Otto TV fixes it at boot.

`tools/device/baseline.sh` and `tools/device/trace.sh` are how these were
measured (see [Tests and device tools](#tests-and-device-tools)).

## Keyboard, sound and screens

**Keyboard shortcuts.** A PC keyboard has no Back key, and Android TV apps
need one:

| Key | Does |
|---|---|
| Esc | Back |
| Windows key | Home |
| Alt+F4 | Closes the app on screen (power options on the home screen) |
| Ctrl+Alt+Del | Power options |
| F1 | Shortcuts, with the phone's QR code |
| F2 | Quick panel |

With a physical keyboard plugged in, the on-screen keyboard stays out of the
way, and Enter does what the TV keyboard's "next" button does.

**Sound.** At boot, the hooks read the HDMI jack sensors on every sound card
and send the sound to the one with a TV on it, trying again a bit later in
case the TV was still waking up. Nothing is hard-coded, so it should work on
Intel, AMD and Nvidia (only Intel is verified). The quick panel switches to
the laptop speakers, and the choice is kept. The full story is in
[the Acer notes](hardware/acer-hm77-ivybridge.md#the-hdmi-audio-investigation).

**Screens.** Android 14 keeps an HDMI screen dark until someone answers
"Mirror to display?", and asks on the laptop's own screen. Otto TV skips
that question. The laptop's screen can go dark (its backlight) from the
phone or the quick panel, and it always lights up again when no TV is
showing the picture.

**Protected video (Netflix, Prime Video...).** These apps play through
Widevine, Google's DRM. On a PC it's the software version (L3), so services
cap the quality, usually around 480p. Upstream's Widevine service was built
for CPUs from 2013 on: on older ones (the Acer's Ivy Bridge) it crashed with
"illegal instruction" the moment an app asked for a license, so nothing with
protected video could play. The missing instruction is MOVBE (load or store
with the bytes reversed), used in 7 places, none of them in decoding video.
`native/movbe` is a small library that does those in software: the initrd
loads it into that one service, only on CPUs without MOVBE. Upstream has no
Google Play services either, and some apps need them; and Netflix may refuse
devices that aren't certified anyway.

**First boot.** Upstream's setup wizard is hidden. Otto TV asks for Wi-Fi if
the TV is offline, then shows the phone's QR code. Bluetooth pairing is one
button away.

## Build

You need Linux (WSL works):

```sh
sudo apt install xorriso curl python3 golang openjdk-17-jdk-headless zip
tools/setup-android-sdk.sh && export ANDROID_HOME=~/android-sdk
./build.sh path/to/upstream.iso     # or set UPSTREAM_ISO_URL in config.env
```

You get `out/otto-tv-<version>.iso` (plus its `.sha256` and `.apps.lock`)
and `out/otto-tv-<version>-update.tar`. Write the ISO with Rufus,
balenaEtcher or `dd`, or copy it to a Ventoy stick.

A few things worth knowing:

- `tools/build-ours.sh <dir>` builds just our two programs (`otto-remote`
  and the TV app). `OTTO_PREBUILT=<dir> ./build.sh` uses them, for a machine
  without Go or the Android SDK.
- `OTTO_UPDATE_ONLY=1 ./build.sh` stops after the update archive, which skips
  the slow ISO step.
- The TV app is built without Gradle (`tools/build-tv-app.sh`). The first
  build creates its signing key in `cache/`, which git ignores.
- The bundled apps are listed in `apps.txt`. The build fetches them from
  F-Droid and writes their exact versions and hashes to `apps.lock`.
- The images (boot menu, animation, icons) are committed.
  `node tools/brand/make-assets.js` and `python3 tools/brand/grub-theme.py`
  remake them from `art/brand/otto-geo.js` and `art/fonts`.
- CI lints and tests every push, and builds the ISO when you run it by hand
  (Actions → CI → Run workflow).

## Boot options

Uppercase `VAR=value` pairs on the kernel command line become variables our
hooks read. `EXTRA_KERNEL_ARGS` in `config.env` sets them for every boot. To
try one once, press `e` on a menu entry, add it to the `linux` line and boot
with Ctrl+X.

| Option | Default | What it changes |
|---|---|---|
| `OTTO_AUDIO` | `auto` | `off` skips HDMI audio detection |
| `OTTO_LOCALE` | `pt-BR` | Default language (one the user picks wins) |
| `OTTO_MIRROR` | `auto` | `ask` brings back "Mirror to display?" |
| `OTTO_ESC` | `back` | `escape` keeps Esc as Esc |
| `OTTO_IME` | `auto` | `tv` always uses the TV keyboard, `hard` never shows one |
| `OTTO_REMOTE` | `on` | `off` doesn't start the phone remote (or the shortcuts) |
| `OTTO_SETUP` | `ours` | `wizard` keeps upstream's setup wizard |
| `OTTO_LAUNCHER` | `ours` | `upstream` keeps upstream's home screen |
| `OTTO_PREFETCH` | `on` | `off` turns off the boot read-ahead |
| `OTTO_COMPILE` | `on` | `off` doesn't compile apps ahead of time |
| `OTTO_GPU` | `auto` | `off` leaves Intel's GPU clock alone |
| `OTTO_MOVBE` | `auto` | `off` doesn't fix Widevine on CPUs without MOVBE |
| `SET_TZ_LOCATION` | `America/Sao_Paulo` | Time zone (upstream's option) |

## Tests and device tools

```sh
tests/run.sh
```

Runs the shell tests (HDMI detection against real device dumps, the boot
hooks with Android's commands faked, GRUB and installer patching, the
keyboard choice), the Go tests (`remote/`: the phone remote, pairing,
`otto-tv.local`, updates, read-ahead) and the TV app's logic tests.

Scripts in `tools/device/` run on a TV over ADB:

| Script | What it's for |
|---|---|
| `update.sh` | Installs an update archive without reinstalling |
| `baseline.sh` | Measures boot time, memory, app start times, smoothness |
| `trace.sh` | Records a Perfetto trace of the home screen or a whole boot |
| `input-test.sh` | Keyboard and input diagnostics |

To get ADB on a TV: turn on developer options, rooted debugging and network
debugging, then `adb connect <ip>:5555 && adb root`. Our hooks log to
`adb shell dmesg | grep otto` and `adb logcat -s otto`; the phone remote logs
to `/data/misc/otto/remote.log`.

## Known gaps

- **Only one machine so far:** the Acer in [docs/hardware](hardware/).
- **No ISO to download:** it's bigger than the 2 GB GitHub allows per release
  file. CI builds land as short-lived workflow artifacts for now.
- **Plain HTTP for the phone remote:** traffic stays on the home network, but
  someone already on that Wi-Fi could read the token.
- **Typing from the phone** goes through a US layout, so Portuguese accents
  work, but emoji and other scripts are skipped (the phone says so).
- **The laptop screen** can only go dark, so the GPU still draws for it.
  Turning the panel itself off crashed the Acer's GPU driver at boot. A
  "TV only" mode is still to be tried.
- **Navigation sounds** went missing once on the Acer and came back after a
  restart. Most likely Android failed to load them at boot while the sound
  server restarted; since 0.7.2 the home screen asks Android to load them
  again, so they should come back on their own. Not confirmed yet.
- **Wi-Fi and keyboard layout** are set in Android's settings for now.
- **App downloads** come over HTTPS from f-droid.org; checking them against
  F-Droid's signed index is on the list.
- **Streaming apps:** Widevine is L3 (lower quality), there are no Google
  Play services, and Max stays on its logo for reasons still unknown.
- **Old Intel Macs** that only boot from HFS+ won't boot (the build has to
  drop that copy of the boot files).

## Credits

Built on [LineageOS TV x86](https://github.com/LineageOS-TV-x86), which
builds on LineageOS, [Bliss OS](https://blissos.org) and Android-x86. Otto TV
only adds boot-time configuration and its own app on top of their work. The
bundled apps come from [F-Droid](https://f-droid.org) under their own
open-source licenses, and the typeface is Inter (SIL OFL).
