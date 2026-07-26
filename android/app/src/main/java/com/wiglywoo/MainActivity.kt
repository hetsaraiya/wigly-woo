package com.wiglywoo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var startupError: String? = null
        CoreBridge.load()
        if (CoreBridge.loadError != null) {
            startupError = "Native library load failed:\n\n" + CoreBridge.loadError!!.stackTraceToString()
        } else {
            try {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("wigly-woo").apply {
                    setReferenceCounted(true)
                    acquire()
                }
                val saveDir = File(getExternalFilesDir(null), "incoming").apply { mkdirs() }
                val rc = CoreBridge.start(name = Build.MODEL ?: "Android", saveDir = saveDir.absolutePath)
                if (rc != 0) startupError = "woo_start returned $rc"
            } catch (t: Throwable) {
                startupError = "Core start failed:\n\n" + t.stackTraceToString()
            }
        }

        CompanionManager.initialize(applicationContext)
        if (CompanionConfig.load(this).enabled) {
            ContextCompat.startForegroundService(this, Intent(this, CompanionForegroundService::class.java))
        }

        setContent { WiglyWooApp(startupError) }
    }

    override fun onDestroy() {
        super.onDestroy()
        CoreBridge.listener = null
        CoreBridge.stop()
        multicastLock?.let { if (it.isHeld) it.release() }
    }
}

private data class PeerRow(val id: String, val name: String, val fingerprint: String)
private data class TransferUi(val name: String, val dir: String, val sent: Long, val total: Long, val speed: Double)
private data class Trust(val name: String, val fingerprint: String, val file: String)

// ═══ v2 design tokens ════════════════════════════════════════════════════════

private data class V2(
    val wallTop: Color, val wallBottom: Color,
    val glass: Color, val glass2: Color, val stroke: Color, val hair: Color,
    val tx: Color, val tx2: Color, val tx3: Color,
    val ink: Color, val onInk: Color,
    val accent: Color, val accentSoft: Color, val surface: Color,
    val ok: Color, val warn: Color, val warnBg: Color, val warnLine: Color, val err: Color,
    val inset: Color, val chipBg: Color, val scrim: Color,
)

private val LightV2 = V2(
    wallTop = Color(0xFFF6F7FB), wallBottom = Color(0xFFF1F3F8),
    glass = Color(0xFFFFFFFF), glass2 = Color(0xFFFFFFFF),
    stroke = Color(0x161F2430), hair = Color(0x161F2430),
    tx = Color(0xFF1B1E27), tx2 = Color(0xFF5E6370), tx3 = Color(0xFF8F94A0),
    ink = Color(0xFF1B1E27), onInk = Color(0xFFFFFFFF),
    accent = Color(0xFF555BDC), accentSoft = Color(0x1A555BDC), surface = Color(0xFFFFFFFF),
    ok = Color(0xFF1F9D54), warn = Color(0xFFB57A12),
    warnBg = Color(0x1AB57A12), warnLine = Color(0x42B57A12), err = Color(0xFFCF4A3F),
    inset = Color(0xFFF6F7FB), chipBg = Color(0x0D17181C), scrim = Color(0x59141419),
)

private val DarkV2 = V2(
    wallTop = Color(0xFF111217), wallBottom = Color(0xFF111217),
    glass = Color(0xFF1D2028), glass2 = Color(0xFF242832),
    stroke = Color(0x17FFFFFF), hair = Color(0x17FFFFFF),
    tx = Color(0xFFF4F5F8), tx2 = Color(0xFFA8ACB8), tx3 = Color(0xFF737886),
    ink = Color(0xFFF4F5F8), onInk = Color(0xFF111217),
    accent = Color(0xFF7C83FF), accentSoft = Color(0x247C83FF), surface = Color(0xFF1D2028),
    ok = Color(0xFF4ECB85), warn = Color(0xFFE8B45A),
    warnBg = Color(0x1AE8B45A), warnLine = Color(0x47E8B45A), err = Color(0xFFEE7B6F),
    inset = Color(0x4D000000), chipBg = Color(0x0FFFFFFF), scrim = Color(0x80000000),
)

private val LocalV2 = staticCompositionLocalOf { LightV2 }

private fun Modifier.glass(t: V2, radius: androidx.compose.ui.unit.Dp, prominent: Boolean = false): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .shadow(if (prominent) 12.dp else 7.dp, shape, spotColor = Color.Black.copy(alpha = 0.30f))
        .background(if (prominent) t.glass2 else t.glass, shape)
        .border(1.dp, t.stroke, shape)
}

private fun Modifier.pressable(onClick: () -> Unit): Modifier =
    clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)

// ═══ Root ════════════════════════════════════════════════════════════════════

@Composable
fun WiglyWooApp(startupError: String? = null) {
    val dark = isSystemInDarkTheme()
    val t = if (dark) DarkV2 else LightV2
    val scheme = if (dark)
        darkColorScheme(primary = t.ink, onPrimary = t.onInk, surface = Color(0xFF24262D),
            background = t.wallBottom, onSurface = t.tx, onBackground = t.tx, outline = t.hair)
    else
        lightColorScheme(primary = t.ink, onPrimary = t.onInk, surface = Color.White,
            background = t.wallBottom, onSurface = t.tx, onBackground = t.tx, outline = t.hair)

    CompositionLocalProvider(LocalV2 provides t) {
        MaterialTheme(colorScheme = scheme) {
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(t.wallTop, t.wallBottom)))
            ) {
                if (startupError != null) ErrorScreen(startupError) else MainScreen()
            }
        }
    }
}

@Composable
private fun ErrorScreen(message: String) {
    val t = LocalV2.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Startup error", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = t.err)
        LazyColumn(Modifier.weight(1f)) {
            items(message.lines()) { Text(it, fontSize = 12.sp, color = t.tx2) }
        }
    }
}

private enum class Tab { Send, Inbox, Companion }

private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"

