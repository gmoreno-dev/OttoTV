package mdns

import (
	"context"
	"fmt"
	"log"
	"net"
	"os"
	"slices"
	"strings"
	"sync"
	"time"
)

// Port and group of multicast DNS.
var group = &net.UDPAddr{IP: net.IPv4(224, 0, 0, 251), Port: 5353}

// Packet is one datagram, with the interface it came in on (0: unknown).
type Packet struct {
	Data    []byte
	From    *net.UDPAddr
	Ifindex int
}

// Conn is the network side, a real socket (Listen) or a test's fake.
type Conn interface {
	// Read returns the next packet; an error ends Run.
	Read() (Packet, error)
	// Send sends b to addr, out of the interface ifindex (0: any).
	Send(b []byte, addr *net.UDPAddr, ifindex int) error
	// Join joins the mDNS group on an interface; joining twice is fine.
	Join(ifindex int) error
	Close() error
}

// Responder claims Base+".local" (or Base-2.local, Base-3.local... when
// another machine has it) and answers for it.
type Responder struct {
	Base string // "otto-tv"
	// State is a file that keeps the name between boots, so a phone that
	// saved otto-tv-2.local keeps finding this TV. May be "".
	State string
	// Addrs lists the IPv4 addresses to answer with, by interface index.
	// nil: the machine's interfaces (Interfaces).
	Addrs func() map[int][]net.IP
	Conn  Conn
	Log   *log.Logger

	// For tests.
	Probe    time.Duration // between probes; 250 ms
	Announce time.Duration // between the two announcements; 1 s
	Refresh  time.Duration // how often the addresses are looked at; 5 s

	mu   sync.Mutex
	name string // claimed; "" until then
}

// Host is the name the TV answers for, "otto-tv.local", or "" while it is
// not claimed yet.
func (r *Responder) Host() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.name == "" {
		return ""
	}
	return r.name + ".local"
}

// Run claims a name and answers for it until ctx ends.
func (r *Responder) Run(ctx context.Context) error {
	if r.Addrs == nil {
		r.Addrs = Interfaces
	}
	if r.Log == nil {
		r.Log = log.New(os.Stderr, "mdns: ", 0)
	}
	if r.Probe == 0 {
		r.Probe = 250 * time.Millisecond
	}
	if r.Announce == 0 {
		r.Announce = time.Second
	}
	if r.Refresh == 0 {
		r.Refresh = 5 * time.Second
	}
	packets := make(chan Packet, 16)
	readErr := make(chan error, 1)
	go func() {
		for {
			p, err := r.Conn.Read()
			if err != nil {
				readErr <- err
				return
			}
			select {
			case packets <- p:
			default: // busy: mDNS asks again
			}
		}
	}()
	defer r.Conn.Close()

	// Nothing to claim with until the TV is on a network.
	addrs := r.Addrs()
	for len(addrs) == 0 {
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(r.Refresh):
		case err := <-readErr:
			return err
		}
		addrs = r.Addrs()
	}
	r.join(addrs)

	for _, name := range r.candidates() {
		free, err := r.probe(ctx, name, addrs, packets, readErr)
		if err != nil || ctx.Err() != nil {
			return err
		}
		if free {
			r.mu.Lock()
			r.name = name
			r.mu.Unlock()
			r.save(name)
			r.Log.Printf("answering for %s.local", name)
			break
		}
		r.Log.Printf("%s.local is taken on this network, trying another name", name)
	}
	if r.Host() == "" {
		return fmt.Errorf("no free name")
	}
	name := r.Host()
	r.announce(addrs, ttlHost)
	nextAnnounce := time.After(r.Announce)

	refresh := time.NewTicker(r.Refresh)
	defer refresh.Stop()
	var defended time.Time
	for {
		select {
		case <-ctx.Done():
			r.announce(addrs, 0) // goodbye: caches drop the name now
			return nil
		case err := <-readErr:
			return err
		case <-nextAnnounce:
			r.announce(addrs, ttlHost)
		case <-refresh.C:
			now := r.Addrs()
			if !sameAddrs(now, addrs) {
				addrs = now
				r.join(addrs)
				r.Log.Printf("addresses changed: %s", describe(addrs))
				r.announce(addrs, ttlHost)
				nextAnnounce = time.After(r.Announce)
			}
		case p := <-packets:
			m, err := parse(p.Data)
			if err != nil || m.opcode != 0 || own(p.From, addrs) {
				continue
			}
			if m.response {
				// Someone else says they are us: say who we are, at most
				// once a second, so caches keep pointing at this TV.
				if claims(m, name, addrs) && time.Since(defended) > time.Second {
					defended = time.Now()
					r.Log.Printf("%s claims %s too, answering", p.From.IP, name)
					r.announce(addrs, ttlHost)
				}
				continue
			}
			r.answer(m, p, name, addrs)
		}
	}
}

