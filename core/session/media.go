package session

import "encoding/binary"

// Media datagrams are what the shells write to the video and audio sockets.
// The mux never interprets the codec. It only peeks at this header so it can
// drop late video without dropping a codec config or a keyframe.
//
//	kind u8     0 = codec config, 1 = frame
//	flags u8    bit 0 = keyframe (video)
//	pts u64be   nanoseconds on the sender clock
//	payload     codec bytes
const mediaHeaderSize = 10

// EncodeMedia builds one video or audio datagram.
func EncodeMedia(kind, flags byte, pts uint64, payload []byte) []byte {
	out := make([]byte, mediaHeaderSize+len(payload))
	out[0] = kind
	out[1] = flags
	binary.BigEndian.PutUint64(out[2:10], pts)
	copy(out[mediaHeaderSize:], payload)
	return out
}

// AudioFlags maps an audio datagram onto mux flags. Codec config is kept.
func AudioFlags(payload []byte) byte {
	if len(payload) >= 1 && payload[0] == 0 {
		return 0
	}
	return FlagDroppable
}

// VideoFlags maps a media datagram onto mux flags.
// Codec config is a keyframe and is not droppable. A frame is droppable, and
// a keyframe when flags bit 0 is set. Anything that is not a media datagram
// is droppable so a confused sender cannot pin the queue.
func VideoFlags(payload []byte) byte {
	if len(payload) < 2 {
		return FlagDroppable
	}
	if payload[0] == 0 {
		return FlagKeyframe
	}
	flags := FlagDroppable
	if payload[1]&1 != 0 {
		flags |= FlagKeyframe
	}
	return flags
}
