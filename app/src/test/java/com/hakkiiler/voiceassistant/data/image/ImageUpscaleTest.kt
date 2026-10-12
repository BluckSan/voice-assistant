package com.hakkiiler.voiceassistant.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ImageUpscaleTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun flatColorStaysFlat() {
        val src = IntArray(8 * 6) { argb(120, 60, 200) }
        val out = ImageUpscale.upscale(src, 8, 6, 2)
        assertEquals(16 * 12, out.size)
        out.forEach { assertEquals(argb(120, 60, 200), it) }
    }

    @Test fun gradientIsPreservedAndOpaque() {
        val w = 16; val h = 4
        val src = IntArray(w * h) { i -> val v = (i % w) * 16; argb(v, v, v) }
        val out = ImageUpscale.upscale(src, w, h, 2, sharpen = 0f)
        for (y in 0 until h * 2) {
            var prev = -1
            for (x in 2 until w * 2 - 2) {
                val p = out[y * w * 2 + x]
                assertEquals(0xFF, (p ushr 24) and 0xFF)
                val v = (p shr 16) and 0xFF
                assertTrue("monotonic at $x", v >= prev - 1)
                prev = v
            }
        }
        // средняя яркость не уезжает
        val meanSrc = src.map { (it shr 16) and 0xFF }.average()
        val meanOut = out.map { (it shr 16) and 0xFF }.average()
        assertTrue(abs(meanSrc - meanOut) < 2.0)
    }
}
