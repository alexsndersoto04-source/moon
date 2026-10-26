package com.moonshare.ui.transfer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moonshare.core.MoonLog
import com.moonshare.data.model.TaskState
import com.moonshare.data.model.TransferTask
import com.moonshare.transfer.EngineMode
import com.moonshare.transfer.EnginePhase
import com.moonshare.transfer.OrbPulse
import com.moonshare.ui.common.GhostButton
import com.moonshare.ui.common.GlowProgress
import com.moonshare.ui.glass.CrystalOrb
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.Danger
import com.moonshare.ui.theme.InkFaint
import com.moonshare.ui.theme.NumericStyle
import com.moonshare.ui.theme.Ok
import com.moonshare.vm.MoonViewModel
import com.moonshare.vm.sizeLabel

/**
 * Pantalla de la sesión en curso: la esfera grande arriba como medidor y la cola
 * abajo, con un estado visible por archivo. Sin diálogos modales: se puede ir y
 * venir, la transferencia vive en el servicio.
 */
@Composable
fun TransferScreen(vm: MoonViewModel, onBack: () -> Unit) {
    val engine by vm.engineState.collectAsState()
    val ui by vm.ui.collectAsState()

    var pulse by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        vm.pulses.collect { p: OrbPulse -> pulse = p.nonce }
    }

    val active = engine.mode != EngineMode.Idle
    val tasks = engine.tasks

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack, "Volver",
                    tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (engine.mode) {
                        EngineMode.Sending -> "Enviando a ${engine.peerName}"
                        EngineMode.Receiving -> "Recibiendo de ${engine.peerName}"
                        else -> "Actividad"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    engine.statusText.ifBlank {
                        when {
                            engine.isHosting -> "esperando el acercamiento"
                            engine.finished -> "listo"
                            else -> "sin actividad"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                )
            }
            if (active) {
                GhostButton(text = "Cancelar", onClick = { vm.cancelTransfer() })
            }
        }

        Spacer(Modifier.height(8.dp))

        // ------------------------------------------------- esfera medidora
        if (ui.settings.orbEnabled) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(232.dp),
                contentAlignment = Alignment.Center,
            ) {
                CrystalOrb(
                    modifier = Modifier.size(200.dp),
                    progress = engine.progress,
                    pulseNonce = pulse,
                    intensity = ui.settings.orbIntensity,
                    reducedMotion = ui.settings.reduceMotion,
                    active = active,
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.alpha(if (ui.settings.orbEnabled) 1f else 0f),
                ) {
                    Text(
                        "${tasks.count { it.state == TaskState.Done }}/${tasks.size}",
                        style = NumericStyle,
                        color = if (active) Aqua else Color.White.copy(alpha = 0.22f),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${tasks.sumOf { it.transferred }.sizeLabel()} de ${tasks.sumOf { it.size }.sizeLabel()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint,
                    )
                }
            }
        }

        GlowProgress(
            progress = engine.progress,
            modifier = Modifier.fillMaxWidth(),
            color = if (engine.error != null) Danger else Aqua,
        )

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                MoonLog.rate(engine.speedBps.toDouble()),
                style = MaterialTheme.typography.labelMedium,
                color = Aqua.copy(alpha = 0.85f),
            )
            Text(
                "${tasks.sumOf { it.size }.sizeLabel()} en total",
                style = MaterialTheme.typography.labelMedium,
                color = InkFaint,
            )
        }
        Spacer(Modifier.height(12.dp))

        // ------------------------------------------------- cola
        AnimatedVisibility(visible = tasks.isEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.NearMe, null, tint = InkFaint, modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Cuando acerques otro teléfono, va a aparecer acá.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sin NFC, elegí el dispositivo de la lista y se conecta solo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                )
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(tasks, key = { it.itemId }) { task ->
                TaskRow(task)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TaskRow(task: TransferTask) {
    val (color, icon, label) = when (task.state) {
        TaskState.Done -> Triple(Ok, Icons.Rounded.CheckCircle, "listo")
        TaskState.Failed -> Triple(Danger, Icons.Rounded.ErrorOutline, task.error ?: "error")
        TaskState.Rejected -> Triple(InkFaint, Icons.Rounded.ErrorOutline, "rechazado")
        TaskState.Canceled -> Triple(InkFaint, Icons.Rounded.ErrorOutline, "cancelado")
        TaskState.Streaming -> Triple(Aqua, Icons.Rounded.HourglassTop, "pasando…")
        TaskState.AwaitingConsent -> Triple(Aqua, Icons.Rounded.NearMe, "esperando ok")
        TaskState.Queued -> Triple(InkFaint, Icons.Rounded.HourglassTop, "en cola")
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(5.dp))
            if (task.state.isActive) {
                GlowProgress(task.progress, color = Aqua)
            } else {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (task.state == TaskState.Streaming) {
                "${task.transferred.sizeLabel()} / ${task.size.sizeLabel()}"
            } else {
                task.size.sizeLabel()
            },
            style = MaterialTheme.typography.labelSmall,
            color = InkFaint,
        )
    }
}
