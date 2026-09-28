package com.wiglywoo.ecosystem

import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import com.wiglywoo.CompanionManager
import org.json.JSONObject

/**
 * Incoming SMS to the Mac and replies from it, for the sideload build. A Play
 * Store build must not ship these permissions. Codes inside messages reach the
 * Mac through the messaging app's notification, so they are not detected twice.
 */
class SmsBridge(private val context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {
    private var registered = false
    private var lastId = -1L

    fun update(on: Boolean) {
        if (on && !registered) {
            registered = true
            lastId = newestId()
            context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, this)
        } else if (!on && registered) {
            registered = false
            context.contentResolver.unregisterContentObserver(this)
        }
    }

    // One SMS fires several changes; only a new row is sent.
    override fun onChange(selfChange: Boolean, uri: Uri?) {
        val cursor = runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null, null, "${Telephony.Sms.DATE} DESC LIMIT 1",
            )
        }.getOrNull() ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val id = it.getLong(0)
            if (id <= lastId) return
            lastId = id
            CompanionManager.send(JSONObject()
                .put("type", "sms")
                .put("address", it.getString(1).orEmpty())
                .put("body", it.getString(2).orEmpty())
                .put("date", it.getLong(3)))
        }
    }

    @SuppressLint("MissingPermission")
    fun send(address: String, body: String) {
        if (!registered || address.isBlank() || body.isBlank()) return
        val manager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        runCatching { manager.sendMultipartTextMessage(address, null, manager.divideMessage(body), null, null) }
    }

    private fun newestId(): Long = runCatching {
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null,
            "${Telephony.Sms._ID} DESC LIMIT 1")?.use { if (it.moveToFirst()) it.getLong(0) else -1L }
    }.getOrNull() ?: -1L
}
