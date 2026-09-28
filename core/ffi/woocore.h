/*
 * woocore.h — the one C ABI both platform shells cross.
 *
 * This is the hand-written reference contract; building with
 * `-buildmode=c-archive` also emits a generated header next to the .a, which is
 * ABI-compatible with this one. macOS includes this via a module map; the
 * Android JNI shim #includes it.
 *
 * Memory: any `char*` RETURNED by this library is heap-allocated by Go and must
 * be released with woo_free(). Strings PASSED IN are copied; the caller keeps
 * ownership.
 *
 * Threading: the event callback fires on a Go-owned goroutine/thread. Shells
 * must marshal to their UI thread (DispatchQueue.main / runOnUiThread).
 */
#ifndef WOOCORE_H
#define WOOCORE_H

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Events: core -> shell ------------------------------------------------
 * One callback delivers every event as a JSON string. Envelope shapes:
 *   {"type":"peer_found","id","name","addr","port","fingerprint"}
 *   {"type":"trust_request","name","fingerprint","file","size"}
 *   {"type":"progress","dir":"send|recv","name","sent","total"}
 *   {"type":"done","dir":"send|recv","name","path"}
 *   {"type":"error","message"}
 *   {"type":"peer_found",...,"caps","session"}
 *   {"type":"session_open","id","fingerprint","role","video","audio","control","meta"}
 *   {"type":"session_closed","id"}
 *   {"type":"session_error","message"}
 *   {"type":"hotspot_ready","ssid","psk"}
 *
 * session_open file descriptors are datagram sockets owned by the shell.
 * hotspot_ready stays in-process; do not put the passphrase on a beacon.
 */
typedef void (*woo_event_cb)(const char* json);
extern void woo_set_event_cb(woo_event_cb cb);

/* ---- Lifecycle ------------------------------------------------------------
 * config_json: {"Name":"...","SaveDir":"...","Port":0}
 * returns 0 on success, negative on error.
 */
extern int  woo_start(const char* config_json);
extern void woo_stop(void);

/* {"name","fingerprint"} — show the fingerprint at pairing. Caller frees. */
extern char* woo_identity_json(void);

/* ---- Downcalls: shell -> core --------------------------------------------- */

/* JSON array of peers; each {id,name,addr,port,fingerprint}. Caller frees. */
extern char* woo_peers_json(void);

/* Send `path` to the peer with the given id. Non-blocking; watch events. */
extern int  woo_send_file(const char* peer_id, const char* path);

/* Send an already-open file descriptor (Android: content:// URIs have no path).
 * Streams straight from fd with no copy. Takes ownership of fd and closes it.
 * Non-blocking; watch events. */
extern int  woo_send_fd(const char* peer_id, int fd, const char* name, long long size);

/* Answer a trust_request: ok != 0 accepts the incoming transfer. */
extern void woo_trust(const char* fingerprint, int ok);

/* Abort every in-progress transfer (send or receive). Emits a "canceled" event. */
extern void woo_cancel(void);

/* Release a string returned by this library. */
extern void woo_free(char* p);

/* Capability bits advertised on the next beacon. */
extern void woo_set_caps(unsigned int caps);

/* Let this certificate open a media session to us. */
extern void woo_session_allow(const char* fingerprint);

/* Dial host:port and require that server certificate. Result is an event. */
extern int  woo_session_dial(const char* addr, const char* fingerprint);

/* End one media session. */
extern void woo_session_close(const char* id);

/* mode: 0 off, 1 host, 2 join. ssid and psk are used for join. */
extern int  woo_hotspot_configure(int mode, const char* ssid, const char* psk);

/* Shell radio callbacks. Return 0 on success. Buffers are NUL-terminated. */
typedef int (*woo_hotspot_up_fn)(char* ssid, int ssid_cap, char* psk, int psk_cap);
typedef int (*woo_hotspot_join_fn)(const char* ssid, const char* psk);
typedef int (*woo_wifi_direct_fn)(void);
extern void woo_set_link_levers(woo_hotspot_up_fn up, woo_hotspot_join_fn join, woo_wifi_direct_fn direct);

#ifdef __cplusplus
}
#endif

#endif /* WOOCORE_H */
