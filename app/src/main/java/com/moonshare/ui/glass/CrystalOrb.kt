package com.moonshare.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.Dp
import android.os.Build
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.AquaSoft
import com.moonshare.ui.theme.Blue
import com.moonshare.ui.theme.BlueDeep
import com.moonshare.ui.theme.OrbCore
import com.moonshare.ui.theme.OrbEdge
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * La esfera. Cristalina, sin bordes, azul + verde turquesa, transparente.
 *
 * Adentro hay agua: el nivel sube con el progreso del archivo que está pasando y,
 * cada vez que termina uno, la superficie salpica y sale una onda. Así la esfera no
 * es decoración — es el medidor, y coincide con el sonido de gota.
 *
 * Todo se pinta con gradientes en un [Canvas]; no hay un solo trazo de contorno.
 */
@Composable
fun CrystalOrb(
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    pulseNonce: Long = 0L,
    intensity: Float = 0.85f,
    reducedMotion: Boolean = false,
    active: Boolean = true,
) {
    val level = remember { Animatable(0f) }
    val splash = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }

    LaunchedEffect(progress) {
        // 220 ms con easing: el agua no salta, se derrama
        level.animateTo(progress.coerceIn(0f, 1f), tween(220, easing = FastOutSlowInEasing))
    }

    LaunchedEffect(pulseNonce) {
        if (pulseNonce == 0L) return@LaunchedEffect
        splash.snapTo(0f)
        ring.snapTo(0f)
        coroutineScope {
            launch {
                splash.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
                splash.animateTo(0f, tween(620, easing = LinearEasing))
            }
            launch {
                ring.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            }
        }
    }

    val motion = if (reducedMotion) 0f else 1f

    // El shimmer ambiental vivia en un `rememberInfiniteTransition`, o sea: recomposicion
    // infinita aunque la esfera no tenga nada que mostrar (en reposo, o en el onboarding).
    // Sobre el Helio G35 del Redmi 9C son cycles y bateria regalados, y en el test de
    // arranque de CI no dejaba idlear el looper (el OutOfMemoryError de ShadowTrace que
    // salio ahi era exactamente eso). La superficie ahora respira solo mientras hay una
    // transferencia en curso, y con ciclos acotados: ~17 min y para.
    val phase = remember { Animatable(0f) }
    val breathe = remember { Animatable(1f) }
    LaunchedEffect(active, reducedMotion) {
        if (!active || motion == 0f) {
            phase.snapTo(0f)
            breathe.snapTo(1f)
            return@LaunchedEffect
        }
        repeat(240) {
            launch {
                breathe.animateTo(
                    if (it % 2 == 0) 1f + 0.015f else 1f - 0.015f,
                    tween(4400, easing = FastOutSlowInEasing),
                )
            }
            launch {
                phase.animateTo(phase.value + (PI * 2).toFloat(), tween(3600, easing = LinearEasing))
            }
            delay(4400)
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {

        // 1 · halo: luz que escapa del vidrio
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.55f * intensity }
                .moonBlur(30.dp)
                .background(
                    Brush.radialGradient(
                        0f to Aqua.copy(alpha = 0.30f * intensity),
                        0.45f to Blue.copy(alpha = 0.16f * intensity),
                        1f to Color.Transparent,
                    )
                )
        )

        Box(
            Modifier
                .size(188.dp)
                .graphicsLayer {
                    scaleX = breathe.value
                    scaleY = breathe.value
                    alpha = if (active) 1f else 0.55f
                }
        ) {
            Canvas(Modifier.fillMaxSize().clipToBounds()) {
                // En el primer frame de algunos ROMs el canvas llega con size 0 y un
                // radio nulo en Brush.radialGradient revienta el draw. Se corta temprano.
                if (size.width < 1f || size.height < 1f) return@Canvas
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = (minOf(size.width, size.height) / 2f).coerceAtLeast(1f)

                // 2 · cuerpo de vidrio: centro casi vacío, canto con color
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.05f * intensity),
                        0.55f to Aqua.copy(alpha = 0.10f * intensity),
                        0.86f to Blue.copy(alpha = 0.26f * intensity),
                        1f to OrbEdge.copy(alpha = 0.05f),
                        center = c,
                        radius = r,
                    ),
                    radius = r,
                    center = c,
                )

                // 3 · el agua, recortada a la esfera
                val sphere = Path().apply {
                    addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r))
                }
                drawContext.canvas.save()
                drawContext.canvas.clipPath(sphere)

                val surfaceY = c.y + r - (r * 2f) * (0.06f + 0.9f * level.value)
                val amp = size.width * 0.022f * (1f + splash.value * 2.6f) * motion
                val water = Path().apply {
                    moveTo(0f, surfaceY)
                    val step = size.width / 28f
                    var x = 0f
                    while (x < size.width) {
                        val nx = minOf(x + step, size.width)
                        val y = surfaceY +
                            sin((nx / size.width) * PI.toFloat() * 3f + phase.value) * amp +
                            sin((nx / size.width) * PI.toFloat() * 7f - phase.value * 1.7f) * amp * 0.35f
                        lineTo(nx, y)
                        x = nx
                    }
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(
                    path = water,
                    brush = Brush.verticalGradient(
                        0f to AquaSoft.copy(alpha = 0.55f * intensity),
                        0.35f to Aqua.copy(alpha = 0.40f * intensity),
                        1f to BlueDeep.copy(alpha = 0.55f * intensity),
                        startY = surfaceY,
                        endY = size.height,
                    ),
                )
                drawContext.canvas.restore()

                // 4 · especular: la marca del vidrio pulido
                drawOval(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.55f * intensity), Color.Transparent),
                        center = Offset(c.x - r * 0.34f, c.y - r * 0.44f),
                        radius = (r * 0.42f).coerceAtLeast(1f),
                    ),
                    topLeft = Offset(c.x - r * 0.66f, c.y - r * 0.76f),
                    size = Size(r * 0.86f, r * 0.52f),
                )

                // 5 · caústica: luz rebotada abajo, da peso y volumen
                drawOval(
                    brush = Brush.radialGradient(
                        listOf(OrbCore.copy(alpha = 0.30f * intensity), Color.Transparent),
                        center = Offset(c.x, c.y + r * 0.62f),
                        radius = (r * 0.6f).coerceAtLeast(1f),
                    ),
                    topLeft = Offset(c.x - r * 0.8f, c.y + r * 0.16f),
                    size = Size(r * 1.6f, r * 0.9f),
                )

                // 6 · onda que sale cuando un archivo termina
                if (ring.value > 0.001f) {
                    drawCircle(
                        color = Aqua.copy(alpha = (1f - ring.value) * 0.35f * intensity),
                        radius = r * (0.35f + ring.value * 0.75f),
                        center = c,
                        style = Stroke(width = (2.5f - ring.value * 2f).dp.toPx()),
                    )
                }
            }
        }

        // 7 · sombra de contacto: ancla la esfera a la pantalla
        Box(
            Modifier
                .size(width = 120.dp, height = 26.dp)
                .align(Alignment.BottomCenter)
                .graphicsLayer { translationY = 16.dp.toPx() }
                .moonBlur(18.dp)
                .background(
                    Brush.radialGradient(listOf(Color(0x59000000), Color.Transparent))
                )
        )
    }
}

/** Pozo de luz detrás de la esfera. */
@Composable
fun OrbBackdrop(intensity: Float, modifier: Modifier = Modifier) {
    Box(modifier = modifier.moonBlur(60.dp).background(orbWell(alpha = 0.13f * intensity)))
}

/**
 * `Modifier.blur` usa RenderEffect, que recién existe en API 31. Por debajo la
 * documentación de Compose dice "no-op", pero en varias ROMs (MIUI 12 incluida)
 * un RenderEffect nulo en un nodo con shadow deja artefactos o revienta el draw.
 * Como la esfera se compone en el primer frame, acá se prefiere no desenfocar a
 * arriesgar la app entera: el gradiente radial ya hace el trabajo visual.
 */
private fun Modifier.moonBlur(radius: Dp): Modifier =
    if (Build.VERSION.SDK_INT >= 31) this.blur(radius) else this
