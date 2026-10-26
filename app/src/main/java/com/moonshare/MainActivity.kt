package com.moonshare

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.moonshare.core.CrashReport
import com.moonshare.core.MoonLog
import com.moonshare.core.Startup
import com.moonshare.transfer.EnginePhase
import com.moonshare.ui.BootFailureScreen
import com.moonshare.ui.CrashDialogHost
import com.moonshare.ui.MoonApp
import com.moonshare.ui.ShareIntentRouter
import com.moonshare.vm.MoonViewModel
import com.moonshare.vm.MoonViewModelFactory

/**
 * Única Activity de MoonShare. Es deliberadamente tonta: decide el tema, engancha
 * los intents (compartir desde otras apps, NDEF, carpeta SAF) y mantiene la pantalla
 * encendida durante una transferencia. Toda la lógica está en el motor.
 *
 * `launchMode="singleTask"` + `onNewIntent` es lo que permite que el flujo
 * "Compartir fotos → Moon" de un lote enorme entre sin recrear la UI, que en un
 * equipo modesto sería un salto de memoria considerable.
 */
class MainActivity : ComponentActivity() {

    private val vm: MoonViewModel by viewModels { MoonViewModelFactory(application) }

    /**
     * true mientras la app esta en "modo diagnostico". No se construye el ViewModel ni
     * el motor en ese modo: si el crash del arranque viniera de ahi, la pantalla de
     * ayuda moriria antes de poder mostrarse (fue exactamente lo que paso una vez).
     */
    private val bootFailed = mutableStateOf(false)

    private val treePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            vm.onTreePicked(uri)
        } else {
            MoonLog.d("UI", "carpeta cancelada")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Startup.set(Startup.Phase.ACTIVITY)
        enableEdgeToEdge()

        // ¿La sesión anterior murió antes de demostrar que estaba viva? Entonces esto
        // es lo único que se dibuja: sin tema, sin ViewModel, sin motor.
        bootFailed.value = CrashReport.hasPending(this)
        if (bootFailed.value) {
            MoonLog.e("Boot", "arranque previo inconcluso (fase ${CrashReport.lastPhase(this)})")
            setContent {
                val dump = CrashReport.pendingText(this)
                BootFailureScreen(
                    text = dump,
                    phase = CrashReport.lastPhase(this),
                    onCopy = {
                        CrashReport.copyToClipboard(this, CrashReport.pendingText(this))
                        finish()
                    },
                    onContinue = {
                        CrashReport.acknowledge(this)
                        bootFailed.value = false
                    },
                )
            }
            return
        }

        // MoonApp es el dueño del tema: necesita leer `amoledBlack` de los ajustes,
        // y eso solo vive en el ViewModel, así que la decoración empieza ahí adentro.
        setContent {
            MoonApp(vm = vm)
            ShareIntentRouter(
                vm = vm,
                intent = intent,
                onHandled = { MoonLog.i("UI", "intent de compartir procesado") },
            )
            KeepScreenOn(vm)
            FolderPickerHost(vm, treePicker)
            // Si hay un crash sin ver, se muestra acá antes de cualquier otra cosa.
            CrashDialogHost(vm)
        }
        // La primera composición ya pasó: a partir de acá un fallo es de la UI/VM.
        window.decorView.post {
            Startup.set(Startup.Phase.FIRST_FRAME)
            // Y si el frame se dibujó y sobrevive un segundo, la app ya no está en
            // "arranque frágil": a partir de acá una muerte se lee como tal, no como
            // un arranque roto.
            window.decorView.postDelayed({
                Startup.set(Startup.Phase.READY)
                CrashReport.markHealthy(this)
            }, 1100)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (bootFailed.value) return
        vm.acceptViewIntent(intent)
        vm.acceptShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (bootFailed.value) return
        vm.attachNfc(this)
        vm.resumeScanIfPending()
    }

    override fun onPause() {
        super.onPause()
        if (bootFailed.value) return
        // Importante: NFC en readerMode consume batería y bloquea a otras apps
        // (pagos, otro lector). Solo se deja activo mientras la hoja de pareo está visible.
        vm.detachNfc()
    }
}

/**
 * Pantalla encendida durante una transferencia. Se hace con la flag de la ventana
 * en vez de un WakeLock: mismo efecto, y el sistema lo libera solo cuando la
 * Activity se destruye (un WakeLock mal liberado drena una batería de 5000 mAh).
 */
@Composable
private fun KeepScreenOn(vm: MoonViewModel) {
    val engine by vm.engineState.collectAsState()
    val ui by vm.ui.collectAsState()
    val allowed = ui.settings.keepScreenOn
    val active = engine.phase == EnginePhase.Transferring || engine.phase == EnginePhase.Handshaking
    val window = (LocalContext.current as? ComponentActivity)?.window
    DisposableEffect(active && allowed) {
        if (active && allowed) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
private fun FolderPickerHost(vm: MoonViewModel, launcher: androidx.activity.result.ActivityResultLauncher<android.net.Uri?>) {
    LaunchedEffect(Unit) {
        vm.folderPicker.collect { launcher.launch(null) }
    }
}
