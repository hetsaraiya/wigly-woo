package com.wiglywoo.ecosystem

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import com.wiglywoo.CompanionManager
import org.json.JSONObject

/**
 * Sends battery, radio, and Do Not Disturb to the Mac. Battery and ringer
 * changes arrive as broadcasts; signal strength has none, so a slow tick
 * catches it. Identical reports are not re-sent.
 */
class StatusReporter(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var last = ""
    private var started = false

    private val tick = object : Runnable {
        override fun run() {
            publish()
            main.postDelayed(this, 60_000)
        }
    }

    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) { publish() }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        main.post(tick)
    }

    private fun publish() {
        if (!EcosystemSettings.enabled(context, EcosystemSettings.Feature.STATUS)) return
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        @Suppress("DEPRECATION")
        val ssid = context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.trim('"').orEmpty()
            .takeUnless { it == "<unknown ssid>" }.orEmpty() // hidden without Location permission
        val dnd = (context.getSystemService(android.app.NotificationManager::class.java)
            ?.currentInterruptionFilter ?: 0).let { it != 0 && it != android.app.NotificationManager.INTERRUPTION_FILTER_ALL }
        val signal = runCatching { context.getSystemService(TelephonyManager::class.java)?.signalStrength?.level }.getOrNull()
        val payload = JSONObject()
            .put("type", "phone_status")
            .put("battery", if (scale > 0) level * 100 / scale else level)
            .put("charging", plugged != 0)
            .put("wifi", ssid)
            .put("dnd", dnd)
            .put("signal", signal ?: -1)
            .toString()
        if (payload == last) return
        last = payload
        CompanionManager.send(JSONObject(payload))
    }
}
