package com.moonshare.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moonshare.core.MoonLog
import com.moonshare.transfer.EngineMode
import com.moonshare.ui.common.GhostButton
import com.moonshare.ui.common.PrimaryButton
import com.moonshare.ui.common.StatusChip
import com.moonshare.ui.glass.CrystalOrb
import com.moonshare.ui.glass.moonGlass
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.BlueSoft
import com.moonshare.ui.theme.Danger
import com.moonshare.ui.theme.InkFaint
import com.moonshare.ui.theme.NumericStyle
import com.moonshare.ui.theme.Ok
import com.moonshare.ui.theme.Warn
import com.moonshare.vm.MoonViewModel
import com.moonshare.vm.sizeLabel

@Composable
fun HomeScreen(vm: MoonViewModel, onSend: () -> Unit, onOpenTransfer: () -> Unit, onOpenSettings: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val engine by vm.engineState.collectAsState()
    val offer by vm.pendingOffer.collectAsState()
    val peers by vm.engine.discoveredPeers.collectAsState()

    val s = ui.settings
    val hosting = engine.isHosting
    val active = engine.mode.name != "Idle"

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- cabecera
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "MoonShare",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 1.6.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (s.deviceNickname.isNotBlank()) s.deviceNickname else "Listo para pasar",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .clickable { onOpenSettings() },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    Icons.Rounded.Tune, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp),
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---------------------------------------------------------- esfera
        Box(
            Modifier
                .fillMaxWidth()
                .height(312.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Pozo de luz: solo cuando hay algo en curso o estás escuchando.
            // Es un `if` y no AnimatedVisibility porque dentro de un Box con
            // contentAlignment el scope de animación no aplica.
            if (hosting || active) {
                Box(
                    Modifier
                        .size(300.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(Aqua.copy(alpha = 0.10f), Color.Transparent)
                            )
                        )
                )
            }
            if (s.orbEnabled) {
                CrystalOrb(
                    modifier = Modifier.size(230.dp),
                    progress = if (active) engine.progress else if (hosting) 0f else 0f,
                    intensity = s.orbIntensity,
                    reducedMotion = s.reduceMotion,
                    active = active || hosting,
                )
            }

            // estado al centro de la esfera
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        active -> "${(engine.progress * 100).toInt()}%"
                        hosting -> "ON"
                        else -> "—"
                    },
                    style = NumericStyle,
                    color = if (active || hosting) Aqua else Color.White.copy(alpha = 0.25f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        engine.mode == EngineMode.Receiving -> "recibiendo"
                        engine.mode == EngineMode.Sending -> "enviando"
                        hosting -> "escuchando"
                        else -> "en espera"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (engine.speedBps > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        MoonLog.rate(engine.speedBps.toDouble()),
                        style = MaterialTheme.typography.labelSmall,
                        color = Aqua.copy(alpha = 0.8f),
                    )
                }
            }
        }

        // ---------------------------------------------------------- estado
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                StatusChip(
                    if (hosting) "Wi-Fi Direct activo" else "Wi-Fi Direct inactivo",
                    if (hosting) Ok else Warn,
                )
            }
            item {
                StatusChip(
                    when {
                        !ui.nfcSupported -> "sin NFC"
                        hosting -> "NFC listo para el golpe"
                        else -> "NFC en espera"
                    },
                    if (ui.nfcSupported) Aqua else InkFaint,
                )
            }
            if (peers.isNotEmpty()) item {
                StatusChip("${peers.size} cerca", BlueSoft)
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---------------------------------------------------------- acciones
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimarySendButton(onSend = onSend, selected = ui.selected.size)
            ReceiveToggle(
                hosting = hosting,
                active = active,
                onToggle = { if (hosting) vm.stopReceiving() else vm.startReceiving() },
            )
        }

        // ---------------------------------------------------------- oferta entrante
        AnimatedVisibility(
            visible = offer != null,
            enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(),
            exit = fadeOut(tween(180)),
        ) {
            offer?.let { o ->
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth().moonGlass(elevation = 20.dp)) {
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            Icons.Rounded.NearMe, null, tint = Aqua, modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${o.fromName} quiere enviarte",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "${o.tasks.size} archivo(s) · ${o.totalBytes.sizeLabel()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        o.tasks.take(4).forEach { t ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(4.dp).height(4.dp).clip(CircleShape).background(Aqua.copy(alpha = 0.7f)))
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    t.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(t.size.sizeLabel(), style = MaterialTheme.typography.labelSmall, color = InkFaint)
                            }
                        }
                        if (o.tasks.size > 4) {
                            Text("y ${o.tasks.size - 4} más…", style = MaterialTheme.typography.labelSmall, color = InkFaint)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("Rechazar", { vm.respondToOffer(false) }, Modifier.weight(1f))
                        Box(Modifier.weight(1f)) {
                            PrimaryButton(
                                text = "Aceptar",
                                onClick = { vm.respondToOffer(true) },
                                tint = Ok,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }

        // ---------------------------------------------------------- error
        AnimatedVisibility(visible = engine.error != null, enter = fadeIn(), exit = fadeOut()) {
            engine.error?.let { err ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Danger.copy(alpha = 0.12f))
                        .clickable { vm.clearError() }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    androidx.compose.material3.Icon(
                        Icons.Rounded.Refresh, null, tint = InkFaint, modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        // ---------------------------------------------------------- atajos
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                text = if (active) "Ver" else "Actividad",
                onClick = onOpenTransfer,
                icon = if (active) Icons.Rounded.PlayArrow else Icons.Rounded.Bolt,
                modifier = Modifier.weight(1f),
            )
            GhostButton(
                text = "Buscar",
                onClick = { vm.refreshPeers() },
                icon = Icons.Rounded.Wifi,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun PrimarySendButton(onSend: () -> Unit, selected: Int) {
    PrimaryButton(
        text = if (selected > 0) "Enviar $selected" else "Elegir qué enviar",
        onClick = onSend,
        icon = Icons.Rounded.PlayArrow,
    )
}

@Composable
private fun ReceiveToggle(hosting: Boolean, active: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                if (hosting) Aqua.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.05f)
            )
            .clickable(enabled = !active) { onToggle() }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (hosting) "Recibiendo" else "Recibir",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (hosting) Aqua else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (hosting) "Acercá el otro teléfono y listo · tocá para parar"
                else "Deja este teléfono disponible para que te pasen archivos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Icon(
            if (hosting) Icons.Rounded.Stop else Icons.Rounded.NearMe,
            null,
            tint = if (hosting) Aqua else InkFaint,
            modifier = Modifier.size(20.dp),
        )
    }
}

