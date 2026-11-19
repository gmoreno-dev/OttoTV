package server

import (
	"context"
	"encoding/json"
	"html/template"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	u "otto/remote/uinput"
)

func newTestServer(t *testing.T) (*Server, *recorder) {
	t.Helper()
	p, err := LoadPairing(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	r := &recorder{}
	return &Server{
		Name:    "Test TV",
		Port:    8686,
		Pairing: p,
		Keys:    r,
		Pointer: r,
		Static:  fstest.MapFS{"index.html": {Data: []byte("remote")}},
		TVPage:  template.Must(template.New("tv").Parse(`{{.PIN}} {{.URL}} {{.QR}} {{index .Texts "title"}}`)),
		LANIP:   func() string { return "192.168.0.42" },
	}, r
}

func get(h http.Handler, remote, host, path string, header ...string) *httptest.ResponseRecorder {
	req := httptest.NewRequest("GET", path, nil)
	req.RemoteAddr = remote
	req.Host = host
	for i := 0; i+1 < len(header); i += 2 {
		req.Header.Set(header[i], header[i+1])
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	return w
}

func TestPairingScreenIsOnlyForTheTV(t *testing.T) {
	s, _ := newTestServer(t)
	h := s.Handler()

	if w := get(h, "192.168.0.50:5000", "192.168.0.42:8686", "/tv"); w.Code != http.StatusForbidden {
		t.Errorf("phone on the network got %d, want 403", w.Code)
	}
	// DNS rebinding: a page in the TV's own browser, under someone else's name.
	if w := get(h, "127.0.0.1:5000", "evil.example:8686", "/tv"); w.Code != http.StatusForbidden {
		t.Errorf("rebinding request got %d, want 403", w.Code)
	}

	w := get(h, "127.0.0.1:5000", "127.0.0.1:8686", "/tv", "Accept-Language", "pt-BR,pt;q=0.9")
	if w.Code != http.StatusOK {
		t.Fatalf("TV got %d", w.Code)
	}
	body := w.Body.String()
	for _, want := range []string{"192.168.0.42:8686", "<svg", "Conecte seu celular"} {
		if !strings.Contains(body, want) {
			t.Errorf("pairing screen lacks %q:\n%s", want, body)
		}
	}
	if strings.Contains(body, s.Pairing.Token()) {
		t.Error("token printed as text: it should only travel inside the QR code")
	}
}

func TestPairWithPIN(t *testing.T) {
	s, _ := newTestServer(t)
	ts := httptest.NewServer(s.Handler())
	defer ts.Close()

	post := func(pin string) (int, map[string]string) {
		res, err := http.Post(ts.URL+"/api/pair", "application/json", strings.NewReader(`{"pin":"`+pin+`"}`))
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var body map[string]string
		json.NewDecoder(res.Body).Decode(&body)
		return res.StatusCode, body
	}

	if code, body := post("123456"); code != http.StatusForbidden || body["error"] != "closed" {
		t.Errorf("before the screen opened: %d %v", code, body)
	}
	pin, _ := s.Pairing.Open()
	if code, body := post(pin); code != http.StatusOK || body["token"] != s.Pairing.Token() {
		t.Errorf("right PIN: %d %v", code, body)
	}
}

func TestPhoneDrivesTheKeyboard(t *testing.T) {
	s, rec := newTestServer(t)
	ts := httptest.NewServer(s.Handler())
	defer ts.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"

	// Wrong token: turned away.
	c, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	wsjson.Write(ctx, c, Message{Type: "auth", Token: "guess"})
	var reply map[string]any
	wsjson.Read(ctx, c, &reply)
	if reply["error"] != "unauthorized" {
		t.Errorf("wrong token reply = %v", reply)
	}
	c.CloseNow()

	// Right token: commands reach the virtual keyboard.
	c, _, err = websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	wsjson.Write(ctx, c, Message{Type: "auth", Token: s.Pairing.Token()})
	reply = nil
	if err := wsjson.Read(ctx, c, &reply); err != nil || reply["type"] != "ready" || reply["name"] != "Test TV" {
		t.Fatalf("ready = %v, %v", reply, err)
	}
	wsjson.Write(ctx, c, Message{Type: "key", Key: "home"})
	wsjson.Write(ctx, c, Message{Type: "ping"})
	reply = nil
	wsjson.Read(ctx, c, &reply) // pong: the key above has been handled

	got := rec.events()
	want := []string{k(u.KeyHomepage, true), k(u.KeyHomepage, false)}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Errorf("events = %v, want %v", got, want)
	}
	status := get(s.Handler(), "127.0.0.1:1", "localhost:8686", "/tv/status")
	if !strings.Contains(status.Body.String(), `"phones":1`) {
		t.Errorf("status doesn't count the phone: %s", status.Body.String())
	}
}

func TestQRSVG(t *testing.T) {
	svg, err := QRSVG("http://192.168.0.42:8686/#t=" + strings.Repeat("ab", 32))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(svg, "<svg") || !strings.Contains(svg, `<path d="M`) {
		t.Errorf("not an SVG QR code: %.80s", svg)
	}
}

func TestHeadersAllowOnlyThisServer(t *testing.T) {
	s, _ := newTestServer(t)
	w := get(s.Handler(), "192.168.0.50:5000", "192.168.0.42:8686", "/")
	csp := w.Header().Get("Content-Security-Policy")
	if !strings.Contains(csp, "connect-src 'self' ws://192.168.0.42:8686;") {
		t.Errorf("CSP = %q", csp)
	}
	body, _ := io.ReadAll(w.Body)
	if string(body) != "remote" {
		t.Errorf("index = %q", body)
	}
}

type fakeLaptop struct{ off, missing bool }

func (l *fakeLaptop) Available() bool { return !l.missing }
func (l *fakeLaptop) Off() bool       { return l.off }
func (l *fakeLaptop) Toggle() (bool, error) {
	l.off = !l.off
	return l.off, nil
}

// session pairs a phone with s and returns its connection and ready message.
func session(t *testing.T, ctx context.Context, s *Server) (*websocket.Conn, map[string]any) {
	t.Helper()
	ts := httptest.NewServer(s.Handler())
	t.Cleanup(ts.Close)
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(ts.URL, "http")+"/ws", nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.CloseNow() })
	wsjson.Write(ctx, c, Message{Type: "auth", Token: s.Pairing.Token()})
	var ready map[string]any
	if err := wsjson.Read(ctx, c, &ready); err != nil {
		t.Fatal(err)
	}
	return c, ready
}

