// Package panel turns a laptop's own screen dark (its backlight) while a TV
// is connected, and keeps it that way across reboots.
//
// Only the backlight: switching the panel itself off while the system runs
// crashed the GPU driver on the reference Acer (v0.3.7). The GPU still draws
// the mirrored picture, which costs a few tens of MB of buffers, but the
// screen is dark, which is what a laptop on a TV shelf needs.
//
// Android writes the backlight too (every time the screen wakes up), so the
// choice is re-applied whenever that happens.
//
// A plugged-in TV isn't enough: Android 14 keeps it dark until "Mirror to
// display?" is answered, on the laptop screen. With that screen dark nobody
// could answer it (v0.3.9 after a reboot), so it stays lit until Android
// actually shows something on the TV.
package panel

import (
	"context"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Panel is the laptop screen's backlight.
type Panel struct {
	Backlight string // /sys/class/backlight
	DRM       string // /sys/class/drm
	State     string // file that keeps the choice
	// Shown tells whether Android shows anything on an external display.
	// Nil means it does whenever one is connected.
	Shown func() bool

	mu      sync.Mutex
	off     bool              // what the user chose
	dimmed  bool              // whether we turned it dark
	waiting bool              // TV connected, but Android not showing on it yet
	saved   map[string]string // brightness to restore, per backlight device
}

// New loads the saved choice.
func New(backlight, drm, state string) *Panel {
	p := &Panel{Backlight: backlight, DRM: drm, State: state, saved: map[string]string{}}
	if b, err := os.ReadFile(state); err == nil {
		p.off = strings.TrimSpace(string(b)) == "off"
	}
	return p
}

// Available tells whether there is a laptop screen to turn dark.
func (p *Panel) Available() bool {
	return len(p.devices()) > 0
}

// Off is the user's choice (the screen may still be lit when no TV is
// connected: see Apply).
func (p *Panel) Off() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.off
}

// Toggle flips the choice, saves it and applies it. It returns the new one.
func (p *Panel) Toggle() (bool, error) {
	p.mu.Lock()
	p.off = !p.off
	off := p.off
	p.mu.Unlock()
	err := p.save(off)
	p.Apply()
	return off, err
}

func (p *Panel) save(off bool) error {
	v := "on\n"
	if off {
		v = "off\n"
	}
	tmp := p.State + ".tmp"
	if err := os.WriteFile(tmp, []byte(v), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, p.State)
}

// Apply makes the screen match the choice. The screen only goes dark while
// a TV (any external display) is connected and showing the picture: the
// laptop must never be left as the only screen, dark.
func (p *Panel) Apply() {
	p.mu.Lock()
	defer p.mu.Unlock()
	tv := p.off && ExternalConnected(p.DRM)
	dim := tv && (p.Shown == nil || p.Shown())
	waiting := tv && !dim
	if waiting && !p.waiting {
		log.Printf("panel: the TV isn't showing the picture yet, so the laptop screen stays lit")
	}
	p.waiting = waiting
	for _, dev := range p.devices() {
		if dim {
			cur := read(filepath.Join(dev, "brightness"))
			if cur != "" && cur != "0" {
				// Ours is 0, so anything else was Android setting the
				// brightness it wants: that is what to restore later.
				p.saved[dev] = cur
				write(filepath.Join(dev, "brightness"), "0")
			}
			setPower(dev, "4") // FB_BLANK_POWERDOWN, where the driver has it
		} else if p.dimmed {
			setPower(dev, "0")
			value := p.saved[dev]
			if value == "" {
				value = read(filepath.Join(dev, "max_brightness"))
			}
			if value != "" {
				write(filepath.Join(dev, "brightness"), value)
			}
		}
	}
	if dim != p.dimmed {
		if dim {
			log.Printf("panel: laptop screen dark (a TV is connected)")
		} else {
			log.Printf("panel: laptop screen lit")
			p.saved = map[string]string{}
		}
	}
	p.dimmed = dim
}

// Watch keeps the choice applied until ctx ends: right away when Android
// writes the backlight, and every few seconds for TVs plugged in or out.
func (p *Panel) Watch(ctx context.Context, every time.Duration) {
	p.Apply()
	changed := make(chan struct{}, 1)
	if f, err := p.inotify(); err == nil {
		// Closing the file ends the read below: the runtime poller wakes it.
		context.AfterFunc(ctx, func() { f.Close() })
		go func() {
			buf := make([]byte, 4096)
			for {
				if _, err := f.Read(buf); err != nil {
					return
				}
				select {
				case changed <- struct{}{}:
				default:
				}
			}
		}()
	}
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-changed:
		case <-t.C:
		}
		p.Apply()
	}
}

// inotify watches every backlight's brightness file for writes. Non-blocking,
// so the returned file goes through Go's poller and can be closed while read.
func (p *Panel) inotify() (*os.File, error) {
	fd, err := syscall.InotifyInit1(syscall.IN_CLOEXEC | syscall.IN_NONBLOCK)
	if err != nil {
		return nil, err
	}
	watched := 0
	for _, dev := range p.devices() {
		if _, err := syscall.InotifyAddWatch(fd, filepath.Join(dev, "brightness"), syscall.IN_MODIFY); err == nil {
			watched++
		}
	}
	if watched == 0 {
		syscall.Close(fd)
		return nil, os.ErrNotExist
	}
	return os.NewFile(uintptr(fd), "inotify"), nil
}

func (p *Panel) devices() []string {
	devs, _ := filepath.Glob(filepath.Join(p.Backlight, "*"))
	var out []string
	for _, d := range devs {
		if _, err := os.Stat(filepath.Join(d, "brightness")); err == nil {
			out = append(out, d)
		}
	}
	return out
}

// ExternalConnected tells whether a TV or monitor is plugged in, from the
// DRM connector statuses: anything connected that isn't a built-in panel.
func ExternalConnected(drm string) bool {
	conns, _ := filepath.Glob(filepath.Join(drm, "card*-*"))
	for _, c := range conns {
		name := filepath.Base(c)
		if internal(name) {
			continue
		}
		if read(filepath.Join(c, "status")) == "connected" {
			return true
		}
	}
	return false
}

func internal(connector string) bool {
	for _, kind := range []string{"-LVDS-", "-eDP-", "-DSI-", "-Writeback-", "-Virtual-"} {
		if strings.Contains(connector, kind) {
			return true
		}
	}
	return false
}

func setPower(dev, value string) {
	path := filepath.Join(dev, "bl_power")
	if cur := read(path); cur != "" && cur != value {
		write(path, value)
	}
}

func read(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func write(path, value string) {
	if err := os.WriteFile(path, []byte(value), 0o644); err != nil {
		log.Printf("panel: %v", err)
	}
}

// ShownCommand is how Android lists the external displays it has turned on.
// A TV still waiting for "Mirror to display?" is connected but not listed.
var ShownCommand = []string{"cmd", "display", "get-displays", "--ids-only", "--type", "external"}

// ExternalShown reads ShownCommand's output: one display id per line.
func ExternalShown(out string) bool {
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if _, err := strconv.Atoi(line); err == nil {
			return true
		}
	}
	return false
}
