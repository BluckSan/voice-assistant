package com.hakkiiler.voiceassistant.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val text: String,
    val fromUser: Boolean,
    val language: Language,
    val timestamp: Long,
)

@Serializable
data class Event(
    val id: String,
    val title: String,
    /** Время события, epoch millis. */
    val timestamp: Long,
    /** За сколько минут напомнить; 0 — в момент события. */
    val reminderMinutes: Int = 0,
)

/**
 * Пользовательская команда-алиас: фраза → приложение.
 * Системные алиасы («банк», «браузер», «музыка») создаются заранее, пользователь выбирает для них приложение.
 */
@Serializable
data class CustomCommand(
    val id: String,
    /** Фразы-триггеры (на любом языке), например ["банк", "bank"]. */
    val triggers: List<String>,
    val packageName: String? = null,
    val appLabel: String? = null,
    val isSystem: Boolean = false,
    /** Отображаемое имя системного алиаса. */
    val title: String = triggers.firstOrNull().orEmpty(),
)

data class AppInfo(val label: String, val packageName: String)

enum class VoiceGender { FEMALE, MALE }

enum class AssistantStatus {
    STOPPED, LOADING_MODELS, WAITING_WAKE_WORD, LISTENING, PROCESSING, SPEAKING, ERROR
}

data class AssistantState(
    val status: AssistantStatus = AssistantStatus.STOPPED,
    val partialText: String = "",
    val lastLanguage: Language? = null,
    val lastConfidence: Float? = null,
    val error: String? = null,
)
