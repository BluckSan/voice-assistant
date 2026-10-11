package com.hakkiiler.voiceassistant.domain.parser

import com.hakkiiler.voiceassistant.domain.model.EventRange
import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.RangeKind
import com.hakkiiler.voiceassistant.domain.model.SettingsPanel
import com.hakkiiler.voiceassistant.domain.model.SmallTalk
import com.hakkiiler.voiceassistant.domain.model.VoiceCommand
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Превращает распознанную фразу в [VoiceCommand].
 * Чистый Kotlin без Android — покрыт unit-тестами.
 * Порядок проверок важен: от самых специфичных команд к «открой X» и болтовне.
 */
class CommandParser(private val now: () -> LocalDateTime = { LocalDateTime.now() }) {

    fun parse(rawText: String, lang: Language): VoiceCommand {
        val text = Text.normalize(rawText)
        if (text.isEmpty()) return VoiceCommand.Unknown(rawText)
        val k = Keywords.of(lang)
        fun has(list: List<String>) = Text.hasAny(text, list)

        // 1. короткие служебные фразы
        if (k.repeat.any { text == it.trimEnd('*') }) return VoiceCommand.Repeat
        if (has(k.thanks) && text.split(' ').size <= 4) return VoiceCommand.Thanks
        if (has(k.shutdownAssistant)) return VoiceCommand.ShutdownAssistant
        if (has(k.powerMenu)) return VoiceCommand.PowerMenu
        if (k.stop.any { text == it }) return VoiceCommand.Stop

        // 2. рисование — раньше событий и «открой»: «нарисуй план дома», «нарисуй открытку»
        parseDraw(text, lang)?.let { return it }

        // 3. события: создание раньше запроса («запланируй» содержит «план»)
        if (has(k.eventCreate)) return parseCreateEvent(text, lang)
        if (has(k.eventQuery) && !has(k.musicWord)) return VoiceCommand.QueryEvents(parseRange(text, lang))

        // 3. время и дата
        if (has(k.time)) return VoiceCommand.TellTime
        if (has(k.date)) return VoiceCommand.TellDate

        // 4. математика: триггер или просто «число оператор число»
        val math = MathEvaluator.evaluate(text)
        if (math !is MathResult.Invalid) return VoiceCommand.Math(math)
        // «посчитай пять» — явная просьба посчитать, но пример не разобрали
        if (has(k.mathTrigger) && NumberWords.extractAll(text).isNotEmpty() && !has(k.volumeWord) && !has(k.battery)) {
            return VoiceCommand.Math(math)
        }

        // 5. фонарик (до музыки и «включи X»)
        if (has(k.flashlight)) {
            val off = has(k.off)
            return VoiceCommand.Flashlight(on = !off)
        }

        // 6. громкость
        val volumeMentioned = has(k.volumeWord)
        if (volumeMentioned) {
            NumberWords.first(text)?.let { return VoiceCommand.VolumeSet(it.toInt().coerceIn(0, 100)) }
            if (has(k.volumeUp)) return VoiceCommand.VolumeUp
            if (has(k.volumeDown)) return VoiceCommand.VolumeDown
        }
        if (lang == Language.RU && (text == "громче" || text == "погромче" || text == "сделай громче")) return VoiceCommand.VolumeUp
        if (lang == Language.RU && (text == "тише" || text == "потише" || text == "сделай тише")) return VoiceCommand.VolumeDown
        if (lang == Language.EN && Text.hasAny(text, listOf("louder", "turn it up", "volume up"))) return VoiceCommand.VolumeUp
        if (lang == Language.EN && Text.hasAny(text, listOf("quieter", "turn it down", "volume down"))) return VoiceCommand.VolumeDown

        // 7. музыка
        val track = has(k.trackWord) || has(k.musicWord)
        if (track && has(k.next)) return VoiceCommand.MusicNext
        if (track && has(k.previous)) return VoiceCommand.MusicPrevious
        if (lang == Language.EN && (text == "next" || text == "skip" || text == "skip it")) return VoiceCommand.MusicNext
        if (has(k.musicWord)) {
            if (has(k.pauseVerbs)) return VoiceCommand.MusicPause
            if (has(k.playVerbs)) return VoiceCommand.MusicPlay
        }
        if (k.pauseStandalone.any { text == it }) return VoiceCommand.MusicPause
        if (k.resume.any { text == it.trimEnd('*') || (it.endsWith("*") && text.startsWith(it.dropLast(1))) }) {
            return VoiceCommand.MusicResume
        }

        // 8. батарея, память
        if (has(k.battery)) return VoiceCommand.Battery
        if (has(k.clearMemory)) return VoiceCommand.ClearMemory

        // 9. настройки
        val panel = when {
            has(k.wifi) -> SettingsPanel.WIFI
            has(k.bluetooth) -> SettingsPanel.BLUETOOTH
            has(k.display) -> SettingsPanel.DISPLAY
            has(k.languageWord) -> SettingsPanel.LANGUAGE
            else -> null
        }
        if (has(k.settingsWord)) return VoiceCommand.OpenSettings(panel ?: SettingsPanel.GENERAL)
        if (panel != null && has(k.openVerbs)) return VoiceCommand.OpenSettings(panel)

        // 10. открыть приложение
        val verb = k.openVerbs.sortedByDescending { it.length }.firstOrNull { Text.matches(text, it) }
        if (verb != null) {
            val name = Text.removeAll(Text.removeAll(text, listOf(verb)), k.fillers)
            if (name.isNotBlank()) return VoiceCommand.OpenApp(name)
        }

        // 11. болтовня
        if (has(k.whatCanYouDo)) return VoiceCommand.Chat(SmallTalk.WHAT_CAN_YOU_DO)
        if (has(k.whoAreYou)) return VoiceCommand.Chat(SmallTalk.WHO_ARE_YOU)
        if (has(k.howAreYou)) return VoiceCommand.Chat(SmallTalk.HOW_ARE_YOU)
        if (has(k.greeting)) return VoiceCommand.Chat(SmallTalk.GREETING)
        if (has(k.thanks)) return VoiceCommand.Thanks
        if (has(k.stop) && text.split(' ').size <= 2) return VoiceCommand.Stop

        return VoiceCommand.Unknown(rawText)
    }

