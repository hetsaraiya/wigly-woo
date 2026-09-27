package com.wiglywoo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

object CompanionManager {
    private const val TAG = "WiglyCompanion"
    private lateinit var app: Context
    private var client: SupabaseRealtimeClient? = null
    private var config = CompanionConfig()
    private var deviceId = ""
    private var suppressClipboardHash: String? = null
    private var lastSentClipboardHash: String? = null
    private var pendingClipboardId: String? = null
    private var pendingClipboardText: String? = null
    private var pendingClipboardLastSent = 0L
    private var pendingClipboardRetryMs = 1_500L
    private val receivedClipboardIds = LinkedHashSet<String>()
    private var clipboardRegistered = false
    private val main = Handler(Looper.getMainLooper())

    // Keeps retrying an unacknowledged clipboard send on its own, so delivery
    // does not depend on the keyboard service's polling being alive.
    private val clipboardRetry = object : Runnable {
        override fun run() {
            if (pendingClipboardId == null) return
            sendPendingClipboardIfDue()
            main.postDelayed(this, pendingClipboardRetryMs)
        }
    }

    private val stateListeners = CopyOnWriteArraySet<(SupabaseRealtimeClient.State) -> Unit>()
    private val messageListeners = CopyOnWriteArraySet<(JSONObject) -> Unit>()
    @Volatile var state: SupabaseRealtimeClient.State = SupabaseRealtimeClient.State.OFF
        private set

    fun initialize(context: Context) {
        if (!::app.isInitialized) {
            app = context.applicationContext
            deviceId = CompanionConfig.deviceId(app)
            ShizukuClipboardBridge.initialize(app)
        }
        applyConfig(CompanionConfig.load(app))
    }

    @Synchronized
    fun applyConfig(newConfig: CompanionConfig) {
        if (!::app.isInitialized) return
        val normalized = newConfig.normalized()
        val connectionChanged = normalized.supabaseUrl != config.supabaseUrl ||
            normalized.publishableKey != config.publishableKey ||
            normalized.pairingSecret != config.pairingSecret || normalized.enabled != config.enabled
        config = normalized
        CompanionConfig.save(app, config)
        ensureClipboardListener()
        if (!connectionChanged) return
        client?.disconnect()
        client = null
        if (config.enabled && config.isComplete) {
            client = SupabaseRealtimeClient(config, ::updateState, ::receiveEnvelope).also { it.connect() }
        } else updateState(SupabaseRealtimeClient.State.OFF)
    }

    fun reload() = applyConfig(CompanionConfig.load(app))

    fun addStateListener(listener: (SupabaseRealtimeClient.State) -> Unit) {
        stateListeners += listener
        listener(state)
    }

    fun removeStateListener(listener: (SupabaseRealtimeClient.State) -> Unit) { stateListeners -= listener }
    fun addMessageListener(listener: (JSONObject) -> Unit) { messageListeners += listener }
    fun removeMessageListener(listener: (JSONObject) -> Unit) { messageListeners -= listener }

    fun send(message: JSONObject): Boolean {
        if (state != SupabaseRealtimeClient.State.CONNECTED || !config.isComplete) {
            Log.w(TAG, "Dropping ${message.optString("type")}: state=$state complete=${config.isComplete}")
            return false
        }
        message.put("sentAt", System.currentTimeMillis())
        val accepted = client?.broadcast(CompanionCrypto.encrypt(message, config.pairingSecret, deviceId)) == true
        Log.i(TAG, "Broadcast ${message.optString("type")}: accepted=$accepted")
        return accepted
    }

