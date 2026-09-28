package session

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"os"
	"sync"
	"time"

	"github.com/wiglywoo/core/crypto"
)

const magic = "WOOS"

// handshakeTimeout bounds the TLS handshake and magic exchange.
const handshakeTimeout = 10 * time.Second

// ErrFingerprint is returned when the peer certificate is not the one requested.
var ErrFingerprint = errors.New("session fingerprint mismatch")

// ErrNotAllowed is returned when the listener rejects the client certificate.
var ErrNotAllowed = errors.New("session peer not allowed")

// ErrClosed is returned when the session is already closed.
var ErrClosed = errors.New("session closed")

// Conn is one mutual-TLS session. TakeFDs hands the shell one stream
// socket per channel. The shell owns those descriptors after TakeFDs; this
// side only closes its own ends.
type Conn struct {
	ID          string
	Fingerprint string
	Role        string // "listen" or "dial"

	tls    net.Conn
	mux    *Mux
	locals []*os.File

	fdMu   sync.Mutex // guards remote and taken
	remote [4]int
	taken  bool

	done chan struct{}
	once sync.Once

	ctrlMu    sync.Mutex // serializes writes to the shell's control socket
	ctrlLocal *os.File
	lastSync  time.Time
}

// FDs is the shell-facing end of video, audio, control, and meta.
// The second call returns -1s; the descriptors are owned by the caller.
func (c *Conn) TakeFDs() (video, audio, control, meta int) {
	c.fdMu.Lock()
	defer c.fdMu.Unlock()
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
		c.fdMu.Lock()
		if !c.taken {
			c.taken = true // closed here, so never handed out
			for _, fd := range c.remote {
				closeFD(fd)
			}
		}
		c.fdMu.Unlock()
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
	_ = raw.SetDeadline(time.Now().Add(handshakeTimeout))
	if err := readMagic(raw); err != nil {
		_ = raw.Close()
		return nil, err
	}
	_ = raw.SetDeadline(time.Time{})
	noteTCP(raw)
	return startConn(raw, fp, "dial")
}

func acceptConn(raw net.Conn, allow func(string) bool) (*Conn, error) {
	tc, ok := raw.(*tls.Conn)
	if !ok {
		return nil, errors.New("session accept: not a tls conn")
	}
	// A peer that connects and stalls must not hold a goroutine forever.
	_ = raw.SetDeadline(time.Now().Add(handshakeTimeout))
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
	_ = raw.SetDeadline(time.Time{})
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
	locals := map[byte]*os.File{}
	for i, ch := range channels {
		local, remote, err := newPair()
		if err != nil {
			c.Close()
			return nil, err
		}
		c.locals = append(c.locals, local)
		c.remote[i] = remote
		locals[ch] = local
	}
	c.ctrlLocal = locals[ChanControl]
	c.mux.OnDrop = c.requestSyncFrame
	// Pumps start only once every socket exists: a pump that fails calls Close.
	for ch, local := range locals {
		go c.pumpUp(ch, local)
	}
	go c.pumpDown(locals)
	go c.writeLoop()
	return c, nil
}

// pumpUp forwards records the shell writes onto the TLS connection.
func (c *Conn) pumpUp(ch byte, local *os.File) {
	defer c.Close()
	r := bufio.NewReaderSize(local, 256<<10)
	for {
		payload, err := readRecord(r)
		if err != nil {
			return
		}
		flags := byte(0)
		if ch == ChanVideo {
			flags = VideoFlags(payload)
		} else if ch == ChanAudio {
			flags = AudioFlags(payload)
		}
		c.mux.Enqueue(Frame{Channel: ch, Flags: flags, Payload: payload})
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

// pumpDown hands frames from the TLS connection to the shell. Late video is
// already dropped by the sender's mux, so this simply writes.
func (c *Conn) pumpDown(locals map[byte]*os.File) {
	defer c.Close()
	for {
		f, err := ReadFrame(c.tls)
		if err != nil {
			return
		}
		local, ok := locals[f.Channel]
		if !ok {
			continue
		}
		if f.Channel == ChanControl {
			c.ctrlMu.Lock()
		}
		err = writeRecord(local, f.Payload)
		if f.Channel == ChanControl {
			c.ctrlMu.Unlock()
		}
		if err != nil {
			return
		}
	}
}

// requestSyncFrame tells this side's shell to encode a keyframe, so video
// resumes right after a drop. The mux calls it for every dropped frame; it
// asks at most every 250 ms and keeps asking until the drops stop.
func (c *Conn) requestSyncFrame() {
	c.ctrlMu.Lock()
	defer c.ctrlMu.Unlock()
	if time.Since(c.lastSync) < 250*time.Millisecond || c.ctrlLocal == nil {
		return
	}
	c.lastSync = time.Now()
	_ = writeRecord(c.ctrlLocal, []byte{CtrlSyncFrame})
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
