package session

import "encoding/binary"

// Control datagrams travel on ChanControl. Both shells speak this layout;
// the core forwards the bytes and does not interpret them.
//
//	type u8 | body
//
// Touch body: action u8, pointer u8, x u16be, y u16be, pressure u16be.
// x and y are 0..65535 across the current video frame.
// Scroll body: x u16be, y u16be, dx i16be, dy i16be.
// Key body: action u8, androidKeyCode u32be, metaState u32be.
// Text, clipboard, and launch bodies are raw UTF-8.
// Button body: id u8.
// Config body: width u16be, height u16be, fps u8, bitrate u32be, limit u16be,
// codec u8, flags u8.
// Display-power and record bodies: one u8.
// Pointer body: action u8, buttons u8, x u16be, y u16be, dx i16be, dy i16be.
const (
	CtrlTouch        byte = 1
	CtrlScroll       byte = 2
	CtrlKey          byte = 3
	CtrlText         byte = 4
	CtrlButton       byte = 5
	CtrlClipboard    byte = 6
	CtrlConfig       byte = 7
	CtrlDisplayPower byte = 8
	CtrlLaunch       byte = 9
	CtrlRecord       byte = 10
	CtrlPointer      byte = 11

	TouchDown byte = 0
	TouchMove byte = 1
	TouchUp   byte = 2

	BtnBack          byte = 1
	BtnHome          byte = 2
	BtnRecents       byte = 3
	BtnNotifications byte = 4
	BtnSettings      byte = 5
	BtnPower         byte = 6
	BtnWake          byte = 7
	BtnSleep         byte = 8
	BtnRotate        byte = 9

	CodecHEVC byte = 1
	CodecH264 byte = 2
	CodecOpus byte = 3
	CodecAAC  byte = 4

	// ConfigFlagScreenOff asks the phone to keep the panel dark.
	ConfigFlagScreenOff byte = 1 << 0
	// ConfigFlagAudioOnly is the "phone audio through Mac speakers" mode.
	ConfigFlagAudioOnly byte = 1 << 1
	// ConfigFlagHeadless is universal control: input without a video window.
	ConfigFlagHeadless byte = 1 << 2
	// ConfigFlagAppDisplay asks for a fixed virtual display running one app.
	ConfigFlagAppDisplay byte = 1 << 3

	PointerMove  byte = 0
	PointerDown  byte = 1
	PointerUp    byte = 2
	PointerWheel byte = 3

	PointerLeft   byte = 1 << 0
	PointerRight  byte = 1 << 1
	PointerMiddle byte = 1 << 2
)

// Touch encodes a normalized touch or mouse-button event.
func Touch(action, pointer byte, x, y, pressure uint16) []byte {
	b := []byte{CtrlTouch, action, pointer, 0, 0, 0, 0, 0, 0}
	binary.BigEndian.PutUint16(b[3:5], x)
	binary.BigEndian.PutUint16(b[5:7], y)
	binary.BigEndian.PutUint16(b[7:9], pressure)
	return b
}

// Key encodes an Android key down or up.
func Key(action byte, keyCode, meta uint32) []byte {
	b := make([]byte, 10)
	b[0] = CtrlKey
	b[1] = action
	binary.BigEndian.PutUint32(b[2:6], keyCode)
	binary.BigEndian.PutUint32(b[6:10], meta)
	return b
}

// Button encodes a navigation or power command.
func Button(id byte) []byte { return []byte{CtrlButton, id} }

// Config encodes the stream the Mac wants.
func Config(width, height uint16, fps byte, bitrate uint32, limit uint16, codec, flags byte) []byte {
	b := make([]byte, 13)
	b[0] = CtrlConfig
	binary.BigEndian.PutUint16(b[1:3], width)
	binary.BigEndian.PutUint16(b[3:5], height)
	b[5] = fps
	binary.BigEndian.PutUint32(b[6:10], bitrate)
	binary.BigEndian.PutUint16(b[10:12], limit)
	b[12] = codec
	// flags live in an extra byte; older readers that stop at 13 still work
	// because they ignore a trailing byte only if we keep it inside the
	// datagram. Include it.
	return append(b, flags)
}
