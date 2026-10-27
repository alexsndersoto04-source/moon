package com.moonshare.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.res.stringResource
import com.moonshare.R
import com.moonshare.core.MoonLog
import com.moonshare.core.perm.MoonPermissions
import com.moonshare.ui.common.PrimaryButton
import com.moonshare.ui.history.HistoryScreen
import com.moonshare.ui.home.HomeScreen
import com.moonshare.ui.pairing.PairingSheet
import com.moonshare.ui.pick.FilePickerScreen
import com.moonshare.ui.settings.SettingsScreen
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.InkFaint
import com.moonshare.ui.theme.MoonTheme
import com.moonshare.transfer.EngineMode
import com.moonshare.transfer.EnginePhase
import androidx.navigation.compose.currentBackStackEntryAsState
import com.moonshare.ui.transfer.TransferScreen
import com.moonshare.vm.MoonViewModel

object Routes {
    const val HOME = "home"
    const val PICK = "pick"
    const val TRANSFER = "transfer"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
}

@Composable
fun MoonApp(vm: MoonViewModel) {
    val ui by vm.ui.collectAsState()
    var showPairing by remember { mutableStateOf(false) }

    MoonTheme(dark = false, amoled = false) {
        val backdrop = remember { Brush.linearGradient(listOf(Color.White, Color.White)) }
        Box(
            Modifier
                .fillMaxSize()
                .background(backdrop)
                .statusBarsPadding()
        ) {
            val nav = rememberNavController()

            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        vm = vm,
                        onSend = { nav.navigate(Routes.PICK) },
                        onOpenTransfer = { nav.navigate(Routes.TRANSFER) },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                        onHistory = { nav.navigate(Routes.HISTORY) },
                    )
                }
                composable(Routes.PICK) {
                    FilePickerScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        onPairing = { showPairing = true },
                        onSendStarted = { nav.navigate(Routes.TRANSFER) { popUpTo(Routes.HOME) } },
                    )
                }
                composable(Routes.TRANSFER) {
                    TransferScreen(vm = vm, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        onHistory = { nav.navigate(Routes.HISTORY) },
                    )
                }
                composable(Routes.HISTORY) {
                    HistoryScreen(vm = vm, onBack = { nav.popBackStack() })
                }
            }

            if (showPairing) {
                PairingOverlay(
                    vm = vm,
                    onDismiss = { showPairing = false },
                    onDone = {
                        showPairing = false
                        nav.navigate(Routes.TRANSFER) { popUpTo(Routes.HOME) }
                    },
                )
            }

            PermissionGate(vm)
            TransferPresenceOverlay(
                vm = vm,
                hidden = showPairing,
                onOpen = { nav.navigate(Routes.TRANSFER) },
            )
        }
    }
}

/**
 * Efecto translúcido de presencia: aparece sobre cualquier pantalla cuando un
 * envío/recepción está vivo. Es información, no un adorno futurista: quién,
 * qué archivo, cuánto falta y cancelar si hace falta.
 */
@Composable
private fun TransferPresenceOverlay(vm: MoonViewModel, hidden: Boolean, onOpen: () -> Unit) {
    val engine by vm.engineState.collectAsState()
    val show = !hidden && (
        engine.mode != EngineMode.Idle ||
            engine.phase == EnginePhase.Joining ||
            engine.phase == EnginePhase.Handshaking ||
            engine.phase == EnginePhase.Done ||
            engine.phase == EnginePhase.Failed
        )
    AnimatedVisibility(
        visible = show,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(140)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.White.copy(alpha = 0.94f))
                    .clickable {
                        if (engine.phase != EnginePhase.Done && engine.phase != EnginePhase.Failed) onOpen()
                    }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).clip(CircleShape)
                            .background(if (engine.phase == EnginePhase.Failed) Color(0xFFFEE4E0) else Color(0xFFEAF2FF)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.NearMe, null,
                            tint = if (engine.phase == EnginePhase.Failed) Color(0xFFD92D20) else Aqua,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                engine.phase == EnginePhase.Done && engine.mode == EngineMode.Receiving -> "Recepción completada"
                                engine.phase == EnginePhase.Done -> "Envío completado"
                                engine.phase == EnginePhase.Failed -> "La transferencia se interrumpió"
                                engine.mode == EngineMode.Receiving -> "Recibiendo de ${engine.peerName}"
                                engine.mode == EngineMode.Sending -> "Enviando a ${engine.peerName}"
                                engine.phase == EnginePhase.Joining -> "Conectando con el otro teléfono"
                                else -> engine.statusText.ifBlank { "Preparando la transferencia" }
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            engine.error ?: engine.statusText.ifBlank {
                                engine.tasks.firstOrNull { it.state.name == "Streaming" }?.name
                                    ?: "${engine.tasks.count { it.state.name == "Done" }} de ${engine.tasks.size} archivos"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = InkFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (engine.mode != EngineMode.Idle && engine.phase != EnginePhase.Done) {
                        Text(
                            "Cancelar",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFFD92D20),
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .clickable { vm.cancelTransfer() }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                if (engine.tasks.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier.fillMaxWidth().height(5.dp).clip(CircleShape)
                            .background(Color(0xFFE6EEF7)),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(engine.progress.coerceIn(0f, 1f))
                                .height(5.dp).clip(CircleShape).background(Aqua),
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${(engine.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Aqua)
                        if (engine.speedBps > 0) {
                            Text(MoonLog.rate(engine.speedBps.toDouble()), style = MaterialTheme.typography.labelMedium, color = InkFaint)
                        }
                    }
                }
            }
        }
    }
}

