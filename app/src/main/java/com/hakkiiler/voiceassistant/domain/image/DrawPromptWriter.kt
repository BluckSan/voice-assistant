package com.hakkiiler.voiceassistant.domain.image

/**
 * Готовит английский запрос для SD 1.5 (CLIP понимает только английский — по русскому «эльфийка» модель
 * рисует что попало, например банку арахисовой пасты).
 * 1. Убираем командные слова («нарисуй», «мне», «пожалуйста»).
 * 2. Перевод: нейросеть Qwen по [SYSTEM] (если скачана) или словарь [PromptTranslator] + фразы вроде «в полный рост».
 * 3. Добавляем теги качества.
 */
object DrawPromptWriter {

    const val QUALITY = "highly detailed, sharp focus, professional photo, 8k"

    val SYSTEM = """
You translate picture requests from Russian (sometimes mixed with English) into prompts for Stable Diffusion 1.5.
Write ONE line of comma-separated English tags: main subject first, then the details the user gave (gender, clothing, hair, pose, place, light, style).
Translate faithfully. Do not invent framing: write "full body shot" only if the user asked for full height ("в полный рост", "во весь рост"), write "portrait" only if the user asked for a portrait.
You may add 2-3 fitting details about lighting or background.
End the line with: $QUALITY
Output ONLY the prompt. No quotes, no explanations, no Russian words.
""".trim()

    /** Примеры «запрос → промпт»: на 3B-модели они работают лучше правил (без них она везде дописывала full body). */
    val EXAMPLES = listOf(
        "девушку с косой в поле подсолнухов" to "young woman with a braid, standing in a sunflower field, summer, warm sunlight, $QUALITY",
        "воина в полный рост с топором" to "warrior holding an axe, full body shot, standing, battle-worn armor, dramatic light, $QUALITY",
        "собаку на пляже в стиле аниме" to "dog on a beach, anime style, sea waves, bright day, highly detailed, sharp focus, 8k",
        "принцессу в полный рост в замке" to "princess, young woman, full body shot, standing, in a castle hall, long gown, candlelight, $QUALITY",
    )

    private val COMMAND_WORDS = listOf(
        "нарисуй", "нарисуйте", "нарисовать", "нарисую", "сгенерируй", "сгенерировать", "создай", "сделай",
        "изобрази", "покажи", "draw", "paint", "generate", "please", "пожалуйста", "мне", "картинку", "картинка",
        "изображение", "рисунок", "фото", "фотку", "с", "можешь", "можно", "а", "ну", "давай",
    )
    private val TRAILING = listOf("пожалуйста", "please", "плиз")

    /** «нарисуй мне эльфийку в полный рост пожалуйста» → «эльфийку в полный рост». */
    fun stripCommand(text: String): String {
        var words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        while (words.isNotEmpty() && words.first().lowercase().trim(',', '.', '!', '?') in COMMAND_WORDS) words = words.drop(1)
        while (words.isNotEmpty() && words.last().lowercase().trim(',', '.', '!', '?') in TRAILING) words = words.dropLast(1)
        return words.joinToString(" ").trim(',', '.', ' ')
    }

    /** Словарный перевод + теги. */
    fun fallback(original: String, translator: PromptTranslator): String {
        val stripped = stripCommand(original)
        val english = translator.translate(stripped).ifBlank { stripped }
        return withQuality(english)
    }

    fun withQuality(p: String): String {
        val base = p.trim().trimEnd(',', '.', ' ')
        return if (base.contains("detailed", ignoreCase = true)) base else "$base, $QUALITY"
    }

    /** Ответ нейросети → одна строка запроса; null, если ответ негодный (пусто, русские буквы, болтовня). */
    fun cleanLlmOutput(raw: String): String? {
        var line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        line = line.removePrefix("Prompt:").removePrefix("prompt:").trim().trim('"', '\'', '`', '«', '»').trim()
        if (line.length < 3) return null
        if (line.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }) return null
        return withQuality(line.take(400))
    }
}
