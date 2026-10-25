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
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.moonshare.core.MoonLog
import com.moonshare.transfer.EnginePhase
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
        enableEdgeToEdge()

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
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.acceptViewIntent(intent)
        vm.acceptShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.attachNfc(this)
        vm.resumeScanIfPending()
    }

    override fun onPause() {
        super.onPause()
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
