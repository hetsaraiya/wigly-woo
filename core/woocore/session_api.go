package woocore

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/wiglywoo/core/session"
)

// dialRetry spaces attempts while the listener learns our fingerprint.
const dialRetry = 500 * time.Millisecond

// SessionPort is the TCP port of the media listener, or 0 if it failed to bind.
func (c *Core) SessionPort() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.sessionPort
}

// AllowFingerprint lets that peer certificate open a session to us.
func (c *Core) AllowFingerprint(fingerprint string) {
	if fingerprint == "" {
		return
	}
	c.mu.Lock()
	c.allowed[fingerprint] = struct{}{}
	c.mu.Unlock()
}

func (c *Core) allowFingerprint(fingerprint string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	_, ok := c.allowed[fingerprint]
	return ok
}

// DialSession opens a session to addr and requires peerFingerprint.
// The result arrives as SessionOpen or SessionError. The listener may not have
// allowed our certificate yet (that news travels over the relay), so a
// rejected dial is retried until the deadline.
func (c *Core) DialSession(addr, peerFingerprint string) {
	if addr == "" || peerFingerprint == "" {
		c.emit(SessionError{Message: "session dial needs an address and fingerprint"})
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(c.ctx, 8*time.Second)
		defer cancel()
		for {
			conn, err := session.Dial(ctx, addr, c.identity, peerFingerprint)
			if err == nil {
				c.track(conn)
				return
			}
			retry := !errors.Is(err, session.ErrFingerprint)
			select {
			case <-ctx.Done():
				retry = false
			case <-time.After(dialRetry):
			}
			if !retry {
				if c.ctx.Err() == nil {
					c.emit(SessionError{Message: "session dial: " + err.Error()})
				}
				return
			}
		}
	}()
}

// CloseSession ends one media session.
func (c *Core) CloseSession(id string) {
	c.mu.Lock()
	conn := c.live[id]
	c.mu.Unlock()
	if conn != nil {
		conn.Close()
	}
}

func (c *Core) acceptSessions() {
	for {
		conn, err := c.sessions.Accept(c.ctx)
		if err != nil {
			return
		}
		c.track(conn)
	}
}

func (c *Core) track(conn *session.Conn) {
	video, audio, control, meta := conn.TakeFDs()
	c.mu.Lock()
	c.live[conn.ID] = conn
	c.mu.Unlock()
	c.emit(SessionOpen{
		ID: conn.ID, Fingerprint: conn.Fingerprint, Role: conn.Role,
		VideoFD: video, AudioFD: audio, ControlFD: control, MetaFD: meta,
	})
	go func() {
		<-conn.Done()
		c.mu.Lock()
		delete(c.live, conn.ID)
		c.mu.Unlock()
		c.emit(SessionClosed{ID: conn.ID})
	}()
}

// FormatSessionAddr joins a host and the peer's session port.
func FormatSessionAddr(host string, port int) string {
	return fmt.Sprintf("%s:%d", host, port)
}
