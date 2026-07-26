package com.wiglywoo

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku

/** Keeps an ordinary-copy clipboard watcher alive without replacing the user's keyboard. */
object ShizukuClipboardBridge {
    enum class Status {
        NOT_RUNNING,
        PERMISSION_REQUIRED,
        PERMISSION_BLOCKED,
        READY,
        UNSUPPORTED,
    }

    private const val REQUEST_CODE = 4817
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false
    private var binding = false
    private var service: IPrivilegedClipboard? = null
    private var lastObservedHash: String? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName("com.wiglywoo", PrivilegedClipboardService::class.java.name))
            .daemon(true)
            .processNameSuffix("clipboard")
            .tag("wigly-clipboard-v1")
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IPrivilegedClipboard.Stub.asInterface(binder)
            binding = false
            schedulePoll(immediate = true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { ensurePermissionAndBind() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        binding = false
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) bindService()
    }

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        ensurePermissionAndBind()
    }

    fun status(): Status = runCatching {
        when {
            !Shizuku.pingBinder() -> Status.NOT_RUNNING
            Shizuku.isPreV11() -> Status.UNSUPPORTED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Status.READY
            Shizuku.shouldShowRequestPermissionRationale() -> Status.PERMISSION_BLOCKED
            else -> Status.PERMISSION_REQUIRED
        }
    }.getOrDefault(Status.NOT_RUNNING)

    fun refresh() {
        ensurePermissionAndBind()
    }

    /** Returns true when the Shizuku permission dialog was requested. */
    fun requestAccess(): Boolean = runCatching {
        if (status() !in setOf(Status.PERMISSION_REQUIRED, Status.PERMISSION_BLOCKED)) {
            return@runCatching false
        }
        Shizuku.requestPermission(REQUEST_CODE)
        true
    }.getOrDefault(false)

    private fun ensurePermissionAndBind() {
        if (!Shizuku.pingBinder()) return
        when {
            Shizuku.isPreV11() -> return
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bindService()
        }
    }

    private fun bindService() {
        if (service != null || binding) return
        binding = true
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
            .onFailure { binding = false }
    }

    private fun schedulePoll(immediate: Boolean = false) {
        main.removeCallbacks(poll)
        main.postDelayed(poll, if (immediate) 0 else 700)
    }

    private val poll = object : Runnable {
        override fun run() {
            val text = runCatching { service?.readText().orEmpty() }.getOrDefault("")
            if (text.isNotEmpty()) {
                val hash = CompanionCrypto.digest(text)
                if (hash != lastObservedHash) {
                    lastObservedHash = hash
                    CompanionManager.forwardClipboardText(text)
                }
            }
            main.postDelayed(this, 700)
        }
    }
}
