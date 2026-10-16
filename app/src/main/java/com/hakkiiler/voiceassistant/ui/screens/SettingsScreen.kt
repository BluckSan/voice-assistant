package com.hakkiiler.voiceassistant.ui.screens

import androidx.compose.material.icons.filled.Settings
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hakkiiler.voiceassistant.BuildConfig
import com.hakkiiler.voiceassistant.R
import com.hakkiiler.voiceassistant.data.image.ImageModelSource
import com.hakkiiler.voiceassistant.data.ai.AiModel
import com.hakkiiler.voiceassistant.data.ai.AiModelSource
import com.hakkiiler.voiceassistant.data.stt.ModelSource
import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.LanguageMode
import com.hakkiiler.voiceassistant.domain.model.VoiceGender
import com.hakkiiler.voiceassistant.overlay.OverlayService
import com.hakkiiler.voiceassistant.ui.AssistantViewModel
import com.hakkiiler.voiceassistant.ui.components.Banner
import com.hakkiiler.voiceassistant.ui.components.BannerTone
import com.hakkiiler.voiceassistant.ui.components.ScreenHeader
import com.hakkiiler.voiceassistant.ui.components.SectionCard
import com.hakkiiler.voiceassistant.ui.theme.Spacing
import com.hakkiiler.voiceassistant.ui.theme.ThemePrefs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var newWord by remember { mutableStateOf("") }
    val installed by vm.installedModels.collectAsStateWithLifecycle()
    val dl by vm.downloadState.collectAsStateWithLifecycle()
    val drawInstalled by vm.draw.installed.collectAsStateWithLifecycle()
    val upscaler by vm.draw.upscaler.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen).padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        ScreenHeader(stringResource(R.string.tab_settings))

        // ---- распознавание ----
        SectionCard(title = stringResource(R.string.settings_recognition), icon = painterResource(R.drawable.ic_mic)) {
            val modes = listOf(
                LanguageMode.AUTO to stringResource(R.string.lang_auto),
                LanguageMode.RU to "Русский",
                LanguageMode.EN to "English",
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (m, label) ->
                    SegmentedButton(
                        selected = s.languageMode == m,
                        onClick = { vm.setLanguageMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
            Hint(stringResource(R.string.settings_lang_hint))
            for (l in Language.entries) {
                val has = l in installed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (has) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                        tint = if (has) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(Spacing.s))
                    Column(Modifier.weight(1f)) {
                        Text(l.label, style = MaterialTheme.typography.bodyLarge)
                        val model = when {
                            has -> stringResource(R.string.settings_model_ok)
                            vm.modelsBundled -> stringResource(R.string.settings_model_missing_bundled)
                            else -> stringResource(R.string.settings_model_missing, ModelSource.approxBytes(l) / 1_000_000)
                        }
                        val tts = stringResource(if (vm.ttsHas(l)) R.string.settings_tts_ok else R.string.settings_tts_missing)
                        Text("$model · $tts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!has && !vm.modelsBundled && !dl.running) {
                        FilledTonalButton(onClick = { vm.downloadModels(listOf(l)) }) { Text(stringResource(R.string.action_download)) }
                    }
                }
            }
            if (dl.running) {
                LinearProgressIndicator(progress = { dl.progress }, modifier = Modifier.fillMaxWidth())
                Hint(stringResource(R.string.settings_downloading, dl.current?.label.orEmpty(), (dl.progress * 100).toInt()))
            }
            dl.error?.let { Banner(it, title = stringResource(R.string.download_failed), tone = BannerTone.Error) }
            val missing = s.languageMode.forcedLanguage()?.let { listOf(it) } ?: Language.entries
            if (missing.any { it !in installed }) Banner(stringResource(R.string.settings_mode_missing), tone = BannerTone.Warning)
        }

        // ---- нейросети: Whisper и собеседник ----
        SectionCard(
            title = stringResource(R.string.ai_section),
            icon = painterResource(R.drawable.ic_sparkle),
            subtitle = stringResource(R.string.ai_section_hint),
        ) {
            vm.ai.unsupportedReason?.let { Banner(it, tone = BannerTone.Warning) }
            AiModelRow(vm, AiModel.WHISPER, stringResource(R.string.ai_whisper_desc))
            HorizontalDivider()
            AiModelRow(vm, AiModel.LLM, stringResource(R.string.ai_llm_desc))
        }

        // ---- слова-активаторы ----
        SectionCard(title = stringResource(R.string.settings_wake_words), icon = rememberVectorPainter(Icons.Filled.Notifications)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.wakeWords.forEach { w ->
                    InputChip(
                        selected = false,
                        onClick = { vm.removeWakeWord(w) },
                        label = { Text(w) },
                        enabled = s.wakeWords.size > 1,
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_delete), Modifier.size(16.dp)) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newWord, { newWord = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.settings_new_word)) }, singleLine = true)
                FilledTonalButton(onClick = { vm.addWakeWord(newWord); newWord = "" }, Modifier.padding(start = Spacing.s), enabled = newWord.isNotBlank()) {
                    Text(stringResource(R.string.action_add))
                }
            }
            Hint(stringResource(R.string.settings_wake_hint))
        }

        // ---- голос ----
        SectionCard(title = stringResource(R.string.settings_voice), icon = rememberVectorPainter(Icons.Filled.Person)) {
            val genders = listOf(VoiceGender.FEMALE to stringResource(R.string.voice_female), VoiceGender.MALE to stringResource(R.string.voice_male))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                genders.forEachIndexed { i, (g, label) ->
                    SegmentedButton(s.voiceGender == g, { vm.setVoiceGender(g) }, SegmentedButtonDefaults.itemShape(i, genders.size)) { Text(label) }
                }
            }
            OutlinedButton(onClick = { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }) { Text(stringResource(R.string.settings_tts_system)) }
        }

        // ---- оформление ----
        SectionCard(title = stringResource(R.string.settings_appearance), icon = painterResource(R.drawable.ic_palette)) {
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                text = stringResource(if (ThemePrefs.supported) R.string.settings_dynamic_color_hint else R.string.settings_dynamic_color_unsupported),
                checked = ThemePrefs.dynamic.value == true,
                enabled = ThemePrefs.supported,
                onChange = { ThemePrefs.set(ctx, it) },
            )
        }

        // ---- фон и разрешения ----
        SectionCard(title = stringResource(R.string.settings_background), icon = painterResource(R.drawable.ic_apps)) {
            SwitchRow(
                title = stringResource(R.string.settings_overlay),
                text = stringResource(R.string.settings_overlay_hint),
                checked = s.overlayEnabled,
                onChange = { on ->
                    if (on && !OverlayService.canDraw(ctx)) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    } else vm.setOverlay(on)
                },
            )
            if (Build.VERSION.SDK_INT >= 31 && !vm.canScheduleExact()) {
                HorizontalDivider()
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                }) { Text(stringResource(R.string.settings_exact_alarms)) }
            }
        }

        // ---- рисование ----
        SectionCard(title = stringResource(R.string.tab_draw), icon = painterResource(R.drawable.ic_image)) {
            Text(ImageModelSource.MODEL_NAME, style = MaterialTheme.typography.bodyLarge)
            Hint(stringResource(if (drawInstalled) R.string.draw_model_installed else R.string.draw_model_not_installed))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.draw_upscaler_name), style = MaterialTheme.typography.bodyLarge)
                    Hint(stringResource(if (upscaler.installed) R.string.draw_upscaler_installed else R.string.draw_upscaler_missing))
                }
                when {
                    upscaler.running -> TextButton(onClick = { vm.draw.cancelUpscalerDownload() }) { Text(stringResource(R.string.action_cancel)) }
                    upscaler.installed -> TextButton(onClick = { vm.draw.deleteUpscaler() }) { Text(stringResource(R.string.action_delete)) }
                    else -> FilledTonalButton(onClick = { vm.draw.downloadUpscaler() }) { Text(stringResource(R.string.action_download)) }
                }
            }
            if (upscaler.running) LinearProgressIndicator(progress = { upscaler.progress }, modifier = Modifier.fillMaxWidth())
            upscaler.error?.let { Banner(it, tone = BannerTone.Error) }
            if (drawInstalled) {
                TextButton(onClick = { vm.draw.deleteModel() }) { Text(stringResource(R.string.draw_delete_model)) }
            }
        }

        AccelSection()

        // ---- о приложении ----
        SectionCard(title = stringResource(R.string.settings_about), icon = rememberVectorPainter(Icons.Filled.Info)) {
            Text(stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyLarge)
            Hint(stringResource(R.string.settings_privacy))
            Text(stringResource(R.string.settings_credits), style = MaterialTheme.typography.titleSmall)
            (listOf("Vosk — Alpha Cephei, Apache-2.0") + AiModelSource.credits + ImageModelSource.credits).forEach { Hint("• $it") }
        }
    }
}

