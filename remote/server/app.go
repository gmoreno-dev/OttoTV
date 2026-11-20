package server

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"rsc.io/qr"

	"otto/remote/update"
)

// System is what the launcher's quick panel can change, which apps may not
// do themselves: otto-remote does it as root (see main.go).
type System interface {
	// SetAudio sends the sound to "tv" (HDMI) or "notebook" (the laptop's
	// speakers) and remembers the choice.
	SetAudio(to string) error
	// SSID is the Wi-Fi network's name, "" if unknown.
	SSID() string
	// Power suspends, restarts ("reboot") or turns off ("shutdown") the TV.
	Power(action string) error
	// Details are the quick panel's system details: cpuTemp, cpuLoad, ip,
	// graphics ("hardware" or "software") and adb (its network port, or 0).
	Details() map[string]any
}

// AdBlock blocks ads on the whole TV (the adblock package).
type AdBlock interface {
	// State is "on", "off" or "paused" (turned off because the network
	// blocks it); "" when it can't be told.
	State() string
	Set(on bool) error
}

// ErrNoTVSound: the TV's sound output isn't known (no TV was found on HDMI).
var ErrNoTVSound = errors.New("no TV sound output found")

// The launcher's routes (tvapp/, home/Remote.java), only for Otto TV's app.
func (s *Server) appRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /app/status", s.onlyFromApp(s.handleAppStatus))
	mux.HandleFunc("POST /app/audio", s.onlyFromApp(s.handleAppAudio))
	mux.HandleFunc("POST /app/screen", s.onlyFromApp(s.handleAppScreen))
	mux.HandleFunc("POST /app/power", s.onlyFromApp(s.handleAppPower))
	mux.HandleFunc("POST /app/adblock", s.onlyFromApp(s.handleAppAdBlock))
	mux.HandleFunc("GET /app/details", s.onlyFromApp(s.handleAppDetails))
	mux.HandleFunc("GET /app/pair", s.onlyFromApp(s.handleAppPair))
	mux.HandleFunc("GET /app/update", s.onlyFromApp(s.handleAppUpdate))
	mux.HandleFunc("POST /app/update/check", s.onlyFromApp(s.handleAppUpdateCheck))
	mux.HandleFunc("POST /app/update/install", s.onlyFromApp(s.handleAppUpdateInstall))
}

// onlyFromApp lets in Otto TV's app (and root or the adb shell), asking
// over this TV's own address with a matching Host header (no DNS
// rebinding). Everything else gets 403.
func (s *Server) onlyFromApp(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !isLoopbackHost(r.Host) || s.Peers == nil {
			http.Error(w, "only for Otto TV's app", http.StatusForbidden)
			return
		}
		ok, err := s.Peers.Allowed(r.RemoteAddr, s.Port)
		if !ok {
			if err != nil {
				log.Printf("app route %s from %s: %v", r.URL.Path, r.RemoteAddr, err)
			} else {
				log.Printf("app route %s from %s: not Otto TV's app", r.URL.Path, r.RemoteAddr)
			}
			http.Error(w, "only for Otto TV's app", http.StatusForbidden)
			return
		}
		h(w, r)
	}
}

func (s *Server) appStatus(wifi bool) map[string]any {
	st := map[string]any{
		"phones": s.phones.Load(),
		"paired": s.Pairing.Paired(),
		"audio":  s.audio(),
		"laptop": s.laptopState(),
	}
	// Asking Android for the network's name (and ad blocking's state)
	// takes a moment: only the quick panel wants them.
	if wifi && s.System != nil {
		st["ssid"] = s.System.SSID()
	}
	if wifi && s.AdBlock != nil {
		st["adblock"] = s.AdBlock.State()
	}
	return st
}

func (s *Server) handleAppStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.appStatus(r.URL.Query().Get("wifi") == "1"))
}

func readBody(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(v); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad request"})
		return false
	}
	return true
}

func (s *Server) handleAppAudio(w http.ResponseWriter, r *http.Request) {
	var req struct {
		To string `json:"to"`
	}
	if !readBody(w, r, &req) {
		return
	}
	if req.To != "tv" && req.To != "notebook" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "to: tv or notebook"})
		return
	}
	if s.System == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "unavailable"})
		return
	}
	if err := s.System.SetAudio(req.To); err != nil {
		log.Printf("audio to %s: %v", req.To, err)
		code := http.StatusInternalServerError
		if errors.Is(err, ErrNoTVSound) {
			code = http.StatusConflict
		}
		writeJSON(w, code, map[string]string{"error": err.Error()})
		return
	}
	log.Printf("audio: now on the %s", req.To)
	writeJSON(w, http.StatusOK, s.appStatus(false))
}

func (s *Server) handleAppScreen(w http.ResponseWriter, r *http.Request) {
	var req struct {
		On bool `json:"on"`
	}
	if !readBody(w, r, &req) {
		return
	}
	if s.laptopState() == "" {
		writeJSON(w, http.StatusConflict, map[string]string{"error": "no_laptop"})
		return
	}
	// The quick panel shows its own notice, so no Notify here.
	if s.Laptop.Off() == req.On {
		if _, err := s.Laptop.Toggle(); err != nil {
			log.Printf("laptop screen: %v", err)
		}
	}
	writeJSON(w, http.StatusOK, map[string]string{"laptop": s.laptopState()})
}

