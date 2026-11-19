// Package server is the phone remote: it serves the remote page, pairs
// phones with the TV and turns their commands into key and mouse events.
package server

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io/fs"
	"log"
	"net"
	"net/http"
	"regexp"
	"strings"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"otto/remote/update"
)

// Server wires the pieces together. Keys and Pointer may be nil when the
// virtual devices could not be created: phones still connect and get told.
type Server struct {
	Name    string
	Version string // this build's, for the quick panel's details
	Port    int
	Pairing *Pairing
	Keys    Keys
	Pointer Pointer
	Laptop  Laptop             // the laptop screen's backlight; nil on desktops
	Static  fs.FS              // the phone remote page
	TVPage  *template.Template // the "Connect phone" screen
	LANIP   func() string      // the TV's address on the home network
	Audio   func() string      // where the sound goes: "tv", "notebook" or ""; may be nil
	Peers   PeerCheck          // who may use /app/ (the launcher); nil: nobody
	System  System             // what /app/ can change; may be nil
	// Notify shows a notice on the TV over any app (tvapp's ToastReceiver):
	// "phone", "screen_off", "screen_on" or "closed" (with the app's
	// package). May be nil.
	Notify func(kind, app string)
	// Panel opens the launcher's quick panel, or closes it when it is open
	// (the phone's Panel button, like F2). May be nil.
	Panel func()
	// LocalName is the TV's name on the network, "otto-tv.local", once the
	// mdns package has claimed it, else "". May be nil.
	LocalName func() string
	// Update installs new versions; nil: none. UpdateRepo is the GitHub
	// repository ("owner/name") whose releases the app looks at.
	Update     *update.Installer
	UpdateRepo string

	phones  atomic.Int32
	pairsAt atomic.Int64 // unix nanos of the last phone that connected
}

// Laptop is a laptop's own screen, which phones can turn dark while the TV
// is the screen (see the panel package).
type Laptop interface {
	Available() bool
	Off() bool
	Toggle() (bool, error)
}

// laptopState is what the phone shows: "" when there's no laptop screen.
func (s *Server) laptopState() string {
	switch {
	case s.Laptop == nil || !s.Laptop.Available():
		return ""
	case s.Laptop.Off():
		return "off"
	}
	return "on"
}

// Handler returns the HTTP routes.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.Handle("GET /", http.FileServerFS(s.Static))
	mux.HandleFunc("GET /ws", s.handleWS)
	mux.HandleFunc("POST /api/pair", s.handlePair)
	mux.HandleFunc("GET /api/id", s.handleID)
	mux.HandleFunc("GET /tv", s.onlyFromTV(s.handleTV))
	mux.HandleFunc("GET /tv/status", s.onlyFromTV(s.handleTVStatus))
	s.appRoutes(mux)
	return s.headers(mux)
}

func (s *Server) headers(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		// Older Safari doesn't count ws:// as 'self', so name it.
		connect := "'self'"
		if validHost.MatchString(r.Host) {
			connect += " ws://" + r.Host
		}
		// The page opened by IP asks the TV's name whether it is there too
		// (remote.js, moveToLocalName).
		if local := s.localAddr(); local != "" && local != r.Host {
			connect += " http://" + local
		}
		h.Set("Content-Security-Policy", "default-src 'self'; connect-src "+connect+
			"; img-src 'self' data:; frame-ancestors 'none'")
		h.Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

var validHost = regexp.MustCompile(`^[A-Za-z0-9.:\[\]-]+$`)

// onlyFromTV restricts a route to the TV itself. The pairing screen shows
// the token as a QR code, so nothing on the network may read it. Checking
// the Host header too stops a web page in the TV's browser from reaching it
// through DNS rebinding.
func (s *Server) onlyFromTV(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil || !net.ParseIP(host).IsLoopback() || !isLoopbackHost(r.Host) {
			http.Error(w, "only available on the TV", http.StatusForbidden)
			return
		}
		// Only Otto TV's app should read this screen (it shows the token).
		// For now just noted: the next release turns it into a check once
		// the logs show the WebView's requests come from the app itself.
		if s.Peers != nil {
			if ok, err := s.Peers.Allowed(r.RemoteAddr, s.Port); !ok {
				log.Printf("tv screen read by another app (%s): %v", r.RemoteAddr, err)
			}
		}
		h(w, r)
	}
}

