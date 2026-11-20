package server

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

type fakePeers struct{ allow bool }

func (f fakePeers) Allowed(string, int) (bool, error) { return f.allow, nil }

type fakeSystem struct {
	mu      sync.Mutex
	calls   []string
	audioTo error
	power   chan string
}

func (f *fakeSystem) SetAudio(to string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, "audio "+to)
	return f.audioTo
}
func (f *fakeSystem) SSID() string { return "Sala 5G" }
func (f *fakeSystem) Power(action string) error {
	f.power <- action
	return nil
}
func (f *fakeSystem) Details() map[string]any {
	return map[string]any{"cpuTemp": 58.0, "graphics": "hardware", "adb": 5555}
}

type fakeAdBlock struct {
	state string
	err   error
	sets  []bool
}

func (f *fakeAdBlock) State() string { return f.state }
func (f *fakeAdBlock) Set(on bool) error {
	f.sets = append(f.sets, on)
	if f.err != nil {
		return f.err
	}
	f.state = map[bool]string{true: "on", false: "off"}[on]
	return nil
}

func appServer(t *testing.T) (*Server, *fakeSystem, *fakeLaptop) {
	s, _ := newTestServer(t)
	sys := &fakeSystem{power: make(chan string, 1)}
	lap := &fakeLaptop{}
	s.Peers = fakePeers{allow: true}
	s.System = sys
	s.Laptop = lap
	s.Audio = func() string { return "tv" }
	return s, sys, lap
}

func post(h http.Handler, path, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest("POST", path, strings.NewReader(body))
	req.RemoteAddr = "127.0.0.1:5000"
	req.Host = "127.0.0.1:8686"
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	return w
}

func decode(t *testing.T, w *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &m); err != nil {
		t.Fatalf("not JSON (%d): %s", w.Code, w.Body)
	}
	return m
}

func TestAppRoutesOnlyForTheApp(t *testing.T) {
	s, _, _ := appServer(t)
	s.Peers = fakePeers{allow: false}
	h := s.Handler()
	for _, path := range []string{"/app/status", "/app/details", "/app/pair"} {
		if w := get(h, "127.0.0.1:5000", "127.0.0.1:8686", path); w.Code != http.StatusForbidden {
			t.Errorf("%s from another app: %d", path, w.Code)
		}
	}
	if w := post(h, "/app/power", `{"action":"reboot"}`); w.Code != http.StatusForbidden {
		t.Errorf("power from another app: %d", w.Code)
	}
	s.Peers = nil
	if w := get(s.Handler(), "127.0.0.1:5000", "127.0.0.1:8686", "/app/status"); w.Code != http.StatusForbidden {
		t.Errorf("no peer check: %d", w.Code)
	}
	// DNS rebinding.
	s.Peers = fakePeers{allow: true}
	if w := get(s.Handler(), "127.0.0.1:5000", "evil.example:8686", "/app/pair"); w.Code != http.StatusForbidden {
		t.Errorf("rebinding: %d", w.Code)
	}
}

func TestAppStatus(t *testing.T) {
	s, _, lap := appServer(t)
	lap.off = true
	h := s.Handler()
	m := decode(t, get(h, "127.0.0.1:5000", "127.0.0.1:8686", "/app/status"))
	if m["audio"] != "tv" || m["laptop"] != "off" || m["phones"] != 0.0 || m["paired"] != false {
		t.Errorf("status: %v", m)
	}
	if _, ok := m["ssid"]; ok {
		t.Error("ssid without asking for it")
	}
	m = decode(t, get(h, "127.0.0.1:5000", "127.0.0.1:8686", "/app/status?wifi=1"))
	if m["ssid"] != "Sala 5G" {
		t.Errorf("ssid: %v", m)
	}
	if _, ok := m["adblock"]; ok {
		t.Error("adblock without ad blocking")
	}
	s.AdBlock = &fakeAdBlock{state: "paused"}
	m = decode(t, get(s.Handler(), "127.0.0.1:5000", "127.0.0.1:8686", "/app/status?wifi=1"))
	if m["adblock"] != "paused" {
		t.Errorf("adblock: %v", m)
	}
	m = decode(t, get(s.Handler(), "127.0.0.1:5000", "127.0.0.1:8686", "/app/status"))
	if _, ok := m["adblock"]; ok {
		t.Error("adblock without the panel asking")
	}
}

