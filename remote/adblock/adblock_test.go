package adblock

import (
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeAndroid answers settings and dumpsys like Android, from a map.
type fakeAndroid struct {
	mu       sync.Mutex
	global   map[string]string
	broken   bool
	failing  bool // settings doesn't answer (system_server not up yet)
	puts     []string
	dumpsyss int
}

func (f *fakeAndroid) run(name string, args ...string) ([]byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	switch {
	case name == "settings" && f.failing:
		return nil, errors.New("cmd: Can't find service: settings")
	case name == "settings" && len(args) == 3 && args[0] == "get" && args[1] == "global":
		if v, ok := f.global[args[2]]; ok {
			return []byte(v + "\n"), nil
		}
		return []byte("null\n"), nil
	case name == "settings" && len(args) == 4 && args[0] == "put" && args[1] == "global":
		f.global[args[2]] = args[3]
		f.puts = append(f.puts, args[2]+"="+args[3])
		return nil, nil
	case name == "dumpsys" && strings.Join(args, " ") == "connectivity networks":
		f.dumpsyss++
		caps := "Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN&VALIDATED"
		if f.broken {
			caps = "Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN PrivateDnsBroken"
		}
		return []byte("NetworkAgentInfo{network{100}  handle{430000000000}  ni{WIFI CONNECTED extra: } " +
			"lp{{InterfaceName: wlan0 PrivateDnsServerName: " + f.global["private_dns_specifier"] + "}} " +
			"nc{[ Transports: WIFI " + caps + " LinkUpBandwidth>=8000Kbps]}}\n"), nil
	}
	return nil, errors.New("unexpected: " + name + " " + strings.Join(args, " "))
}

func (f *fakeAndroid) mode() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.global["private_dns_mode"]
}

func newDNS(t *testing.T) (*DNS, *fakeAndroid) {
	f := &fakeAndroid{global: map[string]string{}}
	return &DNS{Choice: filepath.Join(t.TempDir(), "adblock"), Run: f.run, Online: func() bool { return true }}, f
}

func saved(t *testing.T, d *DNS) string {
	b, _ := os.ReadFile(d.Choice)
	return strings.TrimSpace(string(b))
}

func TestOnTheFirstRun(t *testing.T) {
	d, f := newDNS(t)
	if s := d.State(); s != Off {
		t.Fatalf("before: %q", s)
	}
	if err := d.Apply(); err != nil {
		t.Fatal(err)
	}
	// The server before the mode: the mode switches straight to it.
	if got := strings.Join(f.puts, ","); got != "private_dns_specifier="+Host+",private_dns_mode=hostname" {
		t.Errorf("puts: %s", got)
	}
	if s := d.State(); s != On || saved(t, d) != On {
		t.Errorf("state %q, saved %q", s, saved(t, d))
	}
	// After that Android keeps it: nothing is set again.
	f.puts = nil
	f.global["private_dns_mode"] = "opportunistic" // turned off in Android's settings
	if err := d.Apply(); err != nil || len(f.puts) != 0 {
		t.Errorf("second run: %v, puts %v", err, f.puts)
	}
	if s := d.State(); s != Off {
		t.Errorf("turned off elsewhere: %q", s)
	}
}

func TestTheTVsOwnServerStays(t *testing.T) {
	d, f := newDNS(t)
	f.global["private_dns_mode"] = "hostname"
	f.global["private_dns_specifier"] = "dns.nextdns.io"
	if err := d.Apply(); err != nil {
		t.Fatal(err)
	}
	if len(f.puts) != 0 || saved(t, d) != Off || d.State() != Off {
		t.Errorf("puts %v, saved %q, state %q", f.puts, saved(t, d), d.State())
	}
	// Off from the panel leaves it alone too.
	if err := d.Set(false); err != nil || len(f.puts) != 0 {
		t.Errorf("off: %v, puts %v", err, f.puts)
	}
	// On replaces it: that's what the switch says.
	if err := d.Set(true); err != nil || d.State() != On {
		t.Errorf("on: %v, %q", err, d.State())
	}
}

func TestAlreadyOn(t *testing.T) {
	// Set by hand with adb before this version: counts as on.
	d, f := newDNS(t)
	f.global["private_dns_mode"] = "hostname"
	f.global["private_dns_specifier"] = Host
	if err := d.Apply(); err != nil || saved(t, d) != On || d.State() != On {
		t.Errorf("%v, saved %q, state %q", err, saved(t, d), d.State())
	}
}

func TestSwitch(t *testing.T) {
	d, f := newDNS(t)
	if err := d.Set(true); err != nil || d.State() != On || saved(t, d) != On {
		t.Fatalf("on: %v %q %q", err, d.State(), saved(t, d))
	}
	if err := d.Set(false); err != nil {
		t.Fatal(err)
	}
	if f.mode() != "opportunistic" || d.State() != Off || saved(t, d) != Off {
		t.Errorf("off: mode %q, state %q, saved %q", f.mode(), d.State(), saved(t, d))
	}
	// Off stays off on the next start.
	f.puts = nil
	if err := d.Apply(); err != nil || len(f.puts) != 0 {
		t.Errorf("start after off: %v %v", err, f.puts)
	}
}

