// Package discovery finds nearby peers on the current link.
//
// Phase 1 uses a dependency-free UDP-multicast beacon rather than full RFC-6762
// mDNS: every device periodically multicasts a small JSON beacon describing
// itself, and listens for others. Because both ends always run this app, we
// don't need mDNS interop — this is simpler and has zero external deps. The
// public surface (Announce/Browse + Peer) is what the rest of the core depends
// on, so a real mDNS backend can be swapped in later without touching callers.
package discovery

import (
	"context"
	"encoding/json"
	"net"
	"sync"
	"time"
)

// Multicast group + port owned by this app (not the reserved mDNS 5353).
var (
	groupAddr = &net.UDPAddr{IP: net.IPv4(239, 42, 42, 42), Port: 54545}
)

const announceInterval = 2 * time.Second

// Beacon is the payload broadcast on the wire.
type Beacon struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Port        int    `json:"port"`        // TCP port the transfer server listens on
	Fingerprint string `json:"fingerprint"` // TLS cert fingerprint, shown at pairing
}

// Peer is a discovered device.
type Peer struct {
	ID          string
	Name        string
	Addr        net.IP
	Port        int
	Fingerprint string
	LastSeen    time.Time
}

// Announce multicasts the given beacon until ctx is cancelled.
func Announce(ctx context.Context, b Beacon) error {
	conn, err := net.DialUDP("udp4", nil, groupAddr)
	if err != nil {
		return err
	}
	defer conn.Close()

	payload, _ := json.Marshal(b)
	ticker := time.NewTicker(announceInterval)
	defer ticker.Stop()

	// Send one immediately so discovery feels instant.
	_, _ = conn.Write(payload)
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			if _, err := conn.Write(payload); err != nil {
				return err
			}
		}
	}
}

// Browser tracks peers seen on the multicast group.
type Browser struct {
	selfID string

	mu    sync.Mutex
	peers map[string]Peer

	// OnPeer is called when a new peer appears or an existing one updates.
	OnPeer func(Peer)
}

// NewBrowser returns a Browser that ignores beacons from selfID.
func NewBrowser(selfID string) *Browser {
	return &Browser{selfID: selfID, peers: make(map[string]Peer)}
}

// Browse listens for beacons until ctx is cancelled.
func (b *Browser) Browse(ctx context.Context) error {
	conn, err := net.ListenMulticastUDP("udp4", nil, groupAddr)
	if err != nil {
		return err
	}
	defer conn.Close()
	_ = conn.SetReadBuffer(1 << 20)

	go func() {
		<-ctx.Done()
		_ = conn.Close()
	}()

	buf := make([]byte, 2048)
	for {
		n, src, err := conn.ReadFromUDP(buf)
		if err != nil {
			select {
			case <-ctx.Done():
				return nil
			default:
				return err
			}
		}
		var bc Beacon
		if json.Unmarshal(buf[:n], &bc) != nil || bc.ID == "" || bc.ID == b.selfID {
			continue
		}
		peer := Peer{
			ID:          bc.ID,
			Name:        bc.Name,
			Addr:        src.IP,
			Port:        bc.Port,
			Fingerprint: bc.Fingerprint,
			LastSeen:    time.Now(),
		}
		b.mu.Lock()
		_, existed := b.peers[peer.ID]
		b.peers[peer.ID] = peer
		b.mu.Unlock()
		if b.OnPeer != nil && !existed {
			b.OnPeer(peer)
		}
	}
}

// Peers returns a snapshot of currently known peers.
func (b *Browser) Peers() []Peer {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := make([]Peer, 0, len(b.peers))
	for _, p := range b.peers {
		out = append(out, p)
	}
	return out
}

// Lookup returns a peer by ID or name (name match is a convenience for the CLI).
func (b *Browser) Lookup(idOrName string) (Peer, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if p, ok := b.peers[idOrName]; ok {
		return p, true
	}
	for _, p := range b.peers {
		if p.Name == idOrName {
			return p, true
		}
	}
	return Peer{}, false
}
