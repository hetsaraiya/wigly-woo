package session

import (
	"bytes"
	"io"
	"testing"
)

func TestFrameRoundTrip(t *testing.T) {
	frames := []Frame{
		{Channel: ChanControl, Payload: []byte("back")},
		{Channel: ChanVideo, Flags: FlagDroppable | FlagKeyframe, Payload: []byte{0, 1, 2, 3, 4}},
		{Channel: ChanAudio, Flags: FlagDroppable, Payload: nil},
		{Channel: ChanMeta, Payload: []byte(`{"type":"ready"}`)},
	}
	var buf bytes.Buffer
	for _, f := range frames {
		if err := WriteFrame(&buf, f); err != nil {
			t.Fatal(err)
		}
	}
	for _, want := range frames {
		got, err := ReadFrame(&buf)
		if err != nil {
			t.Fatal(err)
		}
		if got.Channel != want.Channel || got.Flags != want.Flags || !bytes.Equal(got.Payload, want.Payload) {
			t.Fatalf("got %+v payload %x, want %+v payload %x", got, got.Payload, want, want.Payload)
		}
	}
	if _, err := ReadFrame(&buf); err != io.EOF {
		t.Fatalf("tail error = %v", err)
	}
}

func TestFrameRejectsHugeLength(t *testing.T) {
	var hdr [headerSize]byte
	hdr[0] = ChanVideo
	hdr[2], hdr[3], hdr[4], hdr[5] = 0x7f, 0, 0, 0
	_, err := ReadFrame(bytes.NewReader(hdr[:]))
	if err != ErrTooLarge {
		t.Fatalf("err = %v", err)
	}
}

func TestWriteFrameRejectsHugePayload(t *testing.T) {
	err := WriteFrame(&bytes.Buffer{}, Frame{Channel: ChanVideo, Payload: make([]byte, maxPayload+1)})
	if err != ErrTooLarge {
		t.Fatalf("err = %v", err)
	}
}
