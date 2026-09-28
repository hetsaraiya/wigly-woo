package com.wiglywoo.mirror

import org.json.JSONObject

data class MirrorConfig(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 60,
    val bitrate: Int = 8_000_000,
    val limit: Int = 1080,
    val codec: Int = 1,
    val flags: Int = 0,
    val component: String = "",
) {
    val audioOnly: Boolean get() = flags and 2 != 0
    val headless: Boolean get() = flags and 4 != 0
    val screenOff: Boolean get() = flags and 1 != 0
    val appDisplay: Boolean get() = flags and 8 != 0

    companion object {
        fun parse(json: String?): MirrorConfig {
            if (json.isNullOrBlank()) return MirrorConfig()
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return MirrorConfig()
            return MirrorConfig(
                width = o.optInt("width"),
                height = o.optInt("height"),
                fps = o.optInt("fps", 60),
                bitrate = o.optInt("bitrate", 8_000_000),
                limit = o.optInt("limit", 1080),
                codec = o.optInt("codec", 1),
                flags = o.optInt("flags"),
                component = o.optString("component"),
            )
        }
    }
}
