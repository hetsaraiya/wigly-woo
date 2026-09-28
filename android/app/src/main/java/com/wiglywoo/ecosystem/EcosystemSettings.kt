package com.wiglywoo.ecosystem

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Continuity toggles. Anything that reads personal data stays off until the user turns it on. */
object EcosystemSettings {
    private const val PREFS = "wigly_ecosystem"

    enum class Feature(val key: String, val default: Boolean) {
        STATUS("status", true),
        NOW_PLAYING("now_playing", true),
        OTP("otp", true),
        CALLS("calls", false),
        SMS("sms", false),
        SCREENSHOTS("screenshots", false),
        PRESENCE("presence", false),
    }

    fun enabled(context: Context, feature: Feature): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(feature.key, feature.default)

    fun set(context: Context, feature: Feature, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(feature.key, on).apply()
    }

    /** Runtime permissions a feature needs before it can run. */
    fun permissions(feature: Feature): List<String> = when (feature) {
        Feature.CALLS -> listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
        Feature.SMS -> listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
        Feature.SCREENSHOTS -> listOf(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
        Feature.PRESENCE -> if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_ADVERTISE) else emptyList()
        else -> emptyList()
    }

    fun granted(context: Context, feature: Feature): Boolean = permissions(feature).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** On, and allowed to run. */
    fun active(context: Context, feature: Feature): Boolean = enabled(context, feature) && granted(context, feature)
}
