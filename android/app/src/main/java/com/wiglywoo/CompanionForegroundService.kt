package com.wiglywoo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class CompanionForegroundService : Service() {
    private lateinit var manager: NotificationManager
    private val stateListener: (SupabaseRealtimeClient.State) -> Unit = { state ->
        manager.notify(NOTIFICATION_ID, notification(state))
    }

    override fun onCreate() {
        super.onCreate()
        CompanionManager.initialize(this)
        manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "Wigly Woo companion", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps your encrypted Mac connection available" })
        }
        startForeground(NOTIFICATION_ID, notification(CompanionManager.state))
        CompanionManager.addStateListener(stateListener)
    }

    private fun notification(state: SupabaseRealtimeClient.State): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sendClipboard = PendingIntent.getActivity(
            this, 1, Intent(this, SendClipboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when (state) {
            SupabaseRealtimeClient.State.CONNECTED -> "Mac companion connected"
            SupabaseRealtimeClient.State.CONNECTING -> "Connecting to your Mac…"
            SupabaseRealtimeClient.State.ERROR -> "Reconnecting to your Mac"
            SupabaseRealtimeClient.State.OFF -> "Mac companion paused"
        }
        val detail = when (state) {
            SupabaseRealtimeClient.State.CONNECTED ->
                "Notifications, clipboard and keyboard are ready"
            SupabaseRealtimeClient.State.CONNECTING ->
                "Opening the encrypted companion channel"
            SupabaseRealtimeClient.State.ERROR ->
                "Wigly Woo will keep trying automatically"
            SupabaseRealtimeClient.State.OFF ->
                "Open Wigly Woo to resume remote features"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(open)
            .addAction(0, "Send clipboard", sendClipboard)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CompanionManager.reload()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        CompanionManager.removeStateListener(stateListener)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "wigly_companion"
        private const val NOTIFICATION_ID = 4201
    }
}
