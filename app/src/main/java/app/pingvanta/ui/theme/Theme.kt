package app.pingvanta.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pingvanta.data.MonitorStatus
import app.pingvanta.data.ThemeMode

object StatusColors {
    val Up = Color(0xFF22C55E)
    val Down = Color(0xFFEF4444)
    val Unknown = Color(0xFF94A3B8)
    val Degraded = Color(0xFFF59E0B)
}

fun MonitorStatus.color(): Color = when (this) {
    MonitorStatus.UP -> StatusColors.Up
    MonitorStatus.DOWN -> StatusColors.Down
    MonitorStatus.UNKNOWN -> StatusColors.Unknown
}

/** Colour for an uptime percentage; null (no data) is grey. */
fun uptimeColor(percent: Double?): Color = when {
    percent == null -> StatusColors.Unknown
    percent >= 99.0 -> StatusColors.Up
    percent >= 90.0 -> StatusColors.Degraded
    else -> StatusColors.Down
}

private val Brand = Color(0xFF5B6CFF)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E5FF),
    onPrimaryContainer = Color(0xFF131C6B),
    secondary = Color(0xFF596080),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E7F5),
    onSecondaryContainer = Color(0xFF1B2140),
    tertiary = Color(0xFF7A5CFF),
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF111729),
    surface = Color(0xFFF4F6FB),
    onSurface = Color(0xFF111729),
    surfaceVariant = Color(0xFFE6E9F3),
    onSurfaceVariant = Color(0xFF5A6380),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFE),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEEF1F8),
    surfaceContainerHighest = Color(0xFFE6E9F3),
    outline = Color(0xFFB4BBD0),
    outlineVariant = Color(0xFFDDE1EC),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93A0FF),
    onPrimary = Color(0xFF0E1440),
    primaryContainer = Color(0xFF27306B),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFB4BCDD),
    onSecondary = Color(0xFF1B2140),
    secondaryContainer = Color(0xFF232C4B),
    onSecondaryContainer = Color(0xFFDCE1F7),
    tertiary = Color(0xFFB7A4FF),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE6E9F5),
    surface = Color(0xFF0B1020),
    onSurface = Color(0xFFE6E9F5),
    surfaceVariant = Color(0xFF1B2440),
    onSurfaceVariant = Color(0xFF9AA5C4),
    surfaceContainerLowest = Color(0xFF080C18),
    surfaceContainerLow = Color(0xFF10172A),
    surfaceContainer = Color(0xFF131B31),
    surfaceContainerHigh = Color(0xFF1A2340),
    surfaceContainerHighest = Color(0xFF222C4D),
    outline = Color(0xFF465173),
    outlineVariant = Color(0xFF242E4D),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF5B1717),
    onErrorContainer = Color(0xFFFECACA),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PingvantaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
