package server

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestTokenIsCreatedOnceAndKeptPrivate(t *testing.T) {
	dir := t.TempDir()
	p1, err := LoadPairing(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(p1.Token()) != 64 {
		t.Fatalf("token %q is not 32 random bytes in hex", p1.Token())
	}
	info, err := os.Stat(filepath.Join(dir, "token"))
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Errorf("token file mode = %v, want 0600", info.Mode().Perm())
	}
	p2, _ := LoadPairing(dir)
	if p2.Token() != p1.Token() {
		t.Error("token changed across restarts")
	}
	if !p2.Check(p1.Token()) || p2.Check("nope") || p2.Check("") {
		t.Error("Check accepts the wrong token or rejects the right one")
	}
}

func TestPINOnlyWorksWhileTheScreenIsOpen(t *testing.T) {
	now := time.Unix(1000, 0)
	p, _ := LoadPairing(t.TempDir())
	p.now = func() time.Time { return now }

	if _, err := p.Redeem("123456"); !errors.Is(err, ErrPairingClosed) {
		t.Fatalf("before opening: err = %v, want closed", err)
	}
	pin, err := p.Open()
	if err != nil || len(pin) != 6 {
		t.Fatalf("Open() = %q, %v", pin, err)
	}
	if tok, err := p.Redeem(pin); err != nil || tok != p.Token() {
		t.Fatalf("Redeem(right PIN) = %q, %v", tok, err)
	}
	now = now.Add(pairingWindow + time.Second)
	if _, err := p.Redeem(pin); !errors.Is(err, ErrPairingClosed) {
		t.Errorf("after the window: err = %v, want closed", err)
	}
}

func TestTooManyWrongGuessesReplaceThePIN(t *testing.T) {
	p, _ := LoadPairing(t.TempDir())
	pin, _ := p.Open()
	wrong := "000000"
	if pin == wrong {
		wrong = "111111"
	}
	for i := 0; i < maxFailures; i++ {
		if _, err := p.Redeem(wrong); !errors.Is(err, ErrWrongPIN) {
			t.Fatalf("guess %d: err = %v", i, err)
		}
	}
	if _, err := p.Redeem(pin); err == nil {
		t.Fatal("old PIN still works after too many guesses")
	}
	if fresh, _ := p.Open(); fresh == "" {
		t.Error("screen got no new PIN")
	}
}

func TestMarkPaired(t *testing.T) {
	dir := t.TempDir()
	p, _ := LoadPairing(dir)
	p.MarkPaired()
	if _, err := os.Stat(filepath.Join(dir, "paired")); err != nil {
		t.Error("paired marker missing: the TV would keep opening the pairing screen")
	}
}
