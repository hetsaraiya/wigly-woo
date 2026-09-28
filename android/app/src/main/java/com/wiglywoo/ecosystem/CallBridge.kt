package com.wiglywoo.ecosystem

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.wiglywoo.CompanionManager
import org.json.JSONObject

/**
 * Incoming-call banner, answer, and decline. Audio stays on the phone.
 * Android 12+ does not give the caller's number here; the dialer's own
 * notification, which the relay already forwards, names the caller.
 */
class CallBridge(private val context: Context) {
    private val telephony get() = context.getSystemService(TelephonyManager::class.java)
    private var listening: Any? = null

    @SuppressLint("MissingPermission") // update(true) is only called with the permission granted
    fun update(on: Boolean) {
        val tm = telephony ?: return
        if (on && listening == null) {
            listening = if (Build.VERSION.SDK_INT >= 31) {
                object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = publish(state, "")
                }.also { tm.registerTelephonyCallback(context.mainExecutor, it) }
            } else {
                @Suppress("DEPRECATION")
                object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = publish(state, phoneNumber.orEmpty())
                }.also { tm.listen(it, PhoneStateListener.LISTEN_CALL_STATE) }
            }
        } else if (!on && listening != null) {
            val old = listening
            listening = null
            if (Build.VERSION.SDK_INT >= 31 && old is TelephonyCallback) tm.unregisterTelephonyCallback(old)
            @Suppress("DEPRECATION")
            if (old is PhoneStateListener) tm.listen(old, PhoneStateListener.LISTEN_NONE)
        }
    }

    @SuppressLint("MissingPermission")
    fun answer() {
        if (listening == null) return
        runCatching { context.getSystemService(TelecomManager::class.java)?.acceptRingingCall() }
    }

    @SuppressLint("MissingPermission")
    fun decline() {
        if (listening == null || Build.VERSION.SDK_INT < 28) return
        runCatching { context.getSystemService(TelecomManager::class.java)?.endCall() }
    }

    private fun publish(state: Int, number: String) {
        val name = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "active"
            else -> "idle"
        }
        CompanionManager.send(JSONObject().put("type", "call").put("state", name).put("number", number))
    }
}
