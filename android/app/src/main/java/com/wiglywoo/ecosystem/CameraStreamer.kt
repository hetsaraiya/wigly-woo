package com.wiglywoo.ecosystem

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.content.ContextCompat
import com.wiglywoo.mirror.MediaWire
import java.io.FileOutputStream

/**
 * Phone camera as a webcam. The Mac publishes it through a CoreMediaIO
 * extension, which needs Developer ID signing before other apps can see it.
 * This phone does not advertise camera.concurrent, so only one lens streams.
 */
class CameraStreamer(private val context: Context, private val video: FileOutputStream) {
    private val thread = HandlerThread("wigly-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var codec: MediaCodec? = null

    fun start(lens: Int = CameraCharacteristics.LENS_FACING_FRONT) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(CameraManager::class.java) ?: return
        val id = manager.cameraIdList.firstOrNull { cameraId ->
            manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == lens
        } ?: manager.cameraIdList.firstOrNull() ?: return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1280, 720).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        codec = encoder
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(surface) }
                        session.setRepeatingRequest(request.build(), null, handler)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, handler)
            }
            override fun onDisconnected(camera: CameraDevice) { stop() }
            override fun onError(camera: CameraDevice, error: Int) { stop() }
        }, handler)
        Thread {
            val info = MediaCodec.BufferInfo()
            while (codec != null) {
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                if (index < 0 || info.size <= 0) continue
                val buf = encoder.getOutputBuffer(index) ?: continue
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                MediaWire.write(video, if (config) 0 else 1, if (key || config) 1 else 0, info.presentationTimeUs * 1000, buf, info.offset, info.size)
                encoder.releaseOutputBuffer(index, false)
            }
        }.start()
    }

    fun stop() {
        runCatching { device?.close() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        device = null
        codec = null
        thread.quitSafely()
    }
}
