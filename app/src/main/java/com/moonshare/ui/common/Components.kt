package com.moonshare.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.InkFaint
import com.moonshare.ui.theme.PillShape

/**
 * Piezas compartidas. Sin bordes decorativos: los controles se distinguen por
 * fondo, color y espacio, como en una app de sistema.
 */

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String = "") {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        if (trailing.isNotEmpty()) {
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = InkFaint)
        }
    }
}

/** Fila de ajuste: título + descripción + control. Alturas generosas, cero líneas. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    control: @Composable (() -> Unit)? = null,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "enabled")
    Row(
        modifier
            .fillMaxWidth()
            .alpha(alpha)
            .clip(RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        control?.invoke()
    }
}

/** El switch de la casa: píldora con luz, no el toggle de Material con su track duro. */
@Composable
fun MoonSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    val track by animateColorAsState(
        if (checked) Aqua else Color(0xFFE3E9F0),
        tween(180), label = "track",
    )
    Box(
        modifier
            .width(52.dp).height(31.dp)
            .clip(PillShape)
            .background(track)
            .clickable { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        val knobScale by animateFloatAsState(if (checked) 1f else 0.86f, tween(180), label = "knob")
        Box(
            Modifier
                .padding(3.dp)
                .size(25.dp)
                .scale(knobScale)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun MoonSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier.height(30.dp),
        colors = SliderDefaults.colors(
            thumbColor = Aqua,
            inactiveTrackColor = Color(0xFFE3E9F0),
            activeTrackColor = Aqua.copy(alpha = 0.75f),
        ),
    )
}

/**
 * Estado: un punto de color y texto secundario. Antes era una píldora rellena de
 * color al 12% por estado, y eso (tres píldoras de colores distintos juntas) era la
 * mitad de lo que se leía como "interfaz de nave".
 */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    indeterminate: Boolean = false,
) {
    Row(
        modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (indeterminate) {
            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.3.dp, color = color)
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    tint: Color = Aqua,
    loading: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) tint else Color(0xFFE3E9F0))
            .clickable(enabled = enabled && !loading) { onClick() }
            .padding(horizontal = 18.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
            ),
            color = if (enabled) Color.White else InkFaint,
        )
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
fun EmptyState(text: String, hint: String = "", modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 46.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hint.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = InkFaint,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Barra de progreso sin surco: una línea de luz, no un riel. */
@Composable
fun GlowProgress(progress: Float, modifier: Modifier = Modifier, color: Color = Aqua) {
    val anim by animateFloatAsState(progress.coerceIn(0f, 1f), tween(180), label = "prog")
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFFE6EEF7))
    ) {
        Box(
            Modifier
                .fillMaxWidth(anim)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.linearGradient(listOf(color.copy(alpha = 0.45f), color)))
        )
    }
}

@Composable
fun CheckDot(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (checked) Aqua else Color(0xFFE3E9F0)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Rounded.Check, contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
