package update

import (
	"archive/tar"
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func testKey(t *testing.T) (ed25519.PublicKey, ed25519.PrivateKey) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return pub, priv
}

// archive makes an update archive holding otto/update.sh (and padding, so
// it is over the 1 KB a manifest needs).
func archive(t *testing.T, script string) []byte {
	t.Helper()
	var buf bytes.Buffer
	tw := tar.NewWriter(&buf)
	files := []struct{ name, body string }{
		{"scripts/9-otto", "# hooks\n"},
		{"otto/update.sh", script},
		{"otto/padding", strings.Repeat("x", 4096)},
	}
	for _, f := range files {
		tw.WriteHeader(&tar.Header{Name: f.name, Mode: 0o644, Size: int64(len(f.body)), Typeflag: tar.TypeReg})
		tw.Write([]byte(f.body))
	}
	tw.Close()
	return buf.Bytes()
}

func signed(t *testing.T, priv ed25519.PrivateKey, version string, data []byte) Manifest {
	t.Helper()
	path := filepath.Join(t.TempDir(), "otto-tv-"+version+"-update.tar")
	os.WriteFile(path, data, 0o644)
	m, err := ForFile(path, version)
	if err != nil {
		t.Fatal(err)
	}
	Sign(&m, priv)
	return m
}

func TestNewer(t *testing.T) {
	for _, c := range []struct {
		a, b string
		want bool
	}{
		{"0.7.1", "0.7.0", true},
		{"0.10.0", "0.9.9", true}, // numbers, not text
		{"1.0.0", "0.99.99", true},
		{"0.7.0", "0.7.0", false},
		{"0.6.2", "0.7.0", false},
		{"0.7.0", "dev", true},
		{"dev", "0.7.0", false},
		{"0.7", "0.6.0", false},
		{"0.7.0-rc1", "0.6.0", false},
	} {
		if got := Newer(c.a, c.b); got != c.want {
			t.Errorf("Newer(%q, %q) = %v", c.a, c.b, got)
		}
	}
}

func TestSignatureCoversEverything(t *testing.T) {
	pub, priv := testKey(t)
	m := signed(t, priv, "0.7.1", archive(t, "exit 0\n"))
	if err := m.Verify(pub); err != nil {
		t.Fatalf("a good manifest: %v", err)
	}
	other, _ := testKey(t)
	if err := m.Verify(other); !errors.Is(err, ErrSignature) {
		t.Errorf("another key: %v", err)
	}
	// Any field changed after signing breaks it.
	for name, change := range map[string]func(*Manifest){
		"version": func(m *Manifest) { m.Version = "9.9.9" },
		"file":    func(m *Manifest) { m.File = "evil.tar" },
		"size":    func(m *Manifest) { m.Size++ },
		"sha256":  func(m *Manifest) { m.SHA256 = strings.Repeat("0", 64) },
		"sig":     func(m *Manifest) { m.Sig = "AAAA" },
	} {
		c := m
		change(&c)
		if err := c.Verify(pub); !errors.Is(err, ErrSignature) {
			t.Errorf("%s changed: %v", name, err)
		}
	}
	// And fields that make no sense are refused, even when signed.
	for name, bad := range map[string]Manifest{
		"path in the file name": {Version: "0.7.1", File: "../otto.tar", Size: 4096, SHA256: m.SHA256},
		"odd version":           {Version: "0.7.1; rm", File: m.File, Size: 4096, SHA256: m.SHA256},
		"tiny":                  {Version: "0.7.1", File: m.File, Size: 10, SHA256: m.SHA256},
	} {
		Sign(&bad, priv)
		if err := bad.Verify(pub); !errors.Is(err, ErrSignature) {
			t.Errorf("%s: %v", name, err)
		}
	}
}

func TestInstall(t *testing.T) {
	pub, priv := testKey(t)
	data := archive(t, "echo installing\n")
	m := signed(t, priv, "0.7.1", data)
	var ran []string
	in := &Installer{Key: pub, Current: "0.7.0", Dir: t.TempDir(),
		Run: func(script, archive string) ([]byte, error) {
			b, _ := os.ReadFile(script)
			a, _ := os.ReadFile(archive)
			ran = append(ran, string(b), filepath.Base(archive), string(rune('0'+len(a)%10)))
			return []byte("ok\n"), nil
		}}
	if newer, err := in.Check(m); !newer || err != nil {
		t.Fatalf("Check: %v %v", newer, err)
	}
	if err := in.Install(m, bytes.NewReader(data)); err != nil {
		t.Fatalf("Install: %v", err)
	}
	if len(ran) == 0 || ran[0] != "echo installing\n" || ran[1] != "update.tar" {
		t.Errorf("ran %q: want the archive's own update.sh on it", ran)
	}
	if in.Installed() != "0.7.1" {
		t.Errorf("Installed() = %q", in.Installed())
	}
	// Nothing left behind.
	if left, _ := os.ReadDir(in.Dir); len(left) != 0 {
		t.Errorf("left %v", left)
	}
	// The same update again: already there.
	if newer, _ := in.Check(m); newer {
		t.Error("the installed version still counts as newer")
	}
	if err := in.Install(m, bytes.NewReader(data)); !errors.Is(err, ErrNotNewer) {
		t.Errorf("installing it twice: %v", err)
	}
}

