package com.hakkiiler.voiceassistant.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hakkiiler.voiceassistant.domain.model.AppInfo
import com.hakkiiler.voiceassistant.domain.model.CustomCommand
import com.hakkiiler.voiceassistant.ui.AssistantViewModel
import com.hakkiiler.voiceassistant.ui.components.AppPickerDialog

/** Алиасы: системные («банк», «браузер», «музыка») + свои фразы → приложение. */
@Composable
fun CommandsScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val commands by vm.commands.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    var pickFor by remember { mutableStateOf<CustomCommand?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadApps() }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
            item {
                Text("Команды", style = MaterialTheme.typography.titleLarge)
                Text("Скажи фразу — откроется приложение. Несколько вариантов фразы через запятую, на любом языке.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(commands, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (c.isSystem) c.title else c.triggers.joinToString(", "), style = MaterialTheme.typography.titleMedium)
                            if (c.isSystem) Text("Фразы: " + c.triggers.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { pickFor = c }) { Text(c.appLabel ?: "Выбрать приложение") }
                        }
                        if (!c.isSystem) IconButton(onClick = { vm.deleteCommand(c) }) { Icon(Icons.Filled.Delete, contentDescription = "Удалить") }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { creating = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Добавить")
        }
    }

    pickFor?.let { target ->
        AppPickerDialog(apps, onPick = { app ->
            vm.saveCommand(target.copy(packageName = app.packageName, appLabel = app.label)); pickFor = null
        }, onDismiss = { pickFor = null })
    }
    if (creating) NewCommandDialog(apps, onSave = { t, app -> vm.newCommand(t, app); creating = false }, onDismiss = { creating = false })
}

@Composable
private fun NewCommandDialog(apps: List<AppInfo>, onSave: (String, AppInfo) -> Unit, onDismiss: () -> Unit) {
    var trigger by remember { mutableStateOf("") }
    var app by remember { mutableStateOf<AppInfo?>(null) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая команда") },
        text = {
            Column {
                OutlinedTextField(trigger, { trigger = it }, label = { Text("Фраза (напр. «такси, taxi»)") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { picking = true }, Modifier.padding(top = 8.dp)) { Text(app?.label ?: "Выбрать приложение") }
            }
        },
        confirmButton = {
            TextButton(enabled = trigger.isNotBlank() && app != null, onClick = { onSave(trigger, app!!) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    if (picking) AppPickerDialog(apps, onPick = { app = it; picking = false }, onDismiss = { picking = false })
}
