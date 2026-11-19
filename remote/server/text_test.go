package server

import (
	"reflect"
	"testing"

	u "tvbox/remote/uinput"
)

func TestStrokes(t *testing.T) {
	cases := []struct {
		in   rune
		want []Stroke
	}{
		{'a', []Stroke{{Key: u.KeyA}}},
		{'Z', []Stroke{{shift, u.KeyZ}}},
		{'7', []Stroke{{Key: u.Key7}}},
		{'@', []Stroke{{shift, u.Key2}}},
		{'ç', []Stroke{{alt, u.KeyC}}},
		{'Ç', []Stroke{{[]uint16{u.KeyLeftShift, u.KeyLeftAlt}, u.KeyC}}},
		// Dead key, then the letter: Alt+N then a gives ã.
		{'ã', []Stroke{tilde, {Key: u.KeyA}}},
		{'É', []Stroke{acute, {shift, u.KeyE}}},
		{'ô', []Stroke{circumflex, {Key: u.KeyO}}},
		{'😀', nil},
	}
	for _, c := range cases {
		if got := Strokes(c.in); !reflect.DeepEqual(got, c.want) {
			t.Errorf("Strokes(%q) = %v, want %v", c.in, got, c.want)
		}
	}
}

func TestTextStrokes(t *testing.T) {
	strokes, skipped := TextStrokes("Oi ‘você’\r\n😀")
	if skipped != 1 {
		t.Errorf("skipped = %d, want 1 (the emoji)", skipped)
	}
	// ê is the circumflex dead key (Alt+I) then e. Smart quotes become plain
	// ones and \r\n is a single Enter.
	want := []uint16{u.KeyO, u.KeyI, u.KeySpace, u.KeyApostrophe, u.KeyV, u.KeyO,
		u.KeyC, u.KeyI, u.KeyE, u.KeyApostrophe, u.KeyEnter}
	var got []uint16
	for _, s := range strokes {
		got = append(got, s.Key)
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("keys = %v, want %v", got, want)
	}
}
