package com.wiglywoo

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Broadsheet "Column" direction: one quiet serif, cyan for anything
// interactive, hierarchy from size and whitespace only.

data class WW(
    val bg: Color, val surface: Color, val text: Color,
    val accent: Color, val link: Color, val error: Color,
    val accentTint: Color, val attentionTint: Color,
    val tagBg: Color, val tagText: Color, val toggleOff: Color, val dotIdle: Color,
) {
    val muted get() = text.copy(alpha = 0.55f)
    val divider get() = text.copy(alpha = 0.16f)
    val rule get() = text.copy(alpha = 0.08f)
    val scrim get() = Color(0x732D2B2B)
}

val LightWW = WW(
    bg = Color(0xFFF3F2F2), surface = Color(0xFFEAE9E9), text = Color(0xFF201E1D),
    accent = Color(0xFF0088B0), link = Color(0xFF006786), error = Color(0xFFD6006C),
    accentTint = Color(0xFFE9F8FF), attentionTint = Color(0xFFFFF1F4),
    tagBg = Color(0xFFE9F8FF), tagText = Color(0xFF004961),
    toggleOff = Color(0xFFBAB6B6), dotIdle = Color(0xFF9B9797),
)

val DarkWW = WW(
    bg = Color(0xFF1C1B1A), surface = Color(0xFF272524), text = Color(0xFFECEBE9),
    accent = Color(0xFF38A6CF), link = Color(0xFF62C5EE), error = Color(0xFFD6006C),
    accentTint = Color(0xFF0A303E), attentionTint = Color(0xFF4B1528),
    tagBg = Color(0xFF0A303E), tagText = Color(0xFF99E0FF),
    toggleOff = Color(0xFF605D5D), dotIdle = Color(0xFF9B9797),
)

val LocalWW = staticCompositionLocalOf { LightWW }

val Serif = FontFamily(
    Font(R.font.source_serif_regular, FontWeight.Normal),
    Font(R.font.source_serif_semibold, FontWeight.SemiBold),
)

fun baseTextStyle(t: WW) = TextStyle(fontFamily = Serif, fontSize = 15.sp, lineHeight = 22.sp, color = t.text)

private val Radius = RoundedCornerShape(2.dp)

fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(enabled = enabled, onClick = onClick)

// ── Text ──

@Composable
fun T(
    text: String,
    size: TextUnit = 15.sp,
    semibold: Boolean = false,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) = Text(
    text, modifier = modifier, fontSize = size, color = color, maxLines = maxLines,
    overflow = TextOverflow.Ellipsis, letterSpacing = letterSpacing,
    lineHeight = if (size.value >= 24) (size.value * 1.12f).sp else (size.value * 1.45f).sp,
    fontWeight = if (semibold) FontWeight.SemiBold else FontWeight.Normal,
    style = LocalTextStyle.current,
)

@Composable
fun Muted(text: String, size: TextUnit = 13.sp, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    T(text, size, color = LocalWW.current.muted, modifier = modifier, maxLines = maxLines)

@Composable
fun H1(text: String, modifier: Modifier = Modifier) =
    T(text, 30.sp, semibold = true, modifier = modifier, letterSpacing = (-0.45).sp)

@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) =
    T(text.uppercase(), 11.sp, color = LocalWW.current.link, modifier = modifier, letterSpacing = 1.1.sp)

// ── Controls ──

enum class Kind { Primary, Secondary, Ghost }

@Composable
fun WWButton(
    label: String,
    kind: Kind,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
) {
    val t = LocalWW.current
    val fg = when (kind) { Kind.Primary -> t.bg; Kind.Secondary -> t.text; Kind.Ghost -> t.link }
    Row(
        modifier
            .heightIn(min = minHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .background(if (kind == Kind.Primary) t.accent else Color.Transparent, Radius)
            .then(if (kind == Kind.Secondary) Modifier.border(1.dp, t.divider, Radius) else Modifier)
            .tap(enabled, onClick)
            .padding(horizontal = if (kind == Kind.Ghost) 6.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(18.dp))
        T(label, 14.sp, semibold = true, color = fg, maxLines = 1)
    }
}

@Composable
fun WWToggle(checked: Boolean, onToggle: (Boolean) -> Unit) {
    val t = LocalWW.current
    Box(
        Modifier.size(48.dp).tap { onToggle(!checked) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(40.dp, 24.dp).background(if (checked) t.accent else t.toggleOff, Radius).padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(Modifier.size(18.dp).background(t.bg, RoundedCornerShape(1.dp)))
        }
    }
}

enum class Dot { Idle, Connecting, Connected, Error }

@Composable
fun StatusDot(dot: Dot, size: Dp = 7.dp) {
    val t = LocalWW.current
    val m = Modifier.size(size)
    when (dot) {
        Dot.Connecting -> Box(m.border(1.5.dp, t.accent, CircleShape))
        Dot.Connected -> Box(m.background(t.accent, CircleShape))
        Dot.Error -> Box(m.background(t.error, CircleShape))
        Dot.Idle -> Box(m.background(t.dotIdle, CircleShape))
    }
}

@Composable
fun Tag(text: String) {
    val t = LocalWW.current
    T(text, 11.sp, color = t.tagText,
        modifier = Modifier.background(t.tagBg, RoundedCornerShape(1.5.dp)).padding(horizontal = 10.dp, vertical = 3.dp))
}

/** A hairline-ruled list row: title + detail on the left, a control on the right. */
@Composable
fun RuleRow(
    title: String,
    detail: String?,
    minHeight: Dp = 64.dp,
    trailing: @Composable () -> Unit = {},
) {
    val t = LocalWW.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight)
            .drawRule(t.rule),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            T(title, maxLines = 1)
            if (detail != null) Muted(detail, maxLines = 2)
        }
        trailing()
    }
}

fun Modifier.drawRule(color: Color): Modifier = drawBehind {
    val h = 1.dp.toPx()
    drawRect(color, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
}

@Composable
fun ProgressLine(fraction: Float) {
    val t = LocalWW.current
    Box(Modifier.fillMaxWidth().height(2.dp).background(t.divider)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(t.accent))
    }
}

@Composable
fun PhIcon(@DrawableRes id: Int, size: Dp = 22.dp, tint: Color = LocalWW.current.text) =
    Icon(painterResource(id), null, tint = tint, modifier = Modifier.size(size))
