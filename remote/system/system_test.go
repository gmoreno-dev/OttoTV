package system

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"otto/remote/server"
)

// fakeAndroid keeps properties in a map and records every other command.
type fakeAndroid struct {
	props map[string]string
	calls []string
	files map[string]string
	fail  string // a command that fails
}

func (f *fakeAndroid) run(name string, args ...string) ([]byte, error) {
	cmd := strings.TrimSpace(name + " " + strings.Join(args, " "))
	if f.fail != "" && strings.HasPrefix(cmd, f.fail) {
		return nil, errors.New("failed")
	}
	switch name {
	case "getprop":
		return []byte(f.props[args[0]] + "\n"), nil
	case "setprop":
		f.props[args[0]] = args[1]
	}
	f.calls = append(f.calls, cmd)
	return nil, nil
}

func (f *fakeAndroid) read(path string) ([]byte, error) {
	if v, ok := f.files[path]; ok {
		return []byte(v), nil
	}
	return nil, os.ErrNotExist
}

func (f *fakeAndroid) glob(pattern string) ([]string, error) {
	var out []string
	for p := range f.files {
		if ok, _ := filepath.Match(pattern+"/*", p); ok {
			if d := filepath.Dir(p); !contains(out, d) {
				out = append(out, d)
			}
		}
	}
	return out, nil
}

func contains(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}

func newAndroid(t *testing.T) (*Android, *fakeAndroid) {
	f := &fakeAndroid{props: map[string]string{}, files: map[string]string{}}
	return &Android{State: t.TempDir(), Run: f.run, Read: f.read, Glob: f.glob}, f
}

func TestSoundToTheTV(t *testing.T) {
	a, f := newAndroid(t)
	f.props[propTV] = "pcmC0D3p"
	f.props[propPrimary] = "0"
	if err := a.SetAudio("tv"); err != nil {
		t.Fatal(err)
	}
	want := "[setprop hal.audio.out.hdmi pcmC0D3p setprop hal.audio.primary.hdmi 1 pkill audioserver]"
	if fmt.Sprint(f.calls) != want {
		t.Errorf("calls %v", f.calls)
	}
	if b, _ := os.ReadFile(filepath.Join(a.State, "audio")); string(b) != "tv\n" {
		t.Errorf("choice saved as %q", b)
	}
	if got := a.Audio(true); got != "tv" {
		t.Errorf("Audio() = %q", got)
	}
}

func TestSoundToTheLaptop(t *testing.T) {
	a, f := newAndroid(t)
	f.props[propTV] = "pcmC0D3p"
	f.props[propHDMI] = "pcmC0D3p"
	f.props[propPrimary] = "1"
	if err := a.SetAudio("notebook"); err != nil {
		t.Fatal(err)
	}
	// 0, never empty: empty, the HAL goes by Android's routing, maybe HDMI.
	if fmt.Sprint(f.calls) != "[setprop hal.audio.primary.hdmi 0 pkill audioserver]" {
		t.Errorf("calls %v", f.calls)
	}
	if b, _ := os.ReadFile(filepath.Join(a.State, "audio")); string(b) != "notebook\n" {
		t.Errorf("choice saved as %q", b)
	}
	// The TV's output is still known, to switch back.
	if f.props[propTV] != "pcmC0D3p" || f.props[propHDMI] != "pcmC0D3p" {
		t.Error("forgot the TV's output")
	}
	if got := a.Audio(true); got != "notebook" {
		t.Errorf("Audio() = %q", got)
	}
}

func TestSoundSwitchThatDidntTake(t *testing.T) {
	a, f := newAndroid(t)
	f.props[propTV] = "pcmC0D3p"
	f.props[propPrimary] = "1"
	run := a.Run
	a.Run = func(name string, args ...string) ([]byte, error) {
		if name == "setprop" && args[0] == propPrimary {
			return nil, nil // "succeeds" without changing anything
		}
		return run(name, args...)
	}
	if err := a.SetAudio("notebook"); err == nil || !strings.Contains(err.Error(), propPrimary) {
		t.Errorf("err %v", err)
	}
	if _, err := os.Stat(filepath.Join(a.State, "audio")); err == nil {
		t.Error("saved a choice that didn't happen")
	}
	for _, c := range f.calls {
		if strings.HasPrefix(c, "pkill") {
			t.Error("restarted the sound for nothing")
		}
	}
}

