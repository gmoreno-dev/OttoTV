package server

import (
	"os"
	"path/filepath"
	"testing"
)

// A /proc/net/tcp6 as Android shows it: apps' Java sockets are IPv6, with
// IPv4 addresses mapped (::ffff:127.0.0.1). 21EE is port 8686.
const tcp6 = `  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000000000000000000000000000:21EE 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 4101 1 0000000000000000 100 0 0 10 0
   1: 0000000000000000FFFF00000100007F:D431 0000000000000000FFFF00000100007F:21EE 01 00000000:00000000 00:00000000 00000000 10087        0 4242 1 0000000000000000 20 4 30 10 -1
   2: 0000000000000000FFFF00000100007F:D432 0000000000000000FFFF00000100007F:21EE 01 00000000:00000000 00:00000000 00000000 10123        0 4243 1 0000000000000000 20 4 30 10 -1
   3: 0000000000000000FFFF00000100007F:21EE 0000000000000000FFFF00000100007F:D431 01 00000000:00000000 00:00000000 00000000     0        0 4244 1 0000000000000000 20 4 30 10 -1
`

// /proc/net/tcp: root (curl in adb shell runs as 2000, root as 0).
const tcp4 = `  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0100007F:E001 0100007F:21EE 01 00000000:00000000 00:00000000 00000000     0        0 5001 1 0000000000000000 20 4 30 10 -1
   1: 0100007F:E002 0100007F:21EE 01 00000000:00000000 00:00000000 00000000  2000        0 5002 1 0000000000000000 20 4 30 10 -1
`

const packagesList = `com.android.tv.settings 1000 0 /data/user_de/0/com.android.tv.settings platform:privapp:targetSdkVersion=34 1065,3002 0 34
app.ottotv.connect 10087 0 /data/user/0/app.ottotv.connect platform:targetSdkVersion=34 3003 0 500
com.stremio.one 10123 0 /data/user/0/com.stremio.one default:targetSdkVersion=33 3003 0 1
`

func testPeers(t *testing.T) *Peers {
	t.Helper()
	dir := t.TempDir()
	write := func(name, data string) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, []byte(data), 0o600); err != nil {
			t.Fatal(err)
		}
		return p
	}
	return &Peers{
		ProcNet:      []string{write("tcp", tcp4), write("tcp6", tcp6)},
		PackagesList: write("packages.list", packagesList),
		Package:      "app.ottotv.connect",
	}
}

func TestPeersLetInTheAppAndRoot(t *testing.T) {
	p := testPeers(t)
	for _, c := range []struct {
		remote string
		want   bool
	}{
		{"[::ffff:127.0.0.1]:54321", true},  // 0xD431: Otto TV's app
		{"127.0.0.1:54321", true},           // same socket, as Go may print it
		{"[::ffff:127.0.0.1]:54322", false}, // 0xD432: another app
		{"127.0.0.1:57345", true},           // 0xE001: root
		{"127.0.0.1:57346", true},           // 0xE002: adb shell
	} {
		ok, err := p.Allowed(c.remote, 8686)
		if ok != c.want {
			t.Errorf("%s: allowed=%v (%v), want %v", c.remote, ok, err, c.want)
		}
	}
}

func TestPeersRefuseWhatTheyCantTell(t *testing.T) {
	p := testPeers(t)
	if ok, err := p.Allowed("127.0.0.1:1234", 8686); ok || err == nil {
		t.Errorf("unknown socket: allowed=%v err=%v", ok, err)
	}
	if ok, _ := p.Allowed("192.168.0.50:54321", 8686); ok {
		t.Error("a phone on the network got in")
	}
	// The app's socket, but to another port: not ours.
	if ok, _ := p.Allowed("127.0.0.1:54321", 9999); ok {
		t.Error("socket to another port counted")
	}
	p.Package = "app.not.installed"
	if ok, err := p.Allowed("[::ffff:127.0.0.1]:54321", 8686); ok || err == nil {
		t.Errorf("app not installed: allowed=%v err=%v", ok, err)
	}
	p.PackagesList = "/nonexistent/packages.list"
	if ok, _ := p.Allowed("[::ffff:127.0.0.1]:54321", 8686); ok {
		t.Error("allowed without packages.list")
	}
}

func TestPeersOtherAndroidUsers(t *testing.T) {
	p := testPeers(t)
	p.ProcNet = p.ProcNet[:1]
	dir := t.TempDir()
	path := filepath.Join(dir, "tcp")
	// The app in user 10: uid 1010087.
	os.WriteFile(path, []byte("  sl local rem st\n   0: 0100007F:E003 0100007F:21EE 01 00000000:00000000 00:00000000 00000000 1010087 0 1\n"), 0o600)
	p.ProcNet = []string{path}
	if ok, err := p.Allowed("127.0.0.1:57347", 8686); !ok {
		t.Errorf("app in another user refused: %v", err)
	}
}
