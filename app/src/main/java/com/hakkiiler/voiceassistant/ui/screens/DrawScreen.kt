package com.hakkiiler.voiceassistant.ui.screens

import android.Manifest
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.shape.RoundedCornerShape
import com.hakkiiler.voiceassistant.ui.components.StatusPill
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hakkiiler.voiceassistant.R
import com.hakkiiler.voiceassistant.data.image.DrawForm
import com.hakkiiler.voiceassistant.data.image.DrawPhase
import com.hakkiiler.voiceassistant.data.image.DrawPresets
import com.hakkiiler.voiceassistant.data.image.DrawResult
import com.hakkiiler.voiceassistant.data.image.DrawSize
import com.hakkiiler.voiceassistant.data.image.DrawState
import com.hakkiiler.voiceassistant.data.image.GalleryItem
import com.hakkiiler.voiceassistant.data.image.ImageModelDownloadState
import com.hakkiiler.voiceassistant.data.image.ImageModelSource
import com.hakkiiler.voiceassistant.data.image.ImageSaver
import com.hakkiiler.voiceassistant.data.image.Load
import com.hakkiiler.voiceassistant.data.image.UpscalerState
import com.hakkiiler.voiceassistant.ui.AssistantViewModel
import com.hakkiiler.voiceassistant.ui.components.Banner
import com.hakkiiler.voiceassistant.ui.components.BannerTone
import com.hakkiiler.voiceassistant.ui.components.IconBadge
import com.hakkiiler.voiceassistant.ui.components.ScreenHeader
import com.hakkiiler.voiceassistant.ui.components.SectionCard
import com.hakkiiler.voiceassistant.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private fun mb(bytes: Long) = String.format(Locale.US, "%.0f", bytes / 1_000_000.0)

/** Действия экрана — отдельно от ViewModel, чтобы экран можно было отрисовать в превью/скриншот-тестах. */
class DrawActions(
    val update: ((DrawForm) -> DrawForm) -> Unit = {},
    val generate: () -> String? = { null },
    val cancel: () -> Unit = {},
    val clearError: () -> Unit = {},
    val download: () -> Unit = {},
    val cancelDownload: () -> Unit = {},
    val downloadUpscaler: () -> Unit = {},
    val openGallery: (GalleryItem) -> Unit = {},
    val deleteGallery: (GalleryItem) -> Unit = {},
    val save: (DrawResult) -> Unit = {},
    val share: (DrawResult) -> Unit = {},
    val pickPhoto: () -> Unit = {},
    val takePhoto: () -> Unit = {},
    val clearSource: () -> Unit = {},
    val pickFile: () -> Unit = {},
    val shareLog: () -> Unit = {},
    val dismissSourceError: () -> Unit = {},
)

/**
 * «Рисование / Draw»: офлайн-генерация картинок (Realistic Vision V6 + LCM-LoRA) через stable-diffusion.cpp.
 * Голосом: «нарисуй кота в космосе» / "draw a red fox in the snow".
 */