@Composable
private fun MainScreen() {
    val t = LocalV2.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    var tab by remember { mutableStateOf(Tab.Send) }
    var identity by remember { mutableStateOf("") }
    val peers = remember { mutableStateListOf<PeerRow>() }
    val received = remember { mutableStateListOf<File>() }
    var active by remember { mutableStateOf<TransferUi?>(null) }
    var sessionTotal by remember { mutableLongStateOf(0L) }
    var trust by remember { mutableStateOf<Trust?>(null) }
    var target by remember { mutableStateOf<PeerRow?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var companionState by remember { mutableStateOf(CompanionManager.state) }
    var config by remember { mutableStateOf(CompanionConfig.load(context)) }
    var orbMenuFor by remember { mutableStateOf<String?>(null) }
    var dismissedShizukuStatus by remember { mutableStateOf<ShizukuClipboardBridge.Status?>(null) }

    // Re-checked whenever the app comes back to the foreground (permission screens etc.)
    var permTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ShizukuClipboardBridge.refresh()
                permTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val notifAccess = remember(permTick) { notificationAccessGranted(context) }
    val kbEnabled = remember(permTick) { wiglyKeyboardEnabled(context) }
    val shizukuStatus = remember(permTick) { ShizukuClipboardBridge.status() }
    val shizukuInstalled = remember(permTick) { shizukuInstalled(context) }

    LaunchedEffect(shizukuStatus) {
        if (shizukuStatus == ShizukuClipboardBridge.Status.READY) {
            dismissedShizukuStatus = null
        }
    }

    fun handleShizukuAction() {
        when (shizukuStatus) {
            ShizukuClipboardBridge.Status.PERMISSION_REQUIRED,
            ShizukuClipboardBridge.Status.PERMISSION_BLOCKED -> {
                if (!ShizukuClipboardBridge.requestAccess()) openShizuku(context)
            }
            ShizukuClipboardBridge.Status.READY -> Unit
            else -> openShizuku(context)
        }
    }

    DisposableEffect(Unit) {
        val listener: (SupabaseRealtimeClient.State) -> Unit = { companionState = it }
        CompanionManager.addStateListener(listener)
        onDispose { CompanionManager.removeStateListener(listener) }
    }

    val track = remember { SpeedTracker() }
    val incomingDir = remember { File(context.getExternalFilesDir(null), "incoming") }
    fun refreshReceived() {
        received.clear()
        received.addAll(
            incomingDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        )
    }

    LaunchedEffect(Unit) {
        identity = runCatching { CoreBridge.identity().optString("fingerprint") }.getOrDefault("")
        refreshReceived()
        val main = Handler(Looper.getMainLooper())
        CoreBridge.listener = { ev ->
            main.post {
                when (ev.optString("type")) {
                    "peer_found" -> {
                        val row = PeerRow(ev.optString("id"), ev.optString("name"), ev.optString("fingerprint"))
                        if (peers.none { it.id == row.id }) peers.add(row)
                    }
                    "trust_request" -> trust = Trust(ev.optString("name"), ev.optString("fingerprint"), ev.optString("file"))
                    "progress" -> {
                        val name = ev.optString("name")
                        val dir = ev.optString("dir")
                        val sent = ev.optLong("sent")
                        val total = ev.optLong("total")
                        val speed = track.update(name, sent)
                        active = TransferUi(name, dir, sent, total, speed)
                    }
                    "done" -> {
                        val a = active
                        if (a != null) {
                            sessionTotal += if (a.total > 0) a.total else a.sent
                        }
                        active = null
                        track.reset()
                        refreshReceived()
                    }
                    "canceled", "error" -> {
                        active = null
                        track.reset()
                        refreshReceived()
                    }
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val peer = target ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            // Open the fd and hand it straight to the core — no copy, so the
            // transfer (and the Mac's accept prompt) starts instantly even for
            // multi-GB files. Off the main thread just for the metadata query.
            scope.launch(Dispatchers.IO) {
                openForSend(context, uri)?.let { src ->
                    CoreBridge.sendFd(peer.id, src.fd, src.name, src.size)
                }
            }
        }
    }

    fun sendClipboard() {
        val sent = CompanionManager.sendCurrentClipboard()
        Toast.makeText(context,
            if (sent) "Clipboard sent to Mac" else "Copy some text first or check the link",
            Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            MobileAppBar(
                state = companionState,
                config = config,
                onStatusTap = { tab = Tab.Companion }
            )

            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.Send -> SendTabV3(
                        peers = peers, transfer = active, files = received,
                        companionOnline = companionState == SupabaseRealtimeClient.State.CONNECTED,
                        onSendFile = { peer -> orbMenuFor = null; target = peer; picker.launch("*/*") },
                        onSendClipboard = { orbMenuFor = null; sendClipboard() },
                        onCancel = { CoreBridge.cancel() },
                        onOpenFile = { f -> openFile(context, f) },
                        onOpenInbox = { tab = Tab.Inbox },
                        onOpenCompanion = { tab = Tab.Companion })
                    Tab.Inbox -> InboxTabV3(
                        files = received, sessionTotal = sessionTotal,
                        onRefresh = { refreshReceived(); Toast.makeText(context, "Up to date", Toast.LENGTH_SHORT).show() },
                        onOpen = { f -> openFile(context, f) },
                        onShare = { f -> shareFile(context, f) })
                    Tab.Companion -> CompanionTabV3(
                        state = companionState, config = config,
                        notifAccess = notifAccess, kbEnabled = kbEnabled,
                        shizukuStatus = shizukuStatus,
                        onManage = { showSettings = true },
                        onGrantNotif = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        onEnableKb = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                        onShizukuAction = { handleShizukuAction() },
                        onPickKb = {
                            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                .showInputMethodPicker()
                        },
                        onToggleMirror = { on ->
                            config = config.copy(notificationsEnabled = on)
                            CompanionManager.applyConfig(config)
                        },
                        onToggleClip = { on ->
                            config = config.copy(clipboardEnabled = on)
                            CompanionManager.applyConfig(config)
                        },
                        onSendClipboard = { sendClipboard() })
                }
            }

            BottomNavV3(tab = tab, onSelect = { tab = it })
        }

        // ── incoming drop sheet ──
        SheetScaffold(visible = trust != null, dismissable = false, onDismiss = {}) {
            trust?.let { req ->
                IncomingDropSheet(req,
                    onAccept = { CoreBridge.trust(req.fingerprint, true); trust = null },
                    onDecline = { CoreBridge.trust(req.fingerprint, false); trust = null })
            }
        }

        // ── link setup sheet ──
        SheetScaffold(visible = showSettings, dismissable = true, onDismiss = { showSettings = false }) {
            LinkSetupSheet(
                initial = config,
                notifAccess = notifAccess, kbEnabled = kbEnabled,
                onGrantNotif = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onEnableKb = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                onPickKb = {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showInputMethodPicker()
                },
                onDismiss = { showSettings = false },
                onSave = { saved ->
                    CompanionManager.applyConfig(saved)
                    config = saved
                    if (saved.enabled && saved.isComplete) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        ContextCompat.startForegroundService(
                            context, Intent(context, CompanionForegroundService::class.java)
                        )
                    } else {
                        context.stopService(Intent(context, CompanionForegroundService::class.java))
                    }
                    showSettings = false
                })
        }

        val showShizukuPrompt = config.enabled && config.clipboardEnabled &&
            shizukuStatus != ShizukuClipboardBridge.Status.READY &&
            dismissedShizukuStatus != shizukuStatus &&
            trust == null && !showSettings

        SheetScaffold(
            visible = showShizukuPrompt,
            dismissable = true,
            onDismiss = { dismissedShizukuStatus = shizukuStatus }
        ) {
            ShizukuSetupSheet(
                status = shizukuStatus,
                installed = shizukuInstalled,
                onDismiss = { dismissedShizukuStatus = shizukuStatus },
                onAction = { handleShizukuAction() }
            )
        }
    }
}

// ═══ Capsule ═════════════════════════════════════════════════════════════════

@Composable
private fun CapsuleBar(
    state: SupabaseRealtimeClient.State,
    config: CompanionConfig,
    transfer: TransferUi?,
    onTap: () -> Unit,
) {
    val t = LocalV2.current
    val (text, dot) = capsuleStatus(t, state, config, transfer)
    val connecting = transfer == null && state == SupabaseRealtimeClient.State.CONNECTING

    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier.glass(t, 100.dp, prominent = true).pressable(onTap)
                .padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            if (connecting) {
                CircularProgressIndicator(Modifier.size(13.dp), color = t.tx, strokeWidth = 2.dp)
            } else {
                StatusDot(dot)
            }
            Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = t.tx,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (transfer != null && transfer.total > 0) {
                Box(Modifier.width(52.dp).height(4.dp).background(t.hair, RoundedCornerShape(100))) {
                    Box(Modifier.fillMaxHeight()
                        .fillMaxWidth(transfer.sent.toFloat() / transfer.total)
                        .background(t.tx, RoundedCornerShape(100)))
                }
            }
        }
    }
}

private fun capsuleStatus(t: V2, state: SupabaseRealtimeClient.State, config: CompanionConfig, transfer: TransferUi?): Pair<String, Color> {
    if (transfer != null) {
        val pct = if (transfer.total > 0) (transfer.sent * 100 / transfer.total).toInt() else 0
        return (if (transfer.dir == "send") "Sending $pct%" else "Receiving $pct%") to t.tx
    }
    return when {
        !config.isComplete -> "Not paired · tap to set up" to t.tx3
        !config.enabled -> "Link paused" to t.tx3
        state == SupabaseRealtimeClient.State.CONNECTED -> "Linked to your Mac" to t.ok
        state == SupabaseRealtimeClient.State.CONNECTING -> "Linking…" to t.warn
        else -> "Link lost · retrying" to t.err
    }
}

