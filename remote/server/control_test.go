package server

import (
	"fmt"
	"reflect"
	"sync"
	"testing"
	"time"

	u "tvbox/remote/uinput"
)

// recorder stands in for the virtual keyboard and mouse.
type recorder struct {
	mu  sync.Mutex
	log []string
}

func (r *recorder) add(s string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.log = append(r.log, s)
	return nil
}
func (r *recorder) events() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.log...)
}
func (r *recorder) Press(c uint16) error             { return r.add(fmt.Sprintf("+%d", c)) }
func (r *recorder) Release(c uint16) error           { return r.add(fmt.Sprintf("-%d", c)) }
func (r *recorder) Move(dx, dy int32) error          { return r.add(fmt.Sprintf("move %d %d", dx, dy)) }
func (r *recorder) Button(b uint16, down bool) error { return r.add(fmt.Sprintf("btn %d %v", b, down)) }
func (r *recorder) Scroll(v, h int32) error          { return r.add(fmt.Sprintf("scroll %d %d", v, h)) }

func k(code int, down bool) string {
	if down {
		return fmt.Sprintf("+%d", code)
	}
	return fmt.Sprintf("-%d", code)
}

func TestTapHoldsModifiersAroundTheKey(t *testing.T) {
	r := &recorder{}
	s := NewSession(r, r)
	if _, err := s.Handle(Message{Type: "text", Text: "Ç"}); err != nil {
		t.Fatal(err)
	}
	want := []string{
		k(u.KeyLeftShift, true), k(u.KeyLeftAlt, true),
		k(u.KeyC, true), k(u.KeyC, false),
		k(u.KeyLeftAlt, false), k(u.KeyLeftShift, false),
	}
	if got := r.events(); !reflect.DeepEqual(got, want) {
		t.Errorf("events = %v, want %v", got, want)
	}
}

func TestNamedKeys(t *testing.T) {
	r := &recorder{}
	s := NewSession(r, r)
	s.Handle(Message{Type: "key", Key: "back"})
	s.Handle(Message{Type: "key", Key: "ok"})
	want := []string{k(u.KeyBack, true), k(u.KeyBack, false), k(u.KeySelect, true), k(u.KeySelect, false)}
	if got := r.events(); !reflect.DeepEqual(got, want) {
		t.Errorf("events = %v, want %v", got, want)
	}
	if _, err := s.Handle(Message{Type: "key", Key: "self-destruct"}); err == nil {
		t.Error("unknown key accepted")
	}
}

func TestHeldKeyIsReleasedWhenThePhoneLeaves(t *testing.T) {
	r := &recorder{}
	s := NewSession(r, r)
	s.Handle(Message{Type: "key", Key: "down", Action: "down"})
	s.Handle(Message{Type: "key", Key: "down", Action: "down"}) // repeated: no second press
	s.Close()
	want := []string{k(u.KeyDown, true), k(u.KeyDown, false)}
	if got := r.events(); !reflect.DeepEqual(got, want) {
		t.Errorf("events = %v, want %v", got, want)
	}
}

func TestHeldKeyIsReleasedAfterMaxHold(t *testing.T) {
	old := maxHold
	maxHold = 20 * time.Millisecond
	defer func() { maxHold = old }()

	r := &recorder{}
	s := NewSession(r, r)
	s.Handle(Message{Type: "key", Key: "up", Action: "down"})
	time.Sleep(80 * time.Millisecond)
	want := []string{k(u.KeyUp, true), k(u.KeyUp, false)}
	if got := r.events(); !reflect.DeepEqual(got, want) {
		t.Errorf("events = %v, want %v", got, want)
	}
	// The phone's late "up" must not release it twice.
	s.Handle(Message{Type: "key", Key: "up", Action: "up"})
	if got := r.events(); len(got) != 2 {
		t.Errorf("events after late up = %v", got)
	}
}

func TestPointerKeepsSubPixelMovement(t *testing.T) {
	r := &recorder{}
	s := NewSession(r, r)
	s.Handle(Message{Type: "move", DX: 0.6, DY: -0.4})
	s.Handle(Message{Type: "move", DX: 0.6, DY: -0.7})
	s.Handle(Message{Type: "click"})
	s.Handle(Message{Type: "scroll", DY: -2})
	want := []string{"move 1 -1", "btn 272 true", "btn 272 false", "scroll -2 0"}
	if got := r.events(); !reflect.DeepEqual(got, want) {
		t.Errorf("events = %v, want %v", got, want)
	}
}