// candidates: last boot's name first, then otto-tv, otto-tv-2...
func (r *Responder) candidates() []string {
	var names []string
	if r.State != "" {
		if b, err := os.ReadFile(r.State); err == nil {
			if n := strings.TrimSpace(string(b)); validName(n) {
				names = append(names, n)
			}
		}
	}
	for i := 1; i <= 20; i++ {
		n := r.Base
		if i > 1 {
			n = fmt.Sprintf("%s-%d", r.Base, i)
		}
		if !slices.Contains(names, n) {
			names = append(names, n)
		}
	}
	return names
}

func validName(n string) bool {
	if n == "" || len(n) > 63 {
		return false
	}
	for _, c := range n {
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
			return false
		}
	}
	return true
}

func (r *Responder) save(name string) {
	if r.State == "" {
		return
	}
	if b, err := os.ReadFile(r.State); err == nil && strings.TrimSpace(string(b)) == name {
		return
	}
	if err := os.WriteFile(r.State, []byte(name+"\n"), 0o644); err != nil {
		r.Log.Printf("%v", err)
	}
}

// probe asks three times whether anyone has name.local (RFC 6762 8.1) and
// reports whether it is free.
func (r *Responder) probe(ctx context.Context, name string, addrs map[int][]net.IP,
	packets <-chan Packet, readErr <-chan error) (bool, error) {
	host := name + ".local"
	bd := newBuilder(0, false)
	bd.question(host, typeANY, classIN|classQU)
	bd.a(2, host, classIN, ttlHost, all(addrs))
	probe := bd.bytes()
	for i := 0; i < 3; i++ {
		r.sendAll(probe, addrs)
		deadline := time.After(r.Probe)
	wait:
		for {
			select {
			case <-ctx.Done():
				return false, nil
			case err := <-readErr:
				return false, err
			case <-deadline:
				break wait
			case p := <-packets:
				m, err := parse(p.Data)
				if err != nil || !m.response || own(p.From, addrs) {
					continue
				}
				for _, rr := range append(m.answers, m.extra...) {
					if rr.name == host {
						return false, nil
					}
				}
			}
		}
	}
	return true, nil
}

// answer replies to a query for our name.
func (r *Responder) answer(m *message, p Packet, host string, addrs map[int][]net.IP) {
	ips := addrs[p.Ifindex]
	if len(ips) == 0 {
		ips = all(addrs)
	}
	var asked []question
	unicast := false
	for _, q := range m.qs {
		class := q.class &^ classQU
		if q.name != host || (class != classIN && class != classANY) {
			continue
		}
		if q.qtype == typeA || q.qtype == typeAAAA || q.qtype == typeANY {
			asked = append(asked, q)
			unicast = unicast || q.class&classQU != 0
		}
	}
	if len(asked) == 0 {
		return
	}
	// A resolver that isn't mDNS aware asks from another port and waits for
	// a plain DNS answer: its own id, the question back, short TTLs.
	legacy := p.From.Port != group.Port
	id, flush, ttl := uint16(0), uint16(classFlush), uint32(ttlHost)
	if legacy {
		id, flush, ttl = m.id, 0, ttlLegacy
	}
	bd := newBuilder(id, true)
	if legacy {
		for _, q := range asked {
			bd.question(q.name, q.qtype, q.class&^classQU)
		}
	}
	onlyAAAA := true
	for _, q := range asked {
		onlyAAAA = onlyAAAA && q.qtype == typeAAAA
	}
	if onlyAAAA {
		// No IPv6 address: say so, and give the IPv4 one on the way.
		bd.nsec(1, host, classIN|flush, ttl)
		bd.a(3, host, classIN|flush, ttl, ips)
	} else {
		bd.a(1, host, classIN|flush, ttl, ips)
		bd.nsec(3, host, classIN|flush, ttl)
	}
	to := group
	if legacy || unicast {
		to = p.From
	}
	if err := r.Conn.Send(bd.bytes(), to, p.Ifindex); err != nil {
		r.Log.Printf("answer to %s: %v", p.From, err)
	}
}

