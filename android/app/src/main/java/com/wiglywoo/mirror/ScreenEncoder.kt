package com.wiglywoo.mirror

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.io.FileOutputStream
import kotlin.math.max

private const val TAG = "WiglyMirror"

/** Encodes a virtual display to HEVC, falling back to H.264. Hardware codecs only. */
class ScreenEncoder(
    private val context: Context,
    private val video: FileOutputStream,
    private val config: MirrorConfig,
    private val onReady: (width: Int, height: Int, displayId: Int) -> Unit = { _, _, _ -> },
) {
    @Volatile var running = true
        private set
    private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    @Volatile var displayId = 0
        private set
    /** Set once a frame has gone out; after that a failure is the socket, not the codec. */
    private var produced = false

    fun run() {
        val (dw, dh, dpi) = HiddenApi.defaultDisplaySize(context)
        var w = dw
        var h = dh
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
        } catch (t: Throwable) {
            // Only an encoder that never produced a frame is worth retrying as H.264.
            if (!running) return // stop() cancels a pending dequeue; that is not a failure
            if (produced || mime == MediaFormat.MIMETYPE_VIDEO_AVC) throw t
            release()
            encode(MediaFormat.MIMETYPE_VIDEO_AVC, w, h, dpi)
        }
    }

    /** Asks for a keyframe now, so the Mac recovers right after dropped frames. */
    fun requestSyncFrame() {
        Log.i(TAG, "keyframe requested after dropped video")
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun stop() {
        running = false
        release()
    }

    private fun release() {
        runCatching { codec?.stop() }
        runCatching { display?.release() }
        runCatching { codec?.release() }
        codec = null
        display = null
    }

    private fun encode(mime: String, w: Int, h: Int, dpi: Int) {
        val format = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (config.bitrate > 0) config.bitrate else 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, if (config.fps > 0) config.fps else 60)
            // Keyframes are big bursts on Wi-Fi; one every 10 s, like scrcpy.
            // PREPEND_HEADER below still lets a late decoder start at any keyframe.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            setInteger("latency", 0)
            setInteger("priority", 0)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // Every keyframe carries the parameter sets, so a Mac that missed
            // the codec config can still start decoding.
            if (Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                // No reordered frames: the decoder never holds one back.
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                // A 120 Hz panel would otherwise feed ~100 fps; cap what reaches the encoder.
                setFloat("max-fps-to-encoder", (if (config.fps > 0) config.fps else 60).toFloat())
            }
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        }
        val encoder = MediaCodec.createEncoderByType(mime)
        codec = encoder
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        display = if (config.appDisplay) {
            HiddenApi.appDisplay(context, "wigly", w, h, dpi, surface)
        } else {
            HiddenApi.mirrorDisplay(context, "wigly", w, h, dpi, surface)
        } ?: error("virtual display unavailable")
        displayId = display?.display?.displayId ?: 0
        encoder.start()
        onReady(w, h, displayId)
        Log.i(TAG, "encoding ${w}x$h $mime on display $displayId")
        val info = MediaCodec.BufferInfo()
        var frames = 0
        var bytes = 0L
        var lastLog = System.nanoTime()
        while (running) {
            if (System.nanoTime() - lastLog > 5_000_000_000L) {
                Log.i(TAG, "video: $frames frames, ${bytes / 1024} KB in 5 s")
                frames = 0
                bytes = 0
                lastLog = System.nanoTime()
            }
            val index = encoder.dequeueOutputBuffer(info, 10_000)
            if (index < 0) continue
            try {
                val buf = encoder.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    val configBuf = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    MediaWire.write(video, if (configBuf) 0 else 1, if (key || configBuf) 1 else 0,
                        info.presentationTimeUs * 1000, buf, info.offset, info.size)
                    produced = true
                    frames++
                    bytes += info.size
                }
            } finally {
                // Every dequeued buffer goes back, even an empty one, or the encoder stalls.
                encoder.releaseOutputBuffer(index, false)
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }
}