func TestAudioNow(t *testing.T) {
	a, f := newAndroid(t)
	cases := []struct {
		primary, hdmi string
		laptop        bool
		want          string
	}{
		{"1", "pcmC0D3p", true, "tv"},
		{"true", "pcmC0D3p", false, "tv"},
		{"0", "pcmC0D3p", true, "notebook"},
		{"", "", true, "notebook"},   // no TV found at boot: the laptop's speakers
		{"", "", false, ""},          // a desktop without the TV's sound
		{"1", "", true, "notebook"},  // nothing to send to
		{"0", "pcmC0D3p", false, ""}, // a desktop with the sound off the TV
	}
	for _, c := range cases {
		f.props[propPrimary], f.props[propHDMI] = c.primary, c.hdmi
		if got := a.Audio(c.laptop); got != c.want {
			t.Errorf("%+v: got %q", c, got)
		}
	}
}

func TestNoTVSound(t *testing.T) {
	a, f := newAndroid(t)
	if err := a.SetAudio("tv"); !errors.Is(err, server.ErrNoTVSound) {
		t.Errorf("err %v", err)
	}
	if len(f.calls) != 0 {
		t.Errorf("changed things: %v", f.calls)
	}
	if err := a.SetAudio("bluetooth"); err == nil {
		t.Error("unknown output accepted")
	}
	if _, err := os.Stat(filepath.Join(a.State, "audio")); err == nil {
		t.Error("saved a choice that didn't happen")
	}
}

func TestTVTurnedOnAfterBoot(t *testing.T) {
	a, f := newAndroid(t)
	a.Hooks = "/boot/otto/init-hooks.sh"
	found := "pcmC1D7p"
	a.Run = func(name string, args ...string) ([]byte, error) {
		if name == "sh" {
			if args[2] != a.Hooks || !strings.Contains(args[1], "otto_find_hdmi_pcm") {
				t.Errorf("sh %v", args)
			}
			return []byte(found + "\n"), nil
		}
		return f.run(name, args...)
	}
	if err := a.SetAudio("tv"); err != nil {
		t.Fatal(err)
	}
	if f.props[propTV] != "pcmC1D7p" || f.props[propHDMI] != "pcmC1D7p" {
		t.Errorf("props %v", f.props)
	}
	// Still no TV: nothing changes; garbage isn't taken for an output.
	b, g := newAndroid(t)
	b.Hooks = a.Hooks
	b.Run = func(name string, args ...string) ([]byte, error) {
		if name == "sh" {
			return []byte("sh: alsa_amixer: not found\n"), nil
		}
		return g.run(name, args...)
	}
	if err := b.SetAudio("tv"); !errors.Is(err, server.ErrNoTVSound) || g.props[propTV] != "" {
		t.Errorf("err %v, props %v", err, g.props)
	}
}

func TestSoundSwitchFailure(t *testing.T) {
	a, f := newAndroid(t)
	f.props[propTV] = "pcmC0D3p"
	f.fail = "setprop"
	if err := a.SetAudio("tv"); err == nil {
		t.Error("no error")
	}
	if _, err := os.Stat(filepath.Join(a.State, "audio")); err == nil {
		t.Error("saved a choice that didn't happen")
	}
}

const wifiStatus = `Wifi is enabled
Wifi scanning is always available
==== Primary ClientModeManager instance ====
Wifi is connected to "Sala 5G"
WifiInfo: SSID: "Sala 5G", BSSID: 9c:53:22:aa:bb:cc, MAC: 02:00:00:00:00:00, Supplicant state: COMPLETED, RSSI: -58
`

func TestSSID(t *testing.T) {
	a, _ := newAndroid(t)
	n := 0
	out := wifiStatus
	a.Run = func(name string, args ...string) ([]byte, error) {
		n++
		return []byte(out), nil
	}
	if got := a.SSID(); got != "Sala 5G" {
		t.Errorf("got %q", got)
	}
	a.SSID()
	if n != 1 {
		t.Errorf("asked Android %d times in a row", n)
	}
	b, _ := newAndroid(t)
	b.Run = func(string, ...string) ([]byte, error) {
		return []byte("Wifi is enabled\nWifi is not connected\n"), nil
	}
	if got := b.SSID(); got != "" {
		t.Errorf("offline: got %q", got)
	}
	c, _ := newAndroid(t)
	c.Run = func(string, ...string) ([]byte, error) {
		return []byte(`WifiInfo: SSID: "<unknown ssid>", BSSID: 02:00:00:00:00:00`), nil
	}
	if got := c.SSID(); got != "" {
		t.Errorf("unknown: got %q", got)
	}
}

