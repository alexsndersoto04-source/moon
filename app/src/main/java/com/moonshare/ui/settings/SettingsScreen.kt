package com.moonshare.ui.settings

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moonshare.core.perm.MoonPermissions
import com.moonshare.ui.common.MoonSlider
import com.moonshare.ui.common.MoonSwitch
import com.moonshare.ui.common.SectionLabel
import com.moonshare.ui.common.SettingRow
import com.moonshare.ui.glass.moonGlass
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.Danger
import com.moonshare.ui.theme.InkFaint
import com.moonshare.ui.theme.PillShape
import com.moonshare.vm.MoonViewModel
import androidx.compose.ui.platform.LocalContext

/**
 * Ajustes. Organizados de adentro hacia afuera: primero lo que ve el usuario todos
 * los días (apariencia y sonido), al fondo lo que solo toca un vez (puerto, logs).
 * Cada sección es una tarjeta de vidrio; no hay una sola línea divisoria.
 */
@Composable
fun SettingsScreen(vm: MoonViewModel, onBack: () -> Unit, onHistory: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val s = ui.settings
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.loadHistory() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
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
            Column {
                Text("Ajustes", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "control total de la app",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ============================================ APARIENCIA
        SectionLabel("Apariencia")
        Card {
            SettingRow("Negro puro (AMOLED)", "Apaga los píxeles del fondo. Se nota en pantallas OLED.",
                control = { MoonSwitch(s.amoledBlack) { vm.setAmoled(it) } })
            SettingRow("Reducir movimiento", "Frena la respiración de la esfera y las olas del agua.",
                control = { MoonSwitch(s.reduceMotion) { vm.setReduceMotion(it) } })
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("La esfera")
        Card {
            SettingRow("Mostrar esfera", "Si la apagás, la transferencia sigue funcionando igual.",
                control = { MoonSwitch(s.orbEnabled) { vm.setOrb(it) } })
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Intensidad del vidrio",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(s.orbIntensity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Aqua)
                }
                MoonSlider(
                    value = s.orbIntensity,
                    onValueChange = { vm.setOrbIntensity(it) },
                    valueRange = 0.2f..1f,
                    enabled = s.orbEnabled,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Sonido")
        Card {
            SettingRow("Gota de agua por archivo", "Suena cada vez que pasa un archivo, en los dos sentidos.",
                control = { MoonSwitch(s.waterSfxEnabled) { vm.setSfx(it) } })
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Volumen", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.VolumeUp, null, tint = InkFaint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${(s.waterSfxVolume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Aqua)
                }
                MoonSlider(
                    value = s.waterSfxVolume,
                    onValueChange = { vm.setSfxVolume(it) },
                    valueRange = 0f..1f,
                    enabled = s.waterSfxEnabled,
                )
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Probar") { vm.previewSound() }
                }
            }
            SettingRow("Sonar solo al recibir", "El envío queda mudo, útil cuando pasás 200 fotos.",
                control = { MoonSwitch(s.soundOnReceiveOnly) { vm.setSoundOnReceiveOnly(it) } })
            SettingRow("Vibración", "Pulso corto de confirmación en cada archivo.",
                control = { MoonSwitch(s.hapticsEnabled) { vm.setHaptics(it) } })
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Transferencia")
        Card {
            SettingRow("Aceptar sin preguntar", "Quien te envíe no espera tu ok. Solo para gente de confianza.",
                control = { MoonSwitch(s.autoAccept) { vm.setAutoAccept(it) } })
            SettingRow("Guardar en la galería", "Si está off, lo recibido queda en la carpeta privada de la app.",
                control = { MoonSwitch(s.saveToGallery) { vm.setSaveToGallery(it) } })
            SettingRow("Mantener el nombre original", "Si está off, se renombra con la fecha al principio.",
                control = { MoonSwitch(s.keepOriginalName) { vm.setKeepOriginalName(it) } })
            SettingRow("Mantener la pantalla encendida", "Evita que el sistema suspenda la radio a mitad de un archivo grande.",
                control = { MoonSwitch(s.keepScreenOn) { vm.setKeepScreenOn(it) } })
            StepperRow(
                "Archivos en paralelo",
                "Sube el uso de CPU; en equipos modestos 1 es lo más rápido.",
                value = s.maxParallelStreams, range = 1..4,
                onChange = { vm.setMaxParallel(it) },
            )
            StepperRow(
                "Espera máxima de una entrada",
                "Pasado este tiempo, lo que no hayas aprobado se rechaza solo.",
                value = s.receiveTimeoutSec, range = 15..600, step = 15, suffix = " s",
                onChange = { vm.setReceiveTimeout(it) },
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Radio y red")
        Card {
            SettingRow("Preferir 5 GHz", "Más rápido y menos congestionado. Si el otro teléfono no lo soporta, baja solo.",
                control = { MoonSwitch(s.prefer5GHz) { vm.setPrefer5GHz(it) } })
            SettingRow("Dejar el grupo armado", "Aglutina los envíos seguidos: el segundo pareo es instantáneo.",
                control = { MoonSwitch(s.persistGroupAfterSend) { vm.setPersistGroup(it) } })
            NicknameRow(s.deviceNickname) { vm.setNickname(it) }
            PortRow(s.listenPort) { vm.setListenPort(it) }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("NFC")
        Card {
            SettingRow("Parear al tocar", "El golpe de teléfonos manda la dirección Wi-Fi Direct; no reemplaza los datos.",
                control = { MoonSwitch(s.nfcBumpEnabled) { vm.setNfcBump(it) } })
            SettingRow(
                "Estado del hardware",
                com.moonshare.nfc.NfcAvailability.describe(context),
                onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS)) },
                control = { Icon(Icons.Rounded.ChevronRight, null, tint = InkFaint, modifier = Modifier.size(18.dp)) },
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Permisos y almacenamiento")
        Card {
            PermRow(
                "Dispositivos cercanos",
                "Necesario para crear la red Wi-Fi Direct",
                MoonPermissions.hasAll(context, MoonPermissions.nearby()),
            )
            PermRow(
                "Fotos, videos y música",
                "Para elegir qué enviar",
                MoonPermissions.hasAll(context, MoonPermissions.media()),
            )
            PermRow(
                "Notificaciones",
                "Para mostrar el progreso y no cortar la transferencia",
                MoonPermissions.hasAll(context, MoonPermissions.notifications()),
            )
            SettingRow(
                "Acceso a todos los archivos",
                "Opcional. Habilita la pestaña Carpetas sin usar el selector del sistema.",
                onClick = {
                    runCatching {
                        context.startActivity(
                            if (android.os.Build.VERSION.SDK_INT >= 30) MoonPermissions.allFilesSettingsIntent(context)
                            else MoonPermissions.storageSettingsIntent(context)
                        )
                    }
                },
                control = {
                    Text(
                        if (MoonPermissions.hasAllFilesAccess()) "otorgado" else "no",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (MoonPermissions.hasAllFilesAccess()) Aqua else InkFaint,
                    )
                },
            )
            SettingRow(
                "Optimización de batería",
                "En MIUI es la causa nº1 de cortes. Poné “Sin restricciones”.",
                onClick = {
                    runCatching { context.startActivity(MoonPermissions.appDetailsIntent(context)) }
                },
                control = { Icon(Icons.Rounded.ChevronRight, null, tint = InkFaint, modifier = Modifier.size(18.dp)) },
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Avanzado")
        Card {
            SettingRow("Historial de pasadas", "Guarda nombre, tamaño y con quién. Local, nunca se sube a ningún lado.",
                control = { MoonSwitch(s.historyEnabled) { vm.setHistoryEnabled(it) } })
            SettingRow(
                "Ver historial",
                "${ui.history.size} entradas guardadas",
                onClick = onHistory,
                control = { Icon(Icons.Rounded.ChevronRight, null, tint = InkFaint, modifier = Modifier.size(18.dp)) },
            )
            StepperRow(
                "Límite de velocidad",
                "0 = sin límite. KB/s. Útil si el otro teléfono se calienta.",
                value = s.speedLimitKbps, range = 0..4_000_000, step = 500_000,
                onChange = { vm.setSpeedLimit(it) },
            )
            SettingRow("Logs detallados", "Se ven con `adb logcat -s MoonShare:*`. Solo para diagnosticar.",
                control = { MoonSwitch(s.verboseLogs) { vm.setVerboseLogs(it) } })
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Datos")
        Card {
            SettingRow(
                "Borrar historial",
                "Saca las entradas de transferencias pasadas.",
                onClick = { vm.clearHistory() },
                control = { Text("borrar", style = MaterialTheme.typography.labelMedium, color = Danger) },
            )
            SettingRow(
                "Restaurar todo",
                "Deja cada ajuste como venía de fábrica.",
                onClick = { vm.resetSettings() },
                control = { Text("resetear", style = MaterialTheme.typography.labelMedium, color = Danger) },
            )
        }

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().moonGlass().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Info, null, tint = InkFaint, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "MoonShare ${com.moonshare.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Wi-Fi Direct + NFC · min. Android 10 · sin servidores intermedios",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().moonGlass(elevation = 14.dp)) {
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(PillShape)
            .background(Aqua.copy(alpha = 0.14f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Aqua)
    }
}

@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    suffix: String = "",
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Stepper(value, range, step, suffix, onChange)
    }
}

@Composable
private fun Stepper(value: Int, range: IntRange, step: Int, suffix: String, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(PillShape)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        StepBtn("−", enabled = value > range.first) { onChange((value - step).coerceAtLeast(range.first)) }
        Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
            Text(
                "$value$suffix",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
        StepBtn("+", enabled = value < range.last) { onChange((value + step).coerceAtMost(range.last)) }
    }
}

@Composable
private fun StepBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .background(if (enabled) Aqua.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.03f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (enabled) Aqua else InkFaint)
    }
}

@Composable
private fun NicknameRow(current: String, onCommit: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Nombre para mi teléfono", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text("Así te ven los demás al parear", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        TextField(
            value = text,
            onValueChange = {
                text = it.take(24)
                onCommit(text)
            },
            singleLine = true,
            placeholder = { Text("Redmi 9C", color = InkFaint) },
            modifier = Modifier.width(132.dp),
            shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(capitalize = true),
            colors = moonFieldColors(),
        )
    }
}

@Composable
private fun PortRow(port: Int, onCommit: (Int) -> Unit) {
    var text by remember(port) { mutableStateOf(port.toString()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Puerto de escucha", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text("Cambialo solo si dos apps pelean por el mismo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        TextField(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() }.take(5)
                text.toIntOrNull()?.let { if (it in 1024..65535) onCommit(it) }
            },
            singleLine = true,
            modifier = Modifier.width(110.dp),
            shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = moonFieldColors(),
        )
    }
}

@Composable
private fun PermRow(title: String, subtitle: String, granted: Boolean) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        control = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape)
                        .background(if (granted) Aqua else Danger.copy(alpha = 0.85f))
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    if (granted) "listo" else "falta",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (granted) Aqua else Danger,
                )
            }
        },
    )
}

@Composable
private fun moonFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = 0.07f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    cursorColor = Aqua,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
)
