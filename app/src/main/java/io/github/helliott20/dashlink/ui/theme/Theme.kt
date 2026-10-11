package io.github.helliott20.dashlink.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.helliott20.dashlink.R

/*
 * Palette: instrument-cluster amber on a cool cabin dark. Amber is the colour of dashboard
 * telltales and of most dashcams' status LEDs; red is reserved for recording, nothing else.
 */
val Amber = Color(0xFFFFB020)
val RecordRed = Color(0xFFE5383B)
val OkGreen = Color(0xFF2EC27E)

/** Barlow takes its proportions from road signage; used for titles and numbers. */
val Signage = FontFamily(
    Font(R.font.barlow_semicondensed_medium, FontWeight.Medium),
    Font(R.font.barlow_semicondensed_semibold, FontWeight.SemiBold),
)

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
    errorContainer = Color(0xFF5C1417),
    onErrorContainer = Color(0xFFFFDAD8),
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
    primary = Color(0xFF7A5200),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDAA),
    onPrimaryContainer = Color(0xFF2A1B00),
    secondary = Color(0xFF2F6197),
    secondaryContainer = Color(0xFFD3E4FF),
    onSecondaryContainer = Color(0xFF001C38),
    tertiary = Color(0xFF00794A),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFF7F8FA),
    surfaceContainerLow = Color(0xFFF1F2F5),
    surfaceContainer = Color(0xFFEBECEF),
    surfaceContainerHigh = Color(0xFFE5E6EA),
    surfaceContainerHighest = Color(0xFFDFE0E4),
)

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.signage(weight: FontWeight = FontWeight.SemiBold) = copy(fontFamily = Signage, fontWeight = weight, letterSpacing = 0.sp)
    return base.copy(
        displayLarge = base.displayLarge.signage(),
        displayMedium = base.displayMedium.signage(),
        displaySmall = base.displaySmall.signage(),
        headlineLarge = base.headlineLarge.signage(),
        headlineMedium = base.headlineMedium.signage(),
        headlineSmall = base.headlineSmall.signage(),
        titleLarge = base.titleLarge.signage().copy(fontSize = 24.sp),
        titleMedium = base.titleMedium.signage(FontWeight.Medium).copy(fontSize = 18.sp),
    )
}

@Composable
fun DashLinkTheme(dynamic: Boolean = false, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = typography(), content = content)
}
