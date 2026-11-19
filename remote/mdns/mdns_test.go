package mdns

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

// --- Fake network ---------------------------------------------------------------

type sent struct {
	m       *message
	to      *net.UDPAddr
	ifindex int
}

type fakeConn struct {
	in     chan Packet
	out    chan sent
	closed chan struct{}
	once   sync.Once
	mu     sync.Mutex
	joined []int
	// onSend runs on every packet we send, before it is recorded.
	onSend func(m *message, to *net.UDPAddr)
}

func newFake() *fakeConn {
	return &fakeConn{in: make(chan Packet, 16), out: make(chan sent, 64), closed: make(chan struct{})}
}

func (f *fakeConn) Read() (Packet, error) {
	select {
	case p := <-f.in:
		return p, nil
	case <-f.closed:
		return Packet{}, io.EOF
	}
}

func (f *fakeConn) Send(b []byte, to *net.UDPAddr, ifindex int) error {
	m, err := parse(b)
	if err != nil {
		return err
	}
	if f.onSend != nil {
		f.onSend(m, to)
	}
	f.out <- sent{m, to, ifindex}
	return nil
}

func (f *fakeConn) Join(ifindex int) error {
	f.mu.Lock()
	f.joined = append(f.joined, ifindex)
	f.mu.Unlock()
	return nil
}

func (f *fakeConn) Close() error {
	f.once.Do(func() { close(f.closed) })
	return nil
}

var (
	tvIP    = net.IPv4(192, 168, 0, 20).To4()
	phoneIP = net.IPv4(192, 168, 0, 31).To4()
	otherTV = net.IPv4(192, 168, 0, 50).To4()
)

const wifi = 3 // the TV's interface index in these tests

