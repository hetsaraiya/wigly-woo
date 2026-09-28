package com.wiglywoo.mirror

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads control datagrams (layout in core/session/control.go) and injects them.
 * Right-click and the navigation buttons are already resolved by the Mac.
 */
class Controller(
    private val input: FileInputStream,
    private val width: () -> Int,
    private val height: () -> Int,
    private val actions: Actions,
    private val displayId: () -> Int = { 0 },
) {
    interface Actions {
        fun screenPower(on: Boolean)
        fun expand(settings: Boolean)
        fun launch(component: String)
        fun clipboard(text: String)
        /** The core dropped late video: encode a keyframe now. */
        fun syncFrame() {}
        /** Return true when basic mirroring handled the point. */
        fun gesture(action: Int, x: Float, y: Float): Boolean = false
    }

    @Volatile var running = true
    private var touchDown = 0L
    private var mouseDown = 0L
    private val keys by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    fun run() {
        val records = Records.Reader(input)
        while (running) {
            // The socket has a receive timeout, so null means "nothing yet".
            val msg = runCatching { records.next() }.getOrElse { return } ?: continue
            if (msg.isNotEmpty()) runCatching { handle(msg) }
        }
    }

    fun stop() { running = false }

    private fun handle(msg: ByteArray) {
        when (msg[0].toInt() and 0xff) {
            1 -> touch(msg)
            2 -> scroll(msg)
            3 -> key(msg)
            4 -> text(String(msg, 1, msg.size - 1, Charsets.UTF_8))
            5 -> button(msg)
            6 -> actions.clipboard(String(msg, 1, msg.size - 1, Charsets.UTF_8))
            8 -> if (msg.size > 1) actions.screenPower(msg[1].toInt() != 0)
            9 -> actions.launch(String(msg, 1, msg.size - 1, Charsets.UTF_8))
            11 -> pointer(msg)
            12 -> actions.syncFrame()
        }
    }

    private fun touch(msg: ByteArray) {
        if (msg.size < 9) return
        val action = when (msg[1].toInt()) {
            0 -> MotionEvent.ACTION_DOWN
            1 -> MotionEvent.ACTION_MOVE
            else -> MotionEvent.ACTION_UP
        }
        val px = PointerMap.toPixel(u16(msg, 3), width())
        val py = PointerMap.toPixel(u16(msg, 5), height())
        val now = SystemClock.uptimeMillis()
        // Long-press and fling detection read the gesture's down time.
        if (action == MotionEvent.ACTION_DOWN) touchDown = now
        if (actions.gesture(action, px, py)) return
        val event = MotionEvent.obtain(touchDown, now, action, px, py, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        HiddenApi.inject(event, displayId())
        event.recycle()
    }

    private fun pointer(msg: ByteArray) {
        if (msg.size < 10) return
        val buttons = msg[2].toInt() and 0xff
        val x = PointerMap.toPixel(u16(msg, 3), width())
        val y = PointerMap.toPixel(u16(msg, 5), height())
        val now = SystemClock.uptimeMillis()
        val state = when {
            buttons and 1 != 0 -> MotionEvent.BUTTON_PRIMARY
            buttons and 2 != 0 -> MotionEvent.BUTTON_SECONDARY
            buttons and 4 != 0 -> MotionEvent.BUTTON_TERTIARY
            else -> 0
        }
        val action = when (msg[1].toInt()) {
            1 -> MotionEvent.ACTION_DOWN.also { mouseDown = now }
            2 -> MotionEvent.ACTION_UP
            // A move with a button held is a drag, not a hover.
            else -> if (state != 0) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE
        }
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y }
        val event = MotionEvent.obtain(
            if (action == MotionEvent.ACTION_HOVER_MOVE) now else mouseDown, now, action, 1,
            arrayOf(props), arrayOf(coords), 0, state, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        HiddenApi.inject(event, displayId())
        event.recycle()
    }

    private fun scroll(msg: ByteArray) {
        if (msg.size < 9) return
        val x = PointerMap.toPixel(u16(msg, 1), width())
        val y = PointerMap.toPixel(u16(msg, 3), height())
        val dx = ByteBuffer.wrap(msg, 5, 2).order(ByteOrder.BIG_ENDIAN).short / 16f
        val dy = ByteBuffer.wrap(msg, 7, 2).order(ByteOrder.BIG_ENDIAN).short / 16f
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_HSCROLL, -dx)
            setAxisValue(MotionEvent.AXIS_VSCROLL, dy)
        }
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        HiddenApi.inject(event, displayId())
        event.recycle()
    }

    private fun key(msg: ByteArray) {
        if (msg.size < 10) return
        val action = if (msg[1].toInt() == 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val code = ByteBuffer.wrap(msg, 2, 4).order(ByteOrder.BIG_ENDIAN).int
        val meta = ByteBuffer.wrap(msg, 6, 4).order(ByteOrder.BIG_ENDIAN).int
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        HiddenApi.inject(event, displayId())
    }

    /** Types text. Characters the virtual keyboard cannot produce are pasted. */
    private fun text(value: String) {
        if (value.isEmpty()) return
        val events = keys.getEvents(value.toCharArray())
        if (events != null) {
            events.forEach { HiddenApi.inject(it, displayId()) }
            return
        }
        actions.clipboard(value)
        tapKey(KeyEvent.KEYCODE_PASTE)
    }

    private fun button(msg: ByteArray) {
        if (msg.size < 2) return
        when (msg[1].toInt()) {
            1 -> tapKey(KeyEvent.KEYCODE_BACK)
            2 -> tapKey(KeyEvent.KEYCODE_HOME)
            3 -> tapKey(KeyEvent.KEYCODE_APP_SWITCH)
            4 -> actions.expand(settings = false)
            5 -> actions.expand(settings = true)
            6 -> tapKey(KeyEvent.KEYCODE_POWER)
            7 -> tapKey(KeyEvent.KEYCODE_WAKEUP)
            8 -> tapKey(KeyEvent.KEYCODE_SLEEP)
        }
    }

    private fun tapKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        HiddenApi.inject(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), displayId())
        HiddenApi.inject(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), displayId())
    }

    private fun u16(msg: ByteArray, at: Int): Int =
        ByteBuffer.wrap(msg, at, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
}
