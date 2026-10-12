package com.hakkiiler.voiceassistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hakkiiler.voiceassistant.R
import com.hakkiiler.voiceassistant.domain.model.AppInfo
import com.hakkiiler.voiceassistant.domain.model.CustomCommand
import com.hakkiiler.voiceassistant.ui.AssistantViewModel
import com.hakkiiler.voiceassistant.ui.components.AppPickerDialog
import com.hakkiiler.voiceassistant.ui.components.EmptyState
import com.hakkiiler.voiceassistant.ui.components.IconBadge
import com.hakkiiler.voiceassistant.ui.components.ScreenHeader
import com.hakkiiler.voiceassistant.ui.theme.Spacing

/** Алиасы: системные («банк», «браузер», «музыка») + свои фразы → приложение. */
@Composable
fun CommandsScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val commands by vm.commands.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    var pickFor by remember { mutableStateOf<CustomCommand?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadApps() }

    CommandsContent(commands, onPick = { pickFor = it }, onDelete = { vm.deleteCommand(it) }, onCreate = { creating = true }, modifier = modifier)

    pickFor?.let { target ->
        AppPickerDialog(apps, onPick = { app ->
            vm.saveCommand(target.copy(packageName = app.packageName, appLabel = app.label)); pickFor = null
        }, onDismiss = { pickFor = null })
    }
    if (creating) NewCommandDialog(apps, onSave = { t, app -> vm.newCommand(t, app); creating = false }, onDismiss = { creating = false })
}

@Composable
fun CommandsContent(
    commands: List<CustomCommand>,
    onPick: (CustomCommand) -> Unit,
    onDelete: (CustomCommand) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val own = commands.filter { !it.isSystem }
    val system = commands.filter { it.isSystem }
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            item { ScreenHeader(stringResource(R.string.tab_commands), stringResource(R.string.commands_subtitle)) }
            item { SectionLabel(stringResource(R.string.commands_own)) }
            if (own.isEmpty()) item {
                EmptyState(painterResource(R.drawable.ic_apps), stringResource(R.string.commands_empty_title), stringResource(R.string.commands_empty_text))
            }
            items(own, key = { it.id }) { c -> CommandCard(c, onPick, onDelete) }
            if (system.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.commands_builtin)) }
                items(system, key = { it.id }) { c -> CommandCard(c, onPick, onDelete) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onCreate,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.commands_add)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.l),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandCard(c: CustomCommand, onPick: (CustomCommand) -> Unit, onDelete: (CustomCommand) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    painterResource(if (c.isSystem) R.drawable.ic_sparkle else R.drawable.ic_apps),
                    container = if (c.isSystem) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    tint = if (c.isSystem) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.size(Spacing.m))
                Text(
                    if (c.isSystem) c.title else c.triggers.firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!c.isSystem) {
                    IconButton(onClick = { onDelete(c) }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete)) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                c.triggers.forEach { t -> SuggestionChip(onClick = {}, label = { Text("«$t»") }) }
            }
            AssistChip(
                onClick = { onPick(c) },
                label = { Text(c.appLabel ?: stringResource(R.string.commands_pick_app)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_apps), null, Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun NewCommandDialog(apps: List<AppInfo>, onSave: (String, AppInfo) -> Unit, onDismiss: () -> Unit) {
    var trigger by remember { mutableStateOf("") }
    var app by remember { mutableStateOf<AppInfo?>(null) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_apps), null) },
        title = { Text(stringResource(R.string.commands_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    trigger, { trigger = it },
                    label = { Text(stringResource(R.string.commands_phrase)) },
                    supportingText = { Text(stringResource(R.string.commands_phrase_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (app == null) {
                    OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.commands_pick_app)) }
                } else {
                    FilledTonalButton(onClick = { picking = true }) { Text(app!!.label) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = trigger.isNotBlank() && app != null, onClick = { onSave(trigger, app!!) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (picking) AppPickerDialog(apps, onPick = { app = it; picking = false }, onDismiss = { picking = false })
}
