package com.hakkiiler.voiceassistant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.hakkiiler.voiceassistant.R
import com.hakkiiler.voiceassistant.domain.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Пузырь сообщения: справа — пользователь (акцентный цвет), слева — ассистент с аватаркой. */
@Composable
fun ChatBubble(m: ChatMessage) {
    val colors = MaterialTheme.colorScheme
    val time = remember(m.timestamp) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.timestamp)) }
    val shape = if (m.fromUser) {
        RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!m.fromUser) {
            IconBadge(painterResource(R.drawable.ic_sparkle), size = 30)
            Spacer(Modifier.size(8.dp))
        }
        Surface(
            shape = shape,
            color = if (m.fromUser) colors.primary else colors.surfaceContainerHigh,
            contentColor = if (m.fromUser) colors.onPrimary else colors.onSurface,
            modifier = Modifier.widthIn(max = 290.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${m.language.label} · $time",
                    style = MaterialTheme.typography.labelSmall,
                    color = (if (m.fromUser) colors.onPrimary else colors.onSurfaceVariant).copy(alpha = 0.75f),
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                )
            }
        }
    }
}

/** Что сейчас слышит ассистент — «черновой» пузырь курсивом. */
@Composable
fun PartialBubble(text: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            color = colors.primaryContainer.copy(alpha = 0.6f),
            contentColor = colors.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 290.dp),
        ) {
            Text(
                text.ifBlank { stringResource(R.string.assistant_listening_dots) },
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** Ответ нейросети, который печатается прямо сейчас (потоково), с кнопкой «Стоп». */
@Composable
fun StreamingBubble(text: String, loading: Boolean, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        IconBadge(painterResource(R.drawable.ic_sparkle), size = 30)
        Spacer(Modifier.size(8.dp))
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
            color = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
            modifier = Modifier.widthIn(max = 290.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    when {
                        loading -> stringResource(R.string.ai_loading_model)
                        text.isBlank() -> stringResource(R.string.ai_thinking)
                        else -> "$text ▍"
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontStyle = if (loading || text.isBlank()) FontStyle.Italic else FontStyle.Normal,
                    ),
                )
                if (!loading) {
                    androidx.compose.material3.TextButton(
                        onClick = onStop,
                        modifier = Modifier.align(Alignment.End),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text(stringResource(R.string.action_stop_answer), style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}
