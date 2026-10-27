package com.moonshare.ui.history

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
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moonshare.core.MoonLog
import com.moonshare.data.model.HistoryEntry
import com.moonshare.ui.common.EmptyState
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.InkFaint
import com.moonshare.vm.MoonViewModel
import java.text.DateFormat
import java.util.Date

/** Registro local de lo que pasó por el teléfono. Nada se sube a ningún lado. */
@Composable
fun HistoryScreen(vm: MoonViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.loadHistory() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
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
                    "Actividad",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${ui.history.size} pasadas · solo en este teléfono",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                )
            }
            if (ui.history.isNotEmpty()) {
                Text(
                    "Limpiar",
                    style = MaterialTheme.typography.labelMedium,
                    color = Aqua,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.clearHistory() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        if (ui.history.isEmpty()) {
            EmptyState(
                text = "Todavía no pasaste nada",
                hint = "Cada archivo que envíes o recibas queda anotado acá, con su tamaño y con quién.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ui.history, key = { "${it.timestampMs}-${it.name}" }) { e ->
                    HistoryRow(e)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry) {
    val received = e.direction == "Receive"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (received) Icons.Rounded.DownloadForOffline
            else Icons.AutoMirrored.Rounded.DriveFileMove,
            null,
            tint = if (e.ok) Aqua else InkFaint,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${e.peer.ifBlank { "—" }} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.timestampMs))}",
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (e.ok) MoonLog.human(e.size) else "falló",
            style = MaterialTheme.typography.labelSmall,
            color = if (e.ok) MaterialTheme.colorScheme.onSurfaceVariant else InkFaint,
        )
    }
}
