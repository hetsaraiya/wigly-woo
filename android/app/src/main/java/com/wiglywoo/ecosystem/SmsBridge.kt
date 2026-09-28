package com.wiglywoo.ecosystem

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.wiglywoo.CompanionManager
import org.json.JSONObject

/** SMS threads for a sideload build. Play Store builds must not ship this permission. */
class SmsBridge(private val context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {
    fun start() {
        if (!canRead()) return
        context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, this)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        if (!EcosystemSettings.load(context).sms || !canRead()) return
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "${Telephony.Sms.DATE} DESC",
        ) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val body = it.getString(1).orEmpty()
            OtpDetector.consider(it.getString(0).orEmpty(), body)
            CompanionManager.send(JSONObject()
                .put("type", "sms")
                .put("address", it.getString(0).orEmpty())
                .put("body", body)
                .put("date", it.getLong(2)))
        }
    }

    fun send(address: String, body: String) {
        if (!EcosystemSettings.load(context).sms || address.isBlank() || body.isBlank()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        manager.sendTextMessage(address, null, body, null, null)
    }

    private fun canRead() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
}
