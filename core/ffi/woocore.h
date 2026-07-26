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

#ifdef __cplusplus
}
#endif

#endif /* WOOCORE_H */
