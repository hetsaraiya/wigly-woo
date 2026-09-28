package com.wiglywoo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wiglywoo.mirror.MirrorHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class PeerRow(val id: String, val name: String, val fingerprint: String)
data class TransferUi(val name: String, val dir: String, val peer: String, val sent: Long, val total: Long, val speed: Double)
data class Trust(val name: String, val fingerprint: String, val file: String, val size: Long)
data class QueuedSend(val id: String, val peer: PeerRow, val uri: Uri, val name: String, val size: Long)
data class SentRecord(val name: String, val size: Long, val peer: String, val time: Long)

/**
 * Transfer state that outlives the activity's composition: core events,
 * the outgoing queue, history and trusted senders. Compose reads it directly;
 * the heads-up notification's Accept/Decline actions write to it.
 */
object WooState {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val speed = SpeedTracker()

    val peers = mutableStateListOf<PeerRow>()
    val received = mutableStateListOf<File>()
    val queue = mutableStateListOf<QueuedSend>()
    val sent = mutableStateListOf<SentRecord>()
    val trusted = mutableStateMapOf<String, String>()
    val receivedFrom = mutableStateMapOf<String, String>()
    var active by mutableStateOf<TransferUi?>(null)
        private set
    var trust by mutableStateOf<Trust?>(null)
        private set
    var sessionTotal by mutableLongStateOf(0L)
        private set
    var toast by mutableStateOf<String?>(null)
        private set

    /** Whether MainActivity is on screen; off-screen we notify instead. */
    @Volatile var foreground = false

    private var currentSend: QueuedSend? = null
    private var incomingPeer = ""
    private var lastProgressNotify = 0L
    private val clearToast = Runnable { toast = null }

    val incomingDir: File get() = File(app.getExternalFilesDir(null), "incoming")

    /** Called from MainActivity.onCreate, each time the core (re)starts. */
    private var coreStarted = false
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Starts the Go core once per process. The activity and the companion
     * service both call this; the core outlives the activity so the Mac can
     * reach the phone while it sits in the background.
     * Returns a message for the UI when the core could not start.
     */
    @Synchronized
    fun ensureCore(context: Context): String? {
        initialize(context)
        if (coreStarted) return null
        CoreBridge.load()
        CoreBridge.loadError?.let { return "Native library load failed:\n\n" + it.stackTraceToString() }
        return try {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("wigly-woo").apply {
                setReferenceCounted(false)
                acquire()
            }
            incomingDir.mkdirs()
            val rc = CoreBridge.start(name = CompanionConfig.deviceName(app), saveDir = incomingDir.absolutePath)
            if (rc == 0) {
                coreStarted = true
                null
            } else "woo_start returned $rc"
        } catch (t: Throwable) {
            "Core start failed:\n\n" + t.stackTraceToString()
        }
    }

    /** This phone's current core fingerprint, or "" before the core starts. */
    fun fingerprint(): String =
        if (coreStarted) runCatching { CoreBridge.identity().optString("fingerprint") }.getOrDefault("") else ""

    /** The discovered peer that is the paired Mac, falling back to the only peer. */
    fun pairedPeer(): PeerRow? {
        val fp = CompanionManager.macFingerprint
        return peers.firstOrNull { fp.isNotEmpty() && it.fingerprint == fp } ?: peers.singleOrNull()
    }

    private fun initialize(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        loadHistory()
        createChannels()
        refreshReceived()
        CoreBridge.listener = { ev ->
            // Session events carry file descriptors and may block on the
            // Shizuku binder, so they stay off the main thread.
            if (ev.optString("type").startsWith("session_")) MirrorHost.onCore(ev)
            else main.post { handle(ev) }
        }
    }

    fun flash(message: String) {
        main.removeCallbacks(clearToast)
        toast = message
        main.postDelayed(clearToast, 2600)
    }

    fun refreshReceived() {
        received.clear()
        received.addAll(incomingDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList())
    }

    // ── outgoing queue ──

