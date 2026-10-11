package com.hakkiiler.voiceassistant.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hakkiiler.voiceassistant.data.stt.ModelSource
import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.LanguageMode
import com.hakkiiler.voiceassistant.domain.model.VoiceGender
import com.hakkiiler.voiceassistant.overlay.OverlayService
import com.hakkiiler.voiceassistant.ui.AssistantViewModel

@Composable
fun SettingsScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var newWord by remember { mutableStateOf("") }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.titleLarge)

        Text("Язык распознавания", style = MaterialTheme.typography.titleMedium)
        val modes = listOf(LanguageMode.AUTO to "Авто RU+EN", LanguageMode.RU to "Русский", LanguageMode.EN to "English")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (m, label) ->
                SegmentedButton(
                    selected = s.languageMode == m,
                    onClick = { vm.setLanguageMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(label) }
            }
        }
        Text(
            "«Авто» — обе модели слушают одновременно, ответ на языке, на котором ты говорил. " +
                "Один язык — меньше нагрузка на процессор и чуть точнее.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val installed by vm.installedModels.collectAsStateWithLifecycle()
        val dl by vm.downloadState.collectAsStateWithLifecycle()
        for (l in Language.entries) {
            val has = l in installed
            val model = when {
                has -> "модель есть"
                vm.modelsBundled -> "НЕТ модели (scripts/download_models.sh)"
                else -> "модель не скачана (~${ModelSource.approxBytes(l) / 1_000_000} МБ)"
            }
            val tts = if (vm.ttsHas(l)) "голос есть" else "голос не установлен в системе"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${l.label}: $model, $tts", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (!has && !vm.modelsBundled && !dl.running) {
                    OutlinedButton(onClick = { vm.downloadModels(listOf(l)) }) { Text("Скачать") }
                }
            }
        }
        if (dl.running) {
            LinearProgressIndicator(progress = { dl.progress }, modifier = Modifier.fillMaxWidth())
            Text("Скачиваю модель ${dl.current?.label.orEmpty()}: ${(dl.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        dl.error?.let { Text("Ошибка загрузки: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val missing = s.languageMode.forcedLanguage()?.let { listOf(it) } ?: Language.entries
        if (missing.any { it !in installed }) {
            Text("Для выбранного режима не хватает модели — скачай её выше или выбери другой язык.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()

        Text("Слова-активаторы", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.wakeWords.forEach { w ->
                InputChip(
                    selected = false,
                    onClick = { vm.removeWakeWord(w) },
                    label = { Text(w) },
                    enabled = s.wakeWords.size > 1,
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Удалить") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newWord, { newWord = it }, Modifier.weight(1f), label = { Text("Новое слово (RU или EN)") }, singleLine = true)
            Button(onClick = { vm.addWakeWord(newWord); newWord = "" }, Modifier.padding(start = 8.dp)) { Text("Добавить") }
        }
        Text(
            "Слово должно быть в словаре модели: лучше обычные слова («компьютер», \"computer\"), а не выдуманные имена.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()

        Text("Голос ответа", style = MaterialTheme.typography.titleMedium)
        val genders = listOf(VoiceGender.FEMALE to "Женский", VoiceGender.MALE to "Мужской")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            genders.forEachIndexed { i, (g, label) ->
                SegmentedButton(s.voiceGender == g, { vm.setVoiceGender(g) }, SegmentedButtonDefaults.itemShape(i, genders.size)) { Text(label) }
            }
        }
        OutlinedButton(onClick = { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }) { Text("Системные настройки синтеза речи") }
        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Плавающая кнопка", style = MaterialTheme.typography.titleMedium)
                Text("Поверх других приложений. Также нужна, чтобы открывать приложения, когда ассистент в фоне.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(s.overlayEnabled, onCheckedChange = { on ->
                if (on && !OverlayService.canDraw(ctx)) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                } else vm.setOverlay(on)
            })
        }
        if (Build.VERSION.SDK_INT >= 31 && !vm.canScheduleExact()) {
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Разрешить точные напоминания") }
        }
        Text("Распознавание полностью офлайн (Vosk). Данные хранятся только на телефоне.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
