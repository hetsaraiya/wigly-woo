package com.wiglywoo.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class PointerMapTest {
    @Test fun edges() {
        assertEquals(0f, PointerMap.toPixel(0, 1080))
        assertEquals(1079f, PointerMap.toPixel(65535, 1080))
    }

    @Test fun midpoint() {
        assertEquals(539f, PointerMap.toPixel(32768, 1080), 1f)
    }
}