@Composable
private fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 9.dp) {
    Box(
        Modifier.size(size + 6.dp).background(color.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center
    ) { Box(Modifier.size(size).background(color, CircleShape)) }
}

// ═══ v3 product UI ══════════════════════════════════════════════════════════

private fun Modifier.modernCard(t: V2, radius: androidx.compose.ui.unit.Dp = 20.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .shadow(4.dp, shape, spotColor = Color.Black.copy(alpha = 0.16f))
        .background(t.surface, shape)
        .border(1.dp, t.hair, shape)
}

@Composable
private fun MobileAppBar(
    state: SupabaseRealtimeClient.State,
    config: CompanionConfig,
    onStatusTap: () -> Unit,
) {
    val t = LocalV2.current
    val (status, color) = when {
        !config.isComplete -> "Companion not set up" to t.tx3
        !config.enabled -> "Companion paused" to t.tx3
        state == SupabaseRealtimeClient.State.CONNECTED -> "Mac connected" to t.ok
        state == SupabaseRealtimeClient.State.CONNECTING -> "Connecting…" to t.warn
        else -> "Reconnecting" to t.err
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).background(t.accent, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("w", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        Text(
            "wigly woo",
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            color = t.tx,
            modifier = Modifier.padding(start = 10.dp)
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.heightIn(min = 48.dp).pressable(onStatusTap)
                .background(t.surface, RoundedCornerShape(100))
                .border(1.dp, t.hair, RoundedCornerShape(100))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            StatusDot(color, 7.dp)
            Text(status, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = t.tx2)
        }
    }
}

@Composable
private fun V3PageHeader(eyebrow: String, title: String, subtitle: String) {
    val t = LocalV2.current
    Column {
        Text(
            eyebrow.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
            color = t.accent
        )
        Text(
            title,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.6).sp,
            color = t.tx,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            subtitle,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = t.tx2,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun V3SectionHeader(title: String, detail: String? = null) {
    val t = LocalV2.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = t.tx)
        if (detail != null) {
            Text(detail, fontSize = 11.sp, color = t.tx3, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SendTabV3(
    peers: List<PeerRow>,
    transfer: TransferUi?,
    files: List<File>,
    companionOnline: Boolean,
    onSendFile: (PeerRow) -> Unit,
    onSendClipboard: () -> Unit,
    onCancel: () -> Unit,
    onOpenFile: (File) -> Unit,
    onOpenInbox: () -> Unit,
    onOpenCompanion: () -> Unit,
) {
    val t = LocalV2.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        V3PageHeader(
            eyebrow = "Nearby sharing",
            title = "Send a file",
            subtitle = "Choose a nearby device. Files travel directly over your local network."
        )

        if (transfer != null) {
            V3TransferCard(transfer, onCancel)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            V3SectionHeader(
                title = "Nearby devices",
                detail = if (peers.isEmpty()) "Searching automatically" else "${peers.size} available"
            )
            if (peers.isEmpty()) {
                V3DiscoveryCard()
            } else {
                peers.forEach { peer ->
                    V3DeviceCard(peer = peer, onSendFile = { onSendFile(peer) })
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            V3SectionHeader("Quick actions")
            V3QuickAction(
                symbol = "⌘",
                title = "Clipboard & keyboard",
                detail = if (companionOnline)
                    "Your Mac companion is connected and ready."
                else
                    "Connect your Mac to sync text and type remotely.",
                action = if (companionOnline) "Send clipboard" else "Set up",
                color = if (companionOnline) t.ok else t.accent,
                onClick = if (companionOnline) onSendClipboard else onOpenCompanion
            )
            V3QuickAction(
                symbol = "↓",
                title = "Received files",
                detail = if (files.isEmpty())
                    "Files sent to this phone will appear in your inbox."
                else
                    "${files.size} ${if (files.size == 1) "file" else "files"} ready to open.",
                action = "Open inbox",
                color = t.accent,
                onClick = onOpenInbox
            )
        }

        if (files.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                V3SectionHeader("Recent", "Last received")
                files.take(3).forEach { file ->
                    V3CompactFileRow(file = file, onOpen = { onOpenFile(file) })
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun V3DiscoveryCard() {
    val t = LocalV2.current
    val inf = rememberInfiniteTransition(label = "discovery")
    val pulse by inf.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "discoveryPulse"
    )
    Row(
        Modifier.fillMaxWidth().modernCard(t).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(72.dp).border(
                    1.dp,
                    t.accent.copy(alpha = pulse * 0.35f),
                    CircleShape
                )
            )
            Box(
                Modifier.size(50.dp).background(t.accentSoft, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("⌁", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = t.accent)
            }
        }
        Column(Modifier.weight(1f)) {
            Text("Looking for your devices", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = t.tx)
            Text(
                "Open Wigly Woo on the other device and keep both devices on the same Wi‑Fi.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = t.tx2,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                "Only visible on your local network",
                fontSize = 10.5.sp,
                color = t.tx3,
                modifier = Modifier.padding(top = 7.dp)
            )
        }
    }
}

@Composable
private fun V3DeviceCard(peer: PeerRow, onSendFile: () -> Unit) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth().modernCard(t).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(48.dp).background(t.accentSoft, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            LaptopGlyph(color = t.accent, size = 24.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                peer.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = t.tx,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Box(Modifier.size(6.dp).background(t.ok, CircleShape))
                Text("Nearby on Wi‑Fi", fontSize = 10.5.sp, color = t.tx3)
            }
        }
        Text(
            "Choose file",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 48.dp)
                .background(t.accent, RoundedCornerShape(12.dp))
                .pressable(onSendFile)
                .padding(horizontal = 15.dp, vertical = 15.dp)
        )
    }
}

@Composable
private fun V3QuickAction(
    symbol: String,
    title: String,
    detail: String,
    action: String,
    color: Color,
    onClick: () -> Unit,
) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth().modernCard(t).heightIn(min = 82.dp)
            .pressable(onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(44.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(symbol, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = t.tx)
            Text(detail, fontSize = 11.sp, lineHeight = 15.sp, color = t.tx2,
                modifier = Modifier.padding(top = 2.dp))
        }
        Text(action, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun V3TransferCard(transfer: TransferUi, onCancel: () -> Unit) {
    val t = LocalV2.current
    val progress = if (transfer.total > 0)
        (transfer.sent.toFloat() / transfer.total.toFloat()).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().modernCard(t).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(t.accentSoft, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(if (transfer.dir == "send") "↑" else "↓",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.accent)
            }
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text(
                    if (transfer.dir == "send") "Sending now" else "Receiving now",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = t.accent
                )
                Text(
                    transfer.name,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = t.tx,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("${(progress * 100).toInt()}%",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = t.tx)
            Text(
                "Cancel",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.tx2,
                modifier = Modifier.heightIn(min = 48.dp).pressable(onCancel)
                    .padding(start = 12.dp, top = 16.dp, bottom = 16.dp)
            )
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp).background(t.hair, RoundedCornerShape(100)),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(progress)
                    .background(t.accent, RoundedCornerShape(100))
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("${fmtBytes(transfer.sent)} of ${fmtBytes(transfer.total)}",
                fontSize = 10.5.sp, color = t.tx3)
            Spacer(Modifier.weight(1f))
            Text(fmtSpeed(transfer.speed), fontSize = 10.5.sp, color = t.tx3)
        }
    }
}

@Composable
private fun V3CompactFileRow(file: File, onOpen: () -> Unit) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).modernCard(t, 16.dp)
            .pressable(onOpen).padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(40.dp).background(t.accentSoft, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(extOf(file.name), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = t.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(file.name, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = t.tx,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${fmtBytes(file.length())} · ${fmtDate(file.lastModified())}",
                fontSize = 10.5.sp, color = t.tx3, modifier = Modifier.padding(top = 3.dp))
        }
        Text("Open", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = t.accent)
    }
}

@Composable
private fun InboxTabV3(
    files: List<File>,
    sessionTotal: Long,
    onRefresh: () -> Unit,
    onOpen: (File) -> Unit,
    onShare: (File) -> Unit,
) {
    val t = LocalV2.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                V3PageHeader(
                    eyebrow = "Received",
                    title = "Inbox",
                    subtitle = if (files.isEmpty())
                        "Files sent to this phone will appear here."
                    else
                        "${files.size} ${if (files.size == 1) "file" else "files"} saved on this phone."
                )
            }
            Text(
                "Refresh",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.tx2,
                textAlign = TextAlign.Center,
                modifier = Modifier.heightIn(min = 48.dp)
                    .background(t.surface, RoundedCornerShape(100))
                    .border(1.dp, t.hair, RoundedCornerShape(100))
                    .pressable(onRefresh)
                    .padding(horizontal = 14.dp, vertical = 15.dp)
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            V3InfoChip("Files", "${files.size}", Modifier.weight(1f))
            V3InfoChip("This session", fmtBytes(sessionTotal), Modifier.weight(1f))
        }

        if (files.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).modernCard(t),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.size(68.dp).background(t.accentSoft, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("↓", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = t.accent)
                }
                Text("Your inbox is ready", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.tx,
                    modifier = Modifier.padding(top = 14.dp))
                Text(
                    "Send something from your Mac and accept it here. It will be saved automatically.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center,
                    color = t.tx2,
                    modifier = Modifier.widthIn(max = 280.dp).padding(top = 5.dp)
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(files) { file ->
                    V3InboxFileRow(
                        file = file,
                        onOpen = { onOpen(file) },
                        onShare = { onShare(file) }
                    )
                }
            }
        }
    }
}

@Composable
private fun V3InfoChip(label: String, value: String, modifier: Modifier = Modifier) {
    val t = LocalV2.current
    Row(
        modifier.background(t.surface, RoundedCornerShape(13.dp))
            .border(1.dp, t.hair, RoundedCornerShape(13.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.5.sp, color = t.tx3)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = t.tx2)
    }
}

@Composable
private fun V3InboxFileRow(file: File, onOpen: () -> Unit, onShare: () -> Unit) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth().modernCard(t, 16.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(
            Modifier.size(44.dp).background(t.accentSoft, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(extOf(file.name), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = t.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(file.name, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = t.tx,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${fmtBytes(file.length())} · ${fmtDate(file.lastModified())}",
                fontSize = 10.5.sp, color = t.tx3, modifier = Modifier.padding(top = 3.dp))
        }
        Text(
            "Share",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = t.tx2,
            textAlign = TextAlign.Center,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .pressable(onShare).padding(vertical = 16.dp)
        )
        Text(
            "Open",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 48.dp)
                .background(t.accent, RoundedCornerShape(11.dp))
                .pressable(onOpen)
                .padding(horizontal = 13.dp, vertical = 16.dp)
        )
    }
}

@Composable
private fun CompanionTabV3(
    state: SupabaseRealtimeClient.State,
    config: CompanionConfig,
    notifAccess: Boolean,
    kbEnabled: Boolean,
    shizukuStatus: ShizukuClipboardBridge.Status,
    onManage: () -> Unit,
    onGrantNotif: () -> Unit,
    onEnableKb: () -> Unit,
    onShizukuAction: () -> Unit,
    onPickKb: () -> Unit,
    onToggleMirror: (Boolean) -> Unit,
    onToggleClip: (Boolean) -> Unit,
    onSendClipboard: () -> Unit,
) {
    val t = LocalV2.current
    val online = config.enabled && config.isComplete &&
        state == SupabaseRealtimeClient.State.CONNECTED

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        V3PageHeader(
            eyebrow = "Across networks",
            title = "Mac companion",
            subtitle = "Sync notifications and clipboard, or type on this phone from your Mac."
        )

        V3CompanionHero(
            state = state,
            config = config,
            onManage = onManage
        )

        val attentionCount = listOf(
            config.enabled && config.notificationsEnabled && !notifAccess,
            config.enabled && !kbEnabled,
            config.enabled && config.clipboardEnabled &&
                shizukuStatus != ShizukuClipboardBridge.Status.READY
        ).count { it }

        if (attentionCount > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                V3SectionHeader("Needs attention", "$attentionCount ${if (attentionCount == 1) "step" else "steps"}")
                if (config.enabled && config.notificationsEnabled && !notifAccess) {
                    V3ActionNotice(
                        title = "Allow notification access",
                        detail = "Required to mirror Android notifications on your Mac.",
                        action = "Allow",
                        onClick = onGrantNotif
                    )
                }
                if (config.enabled && !kbEnabled) {
                    V3ActionNotice(
                        title = "Enable Wigly Woo keyboard",
                        detail = "Required before your Mac can type into Android apps.",
                        action = "Enable",
                        onClick = onEnableKb
                    )
                }
                if (config.enabled && config.clipboardEnabled &&
                    shizukuStatus != ShizukuClipboardBridge.Status.READY) {
                    V3ActionNotice(
                        title = when (shizukuStatus) {
                            ShizukuClipboardBridge.Status.NOT_RUNNING -> "Start Shizuku"
                            ShizukuClipboardBridge.Status.PERMISSION_REQUIRED -> "Allow Shizuku access"
                            ShizukuClipboardBridge.Status.PERMISSION_BLOCKED -> "Shizuku access was denied"
                            ShizukuClipboardBridge.Status.UNSUPPORTED -> "Update Shizuku"
                            ShizukuClipboardBridge.Status.READY -> "Shizuku is ready"
                        },
                        detail = shizukuWarning(shizukuStatus),
                        action = if (shizukuStatus == ShizukuClipboardBridge.Status.PERMISSION_REQUIRED ||
                            shizukuStatus == ShizukuClipboardBridge.Status.PERMISSION_BLOCKED) "Allow" else "Open",
                        onClick = onShizukuAction
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            V3SectionHeader("Services", "Choose what stays in sync")
            Column(Modifier.fillMaxWidth().modernCard(t)) {
                V3ServiceRow(
                    symbol = "●",
                    title = "Notifications",
                    detail = when {
                        !config.notificationsEnabled -> "Turned off"
                        !notifAccess -> "Permission needed"
                        online -> "Mirroring to your Mac"
                        else -> "Waiting for connection"
                    },
                    active = online && config.notificationsEnabled && notifAccess,
                    checked = config.notificationsEnabled,
                    onToggle = onToggleMirror
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(t.hair))
                V3ServiceRow(
                    symbol = "▣",
                    title = "Clipboard",
                    detail = when {
                        !config.clipboardEnabled -> "Turned off"
                        shizukuStatus != ShizukuClipboardBridge.Status.READY -> "Shizuku setup needed"
                        online -> "Changes sync automatically"
                        else -> "Waiting for connection"
                    },
                    active = online && config.clipboardEnabled &&
                        shizukuStatus == ShizukuClipboardBridge.Status.READY,
                    checked = config.clipboardEnabled,
                    onToggle = onToggleClip
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(t.hair))
                V3KeyboardServiceRow(
                    active = online && kbEnabled,
                    enabled = kbEnabled,
                    onSelect = onPickKb
                )
            }
        }

        val clipReady = online && config.clipboardEnabled
        Text(
            if (clipReady) "Send clipboard to Mac" else "Clipboard unavailable",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (clipReady) Color.White else t.tx3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .background(if (clipReady) t.accent else t.chipBg, RoundedCornerShape(13.dp))
                .pressable { if (clipReady) onSendClipboard() }
                .padding(vertical = 17.dp)
        )

        Text(
            "Connection settings",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = t.accent,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .pressable(onManage).padding(vertical = 15.dp)
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun V3CompanionHero(
    state: SupabaseRealtimeClient.State,
    config: CompanionConfig,
    onManage: () -> Unit,
) {
    val t = LocalV2.current
    val values = when {
        !config.isComplete -> arrayOf(
            "Connect your Mac",
            "Add the same relay details and pairing secret on both devices.",
            "Set up",
            "accent"
        )
        !config.enabled -> arrayOf(
            "Companion is paused",
            "Your pairing is saved. Turn it on whenever you need remote features.",
            "Resume",
            "neutral"
        )
        state == SupabaseRealtimeClient.State.CONNECTED -> arrayOf(
            "Mac is connected",
            "Your encrypted companion channel is ready.",
            "Manage",
            "success"
        )
        state == SupabaseRealtimeClient.State.CONNECTING -> arrayOf(
            "Connecting to Mac…",
            "Wigly Woo is opening the encrypted companion channel.",
            "Check",
            "warning"
        )
        else -> arrayOf(
            "Reconnecting automatically",
            "Nearby file sharing still works while the companion reconnects.",
            "Check",
            "danger"
        )
    }
    val color = when (values[3]) {
        "success" -> t.ok
        "warning" -> t.warn
        "danger" -> t.err
        "neutral" -> t.tx3
        else -> t.accent
    }
    Row(
        Modifier.fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .border(1.dp, color.copy(alpha = 0.22f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Box(
            Modifier.size(54.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            LaptopGlyph(color = color, size = 27.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(values[0], fontSize = 15.sp, fontWeight = FontWeight.Bold, color = t.tx)
            Text(values[1], fontSize = 11.5.sp, lineHeight = 16.sp, color = t.tx2,
                modifier = Modifier.padding(top = 3.dp))
        }
        Text(
            values[2],
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 48.dp)
                .background(color, RoundedCornerShape(11.dp))
                .pressable(onManage)
                .padding(horizontal = 13.dp, vertical = 16.dp)
        )
    }
}

@Composable
private fun V3ActionNotice(
    title: String,
    detail: String,
    action: String,
    onClick: () -> Unit,
) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth()
            .background(t.warnBg, RoundedCornerShape(15.dp))
            .border(1.dp, t.warnLine, RoundedCornerShape(15.dp))
            .pressable(onClick)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(8.dp).background(t.warn, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = t.tx)
            Text(detail, fontSize = 10.5.sp, lineHeight = 15.sp, color = t.tx2,
                modifier = Modifier.padding(top = 2.dp))
        }
        Text(action, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = t.warn,
            modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 16.dp))
    }
}

@Composable
private fun V3ServiceRow(
    symbol: String,
    title: String,
    detail: String,
    active: Boolean,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(
            Modifier.size(40.dp).background(
                if (active) t.ok.copy(alpha = 0.12f) else t.inset,
                RoundedCornerShape(12.dp)
            ),
            contentAlignment = Alignment.Center
        ) {
            Text(symbol, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = if (active) t.ok else t.tx3)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.tx)
            Text(detail, fontSize = 10.5.sp, color = t.tx3, modifier = Modifier.padding(top = 2.dp))
        }
        V2Switch(checked, onToggle)
    }
}

@Composable
private fun V3KeyboardServiceRow(active: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(
            Modifier.size(40.dp).background(
                if (active) t.ok.copy(alpha = 0.12f) else t.inset,
                RoundedCornerShape(12.dp)
            ),
            contentAlignment = Alignment.Center
        ) {
            Text("⌨", fontSize = 16.sp, color = if (active) t.ok else t.tx3)
        }
        Column(Modifier.weight(1f)) {
            Text("Remote keyboard", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.tx)
            Text(
                if (!enabled) "Enable the keyboard first"
                else if (active) "Ready for Mac input" else "Waiting for connection",
                fontSize = 10.5.sp,
                color = t.tx3,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            "Select",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = t.accent,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 48.dp).pressable(onSelect)
                .padding(horizontal = 8.dp, vertical = 16.dp)
        )
    }
}

@Composable
private fun BottomNavV3(tab: Tab, onSelect: (Tab) -> Unit) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth().background(t.surface)
            .border(1.dp, t.hair)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Tab.entries.forEach { item ->
            val selected = item == tab
            Column(
                Modifier.weight(1f).heightIn(min = 56.dp)
                    .background(if (selected) t.accentSoft else Color.Transparent, RoundedCornerShape(14.dp))
                    .pressable { onSelect(item) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.width(if (selected) 18.dp else 6.dp).height(3.dp)
                        .background(if (selected) t.accent else t.tx3, RoundedCornerShape(100))
                )
                Text(
                    item.name,
                    fontSize = 11.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) t.accent else t.tx3,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}

// ═══ Space tab ═══════════════════════════════════════════════════════════════

@Composable
private fun SpaceTab(
    peers: List<PeerRow>,
    transfer: TransferUi?,
    files: List<File>,
    orbMenuFor: String?,
    onOrbTap: (String) -> Unit,
    onSendFile: (PeerRow) -> Unit,
    onSendClipboard: () -> Unit,
    onCancel: () -> Unit,
    onOpenFile: (File) -> Unit,
) {
    val t = LocalV2.current
    Column(Modifier.fillMaxSize()) {
        Text(
            if (peers.isEmpty()) "Nothing in range yet"
            else "${peers.size} device${if (peers.size == 1) "" else "s"} in range",
            fontSize = 11.sp, color = t.tx3, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            RadarBackdrop(searching = peers.isEmpty())

            if (peers.isEmpty()) {
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 110.dp).width(240.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Scanning the room", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.tx2)
                    Text("Devices on your Wi‑Fi drift in here on their own.",
                        fontSize = 11.sp, color = t.tx3, textAlign = TextAlign.Center,
                        lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
                }
            } else {
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    peers.forEach { peer ->
                        DeviceOrb(peer,
                            menuOpen = orbMenuFor == peer.id,
                            onTap = { onOrbTap(peer.id) },
                            onSendFile = { onSendFile(peer) },
                            onSendClipboard = onSendClipboard)
                    }
                }
            }

            // transfer thread + chip
            if (transfer != null) {
                TransferThread(transfer, onCancel)
            }

            // you node
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier.size(56.dp)
                        .shadow(14.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.35f))
                        .background(t.ink, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    PhoneGlyph(color = t.onInk, size = 22.dp)
                }
                Text("This phone", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = t.tx3)
            }
        }

        // recent drops strip
        if (files.isNotEmpty()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp, top = 2.dp)) {
                SectionCaps("Recent drops", Modifier.padding(start = 4.dp, bottom = 7.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files.take(6)) { f ->
                        Column(
                            Modifier.width(118.dp).glass(t, 16.dp).pressable { onOpenFile(f) }
                                .padding(horizontal = 12.dp, vertical = 11.dp)
                        ) {
                            Text(extOf(f.name), fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp, color = t.tx3)
                            Text(f.name, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = t.tx,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 5.dp))
                            Text(fmtBytes(f.length()), fontSize = 10.sp, color = t.tx3,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarBackdrop(searching: Boolean) {
    val t = LocalV2.current
    val inf = rememberInfiniteTransition(label = "radar")
    val breathe by inf.animateFloat(
        initialValue = 1f, targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe")
    val sweep by inf.animateFloat(
        initialValue = -72f, targetValue = 72f,
        animationSpec = infiniteRepeatable(tween(3200), RepeatMode.Reverse),
        label = "sweep")

    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height - 58.dp.toPx())
        val stroke = Stroke(width = 1.dp.toPx())
        drawCircle(t.hair, radius = 125.dp.toPx(), center = center, style = stroke)
        drawCircle(t.hair.copy(alpha = t.hair.alpha * 0.9f), radius = 230.dp.toPx() * breathe, center = center, style = stroke)
        drawCircle(t.hair, radius = 340.dp.toPx(), center = center, style = stroke)

        if (searching) {
            rotate(degrees = sweep, pivot = center) {
                drawLine(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent, 0.55f to t.tx3.copy(alpha = 0.5f), 1f to Color.Transparent,
                        startY = center.y - 310.dp.toPx(), endY = center.y),
                    start = Offset(center.x, center.y - 310.dp.toPx()),
                    end = center,
                    strokeWidth = 1.5.dp.toPx())
            }
        }
    }
}

@Composable
private fun DeviceOrb(
    peer: PeerRow,
    menuOpen: Boolean,
    onTap: () -> Unit,
    onSendFile: () -> Unit,
    onSendClipboard: () -> Unit,
) {
    val t = LocalV2.current
    val inf = rememberInfiniteTransition(label = "orb")
    val float by inf.animateFloat(
        initialValue = 0f, targetValue = -7f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Reverse),
        label = "float")

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.offset(y = float.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                Modifier.size(86.dp).glass(t, 43.dp, prominent = true).pressable(onTap),
                contentAlignment = Alignment.Center
            ) { LaptopGlyph(color = t.tx, size = 34.dp) }
            Text(peer.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = t.tx,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.glass(t, 100.dp).padding(horizontal = 12.dp, vertical = 4.dp))
        }

        AnimatedVisibility(menuOpen, enter = fadeIn(tween(150)), exit = fadeOut(tween(120))) {
            Column(
                Modifier.padding(top = 10.dp).width(190.dp).glass(t, 18.dp, prominent = true).padding(6.dp)
            ) {
                OrbAction("Send a file", onSendFile)
                OrbAction("Send clipboard", onSendClipboard)
            }
        }
    }
}

