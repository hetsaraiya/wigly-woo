package com.wiglywoo.mirror

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wiglywoo.CompanionManager
import com.wiglywoo.CoreBridge
import com.wiglywoo.WooState
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * One mirror at a time. The Mac asks over the relay; the phone dials the
 * pinned session and either the Shizuku server or the basic capture path
 * reads the sockets. Work runs on one worker thread: binding Shizuku blocks,
 * and the relay and core callbacks must not.
 */
object MirrorHost {
    var statusText by mutableStateOf("Off")
        private set
    var basicConsent by mutableStateOf(false)
        private set

    private val worker = Executors.newSingleThreadExecutor()
    /** Keeps Wi-Fi power saving from pausing the radio mid-stream. */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var app: Context? = null
    private var sessionId: String? = null
    private var requested = false
    private var pending: Pending? = null
    private var configJson = "{}"

    private data class Pending(
        val video: ParcelFileDescriptor,
        val audio: ParcelFileDescriptor,
        val control: ParcelFileDescriptor,
        val meta: ParcelFileDescriptor,
    )

    fun install(context: Context) {
        app = context.applicationContext
    }

    fun onRelay(message: JSONObject) {
        when (message.optString("type")) {
            "mirror_request" -> worker.execute { request(message) }
            "mirror_stop" -> worker.execute { stop("", notifyMac = false) }
            "send_recent" -> worker.execute { sendRecent(message.optString("uri")) }
            "open_received" -> openReceived(message.optString("name"))
        }
    }

    fun onCore(event: JSONObject) {
        worker.execute {
            when (event.optString("type")) {
                "session_open" -> if (requested) open(event) else closeUnwanted(event)
                "session_closed" -> if (event.optString("id") == sessionId) stop("", notifyMac = false)
                "session_error" -> if (requested) fail(event.optString("message").ifBlank { "Could not open the session" })
            }
        }
    }

    /** Stop from the phone UI or its notification. The Mac is told. */
    fun stopFromPhone() = worker.execute { stop("Stopped on the phone", notifyMac = true) }

    fun startBasic(context: Context, projection: MediaProjection) = worker.execute {
        val held = pending ?: return@execute
        statusText = "Basic mirroring"
        basicConsent = false
        ProjectionFallback.start(
            context,
            projection,
            FileOutputStream(held.video.fileDescriptor),
            FileInputStream(held.control.fileDescriptor),
        )
        CompanionManager.send(JSONObject().put("type", "mirror_ready").put("mode", "basic"))
    }

    fun deliverProjection(code: Int, data: Intent) {
        val ctx = app ?: return
        MirrorSessionService.startProjection(ctx, code, data)
    }

    private fun request(message: JSONObject) {
        val ctx = app ?: return
        val addr = message.optString("addr")
        val macFp = message.optString("fingerprint")
        configJson = message.optJSONObject("config")?.toString() ?: "{}"
        if (addr.isBlank() || macFp.isBlank()) {
            fail("The Mac did not send an address")
            return
        }
        stop("", notifyMac = false)
        WooState.ensureCore(ctx)?.let {
            fail("Phone core is not running")
            return
        }
        requested = true
        ShizukuMirrorBridge.bind()
        statusText = if (ShizukuMirrorBridge.ready()) "Connecting" else "Basic mirroring needs a tap"
        MirrorSessionService.showWatching(ctx)
        // The Mac only accepts certificates it has been told about. The dial
        // retries until this arrives.
        CompanionManager.send(JSONObject().put("type", "mirror_accept").put("fingerprint", WooState.fingerprint()))
        if (!CoreBridge.dialSession(addr, macFp)) fail("Phone core is not running")
    }

