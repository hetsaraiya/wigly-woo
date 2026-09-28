package com.wiglywoo.mirror

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.io.FileOutputStream
import kotlin.math.max

/** Encodes a virtual display to HEVC, falling back to H.264. Hardware codecs only. */
class ScreenEncoder(
    private val context: Context,
    private val video: FileOutputStream,
    private val config: MirrorConfig,
    private val onReady: (width: Int, height: Int, codec: Int, displayId: Int) -> Unit = { _, _, _, _ -> },
) {
    @Volatile var running = true
        private set
    private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    var width = 0
        private set
    var height = 0
        private set
    var codecId = 1
        private set
    var displayId = 0
        private set

    fun run() {
        val (dw, dh, dpi) = HiddenApi.defaultDisplaySize(context)
        var w = if (config.width > 0) config.width else dw
        var h = if (config.height > 0) config.height else dh
        val limit = if (config.limit > 0) config.limit else 1080
        val long = max(w, h)
        if (long > limit) {
            val scale = limit.toFloat() / long
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        w -= w % 2
        h -= h % 2
        if (w < 2 || h < 2) return
        width = w
        height = h
        val mime = if (config.codec == 2) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
        try {
            encode(mime, w, h, dpi)
        } catch (_: Throwable) {
            if (!running) return
            if (mime != MediaFormat.MIMETYPE_VIDEO_AVC) encode(MediaFormat.MIMETYPE_VIDEO_AVC, w, h, dpi)
        }
    }

    fun resize(w: Int, h: Int, dpi: Int) {
        if (Build.VERSION.SDK_INT < 35) return
        display?.resize(w - w % 2, h - h % 2, dpi)
    }

    fun stop() {
        running = false
        runCatching { codec?.stop() }
        runCatching { display?.release() }
        runCatching { codec?.release() }
    }

    private fun encode(mime: String, w: Int, h: Int, dpi: Int) {
        codecId = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 1 else 2
        val format = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (config.bitrate > 0) config.bitrate else 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, if (config.fps > 0) config.fps else 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger("latency", 0)
            setInteger("priority", 0)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        }
        val encoder = MediaCodec.createEncoderByType(mime)
        codec = encoder
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface: Surface = encoder.createInputSurface()
        val flags = if (config.appDisplay) {
            HiddenApi.FLAG_PUBLIC or HiddenApi.FLAG_OWN_CONTENT_ONLY
        } else {
            HiddenApi.FLAG_PUBLIC or HiddenApi.FLAG_AUTO_MIRROR
        }
        display = HiddenApi.createVirtualDisplay(context, "wigly", w, h, dpi, surface, flags)
            ?: error("virtual display unavailable")
        displayId = display?.display?.displayId ?: 0
        onReady(w, h, codecId, displayId)
        encoder.start()
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = encoder.dequeueOutputBuffer(info, 10_000)
            if (index < 0 || info.size <= 0) continue
            val buf = encoder.getOutputBuffer(index) ?: continue
            val configBuf = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val kind = if (configBuf) 0 else 1
            val flagsByte = if (key || configBuf) 1 else 0
            MediaWire.write(video, kind, flagsByte, info.presentationTimeUs * 1000, buf, info.offset, info.size)
            encoder.releaseOutputBuffer(index, false)
        }
    }
}
