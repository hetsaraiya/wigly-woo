// Package woocore is the orchestrating API that both the CLI and the FFI layer
// drive. It wires identity + discovery + link + transfer together and emits a
// single stream of events the UI consumes. This is the "what" the platform
// shells call down into; everything below it is platform-agnostic Go.
package woocore

import (
	"context"
	"errors"
	"fmt"
	"os"
	"sync"

	"github.com/wiglywoo/core/crypto"
	"github.com/wiglywoo/core/discovery"
	"github.com/wiglywoo/core/link"
	"github.com/wiglywoo/core/transfer"
)

// Config configures a Core.
type Config struct {
	Name    string // shown to peers
	SaveDir string // where received files land
	Port    int    // transfer TCP port (0 = pick free)
}

// Event is anything pushed up to the UI. Concrete types below.
type Event any

// PeerFound is emitted when a new peer appears on the link.
type PeerFound struct{ Peer discovery.Peer }

// TrustRequest is emitted before accepting an incoming transfer so the UI can
// confirm the sender's fingerprint. Respond with Core.Trust(in, ok).
type TrustRequest struct {
	Incoming transfer.Incoming
}

// Progress is emitted as a transfer moves (both send and receive).
type Progress struct {
	Direction string // "send" | "recv"
	Name      string // file name
	Sent      int64
	Total     int64
}

// Done is emitted when a transfer completes.
type Done struct {
	Direction string
	Name      string
	Path      string // local path for received files
}

// Errorf is emitted on failure.
type Errorf struct{ Message string }

// Canceled is emitted when the user aborts the active transfer(s).
type Canceled struct{}

// Core is the engine.
type Core struct {
	cfg      Config
	identity *crypto.Identity
	browser  *discovery.Browser
	server   *transfer.Server
	manager  *link.Manager

	events chan Event
	ctx    context.Context
	cancel context.CancelFunc

	mu      sync.Mutex
	pending map[string]chan bool // fingerprint -> trust decision

	cancelMu   sync.Mutex
	cancelers  map[int]func() // id -> abort func for in-progress transfers
	nextCancel int
}

// New builds a Core with a fresh identity.
func New(cfg Config) (*Core, error) {
	if cfg.Name == "" {
		cfg.Name = "wigly-woo"
	}
	if cfg.SaveDir == "" {
		cfg.SaveDir = "."
	}
	id, err := crypto.NewIdentity(cfg.Name)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Core{
		cfg:       cfg,
		identity:  id,
		manager:   link.NewManager(link.NoLevers{}),
		events:    make(chan Event, 64),
		ctx:       ctx,
		cancel:    cancel,
		pending:   make(map[string]chan bool),
		cancelers: make(map[int]func()),
	}, nil
}

// Identity exposes this device's name + fingerprint for display at pairing.
func (c *Core) Identity() *crypto.Identity { return c.identity }

// Events is the UI's read side. Drain it.
func (c *Core) Events() <-chan Event { return c.events }

// Start brings up the transfer server, announces, and browses for peers.
func (c *Core) Start() error {
	srv, err := transfer.NewServer(c.identity, c.cfg.SaveDir, c.cfg.Port)
	if err != nil {
		return err
	}
	c.server = srv
	c.cfg.Port = srv.Port()

	srv.Accept = func(in transfer.Incoming) bool { return c.awaitTrust(in) }
	srv.Register = func(abort func()) func() {
		id := c.addCanceler(abort)
		return func() { c.removeCanceler(id) }
	}
	srv.OnProgress = func(in transfer.Incoming, sent, total int64) {
		c.emit(Progress{Direction: "recv", Name: in.FileName, Sent: sent, Total: total})
	}
	srv.OnDone = func(in transfer.Incoming, path string) {
		c.emit(Done{Direction: "recv", Name: in.FileName, Path: path})
	}
	go func() {
		if err := srv.Serve(); err != nil && c.ctx.Err() == nil {
			c.emit(Errorf{Message: "server: " + err.Error()})
		}
	}()

	c.browser = discovery.NewBrowser(c.identity.Fingerprint)
	c.browser.OnPeer = func(p discovery.Peer) { c.emit(PeerFound{Peer: p}) }
	go func() {
		if err := c.browser.Browse(c.ctx); err != nil && c.ctx.Err() == nil {
			c.emit(Errorf{Message: "browse: " + err.Error()})
		}
	}()

	beacon := discovery.Beacon{
		ID:          c.identity.Fingerprint, // fingerprint doubles as stable ID
		Name:        c.cfg.Name,
		Port:        c.cfg.Port,
		Fingerprint: c.identity.Fingerprint,
	}
	go func() {
		if err := discovery.Announce(c.ctx, beacon); err != nil && c.ctx.Err() == nil {
			c.emit(Errorf{Message: "announce: " + err.Error()})
		}
	}()
	return nil
}

