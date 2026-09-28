package com.wiglywoo.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** Basic-mirroring input: taps and swipes when Shizuku is not running. */
class WiglyAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onDestroy() {
        if (instance === this) instance = null
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** A tap when the points are close, otherwise a swipe that takes [ms]. */
    fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val moved = Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()) > 24
        val path = Path().apply { moveTo(x1, y1); if (moved) lineTo(x2, y2) }
        val duration = if (moved) ms.coerceIn(60, 1_000) else 40
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
    }

    companion object {
        @Volatile var instance: WiglyAccessibilityService? = null
    }
}