@Composable
private fun OrbAction(label: String, onClick: () -> Unit) {
    val t = LocalV2.current
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.tx,
        modifier = Modifier.fillMaxWidth().pressable(onClick)
            .background(Color.Transparent, RoundedCornerShape(13.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp))
}

@Composable
private fun TransferThread(transfer: TransferUi, onCancel: () -> Unit) {
    val t = LocalV2.current
    val inf = rememberInfiniteTransition(label = "thread")
    val phase by inf.animateFloat(
        initialValue = 0f, targetValue = -40f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase")
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawLine(
                color = t.tx2.copy(alpha = 0.55f),
                start = Offset(size.width / 2f, 176.dp.toPx()),
                end = Offset(size.width / 2f, size.height - 104.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(with(density) { 6.dp.toPx() }, with(density) { 7.dp.toPx() }),
                    phase))
        }

        val pct = if (transfer.total > 0) (transfer.sent * 100 / transfer.total).toInt() else 0
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                Modifier.widthIn(max = 270.dp).glass(t, 100.dp, prominent = true)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(7.dp).background(t.tx, CircleShape))
                Text(transfer.name, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = t.tx,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("$pct%", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = t.tx)
                Box(
                    Modifier.size(18.dp).background(t.chipBg, CircleShape).pressable(onCancel),
                    contentAlignment = Alignment.Center
                ) { Text("×", fontSize = 11.sp, color = t.tx2) }
            }
            Text("${fmtSpeed(transfer.speed)} · ${fmtBytes(transfer.sent)} of ${fmtBytes(transfer.total)}",
                fontSize = 10.5.sp, color = t.tx3)
        }
    }
}

