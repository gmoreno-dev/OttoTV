package hotkeys

import (
	"errors"
	"fmt"
	"strings"
	"testing"
)

const activitiesDump = `ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):
  * Task{8f2c1a #42 type=standard A=10086:com.stremio.one}
    topResumedActivity=ActivityRecord{6d1e2f0 u0 com.stremio.one/com.stremio.tv.MainActivity t42}
  * Task{11aa22 #3 type=home}
    mLastPausedActivity: ActivityRecord{77bb88 u0 com.android.tv.launcher/.MainActivity t3}
`

const homeResolve = `priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
com.android.tv.launcher/.MainActivity
`

func TestTopPackage(t *testing.T) {
	if got := TopPackage(activitiesDump); got != "com.stremio.one" {
		t.Errorf("got %q", got)
	}
	older := "  mResumedActivity: ActivityRecord{1a2b u0 org.fdroid.fdroid/.MainActivity t9}"
	if got := TopPackage(older); got != "org.fdroid.fdroid" {
		t.Errorf("older format: got %q", got)
	}
	if got := TopPackage("nothing here"); got != "" {
		t.Errorf("got %q for no activity", got)
	}
}

func TestHomePackage(t *testing.T) {
	if got := HomePackage(homeResolve); got != "com.android.tv.launcher" {
		t.Errorf("got %q", got)
	}
	if got := HomePackage("No activity found"); got != "" {
		t.Errorf("got %q", got)
	}
}

type fakeKeys struct{ log *[]string }

func (k fakeKeys) Press(c uint16) error {
	*k.log = append(*k.log, fmt.Sprintf("press %d", c))
	return nil
}
func (k fakeKeys) Release(c uint16) error {
	*k.log = append(*k.log, fmt.Sprintf("release %d", c))
	return nil
}

// fakeAndroid answers dumpsys and resolve-activity, and records the rest.
func fakeAndroid(top string, withKeys bool) (*Android, *[]string) {
	var calls []string
	a := &Android{
		LongPress: 1,
		Run: func(name string, args ...string) ([]byte, error) {
			cmd := name + " " + strings.Join(args, " ")
			switch {
			case strings.HasPrefix(cmd, "dumpsys activity"):
				return []byte("    topResumedActivity=ActivityRecord{1 u0 " + top + "/.Main t1}"), nil
			case strings.HasPrefix(cmd, "cmd package resolve-activity"):
				return []byte(homeResolve), nil
			}
			calls = append(calls, cmd)
			return nil, nil
		},
	}
	if withKeys {
		a.Keys = fakeKeys{&calls}
	}
	return a, &calls
}

func TestCloseApp(t *testing.T) {
	a, calls := fakeAndroid("com.stremio.one", true)
	a.Do(CloseApp)
	if fmt.Sprint(*calls) != "[am force-stop com.stremio.one]" {
		t.Errorf("got %v", *calls)
	}
}

func TestCloseAppOnHomeOpensPowerMenu(t *testing.T) {
	a, calls := fakeAndroid("com.android.tv.launcher", true)
	a.Do(CloseApp)
	if fmt.Sprint(*calls) != "[press 116 release 116]" {
		t.Errorf("got %v", *calls)
	}
}

func TestCloseAppKeepsSystemUI(t *testing.T) {
	a, calls := fakeAndroid("com.android.systemui", true)
	a.Do(CloseApp)
	if len(*calls) != 0 {
		t.Errorf("got %v", *calls)
	}
}

func TestPowerMenuWithoutVirtualKeyboard(t *testing.T) {
	a, calls := fakeAndroid("com.stremio.one", false)
	a.Do(PowerMenu)
	if fmt.Sprint(*calls) != "[input keyevent --longpress KEYCODE_POWER]" {
		t.Errorf("got %v", *calls)
	}
}

func TestDumpsysFailure(t *testing.T) {
	a := &Android{Run: func(string, ...string) ([]byte, error) { return nil, errors.New("boom") }}
	a.Do(CloseApp) // must not panic or act
}

type countingLaptop struct{ toggles int }

func (l *countingLaptop) Toggle() (bool, error) { l.toggles++; return l.toggles%2 == 1, nil }