@Composable
private fun AiModelRow(vm: AssistantViewModel, m: AiModel, description: String) {
    val st by vm.ai.state(m).collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (st.installed) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
            tint = if (st.installed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(Spacing.s))
        Column(Modifier.weight(1f)) {
            Text(m.title, style = MaterialTheme.typography.bodyLarge)
            Hint(description)
            Hint(
                if (st.installed) stringResource(R.string.ai_installed, m.file.bytes / 1_000_000)
                else stringResource(R.string.ai_size, m.file.bytes / 1_000_000),
            )
        }
        when {
            st.running -> TextButton(onClick = { vm.ai.cancelDownload(m) }) { Text(stringResource(R.string.action_cancel)) }
            st.installed -> TextButton(onClick = { vm.ai.delete(m) }) { Text(stringResource(R.string.action_delete)) }
            vm.ai.unsupportedReason == null -> FilledTonalButton(onClick = { vm.ai.download(m) }) {
                Text(stringResource(if (st.downloadedBytes > 0) R.string.ai_resume else R.string.action_download))
            }
        }
    }
    if (st.running) {
        LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
        Hint("${st.downloadedBytes / 1_000_000} / ${st.totalBytes / 1_000_000} МБ · ${(st.progress * 100).toInt()}%")
    }
    st.error?.let { Banner(it, title = stringResource(R.string.download_failed), tone = BannerTone.Error) }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SwitchRow(title: String, text: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Spacing.m)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (text != null) Hint(text)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** «Ускорение: Авто / Видеочип (Vulkan) / Процессор» + результат самопроверки. */
