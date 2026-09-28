package com.wiglywoo.ecosystem

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import com.wiglywoo.CompanionManager
import com.wiglywoo.mirror.ShizukuMirrorBridge
import com.wiglywoo.mirror.WiglyAccessibilityService
import org.json.JSONObject

/** Starts the continuity reporters and handles the Mac's relay messages. */
object EcosystemHost {
    private var calls: CallBridge? = null
    private var sms: SmsBridge? = null
    private var media: MediaBridge? = null

    fun install(context: Context) {
        val app = context.applicationContext
        StatusReporter(app).start()
        ScreenshotWatcher(app).start()
        calls = CallBridge(app).also { it.start() }
        sms = SmsBridge(app).also { it.start() }
        media = MediaBridge(app).also { it.start() }
        BlePresence.start(app)
    }

    fun onRelay(message: JSONObject) {
        val ctx = appOrNull() ?: return
        when (message.optString("type")) {
            "ring" -> if (message.optString("target", "phone") == "phone") ring(ctx)
            "lock" -> ShizukuMirrorBridge.runPower(if (message.optString("target") == "phone") "sleep" else "lock")
            "wake" -> ShizukuMirrorBridge.runPower("wake")
            "call_answer" -> calls?.answer()
            "call_decline" -> calls?.decline()
            "call_dial" -> calls?.dial(message.optString("number"))
            "sms_send" -> sms?.send(message.optString("address"), message.optString("body"))
            "media" -> media?.command(message.optString("command"))
            "handoff" -> open(ctx, message.optString("url"))
            "focus" -> setDnd(ctx, message.optBoolean("enabled"))
            "hotspot_request" -> offerHotspot()
            "wifi_share" -> ShizukuMirrorBridge.joinWifi(message.optString("ssid"), message.optString("psk"))
            "wifi_request" -> shareCurrent(ctx)
            "continue_on_mac" -> continueOnMac()
            "capture_request" -> CompanionManager.send(JSONObject().put("type", "capture_ready").put("mode", message.optString("mode")))
            "otp_type" -> if (EcosystemSettings.load(ctx).otpType) typeOtp(message.optString("code"))
            "presence" -> BlePresence.near = message.optBoolean("near", true)
            "sketch_request" -> SketchActivity.open(ctx)
        }
    }

    fun onNotification(app: String, text: String) {
        if (EcosystemSettings.load(appOrNull() ?: return).otp) OtpDetector.consider(app, text)
    }

    private fun ring(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val tone = android.media.RingtoneManager.getRingtone(
            context, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM))
        tone?.play()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone?.stop() }, 8_000)
    }

    private fun open(context: Context, url: String) {
        if (!url.startsWith("http")) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun setDnd(context: Context, enabled: Boolean) {
        if (!EcosystemSettings.load(context).focus) return
        val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        nm.setInterruptionFilter(if (enabled) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    private fun offerHotspot() {
        val json = ShizukuMirrorBridge.bringUpHotspot()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (obj.has("ssid")) {
            CompanionManager.send(JSONObject().put("type", "hotspot_offer").put("ssid", obj.optString("ssid")).put("psk", obj.optString("psk")))
        } else {
            CompanionManager.send(JSONObject().put("type", "hotspot_error").put("reason", obj.optString("error")))
        }
    }

    private fun shareCurrent(context: Context) {
        val json = ShizukuMirrorBridge.listSavedNetworks()
        CompanionManager.send(JSONObject().put("type", "wifi_offer").put("networks", json))
    }

    private fun continueOnMac() {
        val url = WiglyAccessibilityService.instance?.chromeUrl() ?: return
        CompanionManager.send(JSONObject().put("type", "handoff").put("url", url).put("direction", "to-mac"))
    }

    private fun typeOtp(code: String) {
        // The IME is the supported way to type. Put the code on the clipboard
        // the keyboard already watches, and let the user paste if auto-type is off.
        if (code.isBlank()) return
        CompanionManager.forwardClipboardText(code)
    }

    private fun appOrNull(): Context? = holder

    @Volatile private var holder: Context? = null

    fun remember(context: Context) { holder = context.applicationContext }
}