func TestSettingsNotUpYet(t *testing.T) {
	d, f := newDNS(t)
	f.failing = true
	if s := d.State(); s != "" {
		t.Errorf("state: %q", s)
	}
	if err := d.Apply(); err == nil {
		t.Error("no error")
	}
	if saved(t, d) != "" {
		t.Errorf("saved %q before it was set", saved(t, d))
	}
	if err := d.Set(false); err == nil {
		t.Error("off: no error")
	}

	// Watch keeps trying, then checks.
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { d.Watch(ctx, 5*time.Millisecond); close(done) }()
	time.Sleep(20 * time.Millisecond)
	f.mu.Lock()
	f.failing = false
	f.mu.Unlock()
	deadline := time.Now().Add(2 * time.Second)
	for {
		f.mu.Lock()
		n := f.dumpsyss
		f.mu.Unlock()
		if n > 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("never got to checking")
		}
		time.Sleep(5 * time.Millisecond)
	}
	cancel()
	<-done
	if d.State() != On {
		t.Errorf("after Watch: %q", d.State())
	}
}

func TestPausedWhenTheNetworkBlocksIt(t *testing.T) {
	d, f := newDNS(t)
	paused := make(chan struct{}, 1)
	d.Paused = func() { paused <- struct{}{} }
	if err := d.Apply(); err != nil {
		t.Fatal(err)
	}
	f.broken = true
	for i := 1; i < BrokenChecks; i++ {
		d.Check()
		if f.mode() != "hostname" {
			t.Fatalf("paused after %d checks", i)
		}
	}
	d.Check()
	if f.mode() != "opportunistic" || d.State() != Paused || saved(t, d) != Paused {
		t.Fatalf("mode %q, state %q, saved %q", f.mode(), d.State(), saved(t, d))
	}
	select {
	case <-paused:
	case <-time.After(time.Second):
		t.Error("no notice")
	}
	// Paused stays paused: no more checks, nothing at the next start...
	f.puts = nil
	d.Check()
	if err := d.Apply(); err != nil || len(f.puts) != 0 {
		t.Errorf("after pausing: %v %v", err, f.puts)
	}
	// ...until it is turned on again.
	f.broken = false
	if err := d.Set(true); err != nil || d.State() != On {
		t.Errorf("on again: %v %q", err, d.State())
	}
}

func TestNotPausedWithoutInternet(t *testing.T) {
	d, f := newDNS(t)
	online := false
	d.Online = func() bool { return online }
	d.Apply()
	f.broken = true
	for i := 0; i < BrokenChecks*2; i++ {
		d.Check()
	}
	if f.mode() != "hostname" {
		t.Fatal("paused with no internet at all")
	}
	// Checks without internet don't count, nor break the count.
	online = true
	for i := 1; i < BrokenChecks; i++ {
		d.Check()
	}
	if f.mode() != "hostname" {
		t.Fatal("paused too soon")
	}
	d.Check()
	if f.mode() != "opportunistic" {
		t.Error("not paused")
	}
}

func TestBrokenForAMomentIsFine(t *testing.T) {
	d, f := newDNS(t)
	d.Apply()
	for i := 0; i < BrokenChecks*3; i++ {
		f.broken = i%BrokenChecks != BrokenChecks-1 // fine every third check
		d.Check()
	}
	if f.mode() != "hostname" {
		t.Error("paused")
	}
}

func TestNoChecksWhenOff(t *testing.T) {
	d, f := newDNS(t)
	d.Apply()
	d.Set(false)
	f.broken = true
	for i := 0; i < BrokenChecks; i++ {
		d.Check()
	}
	if f.dumpsyss != 0 || saved(t, d) != Off {
		t.Errorf("dumpsys %d times, saved %q", f.dumpsyss, saved(t, d))
	}
}

func TestOtherServerIsntPaused(t *testing.T) {
	// Chosen on, then changed in Android's settings to another server that
	// is broken: not ours.
	d, f := newDNS(t)
	d.Apply()
	f.global["private_dns_specifier"] = "dns.example"
	f.broken = true
	for i := 0; i < BrokenChecks; i++ {
		d.Check()
	}
	if f.mode() != "hostname" || saved(t, d) != On {
		t.Errorf("mode %q, saved %q", f.mode(), saved(t, d))
	}
}

func TestReachable(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skip(err)
	}
	defer l.Close()
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	closed, _ := net.Listen("tcp", "127.0.0.1:0")
	dead := closed.Addr().String()
	closed.Close()
	if !Reachable([]string{dead, l.Addr().String()}, time.Second) {
		t.Error("one answers: not reachable")
	}
	if Reachable([]string{dead}, time.Second) {
		t.Error("none answers: reachable")
	}
}
