# Reference machine: Acer laptop (HM77, Ivy Bridge)

The first machine the project was tested on, driving a 32" HD TV over HDMI.

| Part      | Details                                            |
|-----------|----------------------------------------------------|
| Board     | Acer EA50_HC_CR, Intel HM77 chipset                |
| CPU       | Intel Core i5-3230M (Ivy Bridge, has SSE4.2)       |
| GPU       | Intel HD Graphics 4000                             |
| RAM       | 6 GB DDR3                                          |
| Audio     | HDA Intel PCH: ALC269VB analog (D0), HDMI 0 (D3)  |
| Wi-Fi     | 2.4 GHz 802.11n                                    |
| TV        | Samsung UN32T4300 (32", 1366x768, HDMI)            |

Upstream image: LineageOS TV x86 21.0 (Android 14), `x86_64_tv_go`, built 2026-03-31.

## Results (live mode, from USB)

| Area                 | Result | Notes |
|----------------------|--------|-------|
| Boot from USB        | ✅ | |
| Graphics, HDMI out   | ✅ | Mirrored at native resolution on both screens |
| Network              | ✅ | ~4 MB/s (~32 Mbps) on 2.4 GHz Wi-Fi |
| Video playback       | ✅ | A movie in Stremio played smoothly |
| Audio over HDMI      | ⚠️ → ✅ | Silent by default, fixed by the TV Box audio hook (below) |
| First-boot setup     | ⚠️ | Hangs on "Searching for accessories" (Bluetooth remote); Esc skips it. TV Box disables the wizard. |
| Alt+F1 console       | ❌ | Stays blank even with `androidboot.enable_console=1`; use ADB instead |

## The HDMI audio investigation

Symptom: sound always came out of the laptop speakers, never the TV.

1. `ls /dev/snd` showed one card with two outputs: `pcmC0D0p` and `pcmC0D3p`.
   `hwC0D3` is the HDMI codec, so D3 is HDMI.
2. `/proc/asound/card0/eld#*` showed `monitor_present 1`, `eld_valid 1`,
   `monitor_name SAMSUNG`: the GPU sees the TV as an audio sink.
   `alsa_amixer -c 0 contents` agrees: `'HDMI/DP,pcm=3 Jack'` is `on`.
3. `setprop hal.audio.out pcmC0D3p` + `pkill audioserver` moved the sound
   away from the speakers, but nothing reached the TV.
4. logcat showed the x86 audio HAL finding the device (`found audio out at
   /dev/snd/pcmC0D3p ... id: HDMI 0`), then `ignore pcmC0D3p` and
   `openOutputStream ... status -19` (ENODEV).
5. The HAL source (`hal/audio_hw.c`, `select_card()`) explains it: HDMI devices
   go into a separate cache slot that is only read when
   `hal.audio.primary.hdmi` is true. Without it the HAL looks in the normal
   slot, finds it empty and gives up.
6. Fix: `setprop hal.audio.primary.hdmi 1` + `setprop hal.audio.out.hdmi pcmC0D3p`.
   Sound on the TV.

The audio hook in `iso/tvbox/init-hooks.sh` does this automatically on every
boot by reading the HDMI jack controls, so no device number is hard-coded.
The raw dumps are kept as test fixtures in `tests/fixtures/`.
