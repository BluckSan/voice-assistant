package com.hakkiiler.voiceassistant.domain

import com.hakkiiler.voiceassistant.domain.lang.Hypothesis
import com.hakkiiler.voiceassistant.domain.lang.LanguageSelector
import com.hakkiiler.voiceassistant.domain.lang.WakeWordDetector
import com.hakkiiler.voiceassistant.domain.lang.WordConf
import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.LanguageMode
import com.hakkiiler.voiceassistant.domain.parser.AppMatcher
import com.hakkiiler.voiceassistant.domain.model.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageSelectionTest {
    private val selector = LanguageSelector()

    private fun hyp(lang: Language, text: String, conf: Float) =
        Hypothesis(lang, text, text.split(' ').map { WordConf(it, conf) })

    @Test fun russianSpeechWins() {
        // Русская фраза: RU-модель уверена, EN-модель выдала абракадабру
        val ru = hyp(Language.RU, "открой телеграм", 0.93f)
        val en = hyp(Language.EN, "a craig tell a gram", 0.55f)
        assertEquals(Language.RU, selector.select(listOf(ru, en), LanguageMode.AUTO)!!.language)
    }

    @Test fun englishSpeechWins() {
        val ru = hyp(Language.RU, "вот тайм из ит", 0.6f)
        val en = hyp(Language.EN, "what time is it", 0.9f)
        assertEquals(Language.EN, selector.select(listOf(ru, en), LanguageMode.AUTO)!!.language)
    }

    @Test fun keywordsBreakConfidenceTie() {
        // уверенность одинаковая, но в RU-гипотезе есть ключевые слова команды
        val ru = hyp(Language.RU, "включи фонарик", 0.8f)
        val en = hyp(Language.EN, "flu chief and a rick", 0.8f)
        assertEquals(Language.RU, selector.select(listOf(ru, en), LanguageMode.AUTO)!!.language)
    }

    @Test fun forcedModeIgnoresOtherLanguage() {
        val ru = hyp(Language.RU, "открой телеграм", 0.95f)
        val en = hyp(Language.EN, "open telegram", 0.5f)
        assertEquals(Language.EN, selector.select(listOf(ru, en), LanguageMode.EN)!!.language)
        assertNull(selector.select(listOf(ru), LanguageMode.EN))
    }

    @Test fun englishNoiseWordPenalised() {
        val ru = hyp(Language.RU, "привет", 0.7f)
        val en = hyp(Language.EN, "the", 0.75f)
        assertEquals(Language.RU, selector.select(listOf(ru, en), LanguageMode.AUTO)!!.language)
    }

    @Test fun wakeWordsBothLanguages() {
        val wake = listOf("ассистент", "assistant")
        val ruHit = WakeWordDetector.find(hyp(Language.RU, "ассистент открой ютуб", 0.9f), wake)
        assertEquals("открой ютуб", ruHit!!.remainder)
        val caseForm = WakeWordDetector.find(hyp(Language.RU, "эй ассистента", 0.9f), wake)
        assertNotNull(caseForm); assertEquals("", caseForm!!.remainder)
        val enHit = WakeWordDetector.find(hyp(Language.EN, "hey assistant what time is it", 0.9f), wake)
        assertEquals("what time is it", enHit!!.remainder)
        assertNull(WakeWordDetector.find(hyp(Language.EN, "the weather is nice", 0.9f), wake))
    }

    @Test fun appMatching() {
        val apps = listOf(AppInfo("Telegram", "org.telegram.messenger"), AppInfo("Камера", "com.android.camera"),
            AppInfo("Google Chrome", "com.android.chrome"), AppInfo("Spotify", "com.spotify.music"))
        val tg = AppMatcher.find("телегу", emptyList(), apps)
        assertTrue(tg is AppMatcher.Match.App && tg.packageName == "org.telegram.messenger")
        val cam = AppMatcher.find("камеру", emptyList(), apps)
        assertTrue(cam is AppMatcher.Match.App && cam.packageName == "com.android.camera")
        val sp = AppMatcher.find("spotify", emptyList(), apps)
        assertTrue(sp is AppMatcher.Match.App && sp.packageName == "com.spotify.music")
        assertEquals(AppMatcher.Match.None, AppMatcher.find("фотошоп", emptyList(), apps))
    }
}
