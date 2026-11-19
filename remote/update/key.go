package update

import (
	"crypto/ed25519"
	"encoding/base64"
	"errors"
	"os"
	"strings"
)

// PublicKey checks the releases' signatures. Its private half is
// cache/otto-update.key on the build machine, never in git: whoever has
// it can make every Otto TV install anything.
var PublicKey = mustKey("KsvI+mMWz1M1zaOC1tOiAcsVwofohDplPEHmZlTGhpg=")

func mustKey(b64 string) ed25519.PublicKey {
	b, err := base64.StdEncoding.DecodeString(b64)
	if err != nil || len(b) != ed25519.PublicKeySize {
		panic("update: bad public key")
	}
	return ed25519.PublicKey(b)
}

// LoadPrivateKey reads a private key file: the base64 seed on one line.
func LoadPrivateKey(path string) (ed25519.PrivateKey, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	seed, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(b)))
	if err != nil || len(seed) != ed25519.SeedSize {
		return nil, errors.New(path + ": not an update signing key")
	}
	return ed25519.NewKeyFromSeed(seed), nil
}
