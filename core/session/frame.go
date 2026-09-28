// Package session is a long-lived mutual-TLS connection with four byte
// channels (video, audio, control, meta). File transfer stays on its own
// listener; this package only adds a path.
//
// A frame on the TLS connection is:
//
//	channel u8 | flags u8 | length u32be | payload
//
// Channel numbers are stable. Shells never see this header: the FFI hands
// them one stream socket per channel carrying length-prefixed records.
package session

import (
	"encoding/binary"
	"errors"
	"io"
)

const (
	// ChanVideo carries one encoded access unit per datagram.
	ChanVideo byte = 1
	// ChanAudio carries one encoded audio packet per datagram.
	ChanAudio byte = 2
	// ChanControl carries input and commands. It is never dropped.
	ChanControl byte = 3
	// ChanMeta carries small JSON (ready, apps, errors, unlock). It is never dropped.
	ChanMeta byte = 4

	// FlagDroppable marks a payload the mux may discard under backlog.
	FlagDroppable byte = 1 << 0
	// FlagKeyframe marks a video payload that rebuilds the picture.
	FlagKeyframe byte = 1 << 1

	headerSize = 6
	// maxPayload leaves room for a 1080p HEVC keyframe without accepting a
	// stream that could pin memory.
	maxPayload = 8 << 20
)

// ErrTooLarge is returned when a frame claims more than maxPayload bytes.
var ErrTooLarge = errors.New("session frame too large")

// Frame is one multiplexed message.
type Frame struct {
	Channel byte
	Flags   byte
	Payload []byte
}

// WriteFrame writes one frame. It is not safe to call from two goroutines
// on the same writer; the session mux serializes writes.
func WriteFrame(w io.Writer, f Frame) error {
	if len(f.Payload) > maxPayload {
		return ErrTooLarge
	}
	var hdr [headerSize]byte
	hdr[0] = f.Channel
	hdr[1] = f.Flags
	binary.BigEndian.PutUint32(hdr[2:], uint32(len(f.Payload)))
	if _, err := w.Write(hdr[:]); err != nil {
		return err
	}
	if len(f.Payload) == 0 {
		return nil
	}
	_, err := w.Write(f.Payload)
	return err
}

// ReadFrame reads one frame. A short read at EOF returns io.EOF or io.ErrUnexpectedEOF.
func ReadFrame(r io.Reader) (Frame, error) {
	var hdr [headerSize]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return Frame{}, err
	}
	n := binary.BigEndian.Uint32(hdr[2:])
	if n > maxPayload {
		return Frame{}, ErrTooLarge
	}
	payload := make([]byte, n)
	if _, err := io.ReadFull(r, payload); err != nil {
		return Frame{}, err
	}
	return Frame{Channel: hdr[0], Flags: hdr[1], Payload: payload}, nil
}
