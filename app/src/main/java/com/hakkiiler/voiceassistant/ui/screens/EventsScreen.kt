package com.hakkiiler.voiceassistant.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hakkiiler.voiceassistant.domain.model.Event
import com.hakkiiler.voiceassistant.ui.AssistantViewModel
import com.hakkiiler.voiceassistant.ui.components.EventDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun EventsScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val events by vm.events.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Event?>(null) }
    var creating by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm", Locale("ru")) }
    val now = System.currentTimeMillis()

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
            item {
                Text("События и напоминания", style = MaterialTheme.typography.titleLarge)
                Text("Голосом: «создай событие», «какие планы на завтра», \"remind me tomorrow at 9 am to call mom\"",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!vm.canScheduleExact()) Text("Точные будильники запрещены системой — напоминания могут приходить с задержкой (см. Настройки).",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (events.isEmpty()) item { Text("Пока пусто", Modifier.padding(top = 24.dp)) }
            items(events, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editing = e }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.titleMedium,
                                color = if (e.timestamp < now) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text(Instant.ofEpochMilli(e.timestamp).atZone(ZoneId.systemDefault()).format(fmt))
                            if (e.reminderMinutes > 0) Text("Напомню за ${e.reminderMinutes} мин", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { vm.deleteEvent(e) }) { Icon(Icons.Filled.Delete, contentDescription = "Удалить") }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { creating = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Добавить")
        }
    }
    if (creating || editing != null) {
        val initial = editing
        EventDialog(
            initial = initial,
            onSave = { t, ts, r -> vm.saveEvent(t, ts, r, initial?.id); creating = false; editing = null },
            onDismiss = { creating = false; editing = null },
        )
    }
}
