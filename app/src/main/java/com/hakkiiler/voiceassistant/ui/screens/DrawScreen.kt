package com.hakkiiler.voiceassistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hakkiiler.voiceassistant.data.image.DrawForm
import com.hakkiiler.voiceassistant.data.image.DrawPhase
import com.hakkiiler.voiceassistant.data.image.DrawResult
import com.hakkiiler.voiceassistant.data.image.ImageModelSource
import com.hakkiiler.voiceassistant.data.image.ImageSaver
import com.hakkiiler.voiceassistant.ui.AssistantViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private fun mb(bytes: Long) = String.format(Locale.US, "%.0f", bytes / 1_000_000.0)

/**
 * «Рисование / Draw»: офлайн-генерация картинок моделью SD-Turbo через stable-diffusion.cpp.
 * Голосом: «нарисуй кота в космосе» / "draw a red fox in the snow".
 */
@Composable
fun DrawScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val draw = vm.draw
    val form by draw.form.collectAsStateWithLifecycle()
    val state by draw.state.collectAsStateWithLifecycle()
    val installed by draw.installed.collectAsStateWithLifecycle()
    val dl by draw.download.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // пока рисуем — экран не гаснет (генерация идёт на CPU около минуты)
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Рисование / Draw", style = MaterialTheme.typography.headlineSmall)

        val unsupported = draw.unsupportedReason
        if (unsupported != null) {
            ErrorCard("Рисование недоступно на этом устройстве / Not supported on this device", unsupported)
            return@Column
        }

        if (!installed) ModelCard(vm, dl.running, dl.progress, dl.downloadedBytes, dl.totalBytes, dl.error)

        OutlinedTextField(
            value = form.prompt,
            onValueChange = { v -> draw.updateForm { it.copy(prompt = v, spokenPrompt = null) } },
            label = { Text("Что нарисовать (лучше по-английски) / Prompt") },
            placeholder = { Text("a cozy cabin in a snowy forest, sunset, highly detailed") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = !state.running,
        )
        form.spokenPrompt?.let {
            Text(
                "Сказано: «$it». Модель понимает только английский, поэтому запрос переведён словарём — поправь, если нужно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = form.negativePrompt,
            onValueChange = { v -> draw.updateForm { it.copy(negativePrompt = v) } },
            label = { Text("Чего не должно быть / Negative prompt") },
            placeholder = { Text("blurry, text, watermark") },
            supportingText = { Text("Работает при CFG больше 1 / Used only when CFG > 1") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.running,
        )

        Settings(form, enabled = !state.running) { t -> draw.updateForm(t) }

        // ---- кнопки ----
        if (state.running) {
            Progress(state.phase, state.startedAt)
            OutlinedButton(onClick = { draw.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Отмена / Cancel") }
        } else {
            Button(
                onClick = {
                    draw.generate()?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                },
                enabled = installed && form.prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Нарисовать / Generate") }
        }

        (state.phase as? DrawPhase.Error)?.let { e ->
            ErrorCard("Не получилось / Failed", e.message) { TextButton(onClick = { draw.clearError() }) { Text("OK") } }
        }

        state.result?.let { r -> ResultCard(r, onSave = {
            scope.launch {
                val msg = runCatching { ImageSaver.saveToGallery(context, r.bitmap) }
                    .fold({ "Сохранено в Галерею → Pictures/VoiceAssistant" }, { "Не удалось сохранить: ${it.message}" })
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }, onShare = {
            scope.launch {
                runCatching { ImageSaver.share(context, r.bitmap, r.prompt) }
                    .onFailure { Toast.makeText(context, "Не удалось поделиться: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }) }

        Text(
            "Модель: ${ImageModelSource.MODEL_NAME}, ${ImageModelSource.LICENSE}. Powered by Stability AI. " +
                "Всё считается на телефоне, без интернета и облачных фильтров.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (installed && !state.running) {
            TextButton(onClick = { draw.deleteModel() }) { Text("Удалить модель (освободить ~2 ГБ) / Delete model") }
        }
    }
}

@Composable
private fun ModelCard(vm: AssistantViewModel, running: Boolean, progress: Float, done: Long, total: Long, error: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Нужна модель для рисования / Model required", style = MaterialTheme.typography.titleSmall)
            Text(
                "${ImageModelSource.MODEL_NAME} + TAESD, ~${mb(total)} МБ, скачивается один раз с Hugging Face. " +
                    "Лучше по Wi-Fi; нужно ~2,4 ГБ свободного места. После загрузки рисование работает офлайн.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (running || done > 0) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("${mb(done)} / ${mb(total)} МБ (${(progress * 100).toInt()}%)", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            when {
                running -> OutlinedButton(onClick = { vm.draw.cancelDownload() }) { Text("Пауза / Cancel") }
                error != null -> Button(onClick = { vm.draw.downloadModel() }) { Text("Повторить / Retry") }
                done > 0 -> Button(onClick = { vm.draw.downloadModel() }) { Text("Продолжить / Resume") }
                else -> Button(onClick = { vm.draw.downloadModel() }) { Text("Скачать / Download (~${mb(total)} МБ)") }
            }
        }
    }
}

@Composable
private fun Settings(form: DrawForm, enabled: Boolean, update: ((DrawForm) -> DrawForm) -> Unit) {
    Text("Размер / Size", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DrawForm.SIZES.forEach { s ->
            FilterChip(
                selected = form.size == s,
                onClick = { update { it.copy(size = s) } },
                label = { Text("$s×$s") },
                enabled = enabled,
            )
        }
    }
    Text(
        "SD-Turbo лучше всего рисует 512×512; 256 и 384 быстрее, но проще.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text("Шаги / Steps: ${form.steps}", style = MaterialTheme.typography.titleSmall)
    Slider(
        value = form.steps.toFloat(),
        onValueChange = { v -> update { it.copy(steps = v.roundToInt()) } },
        valueRange = DrawForm.STEPS.first.toFloat()..DrawForm.STEPS.last.toFloat(),
        steps = DrawForm.STEPS.last - DrawForm.STEPS.first - 1,
        enabled = enabled,
    )

    Text("CFG: ${String.format(Locale.US, "%.1f", form.cfg)}", style = MaterialTheme.typography.titleSmall)
    Slider(
        value = form.cfg,
        onValueChange = { v -> update { it.copy(cfg = (v * 2).roundToInt() / 2f) } },
        valueRange = 1f..4f,
        steps = 5,
        enabled = enabled,
    )
    Text(
        "1.0 — как задумано для SD-Turbo. Больше 1 включает негативный запрос, но каждый шаг вдвое дольше.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = if (form.seed < 0) "" else form.seed.toString(),
            onValueChange = { v -> update { it.copy(seed = v.filter(Char::isDigit).take(10).toLongOrNull() ?: -1) } },
            label = { Text("Seed") },
            placeholder = { Text("случайный / random") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { update { it.copy(seed = -1) } }, enabled = enabled) { Text("🎲") }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Быстрый декодер (TAESD)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Быстрее в несколько раз, детали чуть мягче / Faster, slightly softer",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = form.fastDecoder, onCheckedChange = { v -> update { it.copy(fastDecoder = v) } }, enabled = enabled)
    }
}

@Composable
private fun Progress(phase: DrawPhase, startedAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) { now = System.currentTimeMillis(); delay(500) }
    }
    val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)
    val (label, fraction) = when (phase) {
        DrawPhase.LoadingModel -> "Загружаю модель в память… / Loading model" to null
        is DrawPhase.Sampling -> "Шаг ${phase.step} из ${phase.steps} / Step" to
            (phase.step.toFloat() / phase.steps.coerceAtLeast(1))
        DrawPhase.Decoding -> "Проявляю картинку… / Decoding" to null
        else -> "" to null
    }
    Text("$label · ${seconds} с", style = MaterialTheme.typography.bodyMedium)
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ResultCard(r: DrawResult, onSave: () -> Unit, onShare: () -> Unit) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onSave() else Toast.makeText(context, "Нет разрешения на запись", Toast.LENGTH_SHORT).show()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(
                bitmap = r.bitmap.asImageBitmap(),
                contentDescription = r.prompt,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Text(
                "${r.size}×${r.size}, ${r.steps} шаг., seed ${r.seed}, ${String.format(Locale.US, "%.1f", r.millis / 1000.0)} с",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val legacy = ImageSaver.needsLegacyPermission &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    if (legacy) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else onSave()
                }) { Text("В галерею / Save") }
                OutlinedButton(onClick = onShare) { Text("Поделиться / Share") }
            }
        }
    }
}

@Composable
private fun ErrorCard(title: String, message: String, actions: (@Composable () -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            actions?.invoke()
        }
    }
}