// Stop tears everything down.
func (c *Core) Stop() {
	c.cancel()
	if c.server != nil {
		_ = c.server.Close()
	}
}

// Peers returns currently known peers.
func (c *Core) Peers() []discovery.Peer {
	if c.browser == nil {
		return nil
	}
	return c.browser.Peers()
}

// Lookup finds a peer by ID or name.
func (c *Core) Lookup(idOrName string) (discovery.Peer, bool) {
	if c.browser == nil {
		return discovery.Peer{}, false
	}
	return c.browser.Lookup(idOrName)
}

// SendFile resolves the peer's link and sends path. Blocks until complete.
func (c *Core) SendFile(peerIDOrName, path string) error {
	peer, ok := c.Lookup(peerIDOrName)
	if !ok {
		return fmt.Errorf("peer %q not found", peerIDOrName)
	}
	addr, _, err := c.manager.Connect(peer)
	if err != nil {
		return err
	}
	name := baseName(path)
	ctx, cancel := context.WithCancel(c.ctx)
	id := c.addCanceler(cancel)
	defer c.removeCanceler(id)
	defer cancel()
	err = transfer.Send(ctx, addr, c.identity, path,
		func(sent, total int64) {
			c.emit(Progress{Direction: "send", Name: name, Sent: sent, Total: total})
		},
		func(fp string) {}, // peer fingerprint already known from beacon
	)
	if err != nil {
		if !errors.Is(err, context.Canceled) {
			c.emit(Errorf{Message: "send: " + err.Error()})
		}
		return err
	}
	c.emit(Done{Direction: "send", Name: name})
	return nil
}

// SendFD streams an already-open file descriptor to the peer, with no copy to
// disk. Used by the Android shell, which only gets a content:// URI (not a
// path) from the system picker. Takes ownership of fd and closes it when done.
func (c *Core) SendFD(peerIDOrName string, fd uintptr, name string, size int64) error {
	peer, ok := c.Lookup(peerIDOrName)
	if !ok {
		return fmt.Errorf("peer %q not found", peerIDOrName)
	}
	addr, _, err := c.manager.Connect(peer)
	if err != nil {
		return err
	}
	f := os.NewFile(fd, name)
	if f == nil {
		return fmt.Errorf("invalid file descriptor %d", fd)
	}
	defer f.Close()
	base := baseName(name)
	ctx, cancel := context.WithCancel(c.ctx)
	cid := c.addCanceler(cancel)
	defer c.removeCanceler(cid)
	defer cancel()
	err = transfer.SendStream(ctx, addr, c.identity, f, base, size,
		func(sent, total int64) {
			c.emit(Progress{Direction: "send", Name: base, Sent: sent, Total: total})
		},
		func(fp string) {},
	)
	if err != nil {
		if !errors.Is(err, context.Canceled) {
			c.emit(Errorf{Message: "send: " + err.Error()})
		}
		return err
	}
	c.emit(Done{Direction: "send", Name: base})
	return nil
}

// addCanceler registers an abort func and returns its id.
func (c *Core) addCanceler(f func()) int {
	c.cancelMu.Lock()
	defer c.cancelMu.Unlock()
	id := c.nextCancel
	c.nextCancel++
	c.cancelers[id] = f
	return id
}

func (c *Core) removeCanceler(id int) {
	c.cancelMu.Lock()
	delete(c.cancelers, id)
	c.cancelMu.Unlock()
}

// CancelAll aborts every in-progress transfer (send and receive).
func (c *Core) CancelAll() {
	c.cancelMu.Lock()
	fns := make([]func(), 0, len(c.cancelers))
	for _, f := range c.cancelers {
		fns = append(fns, f)
	}
	c.cancelMu.Unlock()
	for _, f := range fns {
		f()
	}
	c.emit(Canceled{})
}

// Trust responds to a TrustRequest for the given fingerprint.
func (c *Core) Trust(fingerprint string, ok bool) {
	c.mu.Lock()
	ch := c.pending[fingerprint]
	c.mu.Unlock()
	if ch != nil {
		ch <- ok
	}
}

// awaitTrust raises a TrustRequest and blocks the receive until the UI answers.
func (c *Core) awaitTrust(in transfer.Incoming) bool {
	ch := make(chan bool, 1)
	c.mu.Lock()
	c.pending[in.PeerFingerprint] = ch
	c.mu.Unlock()
	c.emit(TrustRequest{Incoming: in})
	select {
	case ok := <-ch:
		c.mu.Lock()
		delete(c.pending, in.PeerFingerprint)
		c.mu.Unlock()
		return ok
	case <-c.ctx.Done():
		return false
	}
}

func (c *Core) emit(e Event) {
	select {
	case c.events <- e:
	case <-c.ctx.Done():
	}
}

func baseName(p string) string {
	for i := len(p) - 1; i >= 0; i-- {
		if p[i] == '/' || p[i] == '\\' {
			return p[i+1:]
		}
	}
	return p
}
