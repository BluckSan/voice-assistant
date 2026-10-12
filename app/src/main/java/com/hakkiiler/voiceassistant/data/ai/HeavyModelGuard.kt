package com.hakkiiler.voiceassistant.data.ai

/**
 * Модели, которые вместе не помещаются в память телефона: SD (~1.8–3 ГБ), LLM (~2.6 ГБ), Whisper (~0.45 ГБ).
 * Правило: рисование и LLM одновременно не загружены. Перед рисованием выгружаем LLM и Whisper;
 * перед загрузкой LLM выгружаем SD (если идёт рисование — LLM ждёт/отказывает).
 * Связи задаёт AppContainer.
 */
object HeavyModelGuard {
    @Volatile var unloadChat: (suspend () -> Unit)? = null
    @Volatile var releaseDraw: (suspend () -> Boolean)? = null

    suspend fun beforeDraw() {
        unloadChat?.invoke()
    }

    /** @return false — рисование занято, LLM грузить нельзя. */
    suspend fun beforeChat(): Boolean = releaseDraw?.invoke() ?: true
}
