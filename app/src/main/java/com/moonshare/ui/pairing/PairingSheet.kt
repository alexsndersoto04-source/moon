package com.moonshare.ui.pairing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moonshare.data.model.FileItem
import com.moonshare.nfc.NfcAvailability
import com.moonshare.ui.common.PrimaryButton
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.Danger
import com.moonshare.ui.theme.InkFaint
import com.moonshare.ui.theme.Ok
import com.moonshare.ui.theme.PillShape
import com.moonshare.vm.MoonViewModel
import com.moonshare.vm.sizeLabel

/**
 * La hoja de pareo. Es el corazón de la promesa "se unen los teléfonos y se pasa":
 * acá el usuario acerca el teléfono (o toca un dispositivo de la lista) y el envío
 * arranca sin otro paso.
 *
 * Tres rutas, de mejor a peor:
 *   1. golpe de NFC → el token trae la dirección del par → connect() directo
 *   2. tap en la lista de descubiertos → mismo connect(), un toque más
 *   3. "reintentar" si la radio del otro lado todavía no levantó el grupo
 */
@Composable
fun PairingSheet(vm: MoonViewModel, onDone: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val engine by vm.engineState.collectAsState()
    val peers by vm.peers.collectAsState()
    val token by vm.nfcToken.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val items = ui.selectedItems.ifEmpty { ui.catalog.items.filter { it.id in ui.selected } }
    val bytes = items.sumOf { it.size }

    DisposableEffect(Unit) {
        if (ui.settings.nfcBumpEnabled && vm.nfcSupported) {
            scanning = vm.startNfcScan { msg -> notice = msg }
        }
        onDispose { vm.stopNfcScan() }
    }

    // El token ya dispara el envío por sí solo; acá sólo acompañamos la salida.
    LaunchedEffect(token) {
        if (token != null) {
            vm.notifyPaired()
            onDone()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        // asa de arrastre: el único "indicador" que se permite, y es luz, no línea
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(38.dp).height(4.dp)
                .clip(PillShape)
                .background(Color.White.copy(alpha = 0.18f))
        )
        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            BumpGlyph(active = scanning)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Acercá los dos teléfonos",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        scanning -> "Leyendo el otro aparato por NFC…"
                        !ui.nfcSupported -> "Este teléfono no tiene NFC: elegilo de la lista"
                        else -> "Encendé NFC en el otro y tocalo contra este"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (scanning) Aqua else InkFaint,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Aqua.copy(alpha = 0.08f))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${items.size} archivo(s)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${bytes.sizeLabel()} · destino: ${engine.peerName.ifBlank { "sin parear todavía" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Dispositivos alcanzables",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(Modifier.height(6.dp))

        if (peers.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Wifi, null, tint = InkFaint, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Nada todavía. El otro teléfono tiene que estar en “Recibiendo”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Buscar",
                    style = MaterialTheme.typography.labelMedium,
                    color = Aqua,
                    modifier = Modifier.clickable { vm.refreshPeers() },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.height((peers.size * 56).dp.coerceAtMost(190.dp)),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(peers, key = { it.deviceAddress ?: (it.deviceName ?: it.hashCode().toString()) }) { dev ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .clickable {
                                val t = com.moonshare.nfc.PairingToken(
                                    deviceAddress = dev.deviceAddress ?: "",
                                    deviceName = dev.deviceName ?: "dispositivo",
                                )
                                vm.sendSelected(t)
                                onDone()
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(9.dp).clip(CircleShape).background(
                                if (dev.status == 0) Ok else InkFaint
                            )
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            dev.deviceName ?: "anónimo",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when (dev.status) {
                                0 -> "disponible"
                                1 -> "conectándose"
                                2 -> "conectado"
                                else -> "ocupado"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = InkFaint,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = notice != null, enter = fadeIn(), exit = fadeOut()) {
            notice?.let {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Danger,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            text = if (token != null) "Pareado, pasando…" else "Enviar igual al último",
            onClick = {
                val last = vm.handoffToken
                if (last == null && peers.isEmpty()) {
                    notice = "Nada elegido: acercá los teléfonos o tocá un dispositivo."
                } else {
                    vm.sendSelected(last)
                    onDone()
                }
            },
            icon = Icons.Rounded.Nfc,
            enabled = items.isNotEmpty(),
        )
        Spacer(Modifier.height(6.dp))
    }
}

/** El ícono del "acercar": dos dispositivos con ondas que laten. */
@Composable
private fun BumpGlyph(active: Boolean) {
    val t = rememberInfiniteTransition(label = "bump")
    val pulse by t.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "p",
    )
    Box(
        Modifier.size(54.dp).clip(CircleShape)
            .background(if (active) Aqua.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.05f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Nfc, null,
            tint = if (active) Aqua else InkFaint,
            modifier = Modifier.size(24.dp).alpha(if (active) 0.55f + pulse * 0.45f else 1f),
        )
    }
}

/** Resumen compacto de lo que se va a pasar, reutilizado en la hoja y en el home. */
@Composable
fun SelectionSummary(items: List<FileItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Column(modifier) {
        items.take(3).forEach {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    it.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(it.size.sizeLabel(), style = MaterialTheme.typography.labelSmall, color = InkFaint)
            }
        }
        if (items.size > 3) {
            Text("+${items.size - 3} más", style = MaterialTheme.typography.labelSmall, color = InkFaint)
        }
    }
}