@Composable
private fun AccelSection() {
    val A = com.hakkiiler.voiceassistant.data.accel.Accel
    val mode by A.mode.collectAsStateWithLifecycle()
    val probe by A.probe.collectAsStateWithLifecycle()
    val probing by A.probing.collectAsStateWithLifecycle()
    val drawB by A.drawBackend.collectAsStateWithLifecycle()
    val llmB by A.llmBackend.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SectionCard(title = stringResource(R.string.accel_title), icon = rememberVectorPainter(Icons.Filled.Settings)) {
        val modes = com.hakkiiler.voiceassistant.data.accel.AccelMode.entries
        val labels = listOf(R.string.accel_auto, R.string.accel_vulkan, R.string.accel_cpu)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { A.setMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(stringResource(labels[i]), maxLines = 1) }
            }
        }
        Hint(stringResource(R.string.accel_hint, A.cpuThreads()))
        val status = when {
            probing -> stringResource(R.string.accel_probing)
            probe == null -> stringResource(R.string.accel_not_checked)
            else -> {
                val p = probe!!
                val gpu = p.gpu.ifBlank { "GPU" }
                stringResource(
                    R.string.accel_probe_result, gpu,
                    stringResource(if (p.sdOk) R.string.accel_ok else R.string.accel_bad),
                    stringResource(if (p.llmOk) R.string.accel_ok else R.string.accel_bad),
                ) + (if (p.noF16) " (без fp16)" else "")
            }
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        probe?.detail?.takeIf { it.isNotBlank() && (!probe!!.sdOk || !probe!!.llmOk) }?.let { Hint(it) }
        Hint(stringResource(R.string.accel_active, A.label(drawB), A.label(llmB)))
        TextButton(enabled = !probing, onClick = { scope.launch { A.reprobe() } }) { Text(stringResource(R.string.accel_reprobe)) }
    }
}