// ═══ Drops tab ═══════════════════════════════════════════════════════════════

@Composable
private fun DropsTab(
    files: List<File>,
    sessionTotal: Long,
    onRefresh: () -> Unit,
    onOpen: (File) -> Unit,
    onShare: (File) -> Unit,
) {
    val t = LocalV2.current
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Drops", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp, color = t.tx)
            Spacer(Modifier.weight(1f))
            ChipButton("Refresh", onRefresh)
        }

        if (files.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                DashedCircleGlyph()
                Text("Nothing dropped yet", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.tx2)
                Text("Anything your Mac sends lands here, ready to open or share.",
                    fontSize = 11.5.sp, color = t.tx3, textAlign = TextAlign.Center,
                    lineHeight = 17.sp, modifier = Modifier.width(220.dp))
            }
            Spacer(Modifier.weight(1f))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(files) { f ->
                    Column(
                        Modifier.glass(t, 18.dp).pressable { onOpen(f) }
                            .padding(horizontal = 14.dp, vertical = 13.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(extOf(f.name), fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp, color = t.tx3,
                                modifier = Modifier.background(t.chipBg, RoundedCornerShape(100))
                                    .padding(horizontal = 8.dp, vertical = 3.dp))
                            Spacer(Modifier.weight(1f))
                            Text("↑", fontSize = 14.sp, color = t.tx3,
                                modifier = Modifier.pressable { onShare(f) }.padding(4.dp))
                        }
                        Text(f.name, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = t.tx,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 9.dp))
                        Text("${fmtBytes(f.length())} · ${fmtDate(f.lastModified())}",
                            fontSize = 10.5.sp, color = t.tx3, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }

        Text("${fmtBytes(sessionTotal)} moved this session",
            fontSize = 11.sp, color = t.tx3, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
    }
}

