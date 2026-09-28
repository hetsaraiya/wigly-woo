// Package main (built as c-archive for macOS, c-shared .so for Android) is the
// single C ABI both platform shells cross. It is a thin marshaling layer over
// woocore: downcalls in, one JSON event callback out. See woocore.h.
//
//	macOS:   go build -buildmode=c-archive -o libwoocore.a ./ffi
//	Android: CC=<ndk-clang> GOOS=android GOARCH=arm64 \
//	         go build -buildmode=c-shared -o libwoocore.so ./ffi
package main

/*
#include <stdlib.h>

// Events are pushed up as a single JSON string per event (see woocore.h for the
// envelope shapes). The shell registers one callback.
typedef void (*woo_event_cb)(const char* json);
typedef int (*woo_hotspot_up_fn)(char* ssid, int ssid_cap, char* psk, int psk_cap);
typedef int (*woo_hotspot_join_fn)(const char* ssid, const char* psk);
typedef int (*woo_wifi_direct_fn)(void);

static void woo_dispatch(woo_event_cb cb, const char* json) {
    if (cb != 0) { cb(json); }
}
static int woo_call_up(woo_hotspot_up_fn fn, char* ssid, int ssid_cap, char* psk, int psk_cap) {
    if (fn == 0) return -1;
    return fn(ssid, ssid_cap, psk, psk_cap);
}
static int woo_call_join(woo_hotspot_join_fn fn, const char* ssid, const char* psk) {
    if (fn == 0) return -1;
    return fn(ssid, psk);
}
static int woo_call_direct(woo_wifi_direct_fn fn) {
    if (fn == 0) return -1;
    return fn();
}
*/
import "C"

import (
	"encoding/json"
	"sync"
	"unsafe"

	"github.com/wiglywoo/core/link"
	"github.com/wiglywoo/core/woocore"
)

var (
	mu           sync.Mutex
	core         *woocore.Core
	eventCB      C.woo_event_cb
	hotspotUp    C.woo_hotspot_up_fn
	hotspotJoin  C.woo_hotspot_join_fn
	wifiDirectFn C.woo_wifi_direct_fn
)

//export woo_set_event_cb
func woo_set_event_cb(cb C.woo_event_cb) {
	mu.Lock()
	eventCB = cb
	mu.Unlock()
}

//export woo_start
func woo_start(configJSON *C.char) C.int {
	var cfg woocore.Config
	if s := C.GoString(configJSON); s != "" {
		_ = json.Unmarshal([]byte(s), &cfg)
	}
	c, err := woocore.New(cfg)
	if err != nil {
		return -1
	}
	if err := c.Start(); err != nil {
		return -2
	}
	c.SetLevers(currentLevers())
	mu.Lock()
	core = c
	mu.Unlock()
	go pump(c)
	return 0
}

//export woo_stop
func woo_stop() {
	mu.Lock()
	c := core
	core = nil
	mu.Unlock()
	if c != nil {
		c.Stop()
	}
}

//export woo_identity_json
func woo_identity_json() *C.char {
	mu.Lock()
	c := core
	mu.Unlock()
	if c == nil {
		return C.CString("{}")
	}
	id := c.Identity()
	b, _ := json.Marshal(map[string]any{
		"name": id.Name, "fingerprint": id.Fingerprint, "session": c.SessionPort(),
	})
	return C.CString(string(b)) // caller frees via woo_free
}

//export woo_peers_json
func woo_peers_json() *C.char {
	mu.Lock()
	c := core
	mu.Unlock()
	if c == nil {
		return C.CString("[]")
	}
	out := make([]map[string]any, 0)
	for _, p := range c.Peers() {
		out = append(out, map[string]any{
			"id": p.ID, "name": p.Name, "addr": p.Addr.String(),
			"port": p.Port, "fingerprint": p.Fingerprint,
			"caps": p.Caps, "session": p.Session,
		})
	}
	b, _ := json.Marshal(out)
	return C.CString(string(b)) // caller frees via woo_free
}

//export woo_send_file
func woo_send_file(peerID, path *C.char) C.int {
	mu.Lock()
	c := core
	mu.Unlock()
	if c == nil {
		return -1
	}
	// Non-blocking: progress/done arrive via the event callback.
	go c.SendFile(C.GoString(peerID), C.GoString(path))
	return 0
}

//export woo_send_fd
func woo_send_fd(peerID *C.char, fd C.int, name *C.char, size C.longlong) C.int {
	mu.Lock()
	c := core
	mu.Unlock()
	if c == nil {
		return -1
	}
	// Non-blocking: progress/done arrive via the event callback. The core takes
	// ownership of fd and closes it.
	go c.SendFD(C.GoString(peerID), uintptr(fd), C.GoString(name), int64(size))
	return 0
}

//export woo_cancel
func woo_cancel() {
	mu.Lock()
	c := core
	mu.Unlock()
	if c != nil {
		c.CancelAll()
	}
}

//export woo_trust
func woo_trust(fingerprint *C.char, ok C.int) {
	mu.Lock()
	c := core
	mu.Unlock()
	if c != nil {
		c.Trust(C.GoString(fingerprint), ok != 0)
	}
}

//export woo_free
func woo_free(p *C.char) {
	C.free(unsafe.Pointer(p))
}

//export woo_set_caps
func woo_set_caps(caps C.uint) {
	mu.Lock()
	c := core
	mu.Unlock()
	if c != nil {
		c.SetCaps(uint32(caps))
	}
}

