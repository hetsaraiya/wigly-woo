package com.wiglywoo.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Basic-mirroring input, and an opt-in read of Chrome's URL bar for handoff. */
class WiglyAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onDestroy() {
        if (instance === this) instance = null
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build(), null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 120)).build(), null, null)
    }

    /** Opt-in: the URL currently shown in Chrome's address bar, if the tree exposes it. */
    fun chromeUrl(): String? {
        val root = rootInActiveWindow ?: return null
        return findUrl(root)
    }

    private fun findUrl(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString().orEmpty()
        if (text.startsWith("http://") || text.startsWith("https://")) return text
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findUrl(child)?.let { return it }
        }
        return null
    }

    companion object {
        @Volatile var instance: WiglyAccessibilityService? = null
    }
}
