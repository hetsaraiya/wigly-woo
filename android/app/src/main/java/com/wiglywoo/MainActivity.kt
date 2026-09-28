package com.wiglywoo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.File
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
                WooState.initialize(this)
                val rc = CoreBridge.start(name = CompanionConfig.deviceName(this), saveDir = saveDir.absolutePath)
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

    override fun onResume() {
        super.onResume()
        WooState.foreground = true
    }

    override fun onPause() {
        WooState.foreground = false
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        CoreBridge.listener = null
        CoreBridge.stop()
        multicastLock?.let { if (it.isHeld) it.release() }
    }
}

// ═══ Root ════════════════════════════════════════════════════════════════════

@Composable
fun WiglyWooApp(startupError: String? = null) {
    val dark = isSystemInDarkTheme()
    val t = if (dark) DarkWW else LightWW
    val scheme = if (dark)
        darkColorScheme(primary = t.accent, background = t.bg, surface = t.surface, onSurface = t.text, onBackground = t.text)
    else
        lightColorScheme(primary = t.accent, background = t.bg, surface = t.surface, onSurface = t.text, onBackground = t.text)

    CompositionLocalProvider(LocalWW provides t) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(LocalTextStyle provides baseTextStyle(t)) {
                Box(Modifier.fillMaxSize().background(t.bg)) {
                    if (startupError != null) ErrorScreen(startupError) else MainScreen()
                }
            }
        }
    }
}

@Composable
private fun ErrorScreen(message: String) {
    val context = LocalContext.current
    var showDetails by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        H1("Wigly Woo couldn’t start")
        Muted("Close and reopen the app. If this keeps happening, copy the technical details when reporting the problem.", 15.sp)
        if (showDetails) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Muted(message, 12.sp)
            }
        } else Spacer(Modifier.weight(1f))
        WWButton(if (showDetails) "Hide details" else "Show details", Kind.Secondary, Modifier.fillMaxWidth()) {
            showDetails = !showDetails
        }
        WWButton("Copy details", Kind.Primary, Modifier.fillMaxWidth()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Wigly Woo error", message))
            Toast.makeText(context, "Error details copied", Toast.LENGTH_SHORT).show()
        }
    }
}

private enum class Screen { Onboard, Confirm, Send, Inbox, Companion }
private enum class Sheet { Checklist, Manual }

private data class Perm(val label: String, val sub: String, val done: Boolean, val cta: String, val act: () -> Unit)

private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"

private fun uiPrefs(context: Context) = context.getSharedPreferences("wigly_ui", Context.MODE_PRIVATE)

/** Saves a companion config and starts or stops the persistent service to match. */
private fun applyCompanion(context: Context, config: CompanionConfig) {
    CompanionManager.applyConfig(config)
    if (config.enabled && config.isComplete) {
        ContextCompat.startForegroundService(context, Intent(context, CompanionForegroundService::class.java))
    } else {
        context.stopService(Intent(context, CompanionForegroundService::class.java))
    }
}

private fun dotFor(state: SupabaseRealtimeClient.State, config: CompanionConfig): Dot = when {
    !config.isComplete || !config.enabled -> Dot.Idle
    state == SupabaseRealtimeClient.State.CONNECTED -> Dot.Connected
    state == SupabaseRealtimeClient.State.CONNECTING -> Dot.Connecting
    state == SupabaseRealtimeClient.State.ERROR -> Dot.Error
    else -> Dot.Idle
}

