package server

import (
	"fmt"
	"math"
	"sync"
	"time"

	u "otto/remote/uinput"
)

// Keys is what the server needs from a keyboard (uinput.Keyboard in
// production, a recorder in tests).
type Keys interface {
	Press(code uint16) error
	Release(code uint16) error
}

// Pointer is what the server needs from a mouse.
type Pointer interface {
	Move(dx, dy int32) error
	Button(btn uint16, down bool) error
	Scroll(v, h int32) error
}

// Message is one command from the phone, as JSON over the WebSocket.
//
//	{"type":"key","key":"up","action":"down"}   hold (until "up")
//	{"type":"key","key":"ok"}                   tap
//	{"type":"text","text":"olá"}
//	{"type":"move","dx":3.5,"dy":-2}
//	{"type":"click","button":"left"}
//	{"type":"scroll","dy":1}
//	{"type":"laptop"}                           laptop screen dark/lit
type Message struct {
	Type   string  `json:"type"`
	Token  string  `json:"token,omitempty"`
	Key    string  `json:"key,omitempty"`
	Action string  `json:"action,omitempty"`
	Text   string  `json:"text,omitempty"`
	DX     float64 `json:"dx,omitempty"`
	DY     float64 `json:"dy,omitempty"`
	Button string  `json:"button,omitempty"`
}

// Named keys the phone can send, mapped to what Android expects from a TV
// remote (see Generic.kl).
var namedKeys = map[string]uint16{
	"up": u.KeyUp, "down": u.KeyDown, "left": u.KeyLeft, "right": u.KeyRight,
	"ok": u.KeySelect, "back": u.KeyBack, "home": u.KeyHomepage, "menu": u.KeyMenu,
	"play": u.KeyPlayPause, "next": u.KeyNextSong, "prev": u.KeyPrevSong,
	"rewind": u.KeyRewind, "forward": u.KeyFastFwd,
	"volup": u.KeyVolumeUp, "voldown": u.KeyVolumeDown, "mute": u.KeyMute,
	"power":     u.KeyPower,
	"backspace": u.KeyBackspace, "enter": u.KeyEnter, "space": u.KeySpace,
	"tab": u.KeyTab, "esc": u.KeyEsc,
}

var mouseButtons = map[string]uint16{
	"left": u.BtnLeft, "right": u.BtnRight, "middle": u.BtnMiddle,
}

// maxHold releases a held key the phone never let go of (lost Wi-Fi,
// locked screen), so a dropped "up" can't leave an arrow repeating forever.
var maxHold = 8 * time.Second

// Session is one connected phone. It remembers what it holds down, so
// everything is released when the phone goes away.
type Session struct {
	keys    Keys
	pointer Pointer

	mu     sync.Mutex
	held   map[uint16]*time.Timer
	fx, fy float64 // sub-pixel pointer movement carried between messages
}

func NewSession(k Keys, p Pointer) *Session {
	return &Session{keys: k, pointer: p, held: map[uint16]*time.Timer{}}
}

// Handle runs one command. It returns how many characters of a "text"
// command could not be typed.
func (s *Session) Handle(m Message) (skipped int, err error) {
	switch m.Type {
	case "key":
		code, ok := namedKeys[m.Key]
		if !ok {
			return 0, fmt.Errorf("unknown key %q", m.Key)
		}
		switch m.Action {
		case "down":
			return 0, s.press(code)
		case "up":
			return 0, s.release(code)
		default:
			return 0, s.tap(Stroke{Key: code})
		}
	case "text":
		strokes, skipped := TextStrokes(m.Text)
		for _, st := range strokes {
			if err := s.tap(st); err != nil {
				return skipped, err
			}
		}
		return skipped, nil
	case "move":
		return 0, s.move(m.DX, m.DY)
	case "click":
		btn, ok := mouseButtons[m.Button]
		if !ok {
			btn = u.BtnLeft
		}
		if s.pointer == nil {
			return 0, errNoPointer
		}
		if err := s.pointer.Button(btn, true); err != nil {
			return 0, err
		}
		return 0, s.pointer.Button(btn, false)
	case "scroll":
		if s.pointer == nil {
			return 0, errNoPointer
		}
		return 0, s.pointer.Scroll(clamp(m.DY), clamp(m.DX))
	}
	return 0, fmt.Errorf("unknown message type %q", m.Type)
}

var errNoPointer = fmt.Errorf("no virtual mouse")

func (s *Session) tap(st Stroke) error {
	for _, m := range st.Mods {
		if err := s.keys.Press(m); err != nil {
			return err
		}
	}
	err := s.keys.Press(st.Key)
	if err == nil {
		err = s.keys.Release(st.Key)
	}
	for i := len(st.Mods) - 1; i >= 0; i-- {
		if e := s.keys.Release(st.Mods[i]); err == nil {
			err = e
		}
	}
	return err
}

func (s *Session) press(code uint16) error {
	s.mu.Lock()
	if t, ok := s.held[code]; ok {
		t.Reset(maxHold) // already down: the phone is still holding it
		s.mu.Unlock()
		return nil
	}
	s.held[code] = time.AfterFunc(maxHold, func() { s.release(code) })
	s.mu.Unlock()
	return s.keys.Press(code)
}

func (s *Session) release(code uint16) error {
	s.mu.Lock()
	t, ok := s.held[code]
	if ok {
		t.Stop()
		delete(s.held, code)
	}
	s.mu.Unlock()
	if !ok {
		return nil
	}
	return s.keys.Release(code)
}

// Close releases every key this phone still holds.
func (s *Session) Close() {
	s.mu.Lock()
	codes := make([]uint16, 0, len(s.held))
	for c := range s.held {
		codes = append(codes, c)
	}
	s.mu.Unlock()
	for _, c := range codes {
		s.release(c)
	}
}

func (s *Session) move(dx, dy float64) error {
	if s.pointer == nil {
		return errNoPointer
	}
	s.mu.Lock()
	s.fx += dx
	s.fy += dy
	ix, iy := math.Trunc(s.fx), math.Trunc(s.fy)
	s.fx -= ix
	s.fy -= iy
	s.mu.Unlock()
	if ix == 0 && iy == 0 {
		return nil
	}
	return s.pointer.Move(clamp(ix), clamp(iy))
}

func clamp(v float64) int32 {
	return int32(math.Max(-500, math.Min(500, v)))
}