    fun enqueue(uris: List<Uri>, peer: PeerRow) {
        io.execute {
            val items = uris.map { uri ->
                var name = "file"
                var size = -1L
                runCatching {
                    app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val si = c.getColumnIndex(OpenableColumns.SIZE)
                            if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                        }
                    }
                }
                QueuedSend(UUID.randomUUID().toString(), peer, uri, name, size)
            }
            main.post { queue.addAll(items); pump() }
        }
    }

    fun removeQueued(item: QueuedSend) { queue.removeAll { it.id == item.id } }

    fun cancel() = CoreBridge.cancel()

    private fun pump() {
        if (currentSend != null || queue.isEmpty()) return
        val next = queue.removeAt(0)
        currentSend = next
        io.execute {
            // Open the fd and hand it straight to the core: no copy, so the
            // transfer (and the Mac's accept prompt) starts instantly even for
            // multi-GB files. Native owns and closes the detached fd.
            val ok = runCatching {
                val pfd = app.contentResolver.openFileDescriptor(next.uri, "r") ?: return@runCatching false
                val size = if (next.size >= 0) next.size else pfd.statSize
                CoreBridge.sendFd(next.peer.id, pfd.detachFd(), next.name, size) == 0
            }.getOrDefault(false)
            if (!ok) main.post {
                currentSend = null
                flash("Couldn't open ${next.name}")
                pump()
            }
        }
    }

    // ── incoming ──

    fun answerTrust(req: Trust, accept: Boolean, always: Boolean = false) {
        if (!::app.isInitialized) return // process restarted; the core that asked is gone
        if (trust?.fingerprint == req.fingerprint) trust = null
        NotificationManagerCompat.from(app).cancel(NOTIFY_INCOMING)
        if (accept) {
            incomingPeer = req.name
            if (always) setTrusted(req.fingerprint, req.name)
        } else if (foreground) flash("Declined")
        CoreBridge.trust(req.fingerprint, accept)
    }

    fun setTrusted(fingerprint: String, name: String?) {
        if (name == null) trusted.remove(fingerprint) else trusted[fingerprint] = name
        saveHistory()
    }

    // ── core events (main thread) ──

    private fun handle(ev: JSONObject) {
        when (ev.optString("type")) {
            "peer_found" -> {
                val row = PeerRow(ev.optString("id"), ev.optString("name"), ev.optString("fingerprint"))
                if (peers.none { it.id == row.id }) peers.add(row)
            }
            "trust_request" -> {
                val req = Trust(ev.optString("name"), ev.optString("fingerprint"), ev.optString("file"), ev.optLong("size"))
                if (trusted.containsKey(req.fingerprint)) {
                    answerTrust(req, accept = true)
                    if (foreground) flash("Receiving from ${req.name} (trusted)")
                } else {
                    trust = req
                    if (!foreground) notifyIncoming(req)
                }
            }
            "progress" -> {
                val name = ev.optString("name")
                val dir = ev.optString("dir")
                val peer = if (dir == "send") currentSend?.peer?.name.orEmpty() else incomingPeer
                active = TransferUi(name, dir, peer, ev.optLong("sent"), ev.optLong("total"), speed.update(name, ev.optLong("sent")))
                if (!foreground) notifyProgress()
            }
            "done" -> {
                val a = active
                val dir = ev.optString("dir", a?.dir.orEmpty())
                val name = ev.optString("name", a?.name.orEmpty())
                val size = a?.let { if (it.total > 0) it.total else it.sent } ?: 0L
                sessionTotal += size
                val item = currentSend
                if (dir == "send" && item != null) {
                    sent.add(0, SentRecord(name, size, item.peer.name, System.currentTimeMillis()))
                    while (sent.size > 200) sent.removeAt(sent.size - 1)
                    currentSend = null
                    flash("Sent $name")
                } else {
                    val saved = ev.optString("path").takeIf { it.isNotEmpty() }?.let { File(it).name } ?: name
                    if (incomingPeer.isNotEmpty()) receivedFrom[saved] = incomingPeer
                    flash("Saved $saved")
                }
                saveHistory()
                finishTransfer()
            }
            "canceled", "error" -> {
                val wasSending = currentSend != null
                currentSend = null
                if (ev.optString("type") == "error") flash("Transfer failed. Check both devices are on the same Wi‑Fi.")
                else if (wasSending) flash("Canceled")
                finishTransfer()
            }
        }
    }

    private fun finishTransfer() {
        active = null
        speed.reset()
        refreshReceived()
        NotificationManagerCompat.from(app).cancel(NOTIFY_PROGRESS)
        pump()
    }

    // ── notifications while the app is in the background ──

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_INCOMING, "Incoming files", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Asks before saving a file sent from your Mac" })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_PROGRESS, "Transfers", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Progress while a file is moving" })
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(accept: Boolean, req: Trust): PendingIntent = PendingIntent.getBroadcast(
        app, if (accept) 1 else 2,
        Intent(app, IncomingActionReceiver::class.java)
            .putExtra("accept", accept).putExtra("fingerprint", req.fingerprint),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notifyIncoming(req: Trust) = runCatching {
        val n = NotificationCompat.Builder(app, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${req.name} wants to send a file")
            .setContentText("${req.file} · ${fmtBytes(req.size)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .addAction(0, "Decline", action(false, req))
            .addAction(0, "Accept", action(true, req))
            .build()
        NotificationManagerCompat.from(app).notify(NOTIFY_INCOMING, n)
    }

    private fun notifyProgress() = runCatching {
        val a = active ?: return@runCatching
        val now = System.currentTimeMillis()
        if (now - lastProgressNotify < 500) return@runCatching
        lastProgressNotify = now
        val pct = if (a.total > 0) (a.sent * 100 / a.total).toInt() else 0
        val n = NotificationCompat.Builder(app, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(a.name)
            .setSubText(if (a.dir == "send") "Sending to ${a.peer.ifEmpty { "Mac" }}" else "Receiving from ${a.peer.ifEmpty { "Mac" }}")
            .setContentText("$pct%")
            .setProgress(100, pct, a.total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()
        NotificationManagerCompat.from(app).notify(NOTIFY_PROGRESS, n)
    }

    // ── persistence ──

    private fun prefs() = app.getSharedPreferences("wigly_history", Context.MODE_PRIVATE)

    private fun loadHistory() {
        val p = prefs()
        runCatching {
            val arr = JSONArray(p.getString("sent", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                sent.add(SentRecord(o.optString("name"), o.optLong("size"), o.optString("peer"), o.optLong("time")))
            }
        }
        runCatching { JSONObject(p.getString("trusted", "{}")).let { o -> o.keys().forEach { trusted[it] = o.getString(it) } } }
        runCatching { JSONObject(p.getString("receivedFrom", "{}")).let { o -> o.keys().forEach { receivedFrom[it] = o.getString(it) } } }
    }

    private fun saveHistory() {
        val arr = JSONArray()
        sent.forEach { arr.put(JSONObject().put("name", it.name).put("size", it.size).put("peer", it.peer).put("time", it.time)) }
        prefs().edit()
            .putString("sent", arr.toString())
            .putString("trusted", JSONObject(trusted.toMap()).toString())
            .putString("receivedFrom", JSONObject(receivedFrom.toMap()).toString())
            .apply()
    }

    private const val CHANNEL_INCOMING = "wigly_incoming"
    private const val CHANNEL_PROGRESS = "wigly_transfers"
    private const val NOTIFY_INCOMING = 4301
    private const val NOTIFY_PROGRESS = 4302
}

/** Accept / Decline from the heads-up notification. */
class IncomingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fingerprint = intent.getStringExtra("fingerprint") ?: return
        val req = WooState.trust?.takeIf { it.fingerprint == fingerprint }
            ?: Trust("", fingerprint, "", 0)
        WooState.answerTrust(req, intent.getBooleanExtra("accept", false))
    }
}

class SpeedTracker {
    private var name = ""
    private var lastMs = 0L
    private var lastSent = 0L
    private var ema = 0.0

    /** Feed a progress sample; returns the current smoothed bytes/sec. */
    fun update(name: String, sent: Long): Double {
        val now = System.currentTimeMillis()
        if (name != this.name || sent < lastSent) {
            this.name = name; lastMs = now; lastSent = sent; ema = 0.0
        }
        val dt = (now - lastMs) / 1000.0
        if (dt >= 0.10) {
            val inst = (sent - lastSent) / dt
            ema = if (ema == 0.0) inst else ema * 0.6 + inst * 0.4
            lastMs = now; lastSent = sent
        }
        return ema
    }

    fun reset() { name = ""; lastMs = 0; lastSent = 0; ema = 0.0 }
}

fun fmtBytes(b: Long): String {
    if (b < 0) return "Unknown size"
    if (b < 1000) return "${maxOf(b, 1)} B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = -1
    do { v /= 1000; i++ } while (v >= 1000 && i < units.size - 1)
    return if (i == 0) "${Math.round(v)} KB" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}
