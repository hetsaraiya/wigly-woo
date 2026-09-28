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
	if err := sendMsg(dialCtl, Button(BtnBack), false); err != nil {
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
	if err := sendMsg(dialVideo, frame, false); err != nil {
		t.Fatal(err)
	}
	got, err = readUntil(accVideo, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, frame) {
		t.Fatalf("video %x", got)
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

func readUntil(fd int, d time.Duration) ([]byte, error) {
	deadline := time.Now().Add(d)
	buf := make([]byte, 64*1024)
	for time.Now().Before(deadline) {
		n, err := syscall.Read(fd, buf)
		if n > 0 {
			return append([]byte(nil), buf[:n]...), nil
		}
		if err != nil && !isRetryableRead(err) && !isTimeout(err) {
			return nil, err
		}
	}
	return nil, context.DeadlineExceeded
}
