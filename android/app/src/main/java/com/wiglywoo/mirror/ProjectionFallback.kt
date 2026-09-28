package com.wiglywoo.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.WindowManager
import java.io.FileInputStream
import java.io.FileOutputStream

/** Screen capture with a consent dialog, and taps through the accessibility service. */
object ProjectionFallback {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var controller: Controller? = null
    @Volatile private var running = false

    fun start(context: Context, mediaProjection: MediaProjection, video: FileOutputStream, control: FileInputStream?) {
        stop()
        projection = mediaProjection
        running = true
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val long = maxOf(w, h)
        if (long > 1080) {
            val scale = 1080f / long
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        w -= w % 2
        h -= h % 2
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder = codec
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        display = mediaProjection.createVirtualDisplay(
            "wigly-basic", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC, surface, null, Handler(Looper.getMainLooper()),
        )
        codec.start()
        Thread {
            val info = MediaCodec.BufferInfo()
            while (running) {
                val index = runCatching { codec.dequeueOutputBuffer(info, 10_000) }.getOrDefault(-1)
                if (index < 0) continue
                try {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        val configBuf = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        runCatching {
                            MediaWire.write(video, if (configBuf) 0 else 1, if (key || configBuf) 1 else 0, info.presentationTimeUs * 1000, buf, info.offset, info.size)
                        }
                    }
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }
        }.start()
        if (control != null) {
            // Gestures use real screen pixels, not the scaled video size.
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels
            val ctrl = Controller(control, { screenW }, { screenH }, object : Controller.Actions {
                override fun screenPower(on: Boolean) {}
                override fun expand(settings: Boolean) {}
                override fun launch(component: String) {
                    runCatching {
                        context.startActivity(context.packageManager.getLaunchIntentForPackage(component.substringBefore('/'))?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                override fun clipboard(text: String) {}
                override fun syncFrame() {
                    runCatching { encoder?.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
                }
                private var downX = 0f
                private var downY = 0f
                private var downAt = 0L
                override fun gesture(action: Int, x: Float, y: Float): Boolean {
                    val svc = WiglyAccessibilityService.instance ?: return false
                    when (action) {
                        MotionEvent.ACTION_DOWN -> { downX = x; downY = y; downAt = System.currentTimeMillis() }
                        MotionEvent.ACTION_UP -> svc.stroke(downX, downY, x, y, System.currentTimeMillis() - downAt)
                    }
                    return true
                }
            })
            controller = ctrl
            Thread { runCatching { ctrl.run() } }.start()
        }
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stop() }
        }, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        running = false
        controller?.stop()
        runCatching { display?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { projection?.stop() }
        display = null
        encoder = null
        projection = null
        controller = null
    }
}
