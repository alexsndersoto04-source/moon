package com.moonshare.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moonshare.core.MoonLog
import com.moonshare.core.perm.MoonPermissions
import com.moonshare.transfer.EngineMode
import com.moonshare.ui.common.MoonSwitch
import com.moonshare.ui.common.PrimaryButton
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.Danger
import com.moonshare.ui.theme.InkFaint
import com.moonshare.ui.theme.Ok
import com.moonshare.vm.MoonViewModel
import com.moonshare.vm.sizeLabel

/**
 * Pantalla principal deliberadamente simple: elegir qué enviar y dejar este
 * teléfono disponible. El acercamiento hace el resto; no hay modo Xender de
 * buscar, elegir un par y confirmar cada paso.
 */
@Composable
fun HomeScreen(
    vm: MoonViewModel,
    onSend: () -> Unit,
    onOpenTransfer: () -> Unit,
    onOpenSettings: () -> Unit,
    onHistory: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val engine by vm.engineState.collectAsState()
    val offer by vm.pendingOffer.collectAsState()
    val context = LocalContext.current

    val hosting = engine.isHosting
    val active = engine.mode != EngineMode.Idle
    val wifiOn = runCatching {
        (context.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
            ?.isWifiEnabled != false
    }.getOrDefault(true)

    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (MoonPermissions.hasAll(context, MoonPermissions.nearby())) {
            permissionMessage = null
            vm.clearError()
            vm.startReceiving()
        } else {
            permissionMessage =
                "MoonShare necesita los permisos de cercanía de Android para encontrar y conectarse con el otro teléfono. Sin esos permisos, Android bloquea la Disponibilidad."
        }
    }
    fun startAvailability() {
        val missing = MoonPermissions.missing(context, MoonPermissions.nearby())
        if (missing.isEmpty()) {
            permissionMessage = null
            vm.clearError()
            vm.startReceiving()
        } else {
            nearbyPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Aqua),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.NearMe, null, tint = Color.White, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "MoonShare",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (ui.settings.deviceNickname.isNotBlank()) ui.settings.deviceNickname else "Compartir al acercar",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onOpenSettings() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Settings, "Ajustes",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(34.dp))
        Text(
            "Pasá archivos\ncon solo acercar",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Elegí lo que querés enviar, juntá los teléfonos y la transferencia empieza sola.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.92f),
        )

        Spacer(Modifier.height(28.dp))
        PrimaryButton(
            text = if (ui.selected.isNotEmpty()) "Seguir eligiendo (${ui.selected.size})" else "Elegir fotos, videos o archivos",
            onClick = onSend,
        )

        if (active) {
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = if (engine.mode == EngineMode.Sending) "Ver envío en curso" else "Ver recepción en curso",
                onClick = onOpenTransfer,
            )
        }

        Spacer(Modifier.height(18.dp))

        // Disponibilidad: esta es la única preparación del receptor. Queda activa
        // mediante el servicio en primer plano aunque se minimice la app.
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Disponibilidad",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            hosting -> "Estás disponible. Al juntar los teléfonos, recibís sin aceptar cada envío."
                            else -> "Activala una vez para recibir al acercar, sin tocar botones en cada envío."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(14.dp))
                MoonSwitch(
                    checked = hosting,
                    onChange = { enabled ->
                        if (enabled) startAvailability() else vm.stopReceiving()
                    },
                )
            }
            if (hosting) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Ok))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Lista incluso con la app minimizada",
                        style = MaterialTheme.typography.labelLarge,
                        color = Ok,
                    )
                }
            }
        }

        permissionMessage?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Danger)
        }

        if (!wifiOn) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFFFF4E5)).padding(16.dp),
            ) {
                Text(
                    "El Wi-Fi está apagado",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF93370D),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Android necesita Wi-Fi para crear el enlace directo entre teléfonos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB54708),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Abrir ajustes de Wi-Fi",
                    style = MaterialTheme.typography.labelLarge,
                    color = Aqua,
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { MoonLog.w("UI", "no se pudo abrir el panel de Wi-Fi") }
                    },
                )
            }
        }

        // Una oferta sin toque NFC sí se confirma: el toque físico es consentimiento,
        // pero una conexión remota sin ese gesto no debe entrar sola.
        AnimatedVisibility(
            visible = offer != null,
            enter = slideInVertically(tween(240)) { it / 3 } + fadeIn(),
            exit = fadeOut(tween(160)),
        ) {
            offer?.let { incoming ->
                Column {
                    Spacer(Modifier.height(16.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(18.dp),
                    ) {
                        Text(
                            "${incoming.fromName} quiere enviarte archivos",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "${incoming.tasks.size} archivo(s) · ${incoming.totalBytes.sizeLabel()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        incoming.tasks.take(3).forEach { task ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                task.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) {
                                PrimaryButton(
                                    text = "Rechazar",
                                    onClick = { vm.respondToOffer(false) },
                                    tint = Color(0xFFE3E9F0),
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                PrimaryButton(
                                    text = "Recibir",
                                    onClick = { vm.respondToOffer(true) },
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = engine.error != null, enter = fadeIn(), exit = fadeOut()) {
            engine.error?.let { error ->
                Column {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Danger,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Reintentar",
                        style = MaterialTheme.typography.labelLarge,
                        color = Aqua,
                        modifier = Modifier.clickable {
                            if (!hosting) startAvailability() else vm.clearError()
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .clickable { onHistory() }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.History, null, tint = Aqua, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Actividad reciente",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}
