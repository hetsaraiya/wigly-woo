package com.wiglywoo.mirror

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Base64
import android.util.Log
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Runs as the shell user through Shizuku. It encodes the screen, captures
 * playback audio, and injects input. The AIDL surface is the only way in.
 */
@Keep
class MirrorServer @JvmOverloads constructor(
    private var context: Context? = null,
) : IPrivilegedMirror.Stub() {
    private val io = Executors.newCachedThreadPool()
    private var encoder: ScreenEncoder? = null
    private var audio: AudioCapture? = null
    private var controller: Controller? = null
    private var videoOut: FileOutputStream? = null
    private var metaOut: FileOutputStream? = null
    private val kept = mutableListOf<ParcelFileDescriptor>()

    override fun start(
        video: ParcelFileDescriptor?,
        audioFd: ParcelFileDescriptor?,
        control: ParcelFileDescriptor?,
        meta: ParcelFileDescriptor?,
        configJson: String?,
    ): String {
        enforceCaller()
        val ctx = context ?: return err("no context")
        stop()
        val config = MirrorConfig.parse(configJson)
        return try {
            listOfNotNull(video, audioFd, control, meta).forEach { kept += it }
            videoOut = video?.let { FileOutputStream(it.fileDescriptor) }
            metaOut = meta?.let { FileOutputStream(it.fileDescriptor) }
            if (!config.audioOnly && !config.headless && videoOut != null) {
                val enc = ScreenEncoder(ctx, videoOut!!, config) { w, h, codec, displayId ->
                    if (config.appDisplay && config.component.isNotEmpty()) {
                        runCatching {
                            Runtime.getRuntime().exec(arrayOf("am", "start", "--display", displayId.toString(), "-n", config.component))
                        }
                    }
                    meta(JSONObject().put("type", "ready").put("video", codec).put("audio", audio?.codecId ?: 0)
                        .put("w", w).put("h", h).put("sdk", android.os.Build.VERSION.SDK_INT)
                        .put("flex", android.os.Build.VERSION.SDK_INT >= 35))
                }
                encoder = enc
                io.execute {
                    runCatching { enc.run() }.onFailure {
                        Log.e(TAG, "encode", it)
                        meta(JSONObject().put("type", "error").put("reason", it.message ?: "encoder stopped"))
                    }
                }
            } else {
                meta(JSONObject().put("type", "ready").put("video", 0).put("audio", 0)
                    .put("w", 0).put("h", 0).put("sdk", android.os.Build.VERSION.SDK_INT).put("flex", false))
            }
            if (audioFd != null && !config.headless) {
                val capture = AudioCapture(FileOutputStream(audioFd.fileDescriptor))
                audio = capture
                io.execute { runCatching { capture.run() } }
            }
            if (control != null) {
                val ctrl = Controller(
                    FileInputStream(control.fileDescriptor),
                    width = { encoder?.width ?: HiddenApi.defaultDisplaySize(ctx).first },
                    height = { encoder?.height ?: HiddenApi.defaultDisplaySize(ctx).second },
                    actions = shellActions(ctx),
                )
                controller = ctrl
                io.execute { runCatching { ctrl.run() } }
            }
            if (config.screenOff) setScreenPower(false)
            io.execute { publishApps(ctx) }
            "ok"
        } catch (t: Throwable) {
            Log.e(TAG, "start", t)
            err(t.message ?: "start failed")
        }
    }

    override fun stop() {
        runCatching { enforceCaller() }
        controller?.stop()
        audio?.stop()
        encoder?.stop()
        controller = null
        audio = null
        encoder = null
        runCatching { videoOut?.close() }
        runCatching { metaOut?.close() }
        kept.forEach { runCatching { it.close() } }
        kept.clear()
        videoOut = null
        metaOut = null
    }

    override fun setScreenPower(on: Boolean) {
        enforceCaller()
        HiddenApi.setDisplayPower(on)
    }

    override fun capabilities(): String {
        enforceCaller()
        return HiddenApi.capabilitiesJson(context)
    }

    override fun bringUpHotspot(): String {
        enforceCaller()
        val ctx = context ?: return err("no context")
        if (android.os.Build.VERSION.SDK_INT < 30) return err("settings")
        return runCatching { HotspotStarter.start(ctx) }.getOrElse { err(it.message ?: "hotspot failed") }
    }

    override fun joinWifi(ssid: String?, psk: String?): String {
        enforceCaller()
        val ctx = context ?: return err("no context")
        if (ssid.isNullOrBlank()) return err("missing ssid")
        return runCatching { addNetwork(ctx, ssid, psk.orEmpty()) }.getOrElse { err(it.message ?: "join failed") }
    }

    override fun listSavedNetworks(): String {
        enforceCaller()
        val ctx = context ?: return err("no context")
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val list = JSONArray()
        val method = wifi.javaClass.methods.firstOrNull { it.name == "getPrivilegedConfiguredNetworks" }
            ?: wifi.javaClass.methods.firstOrNull { it.name == "getConfiguredNetworks" }
        val configs = runCatching { method?.invoke(wifi) as? List<*> }.getOrNull().orEmpty()
        for (item in configs) {
            val cfg = item as? WifiConfiguration ?: continue
            val name = cfg.SSID?.trim('"') ?: continue
            list.put(JSONObject().put("ssid", name).put("psk", cfg.preSharedKey?.trim('"')))
        }
        return JSONObject().put("networks", list).toString()
    }

    override fun unlock(pin: String?, near: Boolean): String {
        enforceCaller()
        val ctx = context ?: return err("no context")
        if (!near) return err("far")
        if (!HiddenApi.userUnlocked(ctx)) return err("locked since boot")
        val digits = pin?.toCharArray() ?: return err("no pin")
        try {
            HiddenApi.setDisplayPower(true)
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_WAKEUP")).waitFor()
            for (ch in digits) {
                val name = digitKey(ch) ?: return err("pin must be digits")
                Runtime.getRuntime().exec(arrayOf("input", "keyevent", name)).waitFor()
            }
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_ENTER")).waitFor()
            return "ok"
        } finally {
            digits.fill('\u0000')
        }
    }

    override fun runPower(action: String?): String {
        enforceCaller()
        val key = when (action) {
            "wake" -> "KEYCODE_WAKEUP"
            "sleep" -> "KEYCODE_SLEEP"
            "lock" -> "KEYCODE_POWER"
            else -> return err("unknown")
        }
        return runCatching {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", key)).waitFor()
            "ok"
        }.getOrElse { err(it.message ?: "power failed") }
    }

    override fun destroy() {
        stop()
        exitProcess(0)
    }

    private fun shellActions(ctx: Context) = object : Controller.Actions {
        override fun screenPower(on: Boolean) { HiddenApi.setDisplayPower(on) }
        override fun expand(settings: Boolean) {
            val which = if (settings) "expand-settings" else "expand-notifications"
            runCatching { Runtime.getRuntime().exec(arrayOf("cmd", "statusbar", which)) }
        }
        override fun launch(component: String) {
            if (component.isBlank()) return
            val id = encoder?.displayId
            val cmd = if (id != null && id != 0) {
                arrayOf("am", "start", "--display", id.toString(), "-n", component)
            } else {
                arrayOf("am", "start", "-n", component)
            }
            runCatching { Runtime.getRuntime().exec(cmd) }
        }
        override fun clipboard(text: String) { setClipboard(text) }
        override fun reconfigure(width: Int, height: Int, fps: Int, bitrate: Int, limit: Int, codec: Int, flags: Int) {
            if (width > 0 && height > 0) {
                val dpi = HiddenApi.defaultDisplaySize(ctx).third
                encoder?.resize(width, height, dpi)
            }
            if (flags and 1 != 0) HiddenApi.setDisplayPower(false)
            if (flags and 1 == 0 && flags != 0) HiddenApi.setDisplayPower(true)
        }
        override fun record(on: Boolean) {
            meta(JSONObject().put("type", "record").put("on", on))
        }
        override fun unlock(pin: String) {
            val chars = pin.toCharArray()
            try {
                this@MirrorServer.unlock(String(chars), true)
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    private fun publishApps(ctx: Context) {
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = JSONArray()
        for (info in pm.queryIntentActivities(intent, 0).take(80)) {
            val label = info.loadLabel(pm)?.toString().orEmpty()
            val component = info.activityInfo?.let { "${it.packageName}/${it.name}" } ?: continue
            val icon = runCatching { png(info.loadIcon(pm)) }.getOrNull()
            val row = JSONObject().put("label", label).put("component", component).put("package", info.activityInfo.packageName)
            if (icon != null && apps.length() < 24) row.put("icon", icon)
            apps.put(row)
        }
        meta(JSONObject().put("type", "apps").put("apps", apps))
    }

    private fun png(drawable: Drawable): String {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 48, 48)
        drawable.draw(canvas)
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
        bitmap.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun setClipboard(text: String) {
        runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getDeclaredMethod("getService", String::class.java).invoke(null, Context.CLIPBOARD_SERVICE) as android.os.IBinder
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val clipboard = stub.getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            val clip = ClipData.newPlainText("wigly", text)
            val set = clipboard.javaClass.methods.first { it.name == "setPrimaryClip" }
            set.isAccessible = true
            when (set.parameterCount) {
                3 -> set.invoke(clipboard, clip, "com.android.shell", 0)
                4 -> set.invoke(clipboard, clip, "com.android.shell", null, 0)
                else -> set.invoke(clipboard, clip, "com.android.shell")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun addNetwork(ctx: Context, ssid: String, psk: String): String {
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val cfg = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (psk.isEmpty()) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            else preSharedKey = "\"$psk\""
        }
        val id = wifi.addNetwork(cfg)
        if (id < 0) return err("addNetwork refused")
        wifi.enableNetwork(id, true)
        return JSONObject().put("ok", true).put("ssid", ssid).toString()
    }

    private fun meta(json: JSONObject) {
        val out = metaOut ?: return
        runCatching { out.write(json.toString().toByteArray()) }
    }

    private fun enforceCaller() {
        val calling = Binder.getCallingUid()
        val ctx = context ?: return
        val appUid = runCatching { ctx.packageManager.getApplicationInfo("com.wiglywoo", 0).uid }.getOrNull()
        if (calling != appUid && calling != Process.SHELL_UID && calling != Process.SYSTEM_UID && calling != Process.myUid()) {
            throw SecurityException("caller $calling")
        }
    }

    private fun digitKey(ch: Char): String? = when (ch) {
        in '0'..'9' -> "KEYCODE_$ch"
        else -> null
    }

    private fun err(message: String) = JSONObject().put("error", message).toString()

    companion object {
        private const val TAG = "WiglyMirror"
    }
}
