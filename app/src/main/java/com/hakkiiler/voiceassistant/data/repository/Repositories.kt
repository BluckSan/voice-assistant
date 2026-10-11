package com.hakkiiler.voiceassistant.data.repository

import android.content.Context
import com.hakkiiler.voiceassistant.domain.model.ChatMessage
import com.hakkiiler.voiceassistant.domain.model.CustomCommand
import com.hakkiiler.voiceassistant.domain.model.Event
import com.hakkiiler.voiceassistant.domain.usecases.CommandStore
import com.hakkiiler.voiceassistant.domain.usecases.EventStore
import com.hakkiiler.voiceassistant.domain.usecases.ProcessVoiceCommandUseCase
import kotlinx.coroutines.CoroutineScope

class EventRepository(context: Context, scope: CoroutineScope) :
    JsonListRepository<Event>(context, scope, "events", Event.serializer()), EventStore {
    override fun currentEvents(): List<Event> = items.value
    override suspend fun add(event: Event) = update { (it + event).sortedBy { e -> e.timestamp } }
    suspend fun remove(id: String) = update { list -> list.filterNot { it.id == id } }
    suspend fun upsert(event: Event) = update { list -> (list.filterNot { it.id == event.id } + event).sortedBy { it.timestamp } }
}

class CommandRepository(context: Context, scope: CoroutineScope) :
    JsonListRepository<CustomCommand>(context, scope, "commands", CustomCommand.serializer(), DEFAULTS), CommandStore {
    override fun currentCommands(): List<CustomCommand> = items.value
    suspend fun upsert(c: CustomCommand) = update { list ->
        if (list.any { it.id == c.id }) list.map { if (it.id == c.id) c else it } else list + c
    }
    suspend fun remove(id: String) = update { list -> list.filterNot { it.id == id && !it.isSystem } }

    companion object {
        /** Системные алиасы из оригинального приложения, теперь с английскими триггерами. */
        val DEFAULTS = listOf(
            CustomCommand(ProcessVoiceCommandUseCase.SYSTEM_BANK_ID, listOf("банк", "bank", "мой банк", "my bank"), isSystem = true, title = "Банк / Bank"),
            CustomCommand(ProcessVoiceCommandUseCase.SYSTEM_BROWSER_ID, listOf("браузер", "browser", "интернет браузер"), isSystem = true, title = "Браузер / Browser"),
            CustomCommand(ProcessVoiceCommandUseCase.SYSTEM_MUSIC_ID, listOf("музыка", "музыку", "music", "плеер", "player"), isSystem = true, title = "Музыка / Music"),
        )
    }
}

class ChatRepository(context: Context, scope: CoroutineScope) :
    JsonListRepository<ChatMessage>(context, scope, "chat", ChatMessage.serializer()) {
    suspend fun add(m: ChatMessage) = update { (it + m).takeLast(MAX) }
    suspend fun clear() = update { emptyList() }

    companion object { const val MAX = 100 }
}
