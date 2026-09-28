package com.wiglywoo.ecosystem

import com.wiglywoo.CompanionManager
import org.json.JSONObject

/** Pulls a one-time code out of an SMS or a notification. */
object OtpDetector {
    private val code = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")
    private val hint = Regex("(?i)(code|otp|passcode|verification|verify|2fa|登录|验证码)")

    fun consider(source: String, text: String) {
        if (!hint.containsMatchIn(text) && !text.contains("G-")) return
        val match = code.find(text)?.groupValues?.getOrNull(1) ?: return
        CompanionManager.send(JSONObject()
            .put("type", "otp")
            .put("code", match)
            .put("source", source))
    }
}
