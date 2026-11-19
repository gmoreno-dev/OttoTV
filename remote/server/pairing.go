package server

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Pairing guards the remote. A phone needs the TV's token, which it gets in
// one of two ways, both requiring someone in front of the TV:
//   - scanning the QR code on the "Connect phone" screen (the token rides in
//     the URL fragment, which browsers never send to the server), or
//   - typing the 6-digit PIN shown on that same screen.
//
// The PIN only works while that screen is open, and a few wrong guesses
// replace it.
type Pairing struct {
	dir string
	now func() time.Time

	mu       sync.Mutex
	token    string
	pin      string
	openTill time.Time
	failures int
}

const (
	pairingWindow = 5 * time.Minute
	maxFailures   = 5
)

var ErrPairingClosed = errors.New("pairing is closed: open \"Connect phone\" on the TV")
var ErrWrongPIN = errors.New("wrong PIN")

// LoadPairing reads the token from dir, creating one on first run.
func LoadPairing(dir string) (*Pairing, error) {
	p := &Pairing{dir: dir, now: time.Now}
	path := filepath.Join(dir, "token")
	b, err := os.ReadFile(path)
	if err == nil && len(strings.TrimSpace(string(b))) >= 32 {
		p.token = strings.TrimSpace(string(b))
		return p, nil
	}
	if p.token, err = randomHex(32); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	return p, os.WriteFile(path, []byte(p.token+"\n"), 0o600)
}

// Token is the secret the QR code carries.
func (p *Pairing) Token() string { return p.token }

// Check reports whether a phone presented the right token.
func (p *Pairing) Check(token string) bool {
	return subtle.ConstantTimeCompare([]byte(token), []byte(p.token)) == 1
}

// Open (re)opens the pairing window and returns the current PIN. The TV
// screen calls it while it is showing.
func (p *Pairing) Open() (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.pin == "" || p.now().After(p.openTill) {
		pin, err := randomPIN()
		if err != nil {
			return "", err
		}
		p.pin, p.failures = pin, 0
	}
	p.openTill = p.now().Add(pairingWindow)
	return p.pin, nil
}

// Redeem trades the PIN for the token.
func (p *Pairing) Redeem(pin string) (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.pin == "" || p.now().After(p.openTill) {
		return "", ErrPairingClosed
	}
	if subtle.ConstantTimeCompare([]byte(strings.TrimSpace(pin)), []byte(p.pin)) != 1 {
		p.failures++
		if p.failures >= maxFailures {
			p.pin = "" // too many guesses: the screen will show a new PIN
		}
		return "", ErrWrongPIN
	}
	return p.token, nil
}

// MarkPaired records that a phone has connected at least once, so the TV
// stops opening the pairing screen at boot (see init-hooks.sh).
func (p *Pairing) MarkPaired() {
	path := filepath.Join(p.dir, "paired")
	if _, err := os.Stat(path); err == nil {
		return
	}
	os.WriteFile(path, []byte(p.now().Format(time.RFC3339)+"\n"), 0o600)
}

// Paired reports whether a phone has ever connected (see MarkPaired).
func (p *Pairing) Paired() bool {
	_, err := os.Stat(filepath.Join(p.dir, "paired"))
	return err == nil
}

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func randomPIN() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n.Int64()), nil
}
