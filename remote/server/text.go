package server

import (
	"unicode"

	u "otto/remote/uinput"
)

// Stroke is one key press with the modifiers held while it happens.
type Stroke struct {
	Mods []uint16
	Key  uint16
}

var shift = []uint16{u.KeyLeftShift}
var alt = []uint16{u.KeyLeftAlt}

// Our virtual keyboard gets Android's Generic.kcm: a US layout where Alt
// gives ç and the accents as dead keys (Alt+E then a gives á), like a Mac.
var letters = [26]uint16{
	u.KeyA, u.KeyB, u.KeyC, u.KeyD, u.KeyE, u.KeyF, u.KeyG, u.KeyH, u.KeyI,
	u.KeyJ, u.KeyK, u.KeyL, u.KeyM, u.KeyN, u.KeyO, u.KeyP, u.KeyQ, u.KeyR,
	u.KeyS, u.KeyT, u.KeyU, u.KeyV, u.KeyW, u.KeyX, u.KeyY, u.KeyZ,
}

var plain = map[rune]Stroke{
	' ': {Key: u.KeySpace}, '\n': {Key: u.KeyEnter}, '\t': {Key: u.KeyTab},
	'1': {Key: u.Key1}, '2': {Key: u.Key2}, '3': {Key: u.Key3}, '4': {Key: u.Key4},
	'5': {Key: u.Key5}, '6': {Key: u.Key6}, '7': {Key: u.Key7}, '8': {Key: u.Key8},
	'9': {Key: u.Key9}, '0': {Key: u.Key0},
	'-': {Key: u.KeyMinus}, '=': {Key: u.KeyEqual}, '[': {Key: u.KeyLeftBrace},
	']': {Key: u.KeyRightBrace}, '\\': {Key: u.KeyBackslash}, ';': {Key: u.KeySemicolon},
	'\'': {Key: u.KeyApostrophe}, '`': {Key: u.KeyGrave}, ',': {Key: u.KeyComma},
	'.': {Key: u.KeyDot}, '/': {Key: u.KeySlash},

	'!': {shift, u.Key1}, '@': {shift, u.Key2}, '#': {shift, u.Key3},
	'$': {shift, u.Key4}, '%': {shift, u.Key5}, '^': {shift, u.Key6},
	'&': {shift, u.Key7}, '*': {shift, u.Key8}, '(': {shift, u.Key9},
	')': {shift, u.Key0}, '_': {shift, u.KeyMinus}, '+': {shift, u.KeyEqual},
	'{': {shift, u.KeyLeftBrace}, '}': {shift, u.KeyRightBrace},
	'|': {shift, u.KeyBackslash}, ':': {shift, u.KeySemicolon},
	'"': {shift, u.KeyApostrophe}, '~': {shift, u.KeyGrave},
	'<': {shift, u.KeyComma}, '>': {shift, u.KeyDot}, '?': {shift, u.KeySlash},

	'ç': {alt, u.KeyC}, 'Ç': {[]uint16{u.KeyLeftShift, u.KeyLeftAlt}, u.KeyC},
}

// Dead keys in Generic.kcm, and the letters each one combines with.
var (
	acute      = Stroke{alt, u.KeyE}
	grave      = Stroke{alt, u.KeyGrave}
	circumflex = Stroke{alt, u.KeyI}
	tilde      = Stroke{alt, u.KeyN}
	diaeresis  = Stroke{alt, u.KeyU}
)

var accented = map[rune]struct {
	dead Stroke
	base rune
}{
	'á': {acute, 'a'}, 'é': {acute, 'e'}, 'í': {acute, 'i'}, 'ó': {acute, 'o'}, 'ú': {acute, 'u'},
	'à': {grave, 'a'}, 'è': {grave, 'e'}, 'ì': {grave, 'i'}, 'ò': {grave, 'o'}, 'ù': {grave, 'u'},
	'â': {circumflex, 'a'}, 'ê': {circumflex, 'e'}, 'î': {circumflex, 'i'}, 'ô': {circumflex, 'o'}, 'û': {circumflex, 'u'},
	'ã': {tilde, 'a'}, 'õ': {tilde, 'o'}, 'ñ': {tilde, 'n'},
	'ä': {diaeresis, 'a'}, 'ë': {diaeresis, 'e'}, 'ï': {diaeresis, 'i'}, 'ö': {diaeresis, 'o'}, 'ü': {diaeresis, 'u'},
}

// Strokes returns the key strokes that type r, or nil when the keyboard
// layout can't type it (emoji, other scripts).
func Strokes(r rune) []Stroke {
	switch {
	case r >= 'a' && r <= 'z':
		return []Stroke{{Key: letters[r-'a']}}
	case r >= 'A' && r <= 'Z':
		return []Stroke{{shift, letters[r-'A']}}
	}
	if s, ok := plain[r]; ok {
		return []Stroke{s}
	}
	lower := unicode.ToLower(r)
	if a, ok := accented[lower]; ok {
		base := Strokes(a.base)
		if lower != r {
			base = Strokes(unicode.ToUpper(a.base))
		}
		return append([]Stroke{a.dead}, base...)
	}
	return nil
}

// Smart quotes and dashes from phone keyboards, mapped to what we can type.
var lookalikes = map[rune]rune{
	'‘': '\'', '’': '\'', '“': '"', '”': '"', '–': '-', '—': '-', '…': '.',
	'\u00a0': ' ', // no-break space
}

// TextStrokes returns the strokes for a whole string and how many
// characters it had to skip.
func TextStrokes(s string) (strokes []Stroke, skipped int) {
	for _, r := range s {
		if r == '\r' { // "\r\n" is one line break
			continue
		}
		if l, ok := lookalikes[r]; ok {
			r = l
		}
		st := Strokes(r)
		if st == nil {
			skipped++
			continue
		}
		strokes = append(strokes, st...)
	}
	return strokes, skipped
}
