package com.hakkiiler.voiceassistant.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawPresetsTest {
    @Test fun defaultIsPortrait512x768WithUpscaleTo1024x1536() {
        val f = DrawForm()
        assertEquals(DrawSize(512, 768), f.size)
        assertTrue(f.upscale)
        assertEquals(6, f.steps)
        assertEquals(1.0f, f.cfg)
        assertEquals(DrawSize(1024, 1536), DrawPresets.outputSize(f.size, f.upscale))
    }

    @Test fun sizesAreMultiplesOf64AndCoverAllOrientations() {
        DrawPresets.SIZES.forEach { assertTrue(it.label, it.width % 64 == 0 && it.height % 64 == 0) }
        DrawSize.Orientation.entries.forEach { o -> assertTrue(DrawPresets.SIZES.any { it.orientation == o }) }
        listOf(DrawSize(768, 768), DrawSize(640, 960), DrawSize(960, 640), DrawSize(768, 1152), DrawSize(1152, 768))
            .forEach { assertTrue(it.label, it in DrawPresets.SIZES) }
    }

    @Test fun biggestSizesAreFlaggedHeavy() {
        assertEquals(Load.EXTREME, DrawPresets.load(DrawSize(768, 1152)))
        assertEquals(Load.HEAVY, DrawPresets.load(DrawSize(768, 768)))
        assertEquals(Load.NORMAL, DrawPresets.load(DrawSize(512, 768)))
        assertEquals(Load.LIGHT, DrawPresets.load(DrawSize(384, 384)))
    }

    @Test fun memoryAndTimeGrowWithSize() {
        val small = DrawPresets.estimateBytes(DrawSize(512, 512), false)
        val big = DrawPresets.estimateBytes(DrawSize(768, 1152), true)
        assertTrue(big > small)
        // на 8 ГБ телефоне самый большой размер не должен требовать больше ~4.5 ГБ
        assertTrue(big < 4_600L * 1024 * 1024)
        val t1 = DrawPresets.estimateSeconds(DrawSize(512, 768), 6, 1f, true, false)
        val t2 = DrawPresets.estimateSeconds(DrawSize(512, 768), 6, 1.5f, true, false)
        val t3 = DrawPresets.estimateSeconds(DrawSize(512, 768), 6, 1f, true, true)
        assertTrue(t2.first > t1.first) // CFG > 1 удваивает шаг
        assertTrue(t3.first > t1.first) // нейро-увеличение долгое
    }
}