// announce tells the network our addresses (ttl 0: that we are leaving).
func (r *Responder) announce(addrs map[int][]net.IP, ttl uint32) {
	host := r.Host()
	for idx, ips := range addrs {
		bd := newBuilder(0, true)
		bd.a(1, host, classIN|classFlush, ttl, ips)
		bd.nsec(3, host, classIN|classFlush, ttl)
		if err := r.Conn.Send(bd.bytes(), group, idx); err != nil {
			r.Log.Printf("announce: %v", err)
		}
	}
}

func (r *Responder) sendAll(b []byte, addrs map[int][]net.IP) {
	for idx := range addrs {
		if err := r.Conn.Send(b, group, idx); err != nil {
			r.Log.Printf("send: %v", err)
		}
	}
}

func (r *Responder) join(addrs map[int][]net.IP) {
	for idx := range addrs {
		if err := r.Conn.Join(idx); err != nil {
			r.Log.Printf("join on interface %d: %v", idx, err)
		}
	}
}

// claims reports whether a response says host is at an address not ours.
func claims(m *message, host string, addrs map[int][]net.IP) bool {
	mine := all(addrs)
	for _, rr := range append(m.answers, m.extra...) {
		if rr.name != host || rr.rtype != typeA || rr.ttl == 0 || len(rr.data) != 4 {
			continue
		}
		if !slices.ContainsFunc(mine, func(ip net.IP) bool { return ip.Equal(net.IP(rr.data)) }) {
			return true
		}
	}
	return false
}

// own reports whether a packet came from this machine (multicast loops
// back what we send).
func own(from *net.UDPAddr, addrs map[int][]net.IP) bool {
	if from == nil {
		return false
	}
	return slices.ContainsFunc(all(addrs), func(ip net.IP) bool { return ip.Equal(from.IP) })
}

func all(addrs map[int][]net.IP) []net.IP {
	var ips []net.IP
	for _, idx := range sortedKeys(addrs) {
		ips = append(ips, addrs[idx]...)
	}
	return ips
}

func sortedKeys(addrs map[int][]net.IP) []int {
	keys := make([]int, 0, len(addrs))
	for k := range addrs {
		keys = append(keys, k)
	}
	slices.Sort(keys)
	return keys
}

func sameAddrs(a, b map[int][]net.IP) bool {
	return describe(a) == describe(b)
}

func describe(addrs map[int][]net.IP) string {
	var parts []string
	for _, idx := range sortedKeys(addrs) {
		for _, ip := range addrs[idx] {
			parts = append(parts, fmt.Sprintf("%s (if %d)", ip, idx))
		}
	}
	if len(parts) == 0 {
		return "none"
	}
	return strings.Join(parts, ", ")
}

// Interfaces lists the IPv4 addresses of the interfaces that are up and do
// multicast, leaving out loopback and link-local (169.254) ones.
func Interfaces() map[int][]net.IP {
	out := map[int][]net.IP{}
	ifaces, err := net.Interfaces()
	if err != nil {
		return out
	}
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 || ifc.Flags&net.FlagMulticast == 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			ipn, ok := a.(*net.IPNet)
			if !ok || ipn.IP.To4() == nil || ipn.IP.IsLinkLocalUnicast() {
				continue
			}
			out[ifc.Index] = append(out[ifc.Index], ipn.IP.To4())
		}
	}
	return out
}