func isLoopbackHost(hostport string) bool {
	host := hostport
	if h, _, err := net.SplitHostPort(hostport); err == nil {
		host = h
	}
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// --- Phone ---------------------------------------------------------------------

func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	var req struct {
		PIN string `json:"pin"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad request"})
		return
	}
	token, err := s.Pairing.Redeem(req.PIN)
	switch {
	case errors.Is(err, ErrWrongPIN):
		time.Sleep(time.Second) // slows down guessing
		writeJSON(w, http.StatusForbidden, map[string]string{"error": "wrong_pin"})
	case errors.Is(err, ErrPairingClosed):
		writeJSON(w, http.StatusForbidden, map[string]string{"error": "closed"})
	case err != nil:
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "internal"})
	default:
		writeJSON(w, http.StatusOK, map[string]string{"token": token})
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// handleWS runs one phone's connection: an auth message first, then
// commands until it goes away.
func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	// Default options only accept pages from this same server.
	c, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	defer c.CloseNow()
	c.SetReadLimit(64 << 10)
	ctx := r.Context()

	var hello Message
	authCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	err = wsjson.Read(authCtx, c, &hello)
	cancel()
	if err != nil || hello.Type != "auth" || !s.Pairing.Check(hello.Token) {
		wsjson.Write(ctx, c, map[string]string{"type": "error", "error": "unauthorized"})
		c.Close(4401, "unauthorized")
		return
	}

	s.Pairing.MarkPaired()
	if hello.Fresh {
		// Just paired (QR code or code typed on the phone), not a phone
		// coming back: say so on the TV.
		s.notify("phone", "")
	}
	s.phones.Add(1)
	s.pairsAt.Store(time.Now().UnixNano())
	defer s.phones.Add(-1)
	log.Printf("phone connected from %s", r.RemoteAddr)
	defer log.Printf("phone disconnected from %s", r.RemoteAddr)

	ready := map[string]any{"type": "ready", "name": s.Name, "input": s.Keys != nil,
		"laptop": s.laptopState()}
	if err := wsjson.Write(ctx, c, ready); err != nil {
		return
	}

	session := NewSession(s.Keys, s.Pointer)
	defer session.Close()
	for {
		var m Message
		if err := wsjson.Read(ctx, c, &m); err != nil {
			return
		}
		if m.Type == "ping" {
			wsjson.Write(ctx, c, map[string]string{"type": "pong"})
			continue
		}
		if m.Type == "laptop" {
			if s.laptopState() == "" {
				wsjson.Write(ctx, c, map[string]string{"type": "error", "error": "no_laptop"})
				continue
			}
			if _, err := s.Laptop.Toggle(); err != nil {
				log.Printf("laptop screen: %v", err)
			}
			s.notify("screen_"+s.laptopState(), "")
			wsjson.Write(ctx, c, map[string]string{"type": "laptop", "laptop": s.laptopState()})
			continue
		}
		if m.Type == "panel" {
			if s.Panel == nil {
				wsjson.Write(ctx, c, map[string]string{"type": "error", "error": "no_panel"})
			} else {
				go s.Panel()
			}
			continue
		}
		if s.Keys == nil {
			wsjson.Write(ctx, c, map[string]string{"type": "error", "error": "no_input"})
			continue
		}
		skipped, err := session.Handle(m)
		if err != nil {
			log.Printf("command %+v: %v", m, err)
			wsjson.Write(ctx, c, map[string]string{"type": "error", "error": err.Error()})
		}
		if skipped > 0 {
			wsjson.Write(ctx, c, map[string]any{"type": "typed", "skipped": skipped})
		}
	}
}

// --- TV --------------------------------------------------------------------------

// TVPageData fills the "Connect phone" template.
type TVPageData struct {
	Name    string
	URL     string // address to type by hand, without the token
	QR      template.HTML
	PIN     string
	Digits  [2][]string // the PIN in two groups of three, as the TV shows it
	Lang    string
	Texts   map[string]string
	Step    string // "Passo 1 de 2"... while setting up: Wi-Fi first, then the phone
	Another bool   // a phone has connected before: "Connect another phone"
	Audio   string // see Server.Audio
	Phone   bool   // a phone is connected now
}

func (s *Server) handleTV(w http.ResponseWriter, r *http.Request) {
	pin, err := s.Pairing.Open()
	if err != nil {
		http.Error(w, "pairing unavailable", http.StatusInternalServerError)
		return
	}
	base := s.baseURL()
	lang := pickLang(r.Header.Get("Accept-Language"))
	data := TVPageData{
		Name: s.Name, PIN: pin, Digits: pinDigits(pin), Lang: lang, Texts: tvTexts[lang],
		Another: s.Pairing.Paired(), Audio: s.audio(), Phone: s.phones.Load() > 0,
	}
	// The steps show while setting up: offline, the Wi-Fi comes first; the
	// page comes back with ?setup=1 once the TV is online.
	if !data.Another {
		if base == "" {
			data.Step = data.Texts["firstStep"]
		} else if r.URL.Query().Get("setup") == "1" {
			data.Step = data.Texts["secondStep"]
		}
	}
	if base == "" {
		data.URL = ""
	} else {
		data.URL = strings.TrimPrefix(base, "http://")
		qr, err := QRSVG(base + "/#t=" + s.Pairing.Token())
		if err != nil {
			http.Error(w, "qr: "+err.Error(), http.StatusInternalServerError)
			return
		}
		data.QR = template.HTML(qr)
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := s.TVPage.Execute(w, data); err != nil {
		log.Printf("tv page: %v", err)
	}
}

// handleTVStatus keeps the pairing window open while the screen shows and
// tells it when a phone connects.
func (s *Server) handleTVStatus(w http.ResponseWriter, r *http.Request) {
	pin, _ := s.Pairing.Open()
	writeJSON(w, http.StatusOK, map[string]any{
		"pin":      pin,
		"url":      strings.TrimPrefix(s.baseURL(), "http://"),
		"phones":   s.phones.Load(),
		"pairedAt": s.pairsAt.Load() / int64(time.Millisecond),
		"audio":    s.audio(),
	})
}

func (s *Server) notify(kind, app string) {
	if s.Notify != nil {
		go s.Notify(kind, app)
	}
}

func (s *Server) audio() string {
	if s.Audio == nil {
		return ""
	}
	return s.Audio()
}

// pinDigits splits "482913" into 4 8 2 and 9 1 3.
func pinDigits(pin string) [2][]string {
	var d [2][]string
	for i, r := range pin {
		d[min(i/3, 1)] = append(d[min(i/3, 1)], string(r))
	}
	return d
}

// localAddr is "otto-tv.local:8686", or "" while the TV has no name.
func (s *Server) localAddr() string {
	if s.LocalName == nil {
		return ""
	}
	name := s.LocalName()
	if name == "" || !validHost.MatchString(name) {
		return ""
	}
	return fmt.Sprintf("%s:%d", name, s.Port)
}

// handleID tells a phone's page which TV this is and its name on the
// network. A page opened by IP address (the QR code has one: every phone
// can open it) moves to the name when this phone reaches the same TV there,
// so the remote it saves keeps working when the router changes the TV's
// address. Any page may ask (CORS), and the id says nothing about the
// token: it only tells two Otto TVs apart.
func (s *Server) handleID(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	sum := sha256.Sum256([]byte("otto-tv id\n" + s.Pairing.Token()))
	writeJSON(w, http.StatusOK, map[string]string{
		"id":    hex.EncodeToString(sum[:8]),
		"local": s.localAddr(),
	})
}

func (s *Server) baseURL() string {
	ip := s.LANIP()
	if ip == "" {
		return ""
	}
	return fmt.Sprintf("http://%s:%d", ip, s.Port)
}

func pickLang(accept string) string {
	if strings.HasPrefix(strings.ToLower(strings.TrimSpace(accept)), "pt") {
		return "pt"
	}
	return "en"
}

var tvTexts = map[string]map[string]string{
	"pt": {
		"title":      "Conecte seu celular",
		"step1":      "Conecte o celular ao mesmo Wi-Fi da TV.",
		"step2":      "Aponte a câmera para o código.",
		"step3":      "Toque no link: o controle abre no navegador.",
		"manual":     "Ou abra este endereço e digite o código:",
		"connected":  "Celular conectado!",
		"close":      "Aperte Voltar para sair.",
		"firstStep":  "Passo 1 de 2",
		"secondStep": "Passo 2 de 2",
		"wifiTitle":  "Conecte a TV ao Wi-Fi",
		"wifiText":   "Depois disso, o código para conectar o celular aparece aqui.",
		"wifiButton": "Escolher rede Wi-Fi",
		"bluetooth":  "Parear controle Bluetooth",
		"skip":       "Aperte Voltar para pular. Esta tela fica em Conectar celular, no painel rápido.",
		"another":    "Conectar outro celular",
		"online":     "Wi-Fi conectado",
		"offline":    "Sem internet",
		"phone":      "Celular",
		"noPhone":    "Nenhum celular conectado",
		"audioTV":    "TV",
		"audioNb":    "Notebook",
		"sound":      "O som sai pela",
	},
	"en": {
		"title":      "Connect your phone",
		"step1":      "Join the same Wi-Fi as the TV.",
		"step2":      "Point the camera at the code.",
		"step3":      "Tap the link: the remote opens in the browser.",
		"manual":     "Or open this address and type the code:",
		"connected":  "Phone connected!",
		"close":      "Press Back to close.",
		"firstStep":  "Step 1 of 2",
		"secondStep": "Step 2 of 2",
		"wifiTitle":  "Connect the TV to Wi-Fi",
		"wifiText":   "Then the code to connect your phone shows up here.",
		"wifiButton": "Choose a Wi-Fi network",
		"bluetooth":  "Pair a Bluetooth remote",
		"skip":       "Press Back to skip. This screen stays in Connect phone, in the quick panel.",
		"another":    "Connect another phone",
		"online":     "Wi-Fi connected",
		"offline":    "No internet",
		"phone":      "Phone",
		"noPhone":    "No phone connected",
		"audioTV":    "TV",
		"audioNb":    "Laptop",
		"sound":      "Sound plays on the",
	},
}

// LANIP finds the TV's IPv4 address on the home network, preferring
// private addresses on interfaces that are up.
func LANIP() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	var fallback string
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			ipn, ok := a.(*net.IPNet)
			if !ok || ipn.IP.To4() == nil || ipn.IP.IsLinkLocalUnicast() {
				continue
			}
			if ipn.IP.IsPrivate() {
				return ipn.IP.String()
			}
			if fallback == "" {
				fallback = ipn.IP.String()
			}
		}
	}
	return fallback
}
