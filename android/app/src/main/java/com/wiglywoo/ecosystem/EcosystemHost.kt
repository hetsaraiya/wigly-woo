package com.wiglywoo.ecosystem

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.wiglywoo.CompanionManager
import com.wiglywoo.ecosystem.EcosystemSettings.Feature
import com.wiglywoo.mirror.ShizukuMirrorBridge
import org.json.JSONObject
import java.util.concurrent.Executors

/** Starts the continuity reporters and handles the Mac's relay messages. */
object EcosystemHost {
    private val main = Handler(Looper.getMainLooper())
    // Hotspot and unlock block on the Shizuku binder; the relay thread must not.
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var app: Context? = null
    private var status: StatusReporter? = null
    private var calls: CallBridge? = null
    private var sms: SmsBridge? = null
    private var media: MediaBridge? = null
    private var screenshots: ScreenshotWatcher? = null

    fun install(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        status = StatusReporter(ctx)
        calls = CallBridge(ctx)
        sms = SmsBridge(ctx)
        media = MediaBridge(ctx)
        screenshots = ScreenshotWatcher(ctx)
        refresh()
    }

    /** Starts or stops each feature to match its toggle and permissions. Safe to call often. */
    fun refresh() = main.post {
        val ctx = app ?: return@post
        status?.start()
        media?.start()
        calls?.update(EcosystemSettings.active(ctx, Feature.CALLS))
        sms?.update(EcosystemSettings.active(ctx, Feature.SMS))
        screenshots?.update(EcosystemSettings.active(ctx, Feature.SCREENSHOTS))
        BlePresence.update(ctx, EcosystemSettings.active(ctx, Feature.PRESENCE))
    }

    fun onRelay(message: JSONObject) {
        val ctx = app ?: return
        when (message.optString("type")) {
            "ring" -> main.post { ring(ctx) }
            "call_answer" -> calls?.answer()
            "call_decline" -> calls?.decline()
            "sms_send" -> sms?.send(message.optString("address"), message.optString("body"))
            "media" -> media?.command(message.optString("command"))
            "handoff" -> open(ctx, message.optString("url"))
            "hotspot_request" -> worker.execute { offerHotspot() }
            "presence" -> BlePresence.near = message.optBoolean("near", true)
            "unlock" -> {
                val pin = message.optString("pin").toCharArray()
                worker.execute { unlock(pin) }
            }
        }
    }

    fun onNotification(app: String, text: String) {
        val ctx = this.app ?: return
        if (EcosystemSettings.enabled(ctx, Feature.OTP)) OtpDetector.consider(app, text)
    }

    /** Alarm sound for eight seconds. The alarm stream plays even when the ringer is silent. */
    private fun ring(context: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        val tone = RingtoneManager.getRingtone(context, uri) ?: return
        tone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        tone.play()
        main.postDelayed({ tone.stop() }, 8_000)
    }

    private fun open(context: Context, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun offerHotspot() {
        val obj = runCatching { JSONObject(ShizukuMirrorBridge.bringUpHotspot()) }.getOrNull() ?: return
        if (obj.has("ssid")) {
            CompanionManager.send(JSONObject().put("type", "hotspot_offer").put("ssid", obj.optString("ssid")).put("psk", obj.optString("psk")))
        } else {
            CompanionManager.send(JSONObject().put("type", "hotspot_error").put("reason", obj.optString("error")))
        }
    }

    private fun unlock(pin: CharArray) {
        val result = ShizukuMirrorBridge.unlock(pin, near = BlePresence.near)
        val error = runCatching { JSONObject(result).optString("error") }.getOrDefault("")
        CompanionManager.send(JSONObject().put("type", "unlock_result").put("ok", result == "ok").put("reason", error))
    }
}
