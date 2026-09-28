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
	"net"
	"strconv"
	"sync"

	"github.com/wiglywoo/core/discovery"
)

// ErrUnsupported means a strategy cannot run on this device/OS right now.
var ErrUnsupported = errors.New("link strategy unsupported here")

// ErrAwaitingPeer means the hotspot radio is up (or the join succeeded) and
// discovery has not yet seen the peer on that network.
var ErrAwaitingPeer = errors.New("hotspot is up; waiting for the peer")

// Hotspot modes. Off is the default so an ordinary send never toggles a radio.
const (
	HotspotOff  = 0
	HotspotHost = 1
	HotspotJoin = 2
)

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

// Hotspot brings up or joins a phone hotspot so both ends share a subnet.
// The radio work is delegated to the shell's Levers. Resolve stays unsupported
// until the shell calls Configure — a failed LAN send must not toggle the radio.
type Hotspot struct {
	Levers Levers
	// OnReady is called once when this device has brought a hotspot up.
	OnReady func(ssid, psk string)

	mu      sync.Mutex
	mode    int
	ssid    string
	psk     string
	brought bool
}

// Configure arms the strategy. Host ignores ssid and psk; the shell's lever
// reports them. Join uses the credentials the other device sent.
func (h *Hotspot) Configure(mode int, ssid, psk string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.mode = mode
	h.ssid = ssid
	h.psk = psk
	h.brought = false
}

// Credentials returns the last SSID and passphrase this device hosted or was
// told to join.
func (h *Hotspot) Credentials() (ssid, psk string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.ssid, h.psk
}

func (h *Hotspot) Name() string { return "hotspot" }

func (h *Hotspot) Resolve(p discovery.Peer) (string, error) {
	if h == nil || h.Levers == nil {
		return "", ErrUnsupported
	}
	h.mu.Lock()
	mode, ssid, psk, brought := h.mode, h.ssid, h.psk, h.brought
	h.mu.Unlock()
	if mode == HotspotOff {
		return "", ErrUnsupported
	}
	if mode == HotspotJoin {
		if ssid == "" {
			return "", fmt.Errorf("hotspot join: missing credentials")
		}
		if !brought {
			if err := h.Levers.JoinHotspot(ssid, psk); err != nil {
				return "", err
			}
			h.mu.Lock()
			h.brought = true
			h.mu.Unlock()
		}
	} else if !brought {
		gotSSID, gotPSK, err := h.Levers.BringUpHotspot()
		if err != nil {
			return "", err
		}
		h.mu.Lock()
		h.ssid, h.psk, h.brought = gotSSID, gotPSK, true
		h.mu.Unlock()
		ssid = gotSSID
		if h.OnReady != nil {
			h.OnReady(gotSSID, gotPSK)
		}
	}
	if p.Addr == nil || p.Port == 0 {
		return "", fmt.Errorf("%w (%s)", ErrAwaitingPeer, ssid)
	}
	return net.JoinHostPort(p.Addr.String(), strconv.Itoa(p.Port)), nil
}

// WifiDirect: peer-to-peer Wi-Fi where both ends support it (Android does;
// macOS has no clean public API — hence Phase 2 / stretch).
type WifiDirect struct{ Levers Levers }

func (*WifiDirect) Name() string { return "wifi-direct" }
func (w *WifiDirect) Resolve(p discovery.Peer) (string, error) {
	// macOS has no public Wi-Fi Direct API, so a phone that starts a group
	// still has nothing to join. Instant Hotspot is the substitute.
	return "", ErrUnsupported
}

// Manager tries strategies in order and returns the first address that resolves.
type Manager struct {
	hotspot    *Hotspot
	direct     *WifiDirect
	strategies []Strategy
}

// NewManager builds the default chain: LAN, then hotspot, then direct.
// Hotspot does nothing until ConfigureHotspot arms it.
func NewManager(levers Levers) *Manager {
	if levers == nil {
		levers = NoLevers{}
	}
	h := &Hotspot{Levers: levers}
	d := &WifiDirect{Levers: levers}
	return &Manager{
		hotspot:    h,
		direct:     d,
		strategies: []Strategy{SameLAN{}, h, d},
	}
}

// SetLevers replaces the shell callbacks used by hotspot and Wi-Fi Direct.
func (m *Manager) SetLevers(levers Levers) {
	if levers == nil {
		levers = NoLevers{}
	}
	if m.hotspot != nil {
		m.hotspot.Levers = levers
	}
	if m.direct != nil {
		m.direct.Levers = levers
	}
}

// ConfigureHotspot arms or disarms the hotspot strategy.
func (m *Manager) ConfigureHotspot(mode int, ssid, psk string) {
	if m.hotspot != nil {
		m.hotspot.Configure(mode, ssid, psk)
	}
}

// SetHotspotReady receives the SSID and passphrase when this device hosts.
func (m *Manager) SetHotspotReady(fn func(ssid, psk string)) {
	if m.hotspot != nil {
		m.hotspot.OnReady = fn
	}
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
