package session

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"os"
	"sync"

	"github.com/wiglywoo/core/crypto"
)

const magic = "WOOS"

// ErrFingerprint is returned when the peer certificate is not the one requested.
var ErrFingerprint = errors.New("session fingerprint mismatch")

// ErrNotAllowed is returned when the listener rejects the client certificate.
var ErrNotAllowed = errors.New("session peer not allowed")

// ErrClosed is returned when the session is already closed.
var ErrClosed = errors.New("session closed")

// Conn is one mutual-TLS session. TakeFDs hands the shell one datagram
// socket per channel. The shell owns those descriptors after TakeFDs; this
// side only closes its own ends.
type Conn struct {
	ID          string
	Fingerprint string
	Role        string // "listen" or "dial"

	tls    net.Conn
	mux    *Mux
	locals []*os.File
	remote [4]int
	taken  bool

	done chan struct{}
	once sync.Once
}

// FDs is the shell-facing end of video, audio, control, and meta.
// The second call returns -1s; the descriptors are owned by the caller.
func (c *Conn) TakeFDs() (video, audio, control, meta int) {
	if c.taken {
		return -1, -1, -1, -1
	}
	c.taken = true
	return c.remote[0], c.remote[1], c.remote[2], c.remote[3]
}

// Done closes when the session ends.
func (c *Conn) Done() <-chan struct{} { return c.done }

// Close tears down the TLS connection and the local ends of the sockets.
func (c *Conn) Close() {
	c.once.Do(func() {
		close(c.done)
		c.mux.Close()
		_ = c.tls.Close()
		for _, f := range c.locals {
			_ = f.Close()
		}
		if !c.taken {
			for _, fd := range c.remote {
				closeFD(fd)
			}
		}
	})
}

// Listener accepts pinned sessions.
type Listener struct {
	ln    net.Listener
	allow func(fingerprint string) bool
	out   chan *Conn
	done  chan struct{}
	once  sync.Once
}

// Listen binds a TLS listener. allow may be nil, which accepts any client
// certificate. The fingerprint is still available on Conn for the shell.
func Listen(addr string, id *crypto.Identity, allow func(string) bool) (*Listener, error) {
	ln, err := tlsListen(addr, id)
	if err != nil {
		return nil, err
	}
	l := &Listener{
		ln:    ln,
		allow: allow,
		out:   make(chan *Conn, 4),
		done:  make(chan struct{}),
	}
	go l.loop()
	return l, nil
}

// Port is the bound TCP port.
func (l *Listener) Port() int {
	return l.ln.Addr().(*net.TCPAddr).Port
}

// Accept waits for the next session.
func (l *Listener) Accept(ctx context.Context) (*Conn, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-l.done:
		return nil, net.ErrClosed
	case c := <-l.out:
		if c == nil {
			return nil, net.ErrClosed
		}
		return c, nil
	}
}

// Close stops the listener. Existing sessions stay up.
func (l *Listener) Close() error {
	l.once.Do(func() { close(l.done) })
	return l.ln.Close()
}

func (l *Listener) loop() {
	for {
		raw, err := l.ln.Accept()
		if err != nil {
			select {
			case <-l.done:
			default:
			}
			return
		}
		go func() {
			c, err := acceptConn(raw, l.allow)
			if err != nil {
				_ = raw.Close()
				return
			}
			select {
			case l.out <- c:
			case <-l.done:
				c.Close()
			}
		}()
	}
}

// Dial opens a session to addr and requires the server certificate to match
// peerFingerprint.
func Dial(ctx context.Context, addr string, id *crypto.Identity, peerFingerprint string) (*Conn, error) {
	raw, err := tlsDial(ctx, addr, id)
	if err != nil {
		return nil, err
	}
	fp := crypto.PeerFingerprint(raw.ConnectionState())
	if fp == "" || fp != peerFingerprint {
		_ = raw.Close()
		return nil, ErrFingerprint
	}
	if _, err := raw.Write([]byte(magic)); err != nil {
		_ = raw.Close()
		return nil, err
	}
	if err := readMagic(raw); err != nil {
		_ = raw.Close()
		return nil, err
	}
	noteTCP(raw)
	return startConn(raw, fp, "dial")
}

func acceptConn(raw net.Conn, allow func(string) bool) (*Conn, error) {
	tc, ok := raw.(*tls.Conn)
	if !ok {
		return nil, errors.New("session accept: not a tls conn")
	}
	if err := tc.Handshake(); err != nil {
		return nil, err
	}
	fp := crypto.PeerFingerprint(tc.ConnectionState())
	if allow != nil && !allow(fp) {
		return nil, ErrNotAllowed
	}
	if err := readMagic(raw); err != nil {
		return nil, err
	}
	if _, err := raw.Write([]byte(magic)); err != nil {
		return nil, err
	}
	noteTCP(raw)
	return startConn(raw, fp, "listen")
}

func startConn(raw net.Conn, fp, role string) (*Conn, error) {
	channels := []byte{ChanVideo, ChanAudio, ChanControl, ChanMeta}
	c := &Conn{
		ID:          newID(),
		Fingerprint: fp,
		Role:        role,
		tls:         raw,
		mux:         NewMux(),
		remote:      [4]int{-1, -1, -1, -1},
		done:        make(chan struct{}),
	}
	localFD := map[byte]int{}
	for i, ch := range channels {
		local, remote, err := newPair()
		if err != nil {
			c.Close()
			return nil, err
		}
		c.locals = append(c.locals, local)
		c.remote[i] = remote
		localFD[ch] = int(local.Fd())
		go c.pumpUp(ch, int(local.Fd()))
	}
	go c.pumpDown(localFD)
	go c.writeLoop()
	return c, nil
}

func (c *Conn) pumpUp(ch byte, fd int) {
	buf := make([]byte, maxPayload)
	for {
		n, err := readMsg(fd, buf)
		if n > 0 {
			payload := append([]byte(nil), buf[:n]...)
			flags := byte(0)
			if ch == ChanVideo {
				flags = VideoFlags(payload)
			} else if ch == ChanAudio {
				flags = FlagDroppable
			}
			c.mux.Enqueue(Frame{Channel: ch, Flags: flags, Payload: payload})
		}
		if err != nil {
			if isTimeout(err) || isRetryableRead(err) {
				select {
				case <-c.done:
					return
				default:
					continue
				}
			}
			return
		}
	}
}

func (c *Conn) writeLoop() {
	defer c.Close()
	for {
		f, ok := c.mux.Next()
		if !ok {
			return
		}
		if err := WriteFrame(c.tls, f); err != nil {
			return
		}
	}
}

func (c *Conn) pumpDown(fds map[byte]int) {
	defer c.Close()
	for {
		f, err := ReadFrame(c.tls)
		if err != nil {
			return
		}
		fd, ok := fds[f.Channel]
		if !ok {
			continue
		}
		_ = sendMsg(fd, f.Payload, f.Flags&FlagDroppable != 0)
	}
}

func readMagic(r io.Reader) error {
	buf := make([]byte, len(magic))
	if _, err := io.ReadFull(r, buf); err != nil {
		return err
	}
	if string(buf) != magic {
		return errors.New("session magic mismatch")
	}
	return nil
}

func newID() string {
	var b [8]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}

func isTimeout(err error) bool {
	var ne net.Error
	return errors.Is(err, os.ErrDeadlineExceeded) || (errors.As(err, &ne) && ne.Timeout())
}
