package server

import (
	"archive/tar"
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"otto/remote/update"
)

func updateServer(t *testing.T) (*Server, ed25519.PrivateKey, *[]string) {
	t.Helper()
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	s, _, _ := appServer(t)
	var ran []string
	s.Update = &update.Installer{Key: pub, Current: "0.7.0", Dir: t.TempDir(),
		Run: func(script, archive string) ([]byte, error) {
			ran = append(ran, filepath.Base(archive))
			return nil, nil
		}}
	s.UpdateRepo = "gmoreno-dev/OttoTV"
	return s, priv, &ran
}

func release(t *testing.T, priv ed25519.PrivateKey, version string) (update.Manifest, []byte) {
	t.Helper()
	var buf bytes.Buffer
	tw := tar.NewWriter(&buf)
	tw.WriteHeader(&tar.Header{Name: "otto/update.sh", Mode: 0o755, Size: 7, Typeflag: tar.TypeReg})
	tw.Write([]byte("exit 0\n"))
	tw.WriteHeader(&tar.Header{Name: "otto/otto-remote", Mode: 0o755, Size: 4096, Typeflag: tar.TypeReg})
	tw.Write(make([]byte, 4096))
	tw.Close()
	path := filepath.Join(t.TempDir(), "otto-tv-"+version+"-update.tar")
	os.WriteFile(path, buf.Bytes(), 0o644)
	m, err := update.ForFile(path, version)
	if err != nil {
		t.Fatal(err)
	}
	update.Sign(&m, priv)
	return m, buf.Bytes()
}

func install(h http.Handler, m update.Manifest, body []byte) *httptest.ResponseRecorder {
	req := httptest.NewRequest("POST", "/app/update/install", bytes.NewReader(body))
	req.RemoteAddr = "127.0.0.1:5000"
	req.Host = "127.0.0.1:8686"
	head, _ := json.Marshal(m)
	req.Header.Set("Otto-Manifest", string(head))
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	return w
}

func TestUpdateRoutes(t *testing.T) {
	s, priv, ran := updateServer(t)
	h := s.Handler()

	info := decode(t, get(h, "127.0.0.1:5000", "127.0.0.1:8686", "/app/update"))
	if info["current"] != "0.7.0" || info["repo"] != "gmoreno-dev/OttoTV" || info["live"] != false || info["installed"] != "" {
		t.Errorf("/app/update = %v", info)
	}

	m, data := release(t, priv, "0.7.1")
	body, _ := json.Marshal(m)
	if got := decode(t, post(h, "/app/update/check", string(body))); got["newer"] != true || got["version"] != "0.7.1" {
		t.Errorf("check = %v", got)
	}
	forged := m
	forged.Version = "0.7.2"
	body, _ = json.Marshal(forged)
	if w := post(h, "/app/update/check", string(body)); w.Code != http.StatusBadRequest || decode(t, w)["error"] != "signature" {
		t.Errorf("forged check: %d %s", w.Code, w.Body)
	}

	// A damaged download is refused, a good one installed.
	bad := append([]byte(nil), data...)
	bad[len(bad)-1] ^= 1
	if w := install(h, m, bad); decode(t, w)["error"] != "download" {
		t.Errorf("damaged: %d %s", w.Code, w.Body)
	}
	if w := install(h, m, data); w.Code != http.StatusOK || decode(t, w)["version"] != "0.7.1" {
		t.Fatalf("install: %d %s", w.Code, w.Body)
	}
	if len(*ran) != 1 {
		t.Errorf("update.sh ran %d times", len(*ran))
	}
	info = decode(t, get(h, "127.0.0.1:5000", "127.0.0.1:8686", "/app/update"))
	if info["installed"] != "0.7.1" {
		t.Errorf("after installing: %v", info)
	}
	if w := install(h, m, data); decode(t, w)["error"] != "not_newer" {
		t.Errorf("again: %s", w.Body)
	}
}

func TestUpdateRoutesOnlyForTheApp(t *testing.T) {
	s, priv, ran := updateServer(t)
	s.Peers = fakePeers{allow: false}
	h := s.Handler()
	m, data := release(t, priv, "0.7.1")
	if w := install(h, m, data); w.Code != http.StatusForbidden {
		t.Errorf("another app installing: %d", w.Code)
	}
	if w := get(h, "127.0.0.1:5000", "127.0.0.1:8686", "/app/update"); w.Code != http.StatusForbidden {
		t.Errorf("another app asking: %d", w.Code)
	}
	if len(*ran) != 0 {
		t.Error("installed for another app")
	}
}

func TestNoUpdatesWithoutAnInstaller(t *testing.T) {
	s, _, _ := appServer(t)
	h := s.Handler()
	if got := decode(t, get(h, "127.0.0.1:5000", "127.0.0.1:8686", "/app/update")); got["repo"] != "" {
		t.Errorf("/app/update = %v", got)
	}
	if w := post(h, "/app/update/check", "{}"); w.Code != http.StatusServiceUnavailable || !strings.Contains(w.Body.String(), "unavailable") {
		t.Errorf("check: %d", w.Code)
	}
}
