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
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.InkFaint
import com.moonshare.ui.theme.PillShape

/**
 * Piezas compartidas. Ninguna dibuja un borde: los controles se delimitan por
 * relleno y por la sombra tintada de [com.moonshare.ui.glass.moonGlass].
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
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track by animateColorAsState(
        if (checked) Aqua.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.07f),
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
                .background(if (checked) Aqua else Color.White.copy(alpha = 0.55f))
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
            inactiveTrackColor = Color.White.copy(alpha = 0.10f),
            activeTrackColor = Aqua.copy(alpha = 0.75f),
        ),
    )
}

/** Chip de estado: punto de color + texto. Sin contorno. */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    indeterminate: Boolean = false,
) {
    Row(
        modifier
            .clip(PillShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (indeterminate) {
            CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.4.dp, color = color)
        } else {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
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
    val bg by animateColorAsState(
        if (enabled) tint.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.04f),
        tween(160), label = "btnbg",
    )
    Row(
        modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(bg)
            .clickable(enabled = enabled && !loading) { onClick() }
            .padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = tint)
        } else if (icon != null) {
            Icon(
                icon, contentDescription = null, tint = tint,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) tint else InkFaint,
        )
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .clip(PillShape)
            .background(Color.White.copy(alpha = 0.05f))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            .background(Color.White.copy(alpha = 0.06f))
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
                if (checked) Aqua else Color.White.copy(alpha = 0.10f)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Rounded.Check, contentDescription = null,
                tint = Color(0xFF06231F),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
