package com.hakkiiler.voiceassistant.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hakkiiler.voiceassistant.ui.screens.AssistantScreen
import com.hakkiiler.voiceassistant.ui.screens.CommandsScreen
import com.hakkiiler.voiceassistant.ui.screens.DrawScreen
import com.hakkiiler.voiceassistant.ui.screens.EventsScreen
import com.hakkiiler.voiceassistant.ui.screens.SettingsScreen

private data class Tab(val title: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Ассистент", Icons.Filled.Home),
    Tab("События", Icons.Filled.DateRange),
    Tab("Команды", Icons.AutoMirrored.Filled.List),
    Tab("Рисование", Icons.Filled.Create),
    Tab("Настройки", Icons.Filled.Settings),
)

private const val DRAW_TAB = 3

/** Корневой экран: нижняя навигация между пятью разделами (раньше всё было в одном MainContainer). */
@Composable
fun MainScreen(vm: AssistantViewModel = viewModel()) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    // голосовое «нарисуй …» переключает на вкладку рисования
    val openDraw by vm.draw.openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openDraw) {
        if (openDraw) { selected = DRAW_TAB; vm.draw.consumeOpenRequest() }
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(t.icon, contentDescription = t.title) },
                        label = { Text(t.title, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (selected) {
            0 -> AssistantScreen(vm, m)
            1 -> EventsScreen(vm, m)
            2 -> CommandsScreen(vm, m)
            DRAW_TAB -> DrawScreen(vm, m)
            else -> SettingsScreen(vm, m)
        }
    }
}