@Composable
private fun MainScreen() {
    val t = LocalWW.current
    val context = LocalContext.current

    var screen by remember {
        mutableStateOf(if (uiPrefs(context).getBoolean("onboarded", false)) Screen.Send else Screen.Onboard)
    }
    var obStep by remember { mutableIntStateOf(0) }
    var pendingPair by remember { mutableStateOf<CompanionConfig?>(null) }
    var pairReturn by remember { mutableStateOf(Screen.Companion) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var companionState by remember { mutableStateOf(CompanionManager.state) }
    var config by remember { mutableStateOf(CompanionConfig.load(context)) }
    var connectedSince by remember { mutableStateOf<Long?>(null) }
    var target by remember { mutableStateOf<PeerRow?>(null) }

    // Re-checked whenever the app comes back to the foreground (permission screens etc.)
    var permTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ShizukuClipboardBridge.refresh()
                WooState.refreshReceived()
                config = CompanionConfig.load(context)
                permTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) {
        val listener: (SupabaseRealtimeClient.State) -> Unit = {
            companionState = it
            config = CompanionConfig.load(context)
            connectedSince = if (it == SupabaseRealtimeClient.State.CONNECTED) connectedSince ?: System.currentTimeMillis() else null
        }
        CompanionManager.addStateListener(listener)
        onDispose { CompanionManager.removeStateListener(listener) }
    }

    val notifAccess = remember(permTick) { notificationAccessGranted(context) }
    val postNotif = remember(permTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val kbEnabled = remember(permTick) { wiglyKeyboardEnabled(context) }
    val shizukuStatus = remember(permTick) { ShizukuClipboardBridge.status() }
    val shizukuInstalled = remember(permTick) { shizukuInstalled(context) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val peer = target ?: return@rememberLauncherForActivityResult
        if (uris.isNotEmpty()) WooState.enqueue(uris, peer)
    }

    fun update(transform: (CompanionConfig) -> CompanionConfig) {
        val next = transform(CompanionConfig.load(context))
        config = next
        applyCompanion(context, next)
    }

    fun scan(returnTo: Screen) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code ->
                val parsed = CompanionConfig.fromPairingUrl(code.rawValue.orEmpty(), CompanionConfig.load(context))
                if (parsed == null) {
                    WooState.flash("That isn’t a Wigly Woo pairing code")
                } else {
                    pendingPair = parsed
                    pairReturn = returnTo
                    screen = Screen.Confirm
                }
            }
            .addOnFailureListener { WooState.flash("Couldn’t open the scanner") }
    }

    fun sendClipboard() {
        val sent = CompanionManager.sendCurrentClipboard()
        WooState.flash(if (sent) "Sent to Mac clipboard" else "Queued. Delivers when your Mac is back")
    }

    val perms = listOf(
        Perm("Notification access", "Mirror notifications on your Mac", notifAccess, "Allow") {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        Perm("Show notifications", "For the always-on companion notice", postNotif, "Allow") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        Perm("Wigly keyboard", "Lets your Mac type into apps", kbEnabled, "Enable") {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        },
        shizukuPerm(context, shizukuStatus, shizukuInstalled),
    )
    val pending = perms.count { !it.done }

    BackHandler(enabled = sheet != null) { sheet = null }
    BackHandler(enabled = sheet == null && screen == Screen.Confirm) { screen = pairReturn }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            when (screen) {
                Screen.Onboard -> Onboarding(
                    step = obStep,
                    perms = perms,
                    pending = pending,
                    onNext = { obStep++ },
                    onScan = { scan(Screen.Onboard) },
                    onFinish = {
                        uiPrefs(context).edit().putBoolean("onboarded", true).apply()
                        screen = Screen.Send
                    }
                )
                Screen.Confirm -> ConfirmPairing(
                    config = pendingPair,
                    onCancel = { pendingPair = null; screen = pairReturn },
                    onPair = {
                        val cfg = pendingPair ?: return@ConfirmPairing
                        config = cfg
                        applyCompanion(context, cfg)
                        WooState.flash("Paired with ${cfg.peerName.ifEmpty { "your Mac" }}")
                        pendingPair = null
                        if (pairReturn == Screen.Onboard) obStep = 2
                        screen = pairReturn
                    }
                )
                else -> {
                    TopBar(companionState, config) { screen = Screen.Companion }
                    Box(Modifier.weight(1f)) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(start = 20.dp, end = 20.dp, top = 15.dp, bottom = 30.dp)
                        ) {
                            when (screen) {
                                Screen.Send -> SendScreen(
                                    connected = companionState == SupabaseRealtimeClient.State.CONNECTED,
                                    onChoose = { peer -> target = peer; picker.launch("*/*") },
                                    onOpen = { openFile(context, it) },
                                    onSendClipboard = ::sendClipboard,
                                )
                                Screen.Inbox -> InboxScreen(
                                    onOpen = { openFile(context, it) },
                                    onShare = { shareFile(context, it) },
                                )
                                else -> CompanionScreen(
                                    state = companionState,
                                    config = config,
                                    connectedSince = connectedSince,
                                    pending = pending,
                                    kbEnabled = kbEnabled,
                                    notifAccess = notifAccess,
                                    shizukuReady = shizukuStatus == ShizukuClipboardBridge.Status.READY,
                                    onScan = { scan(Screen.Companion) },
                                    onManual = { sheet = Sheet.Manual },
                                    onChecklist = { sheet = Sheet.Checklist },
                                    onResume = { update { it.copy(enabled = true) } },
                                    onPause = { update { it.copy(enabled = false) } },
                                    onUnpair = {
                                        update { it.copy(pairingSecret = "", peerName = "", enabled = false) }
                                        WooState.flash("Unpaired")
                                    },
                                    onToggleNotifications = { on -> update { it.copy(notificationsEnabled = on) } },
                                    onToggleClipboard = { on -> update { it.copy(clipboardEnabled = on) } },
                                    onKeyboard = {
                                        if (!kbEnabled) context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                        else (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                            .showInputMethodPicker()
                                    },
                                    onSendClipboard = ::sendClipboard,
                                )
                            }
                        }
                    }
                    BottomNav(screen) { screen = it }
                }
            }
        }

        // ── sheets ──
        val incoming = WooState.trust
        SheetHost(visible = incoming != null, onDismiss = null) {
            if (incoming != null) IncomingSheet(incoming)
        }
        SheetHost(visible = incoming == null && sheet == Sheet.Checklist, onDismiss = { sheet = null }) {
            ChecklistSheet(perms) { sheet = null }
        }
        SheetHost(visible = incoming == null && sheet == Sheet.Manual, onDismiss = { sheet = null }) {
            ManualSheet(
                initial = CompanionConfig.load(context),
                onCancel = { sheet = null },
                onSave = { saved ->
                    val cfg = saved.copy(enabled = true)
                    config = cfg
                    applyCompanion(context, cfg)
                    sheet = null
                    WooState.flash("Companion saved")
                }
            )
        }

        // ── toast ──
        val toast = WooState.toast
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 84.dp)
        ) {
            T(toast.orEmpty(), 14.sp, color = t.bg,
                modifier = Modifier.fillMaxWidth().background(t.text, RoundedCornerShape(2.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}

private fun shizukuPerm(context: Context, status: ShizukuClipboardBridge.Status, installed: Boolean): Perm {
    val open = { openShizuku(context) }
    val ask = { if (!ShizukuClipboardBridge.requestAccess()) openShizuku(context) }
    return when (status) {
        ShizukuClipboardBridge.Status.READY -> Perm("Shizuku", "Background clipboard is ready", true, "", {})
        ShizukuClipboardBridge.Status.NOT_RUNNING ->
            if (installed) Perm("Shizuku", "Start the Shizuku service, for background clipboard", false, "Open Shizuku", open)
            else Perm("Shizuku", "Install Shizuku, for background clipboard", false, "Get Shizuku", open)
        ShizukuClipboardBridge.Status.PERMISSION_REQUIRED,
        ShizukuClipboardBridge.Status.PERMISSION_BLOCKED ->
            Perm("Shizuku", "Allow Wigly Woo access, for background clipboard", false, "Allow", ask)
        ShizukuClipboardBridge.Status.UNSUPPORTED ->
            Perm("Shizuku", "Update Shizuku, for background clipboard", false, "Open Shizuku", open)
    }
}

// ═══ Onboarding + pairing ════════════════════════════════════════════════════

@Composable
private fun ColumnScope.Onboarding(
    step: Int,
    perms: List<Perm>,
    pending: Int,
    onNext: () -> Unit,
    onScan: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp)
    ) {
        Muted("Step ${step + 1} of 3")
        when (step) {
            0 -> {
                H1("Your phone and your Mac, linked.")
                Column { Kicker("Nearby sharing", Modifier.padding(bottom = 4.dp)); T("Send files over Wi‑Fi. Nothing to set up.") }
                Column { Kicker("Companion", Modifier.padding(bottom = 4.dp)); T("Notifications, clipboard and typing over any network. Pair once.") }
            }
            1 -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                H1("Pair with your Mac")
                Muted("On the Mac, open Wigly Woo and choose Pair phone. Then scan the code.", 15.sp)
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
                H1("Allow access")
                Muted("Android asks for these before the companion can work. Change them any time.", 15.sp)
                PermList(perms)
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (step) {
            0 -> WWButton("Continue", Kind.Primary, Modifier.fillMaxWidth(), onClick = onNext)
            1 -> {
                WWButton("Scan code", Kind.Primary, Modifier.fillMaxWidth(), icon = R.drawable.ic_ph_qr_code, onClick = onScan)
                WWButton("Later", Kind.Secondary, Modifier.fillMaxWidth(), onClick = onNext)
            }
            else -> WWButton(if (pending > 0) "Skip for now" else "Start", Kind.Primary, Modifier.fillMaxWidth(), onClick = onFinish)
        }
    }
}

@Composable
private fun ColumnScope.ConfirmPairing(config: CompanionConfig?, onCancel: () -> Unit, onPair: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Kicker("Found ${config?.peerName?.ifEmpty { null } ?: "your Mac"}")
        H1("Confirm pairing")
        Muted("Check the same number is on your Mac.", 15.sp)
        T(config?.pairingCode.orEmpty(), 48.sp, semibold = true, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WWButton("Cancel", Kind.Secondary, Modifier.weight(1f), onClick = onCancel)
            WWButton("Pair", Kind.Primary, Modifier.weight(1f), onClick = onPair)
        }
    }
}

@Composable
private fun PermList(perms: List<Perm>) {
    Column {
        perms.forEach { p ->
            RuleRow(p.label, p.sub) {
                if (p.done) Tag("Done")
                else WWButton(p.cta, Kind.Secondary, minHeight = 44.dp, onClick = p.act)
            }
        }
    }
}

// ═══ Chrome ══════════════════════════════════════════════════════════════════

@Composable
private fun TopBar(state: SupabaseRealtimeClient.State, config: CompanionConfig, onTap: () -> Unit) {
    val t = LocalWW.current
    val chip = when {
        !config.isComplete -> "Not set up"
        !config.enabled -> "Paused"
        state == SupabaseRealtimeClient.State.CONNECTED -> "Mac connected"
        state == SupabaseRealtimeClient.State.CONNECTING -> "Connecting…"
        state == SupabaseRealtimeClient.State.ERROR -> "Reconnecting"
        else -> "Offline"
    }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        T("wigly woo", 19.sp, semibold = true, letterSpacing = (-0.4).sp)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.heightIn(min = 40.dp).background(t.surface, RoundedCornerShape(2.dp))
                .tap(onClick = onTap).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusDot(dotFor(state, config))
            T(chip, 13.sp)
        }
    }
}