func TestInstallRefuses(t *testing.T) {
	pub, priv := testKey(t)
	data := archive(t, "exit 0\n")
	m := signed(t, priv, "0.7.1", data)
	run := func(string, string) ([]byte, error) {
		t.Error("the update script ran")
		return nil, nil
	}
	newInstaller := func() *Installer {
		return &Installer{Key: pub, Current: "0.7.0", Dir: t.TempDir(), Run: run}
	}

	// An archive cut short, or changed on the way.
	if err := newInstaller().Install(m, bytes.NewReader(data[:len(data)-10])); !errors.Is(err, ErrDownload) {
		t.Errorf("short archive: %v", err)
	}
	evil := append([]byte(nil), data...)
	evil[600] ^= 1
	if err := newInstaller().Install(m, bytes.NewReader(evil)); !errors.Is(err, ErrDownload) {
		t.Errorf("changed archive: %v", err)
	}
	// More than the manifest says: read no further than Size+1.
	if err := newInstaller().Install(m, bytes.NewReader(append(data, make([]byte, 1<<20)...))); !errors.Is(err, ErrDownload) {
		t.Errorf("long archive: %v", err)
	}
	// Older than this build.
	old := signed(t, priv, "0.6.2", data)
	if err := newInstaller().Install(old, bytes.NewReader(data)); !errors.Is(err, ErrNotNewer) {
		t.Errorf("older: %v", err)
	}
	// Signed by someone else.
	_, other := testKey(t)
	forged := signed(t, other, "0.7.1", data)
	if err := newInstaller().Install(forged, bytes.NewReader(data)); !errors.Is(err, ErrSignature) {
		t.Errorf("forged: %v", err)
	}
	// The USB stick.
	live := newInstaller()
	live.Live = func() bool { return true }
	if err := live.Install(m, bytes.NewReader(data)); !errors.Is(err, ErrLive) {
		t.Errorf("live: %v", err)
	}
	// No installer in the archive.
	var buf bytes.Buffer
	tw := tar.NewWriter(&buf)
	tw.WriteHeader(&tar.Header{Name: "otto/other", Mode: 0o644, Size: 2048, Typeflag: tar.TypeReg})
	tw.Write(make([]byte, 2048))
	tw.Close()
	noScript := signed(t, priv, "0.7.1", buf.Bytes())
	if err := newInstaller().Install(noScript, bytes.NewReader(buf.Bytes())); !errors.Is(err, ErrDownload) {
		t.Errorf("no update.sh: %v", err)
	}
}

func TestInstallReportsTheScriptsError(t *testing.T) {
	pub, priv := testKey(t)
	data := archive(t, "exit 1\n")
	m := signed(t, priv, "0.7.1", data)
	in := &Installer{Key: pub, Current: "0.7.0", Dir: t.TempDir()}
	err := in.Install(m, bytes.NewReader(data))
	if err == nil || in.Installed() != "" {
		t.Fatalf("Install: %v, installed %q", err, in.Installed())
	}
	// The real thing: sh runs the archive's script.
	data = archive(t, "echo erro: sem espaco >&2; exit 3\n")
	m = signed(t, priv, "0.7.2", data)
	err = in.Install(m, bytes.NewReader(data))
	if err == nil || !strings.Contains(err.Error(), "sem espaco") {
		t.Errorf("Install: %v, want the script's last line", err)
	}
}

func TestSignMain(t *testing.T) {
	dir := t.TempDir()
	_, priv := testKey(t)
	keyFile := filepath.Join(dir, "k")
	os.WriteFile(keyFile, []byte(base64.StdEncoding.EncodeToString(priv.Seed())+"\n"), 0o600)
	tarPath := filepath.Join(dir, "otto-tv-0.7.1-update.tar")
	os.WriteFile(tarPath, archive(t, "exit 0\n"), 0o644)

	var out, errOut bytes.Buffer
	// A key that isn't the built-in one is refused: its updates would be.
	if code := SignMain([]string{"-key", keyFile, "-version", "0.7.1", tarPath}, &out, &errOut); code == 0 {
		t.Errorf("signed with a foreign key: %s", out.String())
	}

	// With the built-in key swapped for the test's, it signs.
	saved := PublicKey
	PublicKey = priv.Public().(ed25519.PublicKey)
	defer func() { PublicKey = saved }()
	out.Reset()
	if code := SignMain([]string{"-key", keyFile, "-version", "0.7.1", tarPath}, &out, &errOut); code != 0 {
		t.Fatalf("sign-update: %d %s", code, errOut.String())
	}
	var m Manifest
	if err := json.Unmarshal(out.Bytes(), &m); err != nil {
		t.Fatal(err)
	}
	if m.File != "otto-tv-0.7.1-update.tar" || m.Version != "0.7.1" || m.Verify(PublicKey) != nil {
		t.Errorf("manifest %+v", m)
	}
}

func TestLiveFromCmdline(t *testing.T) {
	dir := t.TempDir()
	live, installed := filepath.Join(dir, "live"), filepath.Join(dir, "installed")
	os.WriteFile(live, []byte("BOOT_IMAGE=/kernel quiet androidboot.live=true SRC=/\n"), 0o644)
	os.WriteFile(installed, []byte("BOOT_IMAGE=/kernel quiet SRC=/otto\n"), 0o644)
	if !LiveFromCmdline(live)() || LiveFromCmdline(installed)() || LiveFromCmdline(filepath.Join(dir, "none"))() {
		t.Error("live detection")
	}
}

// The built-in key is the one cache/otto-update.key holds (when this
// machine has it): a mismatch would make every release uninstallable.
func TestBuiltInKeyMatchesTheBuildMachine(t *testing.T) {
	priv, err := LoadPrivateKey("../../cache/otto-update.key")
	if err != nil {
		t.Skip("no signing key on this machine")
	}
	if !priv.Public().(ed25519.PublicKey).Equal(PublicKey) {
		t.Error("cache/otto-update.key is not the private half of update.PublicKey")
	}
}
