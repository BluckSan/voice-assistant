package com.hakkiiler.voiceassistant.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hakkiiler.voiceassistant.R
import com.hakkiiler.voiceassistant.domain.model.AssistantStatus
import com.hakkiiler.voiceassistant.ui.AssistantViewModel
import com.hakkiiler.voiceassistant.ui.components.ChatBubble

@Composable
fun AssistantScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(chat.size) { if (chat.isNotEmpty()) list.animateScrollToItem(chat.size - 1) }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(statusLabel(state.status), style = MaterialTheme.typography.titleMedium)
                val lang = state.lastLanguage?.let { l ->
                    "Последний язык: ${l.label}" + (state.lastConfidence?.let { " (оценка %.2f)".format(it) } ?: "")
                }
                if (lang != null) Text(lang, style = MaterialTheme.typography.bodySmall)
                if (state.partialText.isNotBlank()) Text("«${state.partialText}»", style = MaterialTheme.typography.bodyMedium)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Слушать в фоне (${settings.wakeWords.take(2).joinToString(" / ")})", Modifier.weight(1f))
                    Switch(settings.backgroundListening, onCheckedChange = { vm.setBackgroundListening(it) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("История", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.clearChat() }) { Icon(Icons.Filled.Delete, contentDescription = "Очистить") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            if (chat.isEmpty()) item {
                Text(
                    "Нажми на микрофон или скажи «${settings.wakeWords.first()}» и команду.\n" +
                        "Примеры: «открой телеграм», «сколько будет пять умножить на шесть», " +
                        "\"what time is it\", \"turn on the flashlight\", «напомни завтра в девять позвонить маме».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(chat, key = { it.id }) { ChatBubble(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f),
                placeholder = { Text("Команда текстом / type a command") }, singleLine = true,
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(onClick = { vm.sendText(input); input = "" }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить") }
            Spacer(Modifier.width(8.dp))
            LargeFloatingActionButton(onClick = { vm.listenNow() }) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = "Говорить", Modifier.size(36.dp))
            }
        }
    }
}

fun statusLabel(s: AssistantStatus) = when (s) {
    AssistantStatus.STOPPED -> "Выключен — нажми на микрофон"
    AssistantStatus.LOADING_MODELS -> "Загружаю модели RU/EN…"
    AssistantStatus.WAITING_WAKE_WORD -> "Жду слово-активатор"
    AssistantStatus.LISTENING -> "Слушаю команду…"
    AssistantStatus.PROCESSING -> "Думаю…"
    AssistantStatus.SPEAKING -> "Отвечаю"
    AssistantStatus.ERROR -> "Ошибка"
}
