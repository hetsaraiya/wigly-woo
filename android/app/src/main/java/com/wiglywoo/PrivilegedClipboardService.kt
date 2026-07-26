package com.wiglywoo

import android.content.ClipDescription
import android.content.ClipData
import android.content.Context
import android.os.IBinder
import android.util.Log
import androidx.annotation.Keep
import kotlin.system.exitProcess

/** Runs under Shizuku's shell UID, which can read the clipboard in background. */
@Keep
class PrivilegedClipboardService @JvmOverloads constructor(
    private var context: Context? = null
) : IPrivilegedClipboard.Stub() {

    override fun readText(): String {
        return runCatching {
            // Invoke the hidden IClipboard binder directly so the operation
            // package matches this user service's shell UID. A normal Context
            // retains WiglyWoo's op package and Samsung correctly rejects it.
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getDeclaredMethod("getService", String::class.java)
                .invoke(null, Context.CLIPBOARD_SERVICE) as IBinder
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val clipboard = stub.getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            val getPrimaryClip = clipboard.javaClass.methods.first { it.name == "getPrimaryClip" }
                .apply { isAccessible = true }
            val clip = when (getPrimaryClip.parameterCount) {
                4 -> getPrimaryClip.invoke(clipboard, "com.android.shell", null, 0, 0)
                3 -> getPrimaryClip.invoke(clipboard, "com.android.shell", null, 0)
                2 -> getPrimaryClip.invoke(clipboard, "com.android.shell", 0)
                else -> null
            } as? ClipData ?: return ""
            val description = clip.description
            val extras = description?.extras
            val sensitive = extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true ||
                extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
            if (sensitive) return ""
            clip.getItemAt(0)?.text?.toString().orEmpty()
        }.onFailure { Log.e("WiglyClipboardShell", "Clipboard read failed", it) }
            .getOrDefault("")
    }

    override fun destroy() {
        exitProcess(0)
    }
}
