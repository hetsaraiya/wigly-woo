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

func TestMuxKeepsCodecConfigWhenDropping(t *testing.T) {
	m := NewMux()
	m.Enqueue(Frame{Channel: ChanVideo, Flags: FlagKeyframe, Payload: []byte("config")})
	for i := 0; i < videoQueueCap+2; i++ {
		m.Enqueue(Frame{Channel: ChanVideo, Flags: FlagDroppable, Payload: []byte{byte(i)}})
	}
	if !m.Dropping() {
		t.Fatal("expected to be dropping")
	}
	m.Enqueue(Frame{Channel: ChanVideo, Flags: FlagDroppable | FlagKeyframe, Payload: []byte("key")})
	for _, want := range []string{"config", "key"} {
		f, ok := m.Next()
		if !ok || string(f.Payload) != want {
			t.Fatalf("want %q, got %q ok=%v", want, f.Payload, ok)
		}
	}
}

func TestMuxKeepsAudioConfig(t *testing.T) {
	m := NewMux()
	m.Enqueue(Frame{Channel: ChanAudio, Payload: []byte("config")})
	for i := 0; i < audioQueueCap+3; i++ {
		m.Enqueue(Frame{Channel: ChanAudio, Flags: FlagDroppable, Payload: []byte{byte(i)}})
	}
	f, _ := m.Next()
	if string(f.Payload) != "config" {
		t.Fatalf("config dropped, got %q", f.Payload)
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

func TestMuxReportsEveryDropUntilKeyframe(t *testing.T) {
	m := NewMux()
	drops := 0
	m.OnDrop = func() { drops++ }
	for i := 0; i < videoQueueCap+5; i++ {
		m.Enqueue(Frame{Channel: ChanVideo, Flags: FlagDroppable, Payload: []byte{byte(i)}})
	}
	// The overflowing frame and the four after it were all dropped.
	if drops != 5 {
		t.Fatalf("drops = %d, want 5", drops)
	}
	m.Enqueue(Frame{Channel: ChanVideo, Flags: FlagDroppable | FlagKeyframe, Payload: []byte("key")})
	m.Enqueue(Frame{Channel: ChanVideo, Flags: FlagDroppable, Payload: []byte("p")})
	if drops != 5 || m.Dropping() {
		t.Fatalf("still dropping after a keyframe (drops=%d)", drops)
	}
}
