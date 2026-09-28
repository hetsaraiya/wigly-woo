package com.wiglywoo.mirror

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import java.io.FileOutputStream

/** Captures playback (REMOTE_SUBMIX) and encodes Opus, falling back to AAC. */
class AudioCapture(private val out: FileOutputStream) {
    @Volatile var running = true
    var codecId = 3
        private set

    fun run() {
        if (Build.VERSION.SDK_INT < 29) return
        val rate = 48_000
        val channel = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val min = AudioRecord.getMinBufferSize(rate, channel, encoding).coerceAtLeast(4096)
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(8) // REMOTE_SUBMIX, a hidden source shell is allowed to use
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(rate)
                        .setChannelMask(channel)
                        .build()
                )
                .setBufferSizeInBytes(min * 4)
                .build()
        }.getOrNull() ?: return
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        val encoder = createEncoder(rate) ?: run {
            record.release()
            return
        }
        try {
            record.startRecording()
            encoder.start()
            val pcm = ByteArray(min)
            val info = MediaCodec.BufferInfo()
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                val inIndex = encoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    encoder.getInputBuffer(inIndex)?.let { buf ->
                        buf.clear()
                        buf.put(pcm, 0, n)
                    }
                    encoder.queueInputBuffer(inIndex, 0, n, System.nanoTime() / 1000, 0)
                }
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(info, 0)
                    if (outIndex < 0) break
                    val buf = encoder.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0) {
                        val configBuf = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        MediaWire.write(out, if (configBuf) 0 else 1, 0, info.presentationTimeUs * 1000, buf, info.offset, info.size)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { encoder.stop() }
            encoder.release()
        }
    }

    fun stop() { running = false }

    private fun createEncoder(rate: Int): MediaCodec? {
        val opus = runCatching { encoder(MediaFormat.MIMETYPE_AUDIO_OPUS, rate) }.getOrNull()
        if (opus != null) {
            codecId = 3
            return opus
        }
        codecId = 4
        return runCatching { encoder(MediaFormat.MIMETYPE_AUDIO_AAC, rate) }.getOrNull()
    }

    private fun encoder(mime: String, rate: Int): MediaCodec {
        val format = MediaFormat.createAudioFormat(mime, rate, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
        }
        return MediaCodec.createEncoderByType(mime).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }
}
