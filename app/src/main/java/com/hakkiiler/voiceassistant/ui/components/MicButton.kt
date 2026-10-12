package com.hakkiiler.voiceassistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hakkiiler.voiceassistant.R

/**
 * Большая круглая кнопка микрофона. Когда ассистент слушает — вокруг расходятся «волны»,
 * а сама кнопка чуть «дышит» в такт громкости.
 */
@Composable
fun MicButton(
    active: Boolean,
    level: Float,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
) {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
    )
    val levelScale by animateFloatAsState(if (active) 1f + level * 0.12f else 1f, spring(stiffness = 600f), label = "lvl")
    val ringAlpha by animateFloatAsState(if (active) 1f else 0f, tween(300), label = "ring")

    Box(modifier.size(size * 1.7f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 1.7f)) {
            if (ringAlpha > 0f) {
                val base = size.toPx() / 2
                for (k in 0..1) {
                    val p = (pulse + k * 0.5f) % 1f
                    drawCircle(
                        color = colors.primary.copy(alpha = (1f - p) * 0.35f * ringAlpha),
                        radius = base + p * base * 0.65f,
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
                drawCircle(colors.primary.copy(alpha = 0.14f * ringAlpha), radius = base * (1.08f + level * 0.3f))
            }
        }
        Box(
            Modifier
                .size(size)
                .scale(levelScale)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        if (active) listOf(colors.primary, colors.secondary) else listOf(colors.primaryContainer, colors.primaryContainer),
                    ),
                )
                .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_mic),
                contentDescription = contentDescription,
                tint = if (active) colors.onPrimary else colors.onPrimaryContainer,
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

/** Живая «волна» громкости: история последних значений уровня, бегущая справа налево. */
@Composable
fun Waveform(level: Float, active: Boolean, modifier: Modifier = Modifier, bars: Int = 36, color: Color = MaterialTheme.colorScheme.primary) {
    val history = remember { mutableStateListOf<Float>().apply { repeat(bars) { add(0f) } } }
    LaunchedEffect(level, active) {
        history.removeAt(0)
        history.add(if (active) level else 0f)
    }
    val transition = rememberInfiniteTransition(label = "wave")
    val idle by transition.animateFloat(0f, 6.28f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "idle")
    Canvas(modifier.fillMaxWidth().height(40.dp)) {
        val gap = 3.dp.toPx()
        val w = (this.size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val v = history[i]
            // в тишине — едва заметная «дышащая» линия
            val idleH = (0.08f + 0.05f * kotlin.math.sin(idle + i * 0.45f))
            val h = this.size.height * maxOf(idleH, v * (0.55f + 0.45f * kotlin.math.sin(i * 1.7f).let { it * it }))
            val x = i * (w + gap)
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.65f * (i.toFloat() / bars)),
                topLeft = Offset(x, (this.size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}
