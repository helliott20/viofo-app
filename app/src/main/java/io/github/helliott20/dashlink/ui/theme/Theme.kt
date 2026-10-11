package io.github.helliott20.dashlink.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Amber = Color(0xFFFFB020)
val RecordRed = Color(0xFFE5383B)
val OkGreen = Color(0xFF2EC27E)

private val Dark = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF2A1B00),
    primaryContainer = Color(0xFF4A3300),
    onPrimaryContainer = Color(0xFFFFDDAA),
    secondary = Color(0xFF9CCBFF),
    onSecondary = Color(0xFF00325A),
    secondaryContainer = Color(0xFF1B3A57),
    onSecondaryContainer = Color(0xFFD0E4FF),
    tertiary = OkGreen,
    error = RecordRed,
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE4E2E6),
    surface = Color(0xFF0E1013),
    onSurface = Color(0xFFE4E2E6),
    surfaceVariant = Color(0xFF23262B),
    onSurfaceVariant = Color(0xFFC3C6CC),
    surfaceContainerLowest = Color(0xFF0A0B0D),
    surfaceContainerLow = Color(0xFF15171B),
    surfaceContainer = Color(0xFF1A1D21),
    surfaceContainerHigh = Color(0xFF22252A),
    surfaceContainerHighest = Color(0xFF2B2E34),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF3A3E45),
)

private val Light = lightColorScheme(
    primary = Color(0xFF805600),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDAA),
    onPrimaryContainer = Color(0xFF2A1B00),
    secondary = Color(0xFF2F6197),
    tertiary = Color(0xFF00794A),
    error = Color(0xFFBA1A1A),
)

@Composable
fun DashLinkTheme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