func TestLaptopScreenKey(t *testing.T) {
	a, _ := fakeAndroid("com.stremio.one", true)
	l := &countingLaptop{}
	a.Laptop = l
	a.Do(ToggleLaptopScreen)
	if l.toggles != 1 {
		t.Errorf("toggles = %d", l.toggles)
	}
	(&Android{}).Do(ToggleLaptopScreen) // desktop: nothing to do, no panic
}

const app = "app.ottotv.connect"

func TestF1AndF2OpenTheLaunchersScreens(t *testing.T) {
	a, calls := fakeAndroid("com.stremio.one", true)
	a.App = app
	a.Do(Shortcuts)
	a.Do(QuickPanel)
	want := "[am start -n app.ottotv.connect/.home.ShortcutsActivity am start -n app.ottotv.connect/.home.PanelActivity]"
	if fmt.Sprint(*calls) != want {
		t.Errorf("got %v", *calls)
	}
}

func TestF1AndF2LeaveTheLauncherAlone(t *testing.T) {
	// The launcher's own screens take F1 and F2 (and close on them).
	a, calls := fakeAndroid(app, true)
	a.App = app
	a.Do(Shortcuts)
	a.Do(QuickPanel)
	if len(*calls) != 0 {
		t.Errorf("got %v", *calls)
	}
	// Without the launcher's app, F1 and F2 do nothing.
	b, calls := fakeAndroid("com.stremio.one", true)
	b.Do(Shortcuts)
	b.Do(QuickPanel)
	if len(*calls) != 0 {
		t.Errorf("no app: got %v", *calls)
	}
}

func TestPowerMenuIsTheQuickPanel(t *testing.T) {
	a, calls := fakeAndroid("com.stremio.one", true)
	a.App = app
	a.Do(PowerMenu)
	if fmt.Sprint(*calls) != "[am start -n app.ottotv.connect/.home.PanelActivity --es focus power]" {
		t.Errorf("got %v", *calls)
	}
}

func TestPowerMenuFallsBackWhenThePanelWontOpen(t *testing.T) {
	a, calls := fakeAndroid("com.stremio.one", true)
	a.App = app
	run := a.Run
	a.Run = func(name string, args ...string) ([]byte, error) {
		if name == "am" && args[0] == "start" {
			return nil, errors.New("Activity class does not exist")
		}
		return run(name, args...)
	}
	a.Do(PowerMenu)
	if fmt.Sprint(*calls) != "[press 116 release 116]" {
		t.Errorf("got %v", *calls)
	}
}

func TestClosingAnAppShowsANotice(t *testing.T) {
	a, _ := fakeAndroid("com.stremio.one", true)
	var got []string
	a.Notify = func(kind, pkg string) { got = append(got, kind+" "+pkg) }
	a.Do(CloseApp)
	if fmt.Sprint(got) != "[closed com.stremio.one]" {
		t.Errorf("got %v", got)
	}
	// Nothing closed, nothing to say.
	b, _ := fakeAndroid("com.android.systemui", true)
	got = nil
	b.Notify = a.Notify
	b.Do(CloseApp)
	if len(got) != 0 {
		t.Errorf("systemui: got %v", got)
	}
}

func TestLaptopScreenKeyShowsANotice(t *testing.T) {
	a, _ := fakeAndroid("com.stremio.one", true)
	a.Laptop = &countingLaptop{}
	var got []string
	a.Notify = func(kind, _ string) { got = append(got, kind) }
	a.Do(ToggleLaptopScreen)
	a.Do(ToggleLaptopScreen)
	if fmt.Sprint(got) != "[screen_off screen_on]" {
		t.Errorf("got %v", got)
	}
}

func TestPhonePanelOpensOverAnything(t *testing.T) {
	// No key reaches the launcher from the phone's button, so the panel
	// opens over the launcher too; an open panel closes (toggle).
	for _, top := range []string{"com.stremio.one", app} {
		a, calls := fakeAndroid(top, true)
		a.App = app
		a.Do(TogglePanel)
		if fmt.Sprint(*calls) != "[am start -n app.ottotv.connect/.home.PanelActivity --ez toggle true]" {
			t.Errorf("%s: got %v", top, *calls)
		}
	}
	b, calls := fakeAndroid("com.stremio.one", true)
	b.Do(TogglePanel)
	if len(*calls) != 0 {
		t.Errorf("no app: got %v", *calls)
	}
}
