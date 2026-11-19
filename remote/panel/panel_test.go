package panel

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// fakeSys lays out /sys/class/backlight and /sys/class/drm like the Acer's.
func fakeSys(t *testing.T, hdmi string) (bl, drm, state string) {
	t.Helper()
	root := t.TempDir()
	bl, drm = filepath.Join(root, "backlight"), filepath.Join(root, "drm")
	dev := filepath.Join(bl, "intel_backlight")
	os.MkdirAll(dev, 0o755)
	put(t, filepath.Join(dev, "brightness"), "700")
	put(t, filepath.Join(dev, "max_brightness"), "976")
	put(t, filepath.Join(dev, "bl_power"), "0")
	for name, status := range map[string]string{"card0-LVDS-1": "connected", "card0-HDMI-A-1": hdmi, "card0-VGA-1": "disconnected"} {
		os.MkdirAll(filepath.Join(drm, name), 0o755)
		put(t, filepath.Join(drm, name, "status"), status)
	}
	return bl, drm, filepath.Join(root, "panel")
}

func put(t *testing.T, path, value string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(value+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
}

func get(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return strings.TrimSpace(string(b))
}

func screen(t *testing.T, bl string) string {
	dev := filepath.Join(bl, "intel_backlight")
	return get(t, filepath.Join(dev, "brightness")) + "/" + get(t, filepath.Join(dev, "bl_power"))
}

func TestToggleWithTV(t *testing.T) {
	bl, drm, state := fakeSys(t, "connected")
	p := New(bl, drm, state)
	if !p.Available() {
		t.Fatal("laptop backlight not found")
	}
	if off, err := p.Toggle(); err != nil || !off {
		t.Fatalf("Toggle = %v, %v", off, err)
	}
	if got := screen(t, bl); got != "0/4" {
		t.Errorf("dark screen: brightness/bl_power = %s, want 0/4", got)
	}
	if got := get(t, state); got != "off" {
		t.Errorf("saved choice %q", got)
	}
	p.Toggle()
	if got := screen(t, bl); got != "700/0" {
		t.Errorf("lit again: %s, want the brightness from before (700/0)", got)
	}
	if got := get(t, state); got != "on" {
		t.Errorf("saved choice %q", got)
	}
}

func TestNeverDarkWithoutTV(t *testing.T) {
	bl, drm, state := fakeSys(t, "disconnected")
	p := New(bl, drm, state)
	p.Toggle()
	if got := screen(t, bl); got != "700/0" {
		t.Errorf("no TV connected, screen went %s", got)
	}
	if !p.Off() {
		t.Error("the choice itself should still be saved as off")
	}
	// The TV shows up later: now it goes dark.
	put(t, filepath.Join(drm, "card0-HDMI-A-1", "status"), "connected")
	p.Apply()
	if got := screen(t, bl); got != "0/4" {
		t.Errorf("TV connected, screen %s, want 0/4", got)
	}
	// And unplugged again: lit, so there is always a picture somewhere.
	put(t, filepath.Join(drm, "card0-HDMI-A-1", "status"), "disconnected")
	p.Apply()
	if got := screen(t, bl); got != "700/0" {
		t.Errorf("TV gone, screen %s, want 700/0", got)
	}
}

func TestChoiceSurvivesRestart(t *testing.T) {
	bl, drm, state := fakeSys(t, "connected")
	New(bl, drm, state).Toggle()
	put(t, filepath.Join(bl, "intel_backlight", "brightness"), "650") // Android at boot
	put(t, filepath.Join(bl, "intel_backlight", "bl_power"), "0")
	p := New(bl, drm, state)
	p.Apply()
	if got := screen(t, bl); got != "0/4" {
		t.Errorf("after a restart the screen is %s, want dark", got)
	}
}

// v0.3.9: after a reboot the TV waits for "Mirror to display?", asked on the
// laptop screen. That screen must stay lit until the TV shows the picture.
func TestLitUntilTheTVShowsThePicture(t *testing.T) {
	bl, drm, state := fakeSys(t, "connected")
	New(bl, drm, state).Toggle()                                      // chosen before the reboot
	put(t, filepath.Join(bl, "intel_backlight", "brightness"), "650") // Android at boot
	put(t, filepath.Join(bl, "intel_backlight", "bl_power"), "0")
	shown := false
	p := New(bl, drm, state)
	p.Shown = func() bool { return shown }
	p.Apply()
	if got := screen(t, bl); got != "650/0" {
		t.Errorf("TV waiting for the question, laptop screen %s, want lit", got)
	}
	shown = true // answered, or skipped by persist.sys.display.enable_on_connect.external
	p.Apply()
	if got := screen(t, bl); got != "0/4" {
		t.Errorf("TV showing the picture, laptop screen %s, want dark", got)
	}
	shown = false // the TV went back to asking (replugged with the question on)
	p.Apply()
	if got := screen(t, bl); got != "650/0" {
		t.Errorf("TV asking again, laptop screen %s, want lit at Android's brightness", got)
	}
}

func TestShownNotAskedWithoutReason(t *testing.T) {
	bl, drm, state := fakeSys(t, "disconnected")
	asked := 0
	p := New(bl, drm, state)
	p.Shown = func() bool { asked++; return true }
	p.Apply()  // screen on: nothing to ask
	p.Toggle() // off, but no TV: nothing to ask either
	if asked != 0 {
		t.Errorf("asked Android %d times with no TV connected", asked)
	}
}

func TestExternalShown(t *testing.T) {
	for out, want := range map[string]bool{
		"2\n":                             true,
		"":                                false,
		"\n":                              false,
		"1\n3\n":                          true,
		"Unknown command: get-displays\n": false,
	} {
		if got := ExternalShown(out); got != want {
			t.Errorf("ExternalShown(%q) = %v, want %v", out, got, want)
		}
	}
}

func TestAndroidRelightsAndWeDimAgain(t *testing.T) {
	bl, drm, state := fakeSys(t, "connected")
	p := New(bl, drm, state)
	p.Toggle()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go p.Watch(ctx, time.Hour) // only inotify can react in time

	// Android wakes the screen and writes its brightness. Written again
	// until noticed, since the watch may not be set up yet.
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		put(t, filepath.Join(bl, "intel_backlight", "brightness"), "512")
		time.Sleep(100 * time.Millisecond)
		if screen(t, bl) == "0/4" {
			break
		}
	}
	if got := screen(t, bl); got != "0/4" {
		t.Fatalf("Android lit the screen and it stayed %s", got)
	}
	p.Toggle()
	if got := screen(t, bl); got != "512/0" {
		t.Errorf("lit again at %s, want what Android asked for last (512/0)", got)
	}
}

func TestExternalConnected(t *testing.T) {
	_, drm, _ := fakeSys(t, "disconnected")
	if ExternalConnected(drm) {
		t.Error("the laptop's own panel counted as a TV")
	}
	put(t, filepath.Join(drm, "card0-HDMI-A-1", "status"), "connected")
	if !ExternalConnected(drm) {
		t.Error("HDMI TV not seen")
	}
}

func TestDesktopHasNothingToDim(t *testing.T) {
	root := t.TempDir()
	p := New(filepath.Join(root, "none"), filepath.Join(root, "drm"), filepath.Join(root, "panel"))
	if p.Available() {
		t.Error("no backlight, but Available")
	}
	p.Apply() // must not panic
}
