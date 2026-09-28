package com.wiglywoo

import org.json.JSONObject

/**
 * CoreBridge is the Kotlin face of the Go core's C ABI, reached through the JNI
 * shim (libwoojni) which forwards to libwoocore. It mirrors the macOS
 * CoreBridge: downcalls in, one event callback out (CoreBridge.onEvent, invoked
 * from native code on the Go callback thread).
 */
object CoreBridge {
    /** Non-null if native libraries failed to load (shown in the UI). */
    @Volatile
    var loadError: Throwable? = null
        private set

    /** Load native libs, capturing any failure instead of crashing at class init. */
    fun load() {
        try {
            System.loadLibrary("woocore") // Go core (C ABI)
            System.loadLibrary("woojni")  // JNI shim that bridges to it
        } catch (t: Throwable) {
            loadError = t
        }
    }

    private external fun nativeStart(config: String): Int
    private external fun nativeStop()
    private external fun nativeIdentity(): String
    private external fun nativePeers(): String
    private external fun nativeSendFile(peerId: String, path: String): Int
    private external fun nativeSendFd(peerId: String, fd: Int, name: String, size: Long): Int
    private external fun nativeTrust(fingerprint: String, ok: Int)
    private external fun nativeCancel()
    private external fun nativeSessionDial(addr: String, fingerprint: String): Int
    private external fun nativeSessionClose(id: String)

    /** Set by the UI to receive event envelopes (already on a worker thread). */
    @Volatile
    var listener: ((JSONObject) -> Unit)? = null

    fun start(name: String, saveDir: String): Int {
        val cfg = JSONObject()
            .put("Name", name)
            .put("SaveDir", saveDir)
            .put("Port", 0)
        return nativeStart(cfg.toString())
    }

    fun stop() = nativeStop()
    fun identity(): JSONObject = JSONObject(nativeIdentity())
    fun peers(): String = nativePeers()
    fun sendFile(peerId: String, path: String): Int = nativeSendFile(peerId, path)

    /** Stream a picked file straight from its fd — no copy. Native owns/closes fd. */
    fun sendFd(peerId: String, fd: Int, name: String, size: Long): Int = nativeSendFd(peerId, fd, name, size)

    fun trust(fingerprint: String, ok: Boolean) = nativeTrust(fingerprint, if (ok) 1 else 0)

    /** Abort the active transfer(s) in either direction. */
    fun cancel() = nativeCancel()

    /** False when the core is not running; the result otherwise arrives as a session event. */
    fun dialSession(addr: String, fingerprint: String): Boolean =
        loadError == null && runCatching { nativeSessionDial(addr, fingerprint) == 0 }.getOrDefault(false)
    fun closeSession(id: String) { if (loadError == null && id.isNotEmpty()) runCatching { nativeSessionClose(id) } }

    /** Called from JNI (woo_jni.c) for every core event. */
    @JvmStatic
    fun onEvent(json: String) {
        runCatching { listener?.invoke(JSONObject(json)) }
    }
}
