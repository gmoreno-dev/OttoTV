// Package uinput creates virtual input devices through the Linux uinput
// driver. Android picks them up like any USB keyboard or mouse, so what we
// type reaches every screen, system dialogs included, which an ordinary app
// can't do.
package uinput

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"os"
	"sync"
	"syscall"
	"unsafe"
)

// ioctl requests from <linux/uinput.h>.
const (
	uiDevCreate  = 0x5501
	uiDevDestroy = 0x5502
	uiSetEvBit   = 0x40045564
	uiSetKeyBit  = 0x40045565
	uiSetRelBit  = 0x40045566
)

// Event types and codes from <linux/input-event-codes.h>.
const (
	evSyn = 0x00
	evKey = 0x01
	evRel = 0x02
	evRep = 0x14

	synReport = 0

	RelX      = 0x00
	RelY      = 0x01
	RelHWheel = 0x06
	RelWheel  = 0x08

	busVirtual = 0x06
)

// userDev is struct uinput_user_dev, the legacy setup record: written once
// before UI_DEV_CREATE. Supported by every kernel Android runs on.
type userDev struct {
	Name         [80]byte
	Bustype      uint16
	Vendor       uint16
	Product      uint16
	Version      uint16
	FFEffectsMax uint32
	Absmax       [64]int32
	Absmin       [64]int32
	Absfuzz      [64]int32
	Absflat      [64]int32
}

// event is struct input_event on 64-bit kernels. The kernel stamps the time
// itself, so we leave it zero.
type event struct {
	Sec   int64
	Usec  int64
	Type  uint16
	Code  uint16
	Value int32
}

// Device is one virtual input device.
type Device struct {
	mu sync.Mutex
	f  *os.File
}

type setup struct {
	ev   []uint16
	keys []uint16
	rels []uint16
}

func create(path, name string, s setup) (*Device, error) {
	f, err := os.OpenFile(path, os.O_WRONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, err
	}
	fail := func(step string, err error) (*Device, error) {
		f.Close()
		return nil, fmt.Errorf("uinput %s: %w", step, err)
	}
	for _, ev := range s.ev {
		if err := ioctl(f, uiSetEvBit, uintptr(ev)); err != nil {
			return fail("UI_SET_EVBIT", err)
		}
	}
	for _, k := range s.keys {
		if err := ioctl(f, uiSetKeyBit, uintptr(k)); err != nil {
			return fail("UI_SET_KEYBIT", err)
		}
	}
	for _, r := range s.rels {
		if err := ioctl(f, uiSetRelBit, uintptr(r)); err != nil {
			return fail("UI_SET_RELBIT", err)
		}
	}

	dev := userDev{Bustype: busVirtual, Version: 1}
	copy(dev.Name[:len(dev.Name)-1], name)
	var buf bytes.Buffer
	binary.Write(&buf, binary.LittleEndian, &dev)
	if _, err := f.Write(buf.Bytes()); err != nil {
		return fail("setup", err)
	}
	if err := ioctl(f, uiDevCreate, 0); err != nil {
		return fail("UI_DEV_CREATE", err)
	}
	return &Device{f: f}, nil
}

// Close removes the device from the system.
func (d *Device) Close() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	ioctl(d.f, uiDevDestroy, 0)
	return d.f.Close()
}

// emit writes events followed by a SYN_REPORT, as one batch.
func (d *Device) emit(evs ...event) error {
	evs = append(evs, event{Type: evSyn, Code: synReport})
	var buf bytes.Buffer
	for i := range evs {
		binary.Write(&buf, binary.LittleEndian, &evs[i])
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	_, err := d.f.Write(buf.Bytes())
	return err
}

func ioctl(f *os.File, req, arg uintptr) error {
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, f.Fd(), req, arg); errno != 0 {
		return errno
	}
	return nil
}

// Keep the struct layouts honest: the kernel reads them byte for byte.
var (
	_ [1116]byte = [unsafe.Sizeof(userDev{})]byte{}
	_ [24]byte   = [unsafe.Sizeof(event{})]byte{}
)

// Keyboard is a virtual keyboard with every standard key plus the TV ones.
type Keyboard struct{ *Device }

// NewKeyboard creates a keyboard. Holding a key auto-repeats, like a real
// one, so holding an arrow on the phone keeps scrolling.
func NewKeyboard(path, name string) (*Keyboard, error) {
	keys := make([]uint16, 0, 256)
	for k := uint16(1); k < 256; k++ {
		keys = append(keys, k)
	}
	keys = append(keys, KeySelect)
	d, err := create(path, name, setup{ev: []uint16{evKey, evRep}, keys: keys})
	if err != nil {
		return nil, err
	}
	return &Keyboard{d}, nil
}

// Press holds a key down.
func (k *Keyboard) Press(code uint16) error {
	return k.emit(event{Type: evKey, Code: code, Value: 1})
}

// Release lets a key go.
func (k *Keyboard) Release(code uint16) error {
	return k.emit(event{Type: evKey, Code: code, Value: 0})
}

// Mouse is a virtual mouse with three buttons and two wheels.
type Mouse struct{ *Device }

// NewMouse creates a mouse.
func NewMouse(path, name string) (*Mouse, error) {
	d, err := create(path, name, setup{
		ev:   []uint16{evKey, evRel},
		keys: []uint16{BtnLeft, BtnRight, BtnMiddle},
		rels: []uint16{RelX, RelY, RelWheel, RelHWheel},
	})
	if err != nil {
		return nil, err
	}
	return &Mouse{d}, nil
}

// Move moves the pointer by dx, dy.
func (m *Mouse) Move(dx, dy int32) error {
	return m.emit(
		event{Type: evRel, Code: RelX, Value: dx},
		event{Type: evRel, Code: RelY, Value: dy},
	)
}

// Button presses or releases a mouse button.
func (m *Mouse) Button(btn uint16, down bool) error {
	v := int32(0)
	if down {
		v = 1
	}
	return m.emit(event{Type: evKey, Code: btn, Value: v})
}

// Scroll turns the wheels: positive v scrolls up, positive h right.
func (m *Mouse) Scroll(v, h int32) error {
	var evs []event
	if v != 0 {
		evs = append(evs, event{Type: evRel, Code: RelWheel, Value: v})
	}
	if h != 0 {
		evs = append(evs, event{Type: evRel, Code: RelHWheel, Value: h})
	}
	if len(evs) == 0 {
		return nil
	}
	return m.emit(evs...)
}
