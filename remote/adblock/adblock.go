// Package adblock blocks ads on the whole TV with Android's Private DNS:
// every app's name lookups go, encrypted (DNS over TLS), to AdGuard DNS,
// which answers the names of ad and tracker servers with nothing. Apps and
// sites that load ads from their own servers (YouTube) still show them.
//
// On by default: the first time otto-remote runs, it turns it on, unless
// the TV already uses a Private DNS server of its own. After that the
// quick panel's switch decides, and Android keeps the setting.
//
// In this mode Android has no fallback: on a network that doesn't let the
// TV reach AdGuard DNS (port 853 blocked), no name resolves and the TV
// looks offline. Check notices that, Android saying Private DNS is broken
// while the internet answers otherwise, and turns it off ("paused", with a
// notice on the TV) until someone turns it on again.
package adblock

import (
	"bytes"
	"context"
	"log"
	"net"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// Host is AdGuard DNS's DNS-over-TLS server, with its default list (ads
// and trackers).
const Host = "dns.adguard-dns.com"

// The states, as the quick panel shows them and the Choice file keeps them.
const (
	On     = "on"
	Off    = "off"
	Paused = "paused" // turned off by Check: the network blocks it
)

// BrokenChecks is how many checks in a row must find Private DNS broken,
// with the internet answering, before Check pauses it: networks take a
// moment to settle after connecting.
const BrokenChecks = 3

// DNS turns ad blocking on and off through Android's settings.
type DNS struct {
	// Choice is the file that keeps what was chosen, On, Off or Paused
	// (/data/misc/otto/adblock). Missing: nothing yet, so Apply turns it on.
	Choice string
	// Run defaults to running the command; tests replace it.
	Run func(name string, args ...string) ([]byte, error)
	// Online says whether the internet answers apart from DNS. Default: a
	// connection to any of a few well-known addresses, without names.
	Online func() bool
	// Paused is called after Check turned it off (a notice on the TV). May
	// be nil.
	Paused func()
	Log    *log.Logger // may be nil

	mu     sync.Mutex
	broken int // checks in a row that found it broken with the internet up
}

// State is On, Off or Paused, as the quick panel shows it; "" when
// Android's settings can't be read. On means Android uses AdGuard DNS now,
// whoever set it.
func (d *DNS) State() string {
	d.mu.Lock()
	defer d.mu.Unlock()
	mode, host, err := d.settings()
	if err != nil {
		d.logf("reading the settings: %v", err)
		return ""
	}
	if mode == "hostname" && host == Host {
		return On
	}
	if d.choice() == Paused {
		return Paused
	}
	return Off
}

// Set turns it on or off, from the quick panel, and remembers the choice.
func (d *DNS) Set(on bool) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	choice := Off
	if on {
		choice = On
	}
	err := d.set(on, choice)
	if err == nil {
		d.logf("%s (quick panel)", choice)
	}
	return err
}

func (d *DNS) set(on bool, choice string) error {
	if on {
		// The server first: switching the mode then uses it at once.
		if err := d.put("private_dns_specifier", Host); err != nil {
			return err
		}
		if err := d.put("private_dns_mode", "hostname"); err != nil {
			return err
		}
	} else {
		mode, host, err := d.settings()
		if err != nil {
			return err
		}
		// Only ours: a server of the user's own stays.
		if mode == "hostname" && host == Host {
			// Android's default ("Automatic"): encrypted when the network's
			// own DNS server can, plain otherwise.
			if err := d.put("private_dns_mode", "opportunistic"); err != nil {
				return err
			}
		}
	}
	d.broken = 0
	return d.save(choice)
}

// Apply turns it on the first time (nothing chosen yet), unless the TV
// already uses a Private DNS server of its own: then it stays, and ad
// blocking is off. Later Android keeps whatever was set.
func (d *DNS) Apply() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.choice() != "" {
		return nil
	}
	mode, host, err := d.settings()
	if err != nil {
		return err
	}
	if mode == "hostname" && host != Host && host != "" && host != "null" {
		d.logf("the TV has a Private DNS server of its own (%s): left as it is", host)
		return d.save(Off)
	}
	if err := d.set(true, On); err != nil {
		return err
	}
	d.logf("on (first run)")
	return nil
}

