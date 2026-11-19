package update

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
)

// SignMain is `otto-remote sign-update -key FILE -version V ARCHIVE`: it
// prints the release's manifest (otto-tv-update.json).
func SignMain(args []string, stdout, stderr io.Writer) int {
	fl := flag.NewFlagSet("sign-update", flag.ContinueOnError)
	fl.SetOutput(stderr)
	keyPath := fl.String("key", "cache/otto-update.key", "the private key")
	version := fl.String("version", "", "the release's version, e.g. 0.7.0")
	if err := fl.Parse(args); err != nil {
		return 2
	}
	if fl.NArg() != 1 || *version == "" {
		fmt.Fprintln(stderr, "usage: otto-remote sign-update -key FILE -version V otto-tv-V-update.tar")
		return 2
	}
	key, err := LoadPrivateKey(*keyPath)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	m, err := ForFile(fl.Arg(0), *version)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	Sign(&m, key)
	// Only a key whose public half is built in signs anything the TVs take.
	if err := m.Verify(PublicKey); err != nil {
		fmt.Fprintln(stderr, "this key is not the one Otto TV checks (update.PublicKey), or the manifest is invalid")
		return 1
	}
	enc := json.NewEncoder(stdout)
	enc.SetIndent("", "  ")
	if err := enc.Encode(m); err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	return 0
}
