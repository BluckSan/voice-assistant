package com.hakkiiler.voiceassistant.domain.llm

import com.hakkiiler.voiceassistant.data.ai.AiController
import com.hakkiiler.voiceassistant.domain.model.ChatMessage
import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.VoiceCommand
import com.hakkiiler.voiceassistant.domain.parser.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ToolCallsTest {
    @Test fun nestedArgs() {
        assertEquals(VoiceCommand.OpenApp("Telegram"), ToolCalls.parse("""{"tool":"open_app","args":{"name":"Telegram"}}"""))
    }

    // так отвечала Qwen2.5-3B на хосте: аргументы рядом с "tool"
    @Test fun flatArgs() {
        assertEquals(VoiceCommand.SetTimer(300, null), ToolCalls.parse("""{"tool":"set_timer","seconds":300}"""))
        assertEquals(VoiceCommand.Flashlight(true), ToolCalls.parse("""{"tool":"flashlight","on":true}"""))
        assertEquals(VoiceCommand.Draw("kitty cat in space"), ToolCalls.parse("""{"tool":"draw","prompt":"kitty cat in space"}"""))
    }

    @Test fun createEvent() {
        val c = ToolCalls.parse("""{"tool":"create_event","title":"Звонок маме","datetime":"2026-10-13T09:00"}""") as VoiceCommand.CreateEvent
        assertEquals("Звонок маме", c.title)
        assertEquals(LocalDateTime.of(2026, 10, 13, 9, 0), c.spec?.absolute)
    }

    @Test fun plainTextIsNotTool() {
        assertNull(ToolCalls.parse("Небо голубое из-за рассеяния света."))
        assertNull(ToolCalls.parse("""Пример JSON: {"a":1} — это объект, который используется в программировании для хранения данных."""))
        assertNull(ToolCalls.parse("""{"tool":"hack_the_planet"}"""))
    }

    @Test fun replyLanguage() {
        assertEquals(Language.RU, ChatPrompt.languageOf("поставь reminder на завтра", Language.EN))
        assertEquals(Language.EN, ChatPrompt.languageOf("open Telegram please", Language.RU))
    }

    @Test fun timerRules() {
        val p = CommandParser()
        assertEquals(VoiceCommand.SetTimer(300, null), p.parse("поставь таймер на пять минут", Language.RU))
        assertEquals(VoiceCommand.SetTimer(90, null), p.parse("таймер на 1 минуту 30 секунд", Language.RU))
        assertEquals(VoiceCommand.SetTimer(1800, null), p.parse("таймер на полчаса", Language.RU))
        assertEquals(VoiceCommand.SetTimer(600, null), p.parse("set a timer for ten minutes", Language.EN))
    }

    @Test fun utf8Streaming() {
        val b = "Привет".toByteArray()
        assertEquals("При", AiController.decodeComplete(b.copyOf(7)))
        assertEquals("Привет", AiController.decodeComplete(b))
    }

    @Test fun historyTrim() {
        val msgs = (1..10).map { ChatMessage("$it", "x".repeat(100), fromUser = it % 2 == 1, language = Language.RU, timestamp = 0) }
        val t = AiController.trimHistory(msgs, 350)
        assertTrue(t.first().fromUser)
        assertTrue(t.size in 2..4)
        assertEquals("10", t.last().id)
    }
}
