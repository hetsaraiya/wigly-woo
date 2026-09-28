package com.wiglywoo.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.FileOutputStream

/**
 * Captures the phone's audio output (REMOTE_SUBMIX, which the shell uid may
 * record) and encodes AAC-LC, 48 kHz stereo — the format the Mac's
 * AudioPlayer decodes.
 */
class AudioCapture(private val context: Context, private val out: FileOutputStream) {
    @Volatile var running = true

    @SuppressLint("MissingPermission") // held by the shell uid this runs as
    fun run() {
        if (Build.VERSION.SDK_INT < 30) return
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(4096)
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(format)
                .setBufferSizeInBytes(min * 4)
                .apply { if (Build.VERSION.SDK_INT >= 31) setContext(context) }
                .build()
        }.onFailure { Log.w(TAG, "audio record", it) }.getOrNull() ?: return
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        val encoder = runCatching { encoder() }.onFailure { Log.w(TAG, "aac encoder", it) }.getOrNull() ?: run {
            record.release()
            return
        }
        try {
            record.startRecording()
            encoder.start()
            // One AAC frame is 1024 samples; feed whole frames.
            val pcm = ByteArray(FRAME_BYTES)
            val info = MediaCodec.BufferInfo()
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                val inIndex = encoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    encoder.getInputBuffer(inIndex)?.apply { clear(); put(pcm, 0, n) }
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

    private fun encoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private companion object {
        const val TAG = "WiglyMirror"
        const val RATE = 48_000
        const val FRAME_BYTES = 1024 * 2 * 2
    }
}
