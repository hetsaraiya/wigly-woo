package com.wiglywoo.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wiglywoo.MainActivity
import com.wiglywoo.R

/** Foreground service required while the Mac is watching, including basic capture. */
class MirrorSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Phone mirroring", NotificationManager.IMPORTANCE_LOW))
        }
        val type = if (intent?.hasExtra("code") == true && Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(), type) else startForeground(ID, notification())
        if (intent?.action == ACTION_STOP) {
            MirrorHost.stop("stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }
        if (code != 0 && data != null) {
            val projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(code, data)
            MirrorHost.startBasic(this, projection)
        }
        return START_STICKY
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 2, Intent(this, MirrorSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.mirror_watching))
            .setContentText("Stop sharing any time")
            .setContentIntent(open)
            .addAction(0, getString(R.string.mirror_stop), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL = "wigly_mirror"
        const val ID = 4202
        const val ACTION_STOP = "com.wiglywoo.mirror.STOP"

        fun start(context: Context) {
            val intent = Intent(context, MirrorSessionService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun startProjection(context: Context, code: Int, data: Intent) {
            val intent = Intent(context, MirrorSessionService::class.java)
                .putExtra("code", code)
                .putExtra("data", data)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
