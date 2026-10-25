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
    radius: Dp = 26.dp,
    strength: Float = 1f,
    elevation: Dp = 12.dp,
): Modifier {
    val shape = RoundedCornerShape(radius)
    val dark = MaterialTheme.colorScheme.background.isDark()
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            // sombra tintada: una sombra negra pura se ve "barata" sobre azul
            ambientColor = if (dark) Color(0x66000000) else Color(0x14264A55),
            spotColor = if (dark) Color(0x99000000) else Color(0x2E264A55),
        )
        .background(
            brush = Brush.verticalGradient(
                0f to if (dark) Color.White.copy(alpha = 0.060f * strength)
                else Color.White.copy(alpha = 0.88f * strength),
                0.55f to if (dark) Color.White.copy(alpha = 0.030f * strength)
                else Color.White.copy(alpha = 0.74f * strength),
                1f to if (dark) Color(0x0C000000) else Color(0x33B9CDD4),
            ),
            shape = shape,
        )
        // Luz cenital: 1.5 dp de blanco arriba que se desvanece hacia los costados.
        // No cierra el contorno, así que sigue sin ser un borde.
        .drawWithCache {
            val band = 1.5.dp.toPx()
            val light = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.18f to Color.White.copy(alpha = if (dark) 0.16f else 0.9f),
                0.82f to Color.White.copy(alpha = if (dark) 0.16f else 0.9f),
                1f to Color.Transparent,
            )
            onDrawBehind {
                drawRect(light, size = Size(size.width, band))
            }
        }
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
fun orbWell(color: Color = MaterialTheme.colorScheme.primary, alpha: Float = 0.11f): Brush =
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
