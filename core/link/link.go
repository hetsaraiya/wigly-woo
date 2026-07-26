// Package link is the fallback chain: it decides HOW two devices get onto a
// shared subnet, then hands a dialable address up to the transfer layer.
//
// Strategies are tried in order; the first that can reach the peer wins. The
// transfer layer never learns which one won — that decoupling is the whole
// point. Phase 1 ships SameLAN working; Hotspot and WifiDirect are stubs whose
// actual radio work is fulfilled by the platform shell via the FFI "link
// levers" (the core cannot toggle radios portably).
package link

import (
	"errors"
	"fmt"

	"github.com/wiglywoo/core/discovery"
)

// ErrUnsupported means a strategy cannot run on this device/OS right now.
var ErrUnsupported = errors.New("link strategy unsupported here")

// Levers are the radio actions only the platform shell can perform. The core
// calls these; each shell implements them for its OS (or returns ErrUnsupported).
type Levers interface {
	BringUpHotspot() (ssid, psk string, err error)
	JoinHotspot(ssid, psk string) error
	StartWifiDirect() error
}

// NoLevers is the default for environments with no radio control (e.g. the CLI
// running on an already-connected LAN). Every lever is unsupported.
type NoLevers struct{}

func (NoLevers) BringUpHotspot() (string, string, error) { return "", "", ErrUnsupported }
func (NoLevers) JoinHotspot(string, string) error        { return ErrUnsupported }
func (NoLevers) StartWifiDirect() error                  { return ErrUnsupported }

// Strategy resolves a peer to a dialable "host:port" address.
type Strategy interface {
	Name() string
	Resolve(p discovery.Peer) (addr string, err error)
}

// SameLAN: the peer's beacon already carries a routable IP and port. Nothing to
// do — we're on the same subnet, just dial it.
type SameLAN struct{}

func (SameLAN) Name() string { return "same-lan" }
func (SameLAN) Resolve(p discovery.Peer) (string, error) {
	if p.Addr == nil || p.Port == 0 {
		return "", fmt.Errorf("peer has no address")
	}
	return fmt.Sprintf("%s:%d", p.Addr.String(), p.Port), nil
}

// Hotspot: bring up / join a phone hotspot so both ends share a subnet without
// any existing Wi-Fi. The radio work is delegated to the shell's Levers.
type Hotspot struct{ Levers Levers }

func (Hotspot) Name() string { return "hotspot" }
func (h Hotspot) Resolve(p discovery.Peer) (string, error) {
	// Real implementation: negotiate who hosts, call Levers.BringUpHotspot /
	// JoinHotspot, then re-discover the peer on the new subnet. Phase 2.
	return "", ErrUnsupported
}

// WifiDirect: peer-to-peer Wi-Fi where both ends support it (Android does;
// macOS has no clean public API — hence Phase 2 / stretch).
type WifiDirect struct{ Levers Levers }

func (WifiDirect) Name() string { return "wifi-direct" }
func (w WifiDirect) Resolve(p discovery.Peer) (string, error) {
	return "", ErrUnsupported
}

// Manager tries strategies in order and returns the first address that resolves.
type Manager struct {
	strategies []Strategy
}

// NewManager builds the default Phase 1 chain: LAN, then hotspot, then direct.
func NewManager(levers Levers) *Manager {
	if levers == nil {
		levers = NoLevers{}
	}
	return &Manager{strategies: []Strategy{
		SameLAN{},
		Hotspot{Levers: levers},
		WifiDirect{Levers: levers},
	}}
}

// Connect returns a dialable address for the peer plus the winning strategy name.
func (m *Manager) Connect(p discovery.Peer) (addr, strategy string, err error) {
	var errs []error
	for _, s := range m.strategies {
		a, err := s.Resolve(p)
		if err == nil {
			return a, s.Name(), nil
		}
		errs = append(errs, fmt.Errorf("%s: %w", s.Name(), err))
	}
	return "", "", fmt.Errorf("no link strategy connected: %w", errors.Join(errs...))
}
