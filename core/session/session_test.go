package session

import (
	"bytes"
	"context"
	"syscall"
	"testing"
	"time"

	"github.com/wiglywoo/core/crypto"
)

func TestSessionRoundTripAndPriority(t *testing.T) {
	serverID, err := crypto.NewIdentity("mac")
	if err != nil {
		t.Fatal(err)
	}
	clientID, err := crypto.NewIdentity("phone")
	if err != nil {
		t.Fatal(err)
	}
	ln, err := Listen("127.0.0.1:0", serverID, func(fp string) bool {
		return fp == clientID.Fingerprint
	})
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	dialed, err := Dial(ctx, ln.ln.Addr().String(), clientID, serverID.Fingerprint)
	if err != nil {
		t.Fatal(err)
	}
	defer dialed.Close()
	accepted, err := ln.Accept(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer accepted.Close()
	if accepted.Fingerprint != clientID.Fingerprint {
		t.Fatalf("listener saw %s", accepted.Fingerprint)
	}
	if dialed.Role != "dial" || accepted.Role != "listen" {
		t.Fatalf("roles %s %s", dialed.Role, accepted.Role)
	}

	_, _, dialCtl, _ := dialed.TakeFDs()
	accVideo, _, accCtl, accMeta := accepted.TakeFDs()
	defer closeFD(dialCtl)
	defer closeFD(accVideo)
	defer closeFD(accCtl)
	defer closeFD(accMeta)

	// A late video frame and a control message. Control must arrive, and it
	// must not be stuck behind the video datagram.
	if err := writeFD(dialCtl, Button(BtnBack)); err != nil {
		t.Fatal(err)
	}
	got, err := readUntil(accCtl, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, Button(BtnBack)) {
		t.Fatalf("control %x", got)
	}

	// The dialer did not take its video fd; write a media frame from the
	// accepted side's peer by using the dialer's still-open video remote.
	dialVideo := dialed.remote[0]
	frame := EncodeMedia(1, 1, 42, []byte{0xaa, 0xbb})
	if err := writeFD(dialVideo, frame); err != nil {
		t.Fatal(err)
	}
	got, err = readUntil(accVideo, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, frame) {
		t.Fatalf("video %x", got)
	}

	// A keyframe-sized record, which a unix datagram could fail to carry.
	big := EncodeMedia(1, 1, 43, bytes.Repeat([]byte{0x5a}, 900<<10))
	if err := writeFD(dialVideo, big); err != nil {
		t.Fatal(err)
	}
	got, err = readUntil(accVideo, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, big) {
		t.Fatalf("big video: %d bytes, want %d", len(got), len(big))
	}
}

func TestDialRejectsWrongFingerprint(t *testing.T) {
	serverID, err := crypto.NewIdentity("mac")
	if err != nil {
		t.Fatal(err)
	}
	clientID, err := crypto.NewIdentity("phone")
	if err != nil {
		t.Fatal(err)
	}
	other, err := crypto.NewIdentity("other")
	if err != nil {
		t.Fatal(err)
	}
	ln, err := Listen("127.0.0.1:0", serverID, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err = Dial(ctx, ln.ln.Addr().String(), clientID, other.Fingerprint)
	if err != ErrFingerprint {
		t.Fatalf("err = %v", err)
	}
}

func TestListenRejectsUnallowed(t *testing.T) {
	serverID, err := crypto.NewIdentity("mac")
	if err != nil {
		t.Fatal(err)
	}
	clientID, err := crypto.NewIdentity("phone")
	if err != nil {
		t.Fatal(err)
	}
	ln, err := Listen("127.0.0.1:0", serverID, func(string) bool { return false })
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	go func() {
		c, err := Dial(context.Background(), ln.ln.Addr().String(), clientID, serverID.Fingerprint)
		if err == nil {
			c.Close()
		}
	}()
	_, err = ln.Accept(ctx)
	if err == nil {
		t.Fatal("accepted a rejected peer")
	}
}

// writeFD writes one record the way a shell does.
func writeFD(fd int, payload []byte) error {
	var buf bytes.Buffer
	if err := writeRecord(&buf, payload); err != nil {
		return err
	}
	b := buf.Bytes()
	for len(b) > 0 {
		n, err := syscall.Write(fd, b)
		if err != nil && err != syscall.EINTR && err != syscall.EAGAIN {
			return err
		}
		if n > 0 {
			b = b[n:]
		}
	}
	return nil
}

// readUntil reads one record from a shell socket, which has a receive timeout.
func readUntil(fd int, d time.Duration) ([]byte, error) {
	deadline := time.Now().Add(d)
	var got []byte
	buf := make([]byte, 64*1024)
	for time.Now().Before(deadline) {
		if len(got) >= recordHeader {
			n := int(got[0])<<24 | int(got[1])<<16 | int(got[2])<<8 | int(got[3])
			if len(got) >= recordHeader+n {
				return got[recordHeader : recordHeader+n], nil
			}
		}
		n, err := syscall.Read(fd, buf)
		if n > 0 {
			got = append(got, buf[:n]...)
			continue
		}
		if err != nil && err != syscall.EAGAIN && err != syscall.EINTR {
			return nil, err
		}
	}
	return nil, context.DeadlineExceeded
}
