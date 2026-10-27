package com.moonshare.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.moonshare.core.perm.MoonPermissions
import com.moonshare.nfc.NfcAvailability
import com.moonshare.ui.common.MoonSlider
import com.moonshare.ui.common.MoonSwitch
import com.moonshare.ui.common.SectionLabel
import com.moonshare.ui.common.SettingRow
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.Danger
import com.moonshare.ui.theme.InkFaint
import com.moonshare.vm.MoonViewModel

/**
 * Ajustes mínimos y honestos: solo controles conectados al comportamiento real
 * de la app. Nada de interruptores decorativos ni funciones prometidas a medias.
 */
@Composable
fun SettingsScreen(vm: MoonViewModel, onBack: () -> Unit, onHistory: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val s = ui.settings
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.loadHistory() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Ajustes", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text("Solo lo necesario", style = MaterialTheme.typography.bodySmall, color = InkFaint)
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Tu teléfono")
        Card {
            NicknameRow(s.deviceNickname) { vm.setNickname(it) }
            SettingRow(
                "NFC y acercamiento",
                NfcAvailability.describe(context),
                onClick = { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS)) } },
                control = { Icon(Icons.Rounded.ChevronRight, null, tint = InkFaint, modifier = Modifier.size(18.dp)) },
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("Compartir al acercar")
        Card {
            SettingRow(
                "Emparejar al juntar los teléfonos",
                "El toque NFC identifica al otro teléfono y autoriza ese envío. Los datos viajan por Wi-Fi Direct.",
                control = { MoonSwitch(s.nfcBumpEnabled) { vm.setNfcBump(it) } },
            )
            SettingRow(
                "Guardar lo recibido en Descargas",
                "Los archivos quedan visibles en Descargas/MoonShare. Si lo apagás, quedan dentro de la app.",
                control = { MoonSwitch(s.saveToGallery) { vm.setSaveToGallery(it) } },
            )
            SettingRow(
                "Mantener pantalla encendida al transferir",
                "Evita que la pantalla se apague durante un archivo grande.",
                control = { MoonSwitch(s.keepScreenOn) { vm.setKeepScreenOn(it) } },
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("Sonido")
        Card {
            SettingRow(
                "Sonido al completar",
                "Un aviso breve cuando un archivo termina de pasar.",
                control = { MoonSwitch(s.waterSfxEnabled) { vm.setSfx(it) } },
            )
            if (s.waterSfxEnabled) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.VolumeUp, null, tint = InkFaint, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Volumen", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        Text("${(s.waterSfxVolume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Aqua)
                    }
                    MoonSlider(s.waterSfxVolume, { vm.setSfxVolume(it) }, 0f..1f)
                    Text(
                        "Probar sonido",
                        style = MaterialTheme.typography.labelLarge,
                        color = Aqua,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { vm.previewSound() }.padding(horizontal = 8.dp, vertical = 7.dp),
                    )
                }
            }
            SettingRow(
                "Vibración breve",
                "Confirma con un toque cuando termina un archivo.",
                control = { MoonSwitch(s.hapticsEnabled) { vm.setHaptics(it) } },
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("Permisos")
        Card {
            PermRow("Dispositivos cercanos", "Permite crear el enlace Wi-Fi Direct", MoonPermissions.hasAll(context, MoonPermissions.nearby()))
            PermRow("Fotos, videos y música", "Permite elegir qué enviar", MoonPermissions.hasAll(context, MoonPermissions.media()))
            PermRow("Notificaciones", "Muestra el progreso y mantiene viva la transferencia", MoonPermissions.hasAll(context, MoonPermissions.notifications()))
            SettingRow(
                "Batería sin restricciones",
                "En Xiaomi y otras marcas evita que el sistema corte una transferencia minimizada.",
                onClick = { runCatching { context.startActivity(MoonPermissions.appDetailsIntent(context)) } },
                control = { Icon(Icons.Rounded.ChevronRight, null, tint = InkFaint, modifier = Modifier.size(18.dp)) },
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("Actividad")
        Card {
            SettingRow(
                "Guardar actividad",
                "Registra localmente qué se envió o recibió. Nunca sale del teléfono.",
                control = { MoonSwitch(s.historyEnabled) { vm.setHistoryEnabled(it) } },
            )
            SettingRow(
                "Ver actividad",
                "${ui.history.size} registros",
                onClick = onHistory,
                control = { Icon(Icons.Rounded.ChevronRight, null, tint = InkFaint, modifier = Modifier.size(18.dp)) },
            )
            SettingRow(
                "Borrar actividad",
                "Elimina el registro local.",
                onClick = { vm.clearHistory() },
                control = { Text("Borrar", style = MaterialTheme.typography.labelMedium, color = Danger) },
            )
        }

        Spacer(Modifier.height(22.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
        ) {
            Text(
                "MoonShare ${com.moonshare.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "Android 10 o superior · Sin cuentas · Sin servidores",
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
            )
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun PermRow(title: String, subtitle: String, granted: Boolean) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        control = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (granted) Aqua else Danger))
                Spacer(Modifier.width(7.dp))
                Text(
                    if (granted) "Listo" else "Falta",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (granted) Aqua else Danger,
                )
            }
        },
    )
}

@Composable
private fun NicknameRow(current: String, onCommit: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Nombre de este teléfono", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(3.dp))
        Text("Así te verá el otro teléfono al acercarse.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        TextField(
            value = text,
            onValueChange = {
                text = it.take(24)
                onCommit(text)
            },
            singleLine = true,
            placeholder = { Text("Mi teléfono", color = InkFaint) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Aqua,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}