@Composable
private fun BottomNav(screen: Screen, onSelect: (Screen) -> Unit) {
    val t = LocalWW.current
    Row(Modifier.fillMaxWidth().height(72.dp).padding(bottom = 8.dp)) {
        listOf(
            Triple(Screen.Send, "Send", R.drawable.ic_ph_paper_plane_tilt),
            Triple(Screen.Inbox, "Inbox", R.drawable.ic_ph_tray),
            Triple(Screen.Companion, "Companion", R.drawable.ic_ph_link_simple),
        ).forEach { (id, label, icon) ->
            val selected = id == screen
            val color = if (selected) t.link else t.text
            Column(
                Modifier.weight(1f).fillMaxHeight().tap { onSelect(id) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
            ) {
                PhIcon(icon, 22.dp, color)
                T(label, 12.sp, semibold = selected, color = color)
            }
        }
    }
}

// ═══ Send ════════════════════════════════════════════════════════════════════

@Composable
private fun SendScreen(
    connected: Boolean,
    onChoose: (PeerRow) -> Unit,
    onOpen: (File) -> Unit,
    onSendClipboard: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(30.dp)) {
        Column {
            H1("Send")
            Muted("Files go straight to your Mac over Wi‑Fi.", 14.sp, Modifier.padding(top = 4.dp))
        }

        if (WooState.active != null || WooState.queue.isNotEmpty()) TransferBlock()

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val peers = WooState.peers
            Kicker(if (peers.isEmpty()) "Nearby" else "Nearby · ${peers.size}")
            if (peers.isEmpty()) Searching()
            peers.forEach { peer -> DeviceCard(peer, onChoose = { onChoose(peer) }) }
        }

        val recent = WooState.received.take(3)
        if (recent.isNotEmpty()) Column {
            Kicker("Recent")
            recent.forEach { f ->
                RuleRow(f.name, "${fmtBytes(f.length())} · ${fmtWhen(f.lastModified())}", minHeight = 56.dp) {
                    WWButton("Open", Kind.Ghost, minHeight = 44.dp) { onOpen(f) }
                }
            }
        }

        WWButton("Send clipboard to Mac", Kind.Secondary, Modifier.fillMaxWidth(),
            icon = R.drawable.ic_ph_clipboard_text, enabled = connected, onClick = onSendClipboard)
    }
}

