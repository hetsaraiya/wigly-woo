package com.wiglywoo.ecosystem

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.wiglywoo.CoreBridge
import com.wiglywoo.WooState

/** Sends new screenshots to the paired Mac with the existing file transfer. */
class ScreenshotWatcher(private val context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {
    private var registered = false
    private var lastId = -1L

    fun update(on: Boolean) {
        if (on && !registered) {
            registered = true
            lastId = newest()?.id ?: -1L
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
        } else if (!on && registered) {
            registered = false
            context.contentResolver.unregisterContentObserver(this)
        }
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        val row = newest() ?: return
        if (row.id <= lastId) return
        lastId = row.id
        if (!row.path.contains("Screenshot", ignoreCase = true) && !row.name.contains("Screenshot", ignoreCase = true)) return
        val peer = WooState.pairedPeer() ?: return
        val item = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, row.id)
        val pfd = runCatching { context.contentResolver.openFileDescriptor(item, "r") }.getOrNull() ?: return
        val size = pfd.statSize
        CoreBridge.sendFd(peer.id, pfd.detachFd(), row.name.ifBlank { "screenshot.png" }, size)
    }

    private data class Row(val id: Long, val name: String, val path: String)

    private fun newest(): Row? = runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH),
            null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { if (it.moveToFirst()) Row(it.getLong(0), it.getString(1).orEmpty(), it.getString(2).orEmpty()) else null }
    }.getOrNull()
}
