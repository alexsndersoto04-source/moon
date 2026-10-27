package com.moonshare.ui.pairing

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moonshare.data.model.FileItem
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.Danger
import com.moonshare.ui.theme.InkFaint
import com.moonshare.vm.MoonViewModel
import com.moonshare.vm.sizeLabel

/**
 * Hoja translúcida de proximidad. Aparece al elegir archivos: no pide tocar
 * “Enviar”. El lector NFC queda activo y, al juntar los teléfonos, el token
 * dispara la transferencia automáticamente.
 *
 * La lista de dispositivos es solo un respaldo explícito para equipos sin NFC;
 * nunca se elige un teléfono automáticamente desde esa lista.
 */
@Composable
fun PairingSheet(vm: MoonViewModel, onDone: () -> Unit, onDismiss: () -> Unit = {}) {
    val ui by vm.ui.collectAsState()
    val engine by vm.engineState.collectAsState()
    val peers by vm.nearbyDevices.collectAsState()
    val token by vm.nfcToken.collectAsState()
    var notice by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    val items = ui.selectedItems
    val bytes = items.sumOf { it.size }

    DisposableEffect(ui.settings.nfcBumpEnabled, vm.nfcSupported) {
        vm.prepareToSend()
        scanning = if (ui.settings.nfcBumpEnabled && vm.nfcSupported) {
            vm.startNfcScan { msg -> notice = msg }
        } else {
            vm.refreshPeers()
            false
        }
        onDispose { vm.stopNfcScan() }
    }

    LaunchedEffect(token) {
        if (token != null) {
            vm.notifyPaired()
            onDone()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
            .background(Color.White.copy(alpha = 0.97f))
            .padding(horizontal = 22.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, "Cerrar", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(4.dp))
        ProximityGlyph(active = scanning)
        Spacer(Modifier.height(18.dp))
        Text(
            "Acercá el otro teléfono",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            when {
                scanning -> "Al juntarse, el envío empieza solo."
                !vm.nfcSupported -> "Este teléfono no tiene NFC. Elegí el otro equipo de la lista."
                !ui.settings.nfcBumpEnabled -> "El emparejamiento al acercar está desactivado en Ajustes."
                else -> "Verificá que NFC esté encendido en los dos teléfonos."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = InkFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.9f),
        )

        Spacer(Modifier.height(18.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${items.size} archivo(s) listos",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                bytes.sizeLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = InkFaint,
            )
            items.take(2).forEach { item ->
                Spacer(Modifier.height(7.dp))
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!vm.nfcSupported || !ui.settings.nfcBumpEnabled || peers.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Wifi, null, tint = Aqua, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Dispositivos cercanos",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Buscar",
                    style = MaterialTheme.typography.labelLarge,
                    color = Aqua,
                    modifier = Modifier.clickable { vm.refreshPeers() },
                )
            }
            Spacer(Modifier.height(8.dp))
            if (peers.isEmpty()) {
                Text(
                    "Todavía no aparece ninguno. El otro teléfono debe tener la Disponibilidad activada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.height((peers.size * 58).dp.coerceAtMost(180.dp)),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(peers, key = { it.endpointId }) { device ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    vm.sendToNearbyEndpoint(device.endpointId)
                                    onDone()
                                }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Aqua))
                            Spacer(Modifier.width(11.dp))
                            Text(
                                device.deviceName.ifBlank { "Teléfono sin nombre" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("Enviar", style = MaterialTheme.typography.labelLarge, color = Aqua)
                        }
                    }
                }
            }
        }

        notice?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Danger, textAlign = TextAlign.Center)
        }
        engine.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Danger, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProximityGlyph(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "proximity")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "pulse",
    )
    Box(Modifier.size(118.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(112.dp).clip(CircleShape)
                .background(Aqua.copy(alpha = if (active) 0.06f + pulse * 0.04f else 0.07f)),
        )
        Box(
            Modifier.size(82.dp).clip(CircleShape)
                .background(Aqua.copy(alpha = if (active) 0.10f + pulse * 0.05f else 0.10f)),
        )
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Aqua),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Nfc, null,
                tint = Color.White,
                modifier = Modifier.size(27.dp).alpha(if (active) 0.65f + pulse * 0.35f else 1f),
            )
        }
    }
}

/** Resumen compacto reutilizado por otras pantallas. */
@Composable
fun SelectionSummary(items: List<FileItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Column(modifier) {
        items.take(3).forEach {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    it.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(it.sizeLabel(), style = MaterialTheme.typography.labelSmall, color = InkFaint)
            }
        }
        if (items.size > 3) {
            Text("+${items.size - 3} más", style = MaterialTheme.typography.labelSmall, color = InkFaint)
        }
    }
}
