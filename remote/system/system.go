// Package system does, as root, what the launcher's quick panel asks for and
// apps may not do themselves: switch the sound between the TV and the
// laptop, suspend, restart and turn off, and read the system's details.
package system

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"otto/remote/server"
)

// Keys presses keys on the virtual keyboard (the phone remote's).
type Keys interface {
	Press(code uint16) error
	Release(code uint16) error
}

const keyPower = 116

// Android carries the actions out with Android's own tools.
type Android struct {
	State string // folder that keeps the sound choice (/data/misc/otto)
	Keys  Keys   // optional: Power is pressed on it to suspend
	// Hooks is init-hooks.sh (/boot/otto/init-hooks.sh): when no TV was on
	// HDMI at boot, its otto_find_hdmi_pcm looks again. Empty: no second look.
	Hooks string
	// Run, Read and Glob default to the real thing; tests replace them.
	Run  func(name string, args ...string) ([]byte, error)
	Read func(path string) ([]byte, error)
	Glob func(pattern string) ([]string, error)

	mu       sync.Mutex
	cpu      []uint64 // last /proc/stat "cpu" line, for the CPU use between two reads
	ssid     string
	ssidRead time.Time
}

// Where the sound goes is a pair of properties the x86 audio HAL reads when
// audioserver starts (see otto_setup_audio in init-hooks.sh, which also
// leaves the TV's output in otto.audio.hdmi, and the choice in
// <State>/audio for the next boot).
const (
	propHDMI    = "hal.audio.out.hdmi"
	propPrimary = "hal.audio.primary.hdmi"
	propTV      = "otto.audio.hdmi"
)

// SetAudio implements server.System.
func (a *Android) SetAudio(to string) error {
	switch to {
	case "tv":
		pcm := a.tvOutput()
		if pcm == "" {
			return server.ErrNoTVSound
		}
		if err := a.setprop(propPrimary, "1"); err != nil {
			return err
		}
		if err := a.setprop(propHDMI, pcm); err != nil {
			return err
		}
	case "notebook":
		if err := a.setprop(propHDMI, ""); err != nil {
			return err
		}
		if err := a.setprop(propPrimary, ""); err != nil {
			return err
		}
	default:
		return fmt.Errorf("unknown sound output %q", to)
	}
	if a.State != "" {
		tmp := filepath.Join(a.State, "audio.tmp")
		if err := os.WriteFile(tmp, []byte(to+"\n"), 0o600); err == nil {
			os.Rename(tmp, filepath.Join(a.State, "audio"))
		}
	}
	// audioserver reads the properties when it starts; init starts it again.
	_, err := a.run("pkill", "audioserver")
	return err
}

// tvOutput is the TV's sound output (pcmC0D3p), as init-hooks.sh found it
// at boot, or as it finds it now: the TV may have been off then.
func (a *Android) tvOutput() string {
	if pcm := a.prop(propTV); pcm != "" {
		return pcm
	}
	if a.Hooks == "" {
		return ""
	}
	if _, err := os.Stat(a.Hooks); a.Run == nil && err != nil {
		return ""
	}
	out, err := a.run("sh", "-c", `. "$0" && otto_find_hdmi_pcm`, a.Hooks)
	pcm := strings.TrimSpace(string(out))
	if err != nil || !pcmRe.MatchString(pcm) {
		return ""
	}
	a.setprop(propTV, pcm)
	return pcm
}

var pcmRe = regexp.MustCompile(`^pcmC[0-9]+D[0-9]+p$`)

var ssidRe = regexp.MustCompile(`(?:connected to "|SSID: ")([^"]+)"`)

// SSID implements server.System. Android only tells apps the network's
// name with location access, so the panel asks here.
func (a *Android) SSID() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	if time.Since(a.ssidRead) < 10*time.Second {
		return a.ssid
	}
	a.ssidRead = time.Now()
	a.ssid = ""
	if out, err := a.run("cmd", "wifi", "status"); err == nil {
		if m := ssidRe.FindStringSubmatch(string(out)); m != nil && m[1] != "<unknown ssid>" {
			a.ssid = m[1]
		}
	}
	return a.ssid
}

// Power implements server.System.
func (a *Android) Power(action string) error {
	switch action {
	case "suspend":
		// Like the phone remote's moon button: a short press of Power.
		if a.Keys != nil && a.Keys.Press(keyPower) == nil {
			time.Sleep(100 * time.Millisecond)
			return a.Keys.Release(keyPower)
		}
		_, err := a.run("input", "keyevent", "KEYCODE_SLEEP")
		return err
	case "reboot", "shutdown":
		_, err := a.run("svc", "power", action)
		return err
	}
	return fmt.Errorf("unknown power action %q", action)
}

