// Package hotkeys gives PC keyboards the TV shortcuts a remote has buttons
// for. It reads the physical keyboards passively (no grab: Android still
// gets every key) and watches for a couple of combos PC users already know:
//
//	Alt+F4         close the app on screen (on the home screen: power menu)
//	Ctrl+Alt+Del   power menu (turn off, restart)
//	F1, F2         the launcher's shortcuts and quick panel, over any app
//	display keys   laptop screen dark/lit: the Fn keys with a screen on
//	               them (KEY_SWITCHVIDEOMODE, KEY_DISPLAY_OFF), when the
//	               laptop sends one
//
// Esc (Back) and the Windows key (Home) need no help: the key layouts map
// them, see iso/scripts/9-otto.
package hotkeys

import (
	"context"
	"encoding/binary"
	"io"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Action is what a shortcut asks for.
type Action int

const (
	None Action = iota
	CloseApp
	PowerMenu
	ToggleLaptopScreen
	Shortcuts
	QuickPanel
	// TogglePanel opens the quick panel or closes it, over anything: the
	// phone's Panel button (no key reaches the launcher then).
	TogglePanel
)

func (a Action) String() string {
	switch a {
	case CloseApp:
		return "close app"
	case PowerMenu:
		return "power menu"
	case ToggleLaptopScreen:
		return "laptop screen"
	case Shortcuts:
		return "shortcuts"
	case QuickPanel:
		return "quick panel"
	case TogglePanel:
		return "quick panel (phone)"
	}
	return "none"
}

// Linux key codes, <linux/input-event-codes.h>.
const (
	keyLeftCtrl  = 29
	keyLeftAlt   = 56
	keyF1        = 59
	keyF2        = 60
	keyF4        = 62
	keyKPDot     = 83 // keypad Del with Num Lock off
	keyRightCtrl = 97
	keyRightAlt  = 100
	keyDelete    = 111

	keySwitchVideoMode = 227 // laptops: Fn key that switches screens
	keyDisplayOff      = 245 // laptops: Fn key that turns the screen off

	evSyn      = 0x00
	evKey      = 0x01
	synDropped = 3
)

// Tracker follows one keyboard's held keys and spots the shortcuts.
type Tracker struct {
	held map[uint16]bool
}

// Feed takes one input event and returns the shortcut it completes, if any.
func (t *Tracker) Feed(typ, code uint16, value int32) Action {
	if t.held == nil {
		t.held = map[uint16]bool{}
	}
	if typ == evSyn && code == synDropped {
		clear(t.held) // the kernel lost events: start over
		return None
	}
	if typ != evKey {
		return None
	}
	switch value {
	case 0:
		delete(t.held, code)
		return None
	case 1:
		t.held[code] = true
	default:
		return None // auto-repeat: one action per press
	}
	ctrl := t.held[keyLeftCtrl] || t.held[keyRightCtrl]
	alt := t.held[keyLeftAlt] || t.held[keyRightAlt]
	switch {
	case code == keyF4 && alt && !ctrl:
		return CloseApp
	case (code == keyDelete || code == keyKPDot) && ctrl && alt:
		return PowerMenu
	case code == keySwitchVideoMode || code == keyDisplayOff:
		return ToggleLaptopScreen
	case code == keyF1 && !alt && !ctrl:
		return Shortcuts
	case code == keyF2 && !alt && !ctrl:
		return QuickPanel
	}
	return None
}

// eventSize is struct input_event on 64-bit kernels: two int64 for the
// time, then type, code and value.
const eventSize = 24

// Decode splits raw input_event records. A trailing partial record is
// ignored (reads from evdev always return whole records).
func Decode(b []byte, each func(typ, code uint16, value int32)) {
	for len(b) >= eventSize {
		each(binary.LittleEndian.Uint16(b[16:]), binary.LittleEndian.Uint16(b[18:]),
			int32(binary.LittleEndian.Uint32(b[20:])))
		b = b[eventSize:]
	}
}

// HasKey tells whether a key capability bitmap (see IsKeyboard) has a key.
// The words are longs, most significant first.
func HasKey(keyCaps string, code int) bool {
	words := strings.Fields(keyCaps)
	i := len(words) - 1 - code/64
	if i < 0 {
		return false
	}
	w := words[i]
	if len(w) > 16 {
		w = w[len(w)-16:]
	}
	v, err := strconv.ParseUint(w, 16, 64)
	return err == nil && v&(1<<(code%64)) != 0
}

// wanted tells which devices to read: keyboards, and the laptop's display
// hotkeys, which often come from a device of their own ("Video Bus").
func wanted(keyCaps string) bool {
	return IsKeyboard(keyCaps) || HasKey(keyCaps, keySwitchVideoMode) || HasKey(keyCaps, keyDisplayOff)
}

// IsKeyboard tells from a key capability bitmap (sysfs
// capabilities/key, or the B: KEY= line of /proc/bus/input/devices) whether a
// device is a real keyboard: it has the Q..P row. Same rule init-hooks.sh
// uses to pick the on-screen keyboard.
func IsKeyboard(keyCaps string) bool {
	words := strings.Fields(keyCaps)
	if len(words) == 0 {
		return false
	}
	low := words[len(words)-1]
	if len(low) > 16 {
		low = low[len(low)-16:]
	}
	v, err := strconv.ParseUint(low, 16, 64)
	if err != nil {
		return false
	}
	const qwertyRow = 0x3ff << 16 // KEY_Q (16) .. KEY_P (25)
	return v&qwertyRow == qwertyRow
}

// Watcher finds keyboards, plugged in now or later, and reports shortcuts.
type Watcher struct {
	SysDir string                 // /sys/class/input
	DevDir string                 // /dev/input
	Skip   func(name string) bool // devices to ignore (our own virtual ones)
	Handle func(Action)
	Every  time.Duration // how often to look for new keyboards

	mu   sync.Mutex
	open map[string]bool // event nodes being read
	seen map[string]bool // event nodes already looked at (keyboards or not)
	wg   sync.WaitGroup
}

// Run watches until ctx ends.
func (w *Watcher) Run(ctx context.Context) {
	every := w.Every
	if every == 0 {
		every = 3 * time.Second
	}
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		w.scan(ctx)
		select {
		case <-ctx.Done():
			w.wg.Wait()
			return
		case <-t.C:
		}
	}
}

