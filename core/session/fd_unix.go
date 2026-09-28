//go:build unix

package session

import (
	"bufio"
	"encoding/binary"
	"io"
	"os"
	"syscall"
	"time"
)

// Shell sockets are stream socketpairs carrying records:
//
//	length u32be | payload
//
// Not datagrams: on Linux one large unix datagram needs one contiguous kernel
// allocation, and a keyframe of a few hundred KB intermittently fails with
// ENOBUFS. A stream has no such limit.
const recordHeader = 4

// newPair returns the core's end (non-blocking, so Go's poller drives it and
// Close unblocks a pending read) and the shell's end (blocking, with a short
// receive timeout so shell reader threads can notice cancellation).
func newPair() (*os.File, int, error) {
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_STREAM, 0)
	if err != nil {
		return nil, -1, err
	}
	for _, fd := range fds {
		syscall.CloseOnExec(fd)
		_ = syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_SNDBUF, 1<<20)
		_ = syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_RCVBUF, 1<<20)
	}
	tv := syscall.NsecToTimeval((200 * time.Millisecond).Nanoseconds())
	_ = syscall.SetsockoptTimeval(fds[1], syscall.SOL_SOCKET, syscall.SO_RCVTIMEO, &tv)
	if err := syscall.SetNonblock(fds[0], true); err != nil {
		syscall.Close(fds[0])
		syscall.Close(fds[1])
		return nil, -1, err
	}
	return os.NewFile(uintptr(fds[0]), "woo-session"), fds[1], nil
}

// readRecord reads one record from a shell socket.
func readRecord(r *bufio.Reader) ([]byte, error) {
	var hdr [recordHeader]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(hdr[:])
	if n > maxPayload {
		return nil, ErrTooLarge
	}
	payload := make([]byte, n)
	if _, err := io.ReadFull(r, payload); err != nil {
		return nil, err
	}
	return payload, nil
}

// writeRecord writes one record in a single call so it is never interleaved.
func writeRecord(w io.Writer, payload []byte) error {
	buf := make([]byte, recordHeader+len(payload))
	binary.BigEndian.PutUint32(buf, uint32(len(payload)))
	copy(buf[recordHeader:], payload)
	_, err := w.Write(buf)
	return err
}

func closeFD(fd int) {
	if fd >= 0 {
		_ = syscall.Close(fd)
	}
}
