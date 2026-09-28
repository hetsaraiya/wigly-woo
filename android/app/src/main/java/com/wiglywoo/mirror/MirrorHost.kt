package com.wiglywoo.mirror

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wiglywoo.CompanionManager
import com.wiglywoo.CoreBridge
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * One mirror at a time. The Mac asks over the relay; the phone dials the
 * pinned session and either the Shizuku server or the basic capture path
 * reads the sockets.
 */
object MirrorHost {
    var statusText by mutableStateOf("Off")
        private set
    var basicConsent by mutableStateOf(false)
        private set

    private var app: Context? = null
    private var sessionId: String? = null
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
        publishCaps()
    }

    fun onRelay(message: JSONObject) {
        when (message.optString("type")) {
            "mirror_request" -> {
                val addr = message.optString("addr")
                val fp = message.optString("fingerprint")
                configJson = message.optJSONObject("config")?.toString() ?: "{}"
                if (addr.isBlank() || fp.isBlank()) {
                    fail("The Mac did not send an address")
                    return
                }
                if (CoreBridge.loadError != null) {
                    fail("Phone core is not running")
                    return
                }
                statusText = if (ShizukuMirrorBridge.ready()) "Connecting" else "Basic mirroring needs a tap"
                basicConsent = !ShizukuMirrorBridge.ready()
                app?.let { MirrorSessionService.start(it) }
                CoreBridge.allowFingerprint(fp)
                CoreBridge.dialSession(addr, fp)
            }
            "mirror_stop" -> stop("The Mac stopped mirroring")
            "unlock_request" -> Unit
            "send_recent" -> sendRecent(message.optString("uri"))
            "open_received" -> openReceived(message.optString("name"))
        }
    }

    fun onCore(event: JSONObject) {
        when (event.optString("type")) {
            "session_open" -> if (event.optString("role") == "dial" || sessionId == null) open(event)
            "session_closed" -> if (event.optString("id") == sessionId) stop("Session ended")
            "session_error" -> fail(event.optString("message").ifBlank { "Could not open the session" })
            "hotspot_ready" -> {
                val ssid = event.optString("ssid")
                val psk = event.optString("psk")
                if (ssid.isNotEmpty()) {
                    CompanionManager.send(JSONObject().put("type", "hotspot_offer").put("ssid", ssid).put("psk", psk))
                }
            }
        }
    }

    fun stop(reason: String) {
        val id = sessionId
        sessionId = null
        id?.let { CoreBridge.closeSession(it) }
        ShizukuMirrorBridge.stop()
        ProjectionFallback.stop()
        pending?.let { close(it) }
        pending = null
        statusText = "Off"
        basicConsent = false
        if (reason.isNotEmpty()) {
            CompanionManager.send(JSONObject().put("type", "mirror_error").put("reason", reason))
        }
        app?.let {
            it.stopService(Intent(it, MirrorSessionService::class.java))
        }
    }

    fun startBasic(context: Context, projection: MediaProjection) {
        val held = pending ?: return
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

    private fun open(event: JSONObject) {
        val id = event.optString("id")
        sessionId = id
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
                statusText = "Mirroring"
                basicConsent = false
                CompanionManager.send(JSONObject().put("type", "mirror_ready").put("mode", "shizuku"))
                app?.let { publishRecent(it, held.meta) }
                return
            }
            fail(result)
            return
        }
        statusText = "Basic mirroring needs a tap"
        basicConsent = true
        CompanionManager.send(JSONObject().put("type", "mirror_error").put("reason", "basic"))
    }

    private fun fail(reason: String) {
        statusText = if (reason == "shizuku" || reason == "basic") {
            "Privileged features paused — restart Shizuku"
        } else reason
        CompanionManager.send(JSONObject().put("type", "mirror_error").put("reason", reason))
    }

    private fun close(held: Pending) {
        listOf(held.video, held.audio, held.control, held.meta).forEach { runCatching { it.close() } }
    }

    private fun publishRecent(context: Context, meta: ParcelFileDescriptor) {
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
            var n = 0
            while (it.moveToNext() && n < 12) {
                val id = it.getLong(0)
                val name = it.getString(1) ?: "photo"
                val item = android.content.ContentUris.withAppendedId(uri, id)
                rows.put(JSONObject().put("name", name).put("uri", item.toString()))
                n++
            }
        }
        runCatching {
            FileOutputStream(meta.fileDescriptor).write(JSONObject().put("type", "recent").put("files", rows).toString().toByteArray())
        }
    }

    private fun sendRecent(uri: String) {
        val ctx = app ?: return
        if (uri.isBlank()) return
        val peer = com.wiglywoo.WooState.peers.firstOrNull() ?: return
        val parsed = android.net.Uri.parse(uri)
        val pfd = runCatching { ctx.contentResolver.openFileDescriptor(parsed, "r") }.getOrNull() ?: return
        val name = parsed.lastPathSegment ?: "photo"
        CoreBridge.sendFd(peer.id, pfd.detachFd(), name, -1)
    }

    private fun openReceived(name: String) {
        val ctx = app ?: return
        if (name.isBlank()) return
        val dir = java.io.File(ctx.getExternalFilesDir(null), "incoming")
        val file = java.io.File(dir, name)
        if (!file.exists()) return
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(view) }
    }

    private fun publishCaps() {
        if (CoreBridge.loadError != null) return
        var caps = 1 or 256 or 128 // mirror, audio, clipboard
        if (ShizukuMirrorBridge.ready()) caps = caps or 4 or 512 // hotspot, unlock
        if (android.os.Build.VERSION.SDK_INT >= 35) caps = caps or 16
        CoreBridge.setCaps(caps)
    }
}