func (s *Server) handleAppAdBlock(w http.ResponseWriter, r *http.Request) {
	var req struct {
		On bool `json:"on"`
	}
	if !readBody(w, r, &req) {
		return
	}
	if s.AdBlock == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "unavailable"})
		return
	}
	if err := s.AdBlock.Set(req.On); err != nil {
		log.Printf("ad blocking on=%v: %v", req.On, err)
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"adblock": s.AdBlock.State()})
}

// powerDelay lets the answer reach the app before the TV goes dark.
var powerDelay = 400 * time.Millisecond

func (s *Server) handleAppPower(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Action string `json:"action"`
	}
	if !readBody(w, r, &req) {
		return
	}
	switch req.Action {
	case "suspend", "reboot", "shutdown":
	default:
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "action: suspend, reboot or shutdown"})
		return
	}
	if s.System == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "unavailable"})
		return
	}
	log.Printf("power: %s (quick panel)", req.Action)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	go func() {
		time.Sleep(powerDelay)
		if err := s.System.Power(req.Action); err != nil {
			log.Printf("power %s: %v", req.Action, err)
		}
	}()
}

func (s *Server) handleAppDetails(w http.ResponseWriter, r *http.Request) {
	d := map[string]any{"ip": s.LANIP(), "version": s.Version}
	if s.System != nil {
		for k, v := range s.System.Details() {
			d[k] = v
		}
	}
	writeJSON(w, http.StatusOK, d)
}

// handleAppPair gives the shortcuts screen (F1) the remote's QR code, as
// rows of 0 and 1: the app draws it. Like the "Connect phone" screen, it
// carries the token.
func (s *Server) handleAppPair(w http.ResponseWriter, r *http.Request) {
	base := s.baseURL()
	if base == "" {
		writeJSON(w, http.StatusOK, map[string]any{"url": "", "qr": []string{}})
		return
	}
	rows, err := QRRows(base + "/#t=" + s.Pairing.Token())
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"url": strings.TrimPrefix(base, "http://"), "qr": rows})
}

// QRRows is text as a QR code, one string of 0 and 1 per row, no quiet zone.
func QRRows(text string) ([]string, error) {
	code, err := qr.Encode(text, qr.M)
	if err != nil {
		return nil, err
	}
	rows := make([]string, code.Size)
	for y := 0; y < code.Size; y++ {
		var b strings.Builder
		for x := 0; x < code.Size; x++ {
			if code.Black(x, y) {
				b.WriteByte('1')
			} else {
				b.WriteByte('0')
			}
		}
		rows[y] = b.String()
	}
	return rows, nil
}

// --- Updates (the update package) -----------------------------------------------
//
// The app looks for a new release on GitHub and downloads it (tvapp,
// home/Updates.java); these routes check what it found and install it.

// handleAppUpdate says what the app needs to look: this build's version,
// the GitHub repository with the releases, whether this system can be
// updated (not a live one) and what was installed since boot.
func (s *Server) handleAppUpdate(w http.ResponseWriter, r *http.Request) {
	if s.Update == nil {
		writeJSON(w, http.StatusOK, map[string]any{"repo": ""})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"current":   s.Update.Current,
		"repo":      s.UpdateRepo,
		"live":      s.Update.IsLive(),
		"installed": s.Update.Installed(),
	})
}

// handleAppUpdateCheck takes a release's manifest and says whether it is
// signed and newer.
func (s *Server) handleAppUpdateCheck(w http.ResponseWriter, r *http.Request) {
	if s.Update == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "unavailable"})
		return
	}
	var m update.Manifest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&m); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad request"})
		return
	}
	newer, err := s.Update.Check(m)
	if err != nil {
		log.Printf("update %s: %v", m.Version, err)
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": updateError(err)})
		return
	}
	log.Printf("update: latest release %s, this is %s (newer: %v)", m.Version, s.Update.Current, newer)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "newer": newer, "version": m.Version})
}

// handleAppUpdateInstall takes the manifest (Otto-Manifest header) and the
// archive (the body, streamed while the app downloads it) and installs it.
func (s *Server) handleAppUpdateInstall(w http.ResponseWriter, r *http.Request) {
	if s.Update == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "unavailable"})
		return
	}
	var m update.Manifest
	if err := json.Unmarshal([]byte(r.Header.Get("Otto-Manifest")), &m); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad request"})
		return
	}
	log.Printf("update: installing %s", m.Version)
	if err := s.Update.Install(m, http.MaxBytesReader(w, r.Body, m.Size+1)); err != nil {
		log.Printf("update %s: %v", m.Version, err)
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": updateError(err)})
		return
	}
	log.Printf("update: %s installed, used after a restart", m.Version)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "version": m.Version})
}

// updateError is the error's name for the app.
func updateError(err error) string {
	switch {
	case errors.Is(err, update.ErrSignature):
		return "signature"
	case errors.Is(err, update.ErrNotNewer):
		return "not_newer"
	case errors.Is(err, update.ErrDownload):
		return "download"
	case errors.Is(err, update.ErrBusy):
		return "busy"
	case errors.Is(err, update.ErrLive):
		return "live"
	}
	return "install"
}