    fun sendCurrentClipboard(): Boolean {
        if (!::app.isInitialized) return false
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString() }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        return forwardClipboardText(text)
    }

    fun forwardClipboardText(text: String): Boolean {
        val hash = CompanionCrypto.digest(text)
        if (hash == suppressClipboardHash) {
            suppressClipboardHash = null
            lastSentClipboardHash = hash
            return false
        }
        if (pendingClipboardText == text) return sendPendingClipboardIfDue()
        if (hash == lastSentClipboardHash && pendingClipboardId == null) return false
        pendingClipboardId = java.util.UUID.randomUUID().toString()
        pendingClipboardText = text.take(65_536)
        pendingClipboardLastSent = 0L
        pendingClipboardRetryMs = 1_500L
        lastSentClipboardHash = hash
        return sendPendingClipboardIfDue(force = true)
    }

    fun sendTextToMac(text: String): Boolean {
        if (text.isEmpty()) {
            Log.w(TAG, "Clipboard share ignored: empty text")
            return false
        }
        Log.i(TAG, "Clipboard share requested: chars=${text.length} state=$state")
        pendingClipboardId = java.util.UUID.randomUUID().toString()
        pendingClipboardText = text.take(65_536)
        pendingClipboardLastSent = 0L
        pendingClipboardRetryMs = 1_500L
        lastSentClipboardHash = CompanionCrypto.digest(text)
        return sendPendingClipboardIfDue(force = true)
    }

    private fun sendPendingClipboardIfDue(force: Boolean = false): Boolean {
        val id = pendingClipboardId ?: return false
        val text = pendingClipboardText ?: return false
        val now = System.currentTimeMillis()
        if (!force && now - pendingClipboardLastSent < pendingClipboardRetryMs) return false
        val sent = send(JSONObject().put("type", "clipboard").put("clipboardID", id).put("text", text))
        if (sent) {
            pendingClipboardLastSent = now
            // Back off while unacknowledged so an offline Mac isn't spammed.
            pendingClipboardRetryMs = (pendingClipboardRetryMs * 3 / 2).coerceAtMost(20_000L)
        }
        main.removeCallbacks(clipboardRetry)
        main.postDelayed(clipboardRetry, pendingClipboardRetryMs)
        return sent
    }

    private fun receiveEnvelope(envelope: JSONObject) {
        if (envelope.optString("sender") == deviceId) return
        val message = CompanionCrypto.decrypt(envelope, config.pairingSecret) ?: return
        Log.i(TAG, "Received ${message.optString("type")}")
        if (message.optString("type") == "hello") {
            val name = message.optString("name")
            if (name.isNotEmpty() && name != config.peerName) {
                config = config.copy(peerName = name)
                CompanionConfig.save(app, config)
            }
            if (!message.optBoolean("reply")) sendHello(reply = true)
        } else if (message.optString("type") == "clipboard_ack") {
            if (message.optString("clipboardID") == pendingClipboardId) {
                pendingClipboardId = null
                pendingClipboardText = null
                pendingClipboardRetryMs = 1_500L
                main.removeCallbacks(clipboardRetry)
            }
        } else if (message.optString("type") == "clipboard" && config.clipboardEnabled) {
            val text = message.optString("text")
            if (text.isNotEmpty()) {
                val id = message.optString("clipboardID")
                if (id.isNotEmpty() && receivedClipboardIds.contains(id)) {
                    send(JSONObject().put("type", "clipboard_ack").put("clipboardID", id))
                    return
                }
                suppressClipboardHash = CompanionCrypto.digest(text)
                val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                main.post {
                    clipboard.setPrimaryClip(ClipData.newPlainText("From Mac", text))
                    if (id.isNotEmpty()) {
                        receivedClipboardIds += id
                        while (receivedClipboardIds.size > 32) receivedClipboardIds.remove(receivedClipboardIds.first())
                        send(JSONObject().put("type", "clipboard_ack").put("clipboardID", id))
                    }
                }
            }
        }
        messageListeners.forEach { it(message) }
    }

    private fun sendHello(reply: Boolean) {
        send(JSONObject().put("type", "hello").put("name", android.os.Build.MODEL ?: "Android")
            .put("kind", "android").put("reply", reply))
    }

    /** The Mac name as last announced, for UI copy. */
    val peerName: String get() = config.peerName

    private fun ensureClipboardListener() {
        if (clipboardRegistered) return
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener {
            val current = CompanionConfig.load(app)
            if (!current.enabled || !current.clipboardEnabled) return@addPrimaryClipChangedListener
            val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString() }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: return@addPrimaryClipChangedListener
            forwardClipboardText(text)
        }
        clipboardRegistered = true
    }

    private fun updateState(newState: SupabaseRealtimeClient.State) {
        state = newState
        Log.i(TAG, "Realtime state=$newState")
        main.post {
            stateListeners.forEach { it(newState) }
            if (newState == SupabaseRealtimeClient.State.CONNECTED) sendHello(reply = false)
            if (newState == SupabaseRealtimeClient.State.CONNECTED && pendingClipboardId != null) {
                // Flush anything that was pending across the reconnect.
                pendingClipboardLastSent = 0L
                sendPendingClipboardIfDue(force = true)
            }
        }
    }
}
