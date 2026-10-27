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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.moonshare.ui.common.GhostButton
import com.moonshare.ui.common.GlowProgress
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.Danger
import com.moonshare.ui.theme.InkFaint
import com.moonshare.ui.theme.NumericStyle
import com.moonshare.ui.theme.Ok
import com.moonshare.vm.MoonViewModel
import com.moonshare.vm.sizeLabel

/**
 * Pantalla de la sesión en curso: resumen claro de progreso y una fila por
 * archivo. La transferencia vive en el servicio, no en esta pantalla.
 */
@Composable
fun TransferScreen(vm: MoonViewModel, onBack: () -> Unit) {
    val engine by vm.engineState.collectAsState()
    val ui by vm.ui.collectAsState()

    val active = engine.mode != EngineMode.Idle
    val tasks = engine.tasks

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
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

        // Resumen sobrio: porcentaje y bytes. Sin esfera ni espectáculo visual.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${(engine.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${tasks.count { it.state == TaskState.Done }} de ${tasks.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 7.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${tasks.sumOf { it.transferred }.sizeLabel()} de ${tasks.sumOf { it.size }.sizeLabel()}",
                style = MaterialTheme.typography.bodyMedium,
                color = InkFaint,
            )
        }
        Spacer(Modifier.height(16.dp))

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
            .background(MaterialTheme.colorScheme.surfaceVariant)
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
