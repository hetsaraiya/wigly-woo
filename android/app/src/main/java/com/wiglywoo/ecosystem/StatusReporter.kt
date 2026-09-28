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

/** Sends battery, radio, and Do Not Disturb when they change. Never polls. */
class StatusReporter(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var last = ""

    private val tick = object : Runnable {
        override fun run() {
            publish()
            main.postDelayed(this, 60_000)
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
        main.post(tick)
    }

    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) { publish() }
    }

    fun publish() {
        if (!EcosystemSettings.load(context).status) return
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val ssid = wifi.connectionInfo?.ssid?.trim('"') ?: ""
        val dnd = (context.getSystemService(android.app.NotificationManager::class.java)
            ?.currentInterruptionFilter ?: 0) != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        val signal = runCatching {
            val tm = context.getSystemService(TelephonyManager::class.java)
            tm?.signalStrength?.level
        }.getOrNull()
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
