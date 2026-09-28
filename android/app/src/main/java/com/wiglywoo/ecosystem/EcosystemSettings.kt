package com.wiglywoo.ecosystem

import android.content.Context

/** Risky relays stay off until the user turns them on. */
object EcosystemSettings {
    private const val PREFS = "wigly_ecosystem"

    data class Flags(
        val status: Boolean = true,
        val nowPlaying: Boolean = true,
        val screenshots: Boolean = false,
        val sms: Boolean = false,
        val calls: Boolean = false,
        val otp: Boolean = true,
        val otpType: Boolean = false,
        val focus: Boolean = false,
        val unlock: Boolean = false,
        val handoff: Boolean = true,
    )

    fun load(context: Context): Flags {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Flags(
            status = p.getBoolean("status", true),
            nowPlaying = p.getBoolean("now_playing", true),
            screenshots = p.getBoolean("screenshots", false),
            sms = p.getBoolean("sms", false),
            calls = p.getBoolean("calls", false),
            otp = p.getBoolean("otp", true),
            otpType = p.getBoolean("otp_type", false),
            focus = p.getBoolean("focus", false),
            unlock = p.getBoolean("unlock", false),
            handoff = p.getBoolean("handoff", true),
        )
    }

    fun save(context: Context, flags: Flags) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("status", flags.status)
            .putBoolean("now_playing", flags.nowPlaying)
            .putBoolean("screenshots", flags.screenshots)
            .putBoolean("sms", flags.sms)
            .putBoolean("calls", flags.calls)
            .putBoolean("otp", flags.otp)
            .putBoolean("otp_type", flags.otpType)
            .putBoolean("focus", flags.focus)
            .putBoolean("unlock", flags.unlock)
            .putBoolean("handoff", flags.handoff)
            .apply()
    }
}
