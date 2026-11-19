package server

import (
	"html/template"
	"strings"
	"testing"

	"otto/remote/web"
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

	if strings.Contains(online, "Step 2 of 2") {
		t.Error("online page shows a setup step it wasn't asked for")
	}

	// Setting up: step 1 offline, step 2 once online.
	s.LANIP = func() string { return "" }
	if page := get(s.Handler(), "127.0.0.1:1", "127.0.0.1:8686", "/tv").Body.String(); !strings.Contains(page, "Step 1 of 2") {
		t.Error("offline page lacks step 1 of 2")
	}
	s.LANIP = func() string { return "192.168.0.42" }
	if page := get(s.Handler(), "127.0.0.1:1", "127.0.0.1:8686", "/tv?setup=1").Body.String(); !strings.Contains(page, "Step 2 of 2") {
		t.Error("online page after setup lacks step 2 of 2")
	}

	// A phone connected before: "another phone", no setup steps.
	s.Pairing.MarkPaired()
	another := get(s.Handler(), "127.0.0.1:1", "127.0.0.1:8686", "/tv?setup=1").Body.String()
	if !strings.Contains(another, "Connect another phone") || strings.Contains(another, "Step 2 of 2") {
		t.Error("with a phone paired before, the page should be Connect another phone, without steps")
	}
}

func TestPINDigits(t *testing.T) {
	d := pinDigits("482913")
	if strings.Join(d[0], "") != "482" || strings.Join(d[1], "") != "913" {
		t.Errorf("pinDigits = %v", d)
	}
}