// scan opens keyboards not being read yet. Only nodes it hasn't looked at
// before are examined: reading sysfs every few seconds for nothing fills the
// kernel log with SELinux notices on Android.
func (w *Watcher) scan(ctx context.Context) {
	nodes, _ := filepath.Glob(filepath.Join(w.SysDir, "event*"))
	present := map[string]bool{}
	for _, node := range nodes {
		present[filepath.Base(node)] = true
	}
	w.mu.Lock()
	if w.open == nil {
		w.open, w.seen = map[string]bool{}, map[string]bool{}
	}
	for ev := range w.seen {
		if !present[ev] {
			delete(w.seen, ev) // unplugged: a new device may get its name
		}
	}
	w.mu.Unlock()

	for _, node := range nodes {
		ev := filepath.Base(node)
		w.mu.Lock()
		known := w.seen[ev]
		w.seen[ev] = true
		w.mu.Unlock()
		if known {
			continue
		}
		name := readTrim(filepath.Join(node, "device", "name"))
		if (w.Skip != nil && w.Skip(name)) ||
			!wanted(readTrim(filepath.Join(node, "device", "capabilities", "key"))) {
			continue
		}
		w.mu.Lock()
		w.open[ev] = true
		w.mu.Unlock()
		f, err := os.Open(filepath.Join(w.DevDir, ev))
		if err != nil {
			w.forget(ev)
			continue
		}
		log.Printf("hotkeys: reading %q (%s)", name, ev)
		w.wg.Add(1)
		go w.read(ctx, ev, f)
	}
}

func (w *Watcher) read(ctx context.Context, ev string, f *os.File) {
	defer w.wg.Done()
	defer w.forget(ev)
	stop := context.AfterFunc(ctx, func() { f.Close() })
	defer stop()
	defer f.Close()

	var tr Tracker
	buf := make([]byte, eventSize*64)
	for {
		n, err := io.ReadAtLeast(f, buf, eventSize)
		Decode(buf[:n-n%eventSize], func(typ, code uint16, value int32) {
			if a := tr.Feed(typ, code, value); a != None && w.Handle != nil {
				go w.Handle(a)
			}
		})
		if err != nil {
			return // unplugged, or shutting down
		}
	}
}

// forget drops a keyboard that stopped reading, so the next scan looks at
// its node again.
func (w *Watcher) forget(ev string) {
	w.mu.Lock()
	delete(w.open, ev)
	delete(w.seen, ev)
	w.mu.Unlock()
}

func readTrim(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}
