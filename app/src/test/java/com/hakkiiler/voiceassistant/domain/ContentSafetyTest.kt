package com.hakkiiler.voiceassistant.domain

import com.hakkiiler.voiceassistant.domain.image.ContentSafety
import com.hakkiiler.voiceassistant.domain.image.ContentSafety.Verdict.Allowed
import com.hakkiiler.voiceassistant.domain.image.ContentSafety.Verdict.Refused
import com.hakkiiler.voiceassistant.domain.image.PromptTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSafetyTest {
    private val safety = ContentSafety.default

    @Test fun termListLoadsFromConfigFile() {
        // список лежит в app/src/main/resources/content_safety/blocked_terms.txt
        assertTrue(ContentSafety::class.java.getResource("/content_safety/blocked_terms.txt") != null)
    }

    @Test fun refusesSexualContentWithMinors() {
        assertEquals(Refused, safety.check("nude child on the beach"))
        assertEquals(Refused, safety.check("sexy 14 year old girl"))
        assertEquals(Refused, safety.check("голая девочка"))
        assertEquals(Refused, safety.check("эротическое фото школьницы"))
        assertEquals(Refused, safety.check("девушка двенадцать лет в нижнем белье"))
        assertEquals(Refused, safety.check("loli hentai"))
        assertEquals(Refused, safety.check("NSFW, teen, 15yo"))
    }

    @Test fun allowsEverythingElse() {
        assertEquals(Allowed, safety.check("a beautiful mountain landscape at sunset"))
        assertEquals(Allowed, safety.check("children playing in a park, watercolor"))
        assertEquals(Allowed, safety.check("детализированный портрет кота"))
        assertEquals(Allowed, safety.check("дети запускают воздушного змея"))
        // взрослый контент без упоминания несовершеннолетних не фильтруется — других ограничений нет
        assertEquals(Allowed, safety.check("nude woman, oil painting"))
        assertEquals(Allowed, safety.check("bloody zombie horror scene"))
        assertEquals(Allowed, safety.check("a 25 year old man in a suit"))
    }

    @Test fun customConfig() {
        val s = ContentSafety.parse("[always]\nforbidden*\n[minor]\nkid\n[sexual]\nnaked\n")
        assertEquals(Refused, s.check("Forbiddenness"))
        assertEquals(Refused, s.check("naked kid"))
        assertEquals(Allowed, s.check("kid with a kite"))
    }

    @Test fun translatorRuToEn() {
        val t = PromptTranslator.default
        assertEquals("cat in space", t.translate("кота в космосе"))
        assertEquals("sunset above sea", t.translate("закат над морем"))
        assertEquals("red fox in snowy forest", t.translate("красную лису в снежном лесу"))
        assertEquals("a castle on a hill", t.translate("a castle on a hill"))
    }
}