@Composable
fun DrawScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val draw = vm.draw
    val form by draw.form.collectAsStateWithLifecycle()
    val state by draw.state.collectAsStateWithLifecycle()
    val installed by draw.installed.collectAsStateWithLifecycle()
    val dl by draw.download.collectAsStateWithLifecycle()
    val upscaler by draw.upscaler.collectAsStateWithLifecycle()
    val gallery by draw.gallery.collectAsStateWithLifecycle()
    val source by draw.source.collectAsStateWithLifecycle()
    val sourceError by draw.sourceError.collectAsStateWithLifecycle()
    val sourceInfo by draw.sourceInfo.collectAsStateWithLifecycle()
    val sourceLoading by draw.sourceLoading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // img2img: выбор фото (системный photo picker, разрешения не нужны) или снимок камерой во временный файл
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) draw.setSource(uri) else com.hakkiiler.voiceassistant.data.image.PickLog.i(context, "picker: cancelled / no uri")
    }
    // запасной путь: системный выбор файлов (если photo picker недоступен или сломан в прошивке)
    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) draw.setSource(uri) else com.hakkiiler.voiceassistant.data.image.PickLog.i(context, "GET_CONTENT: cancelled / no uri")
    }
    // rememberSaveable: пока открыта камера, Android может пересоздать экран — адрес снимка не должен потеряться
    var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        com.hakkiiler.voiceassistant.data.image.PickLog.i(context, "camera result ok=$ok uri=$u")
        if (ok && u != null) draw.setSource(u)
    }

    val scope = rememberCoroutineScope()

    // пока рисуем — экран не гаснет (генерация идёт на CPU несколько минут)
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    val savedMsg = stringResource(R.string.draw_saved)
    val saveFailed = stringResource(R.string.draw_save_failed)
    val shareFailed = stringResource(R.string.draw_share_failed)
    val noPermission = stringResource(R.string.draw_no_permission)
    var pendingSave by remember { mutableStateOf<DrawResult?>(null) }
    val doSave: (DrawResult) -> Unit = { r ->
        scope.launch {
            val msg = runCatching { ImageSaver.saveToGallery(context, r.bitmap) }.fold({ savedMsg }, { "$saveFailed: ${it.message}" })
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val r = pendingSave
        if (ok && r != null) doSave(r) else Toast.makeText(context, noPermission, Toast.LENGTH_SHORT).show()
    }

    DrawContent(
        form = form,
        state = state,
        installed = installed,
        download = dl,
        upscaler = upscaler,
        gallery = gallery,
        source = source,
        sourceInfo = sourceInfo,
        sourceLoading = sourceLoading,
        sourceError = sourceError,
        unsupportedReason = draw.unsupportedReason,
        plan = remember(form, upscaler.installed, state.running) { draw.planFor(form) },
        actions = DrawActions(
            update = { draw.updateForm(it) },
            generate = { draw.generate()?.also { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } },
            cancel = { draw.cancel() },
            clearError = { draw.clearError() },
            download = { draw.downloadModel() },
            cancelDownload = { draw.cancelDownload() },
            downloadUpscaler = { draw.downloadUpscaler() },
            openGallery = { draw.openFromGallery(it) },
            deleteGallery = { draw.deleteFromGallery(it) },
            save = { r ->
                val legacy = ImageSaver.needsLegacyPermission &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                if (legacy) { pendingSave = r; permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) } else doSave(r)
            },
            pickPhoto = {
                com.hakkiiler.voiceassistant.data.image.PickLog.header(context)
                try {
                    picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (e: Exception) {
                    draw.reportPickError("Фото-пикер не запустился, пробую выбор файлов", e)
                    runCatching { getContent.launch("image/*") }.onFailure { draw.reportPickError("Выбор файлов не запустился", it) }
                }
            },
            pickFile = {
                com.hakkiiler.voiceassistant.data.image.PickLog.header(context)
                runCatching { getContent.launch("image/*") }.onFailure { draw.reportPickError("Выбор файлов не запустился", it) }
            },
            shareLog = {
                runCatching {
                    val f = com.hakkiiler.voiceassistant.data.image.PickLog.file(context)
                    if (!f.exists()) f.writeText("empty\n")
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                    context.startActivity(
                        android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            null,
                        ),
                    )
                }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            },
            dismissSourceError = { draw.consumeSourceError() },
            takePhoto = {
                runCatching {
                    val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
                    val file = java.io.File(dir, "photo.jpg")
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    cameraUri = uri
                    com.hakkiiler.voiceassistant.data.image.PickLog.header(context)
                    camera.launch(uri)
                }.onFailure { draw.reportPickError("Камера не запустилась", it) }
            },
            clearSource = { draw.clearSource() },
            share = { r ->
                scope.launch {
                    runCatching { ImageSaver.share(context, r.bitmap, r.prompt) }
                        .onFailure { Toast.makeText(context, "$shareFailed: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            },
        ),
        modifier = modifier,
    )
}

@Composable
fun DrawContent(
    form: DrawForm,
    state: DrawState,
    installed: Boolean,
    download: ImageModelDownloadState,
    upscaler: UpscalerState,
    gallery: List<GalleryItem>,
    unsupportedReason: String?,
    source: Bitmap? = null,
    sourceInfo: com.hakkiiler.voiceassistant.data.image.SourceInfo? = null,
    sourceLoading: Boolean = false,
    sourceError: String? = null,
    actions: DrawActions,
    modifier: Modifier = Modifier,
    plan: DrawPresets.Plan = DrawPresets.plan(
        form.size, form.steps, form.cfg, form.fastDecoder, form.upscale, form.neuralUpscale && upscaler.installed,
    ),
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen).padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        ScreenHeader(stringResource(R.string.tab_draw), stringResource(R.string.draw_subtitle))

        if (unsupportedReason != null) {
            Banner(unsupportedReason, title = stringResource(R.string.draw_unsupported), tone = BannerTone.Error)
            return@Column
        }

        if (!installed) ModelDownloadCard(download, actions)

        PreviewCard(form, state, actions)

        if (gallery.isNotEmpty()) GalleryStrip(gallery, state.result?.file, enabled = !state.running, actions)

        SourceCard(form, source, sourceInfo, sourceLoading, sourceError, enabled = !state.running, actions)

        PromptCard(form, state, installed, actions)

        // для img2img размер задаёт фото
        if (source == null) SizeCard(form, plan, upscaler, enabled = !state.running, actions)

        AdvancedCard(form, enabled = !state.running, actions)

        Text(
            stringResource(R.string.draw_footer, ImageModelSource.MODEL_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------- загрузка модели (первый запуск рисования) ----------------

@Composable
private fun ModelDownloadCard(dl: ImageModelDownloadState, actions: DrawActions) {
    SectionCard(
        title = stringResource(R.string.draw_model_title),
        subtitle = stringResource(R.string.draw_model_size, mb(dl.totalBytes)),
        icon = painterResource(R.drawable.ic_download),
        container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
    ) {
        Text(stringResource(R.string.draw_model_text), style = MaterialTheme.typography.bodyMedium)
        val started = dl.running || dl.downloadedBytes > 0
        if (dl.merging) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Hint(stringResource(R.string.draw_model_merging))
        } else if (started) {
            LinearProgressIndicator(progress = { dl.progress }, modifier = Modifier.fillMaxWidth())
            Hint(stringResource(R.string.draw_model_progress, mb(dl.downloadedBytes), mb(dl.totalBytes), (dl.progress * 100).toInt()))
        }
        dl.error?.let { Banner(it, title = stringResource(R.string.download_failed), tone = BannerTone.Error) }
        when {
            dl.merging -> {}
            dl.running -> OutlinedButton(onClick = actions.cancelDownload) { Text(stringResource(R.string.action_pause)) }
            dl.error != null -> Button(onClick = actions.download) { Text(stringResource(R.string.action_retry)) }
            dl.downloadedBytes > 0 -> Button(onClick = actions.download) { Text(stringResource(R.string.action_resume)) }
            else -> Button(onClick = actions.download, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.s))
                Text(stringResource(R.string.draw_model_download, mb(dl.totalBytes)))
            }
        }
    }
}

// ---------------- превью / прогресс / результат ----------------

@Composable
private fun PreviewCard(form: DrawForm, state: DrawState, actions: DrawActions) {
    val result = state.result
    val ratio = when {
        state.running -> form.size.width.toFloat() / form.size.height
        result != null -> result.width.toFloat() / result.height
        else -> form.size.width.toFloat() / form.size.height
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                    .align(Alignment.CenterHorizontally).clip(MaterialTheme.shapes.extraLarge),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.running -> RunningPreview(state)
                    result != null && result.source != null -> BeforeAfter(result.source, result.bitmap)
                    result != null -> Image(
                        result.bitmap.asImageBitmap(), contentDescription = result.prompt,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                    else -> EmptyPreview()
                }
            }
            val error = state.phase as? DrawPhase.Error
            if (error != null) {
                Banner(
                    error.message, title = stringResource(R.string.draw_failed), tone = BannerTone.Error,
                    modifier = Modifier.padding(Spacing.m),
                    actions = { TextButton(onClick = actions.clearError) { Text("OK") } },
                )
            }
            if (result != null && !state.running) ResultActions(result, actions)
            if (state.running) {
                OutlinedButton(
                    onClick = actions.cancel,
                    enabled = state.phase != DrawPhase.Cancelling,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.m),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

@Composable
private fun EmptyPreview() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            IconBadge(painterResource(R.drawable.ic_image), size = 64, container = MaterialTheme.colorScheme.surfaceContainerHighest)
            Text(stringResource(R.string.draw_empty_preview), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RunningPreview(state: DrawState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAt) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val seconds = ((now - state.startedAt) / 1000).coerceAtLeast(0)
    val shimmer by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "s",
    )
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                listOf(c.primaryContainer.copy(alpha = 0.35f), c.secondaryContainer.copy(alpha = 0.7f), c.primaryContainer.copy(alpha = 0.35f)),
                start = androidx.compose.ui.geometry.Offset(shimmer * 1600f - 600f, 0f),
                end = androidx.compose.ui.geometry.Offset(shimmer * 1600f, 900f),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        val phase = state.phase
        val (label, fraction) = when (phase) {
            DrawPhase.Translating -> stringResource(R.string.draw_phase_translating) to null
            DrawPhase.Cancelling -> stringResource(R.string.draw_phase_cancelling) to null
            DrawPhase.LoadingModel -> stringResource(R.string.draw_phase_loading) to null
            is DrawPhase.Sampling -> stringResource(R.string.draw_phase_step, phase.step, phase.steps) to
                (phase.step.toFloat() / phase.steps.coerceAtLeast(1))
            DrawPhase.Decoding -> stringResource(R.string.draw_phase_decoding) to null
            DrawPhase.Upscaling -> stringResource(R.string.draw_phase_upscaling) to null
            else -> "" to null
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Box(contentAlignment = Alignment.Center) {
                if (fraction != null) {
                    CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(84.dp), strokeWidth = 6.dp, trackColor = c.surfaceContainerHighest)
                    Text("${(fraction * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
                } else {
                    CircularProgressIndicator(Modifier.size(84.dp), strokeWidth = 6.dp, trackColor = c.surfaceContainerHighest)
                }
            }
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.draw_elapsed, formatClock(seconds)), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

private fun formatClock(sec: Long) = "%d:%02d".format(sec / 60, sec % 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultActions(r: DrawResult, actions: DrawActions) {
    Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(r.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaChip("${r.width}×${r.height}")
            if (r.steps > 0) MetaChip(stringResource(R.string.draw_meta_steps, r.steps))
            if (r.seed >= 0) MetaChip("seed ${r.seed}")
            if (r.millis > 0) MetaChip(formatClock(r.millis / 1000))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Button(onClick = { actions.save(r) }, modifier = Modifier.weight(1f)) {
                Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.s))
                Text(stringResource(R.string.action_save_gallery))
            }
            FilledTonalButton(onClick = { actions.share(r) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.s))
                Text(stringResource(R.string.action_share))
            }
        }
        if (r.seed >= 0) {
            TextButton(onClick = { actions.update { it.copy(seed = r.seed, prompt = r.prompt) } }) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.s))
                Text(stringResource(R.string.draw_reuse_seed))
            }
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs))
    }
}

