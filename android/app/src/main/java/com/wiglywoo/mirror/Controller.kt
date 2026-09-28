package com.wiglywoo.mirror

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads control datagrams and injects them. Right-click and the navigation buttons are already resolved by the Mac. */
class Controller(
    private val input: FileInputStream,
    private val width: () -> Int,
    private val height: () -> Int,
    private val actions: Actions,
) {
    interface Actions {
        fun screenPower(on: Boolean)
        fun expand(settings: Boolean)
        fun launch(component: String)
        fun clipboard(text: String)
        fun reconfigure(width: Int, height: Int, fps: Int, bitrate: Int, limit: Int, codec: Int, flags: Int)
        fun record(on: Boolean)
        /** Return true when basic mirroring handled the point. */
        fun gesture(action: Int, x: Float, y: Float): Boolean = false
    }

    @Volatile var running = true

    fun run() {
        val buf = ByteArray(64 * 1024)
        while (running) {
            val n = runCatching { input.read(buf) }.getOrDefault(-1)
            if (n <= 0) {
                if (n < 0) return
                continue
            }
            handle(buf.copyOf(n))
        }
    }

    fun stop() { running = false }

    private fun handle(msg: ByteArray) {
        if (msg.isEmpty()) return
        when (msg[0].toInt() and 0xff) {
            1 -> touch(msg)
            2 -> scroll(msg)
            3 -> key(msg)
            4 -> actions.clipboard(String(msg, 1, msg.size - 1, Charsets.UTF_8))
            5 -> button(msg)
            6 -> actions.clipboard(String(msg, 1, msg.size - 1, Charsets.UTF_8))
            7 -> config(msg)
            8 -> if (msg.size > 1) actions.screenPower(msg[1].toInt() != 0)
            9 -> actions.launch(String(msg, 1, msg.size - 1, Charsets.UTF_8))
            10 -> if (msg.size > 1) actions.record(msg[1].toInt() != 0)
            11 -> pointer(msg)
        }
    }

    private fun touch(msg: ByteArray) {
        if (msg.size < 9) return
        val action = when (msg[1].toInt()) {
            0 -> MotionEvent.ACTION_DOWN
            1 -> MotionEvent.ACTION_MOVE
            else -> MotionEvent.ACTION_UP
        }
        val x = u16(msg, 3)
        val y = u16(msg, 5)
        val px = PointerMap.toPixel(x, width())
        val py = PointerMap.toPixel(y, height())
        val now = SystemClock.uptimeMillis()
        if (actions.gesture(action, px, py)) return
        val event = MotionEvent.obtain(now, now, action, px, py, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        HiddenApi.inject(event)
        event.recycle()
    }

    private fun pointer(msg: ByteArray) {
        if (msg.size < 10) return
        val buttons = msg[2].toInt() and 0xff
        val x = PointerMap.toPixel(u16(msg, 3), width())
        val y = PointerMap.toPixel(u16(msg, 5), height())
        val now = SystemClock.uptimeMillis()
        val action = when (msg[1].toInt()) {
            1 -> MotionEvent.ACTION_DOWN
            2 -> MotionEvent.ACTION_UP
            3 -> MotionEvent.ACTION_SCROLL
            else -> MotionEvent.ACTION_HOVER_MOVE
        }
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        event.source = InputDevice.SOURCE_MOUSE
        event.buttonState = when {
            buttons and 1 != 0 -> MotionEvent.BUTTON_PRIMARY
            buttons and 2 != 0 -> MotionEvent.BUTTON_SECONDARY
            buttons and 4 != 0 -> MotionEvent.BUTTON_TERTIARY
            else -> 0
        }
        HiddenApi.inject(event)
        event.recycle()
    }

    private fun scroll(msg: ByteArray) {
        if (msg.size < 9) return
        val x = PointerMap.toPixel(u16(msg, 1), width())
        val y = PointerMap.toPixel(u16(msg, 3), height())
        val dy = ByteBuffer.wrap(msg, 7, 2).order(ByteOrder.BIG_ENDIAN).short / 120f
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0 }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, dy)
        }
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        HiddenApi.inject(event)
        event.recycle()
    }

    private fun key(msg: ByteArray) {
        if (msg.size < 10) return
        val action = if (msg[1].toInt() == 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val code = ByteBuffer.wrap(msg, 2, 4).order(ByteOrder.BIG_ENDIAN).int
        val meta = ByteBuffer.wrap(msg, 6, 4).order(ByteOrder.BIG_ENDIAN).int
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, action, code, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        HiddenApi.inject(event)
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
            9 -> actions.reconfigure(0, 0, 0, 0, 0, 0, 0)
        }
    }

    private fun config(msg: ByteArray) {
        if (msg.size < 14) return
        val bb = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN)
        val w = bb.getShort(1).toInt() and 0xffff
        val h = bb.getShort(3).toInt() and 0xffff
        val fps = msg[5].toInt() and 0xff
        val bitrate = bb.getInt(6)
        val limit = bb.getShort(10).toInt() and 0xffff
        val codec = msg[12].toInt() and 0xff
        val flags = msg[13].toInt() and 0xff
        actions.reconfigure(w, h, fps, bitrate, limit, codec, flags)
    }

    private fun tapKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        HiddenApi.inject(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        HiddenApi.inject(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private fun u16(msg: ByteArray, at: Int): Int =
        ByteBuffer.wrap(msg, at, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
}
