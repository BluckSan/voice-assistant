package com.hakkiiler.voiceassistant.domain

import com.hakkiiler.voiceassistant.domain.model.Language.EN
import com.hakkiiler.voiceassistant.domain.model.Language.RU
import com.hakkiiler.voiceassistant.domain.model.RangeKind
import com.hakkiiler.voiceassistant.domain.model.SettingsPanel
import com.hakkiiler.voiceassistant.domain.model.SmallTalk
import com.hakkiiler.voiceassistant.domain.model.VoiceCommand
import com.hakkiiler.voiceassistant.domain.model.VoiceCommand.*
import com.hakkiiler.voiceassistant.domain.parser.CommandParser
import com.hakkiiler.voiceassistant.domain.parser.MathResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CommandParserTest {
    // суббота, 10 октября 2026, 12:00
    private val now = LocalDateTime.of(2026, 10, 10, 12, 0)
    private val p = CommandParser { now }

    private fun ru(s: String) = p.parse(s, RU)
    private fun en(s: String) = p.parse(s, EN)
    private fun mathValue(c: VoiceCommand) = ((c as Math).result as MathResult.Value).value

    @Test fun openApps() {
        assertEquals(OpenApp("телеграм"), ru("открой телеграм"))
        assertEquals(OpenApp("ютуб"), ru("Запусти, пожалуйста, ютуб"))
        assertEquals(OpenApp("youtube"), en("open youtube please"))
        assertEquals(OpenApp("telegram"), en("launch the telegram app"))
    }

    @Test fun math() {
        assertEquals(5.0, mathValue(ru("сколько будет два плюс три")), 1e-9)
        assertEquals(14.0, mathValue(ru("посчитай два плюс три умножить на четыре")), 1e-9)
        assertEquals(125.0, mathValue(ru("сто плюс двадцать пять")), 1e-9)
        assertEquals(42.0, mathValue(en("what is six times seven")), 1e-9)
        assertEquals(2.5, mathValue(en("how much is five divided by two")), 1e-9)
        assertEquals(MathResult.DivisionByZero, (ru("сколько будет пять разделить на ноль") as Math).result)
        assertEquals(MathResult.Value(150.0).format(), "150")
    }

    @Test fun smallTalk() {
        assertEquals(Chat(SmallTalk.GREETING), ru("привет"))
        assertEquals(Chat(SmallTalk.HOW_ARE_YOU), ru("как дела"))
        assertEquals(Chat(SmallTalk.WHO_ARE_YOU), en("who are you"))
        assertEquals(Chat(SmallTalk.WHAT_CAN_YOU_DO), en("what can you do"))
        assertEquals(Chat(SmallTalk.GREETING), en("hello"))
    }

    @Test fun volume() {
        assertEquals(VolumeSet(50), ru("громкость пятьдесят"))
        assertEquals(VolumeSet(30), en("set volume to thirty"))
        assertEquals(VolumeUp, ru("сделай громче"))
        assertEquals(VolumeUp, ru("прибавь звук"))
        assertEquals(VolumeDown, en("turn the volume down"))
        assertEquals(VolumeUp, en("louder"))
    }

    @Test fun flashlight() {
        assertEquals(Flashlight(true), ru("включи фонарик"))
        assertEquals(Flashlight(false), ru("выключи фонарик"))
        assertEquals(Flashlight(true), en("turn on the flashlight"))
        assertEquals(Flashlight(false), en("turn off the torch"))
    }

    @Test fun batteryTimeDate() {
        assertEquals(Battery, ru("какой заряд батареи"))
        assertEquals(Battery, en("battery level"))
        assertEquals(TellTime, ru("который час"))
        assertEquals(TellTime, en("what time is it"))
        assertEquals(TellDate, ru("какое сегодня число"))
        assertEquals(TellDate, en("what's the date"))
    }

    @Test fun music() {
        assertEquals(MusicPlay, ru("включи музыку"))
        assertEquals(MusicPause, ru("выключи музыку"))
        assertEquals(MusicPause, ru("пауза"))
        assertEquals(MusicNext, ru("следующий трек"))
        assertEquals(MusicPrevious, ru("предыдущая песня"))
        assertEquals(MusicResume, ru("продолжи"))
        assertEquals(MusicPlay, en("play some music"))
        assertEquals(MusicPause, en("stop the music"))
        assertEquals(MusicNext, en("next song"))
        assertEquals(MusicPrevious, en("previous track"))
        assertEquals(MusicResume, en("resume"))
    }

    @Test fun settings() {
        assertEquals(OpenSettings(SettingsPanel.WIFI), ru("открой настройки вай фай"))
        assertEquals(OpenSettings(SettingsPanel.BLUETOOTH), ru("открой блютуз"))
        assertEquals(OpenSettings(SettingsPanel.GENERAL), ru("открой настройки"))
        assertEquals(OpenSettings(SettingsPanel.DISPLAY), en("open display settings"))
        assertEquals(OpenSettings(SettingsPanel.WIFI), en("open wi fi settings"))
    }

    @Test fun serviceCommands() {
        assertEquals(Repeat, ru("ещё"))
        assertEquals(Repeat, en("again"))
        assertEquals(Thanks, ru("спасибо"))
        assertEquals(Thanks, en("thank you"))
        assertEquals(Stop, en("stop"))
        assertEquals(ShutdownAssistant, ru("выключись"))
        assertEquals(ShutdownAssistant, en("stop listening"))
        assertEquals(ClearMemory, ru("очисти память"))
        assertEquals(ClearMemory, en("close all apps"))
        assertEquals(PowerMenu, ru("выключи телефон"))
    }

    @Test fun createEventRu() {
        val c = ru("напомни завтра в пять вечера позвонить маме") as CreateEvent
        assertEquals("Позвонить маме", c.title)
        assertEquals(LocalDateTime.of(2026, 10, 11, 17, 0), c.spec!!.resolve(now))

        val bare = ru("создай событие") as CreateEvent
        assertNull(bare.spec); assertNull(bare.title)
    }

    @Test fun createEventEn() {
        val c = en("remind me tomorrow at 9 am to call mom") as CreateEvent
        assertEquals("Call mom", c.title)
        assertEquals(LocalDateTime.of(2026, 10, 11, 9, 0), c.spec!!.resolve(now))

        val rel = en("remind me in ten minutes to check the oven") as CreateEvent
        assertEquals(now.plusMinutes(10), rel.spec!!.resolve(now))
        assertTrue(rel.title!!.contains("oven"))
    }

    @Test fun queryEvents() {
        val tomorrow = ru("какие планы на завтра") as QueryEvents
        assertEquals(LocalDate.of(2026, 10, 11), tomorrow.range.start)
        assertEquals(RangeKind.DAY, tomorrow.range.kind)

        val week = en("what are my plans next week") as QueryEvents
        assertEquals(RangeKind.NEXT_WEEK, week.range.kind)
        assertEquals(LocalDate.of(2026, 10, 12), week.range.start)

        val monday = ru("что у меня в понедельник") as QueryEvents
        assertEquals(LocalDate.of(2026, 10, 12), monday.range.start)
    }

    @Test fun unknown() {
        assertTrue(ru("абракадабра") is Unknown)
        assertTrue(en("purple elephants dancing") is Unknown)
    }

    @Test fun ignoresCaseAndPunctuation() {
        assertEquals(OpenApp("телеграм"), ru("Открой Телеграм!"))
        assertNotNull(LocalTime.NOON)
    }
}