// ---------------- лента последних работ ----------------

@Composable
private fun GalleryStrip(items: List<GalleryItem>, current: File?, enabled: Boolean, actions: DrawActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.draw_recent), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val selected = items.firstOrNull { it.file == current }
            if (selected != null && enabled) {
                IconButton(onClick = { actions.deleteGallery(selected) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), contentPadding = PaddingValues(end = Spacing.s)) {
            items(items, key = { it.file.path }) { item ->
                val thumb = rememberThumbnail(item.file)
                val selected = item.file == current
                Box(
                    Modifier.size(76.dp).clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .then(if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), MaterialTheme.shapes.medium) else Modifier)
                        .clickable(enabled = enabled) { actions.openGallery(item) },
                ) {
                    thumb?.let { Image(it.asImageBitmap(), item.prompt, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }
        }
    }
}

@Composable
private fun rememberThumbnail(file: File): Bitmap? = produceState<Bitmap?>(null, file) {
    value = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 200) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}.value

// ---------------- запрос ----------------

@Composable
private fun PromptCard(form: DrawForm, state: DrawState, installed: Boolean, actions: DrawActions) {
    SectionCard(title = stringResource(R.string.draw_prompt_title), icon = painterResource(R.drawable.ic_sparkle)) {
        OutlinedTextField(
            value = form.prompt,
            onValueChange = { v -> actions.update { it.copy(prompt = v, spokenPrompt = null, translatedPrompt = null) } },
            placeholder = { Text(stringResource(R.string.draw_prompt_placeholder)) },
            supportingText = { Text(stringResource(R.string.draw_prompt_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            enabled = !state.running,
            shape = MaterialTheme.shapes.medium,
        )
        form.spokenPrompt?.let { Banner(stringResource(R.string.draw_spoken, it), tone = BannerTone.Info) }
        form.translatedPrompt?.let { Banner(it, title = stringResource(R.string.draw_translated), tone = BannerTone.Info) }
        Button(
            onClick = { actions.generate() },
            enabled = installed && form.prompt.isNotBlank() && !state.running,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(20.dp))
            Spacer(Modifier.size(Spacing.s))
            val out = DrawPresets.outputSize(form.size, form.upscale)
            Text(stringResource(R.string.draw_generate, out.label))
        }
    }
}

// ---------------- размер и увеличение ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SizeCard(form: DrawForm, plan: DrawPresets.Plan, upscaler: UpscalerState, enabled: Boolean, actions: DrawActions) {
    var orientation by rememberSaveable { mutableStateOf(form.size.orientation) }
    SectionCard(title = stringResource(R.string.draw_size_title), icon = painterResource(R.drawable.ic_image)) {
        val options = listOf(
            DrawSize.Orientation.PORTRAIT to stringResource(R.string.draw_portrait),
            DrawSize.Orientation.SQUARE to stringResource(R.string.draw_square),
            DrawSize.Orientation.LANDSCAPE to stringResource(R.string.draw_landscape),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (o, label) ->
                SegmentedButton(
                    selected = orientation == o,
                    onClick = {
                        orientation = o
                        DrawPresets.SIZES.firstOrNull { it.orientation == o }?.let { s ->
                            // ближайший по площади размер в новой ориентации
                            val best = DrawPresets.SIZES.filter { it.orientation == o }.minBy { kotlin.math.abs(it.area - form.size.area) }
                            actions.update { f -> f.copy(size = best ?: s) }
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    enabled = enabled,
                ) { Text(label, maxLines = 1) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            DrawPresets.SIZES.filter { it.orientation == orientation }.forEach { s ->
                FilterChip(
                    selected = form.size == s,
                    onClick = { actions.update { it.copy(size = s) } },
                    label = { Text(s.label) },
                    enabled = enabled,
                    leadingIcon = if (DrawPresets.load(s) >= Load.HEAVY) {
                        { Icon(Icons.Filled.Warning, null, Modifier.size(16.dp)) }
                    } else null,
                )
            }
        }
        LoadInfo(form, plan)

        HorizontalRule()
        SwitchRow(
            title = stringResource(R.string.draw_upscale_title, DrawPresets.outputSize(form.size, true).label),
            text = stringResource(R.string.draw_upscale_basic),
            checked = form.upscale,
            enabled = enabled,
            onChange = { v -> actions.update { it.copy(upscale = v) } },
        )
        AnimatedVisibility(form.upscale) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                val extra = DrawPresets.formatDuration(
                    DrawPresets.estimateSeconds(form.size, form.steps, form.cfg, true, true).last -
                        DrawPresets.estimateSeconds(form.size, form.steps, form.cfg, true, false).last,
                )
                SwitchRow(
                    title = stringResource(R.string.draw_neural_title),
                    text = stringResource(if (upscaler.installed) R.string.draw_upscale_esrgan else R.string.draw_upscaler_missing_short, extra),
                    checked = form.neuralUpscale && upscaler.installed,
                    enabled = enabled && upscaler.installed,
                    onChange = { v -> actions.update { it.copy(neuralUpscale = v) } },
                )
                if (!upscaler.installed) {
                    if (upscaler.running) {
                        LinearProgressIndicator(progress = { upscaler.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        AssistChip(
                            onClick = actions.downloadUpscaler,
                            label = { Text(stringResource(R.string.draw_upscaler_download, mb(ImageModelSource.UPSCALER.bytes))) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp)) },
                        )
                    }
                }
                upscaler.error?.let { Banner(it, tone = BannerTone.Error) }
            }
        }
    }
}

@Composable
private fun HorizontalRule() = androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun LoadInfo(form: DrawForm, plan: DrawPresets.Plan) {
    val load = DrawPresets.load(form.size)
    val (labelRes, tone) = when (load) {
        Load.LIGHT -> R.string.load_light to null
        Load.NORMAL -> R.string.load_normal to null
        Load.HEAVY -> R.string.load_heavy to BannerTone.Warning
        Load.EXTREME -> R.string.load_extreme to BannerTone.Error
    }
    val text = stringResource(
        R.string.draw_estimate,
        String.format(Locale.US, "%.1f", plan.peakBytes / 1_073_741_824.0),
        DrawPresets.formatDuration(plan.seconds.first),
        DrawPresets.formatDuration(plan.seconds.last),
    ) + if (plan.flashAttn) "\n" + stringResource(R.string.draw_low_memory_mode) else ""
    if (tone == null) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(painterResource(R.drawable.ic_memory), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.size(Spacing.s))
            Hint(stringResource(labelRes) + " · " + text)
        }
    } else {
        Banner(
            text + "\n" + stringResource(if (load == Load.EXTREME) R.string.draw_warn_extreme else R.string.draw_warn_heavy),
            title = stringResource(labelRes),
            icon = rememberVectorPainter(Icons.Filled.Warning),
            tone = tone,
        )
    }
}