//export woo_session_allow
func woo_session_allow(fingerprint *C.char) {
	mu.Lock()
	c := core
	mu.Unlock()
	if c != nil {
		c.AllowFingerprint(C.GoString(fingerprint))
	}
}

//export woo_session_dial
func woo_session_dial(addr, fingerprint *C.char) C.int {
	mu.Lock()
	c := core
	mu.Unlock()
	if c == nil {
		return -1
	}
	c.DialSession(C.GoString(addr), C.GoString(fingerprint))
	return 0
}

//export woo_session_close
func woo_session_close(id *C.char) {
	mu.Lock()
	c := core
	mu.Unlock()
	if c != nil {
		c.CloseSession(C.GoString(id))
	}
}

//export woo_hotspot_configure
func woo_hotspot_configure(mode C.int, ssid, psk *C.char) C.int {
	mu.Lock()
	c := core
	mu.Unlock()
	if c == nil {
		return -1
	}
	c.ConfigureHotspot(int(mode), C.GoString(ssid), C.GoString(psk))
	return 0
}

//export woo_set_link_levers
func woo_set_link_levers(up C.woo_hotspot_up_fn, join C.woo_hotspot_join_fn, direct C.woo_wifi_direct_fn) {
	mu.Lock()
	hotspotUp = up
	hotspotJoin = join
	wifiDirectFn = direct
	c := core
	mu.Unlock()
	if c != nil {
		c.SetLevers(currentLevers())
	}
}

// cLevers forwards radio actions to the shell. The callbacks must return
// without calling back into the core: Connect is already on a core goroutine.
type cLevers struct{}

func (cLevers) BringUpHotspot() (string, string, error) {
	mu.Lock()
	up := hotspotUp
	mu.Unlock()
	if up == nil {
		return "", "", link.ErrUnsupported
	}
	ssid := (*C.char)(C.calloc(128, 1))
	psk := (*C.char)(C.calloc(128, 1))
	defer C.free(unsafe.Pointer(ssid))
	defer C.free(unsafe.Pointer(psk))
	if C.woo_call_up(up, ssid, 128, psk, 128) != 0 {
		return "", "", link.ErrUnsupported
	}
	return C.GoString(ssid), C.GoString(psk), nil
}

func (cLevers) JoinHotspot(ssid, psk string) error {
	mu.Lock()
	join := hotspotJoin
	mu.Unlock()
	if join == nil {
		return link.ErrUnsupported
	}
	cs, cp := C.CString(ssid), C.CString(psk)
	defer C.free(unsafe.Pointer(cs))
	defer C.free(unsafe.Pointer(cp))
	if C.woo_call_join(join, cs, cp) != 0 {
		return link.ErrUnsupported
	}
	return nil
}

func (cLevers) StartWifiDirect() error {
	mu.Lock()
	fn := wifiDirectFn
	mu.Unlock()
	if fn == nil || C.woo_call_direct(fn) != 0 {
		return link.ErrUnsupported
	}
	return nil
}

func currentLevers() link.Levers {
	mu.Lock()
	defer mu.Unlock()
	if hotspotUp == nil && hotspotJoin == nil && wifiDirectFn == nil {
		return link.NoLevers{}
	}
	return cLevers{}
}

// pump translates core events into JSON envelopes and dispatches them.
func pump(c *woocore.Core) {
	for e := range c.Events() {
		var m map[string]any
		switch ev := e.(type) {
		case woocore.PeerFound:
			m = map[string]any{"type": "peer_found", "id": ev.Peer.ID, "name": ev.Peer.Name,
				"addr": ev.Peer.Addr.String(), "port": ev.Peer.Port, "fingerprint": ev.Peer.Fingerprint,
				"caps": ev.Peer.Caps, "session": ev.Peer.Session}
		case woocore.TrustRequest:
			m = map[string]any{"type": "trust_request", "name": ev.Incoming.PeerName,
				"fingerprint": ev.Incoming.PeerFingerprint, "file": ev.Incoming.FileName, "size": ev.Incoming.Size}
		case woocore.Progress:
			m = map[string]any{"type": "progress", "dir": ev.Direction, "name": ev.Name, "sent": ev.Sent, "total": ev.Total}
		case woocore.Done:
			m = map[string]any{"type": "done", "dir": ev.Direction, "name": ev.Name, "path": ev.Path}
		case woocore.Errorf:
			m = map[string]any{"type": "error", "message": ev.Message}
		case woocore.Canceled:
			m = map[string]any{"type": "canceled"}
		case woocore.SessionOpen:
			m = map[string]any{"type": "session_open", "id": ev.ID, "fingerprint": ev.Fingerprint,
				"role": ev.Role, "video": ev.VideoFD, "audio": ev.AudioFD,
				"control": ev.ControlFD, "meta": ev.MetaFD}
		case woocore.SessionClosed:
			m = map[string]any{"type": "session_closed", "id": ev.ID}
		case woocore.SessionError:
			m = map[string]any{"type": "session_error", "message": ev.Message}
		case woocore.HotspotReady:
			m = map[string]any{"type": "hotspot_ready", "ssid": ev.SSID, "psk": ev.PSK}
		default:
			continue
		}
		b, _ := json.Marshal(m)
		dispatch(b)
	}
}

func dispatch(envelope []byte) {
	mu.Lock()
	cb := eventCB
	mu.Unlock()
	if cb == nil {
		return
	}
	cs := C.CString(string(envelope))
	C.woo_dispatch(cb, cs)
	C.free(unsafe.Pointer(cs))
}

func main() {}
