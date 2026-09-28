package com.wiglywoo.mirror

/** Maps a 0..65535 control coordinate onto a pixel span. */
object PointerMap {
    fun toPixel(norm: Int, span: Int): Float {
        if (span <= 1) return 0f
        val n = norm.coerceIn(0, 65535)
        return n.toLong() * (span - 1) / 65535f
    }
}
