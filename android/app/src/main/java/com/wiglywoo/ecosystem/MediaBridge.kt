package com.wiglywoo.ecosystem

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.wiglywoo.CompanionManager
import com.wiglywoo.NotificationRelayService
import org.json.JSONObject

/** Active media sessions, allowed because the notification listener is enabled. */
class MediaBridge(private val context: Context) {
    private var controller: MediaController? = null
    private val sessions by lazy { context.getSystemService(MediaSessionManager::class.java) }

    fun start() {
        val component = ComponentName(context, NotificationRelayService::class.java)
        runCatching {
            sessions.addOnActiveSessionsChangedListener({ list -> bind(list?.firstOrNull()) }, component)
            bind(sessions.getActiveSessions(component).firstOrNull())
        }
    }

    fun command(name: String) {
        val controls = controller?.transportControls ?: return
        when (name) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
        }
    }

    private fun bind(next: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback)
        publish(next)
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { publish(controller) }
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) { publish(controller) }
    }

    private fun publish(controller: MediaController?) {
        if (!EcosystemSettings.load(context).nowPlaying || controller == null) return
        val md = controller.metadata
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        CompanionManager.send(JSONObject()
            .put("type", "now_playing")
            .put("title", md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty())
            .put("artist", md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
            .put("playing", playing)
            .put("package", controller.packageName))
    }
}
