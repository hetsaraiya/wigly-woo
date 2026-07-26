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

static void woo_dispatch(woo_event_cb cb, const char* json) {
    if (cb != 0) { cb(json); }
}
*/
import "C"

import (
	"encoding/json"
	"sync"
	"unsafe"

	"github.com/wiglywoo/core/woocore"
)

var (
	mu      sync.Mutex
	core    *woocore.Core
	eventCB C.woo_event_cb
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
	b, _ := json.Marshal(map[string]any{"name": id.Name, "fingerprint": id.Fingerprint})
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

// pump translates core events into JSON envelopes and dispatches them.
func pump(c *woocore.Core) {
	for e := range c.Events() {
		var m map[string]any
		switch ev := e.(type) {
		case woocore.PeerFound:
			m = map[string]any{"type": "peer_found", "id": ev.Peer.ID, "name": ev.Peer.Name,
				"addr": ev.Peer.Addr.String(), "port": ev.Peer.Port, "fingerprint": ev.Peer.Fingerprint}
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
