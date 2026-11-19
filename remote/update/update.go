// Package update installs new versions of Otto TV from the TV itself.
//
// Each release on GitHub carries the update archive (the same
// otto-tv-<version>-update.tar that tools/device/update.sh installs over
// adb) and a manifest, otto-tv-update.json, that names it, with its size,
// its SHA-256 and an Ed25519 signature over all of that. The TV app
// downloads both (Android does the networking: DNS, TLS, proxies) and hands
// them to otto-remote, which only installs what the signature vouches for:
// the private key never leaves the build machine, so neither GitHub nor
// the network can slip in an update.
package update

import (
	"archive/tar"
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
)

// ManifestName is the manifest's file name in a release.
const ManifestName = "otto-tv-update.json"

// Manifest describes one release's update archive.
type Manifest struct {
	Version string `json:"version"`
	File    string `json:"file"`
	Size    int64  `json:"size"`
	SHA256  string `json:"sha256"`
	Sig     string `json:"sig"` // base64 Ed25519 signature of Message()
}

// Message is what the signature covers.
func (m Manifest) Message() []byte {
	return []byte(fmt.Sprintf("otto-tv-update 1\n%s\n%s\n%d\n%s\n", m.Version, m.File, m.Size, m.SHA256))
}

var (
	versionRE = regexp.MustCompile(`^\d{1,4}\.\d{1,4}\.\d{1,4}$`)
	fileRE    = regexp.MustCompile(`^[a-z0-9.-]{1,80}\.tar$`)
	sha256RE  = regexp.MustCompile(`^[0-9a-f]{64}$`)
)

// Errors the TV app tells apart (see server's /app/update routes).
var (
	ErrSignature = errors.New("the update's signature doesn't match")
	ErrNotNewer  = errors.New("not newer than what is installed")
	ErrDownload  = errors.New("the update archive is incomplete or damaged")
	ErrBusy      = errors.New("an update is being installed")
	ErrLive      = errors.New("a live system (USB stick) can't be updated")
)

// Verify checks the manifest's fields and signature.
func (m Manifest) Verify(key ed25519.PublicKey) error {
	if !versionRE.MatchString(m.Version) || !fileRE.MatchString(m.File) || !sha256RE.MatchString(m.SHA256) ||
		m.Size < 1024 || m.Size > 512<<20 {
		return ErrSignature
	}
	sig, err := base64.StdEncoding.DecodeString(m.Sig)
	if err != nil || len(key) != ed25519.PublicKeySize || !ed25519.Verify(key, m.Message(), sig) {
		return ErrSignature
	}
	return nil
}

// Sign fills in m.Sig.
func Sign(m *Manifest, key ed25519.PrivateKey) {
	m.Sig = base64.StdEncoding.EncodeToString(ed25519.Sign(key, m.Message()))
}