    /**
     * «нарисуй мне кота в космосе» → Draw("кота в космосе"), "can you draw a red fox please" → Draw("a red fox").
     * Описание — всё после глагола, без вежливых слов в начале и конце.
     */
    fun parseDraw(text: String, lang: Language): VoiceCommand.Draw? {
        val k = Keywords.of(lang)
        val padded = " $text "
        val verb = k.draw.sortedByDescending { it.length }
            .mapNotNull { v -> padded.indexOf(" $v ").takeIf { it >= 0 }?.let { v to it } }
            .minByOrNull { it.second } ?: return null
        // глагол должен стоять в начале фразы (после вежливых слов): «как нарисовать кота» в середине вопроса — не команда
        val before = Text.removeAll(text.substring(0, verb.second).trim(), k.drawFillers + k.fillers + WAKE_WORDS)
        if (before.isNotBlank()) return null
        var rest = text.substring(minOf(text.length, verb.second + verb.first.length)).trim()
        val fillers = k.drawFillers.sortedByDescending { it.length }
        // срезаем служебные слова в начале («мне картинку с …») и вежливость в конце («… пожалуйста»)
        var changed = true
        while (changed && rest.isNotEmpty()) {
            changed = false
            for (f in fillers) {
                if (rest == f) { rest = ""; changed = true; break }
                if (rest.startsWith("$f ")) { rest = rest.removePrefix("$f ").trim(); changed = true; break }
            }
        }
        for (tail in TAIL_FILLERS) if (rest.endsWith(" $tail")) rest = rest.removeSuffix(" $tail").trim() else if (rest == tail) rest = ""
        if (lang == Language.RU) rest = rest.removePrefix("с ").removePrefix("со ").trim()
        return VoiceCommand.Draw(rest.ifBlank { null })
    }

    private fun parseCreateEvent(text: String, lang: Language): VoiceCommand.CreateEvent {
        val spec = DateTimeParser.parse(text, now())
        val k = Keywords.of(lang)
        val tokens = Text.tokens(text)
        val rest = tokens.filterIndexed { i, _ -> spec == null || i !in spec.consumed }.joinToString(" ")
        val title = Text.removeAll(rest, k.eventFillers).trim()
        return VoiceCommand.CreateEvent(spec?.takeIf { !it.isEmpty }, title.ifBlank { null }?.let(Text::capitalize))
    }

    private companion object {
        val TAIL_FILLERS = listOf("пожалуйста", "please", "for me")
        val WAKE_WORDS = listOf("ассистент", "помощник", "assistant", "hey", "ok", "okay", "эй", "слушай")
    }

    fun parseRange(text: String, lang: Language): EventRange {
        val k = Keywords.of(lang)
        val today = now().toLocalDate()
        if (Text.hasAny(text, k.week)) {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return if (Text.hasAny(text, k.nextWeek)) {
                EventRange(monday.plusWeeks(1), monday.plusWeeks(1).plusDays(6), RangeKind.NEXT_WEEK)
            } else {
                EventRange(monday, monday.plusDays(6), RangeKind.THIS_WEEK)
            }
        }
        val day = DateTimeParser.parse(text, now())?.date ?: today
        return EventRange(day, day, RangeKind.DAY)
    }
}
