package hotkeys

import (
	"context"
	"encoding/binary"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type ev struct {
	code  uint16
	value int32
}

func press(c uint16) ev   { return ev{c, 1} }
func release(c uint16) ev { return ev{c, 0} }
func repeat(c uint16) ev  { return ev{c, 2} }

func feed(evs ...ev) []Action {
	var tr Tracker
	var got []Action
	for _, e := range evs {
		if a := tr.Feed(evKey, e.code, e.value); a != None {
			got = append(got, a)
		}
	}
	return got
}

func TestShortcuts(t *testing.T) {
	cases := []struct {
		name string
		evs  []ev
		want string
	}{
		{"Alt+F4", []ev{press(keyLeftAlt), press(keyF4)}, "[close app]"},
		{"AltGr+F4", []ev{press(keyRightAlt), press(keyF4)}, "[close app]"},
		{"F4 alone", []ev{press(keyF4)}, "[]"},
		{"Alt released before F4", []ev{press(keyLeftAlt), release(keyLeftAlt), press(keyF4)}, "[]"},
		{"held F4 repeats once", []ev{press(keyLeftAlt), press(keyF4), repeat(keyF4), repeat(keyF4)}, "[close app]"},
		{"Alt+F4 twice", []ev{press(keyLeftAlt), press(keyF4), release(keyF4), press(keyF4)}, "[close app close app]"},
		{"Ctrl+Alt+F4 is not Alt+F4", []ev{press(keyLeftCtrl), press(keyLeftAlt), press(keyF4)}, "[]"},
		{"Ctrl+Alt+Del", []ev{press(keyLeftCtrl), press(keyLeftAlt), press(keyDelete)}, "[power menu]"},
		{"Ctrl+Alt+keypad Del", []ev{press(keyRightCtrl), press(keyLeftAlt), press(keyKPDot)}, "[power menu]"},
		{"Ctrl+Del", []ev{press(keyLeftCtrl), press(keyDelete)}, "[]"},
		{"Del alone", []ev{press(keyDelete)}, "[]"},
		{"laptop display switch key", []ev{press(keySwitchVideoMode)}, "[laptop screen]"},
		{"F1", []ev{press(keyF1)}, "[shortcuts]"},
		{"F2", []ev{press(keyF2)}, "[quick panel]"},
		{"held F1 opens once", []ev{press(keyF1), repeat(keyF1), repeat(keyF1)}, "[shortcuts]"},
		{"Alt+F1 is not F1", []ev{press(keyLeftAlt), press(keyF1)}, "[]"},
		{"Ctrl+F2 is not F2", []ev{press(keyRightCtrl), press(keyF2)}, "[]"},
		{"laptop screen-off key", []ev{press(keyDisplayOff), release(keyDisplayOff), press(keyDisplayOff)}, "[laptop screen laptop screen]"},
	}
	for _, c := range cases {
		if got := fmt.Sprint(feed(c.evs...)); got != c.want {
			t.Errorf("%s: got %s, want %s", c.name, got, c.want)
		}
	}
}

func TestDroppedEventsReset(t *testing.T) {
	var tr Tracker
	tr.Feed(evKey, keyLeftAlt, 1)
	tr.Feed(evSyn, synDropped, 0) // the Alt release may be lost
	if a := tr.Feed(evKey, keyF4, 1); a != None {
		t.Errorf("after SYN_DROPPED, F4 gave %v", a)
	}
}

func record(typ, code uint16, value int32) []byte {
	b := make([]byte, eventSize)
	binary.LittleEndian.PutUint64(b[0:], 1700000000)
	binary.LittleEndian.PutUint16(b[16:], typ)
	binary.LittleEndian.PutUint16(b[18:], code)
	binary.LittleEndian.PutUint32(b[20:], uint32(value))
	return b
}

func TestDecode(t *testing.T) {
	raw := append(record(evKey, keyF4, 1), record(evSyn, 0, 0)...)
	raw = append(raw, 1, 2, 3) // partial record
	var got []string
	Decode(raw, func(typ, code uint16, value int32) {
		got = append(got, fmt.Sprintf("%d/%d/%d", typ, code, value))
	})
	if fmt.Sprint(got) != "[1/62/1 0/0/0]" {
		t.Errorf("got %v", got)
	}
}

// Bitmaps from the Acer's /proc/bus/input/devices (tests/fixtures).
const (
	atKeyboard = "402000000 3803078f800d001 feffffdfffefffff fffffffffffffffe"
	touchpad   = "e420 30000 0 0 0 0"
)

func TestIsKeyboard(t *testing.T) {
	for caps, want := range map[string]bool{
		atKeyboard:  true,
		touchpad:    false,
		"":          false,
		"zz":        false,
		"1 3ff0000": true, // just the Q..P row
	} {
		if got := IsKeyboard(caps); got != want {
			t.Errorf("IsKeyboard(%q) = %v, want %v", caps, got, want)
		}
	}
}

// fakeInput lays out /sys/class/input and /dev/input with regular files in
// place of devices: reads hit EOF, which the watcher treats as unplugged.
func fakeInput(t *testing.T, devs map[string][2]string, events map[string][]byte) (sys, dev string) {
	t.Helper()
	root := t.TempDir()
	sys, dev = filepath.Join(root, "sys"), filepath.Join(root, "dev")
	for ev, d := range devs {
		caps := filepath.Join(sys, ev, "device", "capabilities")
		if err := os.MkdirAll(caps, 0o755); err != nil {
			t.Fatal(err)
		}
		os.WriteFile(filepath.Join(sys, ev, "device", "name"), []byte(d[0]+"\n"), 0o644)
		os.WriteFile(filepath.Join(caps, "key"), []byte(d[1]+"\n"), 0o644)
	}
	os.MkdirAll(dev, 0o755)
	for ev, b := range events {
		os.WriteFile(filepath.Join(dev, ev), b, 0o644)
	}
	return sys, dev
}

func TestWatcherReadsKeyboardsOnly(t *testing.T) {
	altF4 := append(record(evKey, keyLeftAlt, 1), record(evKey, keyF4, 1)...)
	ctrlAltDel := append(record(evKey, keyLeftCtrl, 1), record(evKey, keyLeftAlt, 1)...)
	ctrlAltDel = append(ctrlAltDel, record(evKey, keyDelete, 1)...)
	sys, dev := fakeInput(t,
		map[string][2]string{
			"event4": {"AT Translated Set 2 keyboard", atKeyboard},
			"event6": {"ETPS/2 Elantech Touchpad", touchpad},
			"event9": {"Otto Remote Keyboard", atKeyboard},
		},
		map[string][]byte{"event4": altF4, "event6": ctrlAltDel, "event9": ctrlAltDel})

	var mu sync.Mutex
	var got []Action
	done := make(chan struct{}, 8)
	w := &Watcher{
		SysDir: sys, DevDir: dev,
		Skip: func(name string) bool { return strings.HasPrefix(name, "Otto Remote") },
		Handle: func(a Action) {
			mu.Lock()
			got = append(got, a)
			mu.Unlock()
			done <- struct{}{}
		},
	}
	w.scan(context.Background())
	w.wg.Wait()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("no action reported")
	}
	time.Sleep(50 * time.Millisecond) // anything else would show up by now
	mu.Lock()
	defer mu.Unlock()
	if fmt.Sprint(got) != "[close app]" {
		t.Errorf("got %v, want only the laptop keyboard's Alt+F4", got)
	}
}