@Composable
private fun DashedCircleGlyph() {
    val t = LocalV2.current
    androidx.compose.foundation.Canvas(Modifier.size(52.dp)) {
        drawCircle(
            t.tx3.copy(alpha = 0.7f), style = Stroke(
                width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
        val cx = size.width / 2; val cy = size.height / 2
        drawLine(t.tx3, Offset(cx, cy - 7.dp.toPx()), Offset(cx, cy + 5.dp.toPx()),
            strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawLine(t.tx3, Offset(cx - 4.dp.toPx(), cy + 1.dp.toPx()), Offset(cx, cy + 5.dp.toPx()),
            strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawLine(t.tx3, Offset(cx + 4.dp.toPx(), cy + 1.dp.toPx()), Offset(cx, cy + 5.dp.toPx()),
            strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
    }
}

// ═══ Link tab ════════════════════════════════════════════════════════════════

@Composable
private fun LinkTab(
    state: SupabaseRealtimeClient.State,
    config: CompanionConfig,
    notifAccess: Boolean,
    kbEnabled: Boolean,
    shizukuStatus: ShizukuClipboardBridge.Status,
    onManage: () -> Unit,
    onGrantNotif: () -> Unit,
    onEnableKb: () -> Unit,
    onShizukuAction: () -> Unit,
    onPickKb: () -> Unit,
    onToggleMirror: (Boolean) -> Unit,
    onToggleClip: (Boolean) -> Unit,
    onSendClipboard: () -> Unit,
) {
    val t = LocalV2.current
    val online = state == SupabaseRealtimeClient.State.CONNECTED
    val (title, desc, btn, dot) = when {
        !config.isComplete -> Quad("No link yet",
            "Pair this phone with your Mac to mirror notifications, share a clipboard and type from a real keyboard.",
            "Set up the link", t.tx3)
        !config.enabled -> Quad("Link paused", "Your pairing is saved. Flip it back on whenever you need it.", "Manage", t.tx3)
        online -> Quad("Linked to your Mac",
            "Notifications, clipboard and keyboard flow both ways over an encrypted channel.", "Manage", t.ok)
        state == SupabaseRealtimeClient.State.CONNECTING -> Quad("Linking…", "Reaching the encrypted relay channel.", "Manage", t.warn)
        else -> Quad("Link needs attention", "Can't reach the relay. Check the pairing settings or your network.", "Manage", t.err)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.padding(horizontal = 2.dp, vertical = 6.dp)) {
            Text("Link", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp, color = t.tx)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(dot)
                Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.tx)
            }
            Text(desc, fontSize = 12.sp, color = t.tx2, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
            Text(btn, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = t.onInk,
                modifier = Modifier.padding(top = 10.dp)
                    .background(t.ink, RoundedCornerShape(100))
                    .pressable(onManage)
                    .padding(horizontal = 18.dp, vertical = 8.dp))
        }

        if (config.enabled && !notifAccess) {
            WarnBanner("Notification access is off — nothing can mirror to your Mac.", "Tap to allow.", onGrantNotif)
        }
        if (config.enabled && !kbEnabled) {
            WarnBanner("The wigly-woo keyboard isn't enabled yet.", "Tap to enable.", onEnableKb)
        }
        if (config.enabled && config.clipboardEnabled &&
            shizukuStatus != ShizukuClipboardBridge.Status.READY) {
            WarnBanner(
                body = shizukuWarning(shizukuStatus),
                action = if (shizukuStatus == ShizukuClipboardBridge.Status.PERMISSION_REQUIRED ||
                    shizukuStatus == ShizukuClipboardBridge.Status.PERMISSION_BLOCKED)
                    "Allow access." else "Open Shizuku.",
                onClick = onShizukuAction
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            FeatureTile(
                modifier = Modifier.weight(1f),
                title = "Notifications",
                subtitle = when {
                    !notifAccess -> "Needs permission"
                    config.notificationsEnabled -> if (online) "Mirroring to Mac" else "Waiting for link"
                    else -> "Off"
                },
                active = online && config.notificationsEnabled && notifAccess,
                checked = config.notificationsEnabled, onToggle = onToggleMirror)
            FeatureTile(
                modifier = Modifier.weight(1f),
                title = "Clipboard",
                subtitle = if (config.clipboardEnabled) (if (online) "Two-way sync live" else "Waiting for link") else "Off",
                active = online && config.clipboardEnabled,
                checked = config.clipboardEnabled, onToggle = onToggleClip)
        }

        Row(
            Modifier.fillMaxWidth().glass(t, 20.dp).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusDot(if (online && kbEnabled) t.ok else t.tx3, 8.dp)
            Column(Modifier.weight(1f)) {
                Text("Remote keyboard", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.tx)
                Text(if (kbEnabled) "Mac keys type here" else "Not enabled yet",
                    fontSize = 10.5.sp, color = t.tx3, modifier = Modifier.padding(top = 1.dp))
            }
            ChipButton("Select", onPickKb)
        }

        val clipReady = online && config.clipboardEnabled
        Text("Send clipboard now",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (clipReady) t.onInk else t.tx3, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
                .background(if (clipReady) t.ink else t.chipBg, RoundedCornerShape(100))
                .pressable(onSendClipboard)
                .padding(vertical = 13.dp))
    }
}

private data class Quad(val a: String, val b: String, val c: String, val d: Color)

@Composable
private fun WarnBanner(body: String, action: String, onClick: () -> Unit) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth()
            .background(t.warnBg, RoundedCornerShape(16.dp))
            .border(1.dp, t.warnLine, RoundedCornerShape(16.dp))
            .pressable(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(7.dp).background(t.warn, CircleShape))
        Text(body, fontSize = 12.sp, color = t.tx2,
            lineHeight = 17.sp, modifier = Modifier.weight(1f))
        Text(action, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.tx)
    }
}

@Composable
private fun FeatureTile(
    modifier: Modifier,
    title: String,
    subtitle: String,
    active: Boolean,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val t = LocalV2.current
    Column(modifier.glass(t, 20.dp).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (active) t.ok else t.tx3, 8.dp)
            Spacer(Modifier.weight(1f))
            V2Switch(checked, onToggle)
        }
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.tx,
            modifier = Modifier.padding(top = 12.dp))
        Text(subtitle, fontSize = 10.5.sp, color = t.tx3, lineHeight = 14.sp,
            modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun V2Switch(checked: Boolean, onToggle: (Boolean) -> Unit) {
    val t = LocalV2.current
    Switch(
        checked = checked, onCheckedChange = onToggle,
        colors = SwitchDefaults.colors(
            checkedTrackColor = t.ink, checkedThumbColor = t.onInk,
            uncheckedTrackColor = t.chipBg, uncheckedThumbColor = t.tx3,
            uncheckedBorderColor = t.hair),
        modifier = Modifier.height(26.dp))
}

@Composable
private fun ChipButton(label: String, onClick: () -> Unit) {
    val t = LocalV2.current
    Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = t.tx2,
        modifier = Modifier
            .background(t.chipBg, RoundedCornerShape(100))
            .border(1.dp, t.hair, RoundedCornerShape(100))
            .pressable(onClick)
            .padding(horizontal = 13.dp, vertical = 5.dp))
}

@Composable
private fun SectionCaps(text: String, modifier: Modifier = Modifier) {
    val t = LocalV2.current
    Text(text.uppercase(), fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 0.7.sp, color = t.tx3, modifier = modifier)
}

// ═══ Dock ════════════════════════════════════════════════════════════════════

@Composable
private fun DockBar(tab: Tab, onSelect: (Tab) -> Unit) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth().padding(start = 60.dp, end = 60.dp, top = 6.dp, bottom = 12.dp)
            .navigationBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth().glass(t, 100.dp, prominent = true).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Tab.entries.forEach { item ->
                val selected = item == tab
                Text(item.name,
                    fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selected) t.onInk else t.tx2, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                        .background(if (selected) t.ink else Color.Transparent, RoundedCornerShape(100))
                        .pressable { onSelect(item) }
                        .padding(vertical = 9.dp))
            }
        }
    }
}

