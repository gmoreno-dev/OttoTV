package uinput

import (
	"bytes"
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"
)

// emit must write struct input_event exactly as a 64-bit kernel reads it,
// with a SYN_REPORT closing every batch.
func TestEmitWritesKernelEvents(t *testing.T) {
	f, err := os.Create(filepath.Join(t.TempDir(), "events"))
	if err != nil {
		t.Fatal(err)
	}
	kb := &Keyboard{&Device{f: f}}
	if err := kb.Press(KeyBack); err != nil {
		t.Fatal(err)
	}
	f.Close()

	data, _ := os.ReadFile(f.Name())
	if len(data) != 2*24 {
		t.Fatalf("wrote %d bytes, want two 24-byte events", len(data))
	}
	var evs [2]event
	binary.Read(bytes.NewReader(data), binary.LittleEndian, &evs)
	if evs[0] != (event{Type: evKey, Code: KeyBack, Value: 1}) {
		t.Errorf("first event = %+v", evs[0])
	}
	if evs[1] != (event{Type: evSyn, Code: synReport}) {
		t.Errorf("second event = %+v, want SYN_REPORT", evs[1])
	}
}
