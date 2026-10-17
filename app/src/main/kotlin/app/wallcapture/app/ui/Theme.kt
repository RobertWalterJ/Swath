package app.wallcapture.app.ui

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

/** Warm paper, near-black ink, one indigo accent. Sticky-note colours are data and live in [NoteColours]. */
private val LightColours = lightColorScheme(
    primary = Color(0xFF3441D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E8FF),
    onPrimaryContainer = Color(0xFF151B73),
    secondary = Color(0xFF5E636E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFEDE6),
    onSecondaryContainer = Color(0xFF16181D),
    background = Color(0xFFFBFAF7),
    onBackground = Color(0xFF16181D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFF1EFE9),
    onSurfaceVariant = Color(0xFF5E636E),
    outline = Color(0xFFC9C5BA),
    outlineVariant = Color(0xFFE6E3DB),
    error = Color(0xFFB3261E),
    onError = Color.White,
    surfaceContainer = Color(0xFFF6F4EE),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFF1EFE9),
)

private val DarkColours = darkColorScheme(
    primary = Color(0xFFA9B1FF),
    onPrimary = Color(0xFF0E1260),
    primaryContainer = Color(0xFF262F9E),
    onPrimaryContainer = Color(0xFFE6E8FF),
    secondary = Color(0xFFB4B8C2),
    onSecondary = Color(0xFF16181D),
    secondaryContainer = Color(0xFF2A2C32),
    onSecondaryContainer = Color(0xFFF2F1ED),
    background = Color(0xFF101114),
    onBackground = Color(0xFFF2F1ED),
    surface = Color(0xFF1A1C21),
    onSurface = Color(0xFFF2F1ED),
    surfaceVariant = Color(0xFF24262C),
    onSurfaceVariant = Color(0xFFB4B8C2),
    outline = Color(0xFF4A4D55),
    outlineVariant = Color(0xFF2E3037),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    surfaceContainer = Color(0xFF1F2126),
    surfaceContainerHigh = Color(0xFF24262C),
    surfaceContainerHighest = Color(0xFF2A2C32),
)

private val SwathType = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val SwathShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SwathTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColours else LightColours,
        typography = SwathType,
        shapes = SwathShapes,
        content = content,
    )
}

/** Spacing scale in dp. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val edge = 20.dp
}
