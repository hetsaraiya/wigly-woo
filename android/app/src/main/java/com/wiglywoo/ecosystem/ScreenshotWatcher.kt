package com.wiglywoo.ecosystem

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.wiglywoo.CoreBridge
import com.wiglywoo.WooState

/** Sends new screenshots to the Mac with the existing file transfer. */
class ScreenshotWatcher(private val context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {
    private var lastId = -1L

    fun start() {
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        if (!EcosystemSettings.load(context).screenshots) return
        val resolver = context.contentResolver
        val cursor = runCatching {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH),
                null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )
        }.getOrNull() ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val id = it.getLong(0)
            if (id == lastId) return
            val path = it.getString(2).orEmpty()
            val name = it.getString(1).orEmpty()
            if (!path.contains("Screenshot", ignoreCase = true) && !name.contains("Screenshot", ignoreCase = true)) return
            lastId = id
            val item = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val peer = WooState.peers.firstOrNull() ?: return
            val pfd = resolver.openFileDescriptor(item, "r") ?: return
            val size = pfd.statSize
            CoreBridge.sendFd(peer.id, pfd.detachFd(), name.ifBlank { "screenshot.png" }, size)
        }
    }
}
