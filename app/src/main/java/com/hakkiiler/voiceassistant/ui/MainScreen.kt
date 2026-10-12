package com.hakkiiler.voiceassistant.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hakkiiler.voiceassistant.R
import com.hakkiiler.voiceassistant.ui.screens.AssistantScreen
import com.hakkiiler.voiceassistant.ui.screens.CommandsScreen
import com.hakkiiler.voiceassistant.ui.screens.DrawScreen
import com.hakkiiler.voiceassistant.ui.screens.EventsScreen
import com.hakkiiler.voiceassistant.ui.screens.SettingsScreen

private data class Tab(@StringRes val title: Int, @DrawableRes val icon: Int)

private val tabs = listOf(
    Tab(R.string.tab_assistant, R.drawable.ic_chat),
    Tab(R.string.tab_events, R.drawable.ic_event),
    Tab(R.string.tab_commands, R.drawable.ic_apps),
    Tab(R.string.tab_draw, R.drawable.ic_palette),
    Tab(R.string.tab_settings, R.drawable.ic_tune),
)

const val DRAW_TAB = 3

/** Корневой экран: нижняя навигация между пятью разделами, плавная смена вкладок. */
@Composable
fun MainScreen(vm: AssistantViewModel = viewModel(), initialTab: Int = 0) {
    var selected by rememberSaveable { mutableIntStateOf(initialTab) }
    // голосовое «нарисуй …» переключает на вкладку рисования
    val openDraw by vm.draw.openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openDraw) {
        if (openDraw) { selected = DRAW_TAB; vm.draw.consumeOpenRequest() }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(stringResource(t.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = selected,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
            label = "tabs",
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        ) { tab ->
            val m = Modifier.fillMaxSize()
            when (tab) {
                0 -> AssistantScreen(vm, m)
                1 -> EventsScreen(vm, m)
                2 -> CommandsScreen(vm, m)
                DRAW_TAB -> DrawScreen(vm, m)
                else -> SettingsScreen(vm, m)
            }
        }
    }
}
