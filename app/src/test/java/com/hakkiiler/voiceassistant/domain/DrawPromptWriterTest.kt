package com.hakkiiler.voiceassistant.domain

import com.hakkiiler.voiceassistant.domain.image.DrawPromptWriter
import com.hakkiiler.voiceassistant.domain.image.PromptTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawPromptWriterTest {
    private val tr = PromptTranslator.default

    @Test fun elfFullBody() {
        val p = DrawPromptWriter.fallback("нарисуй эльфийку в полный рост", tr)
        println(p)
        assertTrue(p, p.startsWith("female elf"))
        assertTrue(p, p.contains("full body shot"))
        assertTrue(p, p.contains("highly detailed"))
        assertFalse(p.any { it in 'а'..'я' })
    }

    @Test fun moreFallbacks() {
        listOf(
            "нарисуй мне рыжую девушку с длинными волосами в лесу пожалуйста",
            "нарисуй рыцаря в доспехах с мечом на закате",
            "нарисуй кота в космосе",
        ).forEach { println(DrawPromptWriter.fallback(it, tr)) }
    }

    @Test fun strip() {
        assertEquals("эльфийку в полный рост", DrawPromptWriter.stripCommand("Нарисуй мне эльфийку в полный рост, пожалуйста"))
    }

    @Test fun llmCleaning() {
        assertEquals(
            "female elf, full body shot, forest, highly detailed, sharp focus",
            DrawPromptWriter.cleanLlmOutput("\"female elf, full body shot, forest, highly detailed, sharp focus\"\n"),
        )
        assertNull(DrawPromptWriter.cleanLlmOutput("эльфийка в полный рост"))
        assertTrue(DrawPromptWriter.cleanLlmOutput("Prompt: elf woman")!!.endsWith(DrawPromptWriter.QUALITY))
    }
}