// ---------------- дополнительно ----------------

@Composable
private fun AdvancedCard(form: DrawForm, enabled: Boolean, actions: DrawActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard(modifier = Modifier.animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            IconBadge(painterResource(R.drawable.ic_tune))
            Spacer(Modifier.size(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.draw_advanced), style = MaterialTheme.typography.titleMedium)
                Hint(
                    stringResource(R.string.draw_advanced_summary, form.steps, String.format(Locale.US, "%.1f", form.cfg)) +
                        if (form.seed >= 0) " · seed ${form.seed}" else "",
                )
            }
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
        }
        if (open) {
            Text(stringResource(R.string.draw_steps, form.steps), style = MaterialTheme.typography.titleSmall)
            Slider(
                value = form.steps.toFloat(),
                onValueChange = { v -> actions.update { it.copy(steps = v.roundToInt()) } },
                valueRange = DrawPresets.STEPS.first.toFloat()..DrawPresets.STEPS.last.toFloat(),
                steps = DrawPresets.STEPS.last - DrawPresets.STEPS.first - 1,
                enabled = enabled,
            )
            Hint(stringResource(R.string.draw_steps_hint))

            Text("CFG: ${String.format(Locale.US, "%.1f", form.cfg)}", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = form.cfg,
                onValueChange = { v -> actions.update { it.copy(cfg = (v * 4).roundToInt() / 4f) } },
                valueRange = 1f..2f,
                steps = 3,
                enabled = enabled,
            )
            Hint(stringResource(R.string.draw_cfg_hint))

            OutlinedTextField(
                value = form.negativePrompt,
                onValueChange = { v -> actions.update { it.copy(negativePrompt = v) } },
                label = { Text(stringResource(R.string.draw_negative)) },
                supportingText = { Text(stringResource(R.string.draw_negative_hint)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                minLines = 2,
                shape = MaterialTheme.shapes.medium,
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = if (form.seed < 0) "" else form.seed.toString(),
                    onValueChange = { v -> actions.update { it.copy(seed = v.filter(Char::isDigit).take(10).toLongOrNull() ?: -1) } },
                    label = { Text("Seed") },
                    placeholder = { Text(stringResource(R.string.draw_seed_random)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                )
                IconButton(onClick = { actions.update { it.copy(seed = -1) } }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_dice), contentDescription = stringResource(R.string.draw_seed_random))
                }
            }

            SwitchRow(
                title = stringResource(R.string.draw_fast_decoder),
                text = stringResource(R.string.draw_fast_decoder_hint),
                checked = form.fastDecoder,
                enabled = enabled,
                onChange = { v -> actions.update { it.copy(fastDecoder = v) } },
            )
            TextButton(onClick = { actions.update { DrawForm(prompt = it.prompt, spokenPrompt = it.spokenPrompt) } }, enabled = enabled) {
                Text(stringResource(R.string.draw_reset_defaults))
            }
        }
    }
}


