package ir.aai.hushyar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF0B1220)
private val Surface = Color(0xFF141C2B)
private val SurfaceHigh = Color(0xFF1D2739)
private val Accent = Color(0xFF5B8DEF)
private val AccentSoft = Color(0xFF8FE3B0)
private val Danger = Color(0xFFF17C7C)
private val Warn = Color(0xFFF2C46B)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    secondary = AccentSoft,
    onSecondary = Ink,
    background = Ink,
    onBackground = Color(0xFFE8EEF9),
    surface = Surface,
    onSurface = Color(0xFFE8EEF9),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Color(0xFFB9C6DB),
    error = Danger,
    tertiary = Warn
)

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
)

@Composable
fun HushyarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
