package session

import "testing"

func TestMuxPriority(t *testing.T) {
	m := NewMux()
	m.Enqueue(Frame{Channel: ChanVideo, Flags: FlagDroppable, Payload: []byte("v")})
	m.Enqueue(Frame{Channel: ChanAudio, Flags: FlagDroppable, Payload: []byte("a")})
	m.Enqueue(Frame{Channel: ChanMeta, Payload: []byte("m")})
	m.Enqueue(Frame{Channel: ChanControl, Payload: []byte("c")})

	var got []byte
	for i := 0; i < 4; i++ {
		f, ok := m.Next()
		if !ok {
			t.Fatal("missing frame")
		}
		got = append(got, f.Channel)
	}
	// Control and meta share a FIFO, and both beat audio, which beats video.
	want := []byte{ChanMeta, ChanControl, ChanAudio, ChanVideo}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("order %v, want %v", got, want)
		}
	}
}

func TestMuxDropsVideoUntilKeyframe(t *testing.T) {
	m := NewMux()
	for i := 0; i < videoQueueCap; i++ {
		m.Enqueue(Frame{Channel: ChanVideo, Flags: FlagDroppable, Payload: []byte{byte(i)}})
	}
	// This one overflows: the queued P-frames are discarded and further
	// P-frames are ignored.
	m.Enqueue(Frame{Channel: ChanVideo, Flags: FlagDroppable, Payload: []byte("overflow")})
	m.Enqueue(Frame{Channel: ChanVideo, Flags: FlagDroppable, Payload: []byte("late")})
	if !m.Dropping() {
		t.Fatal("expected to be dropping")
	}
	m.Enqueue(Frame{Channel: ChanControl, Payload: []byte("still")})
	m.Enqueue(Frame{Channel: ChanVideo, Flags: FlagDroppable | FlagKeyframe, Payload: []byte("key")})

	f, ok := m.Next()
	if !ok || f.Channel != ChanControl || string(f.Payload) != "still" {
		t.Fatalf("control first, got %+v", f)
	}
	f, ok = m.Next()
	if !ok || string(f.Payload) != "key" {
		t.Fatalf("keyframe next, got %+v ok=%v", f, ok)
	}
	m.Close()
	if _, ok := m.Next(); ok {
		t.Fatal("expected closed empty mux")
	}
}

func TestMuxDropsOldestAudio(t *testing.T) {
	m := NewMux()
	for i := 0; i < audioQueueCap+3; i++ {
		m.Enqueue(Frame{Channel: ChanAudio, Flags: FlagDroppable, Payload: []byte{byte(i)}})
	}
	f, ok := m.Next()
	if !ok {
		t.Fatal("empty")
	}
	if f.Payload[0] != 3 {
		t.Fatalf("oldest kept byte %d, want 3", f.Payload[0])
	}
}

func TestMuxCloseUnblocks(t *testing.T) {
	m := NewMux()
	done := make(chan struct{})
	go func() {
		m.Next()
		close(done)
	}()
	m.Close()
	<-done
}
