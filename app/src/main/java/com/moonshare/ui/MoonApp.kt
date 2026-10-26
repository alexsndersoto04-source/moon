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
import com.moonshare.ui.glass.CrystalOrb
import com.moonshare.ui.glass.moonGlass
import com.moonshare.ui.history.HistoryScreen
import com.moonshare.ui.home.HomeScreen
import com.moonshare.ui.pairing.PairingSheet
import com.moonshare.ui.pick.FilePickerScreen
import com.moonshare.ui.settings.SettingsScreen
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.InkFaint
import com.moonshare.ui.theme.MoonTheme
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

    MoonTheme(dark = true, amoled = ui.settings.amoledBlack) {
        val backdrop = remember(ui.settings.amoledBlack) {
            Brush.linearGradient(
                listOf(Color(0xFF0A1620), Color(0xFF06090D), Color(0xFF04070A))
            )
        }
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
                    )
                }
                composable(Routes.PICK) {
                    FilePickerScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        onPairing = { showPairing = true },
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
            PairingSheet(vm = vm, onDone = onDone)
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
                .background(Color(0xF204070A))
                .padding(horizontal = 26.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // La esfera en el arranque cuenta la promesa; en la pantalla principal
                // ya no: ahi estorba (era el detalle que mas "diseño futurista" sumaba).
                Box(Modifier.size(104.dp)) {
                    CrystalOrb(progress = 0f, intensity = 0.75f)
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
                        .moonGlass(elevation = 16.dp)
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