@Composable
private fun TransferBlock() {
    val a = WooState.active
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (a == null) {
            Kicker("Starting")
        } else {
            val peer = a.peer.ifEmpty { "Mac" }
            val fraction = if (a.total > 0) a.sent.toFloat() / a.total else 0f
            Kicker(if (a.dir == "send") "Sending to $peer" else "Receiving from $peer")
            Row(verticalAlignment = Alignment.Bottom) {
                T(a.name, semibold = true, maxLines = 1, modifier = Modifier.weight(1f))
                T("${(fraction * 100).toInt()}%", 14.sp, modifier = Modifier.padding(start = 10.dp))
            }
            Box(Modifier.padding(top = 4.dp)) { ProgressLine(fraction) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Muted("${fmtBytes(a.sent)} of ${fmtBytes(a.total)} · ${fmtSpeed(a.speed)}", 12.sp, Modifier.weight(1f))
                WWButton("Cancel", Kind.Ghost, minHeight = 44.dp, onClick = WooState::cancel)
            }
        }
        if (WooState.queue.isNotEmpty()) {
            Muted("Up next: ${WooState.queue.joinToString(", ") { it.name }}", maxLines = 2)
        }
    }
}

@Composable
private fun Searching() {
    val t = LocalWW.current
    val pulse by rememberInfiniteTransition(label = "search").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2000)), label = "pulse")
    Row(
        Modifier.padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(64.dp).border(1.dp, t.divider, CircleShape))
            Box(Modifier.size(36.dp).border(1.dp, t.divider, CircleShape))
            Box(Modifier.size((8 + 56 * pulse).dp).alpha(1 - pulse).border(1.dp, t.accent, CircleShape))
            Box(Modifier.size(8.dp).background(t.accent, CircleShape))
        }
        Column {
            T("Looking for your Mac", semibold = true)
            Muted("Open Wigly Woo on it, on the same Wi‑Fi.")
        }
    }
}

