package com.hakkiiler.voiceassistant.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hakkiiler.voiceassistant.R
import com.hakkiiler.voiceassistant.data.stt.DownloadState
import com.hakkiiler.voiceassistant.data.stt.ModelSource
import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.LanguageMode
import com.hakkiiler.voiceassistant.ui.AssistantViewModel
import com.hakkiiler.voiceassistant.ui.components.Banner
import com.hakkiiler.voiceassistant.ui.components.BannerTone
import com.hakkiiler.voiceassistant.ui.components.SectionCard
import com.hakkiiler.voiceassistant.ui.theme.Spacing
import java.util.Locale

private fun mb(bytes: Long) = String.format(Locale.US, "%.0f", bytes / 1_000_000.0)

/**
 * Первый запуск lite-сборки: выбор языка и однократная загрузка моделей Vosk.
 * Тексты на двух языках — пользователь ещё не выбрал, на каком говорит.
 */
@Composable
fun ModelSetupScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val dl by vm.downloadState.collectAsStateWithLifecycle()
    var chosen by rememberSaveable { mutableStateOf<LanguageMode?>(null) }
    ModelSetupContent(
        dl = dl,
        onChoose = { chosen = it; vm.setupLanguage(it) },
        onCancel = { vm.cancelDownload() },
        onRetry = { vm.setupLanguage(chosen ?: LanguageMode.AUTO) },
        onReset = { chosen = null; vm.resetDownloadError() },
        modifier = modifier,
    )
}

@Composable
fun ModelSetupContent(
    dl: DownloadState,
    onChoose: (LanguageMode) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.primaryContainer.copy(alpha = 0.55f), c.surface, c.surface)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Spacer(Modifier.size(Spacing.l))
        Box(
            Modifier.size(88.dp).background(Brush.linearGradient(listOf(c.primary, c.secondary)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(88.dp))
        }
        Text("Добро пожаловать!\nWelcome!", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Для распознавания речи без интернета нужно один раз скачать языковые модели. " +
                "Дальше ассистент работает полностью офлайн.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "To understand speech offline, the assistant needs to download language models once. " +
                "After that it works completely offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant,
        )

        if (!dl.running && dl.error == null) {
            Text("На каком языке будешь говорить?\nWhich language will you speak?", style = MaterialTheme.typography.titleMedium)
            val ru = ModelSource.approxBytes(Language.RU)
            val en = ModelSource.approxBytes(Language.EN)
            LanguageOption("Русский + English", "Авто-определение языка · ~${mb(ru + en)} МБ / MB", primary = true) { onChoose(LanguageMode.AUTO) }
            LanguageOption("Только русский", "~${mb(ru)} МБ") { onChoose(LanguageMode.RU) }
            LanguageOption("English only", "~${mb(en)} MB") { onChoose(LanguageMode.EN) }
            Text(
                "Лучше через Wi-Fi. Второй язык можно докачать позже в Настройках.\n" +
                    "Wi-Fi recommended. You can add the other language later in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
            )
        }

        if (dl.running) {
            SectionCard(title = "Скачиваю / Downloading", icon = painterResource(R.drawable.ic_download)) {
                val which = when (dl.current) {
                    Language.RU -> "Русская модель / Russian model"
                    Language.EN -> "Английская модель / English model"
                    null -> ""
                }
                Text(which, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { dl.progress }, modifier = Modifier.fillMaxWidth())
                Text("${mb(dl.downloadedBytes)} / ${mb(dl.totalBytes)} МБ · ${(dl.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                if (dl.progress >= 0.99f) Text("Распаковываю… / Unpacking…", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onCancel) { Text("Отмена / Cancel") }
            }
        }

        dl.error?.let { err ->
            Banner(
                err + "\nПроверь интернет и попробуй снова. / Check your connection and try again.",
                title = "Не удалось скачать / Download failed",
                tone = BannerTone.Error,
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Button(onClick = onRetry) { Text("Повторить / Retry") }
                        OutlinedButton(onClick = onReset) { Text("Другой язык / Other") }
                    }
                },
            )
        }
    }
}

@Composable
private fun LanguageOption(title: String, subtitle: String, primary: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (primary) c.primary else c.surfaceContainerHigh,
        contentColor = if (primary) c.onPrimary else c.onSurface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Filled.KeyboardArrowRight, null)
        }
    }
}
