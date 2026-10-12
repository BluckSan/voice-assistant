package com.hakkiiler.voiceassistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hakkiiler.voiceassistant.R
import com.hakkiiler.voiceassistant.domain.model.AssistantStatus
import com.hakkiiler.voiceassistant.ui.AssistantViewModel
import com.hakkiiler.voiceassistant.ui.components.Banner
import com.hakkiiler.voiceassistant.ui.components.BannerTone
import com.hakkiiler.voiceassistant.ui.components.ChatBubble
import com.hakkiiler.voiceassistant.ui.components.EmptyState
import com.hakkiiler.voiceassistant.ui.components.MicButton
import com.hakkiiler.voiceassistant.ui.components.PartialBubble
import com.hakkiiler.voiceassistant.ui.components.ScreenHeader
import com.hakkiiler.voiceassistant.ui.components.StatusPill
import com.hakkiiler.voiceassistant.ui.components.Waveform
import com.hakkiiler.voiceassistant.ui.theme.Spacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val level by vm.micLevel.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    val listening = state.status == AssistantStatus.LISTENING
    val showPartial = listening || state.partialText.isNotBlank()

    LaunchedEffect(chat.size, showPartial) {
        val last = chat.size + (if (showPartial) 1 else 0) - 1
        if (last >= 0) list.animateScrollToItem(last)
    }

    Column(modifier.fillMaxSize().padding(horizontal = Spacing.screen)) {
        ScreenHeader(
            title = stringResource(R.string.tab_assistant),
            trailing = {
                if (chat.isNotEmpty()) {
                    IconButton(onClick = { vm.clearChat() }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_clear_chat))
                    }
                }
            },
        )
        // статус + фоновое прослушивание
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(start = Spacing.m, end = Spacing.m, top = Spacing.s, bottom = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(statusLabel(state.status), statusColor(state.status))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.assistant_background), style = MaterialTheme.typography.labelLarge)
                    Text(
                        settings.wakeWords.take(2).joinToString(" / ") { "«$it»" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(Spacing.s))
                Switch(settings.backgroundListening, onCheckedChange = { vm.setBackgroundListening(it) })
            }
        }
        state.error?.let { Banner(it, Modifier.padding(top = Spacing.s), tone = BannerTone.Error) }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = Spacing.m),
        ) {
            if (chat.isEmpty() && !showPartial) item {
                Column {
                    EmptyState(
                        painterResource(R.drawable.ic_chat),
                        stringResource(R.string.assistant_empty_title),
                        stringResource(R.string.assistant_empty_text, settings.wakeWords.firstOrNull().orEmpty()),
                    )
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally),
                    ) {
                        listOf(
                            stringResource(R.string.example_1), stringResource(R.string.example_2),
                            stringResource(R.string.example_3), stringResource(R.string.example_4),
                        ).forEach { ex ->
                            SuggestionChip(onClick = { vm.sendText(ex) }, label = { Text(ex) })
                        }
                    }
                }
            }
            items(chat, key = { it.id }) { ChatBubble(it) }
            if (showPartial) item(key = "partial") { PartialBubble(state.partialText) }
        }

        AnimatedVisibility(listening, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Waveform(level, listening, Modifier.padding(horizontal = Spacing.xl))
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                input, { input = it },
                Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.assistant_input_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedBorderColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.sendText(input); input = "" }),
                trailingIcon = {
                    if (input.isNotBlank()) {
                        FilledIconButton(onClick = { vm.sendText(input); input = "" }) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send))
                        }
                    }
                },
            )
            MicButton(
                active = listening,
                level = level,
                onClick = { vm.listenNow() },
                contentDescription = stringResource(R.string.action_speak),
                size = 60.dp,
            )
        }
    }
}

@Composable
fun statusLabel(s: AssistantStatus) = stringResource(
    when (s) {
        AssistantStatus.STOPPED -> R.string.status_stopped
        AssistantStatus.LOADING_MODELS -> R.string.status_loading
        AssistantStatus.WAITING_WAKE_WORD -> R.string.status_waiting
        AssistantStatus.LISTENING -> R.string.status_listening
        AssistantStatus.PROCESSING -> R.string.status_processing
        AssistantStatus.SPEAKING -> R.string.status_speaking
        AssistantStatus.ERROR -> R.string.status_error
    },
)

@Composable
private fun statusColor(s: AssistantStatus): Color = when (s) {
    AssistantStatus.LISTENING -> MaterialTheme.colorScheme.secondary
    AssistantStatus.WAITING_WAKE_WORD -> MaterialTheme.colorScheme.primary
    AssistantStatus.PROCESSING, AssistantStatus.SPEAKING, AssistantStatus.LOADING_MODELS -> MaterialTheme.colorScheme.tertiary
    AssistantStatus.ERROR -> MaterialTheme.colorScheme.error
    AssistantStatus.STOPPED -> MaterialTheme.colorScheme.outline
}
