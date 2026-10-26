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
import android.net.wifi.p2p.WifiP2pDevice
import com.moonshare.core.MoonLog
import androidx.compose.ui.platform.LocalContext
import com.moonshare.ui.common.MoonSwitch
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
import com.moonshare.ui.theme.InkMedium
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
    val peers by vm.peers.collectAsState()

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

        Spacer(Modifier.height(20.dp))

        // ---------------------------------------------------------- estado
        // Una línea de texto, como la fila de estado de AirDrop. Antes había un pozo de
        // luz, una esfera de 230 dp girando "en espera" y tres píldoras de colores:
        // demasiado teatro para una app que todavía no hizo nada.
        val ctx = LocalContext.current
        val radioOn = runCatching {
            (ctx.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                ?.isWifiEnabled != false
        }.getOrDefault(true)
        val awaitingTransfer = active
        val (stateText, stateColor) = when {
            awaitingTransfer -> "Pasando ${engine.tasks.count { !it.state.name.endsWith("Done") }} de ${engine.tasks.size} archivo(s)" to Aqua
            hosting -> "Listo para recibir · acercá el otro teléfono" to Ok
            !radioOn -> "El Wi‑Fi está apagado" to Warn
            else -> "Tocá Recibir para que te puedan pasar archivos" to InkFaint
        }
        StatusChip(stateText, stateColor)
        if (!radioOn) {
            Spacer(Modifier.height(2.dp))
            Text(
                "Abrir ajustes de Wi‑Fi",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        // Accion directa al panel del sistema: mas util que explicar
                        // en un parrafo donde tocar.
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { MoonLog.w("UI", "no se pudo abrir el panel de Wi-Fi") }
                    }
                    .padding(vertical = 4.dp),
            )
        }

        // ---------------------------------------------------------- la esfera, solo si está pasando algo
        if (awaitingTransfer && s.orbEnabled) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(196.dp),
                contentAlignment = Alignment.Center,
            ) {
                CrystalOrb(
                    modifier = Modifier.size(178.dp),
                    progress = engine.progress,
                    intensity = s.orbIntensity,
                    reducedMotion = s.reduceMotion,
                    active = true,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${(engine.progress * 100).toInt()}%",
                        style = NumericStyle,
                        color = Color.White,
                    )
                    if (engine.speedBps > 0) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            MoonLog.rate(engine.speedBps.toDouble()),
                            style = MaterialTheme.typography.labelMedium,
                            color = Aqua.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------- cercanos
        if (hosting) {
            Spacer(Modifier.height(18.dp))
            Text(
                if (peers.isEmpty()) "Buscando teléfonos cerca…" else "Cerca de vos",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.3.sp,
            )
            if (peers.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Del otro lado tiene que estar MoonShare abierto en “Recibir”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                )
            }
            Column(Modifier.fillMaxWidth()) {
                peers.take(6).forEach { d: WifiP2pDevice ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.refreshPeers() }
                            .padding(vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            d.deviceName?.takeIf { it.isNotBlank() } ?: "dispositivo sin nombre",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (d.status == WifiP2pDevice.STATUS_CONNECTED) "conectado" else "disponible",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkMedium,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---------------------------------------------------------- acciones
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
        // Texto + accion. Sin caja de relleno rojo: una tarjeta de error sobre la
        // pantalla principal convierte un detalle en un susto.
        AnimatedVisibility(visible = engine.error != null, enter = fadeIn(), exit = fadeOut()) {
            engine.error?.let { err ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "Reintentar",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                vm.clearError()
                                vm.startReceiving()
                            }
                            .padding(horizontal = 2.dp, vertical = 4.dp),
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
    // Fila de ajuste con switch, como las preferencias del sistema. Antes era un boton
    // gigante con relleno turquesa que competia con "Elegir que enviar" por la atencion.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Recibir",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (hosting) "Acercá el otro teléfono y listo"
                else "Deja este teléfono disponible para que te pasen archivos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(14.dp))
        MoonSwitch(
            checked = hosting,
            onChange = { if (!active) onToggle() },
        )
    }
}