func TestPhoneTurnsTheLaptopScreenDark(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	s, _ := newTestServer(t)
	laptop := &fakeLaptop{}
	s.Laptop = laptop
	c, ready := session(t, ctx, s)
	if ready["laptop"] != "on" {
		t.Errorf("ready says laptop = %v, want on", ready["laptop"])
	}
	for _, want := range []string{"off", "on"} {
		wsjson.Write(ctx, c, Message{Type: "laptop"})
		var reply map[string]any
		wsjson.Read(ctx, c, &reply)
		if reply["type"] != "laptop" || reply["laptop"] != want {
			t.Errorf("toggle reply = %v, want laptop=%s", reply, want)
		}
	}
}

func TestNoLaptopScreenOnDesktops(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for name, laptop := range map[string]Laptop{"nil": nil, "no backlight": &fakeLaptop{missing: true}} {
		s, _ := newTestServer(t)
		s.Laptop = laptop
		c, ready := session(t, ctx, s)
		if ready["laptop"] != "" {
			t.Errorf("%s: ready says laptop = %v, want empty (button hidden)", name, ready["laptop"])
		}
		wsjson.Write(ctx, c, Message{Type: "laptop"})
		var reply map[string]any
		wsjson.Read(ctx, c, &reply)
		if reply["error"] != "no_laptop" {
			t.Errorf("%s: toggle reply = %v", name, reply)
		}
	}
}
