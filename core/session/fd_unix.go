//go:build unix

package session

import (
	"errors"
	"os"
	"syscall"
	"time"
)

func newPair() (*os.File, int, error) {
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_SEQPACKET, 0)
	if err != nil {
		fds, err = syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_DGRAM, 0)
		if err != nil {
			return nil, -1, err
		}
	}
	for _, fd := range fds {
		syscall.CloseOnExec(fd)
		_ = syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_SNDBUF, 4<<20)
		_ = syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_RCVBUF, 4<<20)
	}
	// The Go side polls with a short receive timeout so session close is
	// noticed even when the shell has not written.
	tv := syscall.NsecToTimeval((200 * time.Millisecond).Nanoseconds())
	_ = syscall.SetsockoptTimeval(fds[0], syscall.SOL_SOCKET, syscall.SO_RCVTIMEO, &tv)
	_ = syscall.SetsockoptTimeval(fds[1], syscall.SOL_SOCKET, syscall.SO_RCVTIMEO, &tv)
	return os.NewFile(uintptr(fds[0]), "woo-session"), fds[1], nil
}

func isRetryableRead(err error) bool {
	return errors.Is(err, syscall.EAGAIN) || errors.Is(err, syscall.EWOULDBLOCK) || errors.Is(err, syscall.ETIMEDOUT)
}

func sendMsg(fd int, payload []byte, droppable bool) error {
	if droppable {
		_ = syscall.SetNonblock(fd, true)
		defer syscall.SetNonblock(fd, false)
	}
	for {
		n, err := syscall.Write(fd, payload)
		if err == syscall.EINTR {
			continue
		}
		if err == syscall.EAGAIN || err == syscall.EWOULDBLOCK {
			return err
		}
		if err != nil {
			return err
		}
		if n == len(payload) || len(payload) == 0 {
			return nil
		}
		// A seqpacket write is all-or-nothing. A short write means the
		// message did not fit; dropping it is safer than splitting it.
		return syscall.EMSGSIZE
	}
}

func readMsg(fd int, buf []byte) (int, error) {
	for {
		n, err := syscall.Read(fd, buf)
		if err == syscall.EINTR {
			continue
		}
		return n, err
	}
}

func closeFD(fd int) {
	if fd >= 0 {
		_ = syscall.Close(fd)
	}
}