func TestWatcherStopsWithContext(t *testing.T) {
	sys, dev := fakeInput(t, nil, nil)
	ctx, cancel := context.WithCancel(context.Background())
	finished := make(chan struct{})
	go func() {
		(&Watcher{SysDir: sys, DevDir: dev, Every: 10 * time.Millisecond}).Run(ctx)
		close(finished)
	}()
	cancel()
	select {
	case <-finished:
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not return after cancel")
	}
}

// Scans only look at nodes they haven't seen, and look again once one is gone.
func TestWatcherLooksAtEachDeviceOnce(t *testing.T) {
	sys, dev := fakeInput(t,
		map[string][2]string{"event6": {"ETPS/2 Elantech Touchpad", touchpad}},
		nil)
	w := &Watcher{SysDir: sys, DevDir: dev}
	w.scan(context.Background())
	// Turn the touchpad into a keyboard: a second look would open it.
	os.WriteFile(filepath.Join(sys, "event6", "device", "capabilities", "key"), []byte(atKeyboard), 0o644)
	w.scan(context.Background())
	w.wg.Wait()
	if len(w.open) != 0 {
		t.Errorf("known node examined again: open = %v", w.open)
	}
	// Once the node goes away and comes back, it is looked at again.
	os.RemoveAll(filepath.Join(sys, "event6"))
	w.scan(context.Background())
	if w.seen["event6"] {
		t.Error("vanished node still marked as seen")
	}
}

// The Acer's "Video Bus" device: brightness keys, KEY_SWITCHVIDEOMODE and
// the video keys up to KEY_DISPLAY_OFF (from its getevent listing).
const videoBus = "3e000b00000000 0 0 0"

func TestHasKey(t *testing.T) {
	for _, c := range []struct {
		caps string
		code int
		want bool
	}{
		{videoBus, keySwitchVideoMode, true},
		{videoBus, keyDisplayOff, true},
		{videoBus, 226, false},
		{atKeyboard, 16, true},
		{atKeyboard, keyDisplayOff, false},
		{"", 1, false},
		{touchpad, 0x110, true}, // BTN_LEFT
	} {
		if got := HasKey(c.caps, c.code); got != c.want {
			t.Errorf("HasKey(%q, %d) = %v, want %v", c.caps, c.code, got, c.want)
		}
	}
	if !wanted(videoBus) || wanted(touchpad) {
		t.Error("wanted should take the display keys device and skip the touchpad")
	}
}
