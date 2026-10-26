package com.moonshare.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Radios grandes y ninguna línea de 1dp en todo el sistema visual.
 * Los niveles se marcan con luz y desenfoque, no con trazo.
 */
val MoonShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val MoonDarkScheme = darkColorScheme(
    primary = Aqua,
    onPrimary = Color(0xFF070C10),
    primaryContainer = BlueDeep,
    onPrimaryContainer = AquaSoft,
    secondary = BlueSoft,
    onSecondary = Color(0xFF070C10),
    secondaryContainer = NightSurfaceHigh,
    onSecondaryContainer = InkHigh,
    tertiary = AquaSoft,
    background = NightBg,
    onBackground = InkHigh,
    // surface == background: la separación entre bloques es aire, no un rectángulo
    // más claro encima. Es lo que hace que se lea "sistema" y no "panel de nave".
    surface = NightBg,
    onSurface = InkHigh,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = InkMedium,
    outline = InkFaint,
    outlineVariant = Color(0x14FFFFFF),
    error = Danger,
    onError = Color(0xFF070C10),
    scrim = Color(0xE6000000),
)

private val MoonLightScheme = lightColorScheme(
    primary = AquaDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8F6F1),
    onPrimaryContainer = Color(0xFF071A1A),
    secondary = Blue,
    onSecondary = Color(0xFFFFFFFF),
    background = DayBg,
    onBackground = InkHighLight,
    surface = DaySurface,
    onSurface = InkHighLight,
    surfaceVariant = Color(0xFFE7EEF1),
    onSurfaceVariant = InkMediumLight,
    outline = Color(0xFFB6C4C9),
    error = Danger,
)

@Composable
fun MoonTheme(
    dark: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        // Iconos de barra de estado del color correcto según el tema, sin pelearse
        // con el sistema de ventanas: se aplica después de la primera composición.
        SideEffect {
            (view.context as? Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    val base = if (dark) MoonDarkScheme else MoonLightScheme
    val scheme = if (dark && amoled) base.copy(background = NightBgAmoled, surface = NightBgAmoled) else base

    MaterialTheme(
        colorScheme = scheme,
        typography = MoonTypography,
        shapes = MoonShapes,
        content = content,
    )
}

/** Forma "píldora" para los controles principales. */
val PillShape: Shape = RoundedCornerShape(percent = 50)

/** Escala tipográfica para los números grandes (MB, %). */
val NumericStyle = MoonTypography.headlineMedium.copy(
    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
    letterSpacing = (-0.6).sp,
)
