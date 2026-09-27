package com.wiglywoo

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class CompanionConfig(
    val supabaseUrl: String = "",
    val publishableKey: String = "",
    val pairingSecret: String = "",
    val enabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val clipboardEnabled: Boolean = true,
    /** Name the paired Mac announced over the channel ("MacBook Pro"). */
    val peerName: String = "",
) {
    val isComplete: Boolean
        get() = supabaseUrl.startsWith("https://") && publishableKey.isNotBlank() && pairingSecret.length >= 20

    val channelId: String
        get() = sha256Hex("wigly-channel-v1|$pairingSecret").take(40)

    val macName: String get() = peerName.ifEmpty { "your Mac" }

    /** Six digits both devices derive from the secret, compared at pairing. */
    val pairingCode: String
        get() {
            val d = MessageDigest.getInstance("SHA-256").digest("wigly-pair-v1|$pairingSecret".toByteArray())
            val n = ((d[0].toLong() and 0xff) shl 24 or ((d[1].toLong() and 0xff) shl 16) or
                ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)) % 1_000_000
            val s = "%06d".format(n)
            return "${s.take(3)} ${s.takeLast(3)}"
        }

    fun normalized(): CompanionConfig = copy(supabaseUrl = supabaseUrl.trim().trimEnd('/'))

    companion object {
        private const val PREFS = "wigly_companion"

        fun load(context: Context): CompanionConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return CompanionConfig(
                supabaseUrl = p.getString("url", "") ?: "",
                publishableKey = p.getString("key", "") ?: "",
                pairingSecret = p.getString("secret", "") ?: "",
                enabled = p.getBoolean("enabled", false),
                notificationsEnabled = p.getBoolean("notifications", true),
                clipboardEnabled = p.getBoolean("clipboard", true),
                peerName = p.getString("peer_name", "") ?: "",
            )
        }

        fun save(context: Context, config: CompanionConfig) {
            val c = config.normalized()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("url", c.supabaseUrl)
                .putString("key", c.publishableKey)
                .putString("secret", c.pairingSecret)
                .putBoolean("enabled", c.enabled)
                .putBoolean("notifications", c.notificationsEnabled)
                .putBoolean("clipboard", c.clipboardEnabled)
                .putString("peer_name", c.peerName)
                .apply()
        }

        fun deviceId(context: Context): String {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return p.getString("device_id", null) ?: UUID.randomUUID().toString().also {
                p.edit().putString("device_id", it).apply()
            }
        }

        /** Parses the Mac's QR code: wiglywoo://pair?u=…&k=…&s=…&n=… */
        fun fromPairingUrl(raw: String, base: CompanionConfig): CompanionConfig? {
            val uri = runCatching { android.net.Uri.parse(raw.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "wiglywoo" || uri.host != "pair") return null
            val parsed = base.copy(
                supabaseUrl = uri.getQueryParameter("u").orEmpty(),
                publishableKey = uri.getQueryParameter("k").orEmpty(),
                pairingSecret = uri.getQueryParameter("s").orEmpty(),
                peerName = uri.getQueryParameter("n").orEmpty(),
                enabled = true,
            ).normalized()
            return parsed.takeIf { it.isComplete }
        }

        fun generateSecret(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
