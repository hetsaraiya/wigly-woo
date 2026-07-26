package com.wiglywoo

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SupabaseRealtimeClient(
    private val config: CompanionConfig,
    private val onState: (State) -> Unit,
    private val onEnvelope: (JSONObject) -> Unit,
) {
    enum class State { OFF, CONNECTING, CONNECTED, ERROR }

    private val handler = Handler(Looper.getMainLooper())
    private val refs = AtomicInteger(1)
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var stopped = false
    private var attempt = 0
    private var joinRef = ""
    private val topic = "realtime:wigly:${config.channelId}"

    fun connect() {
        stopped = false
        onState(State.CONNECTING)
        val base = config.supabaseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val url = "$base/realtime/v1/websocket?apikey=${config.publishableKey}&vsn=1.0.0"
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun disconnect() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "disabled")
        socket = null
        onState(State.OFF)
    }

    fun broadcast(envelope: JSONObject): Boolean {
        val message = JSONObject()
            .put("topic", topic)
            .put("event", "broadcast")
            .put("payload", JSONObject()
                .put("type", "broadcast")
                .put("event", "companion")
                .put("payload", envelope))
            .put("ref", refs.incrementAndGet().toString())
            .put("join_ref", joinRef)
        return socket?.send(message.toString()) == true
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            joinRef = refs.incrementAndGet().toString()
            val payload = JSONObject().put("config", JSONObject()
                .put("broadcast", JSONObject().put("ack", false).put("self", false))
                .put("presence", JSONObject().put("enabled", false).put("key", ""))
                .put("postgres_changes", org.json.JSONArray())
                .put("private", false))
            webSocket.send(JSONObject()
                .put("topic", topic).put("event", "phx_join")
                .put("payload", payload).put("ref", joinRef).put("join_ref", joinRef).toString())
            scheduleHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (obj.optString("event")) {
                "phx_reply" -> if (obj.optJSONObject("payload")?.optString("status") == "ok") {
                    attempt = 0
                    onState(State.CONNECTED)
                }
                "broadcast" -> {
                    val payload = obj.optJSONObject("payload")
                    val inner = payload?.optJSONObject("payload") ?: payload
                    if (payload?.optString("event") == "companion" && inner != null) onEnvelope(inner)
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!stopped) reconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!stopped) {
                onState(State.ERROR)
                reconnect()
            }
        }
    }

    private fun scheduleHeartbeat() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (stopped) return
                socket?.send(JSONObject()
                    .put("topic", "phoenix").put("event", "heartbeat")
                    .put("payload", JSONObject()).put("ref", refs.incrementAndGet().toString())
                    .put("join_ref", JSONObject.NULL).toString())
                handler.postDelayed(this, 25_000)
            }
        }, 25_000)
    }

    private fun reconnect() {
        socket = null
        handler.removeCallbacksAndMessages(null)
        val delay = (1_000L shl attempt.coerceAtMost(5))
        attempt++
        handler.postDelayed({ if (!stopped) connect() }, delay)
    }
}