@Composable
private fun DeviceCard(peer: PeerRow, onChoose: () -> Unit) {
    val t = LocalWW.current
    val trusted = WooState.trusted.containsKey(peer.fingerprint)
    Row(
        Modifier.fillMaxWidth().background(t.surface, RoundedCornerShape(2.dp)).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        PhIcon(R.drawable.ic_ph_laptop, 28.dp, t.accent)
        Column(Modifier.weight(1f)) {
            T(peer.name, 17.sp, semibold = true, maxLines = 1)
            Muted(if (trusted) "Mac · Trusted" else "Mac · on Wi‑Fi")
        }
        WWButton("Choose files", Kind.Primary, onClick = onChoose)
    }
}

// ═══ Inbox ═══════════════════════════════════════════════════════════════════

@Composable
private fun InboxScreen(onOpen: (File) -> Unit, onShare: (File) -> Unit) {
    var showSent by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column {
            H1("Inbox")
            Muted("${WooState.received.size} received · ${fmtBytes(WooState.sessionTotal)} this session", 14.sp,
                Modifier.padding(top = 4.dp))
        }
        Segmented(listOf("Received", "Sent"), if (showSent) 1 else 0) { showSent = it == 1 }
        Column {
            if (showSent) {
                if (WooState.sent.isEmpty()) Muted("Nothing sent yet. Choose files on the Send tab.", 15.sp)
                WooState.sent.forEach { r ->
                    RuleRow(r.name, "to ${r.peer} · ${fmtBytes(r.size)} · ${fmtWhen(r.time)}")
                }
            } else {
                if (WooState.received.isEmpty()) Muted("Nothing received yet. Files your Mac sends land here.", 15.sp)
                WooState.received.forEach { f ->
                    val from = WooState.receivedFrom[f.name]?.let { "from $it · " }.orEmpty()
                    RuleRow(f.name, "$from${fmtBytes(f.length())} · ${fmtWhen(f.lastModified())}") {
                        Row {
                            WWButton("Share", Kind.Ghost, minHeight = 44.dp) { onShare(f) }
                            WWButton("Open", Kind.Ghost, minHeight = 44.dp) { onOpen(f) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val t = LocalWW.current
    Row(Modifier.border(1.dp, t.divider, RoundedCornerShape(2.dp)).height(IntrinsicSize.Min)) {
        options.forEachIndexed { i, label ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(t.divider))
            T(label, 13.sp, color = if (i == selected) t.bg else t.text,
                modifier = Modifier.heightIn(min = 40.dp)
                    .background(if (i == selected) t.accent else Color.Transparent)
                    .tap { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}

// ═══ Companion ═══════════════════════════════════════════════════════════════

@Composable
private fun CompanionScreen(
    state: SupabaseRealtimeClient.State,
    config: CompanionConfig,
    connectedSince: Long?,
    pending: Int,
    kbEnabled: Boolean,
    notifAccess: Boolean,
    shizukuReady: Boolean,
    onScan: () -> Unit,
    onManual: () -> Unit,
    onChecklist: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onUnpair: () -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onToggleClipboard: (Boolean) -> Unit,
    onKeyboard: () -> Unit,
    onSendClipboard: () -> Unit,
) {
    val t = LocalWW.current
    val connected = config.isComplete && config.enabled && state == SupabaseRealtimeClient.State.CONNECTED
    val mac = config.macName.replaceFirstChar { it.uppercase() }

    data class Hero(val title: String, val detail: String, val cta: String?, val act: (() -> Unit)?)
    val hero = when {
        !config.isComplete -> Hero("Link your Mac",
            "Scan one code to sync notifications, clipboard and typing. Nearby sharing works without it.", "Scan code", onScan)
        !config.enabled -> Hero("Companion paused", "Your pairing is saved.", "Resume", onResume)
        state == SupabaseRealtimeClient.State.CONNECTED -> Hero("$mac is connected",
            "Encrypted relay" + (connectedSince?.let { ", since ${fmtClock(it)}" } ?: "") + ".",
            null, null)
        state == SupabaseRealtimeClient.State.CONNECTING -> Hero("Connecting to ${config.macName}…", "This usually takes a moment.", null, null)
        state == SupabaseRealtimeClient.State.ERROR -> Hero("Reconnecting", "Retrying on its own. Nearby sharing still works.", null, null)
        else -> Hero("Companion offline", "The link is on but not running.", "Reconnect", onResume)
    }

    Column(verticalArrangement = Arrangement.spacedBy(30.dp)) {
        Column {
            H1("Companion")
            Muted("Notifications, clipboard and typing, over any network.", 14.sp, Modifier.padding(top = 4.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            Box(Modifier.padding(top = 8.dp)) { StatusDot(dotFor(state, config), 10.dp) }
            Column {
                T(hero.title, 20.sp, semibold = true)
                Muted(hero.detail, 14.sp, Modifier.padding(top = 2.dp, bottom = 10.dp))
                if (hero.cta != null && hero.act != null) {
                    WWButton(hero.cta, Kind.Primary, minHeight = 44.dp, onClick = hero.act)
                }
            }
        }

        if (pending > 0) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .background(t.attentionTint, RoundedCornerShape(2.dp))
                    .tap(onClick = onChecklist)
                    .padding(horizontal = 20.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    T("Finish setup", semibold = true)
                    T("$pending ${if (pending == 1) "step" else "steps"} left", 13.sp)
                }
                PhIcon(R.drawable.ic_ph_caret_right, 18.dp)
            }
        }

        Column(Modifier.alpha(if (connected) 1f else 0.55f)) {
            Kicker("In sync")
            RuleRow("Notifications", when {
                !config.notificationsEnabled -> "Off"
                !notifAccess -> "Needs notification access"
                connected -> "Mirroring to ${config.macName}"
                else -> "Waiting for connection"
            }, minHeight = 60.dp) { WWToggle(config.notificationsEnabled, onToggleNotifications) }
            RuleRow("Clipboard", when {
                !config.clipboardEnabled -> "Off"
                !shizukuReady -> "Needs Shizuku"
                connected -> "Syncs both ways"
                else -> "Waiting for connection"
            }, minHeight = 60.dp) { WWToggle(config.clipboardEnabled, onToggleClipboard) }
            RuleRow("Remote keyboard", when {
                !kbEnabled -> "Enable the keyboard first"
                connected -> "Ready for Mac input"
                else -> "Waiting for connection"
            }, minHeight = 60.dp) {
                WWButton(if (kbEnabled) "Select" else "Enable", Kind.Ghost, minHeight = 44.dp, onClick = onKeyboard)
            }
        }

        WWButton(if (connected && config.clipboardEnabled) "Send clipboard to Mac" else "Clipboard unavailable",
            Kind.Primary, Modifier.fillMaxWidth(), enabled = connected && config.clipboardEnabled, onClick = onSendClipboard)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!config.isComplete) {
                WWButton("Enter details manually", Kind.Ghost, minHeight = 44.dp, onClick = onManual)
            } else {
                if (config.enabled) WWButton("Pause companion", Kind.Ghost, minHeight = 44.dp, onClick = onPause)
                WWButton("Unpair", Kind.Ghost, minHeight = 44.dp, onClick = onUnpair)
            }
        }
    }
}

// ═══ Sheets ══════════════════════════════════════════════════════════════════

@Composable
private fun SheetHost(visible: Boolean, onDismiss: (() -> Unit)?, content: @Composable () -> Unit) {
    val t = LocalWW.current
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(t.scrim)
                .clickable(MutableInteractionSource(), null) { onDismiss?.invoke() },
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(visible, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(t.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .clickable(MutableInteractionSource(), null) {}
                        .navigationBarsPadding().imePadding()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Box(Modifier.size(36.dp, 4.dp).background(t.divider, RoundedCornerShape(2.dp)).align(Alignment.CenterHorizontally))
                    content()
                }
            }
        }
    }
}

@Composable
private fun IncomingSheet(req: Trust) {
    var showFp by remember(req) { mutableStateOf(false) }
    var always by remember(req) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Muted("From ${req.name}")
        Column {
            T(req.file, 25.sp, semibold = true, maxLines = 2)
            Muted(if (req.size > 0) fmtBytes(req.size) else "Size unavailable")
        }
        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Muted("Sender fingerprint", modifier = Modifier.weight(1f))
            if (showFp) T(fingerprintGroups(req.fingerprint), letterSpacing = 1.5.sp)
            else WWButton("Verify", Kind.Ghost, minHeight = 44.dp) { showFp = true }
        }
        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Always accept from this Mac", modifier = Modifier.weight(1f))
            WWToggle(always) { always = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WWButton("Decline", Kind.Secondary, Modifier.weight(1f)) { WooState.answerTrust(req, false) }
            WWButton("Accept", Kind.Primary, Modifier.weight(1f)) { WooState.answerTrust(req, true, always) }
        }
    }
}

@Composable
private fun ChecklistSheet(perms: List<Perm>, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T("Finish setup", 20.sp, semibold = true)
        Muted("Android asks for these before the companion can work.", 14.sp)
        PermList(perms)
        WWButton("Done", Kind.Primary, Modifier.fillMaxWidth().padding(top = 10.dp), onClick = onDone)
    }
}

@Composable
private fun ManualSheet(initial: CompanionConfig, onCancel: () -> Unit, onSave: (CompanionConfig) -> Unit) {
    var url by remember { mutableStateOf(initial.supabaseUrl) }
    var key by remember { mutableStateOf(initial.publishableKey) }
    var secret by remember { mutableStateOf(initial.pairingSecret) }
    val draft = initial.copy(supabaseUrl = url.trim(), publishableKey = key.trim(), pairingSecret = secret.trim()).normalized()
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Column {
            T("Enter details manually", 20.sp, semibold = true)
            Muted("Use the same relay details and secret on both devices.", 14.sp)
        }
        Field("Relay URL", url, { url = it }, "https://…")
        Field("Publishable key", key, { key = it }, "", secret = true)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.weight(1f)) { Field("Pairing secret", secret, { secret = it }, "At least 20 characters", secret = true) }
            WWButton("Generate", Kind.Secondary) { secret = CompanionConfig.generateSecret() }
        }
        Muted("Keep the secret out of screenshots and chat.", 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WWButton("Cancel", Kind.Secondary, Modifier.weight(1f), onClick = onCancel)
            WWButton("Save", Kind.Primary, Modifier.weight(1f), enabled = draft.isComplete) { onSave(draft) }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean = false) {
    val t = LocalWW.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        T(label, 12.sp, color = t.text.copy(alpha = 0.7f))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = baseTextStyle(t).copy(fontSize = 14.sp),
            cursorBrush = SolidColor(t.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .background(t.bg, RoundedCornerShape(2.dp))
                        .border(1.dp, t.divider, RoundedCornerShape(2.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) T(placeholder, 14.sp, color = t.text.copy(alpha = 0.65f))
                    inner()
                }
            }
        )
    }
}

