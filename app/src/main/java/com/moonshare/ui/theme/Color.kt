package com.moonshare.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta MoonShare.
 *
 * Criterio: UI neutra y un solo acento, como hace iOS. El turquesa NO se usa de
 * adorno en botones ni en chips — vive en la esfera y en el agua del progreso, que es
 * el único lugar donde la app "se pone linda": cuando un archivo está pasando.
 * Ningún color de borde, ninguna superficie de relleno agresiva.
 */

// Acento (azul de sistema, no neón)
val Aqua = Color(0xFF3F8EF7)
val AquaSoft = Color(0xFF82BAFF)
val AquaDeep = Color(0xFF1C5FB8)
val Blue = Color(0xFF0A84FF)
val BlueSoft = Color(0xFF6E9EFF)
val BlueDeep = Color(0xFF0E2C4A)

// Superficies oscuras: negro real y grises de elevación mínimos
val NightBg = Color(0xFF000000)
val NightBgAmoled = Color(0xFF000000)
val NightSurface = Color(0xFF0A0A0B)
val NightSurfaceHigh = Color(0xFF141416)

// Vidrio: rellenos casi invisibles (antes se leían "tarjeta futurista")
val GlassFillDark = Color(0x0AFFFFFF)
val GlassFillDarkStrong = Color(0x12FFFFFF)
val GlassTopLight = Color(0x0AFFFFFF)
val GlassBottomShade = Color(0x06000000)
val GlassFillLight = Color(0xB3FFFFFF)

// Superficies claras
val DayBg = Color(0xFFF2F2F7)
val DaySurface = Color(0xFFFFFFFF)

// Texto: jerarquía por color y tamaño, nunca por contorno
val InkHigh = Color(0xFFFFFFFF)
val InkMedium = Color(0xFF98989F)
val InkFaint = Color(0xFF5A5A60)
val InkHighLight = Color(0xFF000000)
val InkMediumLight = Color(0xFF6D6D72)

// Semánticos (iOS system colors, apagados lo justo)
val Ok = Color(0xFF30D158)
val Warn = Color(0xFFFF9F0A)
val Danger = Color(0xFFFF453A)

// Esfera: azul + turquesa, como se pidió. Acá sí puede brillar.
val OrbCore = Color(0xFFCFF3EE)
val OrbMid = Color(0xFF2E93B8)
val OrbEdge = Color(0xFF0C2740)
val OrbGlow = Color(0x2E3F8EF7)
