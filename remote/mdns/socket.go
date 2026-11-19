package mdns

import (
	"context"
	"encoding/binary"
	"errors"
	"net"
	"syscall"
	"unsafe"
)

// socket is the real Conn: one UDP socket on port 5353 for every
// interface, which learns each packet's interface from IP_PKTINFO and picks
// the outgoing one the same way.
type socket struct {
	c *net.UDPConn
}

// Listen opens port 5353. SO_REUSEADDR lets it share the port with
// Android's own mDNS (system_server's), and both get every multicast packet.
func Listen() (Conn, error) {
	lc := net.ListenConfig{Control: func(_, _ string, rc syscall.RawConn) error {
		var serr error
		err := rc.Control(func(fd uintptr) {
			s := int(fd)
			for _, o := range [][3]int{
				{syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1},
				{syscall.IPPROTO_IP, syscall.IP_PKTINFO, 1},
				{syscall.IPPROTO_IP, syscall.IP_MULTICAST_TTL, 255}, // RFC 6762 11
				{syscall.IPPROTO_IP, syscall.IP_MULTICAST_LOOP, 1},
			} {
				if e := syscall.SetsockoptInt(s, o[0], o[1], o[2]); e != nil && serr == nil {
					serr = e
				}
			}
		})
		if err != nil {
			return err
		}
		return serr
	}}
	pc, err := lc.ListenPacket(context.Background(), "udp4", "0.0.0.0:5353")
	if err != nil {
		return nil, err
	}
	return &socket{c: pc.(*net.UDPConn)}, nil
}

func (s *socket) Read() (Packet, error) {
	buf := make([]byte, 9000)
	oob := make([]byte, 128)
	n, oobn, _, from, err := s.c.ReadMsgUDP(buf, oob)
	if err != nil {
		return Packet{}, err
	}
	p := Packet{Data: buf[:n], From: from}
	if msgs, err := syscall.ParseSocketControlMessage(oob[:oobn]); err == nil {
		for _, m := range msgs {
			if m.Header.Level == syscall.IPPROTO_IP && m.Header.Type == syscall.IP_PKTINFO &&
				len(m.Data) >= syscall.SizeofInet4Pktinfo {
				p.Ifindex = int(int32(binary.NativeEndian.Uint32(m.Data)))
			}
		}
	}
	return p, nil
}

func (s *socket) Send(b []byte, addr *net.UDPAddr, ifindex int) error {
	var oob []byte
	if ifindex > 0 {
		oob = make([]byte, syscall.CmsgSpace(syscall.SizeofInet4Pktinfo))
		h := (*syscall.Cmsghdr)(unsafe.Pointer(&oob[0]))
		h.Level = syscall.IPPROTO_IP
		h.Type = syscall.IP_PKTINFO
		h.SetLen(syscall.CmsgLen(syscall.SizeofInet4Pktinfo))
		pi := (*syscall.Inet4Pktinfo)(unsafe.Pointer(&oob[syscall.CmsgLen(0)]))
		pi.Ifindex = int32(ifindex)
	}
	_, _, err := s.c.WriteMsgUDP(b, oob, addr)
	return err
}

func (s *socket) Join(ifindex int) error {
	rc, err := s.c.SyscallConn()
	if err != nil {
		return err
	}
	var serr error
	err = rc.Control(func(fd uintptr) {
		mreq := &syscall.IPMreqn{Multiaddr: [4]byte{224, 0, 0, 251}, Ifindex: int32(ifindex)}
		serr = syscall.SetsockoptIPMreqn(int(fd), syscall.IPPROTO_IP, syscall.IP_ADD_MEMBERSHIP, mreq)
	})
	if err != nil {
		return err
	}
	if errors.Is(serr, syscall.EADDRINUSE) {
		return nil // already a member there
	}
	return serr
}

func (s *socket) Close() error { return s.c.Close() }
