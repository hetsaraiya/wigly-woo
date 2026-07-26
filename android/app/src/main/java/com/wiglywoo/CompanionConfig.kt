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
) {
    val isComplete: Boolean
        get() = supabaseUrl.startsWith("https://") && publishableKey.isNotBlank() && pairingSecret.length >= 20

    val channelId: String
        get() = sha256Hex("wigly-channel-v1|$pairingSecret").take(40)

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
                .apply()
        }

        fun deviceId(context: Context): String {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return p.getString("device_id", null) ?: UUID.randomUUID().toString().also {
                p.edit().putString("device_id", it).apply()
            }
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
