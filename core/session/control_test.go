package session

import (
	"encoding/binary"
	"testing"
)

func TestTouchLayout(t *testing.T) {
	b := Touch(TouchMove, 2, 0x0102, 0x0304, 0x0506)
	if b[0] != CtrlTouch || b[1] != TouchMove || b[2] != 2 {
		t.Fatalf("header %v", b[:3])
	}
	if binary.BigEndian.Uint16(b[3:5]) != 0x0102 || binary.BigEndian.Uint16(b[5:7]) != 0x0304 {
		t.Fatalf("coords %x", b)
	}
	if binary.BigEndian.Uint16(b[7:9]) != 0x0506 {
		t.Fatalf("pressure %x", b)
	}
}

func TestKeyLayout(t *testing.T) {
	b := Key(1, 4, 0x1000) // KEYCODE_BACK, meta
	if b[0] != CtrlKey || b[1] != 1 {
		t.Fatalf("header %v", b[:2])
	}
	if binary.BigEndian.Uint32(b[2:6]) != 4 || binary.BigEndian.Uint32(b[6:10]) != 0x1000 {
		t.Fatalf("key %x", b)
	}
}

func TestVideoFlags(t *testing.T) {
	cfg := EncodeMedia(0, 0, 1, []byte{1, 2, 3})
	if VideoFlags(cfg) != FlagKeyframe {
		t.Fatalf("config flags %b", VideoFlags(cfg))
	}
	key := EncodeMedia(1, 1, 2, []byte{9})
	if VideoFlags(key) != FlagDroppable|FlagKeyframe {
		t.Fatalf("key flags %b", VideoFlags(key))
	}
	p := EncodeMedia(1, 0, 3, []byte{9})
	if VideoFlags(p) != FlagDroppable {
		t.Fatalf("p flags %b", VideoFlags(p))
	}
}
