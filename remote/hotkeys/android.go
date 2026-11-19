package hotkeys

import (
	"context"
	"log"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Keys presses keys on a virtual keyboard (the phone remote's).
type Keys interface {
	Press(code uint16) error
	Release(code uint16) error
}

const keyPower = 116

// Laptop is the laptop screen's backlight (the panel package).
type Laptop interface {
	Toggle() (bool, error)
}

// Android carries out shortcuts with Android's own tools.
type Android struct {
	Keys   Keys   // optional: holds Power for the power menu
	Laptop Laptop // optional: the laptop screen
	// App is Otto TV's app (app.ottotv.connect): its quick panel is the
	// power menu, and F1 and F2 open its shortcuts and quick panel. Empty:
	// Android's own power menu, and no F1 or F2.
	App string
	// Notify shows a notice on the TV ("closed", with the app); may be nil.
	Notify    func(kind, app string)
	LongPress time.Duration // how long Power is held; 0 means 1.2s
	// Run executes a command and returns its output; nil runs it for real.
	Run func(name string, args ...string) ([]byte, error)

	mu   sync.Mutex // one action at a time
	home string     // home screen package, looked up once
}

// Do carries out one action. Overlapping requests (a key mashed twice) are
// dropped rather than queued.
func (a *Android) Do(act Action) {
	if !a.mu.TryLock() {
		return
	}
	defer a.mu.Unlock()
	switch act {
	case CloseApp:
		a.closeApp()
	case PowerMenu:
		a.powerMenu()
	case ToggleLaptopScreen:
		if a.Laptop != nil {
			off, err := a.Laptop.Toggle()
			if err != nil {
				log.Printf("hotkeys: laptop screen: %v", err)
			}
			if a.Notify != nil {
				state := "screen_on"
				if off {
					state = "screen_off"
				}
				a.Notify(state, "")
			}
		}
	case Shortcuts:
		a.openOver(".home.ShortcutsActivity")
	case QuickPanel:
		a.openOver(".home.PanelActivity")
	case TogglePanel:
		if a.App != "" {
			if _, err := a.run("am", "start", "-n", a.App+"/.home.PanelActivity", "--ez", "toggle", "true"); err != nil {
				log.Printf("hotkeys: quick panel: %v", err)
			}
		}
	}
}

// openOver opens one of the launcher's screens over the app on screen. Not
// over the launcher's own screens: they take F1 and F2 themselves (and
// toggle on them).
func (a *Android) openOver(activity string) {
	if a.App == "" {
		return
	}
	if out, err := a.run("dumpsys", "activity", "activities"); err == nil && TopPackage(string(out)) == a.App {
		return
	}
	if _, err := a.run("am", "start", "-n", a.App+"/"+activity); err != nil {
		log.Printf("hotkeys: open %s: %v", activity, err)
	}
}

// Never closed: closing these takes the whole interface down with them.
var keepRunning = map[string]bool{"android": true, "com.android.systemui": true}

func (a *Android) closeApp() {
	out, err := a.run("dumpsys", "activity", "activities")
	if err != nil {
		log.Printf("hotkeys: close app: %v", err)
		return
	}
	pkg := TopPackage(string(out))
	switch {
	case pkg == "":
		log.Printf("hotkeys: close app: no app on screen")
	case pkg == a.homePackage():
		// Like Alt+F4 on a Windows desktop.
		a.powerMenu()
	case keepRunning[pkg]:
		log.Printf("hotkeys: close app: %s stays open", pkg)
	default:
		if _, err := a.run("am", "force-stop", pkg); err != nil {
			log.Printf("hotkeys: close %s: %v", pkg, err)
			return
		}
		log.Printf("hotkeys: closed %s", pkg)
		if a.Notify != nil {
			a.Notify("closed", pkg)
		}
	}
}

// powerMenu opens the launcher's quick panel on Power. Without the launcher's
// app it long-presses Power, which opens Android's power menu: through our
// virtual keyboard when there is one; `input` takes a second to start.
func (a *Android) powerMenu() {
	if a.App != "" {
		_, err := a.run("am", "start", "-n", a.App+"/.home.PanelActivity", "--es", "focus", "power")
		if err == nil {
			log.Printf("hotkeys: quick panel, power")
			return
		}
		log.Printf("hotkeys: quick panel: %v", err)
	}
	hold := a.LongPress
	if hold == 0 {
		hold = 1200 * time.Millisecond
	}
	if a.Keys != nil && a.Keys.Press(keyPower) == nil {
		time.Sleep(hold)
		a.Keys.Release(keyPower)
	} else if _, err := a.run("input", "keyevent", "--longpress", "KEYCODE_POWER"); err != nil {
		log.Printf("hotkeys: power menu: %v", err)
		return
	}
	log.Printf("hotkeys: power menu")
}

func (a *Android) homePackage() string {
	if a.home == "" {
		out, err := a.run("cmd", "package", "resolve-activity", "--brief",
			"-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
		if err == nil {
			a.home = HomePackage(string(out))
		}
	}
	return a.home
}

func (a *Android) run(name string, args ...string) ([]byte, error) {
	if a.Run != nil {
		return a.Run(name, args...)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return exec.CommandContext(ctx, name, args...).Output()
}

var resumed = regexp.MustCompile(`(?:topResumedActivity|mResumedActivity|ResumedActivity)[=:] *ActivityRecord\{[0-9a-f]+ u\d+ ([A-Za-z0-9_.]+)/`)

// TopPackage finds the app on screen in `dumpsys activity activities`.
func TopPackage(dump string) string {
	if m := resumed.FindStringSubmatch(dump); m != nil {
		return m[1]
	}
	return ""
}

// HomePackage reads `cmd package resolve-activity --brief` output, whose
// last line is package/activity.
func HomePackage(out string) string {
	lines := strings.Split(strings.TrimSpace(out), "\n")
	last := strings.TrimSpace(lines[len(lines)-1])
	if pkg, _, ok := strings.Cut(last, "/"); ok {
		return pkg
	}
	return ""
}