// ═══ Sheets ══════════════════════════════════════════════════════════════════

@Composable
private fun SheetScaffold(
    visible: Boolean,
    dismissable: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val t = LocalV2.current
    AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(150))) {
        Box(
            Modifier.fillMaxSize().background(t.scrim)
                .pressable { if (dismissable) onDismiss() }
        )
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(150)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.92f),
                verticalArrangement = Arrangement.Bottom
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .glass(t, 28.dp, prominent = true)
                        // consume taps so the scrim behind doesn't get them
                        .pressable { }
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 22.dp)
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    Box(Modifier.width(36.dp).height(4.dp)
                        .background(t.hair, RoundedCornerShape(2.dp))
                        .align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(12.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun ShizukuSetupSheet(
    status: ShizukuClipboardBridge.Status,
    installed: Boolean,
    onDismiss: () -> Unit,
    onAction: () -> Unit,
) {
    val t = LocalV2.current
    val title = when (status) {
        ShizukuClipboardBridge.Status.NOT_RUNNING ->
            if (installed) "Start Shizuku" else "Install Shizuku"
        ShizukuClipboardBridge.Status.PERMISSION_REQUIRED -> "Allow Shizuku access"
        ShizukuClipboardBridge.Status.PERMISSION_BLOCKED -> "Shizuku access was denied"
        ShizukuClipboardBridge.Status.UNSUPPORTED -> "Update Shizuku"
        ShizukuClipboardBridge.Status.READY -> "Shizuku is ready"
    }
    val body = when (status) {
        ShizukuClipboardBridge.Status.NOT_RUNNING ->
            if (installed)
                "Open Shizuku, start its service, then return to Wigly Woo."
            else
                "Wigly Woo needs Shizuku for reliable background clipboard sync."
        ShizukuClipboardBridge.Status.PERMISSION_REQUIRED ->
            "Shizuku is running. Allow Wigly Woo access to enable background clipboard sync."
        ShizukuClipboardBridge.Status.PERMISSION_BLOCKED ->
            "Access was denied. Ask Shizuku again so Wigly Woo can sync the clipboard."
        ShizukuClipboardBridge.Status.UNSUPPORTED ->
            "This Shizuku version is too old. Update it before enabling clipboard sync."
        ShizukuClipboardBridge.Status.READY ->
            "Background clipboard sync is available."
    }
    val action = when (status) {
        ShizukuClipboardBridge.Status.PERMISSION_REQUIRED,
        ShizukuClipboardBridge.Status.PERMISSION_BLOCKED -> "Allow access"
        ShizukuClipboardBridge.Status.NOT_RUNNING -> if (installed) "Open Shizuku" else "Get Shizuku"
        else -> "Open Shizuku"
    }

    Column {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = t.tx)
        Text(body, fontSize = 12.5.sp, color = t.tx2, lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Not now", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.tx2,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .background(t.chipBg, RoundedCornerShape(100))
                    .border(1.dp, t.hair, RoundedCornerShape(100))
                    .pressable(onDismiss)
                    .padding(vertical = 12.dp))
            Text(action, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.onInk,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .background(t.ink, RoundedCornerShape(100))
                    .pressable(onAction)
                    .padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun IncomingDropSheet(trust: Trust, onAccept: () -> Unit, onDecline: () -> Unit) {
    val t = LocalV2.current
    var showFp by remember { mutableStateOf(false) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) { DashedCircleGlyph() }
            Column {
                Text("Incoming drop", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.3).sp, color = t.tx)
                Text("from ${trust.name}", fontSize = 12.sp, color = t.tx2, modifier = Modifier.padding(top = 1.dp))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp)
                .background(t.inset, RoundedCornerShape(14.dp))
                .border(1.dp, t.hair, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(extOf(trust.file), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                color = t.tx3, modifier = Modifier.background(t.chipBg, RoundedCornerShape(100))
                    .padding(horizontal = 8.dp, vertical = 3.dp))
            Text(trust.file, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.tx,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }

        Text(
            if (showFp) "Sender: ${trust.fingerprint.take(19).uppercase()}" else "Verify sender fingerprint",
            fontSize = 11.sp, color = t.tx3,
            modifier = Modifier.padding(top = 10.dp).pressable { showFp = !showFp })

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Decline", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.tx2,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .background(t.chipBg, RoundedCornerShape(100))
                    .border(1.dp, t.hair, RoundedCornerShape(100))
                    .pressable(onDecline)
                    .padding(vertical = 11.dp))
            Text("Accept", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.onInk,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .background(t.ink, RoundedCornerShape(100))
                    .pressable(onAccept)
                    .padding(vertical = 11.dp))
        }
    }
}

@Composable
private fun LinkSetupSheet(
    initial: CompanionConfig,
    notifAccess: Boolean,
    kbEnabled: Boolean,
    onGrantNotif: () -> Unit,
    onEnableKb: () -> Unit,
    onPickKb: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (CompanionConfig) -> Unit,
) {
    val t = LocalV2.current
    var url by remember(initial) { mutableStateOf(initial.supabaseUrl) }
    var key by remember(initial) { mutableStateOf(initial.publishableKey) }
    var secret by remember(initial) { mutableStateOf(initial.pairingSecret) }
    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    var notifications by remember(initial) { mutableStateOf(initial.notificationsEnabled) }
    var clipboard by remember(initial) { mutableStateOf(initial.clipboardEnabled) }
    val candidate = CompanionConfig(url, key, secret, enabled, notifications, clipboard).normalized()
    val saveDisabled = enabled && !candidate.isComplete

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(46.dp).background(t.accentSoft, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("↔", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = t.accent)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Connect your Mac",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.3).sp,
                    color = t.tx
                )
                Text(
                    "Use the same relay details and pairing secret on both devices.",
                    fontSize = 11.5.sp,
                    color = t.tx2,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }

        V3SetupStep(
            number = "1",
            title = "Relay details",
            detail = "Your Supabase project carries encrypted companion messages."
        ) {
            V3LabeledField(
                label = "Project URL",
                value = url,
                onChange = { url = it },
                placeholder = "https://your-project.supabase.co"
            )
            Spacer(Modifier.height(9.dp))
            V3LabeledField(
                label = "Publishable key",
                value = key,
                onChange = { key = it },
                placeholder = "Supabase publishable / anon key"
            )
        }

        V3SetupStep(
            number = "2",
            title = "Pairing secret",
            detail = "This is the private password for your devices. Keep it out of screenshots and chat."
        ) {
            V3LabeledField(
                label = "Shared secret",
                value = secret,
                onChange = { secret = it.trim() },
                placeholder = "At least 20 characters"
            )
            Text(
                "Generate a new secret",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = t.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .pressable { secret = CompanionConfig.generateSecret() }
                    .padding(vertical = 16.dp)
            )
        }

        V3SetupStep(
            number = "3",
            title = "Choose what stays connected",
            detail = "You can change these later from the Companion screen."
        ) {
            Column(
                Modifier.fillMaxWidth().background(t.inset, RoundedCornerShape(14.dp))
                    .border(1.dp, t.hair, RoundedCornerShape(14.dp))
            ) {
                V3SetupToggle(
                    title = "Keep companion connected",
                    detail = "Maintain the encrypted link in the background",
                    checked = enabled,
                    onToggle = { enabled = it }
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(t.hair))
                V3SetupToggle(
                    title = "Android notifications",
                    detail = "Show phone notifications on your Mac",
                    checked = notifications,
                    onToggle = { notifications = it }
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(t.hair))
                V3SetupToggle(
                    title = "Clipboard sync",
                    detail = "Keep copied text available on both devices",
                    checked = clipboard,
                    onToggle = { clipboard = it }
                )
            }
        }

        V3SetupStep(
            number = "4",
            title = "Finish Android setup",
            detail = "These system permissions make the companion features work."
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                V3PermissionRow(
                    title = "Notification access",
                    ready = notifAccess,
                    action = if (notifAccess) "Granted" else "Allow",
                    onClick = onGrantNotif
                )
                V3PermissionRow(
                    title = "Wigly Woo keyboard",
                    ready = kbEnabled,
                    action = if (kbEnabled) "Enabled" else "Enable",
                    onClick = onEnableKb
                )
                V3PermissionRow(
                    title = "Active keyboard",
                    ready = false,
                    action = "Select",
                    onClick = onPickKb
                )
            }
        }

        if (saveDisabled) {
            Text("Finish the relay URL, publishable key, and pairing secret before turning the companion on.",
                fontSize = 11.5.sp, color = t.tx2, lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    .background(t.warnBg, RoundedCornerShape(13.dp))
                    .border(1.dp, t.warnLine, RoundedCornerShape(13.dp))
                    .padding(horizontal = 13.dp, vertical = 10.dp))
        }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Cancel", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = t.tx2,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(0.7f).heightIn(min = 52.dp)
                    .background(t.inset, RoundedCornerShape(13.dp))
                    .border(1.dp, t.hair, RoundedCornerShape(13.dp))
                    .pressable(onDismiss)
                    .padding(vertical = 17.dp))
            Text("Save connection", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = if (saveDisabled) t.tx3 else Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1.3f).heightIn(min = 52.dp)
                    .background(if (saveDisabled) t.chipBg else t.accent, RoundedCornerShape(13.dp))
                    .pressable { if (!saveDisabled) onSave(candidate) }
                    .padding(vertical = 17.dp))
        }
    }
}

