package com.hakkiiler.voiceassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.hakkiiler.voiceassistant.di.AppContainer
import com.hakkiiler.voiceassistant.overlay.OverlayService
import com.hakkiiler.voiceassistant.service.AssistantService
import com.hakkiiler.voiceassistant.ui.AssistantViewModel
import com.hakkiiler.voiceassistant.ui.MainScreen
import com.hakkiiler.voiceassistant.ui.screens.ModelSetupScreen
import com.hakkiiler.voiceassistant.domain.model.LanguageMode
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hakkiiler.voiceassistant.ui.theme.VoiceAssistantTheme

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) resumeServices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceAssistantTheme {
                val vm: AssistantViewModel = viewModel()
                val installed by vm.installedModels.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                val dl by vm.downloadState.collectAsStateWithLifecycle()
                // lite: пока нет ни одной модели (или идёт первая загрузка) — экран первого запуска
                val needSetup = !vm.modelsBundled && (installed.isEmpty() || ((dl.running || dl.error != null) && installed.size < requiredCount(settings.languageMode)))
                if (needSetup) {
                    Scaffold { p -> ModelSetupScreen(vm, Modifier.padding(p)) }
                } else {
                    LaunchedEffect(Unit) { resumeServices() }
                    MainScreen(vm)
                }
            }
        }

        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) resumeServices() else permissions.launch(needed.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        val s = AppContainer.from(this).settings.settings.value
        if (s.overlayEnabled && OverlayService.canDraw(this)) OverlayService.start(this)
    }

    private fun requiredCount(mode: LanguageMode) = if (mode == LanguageMode.AUTO) 2 else 1

    private var servicesResumed = false

    /** Фоновое прослушивание стартуем, пока приложение на экране (требование Android 14 для микрофона). */
    private fun resumeServices() {
        val c = AppContainer.from(this)
        if (c.modelDownloads.installed.value.isEmpty()) return // lite: моделей ещё нет
        if (servicesResumed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        servicesResumed = true
        if (c.settings.settings.value.backgroundListening) AssistantService.start(this, listenNow = false)
    }
}
