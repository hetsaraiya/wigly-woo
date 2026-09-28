package link

import (
	"errors"
	"net"
	"testing"

	"github.com/wiglywoo/core/discovery"
)

type fakeLevers struct {
	ssid, psk string
	upErr     error
	joined    []string
	joinErr   error
	ups       int
}

func (f *fakeLevers) BringUpHotspot() (string, string, error) {
	f.ups++
	if f.upErr != nil {
		return "", "", f.upErr
	}
	return f.ssid, f.psk, nil
}
func (f *fakeLevers) JoinHotspot(ssid, psk string) error {
	f.joined = append(f.joined, ssid+"/"+psk)
	return f.joinErr
}
func (f *fakeLevers) StartWifiDirect() error { return ErrUnsupported }

func TestHotspotStaysOffUntilArmed(t *testing.T) {
	m := NewManager(&fakeLevers{ssid: "Wigly", psk: "secret"})
	_, _, err := m.Connect(discovery.Peer{})
	if err == nil || !errors.Is(err, ErrUnsupported) {
		t.Fatalf("err = %v", err)
	}
}

func TestHotspotHostWaitsForPeer(t *testing.T) {
	levers := &fakeLevers{ssid: "Wigly", psk: "secret"}
	m := NewManager(levers)
	var gotSSID, gotPSK string
	m.SetHotspotReady(func(ssid, psk string) { gotSSID, gotPSK = ssid, psk })
	m.ConfigureHotspot(HotspotHost, "", "")

	_, _, err := m.Connect(discovery.Peer{})
	if !errors.Is(err, ErrAwaitingPeer) {
		t.Fatalf("err = %v", err)
	}
	if gotSSID != "Wigly" || gotPSK != "secret" {
		t.Fatalf("ready %q %q", gotSSID, gotPSK)
	}
	if levers.ups != 1 {
		t.Fatalf("brought up %d times", levers.ups)
	}
	// A second resolve must not toggle the radio again.
	peer := discovery.Peer{Addr: net.IPv4(192, 168, 43, 12), Port: 54545}
	addr, strategy, err := m.Connect(peer)
	if err != nil {
		t.Fatal(err)
	}
	if strategy != "same-lan" || addr != "192.168.43.12:54545" {
		t.Fatalf("got %s via %s", addr, strategy)
	}
	if levers.ups != 1 {
		t.Fatalf("second resolve toggled radio: %d", levers.ups)
	}
}

func TestHotspotJoinUsesCredentials(t *testing.T) {
	levers := &fakeLevers{}
	m := NewManager(levers)
	m.ConfigureHotspot(HotspotJoin, "Phone", "pw")
	peer := discovery.Peer{Addr: net.IPv4(192, 168, 43, 1), Port: 9}
	// SameLAN wins when the peer already has an address. Force the hotspot
	// path by clearing the address first, then confirming the join happened.
	_, _, err := m.Connect(discovery.Peer{})
	if !errors.Is(err, ErrAwaitingPeer) {
		t.Fatalf("err = %v", err)
	}
	if len(levers.joined) != 1 || levers.joined[0] != "Phone/pw" {
		t.Fatalf("joined %#v", levers.joined)
	}
	addr, strategy, err := m.Connect(peer)
	if err != nil || strategy != "same-lan" || addr != "192.168.43.1:9" {
		t.Fatalf("addr %s strategy %s err %v", addr, strategy, err)
	}
}
