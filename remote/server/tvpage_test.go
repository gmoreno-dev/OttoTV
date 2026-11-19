package server

import (
	"html/template"
	"strings"
	"testing"

	"tvbox/remote/web"
)

// The real "Connect phone" page: offline it is the first-run Wi-Fi step,
// online it shows the QR code.
func TestRealTVPage(t *testing.T) {
	s, _ := newTestServer(t)
	s.TVPage = template.Must(template.ParseFS(web.Files, "tv.html"))

	s.LANIP = func() string { return "" }
	offline := get(s.Handler(), "127.0.0.1:1", "127.0.0.1:8686", "/tv", "Accept-Language", "pt-BR").Body.String()
	for _, want := range []string{`data-action="openWifi"`, "Conecte a TV ao Wi-Fi", `data-action="openBluetooth"`} {
		if !strings.Contains(offline, want) {
			t.Errorf("offline page lacks %q", want)
		}
	}
	if strings.Contains(offline, `id="qr"`) {
		t.Error("offline page shows a QR code that can't work")
	}

	s.LANIP = func() string { return "192.168.0.42" }
	online := get(s.Handler(), "127.0.0.1:1", "127.0.0.1:8686", "/tv").Body.String()
	for _, want := range []string{`id="qr"`, "<svg", "192.168.0.42:8686", "Connect your phone"} {
		if !strings.Contains(online, want) {
			t.Errorf("online page lacks %q", want)
		}
	}
}
