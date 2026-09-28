package woocore

import (
	"syscall"
	"testing"
	"time"
)

func TestSessionDialBetweenCores(t *testing.T) {
	mac, err := New(Config{Name: "mac", SaveDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	phone, err := New(Config{Name: "phone", SaveDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	if err := mac.Start(); err != nil {
		t.Fatal(err)
	}
	if err := phone.Start(); err != nil {
		t.Fatal(err)
	}
	defer mac.Stop()
	defer phone.Stop()

	macEvents := drain(mac)
	phoneEvents := drain(phone)
	mac.AllowFingerprint(phone.Identity().Fingerprint)
	phone.DialSession(FormatSessionAddr("127.0.0.1", mac.SessionPort()), mac.Identity().Fingerprint)

	macOpen := waitSession(t, macEvents)
	phoneOpen := waitSession(t, phoneEvents)
	if macOpen.Role != "listen" || phoneOpen.Role != "dial" {
		t.Fatalf("roles %s %s", macOpen.Role, phoneOpen.Role)
	}
	if macOpen.Fingerprint != phone.Identity().Fingerprint {
		t.Fatalf("mac saw %s", macOpen.Fingerprint)
	}
	for _, fd := range []int{macOpen.VideoFD, macOpen.AudioFD, macOpen.ControlFD, macOpen.MetaFD, phoneOpen.VideoFD, phoneOpen.AudioFD, phoneOpen.ControlFD, phoneOpen.MetaFD} {
		syscall.Close(fd)
	}
	mac.CloseSession(macOpen.ID)
	if _, ok := waitClosed(t, macEvents); !ok {
		t.Fatal("session did not close")
	}
}

func drain(c *Core) <-chan Event {
	out := make(chan Event, 32)
	go func() {
		for e := range c.Events() {
			out <- e
		}
	}()
	return out
}

func waitSession(t *testing.T, events <-chan Event) SessionOpen {
	t.Helper()
	deadline := time.After(5 * time.Second)
	for {
		select {
		case <-deadline:
			t.Fatal("timed out waiting for session_open")
		case e := <-events:
			if open, ok := e.(SessionOpen); ok {
				return open
			}
		}
	}
}

func waitClosed(t *testing.T, events <-chan Event) (SessionClosed, bool) {
	t.Helper()
	deadline := time.After(5 * time.Second)
	for {
		select {
		case <-deadline:
			return SessionClosed{}, false
		case e := <-events:
			if closed, ok := e.(SessionClosed); ok {
				return closed, true
			}
		}
	}
}
