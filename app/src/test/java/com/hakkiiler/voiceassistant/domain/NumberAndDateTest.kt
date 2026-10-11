package com.hakkiiler.voiceassistant.domain

import com.hakkiiler.voiceassistant.domain.parser.DateTimeParser
import com.hakkiiler.voiceassistant.domain.parser.NumberWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class NumberAndDateTest {
    private val now = LocalDateTime.of(2026, 10, 10, 14, 0) // суббота 14:00

    @Test fun numberWords() {
        assertEquals(25L, NumberWords.first("двадцать пять"))
        assertEquals(2040L, NumberWords.first("две тысячи сорок"))
        assertEquals(1000L, NumberWords.first("тысяча"))
        assertEquals(120L, NumberWords.first("one hundred twenty"))
        assertEquals(105L, NumberWords.first("one hundred and five"))
        assertEquals(listOf(2L, 3L), NumberWords.extractAll("два три"))
        assertEquals(listOf(5L, 30L), NumberWords.extractAll("пять тридцать"))
        assertEquals(42L, NumberWords.first("42"))
    }

    private fun at(text: String) = DateTimeParser.parse(text, now)?.resolve(now)

    @Test fun russianTimes() {
        assertEquals(LocalDateTime.of(2026, 10, 11, 17, 30), at("завтра в пять тридцать вечера"))
        assertEquals(LocalDateTime.of(2026, 10, 10, 20, 0), at("в восемь")) // 08:00 уже прошло → 20:00
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 0), at("в понедельник в девять утра"))
        assertEquals(LocalDateTime.of(2026, 10, 11, 13, 0), at("завтра в час дня"))
        assertEquals(now.plusMinutes(15), at("через пятнадцать минут"))
        assertEquals(now.plusHours(1), at("через час"))
        assertEquals(now.plusMinutes(30), at("через полчаса"))
        assertEquals(LocalDateTime.of(2026, 10, 10, 18, 45), at("в 18:45"))
    }

    @Test fun englishTimes() {
        assertEquals(LocalDateTime.of(2026, 10, 11, 19, 0), at("tomorrow at seven p m"))
        assertEquals(LocalDateTime.of(2026, 10, 16, 8, 15), at("on friday at eight fifteen in the morning"))
        assertEquals(now.plusHours(2), at("in two hours"))
        assertEquals(now.plusMinutes(30), at("in half an hour"))
        assertEquals(LocalDateTime.of(2026, 10, 11, 12, 0), at("tomorrow at noon"))
    }

    @Test fun dateOnlyNeedsTime() {
        val spec = DateTimeParser.parse("послезавтра", now)!!
        assertEquals(now.toLocalDate().plusDays(2), spec.date)
        assertNull(spec.resolve(now))
        assertNull(DateTimeParser.parse("купить хлеб", now))
    }
}
