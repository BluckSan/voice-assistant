package com.hakkiiler.voiceassistant.domain.parser

import com.hakkiiler.voiceassistant.domain.model.AppInfo
import com.hakkiiler.voiceassistant.domain.model.CustomCommand

/** Поиск приложения по произнесённому названию: алиасы → сленг → нечёткое совпадение с подписью. */
object AppMatcher {
    /** Разговорные названия популярных приложений (RU и EN). */
    val slang: Map<String, String> = mapOf(
        "телега" to "org.telegram.messenger", "телегу" to "org.telegram.messenger", "телеграм" to "org.telegram.messenger",
        "телеграмм" to "org.telegram.messenger", "telegram" to "org.telegram.messenger",
        "вк" to "com.vkontakte.android", "вконтакте" to "com.vkontakte.android", "контакт" to "com.vkontakte.android",
        "vk" to "com.vkontakte.android",
        "инста" to "com.instagram.android", "инсту" to "com.instagram.android", "инстаграм" to "com.instagram.android",
        "instagram" to "com.instagram.android",
        "ватсап" to "com.whatsapp", "вацап" to "com.whatsapp", "вотсап" to "com.whatsapp", "whatsapp" to "com.whatsapp",
        "whats app" to "com.whatsapp",
        "ютуб" to "com.google.android.youtube", "ютюб" to "com.google.android.youtube", "ютубчик" to "com.google.android.youtube",
        "youtube" to "com.google.android.youtube", "you tube" to "com.google.android.youtube",
        "хром" to "com.android.chrome", "chrome" to "com.android.chrome",
        "спотифай" to "com.spotify.music", "spotify" to "com.spotify.music",
        "яндекс музыка" to "ru.yandex.music", "яндекс музыку" to "ru.yandex.music",
        "дискорд" to "com.discord", "discord" to "com.discord",
        "тик ток" to "com.zhiliaoapp.musically", "тикток" to "com.zhiliaoapp.musically", "tik tok" to "com.zhiliaoapp.musically",
        "tiktok" to "com.zhiliaoapp.musically",
        "гугл карты" to "com.google.android.apps.maps", "google maps" to "com.google.android.apps.maps",
        "maps" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm", "почту" to "com.google.android.gm", "почта" to "com.google.android.gm",
    )

    sealed interface Match {
        data class Alias(val command: CustomCommand) : Match
        data class App(val packageName: String, val label: String) : Match
        data object None : Match
    }

    fun find(query: String, aliases: List<CustomCommand>, apps: List<AppInfo>): Match {
        val q = Text.normalize(query)
        if (q.isEmpty()) return Match.None
        aliases.firstOrNull { c -> c.triggers.any { Text.normalize(it) == q } }?.let { return Match.Alias(it) }

        slang[q]?.let { pkg ->
            val app = apps.firstOrNull { it.packageName == pkg }
            return Match.App(pkg, app?.label ?: Text.capitalize(query))
        }

        val qLatin = Text.transliterate(q).replace(" ", "")
        var best: AppInfo? = null
        var bestScore = 0.0
        for (app in apps) {
            val label = Text.normalize(app.label)
            val labelLatin = Text.transliterate(label).replace(" ", "")
            val score = when {
                label == q -> 1.0
                label.startsWith(q) || q.startsWith(label) -> 0.92
                label.split(' ').any { it == q } -> 0.9
                else -> maxOf(Text.similarity(label, q), Text.similarity(labelLatin, qLatin) - 0.05)
            }
            if (score > bestScore) { bestScore = score; best = app }
        }
        return if (best != null && bestScore >= 0.72) Match.App(best.packageName, best.label) else Match.None
    }
}