func TestAppAdBlock(t *testing.T) {
	s, _, _ := appServer(t)
	if w := post(s.Handler(), "/app/adblock", `{"on":true}`); w.Code != http.StatusServiceUnavailable {
		t.Errorf("without ad blocking: %d", w.Code)
	}
	ab := &fakeAdBlock{state: "on"}
	s.AdBlock = ab
	h := s.Handler()
	w := post(h, "/app/adblock", `{"on":false}`)
	if m := decode(t, w); w.Code != http.StatusOK || m["adblock"] != "off" {
		t.Errorf("off: %d %v", w.Code, m)
	}
	if w := post(h, "/app/adblock", `{"on":"yes"}`); w.Code != http.StatusBadRequest {
		t.Errorf("bad body: %d", w.Code)
	}
	ab.err = errors.New("settings: no service")
	if w := post(h, "/app/adblock", `{"on":true}`); w.Code != http.StatusInternalServerError {
		t.Errorf("failure: %d", w.Code)
	}
	if len(ab.sets) != 2 || ab.sets[0] || !ab.sets[1] {
		t.Errorf("sets: %v", ab.sets)
	}
	s.Peers = fakePeers{allow: false}
	if w := post(s.Handler(), "/app/adblock", `{"on":false}`); w.Code != http.StatusForbidden {
		t.Errorf("from another app: %d", w.Code)
	}
}

func TestAppAudio(t *testing.T) {
	s, sys, _ := appServer(t)
	h := s.Handler()
	if w := post(h, "/app/audio", `{"to":"notebook"}`); w.Code != http.StatusOK {
		t.Fatalf("audio: %d %s", w.Code, w.Body)
	}
	if strings.Join(sys.calls, ",") != "audio notebook" {
		t.Errorf("calls: %v", sys.calls)
	}
	if w := post(h, "/app/audio", `{"to":"bluetooth"}`); w.Code != http.StatusBadRequest {
		t.Errorf("unknown output: %d", w.Code)
	}
	sys.audioTo = ErrNoTVSound
	if w := post(h, "/app/audio", `{"to":"tv"}`); w.Code != http.StatusConflict {
		t.Errorf("no TV sound: %d", w.Code)
	}
	sys.audioTo = errors.New("setprop failed")
	if w := post(h, "/app/audio", `{"to":"tv"}`); w.Code != http.StatusInternalServerError {
		t.Errorf("failure: %d", w.Code)
	}
}

func TestAppScreen(t *testing.T) {
	s, _, lap := appServer(t)
	var notices []string
	s.Notify = func(kind, app string) { notices = append(notices, kind) }
	h := s.Handler()
	m := decode(t, post(h, "/app/screen", `{"on":false}`))
	if m["laptop"] != "off" || !lap.off {
		t.Errorf("off: %v", m)
	}
	// Asking for what it already is changes nothing.
	m = decode(t, post(h, "/app/screen", `{"on":false}`))
	if m["laptop"] != "off" {
		t.Errorf("off again: %v", m)
	}
	m = decode(t, post(h, "/app/screen", `{"on":true}`))
	if m["laptop"] != "on" {
		t.Errorf("on: %v", m)
	}
	time.Sleep(10 * time.Millisecond)
	if len(notices) != 0 {
		t.Errorf("the panel shows its own notice, got %v", notices)
	}
	s.Laptop = nil
	if w := post(h, "/app/screen", `{"on":false}`); w.Code != http.StatusConflict {
		t.Errorf("desktop: %d", w.Code)
	}
}

func TestAppPower(t *testing.T) {
	s, sys, _ := appServer(t)
	powerDelay = 0
	h := s.Handler()
	if w := post(h, "/app/power", `{"action":"halt"}`); w.Code != http.StatusBadRequest {
		t.Errorf("unknown action: %d", w.Code)
	}
	if w := post(h, "/app/power", `{"action":"reboot"}`); w.Code != http.StatusOK {
		t.Fatalf("reboot: %d", w.Code)
	}
	select {
	case a := <-sys.power:
		if a != "reboot" {
			t.Errorf("did %q", a)
		}
	case <-time.After(time.Second):
		t.Error("power action never ran")
	}
}

