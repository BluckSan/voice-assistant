package com.hakkiiler.voiceassistant.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Фирменная палитра «Полночь»: глубокий сине-фиолетовый фон, лавандовый акцент, бирюзовый второй цвет.
private val Midnight = darkColorScheme(
    primary = Color(0xFFB9A6FF),
    onPrimary = Color(0xFF2A1670),
    primaryContainer = Color(0xFF41308A),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFF6FE3D3),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF1E4A47),
    onSecondaryContainer = Color(0xFFB5F5EB),
    tertiary = Color(0xFFFFB68A),
    onTertiary = Color(0xFF522300),
    tertiaryContainer = Color(0xFF6E3A17),
    onTertiaryContainer = Color(0xFFFFDBC8),
    background = Color(0xFF0E0F1A),
    onBackground = Color(0xFFE5E3F0),
    surface = Color(0xFF0E0F1A),
    onSurface = Color(0xFFE5E3F0),
    surfaceVariant = Color(0xFF2A2B3D),
    onSurfaceVariant = Color(0xFFC6C4D8),
    surfaceContainerLowest = Color(0xFF0A0B14),
    surfaceContainerLow = Color(0xFF151626),
    surfaceContainer = Color(0xFF1A1B2C),
    surfaceContainerHigh = Color(0xFF232436),
    surfaceContainerHighest = Color(0xFF2D2E42),
    outline = Color(0xFF8F8DA3),
    outlineVariant = Color(0xFF3F3F55),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1A1B),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val Daylight = lightColorScheme(
    primary = Color(0xFF5B45C8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1B0062),
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC4F1EA),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFFB0581E),
    tertiaryContainer = Color(0xFFFFDBC8),
    onTertiaryContainer = Color(0xFF341100),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE5E0F0),
    onSurfaceVariant = Color(0xFF48455A),
    surfaceContainerLow = Color(0xFFF5F2FC),
    surfaceContainer = Color(0xFFEFECF7),
    surfaceContainerHigh = Color(0xFFE9E6F2),
    surfaceContainerHighest = Color(0xFFE3E0EC),
    outlineVariant = Color(0xFFCAC4D8),
)

val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = 0.2.sp),
    )
}

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Единые отступы во всех экранах. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val screen = 16.dp
}

/** Оформление: цвета системы (Material You, Android 12+) или фирменная палитра. Хранится в SharedPreferences. */
object ThemePrefs {
    private const val FILE = "ui"
    private const val KEY = "dynamic_color"
    val dynamic = mutableStateOf<Boolean?>(null)

    fun load(context: Context): Boolean =
        dynamic.value ?: context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false).also { dynamic.value = it }

    fun set(context: Context, value: Boolean) {
        dynamic.value = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }

    val supported get() = Build.VERSION.SDK_INT >= 31
}

fun appColorScheme(context: Context?, dark: Boolean, dynamic: Boolean): ColorScheme = when {
    dynamic && context != null && Build.VERSION.SDK_INT >= 31 ->
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> Midnight
    else -> Daylight
}

@Composable
fun VoiceAssistantTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dynamic = ThemePrefs.load(context)
    MaterialTheme(
        colorScheme = appColorScheme(context, dark, dynamic),
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
