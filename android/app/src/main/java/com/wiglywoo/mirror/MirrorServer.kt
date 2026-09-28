package com.wiglywoo.mirror

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
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
    private var metaOut: FileOutputStream? = null
    private val kept = mutableListOf<ParcelFileDescriptor>()
    /** Set while the Mac has the panel dark, so stop() can turn it back on. */
    @Volatile private var panelOff = false

    override fun start(
        video: ParcelFileDescriptor?,
        audioFd: ParcelFileDescriptor?,
        control: ParcelFileDescriptor?,
        meta: ParcelFileDescriptor?,
        configJson: String?,
    ): String {
        enforceCaller()
        val base = context ?: return err("no context")
        val shell = HiddenApi.shell(base)
        stop()
        val config = MirrorConfig.parse(configJson)
        return try {
            listOfNotNull(video, audioFd, control, meta).forEach { kept += it }
            metaOut = meta?.let { FileOutputStream(it.fileDescriptor) }
            if (!config.audioOnly && !config.headless && video != null) {
                val enc = ScreenEncoder(shell, FileOutputStream(video.fileDescriptor), config) { w, h, displayId ->
                    if (config.appDisplay && config.component.isNotEmpty()) launch(config.component, displayId)
                    meta(JSONObject().put("type", "ready").put("w", w).put("h", h))
                }
                encoder = enc
                io.execute {
                    runCatching { enc.run() }.onFailure {
                        if (!enc.running) return@onFailure // stopped on purpose
                        Log.e(TAG, "encode", it)
                        meta(JSONObject().put("type", "error").put("reason", it.message ?: "encoder stopped"))
                    }
                }
            } else {
                meta(JSONObject().put("type", "ready").put("w", 0).put("h", 0))
            }
            if (audioFd != null && !config.headless) {
                val capture = AudioCapture(shell, FileOutputStream(audioFd.fileDescriptor))
                audio = capture
                io.execute { runCatching { capture.run() }.onFailure { Log.w(TAG, "audio", it) } }
            }
            if (control != null) {
                // Input lands in display pixels. A mirror maps onto the real
                // screen (read each time, so rotation is followed); an app
                // display is exactly the encoded size.
                fun screen() = HiddenApi.defaultDisplaySize(base)
                val ctrl = Controller(
                    FileInputStream(control.fileDescriptor),
                    width = { if (config.appDisplay) encoder?.width ?: 0 else screen().first },
                    height = { if (config.appDisplay) encoder?.height ?: 0 else screen().second },
                    actions = shellActions(),
                    displayId = { if (config.appDisplay) encoder?.displayId ?: 0 else 0 },
                )
                controller = ctrl
                io.execute { ctrl.run() }
            }
            if (config.screenOff) setPanel(on = false)
            if (!config.headless && !config.audioOnly) io.execute { publishApps(base) }
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
        if (panelOff) setPanel(on = true)
        runCatching { metaOut?.close() }
        metaOut = null
        kept.forEach { runCatching { it.close() } }
        kept.clear()
    }

    override fun bringUpHotspot(): String {
        enforceCaller()
        val ctx = context ?: return err("no context")
        if (android.os.Build.VERSION.SDK_INT < 30) return err("settings")
        return runCatching { HotspotStarter.start(ctx) }.getOrElse { err(it.message ?: "hotspot failed") }
    }

    override fun unlock(pin: String?, near: Boolean): String {
        enforceCaller()
        val ctx = context ?: return err("no context")
        if (!near) return err("The Mac says the phone is not near")
        if (!HiddenApi.userUnlocked(ctx)) return err("Unlock the phone once after it restarts")
        val digits = pin?.toCharArray() ?: return err("no pin")
        try {
            if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return err("The PIN must be digits")
            setPanel(on = true)
            shell("input", "keyevent", "KEYCODE_WAKEUP")
            // Brings up the PIN pad on a secure lock screen.
            shell("wm", "dismiss-keyguard")
            Thread.sleep(400)
            shell("input", "text", String(digits))
            shell("input", "keyevent", "KEYCODE_ENTER")
            return "ok"
        } finally {
            digits.fill('\u0000')
        }
    }

    override fun destroy() {
        stop()
        exitProcess(0)
    }

    private fun setPanel(on: Boolean) {
        if (HiddenApi.setDisplayPower(on)) panelOff = !on
        else if (!on) meta(JSONObject().put("type", "notice").put("reason", "This phone cannot turn its screen off while mirroring"))
    }

    private fun shellActions() = object : Controller.Actions {
        override fun screenPower(on: Boolean) = setPanel(on)
        override fun expand(settings: Boolean) {
            shell("cmd", "statusbar", if (settings) "expand-settings" else "expand-notifications")
        }
        override fun launch(component: String) = launch(component, encoder?.displayId ?: 0)
        override fun clipboard(text: String) = setClipboard(text)
        override fun syncFrame() { encoder?.requestSyncFrame() }
    }

    private fun launch(component: String, displayId: Int) {
        if (component.isBlank()) return
        if (displayId != 0) shell("am", "start", "--display", displayId.toString(), "-n", component)
        else shell("am", "start", "-n", component)
    }

    private fun shell(vararg command: String) {
        runCatching { Runtime.getRuntime().exec(command).waitFor() }.onFailure { Log.w(TAG, command.joinToString(" "), it) }
    }

    private fun publishApps(ctx: Context) {
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = JSONArray()
        for (info in pm.queryIntentActivities(intent, 0).sortedBy { it.loadLabel(pm).toString().lowercase() }.take(80)) {
            val component = info.activityInfo?.let { "${it.packageName}/${it.name}" } ?: continue
            val row = JSONObject().put("label", info.loadLabel(pm).toString()).put("component", component)
            if (apps.length() < 24) runCatching { png(info.loadIcon(pm)) }.getOrNull()?.let { row.put("icon", it) }
            apps.put(row)
        }
        meta(JSONObject().put("type", "apps").put("apps", apps))
    }

    private fun png(drawable: Drawable): String {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 48, 48)
        drawable.draw(Canvas(bitmap))
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
        bitmap.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun setClipboard(text: String) {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java)
                .invoke(null, Context.CLIPBOARD_SERVICE) as android.os.IBinder
            val clipboard = Class.forName("android.content.IClipboard\$Stub")
                .getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            val clip = ClipData.newPlainText("wigly", text)
            val set = clipboard.javaClass.methods.first { it.name == "setPrimaryClip" }
            // The signature grew over releases: (clip, pkg[, attributionTag], userId[, deviceId]).
            when (set.parameterCount) {
                2 -> set.invoke(clipboard, clip, HiddenApi.SHELL_PACKAGE)
                3 -> set.invoke(clipboard, clip, HiddenApi.SHELL_PACKAGE, 0)
                4 -> set.invoke(clipboard, clip, HiddenApi.SHELL_PACKAGE, null, 0)
                else -> set.invoke(clipboard, clip, HiddenApi.SHELL_PACKAGE, null, 0, 0)
            }
        }.onFailure { Log.w(TAG, "clipboard", it) }
    }

    private fun meta(json: JSONObject) {
        val out = metaOut ?: return
        runCatching { Records.write(out, json.toString().toByteArray()) }
    }

    private fun enforceCaller() {
        val calling = Binder.getCallingUid()
        if (calling == Process.myUid() || calling == Process.SYSTEM_UID || calling == HiddenApi.SHELL_UID) return
        val appUid = runCatching { context?.packageManager?.getApplicationInfo(APP_PACKAGE, 0)?.uid }.getOrNull()
        if (calling != appUid) throw SecurityException("caller $calling")
    }

    private fun err(message: String) = JSONObject().put("error", message).toString()

    companion object {
        private const val TAG = "WiglyMirror"
        private const val APP_PACKAGE = "com.wiglywoo"
    }
}
