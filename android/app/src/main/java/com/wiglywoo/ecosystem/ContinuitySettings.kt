package com.wiglywoo.ecosystem

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wiglywoo.Kicker
import com.wiglywoo.RuleRow
import com.wiglywoo.WWToggle
import com.wiglywoo.ecosystem.EcosystemSettings.Feature

private data class Row(val feature: Feature, val title: String, val detail: String)

private val rows = listOf(
    Row(Feature.STATUS, "Phone status", "Battery and signal in the Mac menu bar"),
    Row(Feature.NOW_PLAYING, "Now playing", "Control phone music with the Mac's media keys"),
    Row(Feature.OTP, "Security codes", "Copy one-time codes to the Mac"),
    Row(Feature.CALLS, "Calls", "Answer or decline from the Mac. Audio stays on the phone"),
    Row(Feature.SMS, "Text messages", "Read and reply to SMS on the Mac"),
    Row(Feature.SCREENSHOTS, "Screenshots", "Send new screenshots to the Mac"),
    Row(Feature.PRESENCE, "Nearby", "Let the Mac sense this phone over Bluetooth"),
)

/** Continuity toggles. Turning one on asks for its permissions first. */
@Composable
fun ContinuitySettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf<Feature?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val feature = asking
        asking = null
        if (feature != null && result.values.all { it }) EcosystemSettings.set(context, feature, true)
        EcosystemHost.refresh()
        tick++
    }
    Column(modifier) {
        Kicker("Continuity", Modifier.padding(bottom = 4.dp))
        rows.forEach { row ->
            val on = remember(tick) { EcosystemSettings.active(context, row.feature) }
            val detail = if (EcosystemSettings.enabled(context, row.feature) && !on)
                "Needs permission. Turn on to allow" else row.detail
            RuleRow(row.title, detail, minHeight = 60.dp) {
                WWToggle(on) { want ->
                    if (want && !EcosystemSettings.granted(context, row.feature)) {
                        asking = row.feature
                        launcher.launch(EcosystemSettings.permissions(row.feature).toTypedArray())
                    } else {
                        EcosystemSettings.set(context, row.feature, want)
                        EcosystemHost.refresh()
                        tick++
                    }
                }
            }
        }
    }
}