    private fun open(event: JSONObject) {
        requested = false
        sessionId = event.optString("id")
        val held = Pending(
            ParcelFileDescriptor.adoptFd(event.optInt("video")),
            ParcelFileDescriptor.adoptFd(event.optInt("audio")),
            ParcelFileDescriptor.adoptFd(event.optInt("control")),
            ParcelFileDescriptor.adoptFd(event.optInt("meta")),
        )
        pending = held
        if (ShizukuMirrorBridge.ready()) {
            val result = ShizukuMirrorBridge.start(held.video, held.audio, held.control, held.meta, configJson)
            if (result == "ok") {
                holdWifi(true)
                statusText = "Mirroring"
                basicConsent = false
                CompanionManager.send(JSONObject().put("type", "mirror_ready").put("mode", "shizuku"))
                app?.let { publishRecent(it) }
            } else {
                fail(result)
            }
            return
        }
        statusText = "Basic mirroring needs a tap"
        basicConsent = true
        CompanionManager.send(JSONObject().put("type", "mirror_error").put("reason", "basic"))
    }

    /** A session nobody asked for (a stale dial) is closed straight away. */
    private fun closeUnwanted(event: JSONObject) {
        CoreBridge.closeSession(event.optString("id"))
        listOf("video", "audio", "control", "meta").forEach { key ->
            runCatching { ParcelFileDescriptor.adoptFd(event.optInt(key)).close() }
        }
    }

    private fun stop(reason: String, notifyMac: Boolean) {
        requested = false
        holdWifi(false)
        val id = sessionId
        sessionId = null
        id?.let { CoreBridge.closeSession(it) }
        ShizukuMirrorBridge.stop()
        ProjectionFallback.stop()
        pending?.let { close(it) }
        pending = null
        statusText = "Off"
        basicConsent = false
        if (notifyMac) {
            CompanionManager.send(JSONObject().put("type", "mirror_error").put("reason", reason))
        }
        app?.let { MirrorSessionService.hideWatching(it) }
    }

    @Suppress("DEPRECATION") // HIGH_PERF still keeps the radio awake when the screen is off
    private fun holdWifi(on: Boolean) {
        if (!on) {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
            return
        }
        if (wifiLock?.isHeld == true) return
        val wifi = app?.getSystemService(android.net.wifi.WifiManager::class.java) ?: return
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = runCatching { wifi.createWifiLock(mode, "wigly-mirror").apply { setReferenceCounted(false); acquire() } }.getOrNull()
    }

    private fun fail(reason: String) {
        stop("", notifyMac = false)
        statusText = if (reason == "shizuku") "Privileged features paused — restart Shizuku" else reason
        CompanionManager.send(JSONObject().put("type", "mirror_error").put("reason", reason))
    }

    private fun close(held: Pending) {
        listOf(held.video, held.audio, held.control, held.meta).forEach { runCatching { it.close() } }
    }

    /** Recent photos go over the relay: only the Shizuku process writes the meta socket. */
    private fun publishRecent(context: Context) {
        val resolver = context.contentResolver
        val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val rows = org.json.JSONArray()
        val cursor = runCatching {
            resolver.query(uri, arrayOf(
                android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            ), null, null, "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC")
        }.getOrNull() ?: return
        cursor.use {
            while (it.moveToNext() && rows.length() < 12) {
                val item = android.content.ContentUris.withAppendedId(uri, it.getLong(0))
                rows.put(JSONObject().put("name", it.getString(1) ?: "photo").put("uri", item.toString()))
            }
        }
        CompanionManager.send(JSONObject().put("type", "phone_recent").put("files", rows))
    }

    private fun sendRecent(uri: String) {
        val ctx = app ?: return
        if (uri.isBlank()) return
        val peer = WooState.pairedPeer() ?: return
        val parsed = android.net.Uri.parse(uri)
        val pfd = runCatching { ctx.contentResolver.openFileDescriptor(parsed, "r") }.getOrNull() ?: return
        val name = runCatching {
            ctx.contentResolver.query(parsed, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: parsed.lastPathSegment ?: "photo"
        val size = pfd.statSize
        CoreBridge.sendFd(peer.id, pfd.detachFd(), name, size)
    }

    private fun openReceived(name: String) {
        val ctx = app ?: return
        if (name.isBlank()) return
        val file = java.io.File(WooState.incomingDir, name)
        if (!file.exists()) return
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(view) }
    }
}