// ---------------- img2img: исходное фото ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(
    form: DrawForm, source: Bitmap?, info: com.hakkiiler.voiceassistant.data.image.SourceInfo?, loading: Boolean,
    error: String?, enabled: Boolean, actions: DrawActions,
) {
    SectionCard(
        title = stringResource(R.string.img2img_title),
        icon = painterResource(R.drawable.ic_image),
        subtitle = stringResource(if (source == null) R.string.img2img_hint else R.string.img2img_selected_short),
    ) {
        // поле выбора: миниатюра + имя/размер + «Заменить» + ✕
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                .clickable(enabled = enabled && !loading, onClick = actions.pickPhoto),
        ) {
            Row(Modifier.padding(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        source != null -> Image(
                            source.asImageBitmap(), contentDescription = info?.name,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                        else -> Icon(painterResource(R.drawable.ic_image), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.size(Spacing.m))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            loading -> stringResource(R.string.img2img_loading)
                            source != null -> info?.name ?: "photo"
                            else -> stringResource(R.string.img2img_pick)
                        },
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (source != null && !loading) {
                        val bytes = info?.bytes?.takeIf { it > 0 }?.let { " · " + String.format(Locale.US, "%.1f", it / 1_000_000.0) + " MB" }.orEmpty()
                        val orig = info?.let { "${it.width}×${it.height}" } ?: ""
                        Text(
                            "$orig$bytes → ${source.width}×${source.height}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = actions.pickPhoto, enabled = enabled,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                        ) { Text(stringResource(R.string.img2img_replace)) }
                    }
                }
                if (source != null && !loading) {
                    IconButton(onClick = actions.clearSource, enabled = enabled) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_delete))
                    }
                }
            }
        }
        if (error != null) {
            Banner(
                error, title = stringResource(R.string.img2img_error), tone = BannerTone.Error,
                actions = {
                    TextButton(onClick = actions.pickFile) { Text(stringResource(R.string.img2img_other_way)) }
                    TextButton(onClick = actions.shareLog) { Text(stringResource(R.string.img2img_share_log)) }
                    TextButton(onClick = actions.dismissSourceError) { Text("OK") }
                },
            )
        }
        // во время рисования менять фото нельзя; в остальное время кнопки активны всегда (модель для выбора не нужна)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FilledTonalButton(onClick = actions.pickPhoto, enabled = enabled && !loading) { Text(stringResource(R.string.img2img_gallery)) }
            FilledTonalButton(onClick = actions.takePhoto, enabled = enabled && !loading) { Text(stringResource(R.string.img2img_camera)) }
            TextButton(onClick = actions.pickFile, enabled = enabled && !loading) { Text(stringResource(R.string.img2img_files)) }
        }
        if (source != null) {
            Text(stringResource(R.string.img2img_strength, (form.strength * 100).roundToInt()), style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = form.strength,
                onValueChange = { v -> actions.update { it.copy(strength = (v * 20).roundToInt() / 20f) } },
                valueRange = 0.2f..0.9f,
                enabled = enabled,
            )
            Text(stringResource(R.string.img2img_strength_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Сравнение «до / после»: тяни ползунок — слева исходное фото, справа результат. */
@Composable
private fun BeforeAfter(before: Bitmap, after: Bitmap) {
    var split by remember(before, after) { mutableStateOf(0.5f) }
    Box(Modifier.fillMaxSize()) {
        Image(after.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Image(
            before.asImageBitmap(), null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().drawWithContent {
                clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
            },
        )
        Box(
            Modifier.fillMaxSize().drawWithContent {
                val x = size.width * split
                drawLine(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 4f)
            },
        )
        Row(Modifier.fillMaxWidth().padding(Spacing.s), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusPill(stringResource(R.string.img2img_before), MaterialTheme.colorScheme.outline)
            StatusPill(stringResource(R.string.img2img_after), MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = split, onValueChange = { split = it },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Spacing.m),
        )
    }
}
