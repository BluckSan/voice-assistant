package com.hakkiiler.voiceassistant.domain

import com.hakkiiler.voiceassistant.domain.model.Language.EN
import com.hakkiiler.voiceassistant.domain.model.Language.RU
import com.hakkiiler.voiceassistant.domain.model.VoiceCommand.Draw
import com.hakkiiler.voiceassistant.domain.model.VoiceCommand.OpenApp
import com.hakkiiler.voiceassistant.domain.model.VoiceCommand.QueryEvents
import com.hakkiiler.voiceassistant.domain.parser.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DrawCommandTest {
    private val p = CommandParser { LocalDateTime.of(2026, 10, 10, 12, 0) }
    private fun ru(s: String) = p.parse(s, RU)
    private fun en(s: String) = p.parse(s, EN)

    @Test fun russianDraw() {
        assertEquals(Draw("кота в космосе"), ru("нарисуй кота в космосе"))
        assertEquals(Draw("закат над морем"), ru("Нарисуй мне, пожалуйста, закат над морем"))
        assertEquals(Draw("котом в шляпе"), ru("нарисуй картинку с котом в шляпе"))
        assertEquals(Draw("дракона"), ru("можешь нарисовать дракона"))
        assertEquals(Draw("замок в горах"), ru("нарисуй-ка замок в горах"))
        assertEquals(Draw("лес зимой"), ru("сгенерируй картинку лес зимой"))
    }

    @Test fun englishDraw() {
        assertEquals(Draw("a red fox in the snow"), en("draw a red fox in the snow"))
        assertEquals(Draw("a castle on a hill"), en("Can you draw me a picture of a castle on a hill please"))
        assertEquals(Draw("a cyberpunk city at night"), en("generate an image of a cyberpunk city at night"))
        assertEquals(Draw("a cat"), en("assistant draw a cat"))
    }

    @Test fun drawWithoutDescriptionOpensScreen() {
        assertEquals(Draw(null), ru("нарисуй"))
        assertEquals(Draw(null), ru("нарисуй мне"))
        assertEquals(Draw(null), en("draw"))
        assertEquals(Draw(null), en("draw me a picture"))
    }

    @Test fun drawWinsOverOtherCommands() {
        // «план» — ключ запроса событий, «открытку» похоже на «открой»
        assertEquals(Draw("план дома"), ru("нарисуй план дома"))
        assertEquals(Draw("a music festival poster"), en("draw a music festival poster"))
    }

    @Test fun notADrawCommand() {
        assertTrue(ru("какие планы на завтра") is QueryEvents)
        assertEquals(OpenApp("draw"), en("open draw"))
        // «нарисовать» в середине вопроса — не команда
        assertTrue(ru("как нарисовать кота") !is Draw)
    }
}
