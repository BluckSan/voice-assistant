package com.hakkiiler.voiceassistant.domain.llm

import com.hakkiiler.voiceassistant.domain.model.Language
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Системная подсказка и подготовка истории для локальной LLM. */
object ChatPrompt {
    fun system(now: LocalDateTime, name: String = "Нори"): String {
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm, EEEE", Locale("ru")))
        return """
Ты — $name, голосовой ассистент на телефоне пользователя. Работаешь офлайн.
Отвечай на языке последнего сообщения пользователя (русский или английский), коротко и по-дружески: 1–3 предложения, без markdown, списков и эмодзи — ответ читается вслух.
Пользователь может смешивать русские и английские слова в одной фразе — это нормально.

Если пользователь просит что-то СДЕЛАТЬ на телефоне, ответь ТОЛЬКО одной строкой JSON без пояснений:
{"tool":"<имя>","args":{...}}
Инструменты:
- open_app {"name":"название приложения"}
- draw {"prompt":"описание картинки на английском"}
- set_timer {"seconds":число,"label":"текст"}
- create_event {"title":"текст","datetime":"YYYY-MM-DDTHH:MM"}
- set_volume {"percent":0-100}
- flashlight {"on":true|false}
- music {"action":"play|pause|next|previous"}
- open_settings {"panel":"general|wifi|bluetooth|display|language"}
- battery {}
Если просьба не про действие — просто ответь текстом. Не выдумывай инструменты.
Сейчас: $stamp.
""".trim()
    }

    /** Язык ответа для голоса: по преобладающему алфавиту. */
    fun languageOf(text: String, fallback: Language): Language {
        var cyr = 0
        var lat = 0
        for (c in text) {
            if (c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё') cyr++
            else if (c in 'a'..'z' || c in 'A'..'Z') lat++
        }
        return when {
            cyr == 0 && lat == 0 -> fallback
            cyr >= lat -> Language.RU
            else -> Language.EN
        }
    }

    /** Убираем то, что TTS читать не должен (markdown, эмодзи). */
    fun forSpeech(text: String): String = text
        .replace(Regex("[*_#`>]+"), "")
        .replace(Regex("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