// Details implements server.System. Unknown values are left out.
func (a *Android) Details() map[string]any {
	d := map[string]any{
		"graphics": a.graphics(),
		"adb":      a.adbPort(),
	}
	if t, ok := a.cpuTemp(); ok {
		d["cpuTemp"] = t
	}
	if l, ok := a.cpuLoad(); ok {
		d["cpuLoad"] = l
	}
	return d
}

// cpuTemp prefers the CPU package's sensor, then the ACPI one.
func (a *Android) cpuTemp() (float64, bool) {
	zones, _ := a.glob("/sys/class/thermal/thermal_zone*")
	best, rank := 0.0, 0
	for _, z := range zones {
		typ := strings.TrimSpace(string(a.read(filepath.Join(z, "type"))))
		v, err := strconv.Atoi(strings.TrimSpace(string(a.read(filepath.Join(z, "temp")))))
		if err != nil || v <= 0 {
			continue
		}
		r := 1
		switch typ {
		case "x86_pkg_temp":
			r = 3
		case "acpitz":
			r = 2
		}
		if r > rank {
			best, rank = float64(v)/1000, r
		}
	}
	return best, rank > 0
}

// cpuLoad is the share of time the CPU was busy since the last call (the
// panel asks every 2 seconds), or over a quarter second on the first one.
func (a *Android) cpuLoad() (float64, bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := a.readCPU()
	if now == nil {
		return 0, false
	}
	prev := a.cpu
	if prev == nil {
		time.Sleep(250 * time.Millisecond)
		prev, now = now, a.readCPU()
		if now == nil {
			return 0, false
		}
	}
	a.cpu = now
	return Busy(prev, now)
}

// Busy is the CPU use between two /proc/stat "cpu" lines, in percent.
func Busy(prev, now []uint64) (float64, bool) {
	if len(prev) < 4 || len(now) != len(prev) {
		return 0, false
	}
	var total, idle uint64
	for i := range now {
		if now[i] < prev[i] {
			return 0, false
		}
		d := now[i] - prev[i]
		total += d
		if i == 3 || i == 4 { // idle and iowait
			idle += d
		}
	}
	if total == 0 {
		return 0, false
	}
	return float64(total-idle) * 100 / float64(total), true
}

func (a *Android) readCPU() []uint64 {
	lines := strings.SplitN(string(a.read("/proc/stat")), "\n", 2)
	fields := strings.Fields(lines[0])
	if len(fields) < 5 || fields[0] != "cpu" {
		return nil
	}
	var v []uint64
	for _, f := range fields[1:] {
		n, err := strconv.ParseUint(f, 10, 64)
		if err != nil {
			return nil
		}
		v = append(v, n)
	}
	return v
}

// graphics is "software" in compatibility mode (no GPU driver), else "hardware".
func (a *Android) graphics() string {
	cmdline := " " + string(a.read("/proc/cmdline")) + " "
	if strings.Contains(cmdline, " HWACCEL=0 ") || strings.Contains(cmdline, " nomodeset ") ||
		strings.Contains(a.prop("ro.hardware.egl"), "swiftshader") {
		return "software"
	}
	return "hardware"
}

// adbPort is the port adb listens on over the network, or 0 when it doesn't.
func (a *Android) adbPort() int {
	for _, p := range []string{"service.adb.tcp.port", "persist.adb.tcp.port"} {
		if n, err := strconv.Atoi(a.prop(p)); err == nil && n > 0 {
			return n
		}
	}
	// Turned on some other way: is anything listening on adb's usual port?
	for _, f := range []string{"/proc/net/tcp", "/proc/net/tcp6"} {
		if Listening(string(a.read(f)), 5555) {
			return 5555
		}
	}
	return 0
}

// Listening reports whether a /proc/net/tcp table has a socket listening
// (state 0A) on port.
func Listening(table string, port int) bool {
	suffix := fmt.Sprintf(":%04X", port)
	for _, line := range strings.Split(table, "\n") {
		f := strings.Fields(line)
		if len(f) > 3 && strings.HasSuffix(f[1], suffix) && f[3] == "0A" {
			return true
		}
	}
	return false
}

func (a *Android) prop(name string) string {
	out, err := a.run("getprop", name)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

func (a *Android) setprop(name, value string) error {
	_, err := a.run("setprop", name, value)
	return err
}

func (a *Android) run(name string, args ...string) ([]byte, error) {
	if a.Run != nil {
		return a.Run(name, args...)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return exec.CommandContext(ctx, name, args...).Output()
}

func (a *Android) read(path string) []byte {
	if a.Read != nil {
		b, _ := a.Read(path)
		return b
	}
	b, _ := os.ReadFile(path)
	return b
}

func (a *Android) glob(pattern string) ([]string, error) {
	if a.Glob != nil {
		return a.Glob(pattern)
	}
	return filepath.Glob(pattern)
}
