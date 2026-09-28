package com.wiglywoo

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class NotificationRelayService : NotificationListenerService() {
    private val incoming: (JSONObject) -> Unit = { message ->
        when (message.optString("type")) {
            "notification_dismiss" -> cancelNotification(message.optString("notificationKey"))
            "notification_reply" -> reply(message.optString("notificationKey"), message.optString("text"))
        }
    }

    override fun onListenerConnected() {
        CompanionManager.initialize(this)
        CompanionManager.addMessageListener(incoming)
        // The system rebinds this listener after boots, updates and process
        // kills — use that to revive the persistent connection service.
        val config = CompanionConfig.load(this)
        if (config.enabled && config.isComplete) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, CompanionForegroundService::class.java))
            }
        }
    }

    override fun onListenerDisconnected() {
        CompanionManager.removeMessageListener(incoming)
    }

    override fun onDestroy() {
        CompanionManager.removeMessageListener(incoming)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val config = CompanionConfig.load(this)
        if (!config.enabled || !config.notificationsEnabled || sbn.packageName in SKIPPED_PACKAGES + packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && body.isBlank()) return
        val appName = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)
        val canReply = n.actions?.any { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        } == true
        CompanionManager.send(JSONObject()
            .put("type", "notification")
            .put("notificationKey", sbn.key)
            .put("app", appName)
            .put("package", sbn.packageName)
            .put("category", n.category ?: "")
            .put("postedAt", sbn.postTime)
            .put("title", title)
            .put("body", body)
            .put("canReply", canReply))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (CompanionConfig.load(this).notificationsEnabled) {
            CompanionManager.send(JSONObject()
                .put("type", "notification_removed")
                .put("notificationKey", sbn.key))
        }
    }

    private companion object {
        // System chrome (screenshots, USB debugging, charging) is noise on the Mac.
        val SKIPPED_PACKAGES = setOf("android", "com.android.systemui")
    }

    private fun reply(key: String, text: String) {
        val n = activeNotifications.firstOrNull { it.key == key }?.notification ?: return
        val action = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } ?: return
        val inputs = action.remoteInputs ?: return
        val intent = Intent()
        val results = Bundle()
        inputs.filter { it.allowFreeFormInput }.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        runCatching { action.actionIntent.send(this, 0, intent) }
    }
}