func TestAppDetailsAndPair(t *testing.T) {
	s, _, _ := appServer(t)
	h := s.Handler()
	m := decode(t, get(h, "127.0.0.1:5000", "127.0.0.1:8686", "/app/details"))
	if m["ip"] != "192.168.0.42" || m["cpuTemp"] != 58.0 || m["adb"] != 5555.0 {
		t.Errorf("details: %v", m)
	}
	m = decode(t, get(h, "127.0.0.1:5000", "127.0.0.1:8686", "/app/pair"))
	rows, _ := m["qr"].([]any)
	if m["url"] != "192.168.0.42:8686" || len(rows) < 21 {
		t.Fatalf("pair: %v", m)
	}
	for _, r := range rows {
		if row := r.(string); len(row) != len(rows) || strings.Trim(row, "01") != "" {
			t.Fatalf("bad row %q", row)
		}
	}
	// The QR code is the same one the "Connect phone" screen shows.
	want, _ := QRRows("http://192.168.0.42:8686/#t=" + s.Pairing.Token())
	if rows[0].(string) != want[0] || len(want) != len(rows) {
		t.Error("QR code differs from the token's")
	}
	s.LANIP = func() string { return "" }
	m = decode(t, get(h, "127.0.0.1:5000", "127.0.0.1:8686", "/app/pair"))
	if m["url"] != "" {
		t.Errorf("offline pair: %v", m)
	}
}

// notices collects what the server shows on the TV.
func notices(s *Server) chan string {
	ch := make(chan string, 8)
	s.Notify = func(kind, app string) { ch <- kind }
	return ch
}

func nextNotice(t *testing.T, ch chan string) string {
	t.Helper()
	select {
	case k := <-ch:
		return k
	case <-time.After(time.Second):
		return ""
	}
}

func TestNewPhoneShowsANotice(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	s, _ := newTestServer(t)
	ch := notices(s)
	ts := httptest.NewServer(s.Handler())
	defer ts.Close()
	dial := func(fresh bool) *websocket.Conn {
		c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(ts.URL, "http")+"/ws", nil)
		if err != nil {
			t.Fatal(err)
		}
		wsjson.Write(ctx, c, Message{Type: "auth", Token: s.Pairing.Token(), Fresh: fresh})
		var ready map[string]any
		wsjson.Read(ctx, c, &ready)
		return c
	}
	c := dial(false)
	defer c.CloseNow()
	select {
	case k := <-ch:
		t.Errorf("a phone coming back showed %q", k)
	case <-time.After(100 * time.Millisecond):
	}
	c2 := dial(true)
	defer c2.CloseNow()
	if k := nextNotice(t, ch); k != "phone" {
		t.Errorf("new phone: notice %q", k)
	}
}

func TestPhoneScreenToggleShowsANotice(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	s, _ := newTestServer(t)
	s.Laptop = &fakeLaptop{}
	ch := notices(s)
	c, _ := session(t, ctx, s)
	for _, want := range []string{"screen_off", "screen_on"} {
		wsjson.Write(ctx, c, Message{Type: "laptop"})
		var reply map[string]any
		wsjson.Read(ctx, c, &reply)
		if k := nextNotice(t, ch); k != want {
			t.Errorf("notice %q, want %q", k, want)
		}
	}
}

func TestPhoneOpensTheQuickPanel(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	s, _ := newTestServer(t)
	opened := make(chan bool, 2)
	s.Panel = func() { opened <- true }
	c, _ := session(t, ctx, s)
	wsjson.Write(ctx, c, Message{Type: "panel"})
	select {
	case <-opened:
	case <-time.After(time.Second):
		t.Fatal("panel not opened")
	}
	// Without the launcher's app, the phone is told.
	s2, _ := newTestServer(t)
	c2, _ := session(t, ctx, s2)
	wsjson.Write(ctx, c2, Message{Type: "panel"})
	var reply map[string]any
	wsjson.Read(ctx, c2, &reply)
	if reply["error"] != "no_panel" {
		t.Errorf("reply %v", reply)
	}
}
