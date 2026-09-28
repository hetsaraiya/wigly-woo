package woocore

import (
	"context"
	"fmt"
	"time"

	"github.com/wiglywoo/core/discovery"
	"github.com/wiglywoo/core/link"
	"github.com/wiglywoo/core/session"
)

func (c *Core) snapshotBeacon() discovery.Beacon {
	c.mu.Lock()
	defer c.mu.Unlock()
	return discovery.Beacon{
		ID:          c.identity.Fingerprint,
		Name:        c.cfg.Name,
		Port:        c.cfg.Port,
		Fingerprint: c.identity.Fingerprint,
		Caps:        c.caps,
		Session:     c.sessionPort,
	}
}

// SetCaps updates the capability bits on the next beacon.
func (c *Core) SetCaps(caps uint32) {
	c.mu.Lock()
	c.caps = caps
	c.mu.Unlock()
}

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

// SetLevers installs the shell's radio callbacks.
func (c *Core) SetLevers(levers link.Levers) { c.manager.SetLevers(levers) }

// ConfigureHotspot arms hosting or joining. See link.HotspotOff, HotspotHost, HotspotJoin.
func (c *Core) ConfigureHotspot(mode int, ssid, psk string) {
	c.manager.ConfigureHotspot(mode, ssid, psk)
}

// DialSession opens a session to addr and requires peerFingerprint.
// The result arrives as SessionOpen or SessionError.
func (c *Core) DialSession(addr, peerFingerprint string) {
	if addr == "" || peerFingerprint == "" {
		c.emit(SessionError{Message: "session dial needs an address and fingerprint"})
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(c.ctx, 8*time.Second)
		defer cancel()
		conn, err := session.Dial(ctx, addr, c.identity, peerFingerprint)
		if err != nil {
			if c.ctx.Err() == nil {
				c.emit(SessionError{Message: "session dial: " + err.Error()})
			}
			return
		}
		c.track(conn)
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