// Watch applies the first-run default, again every interval until
// Android's settings answer (otto-remote may start before they do), then
// checks the network every interval until ctx ends.
func (d *DNS) Watch(ctx context.Context, every time.Duration) {
	for tries := 0; ; tries++ {
		err := d.Apply()
		if err == nil {
			break
		}
		if tries == 0 {
			d.logf("first run: %v (trying again every %v)", err, every)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(every):
		}
	}
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			d.Check()
		}
	}
}

// Check looks once at whether this network blocks ad blocking: Android says
// Private DNS is broken while the internet answers. After BrokenChecks in
// a row it turns ad blocking off, Paused, and calls Paused.
func (d *DNS) Check() {
	d.mu.Lock()
	on := d.choice() == On
	if !on {
		d.broken = 0
	}
	d.mu.Unlock()
	if !on {
		return
	}
	// Each network's line has its capabilities, " PrivateDnsBroken" among
	// them when Android's check of the server failed (Android 10 printed
	// "Private DNS is broken"). Without the "networks" argument an older
	// Android prints everything, networks included.
	out, err := d.run("dumpsys", "connectivity", "networks")
	broken := err == nil && (bytes.Contains(out, []byte("PrivateDnsBroken")) ||
		bytes.Contains(out, []byte("Private DNS is broken")))
	// Broken because there's no internet at all isn't ours to fix: count
	// only the checks where the internet answers.
	up := broken && d.online()

	d.mu.Lock()
	defer d.mu.Unlock()
	if !broken {
		d.broken = 0
		return
	}
	if !up {
		return
	}
	d.broken++
	if d.broken < BrokenChecks || d.choice() != On {
		return
	}
	mode, host, err := d.settings()
	if err != nil || mode != "hostname" || host != Host {
		// Someone else's server now: not ours to pause.
		d.broken = 0
		return
	}
	if err := d.set(false, Paused); err != nil {
		d.logf("pausing: %v", err)
		return
	}
	d.logf("paused: Android can't reach %s on this network, and the internet answers", Host)
	if d.Paused != nil {
		go d.Paused()
	}
}

func (d *DNS) settings() (mode, host string, err error) {
	out, err := d.run("settings", "get", "global", "private_dns_mode")
	if err != nil {
		return "", "", err
	}
	mode = strings.TrimSpace(string(out))
	out, err = d.run("settings", "get", "global", "private_dns_specifier")
	if err != nil {
		return "", "", err
	}
	return mode, strings.TrimSpace(string(out)), nil
}

func (d *DNS) put(name, value string) error {
	_, err := d.run("settings", "put", "global", name, value)
	return err
}

func (d *DNS) choice() string {
	b, err := os.ReadFile(d.Choice)
	if err != nil {
		return ""
	}
	switch c := strings.TrimSpace(string(b)); c {
	case On, Off, Paused:
		return c
	}
	return ""
}

func (d *DNS) save(choice string) error {
	tmp := d.Choice + ".tmp"
	if err := os.WriteFile(tmp, []byte(choice+"\n"), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, d.Choice)
}

func (d *DNS) run(name string, args ...string) ([]byte, error) {
	if d.Run != nil {
		return d.Run(name, args...)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return exec.CommandContext(ctx, name, args...).Output()
}

func (d *DNS) online() bool {
	if d.Online != nil {
		return d.Online()
	}
	return Reachable(Probes, 4*time.Second)
}

// Probes are well-known addresses, of different companies, that answer on
// port 443: reaching any of them, no names involved, means the internet is
// up.
var Probes = []string{"1.1.1.1:443", "8.8.8.8:443", "9.9.9.9:443"}

// Reachable says whether a TCP connection to any of addrs succeeds within
// timeout. It tries them all at once.
func Reachable(addrs []string, timeout time.Duration) bool {
	ok := make(chan bool, len(addrs))
	for _, a := range addrs {
		go func(a string) {
			c, err := net.DialTimeout("tcp", a, timeout)
			if err == nil {
				c.Close()
			}
			ok <- err == nil
		}(a)
	}
	for range addrs {
		if <-ok {
			return true
		}
	}
	return false
}

func (d *DNS) logf(format string, args ...any) {
	if d.Log != nil {
		d.Log.Printf(format, args...)
	}
}
