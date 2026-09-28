package com.wiglywoo.mirror

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.wiglywoo.ShizukuClipboardBridge
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Binds the shell mirror service the same way the clipboard watcher does. */
object ShizukuMirrorBridge {
    private val service = AtomicReference<IPrivilegedMirror?>(null)
    @Volatile private var binding = false
    private val main = Handler(Looper.getMainLooper())

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName("com.wiglywoo", MirrorServer::class.java.name))
            .daemon(false)
            .processNameSuffix("mirror")
            .tag("wigly-mirror-v1")
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service.set(IPrivilegedMirror.Stub.asInterface(binder))
            binding = false
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service.set(null)
            binding = false
        }
    }

    fun ready(): Boolean = ShizukuClipboardBridge.status() == ShizukuClipboardBridge.Status.READY

    fun bind() {
        if (!ready() || service.get() != null || binding) return
        binding = true
        main.post { runCatching { Shizuku.bindUserService(args, connection) }.onFailure { binding = false } }
    }

    fun start(
        video: ParcelFileDescriptor,
        audio: ParcelFileDescriptor,
        control: ParcelFileDescriptor,
        meta: ParcelFileDescriptor,
        configJson: String,
    ): String {
        val svc = awaitService() ?: return "shizuku"
        return runCatching { svc.start(video, audio, control, meta, configJson) }.getOrElse { it.message ?: "start failed" }
    }

    fun stop() { runCatching { service.get()?.stop() } }

    fun bringUpHotspot(): String = call { it.bringUpHotspot() }

    fun unlock(pin: CharArray, near: Boolean): String {
        val svc = awaitService() ?: run {
            pin.fill('\u0000')
            return """{"error":"shizuku"}"""
        }
        return try {
            svc.unlock(String(pin), near)
        } finally {
            pin.fill('\u0000')
        }
    }

    private fun call(block: (IPrivilegedMirror) -> String): String {
        val svc = awaitService() ?: return """{"error":"shizuku"}"""
        return runCatching { block(svc) }.getOrElse { """{"error":${org.json.JSONObject.quote(it.message ?: "failed")}}""" }
    }

    /** Blocks up to 3 s for the bind. Never call on the main thread: the connection callback lands there. */
    private fun awaitService(): IPrivilegedMirror? {
        service.get()?.let { return it }
        bind()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (service.get() == null && System.nanoTime() < deadline) Thread.sleep(40)
        return service.get()
    }
}
