package com.hakkiiler.voiceassistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hakkiiler.voiceassistant.data.stt.ModelSource
import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.LanguageMode
import com.hakkiiler.voiceassistant.ui.AssistantViewModel
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

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Добро пожаловать!\nWelcome!", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Для распознавания речи без интернета нужно один раз скачать языковые модели. " +
                "Дальше ассистент работает полностью офлайн.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "To understand speech offline, the assistant needs to download language models once. " +
                "After that it works completely offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!dl.running && dl.error == null) {
            Text("На каком языке будешь говорить?\nWhich language will you speak?", style = MaterialTheme.typography.titleMedium)
            val ru = ModelSource.approxBytes(Language.RU)
            val en = ModelSource.approxBytes(Language.EN)
            Button(onClick = { chosen = LanguageMode.AUTO; vm.setupLanguage(LanguageMode.AUTO) }, Modifier.fillMaxWidth()) {
                Text("Русский + English (~${mb(ru + en)} МБ / MB)")
            }
            OutlinedButton(onClick = { chosen = LanguageMode.RU; vm.setupLanguage(LanguageMode.RU) }, Modifier.fillMaxWidth()) {
                Text("Только русский (~${mb(ru)} МБ)")
            }
            OutlinedButton(onClick = { chosen = LanguageMode.EN; vm.setupLanguage(LanguageMode.EN) }, Modifier.fillMaxWidth()) {
                Text("English only (~${mb(en)} MB)")
            }
            Text(
                "Лучше через Wi-Fi. Второй язык можно докачать позже в Настройках.\n" +
                    "Wi-Fi recommended. You can add the other language later in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (dl.running) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val which = when (dl.current) {
                        Language.RU -> "русская модель / Russian model"
                        Language.EN -> "английская модель / English model"
                        null -> ""
                    }
                    Text("Скачиваю / Downloading: $which", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(progress = { dl.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${mb(dl.downloadedBytes)} / ${mb(dl.totalBytes)} МБ (${(dl.progress * 100).toInt()}%)")
                    if (dl.progress >= 0.99f) Text("Распаковываю… / Unpacking…", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { vm.cancelDownload() }) { Text("Отмена / Cancel") }
                }
            }
        }

        dl.error?.let { err ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Не удалось скачать / Download failed", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    Text(err, style = MaterialTheme.typography.bodySmall)
                    Text("Проверь интернет и попробуй снова. / Check your connection and try again.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.setupLanguage(chosen ?: LanguageMode.AUTO) }) { Text("Повторить / Retry") }
                    OutlinedButton(onClick = { chosen = null; vm.resetDownloadError() }) { Text("Выбрать другой язык / Choose another language") }
                }
            }
        }
    }
}
