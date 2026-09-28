package com.wiglywoo.mirror

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.wiglywoo.ShizukuClipboardBridge
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Binds the shell mirror service the same way the clipboard watcher does. */
object ShizukuMirrorBridge {
    private val service = AtomicReference<IPrivilegedMirror?>(null)
    private var binding = false

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
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { binding = false }
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

    fun setScreenPower(on: Boolean) { runCatching { service.get()?.setScreenPower(on) } }

    fun bringUpHotspot(): String = call { it.bringUpHotspot() }

    fun joinWifi(ssid: String, psk: String): String = call { it.joinWifi(ssid, psk) }

    fun listSavedNetworks(): String = call { it.listSavedNetworks() }

    fun unlock(pin: CharArray, near: Boolean): String {
        val svc = awaitService() ?: return "shizuku"
        return try {
            svc.unlock(String(pin), near)
        } finally {
            pin.fill('\u0000')
        }
    }

    fun capabilities(): String = call { it.capabilities() }

    private fun call(block: (IPrivilegedMirror) -> String): String {
        val svc = awaitService() ?: return """{"error":"shizuku"}"""
        return runCatching { block(svc) }.getOrElse { """{"error":${org.json.JSONObject.quote(it.message ?: "failed")}}""" }
    }

    private fun awaitService(): IPrivilegedMirror? {
        service.get()?.let { return it }
        bind()
        val latch = CountDownLatch(1)
        val thread = Thread {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                if (service.get() != null) break
                Thread.sleep(40)
            }
            latch.countDown()
        }
        thread.start()
        latch.await(4, TimeUnit.SECONDS)
        return service.get()
    }
}