@Composable
private fun V3SetupStep(
    number: String,
    title: String,
    detail: String,
    content: @Composable () -> Unit,
) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(
            Modifier.size(28.dp).background(t.accentSoft, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(number, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = t.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = t.tx)
            Text(detail, fontSize = 10.5.sp, lineHeight = 15.sp, color = t.tx3,
                modifier = Modifier.padding(top = 2.dp, bottom = 9.dp))
            content()
        }
    }
}

@Composable
private fun V3LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
) {
    val t = LocalV2.current
    Column {
        Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = t.tx2,
            modifier = Modifier.padding(bottom = 5.dp))
        V2Field(value, onChange, placeholder)
    }
}

@Composable
private fun V3SetupToggle(
    title: String,
    detail: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = t.tx)
            Text(detail, fontSize = 10.sp, color = t.tx3, modifier = Modifier.padding(top = 2.dp))
        }
        V2Switch(checked, onToggle)
    }
}

@Composable
private fun V3PermissionRow(
    title: String,
    ready: Boolean,
    action: String,
    onClick: () -> Unit,
) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .background(t.inset, RoundedCornerShape(13.dp))
            .border(1.dp, t.hair, RoundedCornerShape(13.dp))
            .pressable(onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(if (ready) t.ok else t.warn, CircleShape))
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.tx,
            modifier = Modifier.weight(1f).padding(start = 9.dp))
        Text(action, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
            color = if (ready) t.ok else t.accent)
    }
}

@Composable
private fun V2Field(value: String, onChange: (String) -> Unit, placeholder: String) {
    val t = LocalV2.current
    OutlinedTextField(
        value = value, onValueChange = onChange,
        placeholder = { Text(placeholder, fontSize = 12.5.sp, color = t.tx.copy(alpha = 0.38f)) },
        singleLine = true,
        shape = RoundedCornerShape(13.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, color = t.tx),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = t.inset, unfocusedContainerColor = t.inset,
            focusedBorderColor = t.tx3, unfocusedBorderColor = t.hair,
            cursorColor = t.tx),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun BehaviorRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val t = LocalV2.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.tx, modifier = Modifier.weight(1f))
        V2Switch(checked, onToggle)
    }
}

@Composable
private fun PermButton(label: String, onClick: () -> Unit) {
    val t = LocalV2.current
    Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = t.tx,
        modifier = Modifier.fillMaxWidth()
            .background(t.chipBg, RoundedCornerShape(13.dp))
            .border(1.dp, t.hair, RoundedCornerShape(13.dp))
            .pressable(onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp))
}

// ═══ Glyphs (stroke icons matching the v2 design) ════════════════════════════

@Composable
private fun LaptopGlyph(color: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val sw = w * 0.0625f
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.167f, h * 0.25f),
            size = androidx.compose.ui.geometry.Size(w * 0.667f, h * 0.4375f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.067f),
            style = Stroke(width = sw))
        drawLine(color, Offset(w * 0.104f, h * 0.792f), Offset(w * 0.896f, h * 0.792f),
            strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun PhoneGlyph(color: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val sw = w * 0.073f
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.292f, h * 0.125f),
            size = androidx.compose.ui.geometry.Size(w * 0.417f, h * 0.75f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f),
            style = Stroke(width = sw))
        drawLine(color, Offset(w * 0.4375f, h * 0.767f), Offset(w * 0.5625f, h * 0.767f),
            strokeWidth = sw, cap = StrokeCap.Round)
    }
}

// ═══ Intents / helpers (unchanged behavior) ══════════════════════════════════

private fun notificationAccessGranted(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun shizukuInstalled(context: Context): Boolean =
    context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE) != null

private fun openShizuku(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE)
    val intent = launch ?: Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://shizuku.rikka.app/download/")
    )
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "Unable to open Shizuku", Toast.LENGTH_SHORT).show()
    }
}

private fun shizukuWarning(status: ShizukuClipboardBridge.Status): String = when (status) {
    ShizukuClipboardBridge.Status.NOT_RUNNING -> "Shizuku isn't running — background clipboard sync is unavailable."
    ShizukuClipboardBridge.Status.PERMISSION_REQUIRED -> "Shizuku is running — allow Wigly Woo access."
    ShizukuClipboardBridge.Status.PERMISSION_BLOCKED -> "Shizuku access was denied — tap to ask again."
    ShizukuClipboardBridge.Status.UNSUPPORTED -> "This Shizuku version is unsupported."
    ShizukuClipboardBridge.Status.READY -> ""
}

// Settings.Secure.ENABLED_INPUT_METHODS throws SecurityException on targetSdk >= 34;
// the InputMethodManager list is the public equivalent.
private fun wiglyKeyboardEnabled(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
        .enabledInputMethodList.any { it.packageName == context.packageName }
}.getOrDefault(false)

private fun extOf(name: String): String {
    val e = name.substringAfterLast('.', "").uppercase(Locale.US)
    return if (e.isEmpty()) "FILE" else e.take(4)
}

private fun openFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "No app can open this file type", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "Can't share this file", Toast.LENGTH_SHORT).show()
    }
}

private fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}

private fun fmtDate(ms: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(ms)

// --- speed tracking ---------------------------------------------------------

private class SpeedTracker {
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

// --- formatting -------------------------------------------------------------

private fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = -1
    do { v /= 1024; i++ } while (v >= 1024 && i < units.size - 1)
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

private fun fmtSpeed(bytesPerSec: Double): String {
    if (bytesPerSec <= 0) return "—"
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var v = bytesPerSec; var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

private data class SendSource(val fd: Int, val name: String, val size: Long)

// Open the picked content as a raw fd + metadata, with no copy. The fd is
// detached so native owns it (the Go core closes it after streaming).
private fun openForSend(context: Context, uri: Uri): SendSource? = runCatching {
    var name = "file"
    var size = -1L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
        }
    }
    val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
    if (size < 0) size = pfd.statSize          // read before detaching
    val fd = pfd.detachFd()                     // ownership -> native
    SendSource(fd, name, size)
}.getOrNull()
