package com.wiglywoo.ecosystem

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.wiglywoo.CompanionManager
import org.json.JSONObject
import java.util.concurrent.Executor

/** Incoming-call banner and answer, decline, or dial. Audio stays on the phone. */
class CallBridge(private val context: Context) {
    private val granted: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!granted) return
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            tm.registerTelephonyCallback(context.mainExecutor, object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) { publish(state, "") }
            })
        } else {
            @Suppress("DEPRECATION")
            tm.listen(object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    publish(state, phoneNumber.orEmpty())
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    fun answer() {
        if (!EcosystemSettings.load(context).calls) return
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { telecom.acceptRingingCall() }
    }

    fun decline() {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return
        runCatching { telecom.endCall() }
    }

    fun dial(number: String) {
        if (!EcosystemSettings.load(context).calls || number.isBlank()) return
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
        runCatching { context.startActivity(intent) }
    }

    private fun publish(state: Int, number: String) {
        if (!EcosystemSettings.load(context).calls) return
        val name = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "active"
            else -> "idle"
        }
        CompanionManager.send(JSONObject().put("type", "call").put("state", name).put("number", number))
    }
}
