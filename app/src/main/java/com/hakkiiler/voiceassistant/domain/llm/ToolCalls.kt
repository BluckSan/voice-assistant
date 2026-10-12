package com.hakkiiler.voiceassistant.domain.llm

import com.hakkiiler.voiceassistant.domain.model.SettingsPanel
import com.hakkiiler.voiceassistant.domain.model.VoiceCommand
import com.hakkiiler.voiceassistant.domain.parser.DateTimeSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDateTime

/**
 * Разбор ответа нейросети: либо обычный текст, либо одна строка JSON {"tool":"…","args":{…}}.
 * Модель иногда кладёт аргументы рядом с "tool", а не в "args" — принимаем оба варианта.
 */
object ToolCalls {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** null — это не вызов инструмента (обычный ответ). */
    fun parse(reply: String): VoiceCommand? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        // JSON должен быть основным содержимым ответа, а не примером внутри рассказа
        if (reply.length - (end - start + 1) > 40) return null
        val obj = runCatching { json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        val tool = obj.str("tool") ?: obj.str("name") ?: return null
        val args: JsonObject = (obj["args"] as? JsonObject) ?: (obj["arguments"] as? JsonObject) ?: obj
        return toCommand(tool.lowercase(), args)
    }

    private fun toCommand(tool: String, a: JsonObject): VoiceCommand? = when (tool) {
        "open_app" -> a.str("name")?.let { VoiceCommand.OpenApp(it) }
        "draw" -> VoiceCommand.Draw(a.str("prompt")?.takeIf { it.isNotBlank() })
        "set_timer" -> {
            val s = a.num("seconds") ?: a.num("minutes")?.times(60)
            s?.takeIf { it > 0 }?.let { VoiceCommand.SetTimer(it.toInt(), a.str("label")) }
        }
        "create_event" -> {
            val dt = a.str("datetime")?.let { runCatching { LocalDateTime.parse(it.take(16)) }.getOrNull() }
            VoiceCommand.CreateEvent(dt?.let { DateTimeSpec(absolute = it) }, a.str("title"))
        }
        "set_volume" -> a.num("percent")?.let { VoiceCommand.VolumeSet(it.toInt().coerceIn(0, 100)) }
        "flashlight" -> VoiceCommand.Flashlight(a.bool("on") ?: true)
        "music" -> when (a.str("action")?.lowercase()) {
            "pause", "stop" -> VoiceCommand.MusicPause
            "next" -> VoiceCommand.MusicNext
            "previous", "prev" -> VoiceCommand.MusicPrevious
            else -> VoiceCommand.MusicPlay
        }
        "open_settings" -> VoiceCommand.OpenSettings(
            when (a.str("panel")?.lowercase()) {
                "wifi" -> SettingsPanel.WIFI
                "bluetooth" -> SettingsPanel.BLUETOOTH
                "display" -> SettingsPanel.DISPLAY
                "language" -> SettingsPanel.LANGUAGE
                else -> SettingsPanel.GENERAL
            },
        )
        "battery" -> VoiceCommand.Battery
        else -> null
    }

    private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonElement) as? JsonPrimitive
    private fun JsonObject.str(k: String) = prim(k)?.content?.takeIf { it != "null" }
    private fun JsonObject.num(k: String) = prim(k)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
    private fun JsonObject.bool(k: String) = prim(k)?.let { it.booleanOrNull ?: it.content.lowercase().let { s -> s == "on" || s == "true" } }
}