// ForFile makes the manifest of an update archive (unsigned).
func ForFile(path, version string) (Manifest, error) {
	f, err := os.Open(path)
	if err != nil {
		return Manifest{}, err
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil {
		return Manifest{}, err
	}
	return Manifest{Version: version, File: filepath.Base(path), Size: n, SHA256: hex.EncodeToString(h.Sum(nil))}, nil
}

// Newer reports whether version a comes after b. A b that isn't a version
// (a "dev" build) is older than everything.
func Newer(a, b string) bool {
	pa, okA := parts(a)
	pb, okB := parts(b)
	if !okA {
		return false
	}
	if !okB {
		return true
	}
	for i := range pa {
		if pa[i] != pb[i] {
			return pa[i] > pb[i]
		}
	}
	return false
}

func parts(v string) ([3]int, bool) {
	var p [3]int
	if !versionRE.MatchString(v) {
		return p, false
	}
	for i, s := range strings.Split(v, ".") {
		p[i], _ = strconv.Atoi(s)
	}
	return p, true
}

// Installer installs verified updates on this TV.
type Installer struct {
	Key     ed25519.PublicKey
	Current string // this build's version
	Dir     string // where the archive waits while it is installed
	// Live reports whether this is a live system; nil: never.
	Live func() bool
	// Run runs the update script; nil: sh <script> <archive>.
	Run func(script, archive string) ([]byte, error)

	mu        sync.Mutex
	busy      bool
	installed string // version installed this boot, waiting for a restart
}

// Installed is the version installed since this boot, "" if none.
func (in *Installer) Installed() string {
	in.mu.Lock()
	defer in.mu.Unlock()
	return in.installed
}

// IsLive reports whether this system can't be updated in place.
func (in *Installer) IsLive() bool {
	return in.Live != nil && in.Live()
}

// Check reports whether m is a signed update newer than this build (and
// than what was installed since boot).
func (in *Installer) Check(m Manifest) (bool, error) {
	if err := m.Verify(in.Key); err != nil {
		return false, err
	}
	return Newer(m.Version, in.Current) && Newer(m.Version, in.orCurrent()), nil
}

func (in *Installer) orCurrent() string {
	if v := in.Installed(); v != "" {
		return v
	}
	return in.Current
}

// Install reads the archive m describes from body, checks it and installs
// it. The new version runs after a restart.
func (in *Installer) Install(m Manifest, body io.Reader) error {
	newer, err := in.Check(m)
	if err != nil {
		return err
	}
	if !newer {
		return ErrNotNewer
	}
	if in.IsLive() {
		return ErrLive
	}
	in.mu.Lock()
	if in.busy {
		in.mu.Unlock()
		return ErrBusy
	}
	in.busy = true
	in.mu.Unlock()
	defer func() {
		in.mu.Lock()
		in.busy = false
		in.mu.Unlock()
	}()

	if err := os.MkdirAll(in.Dir, 0o700); err != nil {
		return err
	}
	archive := filepath.Join(in.Dir, "update.tar")
	script := filepath.Join(in.Dir, "update.sh")
	defer os.Remove(archive)
	defer os.Remove(script)
	if err := receive(archive, body, m); err != nil {
		return err
	}
	// The archive brings its own installer, so how to install can change
	// from one release to the next.
	if err := extract(archive, "otto/update.sh", script); err != nil {
		return fmt.Errorf("%w: %v", ErrDownload, err)
	}
	run := in.Run
	if run == nil {
		run = func(script, archive string) ([]byte, error) {
			return exec.Command("sh", script, archive).CombinedOutput()
		}
	}
	if out, err := run(script, archive); err != nil {
		return fmt.Errorf("update.sh: %v: %s", err, lastLine(out))
	}
	in.mu.Lock()
	in.installed = m.Version
	in.mu.Unlock()
	return nil
}

// receive writes body to path, at most m.Size bytes, and checks them.
func receive(path string, body io.Reader, m Manifest) error {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), io.LimitReader(body, m.Size+1))
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return fmt.Errorf("%w: %v", ErrDownload, err)
	}
	if n != m.Size || hex.EncodeToString(h.Sum(nil)) != m.SHA256 {
		return ErrDownload
	}
	return nil
}

// extract copies one file out of a tar archive.
func extract(archive, name, to string) error {
	f, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer f.Close()
	tr := tar.NewReader(f)
	for {
		h, err := tr.Next()
		if err == io.EOF {
			return fmt.Errorf("%s is not in the archive", name)
		}
		if err != nil {
			return err
		}
		if strings.TrimPrefix(h.Name, "./") != name || h.Typeflag != tar.TypeReg {
			continue
		}
		out, err := os.OpenFile(to, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o700)
		if err != nil {
			return err
		}
		_, err = io.Copy(out, io.LimitReader(tr, 1<<20))
		if cerr := out.Close(); err == nil {
			err = cerr
		}
		return err
	}
}

func lastLine(out []byte) string {
	lines := bytes.Split(bytes.TrimSpace(out), []byte("\n"))
	return string(lines[len(lines)-1])
}

// LiveFromCmdline reports a live boot (the USB stick), as update.sh does.
func LiveFromCmdline(path string) func() bool {
	return func() bool {
		b, err := os.ReadFile(path)
		return err == nil && bytes.Contains(b, []byte("androidboot.live=true"))
	}
}
