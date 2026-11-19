// Package mdns gives the TV a name on the home network, otto-tv.local, so
// the phone remote keeps working when the router hands the TV a new
// address. It is a small multicast DNS responder (RFC 6762) that answers
// for that one name and nothing else: no service discovery.
package mdns

import (
	"encoding/binary"
	"errors"
	"net"
	"strings"
)

// DNS record types and classes we deal in.
const (
	typeA    = 1
	typeAAAA = 28
	typeNSEC = 47
	typeANY  = 255

	classIN    = 1
	classANY   = 255
	classFlush = 0x8000 // in a record: replaces what caches hold for the name
	classQU    = 0x8000 // in a question: please answer by unicast

	// TTLs from RFC 6762 section 10: 120 s for host names. Legacy unicast
	// resolvers (not mDNS aware) get at most 10 s.
	ttlHost   = 120
	ttlLegacy = 10
)

var errFormat = errors.New("mdns: malformed message")

type question struct {
	name  string // lowercase, dot-separated, no trailing dot
	qtype uint16
	class uint16 // with the QU bit
}

type record struct {
	name  string
	rtype uint16
	class uint16
	ttl   uint32
	data  []byte // as on the wire; names in it may be compressed
}

type message struct {
	id       uint16
	response bool
	opcode   int
	qs       []question
	answers  []record
	auth     []record // a probe's proposed records (RFC 6762 8.2)
	extra    []record
}

func parse(b []byte) (*message, error) {
	if len(b) < 12 {
		return nil, errFormat
	}
	m := &message{
		id:       binary.BigEndian.Uint16(b[0:]),
		response: b[2]&0x80 != 0,
		opcode:   int(b[2]>>3) & 0xF,
	}
	counts := [4]int{}
	for i := range counts {
		counts[i] = int(binary.BigEndian.Uint16(b[4+2*i:]))
	}
	off := 12
	for i := 0; i < counts[0]; i++ {
		name, n, err := readName(b, off)
		if err != nil || n+4 > len(b) {
			return nil, errFormat
		}
		m.qs = append(m.qs, question{name, binary.BigEndian.Uint16(b[n:]), binary.BigEndian.Uint16(b[n+2:])})
		off = n + 4
	}
	sections := []*[]record{&m.answers, &m.auth, &m.extra}
	for s, dst := range sections {
		for i := 0; i < counts[s+1]; i++ {
			name, n, err := readName(b, off)
			if err != nil || n+10 > len(b) {
				return nil, errFormat
			}
			r := record{
				name:  name,
				rtype: binary.BigEndian.Uint16(b[n:]),
				class: binary.BigEndian.Uint16(b[n+2:]),
				ttl:   binary.BigEndian.Uint32(b[n+4:]),
			}
			size := int(binary.BigEndian.Uint16(b[n+8:]))
			if n+10+size > len(b) {
				return nil, errFormat
			}
			r.data = b[n+10 : n+10+size]
			*dst = append(*dst, r)
			off = n + 10 + size
		}
	}
	return m, nil
}

// readName reads a possibly compressed name at off and returns it with the
// offset just past it.
func readName(b []byte, off int) (string, int, error) {
	var labels []string
	end := -1
	for jumps := 0; ; {
		if off >= len(b) {
			return "", 0, errFormat
		}
		n := int(b[off])
		switch {
		case n == 0:
			if end < 0 {
				end = off + 1
			}
			return strings.ToLower(strings.Join(labels, ".")), end, nil
		case n&0xC0 == 0xC0:
			if off+1 >= len(b) || jumps > 32 {
				return "", 0, errFormat
			}
			if end < 0 {
				end = off + 2
			}
			off = int(binary.BigEndian.Uint16(b[off:]) & 0x3FFF)
			jumps++
		case n&0xC0 != 0:
			return "", 0, errFormat
		default:
			if off+1+n > len(b) {
				return "", 0, errFormat
			}
			labels = append(labels, string(b[off+1:off+1+n]))
			off += 1 + n
		}
	}
}

// builder writes a message. Names are written in full: our messages are
// small, and that keeps this simple.
type builder struct {
	b      []byte
	counts [4]uint16
}

func newBuilder(id uint16, response bool) *builder {
	bd := &builder{b: make([]byte, 12, 512)}
	binary.BigEndian.PutUint16(bd.b, id)
	if response {
		bd.b[2] = 0x84 // QR, AA
	}
	return bd
}

func (bd *builder) name(name string) {
	for _, l := range strings.Split(name, ".") {
		bd.b = append(bd.b, byte(len(l)))
		bd.b = append(bd.b, l...)
	}
	bd.b = append(bd.b, 0)
}

func (bd *builder) question(name string, qtype, class uint16) {
	bd.name(name)
	bd.b = binary.BigEndian.AppendUint16(bd.b, qtype)
	bd.b = binary.BigEndian.AppendUint16(bd.b, class)
	bd.counts[0]++
}

// section: 1 answers, 2 authority, 3 additional.
func (bd *builder) record(section int, name string, rtype, class uint16, ttl uint32, data []byte) {
	bd.name(name)
	bd.b = binary.BigEndian.AppendUint16(bd.b, rtype)
	bd.b = binary.BigEndian.AppendUint16(bd.b, class)
	bd.b = binary.BigEndian.AppendUint32(bd.b, ttl)
	bd.b = binary.BigEndian.AppendUint16(bd.b, uint16(len(data)))
	bd.b = append(bd.b, data...)
	bd.counts[section]++
}

// a adds one A record per address.
func (bd *builder) a(section int, name string, class uint16, ttl uint32, ips []net.IP) {
	for _, ip := range ips {
		if v4 := ip.To4(); v4 != nil {
			bd.record(section, name, typeA, class, ttl, v4)
		}
	}
}

// nsec says the name has A records and nothing else (RFC 6762 6.1), so a
// phone asking for IPv6 knows at once not to wait for an answer.
func (bd *builder) nsec(section int, name string, class uint16, ttl uint32) {
	var data []byte
	for _, l := range strings.Split(name, ".") {
		data = append(data, byte(len(l)))
		data = append(data, l...)
	}
	data = append(data, 0)
	// Window 0, one byte of bitmap, bit 1 (type A) set.
	data = append(data, 0, 1, 0x40)
	bd.record(section, name, typeNSEC, class, ttl, data)
}

func (bd *builder) bytes() []byte {
	for i, c := range bd.counts {
		binary.BigEndian.PutUint16(bd.b[4+2*i:], c)
	}
	return bd.b
}
