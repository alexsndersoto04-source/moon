package com.moonshare.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Primitivas de "vidrio" de MoonShare.
 *
 * La regla del sistema: NUNCA un borde de 1px. La separación entre planos se logra
 * con (a) relleno translúcido, (b) una luz tenue en el canto superior y (c) sombra
 * difusa con color. Eso es lo que hace que se lea "cristal" y no "tarjeta flat".
 *
 * No usamos [Modifier.blur] de fondo: sobre un Mali-G52 (Redmi 9C) un blur en vivo
 * de toda la grilla cuesta frames; los gradientes dan la misma lectura por una
 * fracción del costo. El desenfoque real sí se usa, pero solo en la esfera, que es
 * un elemento chico y es el protagonista.
 */

@Composable
fun Modifier.moonGlass(
    radius: Dp = 18.dp,
    strength: Float = 1f,
    @Suppress("UNUSED_PARAMETER") elevation: Dp = 12.dp,
): Modifier {
    val shape = RoundedCornerShape(radius)
    val dark = MaterialTheme.colorScheme.background.isDark()
    // Una sola capa translúcida, sin sombra tintada ni "luz cenital". Las dos cosas
    // anteriores eran las que hacían que cada bloque pareciera un panel de cristal
    // flotando en el espacio; para una app de pasar archivos eso es ruido.
    return this.background(
        color = if (dark) Color.White.copy(alpha = 0.055f * strength)
        else Color.White.copy(alpha = 0.94f * strength),
        shape = shape,
    )
}

/** Relleno de un ítem seleccionado: tint turquesa→azul, sin bordes tampoco. */
@Composable
fun Modifier.moonGlassSelected(radius: Dp = 26.dp): Modifier = this.then(
    Modifier.background(
        brush = Brush.linearGradient(
            0f to Color(0x3339D5C4),
            1f to Color(0x222E7BE8),
            start = Offset.Zero,
            end = Offset.Infinite,
        ),
        shape = RoundedCornerShape(radius),
    )
)

/** El "pozo" de luz bajo la esfera. */
@Composable
fun orbWell(color: Color = MaterialTheme.colorScheme.primary, alpha: Float = 0.07f): Brush =
    Brush.radialGradient(
        0f to color.copy(alpha = alpha * 1.9f),
        0.55f to color.copy(alpha = alpha * 0.55f),
        1f to Color.Transparent,
    )

/** Sombra tintada para botones/flotantes, misma lógica que moonGlass. */
@Composable
fun Modifier.moonGlow(color: Color, radius: Dp = 26.dp, spread: Dp = 18.dp): Modifier =
    this.then(
        Modifier.shadow(
            elevation = spread,
            shape = RoundedCornerShape(radius),
            clip = false,
            ambientColor = color.copy(alpha = 0.45f),
            spotColor = color.copy(alpha = 0.65f),
        )
    )

/** Luminancia percibida: decidimos "modo oscuro" por el fondo, no por el sistema. */
private fun Color.isDark(): Boolean = (0.2126 * red + 0.7152 * green + 0.0722 * blue) < 0.5
