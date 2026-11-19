package server

import (
	"bufio"
	"errors"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
)

// PeerCheck tells whether a local connection may use the launcher's routes.
type PeerCheck interface {
	Allowed(remoteAddr string, port int) (bool, error)
}

// Peers tells which app is on the other end of a local connection, so the
// launcher's routes (/app/) only answer Otto TV's own app: they restart
// the TV, switch its sound and hand out the pairing token, which other apps
// on the TV must not get.
//
// Linux lists every TCP socket in /proc/net/tcp and tcp6 with the user that
// owns it; on Android each app is its own user, listed by package in
// /data/system/packages.list. otto-remote runs as root, so it can read both.
type Peers struct {
	ProcNet      []string // /proc/net/tcp, /proc/net/tcp6
	PackagesList string   // /data/system/packages.list
	Package      string   // the app allowed in: app.ottotv.connect
}

// System users that may call the routes too: root and the adb shell.
var trustedUIDs = map[int]bool{0: true, 2000: true}

var errNoSocket = errors.New("connection not found in /proc/net")

// Allowed reports whether the connection from remoteAddr (as net/http
// gives it) to our port comes from the app or a trusted system user.
func (p *Peers) Allowed(remoteAddr string, port int) (bool, error) {
	uid, err := p.UID(remoteAddr, port)
	if err != nil {
		return false, err
	}
	if trustedUIDs[uid] {
		return true, nil
	}
	app, err := p.appID()
	if err != nil {
		return false, err
	}
	// Each Android user has its own range of 100000 ids.
	return uid%100000 == app, nil
}

// UID finds who owns the client end of a connection to our port.
func (p *Peers) UID(remoteAddr string, port int) (int, error) {
	host, portStr, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		return 0, err
	}
	if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
		return 0, fmt.Errorf("%s is not on this TV", host)
	}
	client, err := strconv.Atoi(portStr)
	if err != nil {
		return 0, err
	}
	for _, path := range p.ProcNet {
		uid, err := socketUID(path, client, port)
		if err == nil {
			return uid, nil
		}
		if !errors.Is(err, errNoSocket) && !errors.Is(err, os.ErrNotExist) {
			return 0, err
		}
	}
	return 0, errNoSocket
}

// socketUID scans one /proc/net/tcp* table for the socket whose local port
// is the client's and remote port is ours. Lines look like
//
//	0: 0100007F:D431 0100007F:21EE 01 00000000:00000000 00:00000000 00000000 10087 0 4242 ...
//
// with ports in hex and the owner's uid in the 8th field.
func socketUID(path string, localPort, remotePort int) (int, error) {
	f, err := os.Open(path)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	s := bufio.NewScanner(f)
	for s.Scan() {
		fields := strings.Fields(s.Text())
		if len(fields) < 8 || fields[0] == "sl" {
			continue
		}
		if hexPort(fields[1]) == localPort && hexPort(fields[2]) == remotePort {
			return strconv.Atoi(fields[7])
		}
	}
	if err := s.Err(); err != nil {
		return 0, err
	}
	return 0, errNoSocket
}

func hexPort(addr string) int {
	i := strings.LastIndexByte(addr, ':')
	if i < 0 {
		return -1
	}
	v, err := strconv.ParseInt(addr[i+1:], 16, 32)
	if err != nil {
		return -1
	}
	return int(v)
}

// appID reads the app's id from packages.list: "app.ottotv.connect 10087 0 ...".
func (p *Peers) appID() (int, error) {
	f, err := os.Open(p.PackagesList)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	s := bufio.NewScanner(f)
	s.Buffer(make([]byte, 64<<10), 1<<20)
	for s.Scan() {
		fields := strings.Fields(s.Text())
		if len(fields) >= 2 && fields[0] == p.Package {
			uid, err := strconv.Atoi(fields[1])
			if err != nil {
				return 0, err
			}
			return uid % 100000, nil
		}
	}
	if err := s.Err(); err != nil {
		return 0, err
	}
	return 0, fmt.Errorf("%s is not installed", p.Package)
}