type keys struct{ log []string }

func (k *keys) Press(c uint16) error   { k.log = append(k.log, fmt.Sprint("press ", c)); return nil }
func (k *keys) Release(c uint16) error { k.log = append(k.log, fmt.Sprint("release ", c)); return nil }

func TestPower(t *testing.T) {
	a, f := newAndroid(t)
	for _, act := range []string{"reboot", "shutdown"} {
		if err := a.Power(act); err != nil {
			t.Fatal(err)
		}
	}
	if err := a.Power("halt"); err == nil {
		t.Error("unknown action accepted")
	}
	a.Power("suspend") // without a virtual keyboard
	if fmt.Sprint(f.calls) != "[svc power reboot svc power shutdown input keyevent KEYCODE_SLEEP]" {
		t.Errorf("calls %v", f.calls)
	}
	k := &keys{}
	a.Keys = k
	a.Power("suspend")
	if fmt.Sprint(k.log) != "[press 116 release 116]" {
		t.Errorf("keys %v", k.log)
	}
}

func TestDetails(t *testing.T) {
	a, f := newAndroid(t)
	f.files["/sys/class/thermal/thermal_zone0/type"] = "acpitz\n"
	f.files["/sys/class/thermal/thermal_zone0/temp"] = "51000\n"
	f.files["/sys/class/thermal/thermal_zone1/type"] = "x86_pkg_temp\n"
	f.files["/sys/class/thermal/thermal_zone1/temp"] = "58500\n"
	f.files["/proc/stat"] = "cpu  100 0 100 800 0 0 0 0 0 0\ncpu0 1 2 3 4\n"
	f.files["/proc/cmdline"] = "root=/dev/ram0 SRC=/android quiet"
	f.props["service.adb.tcp.port"] = "5555"
	stat := []string{"cpu  100 0 100 800 0 0 0 0 0 0", "cpu  150 0 150 900 0 0 0 0 0 0"}
	read := a.Read
	a.Read = func(p string) ([]byte, error) {
		if p == "/proc/stat" {
			s := stat[0]
			if len(stat) > 1 {
				stat = stat[1:]
			}
			return []byte(s + "\n"), nil
		}
		return read(p)
	}
	d := a.Details()
	if d["cpuTemp"] != 58.5 || d["graphics"] != "hardware" || d["adb"] != 5555 {
		t.Errorf("details %v", d)
	}
	if d["cpuLoad"] != 50.0 {
		t.Errorf("cpu load %v", d["cpuLoad"])
	}
	// Compatibility mode, adb off, no sensors.
	b, g := newAndroid(t)
	g.files["/proc/cmdline"] = "quiet HWACCEL=0 SRC=/android"
	d = b.Details()
	if d["graphics"] != "software" || d["adb"] != 0 {
		t.Errorf("details %v", d)
	}
	if _, ok := d["cpuTemp"]; ok {
		t.Errorf("made up a temperature: %v", d)
	}
}

func TestBusy(t *testing.T) {
	cases := []struct {
		prev, now []uint64
		want      float64
		ok        bool
	}{
		{[]uint64{0, 0, 0, 0, 0}, []uint64{25, 0, 25, 50, 0}, 50, true},
		{[]uint64{0, 0, 0, 0, 0}, []uint64{0, 0, 0, 90, 10}, 0, true}, // iowait is idle
		{[]uint64{0, 0, 0, 0}, []uint64{0, 0, 0, 0}, 0, false},        // no time passed
		{[]uint64{5, 0, 0, 0}, []uint64{4, 0, 0, 0}, 0, false},        // counters went back
		{[]uint64{0, 0}, []uint64{1, 1}, 0, false},
	}
	for _, c := range cases {
		got, ok := Busy(c.prev, c.now)
		if got != c.want || ok != c.ok {
			t.Errorf("Busy(%v, %v) = %v %v", c.prev, c.now, got, ok)
		}
	}
}

func TestListening(t *testing.T) {
	table := `  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000:15B3 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 1
   1: 0100007F:21EE 0100007F:D431 01 00000000:00000000 00:00000000 00000000     0        0 2
`
	if !Listening(table, 5555) {
		t.Error("adb not seen")
	}
	if Listening(table, 8686) {
		t.Error("a connection taken for a listener")
	}
}