// ═══ Intents / helpers ═══════════════════════════════════════════════════════

private fun fingerprintGroups(fp: String): String =
    fp.uppercase(Locale.US).filter { it.isLetterOrDigit() }.take(12).chunked(4).joinToString(" ")

private fun fmtWhen(ms: Long): String {
    val now = System.currentTimeMillis()
    return when {
        now - ms < 60_000 -> "Just now"
        DateUtils.isToday(ms) -> fmtClock(ms)
        DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(ms)
    }
}

private fun fmtClock(ms: Long): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(ms)

private fun fmtSpeed(bytesPerSec: Double): String =
    if (bytesPerSec <= 0) "Calculating…" else "${fmtBytes(bytesPerSec.toLong())}/s"

private fun notificationAccessGranted(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun shizukuInstalled(context: Context): Boolean =
    context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE) != null

private fun openShizuku(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE)
    val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "Unable to open Shizuku", Toast.LENGTH_SHORT).show()
    }
}

// Settings.Secure.ENABLED_INPUT_METHODS throws SecurityException on targetSdk >= 34;
// the InputMethodManager list is the public equivalent.
private fun wiglyKeyboardEnabled(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
        .enabledInputMethodList.any { it.packageName == context.packageName }
}.getOrDefault(false)

private fun openFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        WooState.flash("No app can open this file type")
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
        WooState.flash("Can’t share this file")
    }
}

private fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}