/** La hoja de pareo, montada sobre un scrim en vez de un ModalBottomSheet del sistema. */
@Composable
private fun PairingOverlay(vm: MoonViewModel, onDismiss: () -> Unit, onDone: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .clickable(enabled = true) { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .clickable(enabled = false) { }
        ) {
            // Sin AnimatedVisibility acá: la hoja vive un `if` en el composition scope
            // del Box, y animarla pedía un scope de Column/Row. El feedback de entrada
            // lo da la propia hoja (el glifo de NFC late), así que no se pierde nada.
            PairingSheet(vm = vm, onDone = onDone, onDismiss = onDismiss)
        }
    }
}

/**
 * Portón de permisos del primer arranque. Explica por qué pide cada cosa antes de
 * abrir el diálogo del sistema — y deja pasar igual si el usuario niega el de
 * notificaciones, porque la app funciona sin él (solo se ve menos progreso).
 */
@Composable
private fun PermissionGate(vm: MoonViewModel) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }

    val needed = remember {
        MoonPermissions.missing(context, MoonPermissions.requiredForFirstRun())
    }
    var pending by remember { mutableStateOf(needed) }

    LaunchedEffect(Unit) { show = needed.isNotEmpty() }

    val requester = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        pending = pending.filterNot { it in result.keys }
        if (denied.isNotEmpty() && denied.containsAll(MoonPermissions.nearby())) {
            MoonLog.w("UI", "sin permisos de cercanos: Wi-Fi Direct no va a arrancar")
        }
        vm.setOnboarded(true)
        show = false
    }

    AnimatedVisibility(
        visible = show,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(200)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(horizontal = 26.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Marca simple y reconocible; la explicación de permisos va debajo.
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Aqua),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.NearMe, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    "Pasá archivos de un teléfono al otro con un golpe",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Wi-Fi Direct mueve los datos, NFC los presenta. No pasa nada por internet ni por ningún servidor.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.9f),
                )
                Spacer(Modifier.height(28.dp))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(vertical = 8.dp),
                ) {
                    // Los textos viven en strings.xml (unicos, traducibles); Lint venia
                    // marcando esos recursos como muertos porque la pantalla los tenía
                    // escritos a mano encima.
                    GrantRow(Icons.Rounded.Shield, "Wi-Fi Direct", stringResource(R.string.perm_nearby_rationale))
                    GrantRow(Icons.Rounded.NearMe, "Tus archivos", stringResource(R.string.perm_media_rationale))
                    if (Build.VERSION.SDK_INT >= 33) {
                        GrantRow(Icons.Rounded.NearMe, "Notificaciones", stringResource(R.string.perm_notif_rationale))
                    }
                }

                Spacer(Modifier.height(24.dp))
                PrimaryButton(
                    text = "Continuar",
                    onClick = { requester.launch(pending.toTypedArray()) },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Podés cambiar esto después en Ajustes",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint,
                )
            }
        }
    }
}

@Composable
private fun GrantRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(Aqua.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Aqua, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = InkFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Enrutador del intent de "Compartir → MoonShare" que llega con la app cerrada. */
@Composable
fun ShareIntentRouter(vm: MoonViewModel, intent: Intent?, onHandled: () -> Unit) {
    LaunchedEffect(intent) {
        if (intent == null) return@LaunchedEffect
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                vm.acceptShareIntent(intent)
                onHandled()
            }
            // NDEF_DISCOVERED con la app cerrada: el intent trae el token de pareo
            android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                vm.acceptViewIntent(intent)
                onHandled()
            }
        }
    }
}
