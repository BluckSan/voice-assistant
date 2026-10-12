package com.hakkiiler.voiceassistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hakkiiler.voiceassistant.R
import com.hakkiiler.voiceassistant.domain.model.Event
import com.hakkiiler.voiceassistant.ui.AssistantViewModel
import com.hakkiiler.voiceassistant.ui.components.Banner
import com.hakkiiler.voiceassistant.ui.components.BannerTone
import com.hakkiiler.voiceassistant.ui.components.EmptyState
import com.hakkiiler.voiceassistant.ui.components.EventDialog
import com.hakkiiler.voiceassistant.ui.components.ScreenHeader
import com.hakkiiler.voiceassistant.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun EventsScreen(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    val events by vm.events.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Event?>(null) }
    var creating by remember { mutableStateOf(false) }
    EventsContent(
        events = events,
        exactAlarmsAllowed = vm.canScheduleExact(),
        onEdit = { editing = it },
        onDelete = { vm.deleteEvent(it) },
        onCreate = { creating = true },
        modifier = modifier,
    )
    if (creating || editing != null) {
        val initial = editing
        EventDialog(
            initial = initial,
            onSave = { t, ts, r -> vm.saveEvent(t, ts, r, initial?.id); creating = false; editing = null },
            onDismiss = { creating = false; editing = null },
        )
    }
}

/** Без ViewModel — чтобы экран можно было показать в превью/скриншот-тестах. */
@Composable
fun EventsContent(
    events: List<Event>,
    exactAlarmsAllowed: Boolean,
    onEdit: (Event) -> Unit,
    onDelete: (Event) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val locale = Locale.getDefault()
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val dayFmt = remember(locale) { DateTimeFormatter.ofPattern("EEEE, d MMMM", locale) }
    val upcoming = events.filter { it.timestamp >= now }.sortedBy { it.timestamp }
    val past = events.filter { it.timestamp < now }.sortedByDescending { it.timestamp }
    val sTodayLabel = stringResource(R.string.events_today)
    val sTomorrowLabel = stringResource(R.string.events_tomorrow)

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            item {
                ScreenHeader(stringResource(R.string.tab_events), stringResource(R.string.events_subtitle))
                if (!exactAlarmsAllowed) {
                    Banner(
                        stringResource(R.string.events_exact_warning),
                        icon = rememberVectorPainter(Icons.Filled.Warning),
                        tone = BannerTone.Warning,
                        modifier = Modifier.padding(bottom = Spacing.s),
                    )
                }
            }
            if (events.isEmpty()) item {
                EmptyState(painterResource(R.drawable.ic_event), stringResource(R.string.events_empty_title), stringResource(R.string.events_empty_text))
            }
            val groups = upcoming.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            groups.forEach { (day, list) ->
                item(key = "h$day") {
                    val label = when (day) {
                        today -> sTodayLabel
                        today.plusDays(1) -> sTomorrowLabel
                        else -> day.format(dayFmt).replaceFirstChar { it.titlecase(locale) }
                    }
                    SectionLabel(label)
                }
                items(list, key = { it.id }) { e -> EventCard(e, timeFmt, zone, past = false, onEdit, onDelete) }
            }
            if (past.isNotEmpty()) {
                item(key = "past") { SectionLabel(stringResource(R.string.events_past)) }
                items(past, key = { it.id }) { e -> EventCard(e, timeFmt, zone, past = true, onEdit, onDelete) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onCreate,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.events_add)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.l),
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs, start = Spacing.xs),
    )
}

@Composable
private fun EventCard(
    e: Event,
    timeFmt: DateTimeFormatter,
    zone: ZoneId,
    past: Boolean,
    onEdit: (Event) -> Unit,
    onDelete: (Event) -> Unit,
) {
    val dt = Instant.ofEpochMilli(e.timestamp).atZone(zone)
    val locale = Locale.getDefault()
    Card(
        Modifier.fillMaxWidth().alpha(if (past) 0.6f else 1f).clickable { onEdit(e) },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            // «бейдж» с датой
            Column(
                Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("${dt.dayOfMonth}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    dt.format(DateTimeFormatter.ofPattern("MMM", locale)).trimEnd('.'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.size(Spacing.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(e.title, style = MaterialTheme.typography.titleMedium)
                Text(dt.format(timeFmt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.reminderMinutes > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Notifications, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.size(Spacing.xs))
                        Text(
                            stringResource(R.string.events_remind_before, e.reminderMinutes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
            IconButton(onClick = { onDelete(e) }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete)) }
        }
    }
}
