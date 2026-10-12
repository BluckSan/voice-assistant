package com.hakkiiler.voiceassistant.data.image

import org.junit.Assert.assertEquals
import org.junit.Test

class InitImageTest {
    @Test fun sizes() {
        assertEquals(DrawSize(576, 768), InitImage.targetSize(3000, 4000))   // 3:4 портрет
        assertEquals(DrawSize(768, 512), InitImage.targetSize(4000, 2250))   // 16:9 → самая узкая 512
        assertEquals(DrawSize(768, 768), InitImage.targetSize(1080, 1080))
        assertEquals(DrawSize(512, 768), InitImage.targetSize(1080, 2400))   // длинный экран → 512 + обрезка
    }

    @Test fun steps() {
        assertEquals(11, InitImage.stepsFor(6, 0.55f))
        assertEquals(7, InitImage.stepsFor(6, 0.9f))
        assertEquals(16, InitImage.stepsFor(6, 0.2f))
    }
}