func start(t *testing.T, f *fakeConn, state string) (*Responder, context.CancelFunc, chan error) {
	t.Helper()
	r := &Responder{
		Base: "otto-tv", State: state, Conn: f,
		Addrs: func() map[int][]net.IP { return map[int][]net.IP{wifi: {tvIP}} },
		Log:   log.New(io.Discard, "", 0),
		Probe: 5 * time.Millisecond, Announce: 20 * time.Millisecond, Refresh: time.Hour,
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	t.Cleanup(func() {
		cancel()
		<-done
	})
	return r, cancel, done
}

// next returns the next packet we sent that matches ok, failing after 2 s.
func next(t *testing.T, f *fakeConn, what string, ok func(sent) bool) sent {
	t.Helper()
	deadline := time.After(2 * time.Second)
	for {
		select {
		case s := <-f.out:
			if ok(s) {
				return s
			}
		case <-deadline:
			t.Fatalf("never sent %s", what)
		}
	}
}

func isAnnounce(s sent) bool { return s.m.response && len(s.m.qs) == 0 && s.to == group }

func claimed(t *testing.T, f *fakeConn) sent {
	t.Helper()
	return next(t, f, "an announcement", isAnnounce)
}

func query(id uint16, name string, qtype, class uint16) []byte {
	bd := newBuilder(id, false)
	bd.question(name, qtype, class)
	return bd.bytes()
}

func fromPhone(port int) *net.UDPAddr { return &net.UDPAddr{IP: phoneIP, Port: port} }

func aRecords(m *message, section []record) []string {
	var out []string
	for _, r := range section {
		if r.rtype == typeA {
			out = append(out, r.name+"="+net.IP(r.data).String())
		}
	}
	return out
}

// --- Tests --------------------------------------------------------------------

func TestClaimsTheNameAndAnswers(t *testing.T) {
	f := newFake()
	r, _, _ := start(t, f, "")

	// Three probes first, with the address we will claim (RFC 6762 8.1).
	for i := 0; i < 3; i++ {
		p := next(t, f, "a probe", func(s sent) bool { return !s.m.response })
		if len(p.m.qs) != 1 || p.m.qs[0].name != "otto-tv.local" || p.m.qs[0].qtype != typeANY {
			t.Fatalf("probe %d asks %+v", i, p.m.qs)
		}
		if got := aRecords(p.m, p.m.auth); len(got) != 1 || got[0] != "otto-tv.local=192.168.0.20" {
			t.Fatalf("probe %d proposes %v", i, got)
		}
	}
	a := claimed(t, f)
	if got := aRecords(a.m, a.m.answers); len(got) != 1 || got[0] != "otto-tv.local=192.168.0.20" {
		t.Fatalf("announced %v", got)
	}
	if a.m.answers[0].class != classIN|classFlush || a.m.answers[0].ttl != ttlHost {
		t.Errorf("announcement class %#x ttl %d", a.m.answers[0].class, a.m.answers[0].ttl)
	}
	if r.Host() != "otto-tv.local" {
		t.Errorf("Host() = %q", r.Host())
	}
	f.mu.Lock()
	joined := f.joined
	f.mu.Unlock()
	if len(joined) == 0 || joined[0] != wifi {
		t.Errorf("joined %v, want the Wi-Fi interface", joined)
	}

	// A phone asks, as iPhones and Androids do: multicast, from port 5353,
	// any letter case.
	f.in <- Packet{Data: query(0, "Otto-TV.local", typeA, classIN), From: fromPhone(5353), Ifindex: wifi}
	ans := next(t, f, "an answer", func(s sent) bool { return s.m.response && len(s.m.answers) > 0 })
	if ans.to != group || ans.ifindex != wifi {
		t.Errorf("answer went to %v on %d, want the group on the Wi-Fi", ans.to, ans.ifindex)
	}
	if got := aRecords(ans.m, ans.m.answers); len(got) != 1 || got[0] != "otto-tv.local=192.168.0.20" {
		t.Errorf("answered %v", got)
	}
	if len(ans.m.extra) != 1 || ans.m.extra[0].rtype != typeNSEC {
		t.Errorf("no NSEC in the additional section: %+v", ans.m.extra)
	}
}

func TestOtherNamesAreNotOurs(t *testing.T) {
	f := newFake()
	start(t, f, "")
	claimed(t, f)
	claimed(t, f) // the second announcement: from here on, only answers
	for _, name := range []string{"otto-tv-2.local", "printer.local", "otto-tv.local.example"} {
		f.in <- Packet{Data: query(0, name, typeA, classIN), From: fromPhone(5353), Ifindex: wifi}
	}
	// And one for us, to know the others were read and skipped.
	f.in <- Packet{Data: query(0, "otto-tv.local", typeA, classIN), From: fromPhone(5353), Ifindex: wifi}
	ans := next(t, f, "an answer", func(sent) bool { return true })
	for _, r := range ans.m.answers {
		if r.name != "otto-tv.local" {
			t.Errorf("answered for %s", r.name)
		}
	}
	select {
	case s := <-f.out:
		t.Errorf("a second answer: %+v", s.m.answers)
	case <-time.After(50 * time.Millisecond):
	}
}

func TestTakenNameMovesOn(t *testing.T) {
	f := newFake()
	var once sync.Once
	// Another Otto TV already answers for otto-tv.local.
	f.onSend = func(m *message, _ *net.UDPAddr) {
		if !m.response && len(m.qs) == 1 && m.qs[0].name == "otto-tv.local" {
			once.Do(func() {
				bd := newBuilder(0, true)
				bd.a(1, "otto-tv.local", classIN|classFlush, ttlHost, []net.IP{otherTV})
				f.in <- Packet{Data: bd.bytes(), From: &net.UDPAddr{IP: otherTV, Port: 5353}, Ifindex: wifi}
			})
		}
	}
	state := filepath.Join(t.TempDir(), "mdns-name")
	r, _, _ := start(t, f, state)
	a := claimed(t, f)
	if got := aRecords(a.m, a.m.answers); len(got) != 1 || got[0] != "otto-tv-2.local=192.168.0.20" {
		t.Fatalf("announced %v, want otto-tv-2.local", got)
	}
	if r.Host() != "otto-tv-2.local" {
		t.Errorf("Host() = %q", r.Host())
	}
	if b, _ := os.ReadFile(state); string(b) != "otto-tv-2\n" {
		t.Errorf("saved %q", b)
	}
}

func TestLastBootsNameFirst(t *testing.T) {
	state := filepath.Join(t.TempDir(), "mdns-name")
	os.WriteFile(state, []byte("otto-tv-2\n"), 0o644)
	f := newFake()
	start(t, f, state)
	p := next(t, f, "a probe", func(s sent) bool { return !s.m.response })
	if p.m.qs[0].name != "otto-tv-2.local" {
		t.Errorf("first probe for %s, want last boot's otto-tv-2.local", p.m.qs[0].name)
	}
}

func TestBadStateIsIgnored(t *testing.T) {
	state := filepath.Join(t.TempDir(), "mdns-name")
	os.WriteFile(state, []byte("../evil name\n"), 0o644)
	f := newFake()
	start(t, f, state)
	p := next(t, f, "a probe", func(s sent) bool { return !s.m.response })
	if p.m.qs[0].name != "otto-tv.local" {
		t.Errorf("first probe for %s", p.m.qs[0].name)
	}
}

func TestLegacyUnicastQuery(t *testing.T) {
	f := newFake()
	start(t, f, "")
	claimed(t, f)
	// A plain resolver (not mDNS aware) asks from a random port.
	f.in <- Packet{Data: query(0xBEEF, "otto-tv.local", typeA, classIN), From: fromPhone(40000), Ifindex: wifi}
	ans := next(t, f, "a unicast answer", func(s sent) bool { return s.to != group })
	if ans.to.Port != 40000 || !ans.to.IP.Equal(phoneIP) {
		t.Errorf("answer went to %v", ans.to)
	}
	if ans.m.id != 0xBEEF || len(ans.m.qs) != 1 || ans.m.qs[0].name != "otto-tv.local" {
		t.Errorf("id %#x, questions %+v: want the query's", ans.m.id, ans.m.qs)
	}
	if len(ans.m.answers) == 0 || ans.m.answers[0].ttl != ttlLegacy || ans.m.answers[0].class != classIN {
		t.Errorf("answers %+v: want TTL %d, no cache-flush bit", ans.m.answers, ttlLegacy)
	}
}

func TestUnicastRequested(t *testing.T) {
	f := newFake()
	start(t, f, "")
	claimed(t, f)
	f.in <- Packet{Data: query(0, "otto-tv.local", typeA, classIN|classQU), From: fromPhone(5353), Ifindex: wifi}
	ans := next(t, f, "a unicast answer", func(s sent) bool { return s.to != group })
	if !ans.to.IP.Equal(phoneIP) || ans.to.Port != 5353 || ans.m.id != 0 {
		t.Errorf("answer to %v id %d", ans.to, ans.m.id)
	}
}

func TestIPv6QuestionGetsNSEC(t *testing.T) {
	f := newFake()
	start(t, f, "")
	claimed(t, f)
	f.in <- Packet{Data: query(0, "otto-tv.local", typeAAAA, classIN), From: fromPhone(5353), Ifindex: wifi}
	ans := next(t, f, "an answer", func(s sent) bool { return s.m.response && len(s.m.answers) == 1 && s.m.answers[0].rtype == typeNSEC })
	if got := aRecords(ans.m, ans.m.extra); len(got) != 1 {
		t.Errorf("no A record on the way: %v", got)
	}
	// The bitmap says: only A.
	if d := ans.m.answers[0].data; !bytes.HasSuffix(d, []byte{0, 1, 0x40}) {
		t.Errorf("NSEC data % x", d)
	}
}

func TestDefendsTheName(t *testing.T) {
	f := newFake()
	start(t, f, "")
	claimed(t, f)
	claimed(t, f) // the second announcement
	bd := newBuilder(0, true)
	bd.a(1, "otto-tv.local", classIN|classFlush, ttlHost, []net.IP{otherTV})
	f.in <- Packet{Data: bd.bytes(), From: &net.UDPAddr{IP: otherTV, Port: 5353}, Ifindex: wifi}
	a := claimed(t, f)
	if got := aRecords(a.m, a.m.answers); len(got) != 1 || got[0] != "otto-tv.local=192.168.0.20" {
		t.Errorf("defended with %v", got)
	}
}

func TestOwnPacketsAreIgnored(t *testing.T) {
	f := newFake()
	start(t, f, "")
	claimed(t, f)
	claimed(t, f)
	// Multicast loops back our own query... nothing to answer.
	f.in <- Packet{Data: query(0, "otto-tv.local", typeA, classIN), From: &net.UDPAddr{IP: tvIP, Port: 5353}, Ifindex: wifi}
	select {
	case s := <-f.out:
		t.Errorf("answered our own packet: %+v", s.m)
	case <-time.After(50 * time.Millisecond):
	}
}

func TestGoodbye(t *testing.T) {
	f := newFake()
	_, cancel, done := start(t, f, "")
	claimed(t, f)
	claimed(t, f)
	cancel()
	bye := next(t, f, "a goodbye", isAnnounce)
	if len(bye.m.answers) == 0 || bye.m.answers[0].ttl != 0 {
		t.Errorf("goodbye %+v: want TTL 0", bye.m.answers)
	}
	if err := <-done; err != nil {
		t.Errorf("Run: %v", err)
	}
	done <- nil // for the cleanup
}

func TestWaitsForANetwork(t *testing.T) {
	f := newFake()
	var mu sync.Mutex
	var addrs map[int][]net.IP
	r := &Responder{
		Base: "otto-tv", Conn: f, Log: log.New(io.Discard, "", 0),
		Addrs:   func() map[int][]net.IP { mu.Lock(); defer mu.Unlock(); return addrs },
		Probe:   5 * time.Millisecond,
		Refresh: 10 * time.Millisecond,
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go r.Run(ctx)
	select {
	case s := <-f.out:
		t.Fatalf("sent %+v before the TV had an address", s.m)
	case <-time.After(50 * time.Millisecond):
	}
	mu.Lock()
	addrs = map[int][]net.IP{wifi: {tvIP}}
	mu.Unlock()
	next(t, f, "a probe once online", func(s sent) bool { return !s.m.response })
}

func TestParseCompressedNames(t *testing.T) {
	// Two questions, the second pointing at the first's "local".
	b := []byte{0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0,
		7, 'o', 't', 't', 'o', '-', 't', 'v', 5, 'l', 'o', 'c', 'a', 'l', 0, 0, 1, 0, 1,
		3, 'f', 'o', 'o', 0xC0, 20, 0, 1, 0, 1}
	m, err := parse(b)
	if err != nil {
		t.Fatal(err)
	}
	if len(m.qs) != 2 || m.qs[0].name != "otto-tv.local" || m.qs[1].name != "foo.local" {
		t.Errorf("questions %+v", m.qs)
	}
}

func TestParseRejectsGarbage(t *testing.T) {
	loop := []byte{0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xC0, 12, 0, 1, 0, 1}
	for _, b := range [][]byte{nil, {1, 2, 3}, loop, query(0, "otto-tv.local", typeA, classIN)[:20]} {
		if _, err := parse(b); err == nil {
			t.Errorf("parsed % x", b)
		}
	}
}

// The real socket, on loopback: a legacy query gets a unicast answer.
func TestSocket(t *testing.T) {
	c, err := Listen()
	if err != nil {
		t.Skipf("port 5353: %v", err)
	}
	lo, err := net.InterfaceByName("lo")
	if err != nil {
		c.Close()
		t.Skip("no lo")
	}
	r := &Responder{
		Base: "otto-tv", Conn: c, Log: log.New(io.Discard, "", 0),
		// Not 127.0.0.1: packets from our own addresses are ignored.
		Addrs:   func() map[int][]net.IP { return map[int][]net.IP{lo.Index: {net.IPv4(10, 9, 8, 7)}} },
		Probe:   5 * time.Millisecond,
		Refresh: time.Hour,
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Run(ctx) }()
	defer func() {
		cancel()
		<-done
	}()
	for i := 0; i < 200 && r.Host() == ""; i++ {
		time.Sleep(5 * time.Millisecond)
	}
	if r.Host() == "" {
		t.Fatal("never claimed a name")
	}
	q, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 5353})
	if err != nil {
		t.Fatal(err)
	}
	defer q.Close()
	q.Write(query(7, r.Host(), typeA, classIN))
	q.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 1500)
	for {
		n, err := q.Read(buf)
		if errors.Is(err, os.ErrDeadlineExceeded) {
			t.Fatal("no answer")
		}
		if err != nil {
			t.Fatal(err)
		}
		m, err := parse(buf[:n])
		if err != nil || m.id != 7 {
			continue
		}
		if got := aRecords(m, m.answers); len(got) != 1 || got[0] != r.Host()+"=10.9.8.7" {
			t.Errorf("answered %v", got)
		}
		return
	}
}
