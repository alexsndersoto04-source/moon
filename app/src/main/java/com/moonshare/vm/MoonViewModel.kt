package com.moonshare.vm

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moonshare.MoonApplication
import com.moonshare.core.MoonLog
import com.moonshare.data.media.MediaRepository
import com.moonshare.data.model.FileItem
import com.moonshare.data.model.HistoryEntry
import com.moonshare.data.model.MediaCategory
import com.moonshare.data.settings.MoonSettings
import com.moonshare.data.settings.SettingsStore
import com.moonshare.nfc.PairingToken
import com.moonshare.transfer.TransferEngine
import com.moonshare.transfer.TransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado del catálogo de archivos (lo que se ve en la grilla de selección). */
data class CatalogState(
    val category: MediaCategory = MediaCategory.Photos,
    val query: String = "",
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val treeUri: String? = null,
)

data class UiState(
    val settings: MoonSettings = MoonSettings(),
    val catalog: CatalogState = CatalogState(),
    val selected: Set<String> = emptySet(),
    val selectedById: Map<String, FileItem> = emptyMap(),
    val nfcSupported: Boolean = false,
    val nfcReading: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    val folderAccess: Boolean = false,
) {
    // La selección no depende de la categoría visible: elegir fotos, pasar a videos
    // y elegir uno más conserva ambos archivos reales con su Uri y tamaño.
    val selectedItems: List<FileItem>
        get() = selectedById.values.toList()
    val selectedBytes: Long get() = selectedItems.sumOf { it.size }
}

class MoonViewModel(app: Application) : AndroidViewModel(app) {

    private val moonApp = app as MoonApplication

    /**
     * Nullable a propósito, y esta línea es la que explica el "pantalla negra y se sale
     * sola": antes el motor se leía como `moonApp.engine` (lateinit). Si cualquier
     * subsistema del arranque fallaba, leer ese `lateinit` lanza
     * `UninitializedPropertyAccessException` dentro de la construcción del ViewModel,
     * eso mata `onCreate` de la Activity y Android cierra sin mostrar nada. Ahora el
     * motor puede estar muerto y la app abre, con el aviso a la vista.
     */
    val engine: TransferEngine? = moonApp.engineOrNull()
    private val settings: SettingsStore = moonApp.settings
    private val media: MediaRepository = moonApp.media

    /** false => no se puede transferir; la UI usa esto para deshabilitar acciones. */
    val ready: Boolean get() = engine != null

    /** Flujos con respaldo: la UI consume siempre, haya o no haya motor. */
    val nearbyDevices: StateFlow<List<com.moonshare.net.nearby.NearbyDevice>> =
        engine?.nearbyDevices ?: MutableStateFlow(emptyList())
    val nfcSupported: Boolean get() = engine?.nfcSupported ?: false
    val handoffToken: com.moonshare.nfc.PairingToken? get() = engine?.nfcHandoffToken
    fun notifyPaired() { engine?.notifyPaired() }

    private val _ui = MutableStateFlow(
        UiState(nfcSupported = engine?.nfcSupported ?: false)
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Pulsos de la esfera + sonido, reemitidos desde el engine. */
    val pulses: kotlinx.coroutines.flow.SharedFlow<com.moonshare.transfer.TransferEvent> =
        engine?.pulses ?: kotlinx.coroutines.flow.MutableSharedFlow()

    val engineState: StateFlow<com.moonshare.transfer.EngineState> =
        engine?.state ?: MutableStateFlow(com.moonshare.transfer.EngineState())
    val pendingOffer: StateFlow<com.moonshare.transfer.PendingOffer?> =
        engine?.pendingOffer ?: MutableStateFlow(null)

    /** Token que llegó por NFC y todavía no se consumió (la UI lo muestra un instante). */
    private val _nfcToken = MutableStateFlow<PairingToken?>(null)
    val nfcToken: StateFlow<PairingToken?> = _nfcToken.asStateFlow()

    /** Uri pendientes de agregar, traídas por ACTION_SEND desde otra app. */
    private val _incomingShare = MutableSharedFlow<List<Uri>>(replay = 1, extraBufferCapacity = 4)
    val incomingShare = _incomingShare.asSharedFlow()

    /**
     * `viewModelScope` propaga cualquier excepción al handler del proceso y eso
     * cierra la app. Todo lo que toca IO de disco/red pasa por acá.
     */
    private fun safe(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                MoonLog.e("VM", "fallo no previsto en la capa de UI", e)
            }
        }
    }

    init {
        safe {
            settings.settings.collect { s ->
                _ui.update { it.copy(settings = s) }
                settings.cache(s)
            }
        }
        viewModelScope.launch { load() }
    }

    // ------------------------------------------------------------- catálogo
    fun setCategory(cat: MediaCategory) {
        if (_ui.value.catalog.category == cat) return
        _ui.update { it.copy(catalog = it.catalog.copy(category = cat, items = emptyList(), loading = true)) }
        load()
    }

    fun setQuery(q: String) {
        _ui.update { it.copy(catalog = it.catalog.copy(query = q)) }
        load(debounce = true)
    }

    fun refresh() = load(force = true)

    private var loadSeq = 0

    private fun load(force: Boolean = false, debounce: Boolean = false) {
        val seq = ++loadSeq
        safe body@{
            if (debounce) kotlinx.coroutines.delay(220)
            val cat = _ui.value.catalog.category
            val q = _ui.value.catalog.query
            if (cat == MediaCategory.All && _ui.value.catalog.treeUri != null) {
                loadTree(seq)
                return@body
            }
            _ui.update { it.copy(catalog = it.catalog.copy(loading = true, error = null)) }
            val items = media.query(cat, q)
            if (seq != loadSeq) return@body
            _ui.update {
                it.copy(
                    catalog = it.catalog.copy(
                        items = items,
                        loading = false,
                        error = if (items.isEmpty() && q.isBlank()) {
                            when (cat) {
                                MediaCategory.Photos, MediaCategory.Videos, MediaCategory.Audio ->
                                    "Sin permisos de galería o no hay nada todavía"
                                else -> "Nada por acá"
                            }
                        } else null,
                    )
                )
            }
            if (force) MoonLog.d("VM", "${items.size} ítems en ${cat.id}")
        }
    }

    private suspend fun loadTree(seq: Int) {
        val raw = _ui.value.catalog.treeUri ?: return
        _ui.update { it.copy(catalog = it.catalog.copy(loading = true)) }
        val items = withContext(Dispatchers.IO) { media.listTree(Uri.parse(raw)) }
        if (seq != loadSeq) return
        _ui.update {
            it.copy(catalog = it.catalog.copy(items = items, loading = false, error = null))
        }
    }

    private val _folderPicker = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** La Activity colecta esto y dispara el launcher de SAF (el picker es suyo). */
    val folderPicker = _folderPicker

    fun openFolderPicker() {
        _ui.update { it.copy(catalog = it.catalog.copy(category = MediaCategory.All)) }
        _folderPicker.tryEmit(Unit)
    }

    fun onTreePicked(treeUri: Uri) {
        safe {
            media.takePersistablePermission(treeUri)
            _ui.update { it.copy(catalog = it.catalog.copy(treeUri = treeUri.toString(), category = MediaCategory.All)) }
            load(force = true)
        }
    }

    fun closeTree() {
        _ui.update {
            it.copy(catalog = it.catalog.copy(treeUri = null, category = MediaCategory.Photos))
        }
        load()
    }

    // ------------------------------------------------------------- selección
    fun toggle(item: FileItem) {
        _ui.update {
            val byId = it.selectedById.toMutableMap()
            if (byId.remove(item.id) == null) byId[item.id] = item
            it.copy(selected = byId.keys.toSet(), selectedById = byId)
        }
    }

    fun selectAll() = _ui.update { st ->
        val byId = st.selectedById + st.catalog.items.associateBy { it.id }
        st.copy(selected = byId.keys.toSet(), selectedById = byId)
    }

    fun clearSelection() = _ui.update { it.copy(selected = emptySet(), selectedById = emptyMap()) }

    fun invertSelection() = _ui.update { st ->
        val byId = st.selectedById.toMutableMap()
        st.catalog.items.forEach { item ->
            if (byId.remove(item.id) == null) byId[item.id] = item
        }
        st.copy(selected = byId.keys.toSet(), selectedById = byId)
    }

    /** Total de la selección, ya sumando los ítems agregados desde otra app. */
    fun sendSelected(viaNfc: PairingToken? = null) {
        val st = _ui.value
        val items = st.selectedItems.ifEmpty { externalItems }
        if (items.isEmpty()) {
            engine?.failWith("Elegí primero qué enviar")
            return
        }
        consumeNfc()
        // El emisor no debe levantar su propio grupo de recepción: solo necesita que
        // el servicio mantenga vivo el proceso mientras dura el envío.
        TransferService.ensureForSending(getApplication())
        engine?.send(items, viaNfc)
    }

    /** Ítems que llegaron por intent de "Compartir" y que el usuario no tocó en la grilla. */
    private var externalItems: List<FileItem> = emptyList()

    fun acceptShareIntent(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )

            Intent.ACTION_SEND_MULTIPLE ->
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

            else -> emptyList()
        }
        if (uris.isEmpty()) return
        safe {
            val items = withContext(Dispatchers.IO) { media.fromUris(uris) }
            externalItems = items
            _incomingShare.emit(uris)
            _ui.update { st ->
                val byId = st.selectedById + items.associateBy { it.id }
                st.copy(
                    selected = byId.keys.toSet(),
                    selectedById = byId,
                    catalog = st.catalog.copy(
                        category = MediaCategory.All,
                        items = (items + st.catalog.items).distinctBy { it.id },
                        loading = false,
                        error = null,
                    ),
                )
            }
        }
    }

    fun acceptViewIntent(intent: Intent?) {
        // Un NDEF_DISCOVERED que llegó cuando la app estaba cerrada: el token ya
        // está en el intent, lo procesamos igual que un golpe en primer plano.
        val uri = intent?.data ?: return
        PairingToken.parse(uri)?.let { onNfcToken(it) }
    }

    fun onNfcToken(token: PairingToken) {
        _nfcToken.value = token
        engine?.let { it.nfcHandoffToken = token }
        MoonLog.i("VM", "token NFC de ${token.deviceName}")
        if (_ui.value.selected.isNotEmpty() || externalItems.isNotEmpty()) {
            sendSelected(token)
        }
    }

    private fun consumeNfc() {
        _nfcToken.value = null
        engine?.let { it.nfcHandoffToken = null }
    }

    // ------------------------------------------------------------- NFC
    private var nfc: com.moonshare.nfc.NfcHandoff? = null

    /** La Activity se registra en onResume: el lector NFC necesita una Window activa. */
    fun attachNfc(activity: android.app.Activity) {
        if (nfc?.hosts(activity) == true) return
        nfc?.stop()
        nfc = com.moonshare.nfc.NfcHandoff(activity)
    }

    fun detachNfc() {
        nfc?.stop()
    }

    private var wantScan = false
    private var scanError: ((String) -> Unit)? = null

    /** Empieza a escuchar golpes de NFC. Devuelve false si el equipo no puede. */
    fun startNfcScan(onError: (String) -> Unit = {}): Boolean {
        wantScan = true
        scanError = onError
        val handoff = nfc ?: return false
        return handoff.start(
            onToken = { token -> onNfcToken(token) },
            onError = onError,
        ).also { _ui.update { st -> st.copy(nfcReading = it) } }
    }

    fun stopNfcScan() {
        wantScan = false
        scanError = null
        nfc?.stop()
        _ui.update { it.copy(nfcReading = false) }
    }

    /**
     * Tras un onResume el sistema tira el readerMode anterior: si la hoja de pareo
     * sigue abierta, hay que volver a registrarla.
     */
    fun resumeScanIfPending() {
        if (wantScan) startNfcScan { scanError?.invoke(it) }
    }

    fun nfcTokenFromIntent(intent: Intent?): PairingToken? = nfc?.tokenFromIntent(intent)

    // ------------------------------------------------------------- sesión
    fun startReceiving() {
        TransferService.start(getApplication())
    }

    fun stopReceiving() {
        TransferService.stop(getApplication())
    }

    fun respondToOffer(accept: Boolean) {
        engine?.respondToOffer(accept)
    }

    fun cancelTransfer() {
        engine?.cancelAll()
    }

    fun clearError() {
        engine?.clearError()
    }

    /** Inicia/renueva el descubrimiento Nearby para el respaldo sin NFC. */
    fun refreshPeers() = engine?.refreshPeers()

    fun prepareToSend() = engine?.prepareToSend()

    fun sendToNearbyEndpoint(endpointId: String) {
        val items = _ui.value.selectedItems.ifEmpty { externalItems }
        if (items.isEmpty()) {
            engine?.failWith("Elegí primero qué enviar")
            return
        }
        TransferService.ensureForSending(getApplication())
        engine?.sendToNearbyEndpoint(items, endpointId)
    }

    // ------------------------------------------------------------- ajustes
    private fun edit(block: suspend (SettingsStore) -> Unit) {
        safe { block(settings) }
    }

    fun setAutoAccept(v: Boolean) = edit { it.setAutoAccept(v) }
    fun setSaveToGallery(v: Boolean) = edit { it.setSaveToGallery(v) }
    fun setKeepOriginalName(v: Boolean) = edit { it.setKeepOriginalName(v) }
    fun setMaxParallel(v: Int) = edit { it.setMaxParallel(v) }
    fun setKeepScreenOn(v: Boolean) = edit { it.setKeepScreenOn(v) }
    fun setReceiveTimeout(v: Int) = edit { it.setReceiveTimeout(v) }
    fun setPrefer5GHz(v: Boolean) = edit { it.setPrefer5GHz(v) }
    fun setListenPort(v: Int) = edit { it.setListenPort(v) }
    fun setNickname(v: String) = edit { it.setNickname(v) }
    fun setPersistGroup(v: Boolean) = edit { it.setPersistGroup(v) }
    fun setSfx(v: Boolean) = edit { it.setSfx(v) }
    fun setSfxVolume(v: Float) = edit { it.setSfxVolume(v) }
    fun setHaptics(v: Boolean) = edit { it.setHaptics(v) }
    fun setSoundOnReceiveOnly(v: Boolean) = edit { it.setSoundOnReceiveOnly(v) }
    fun setOrb(v: Boolean) = edit { it.setOrb(v) }
    fun setOrbIntensity(v: Float) = edit { it.setOrbIntensity(v) }
    fun setReduceMotion(v: Boolean) = edit { it.setReduceMotion(v) }
    fun setAmoled(v: Boolean) = edit { it.setAmoled(v) }
    fun setNfcBump(v: Boolean) = edit { it.setNfcBump(v) }
    fun setVerboseLogs(v: Boolean) = edit { it.setVerboseLogs(v) }
    fun setSpeedLimit(v: Int) = edit { it.setSpeedLimitKbps(v) }
    fun setHistoryEnabled(v: Boolean) = edit { it.setHistoryEnabled(v) }
    fun resetSettings() = edit { it.resetAll() }

    fun previewSound() {
        moonApp.sfx.onFileReceived()
    }

    fun loadHistory() {
        safe {
            val list = settings.readHistory()
            _ui.update { it.copy(history = list) }
        }
    }

    fun clearHistory() = edit { store ->
        store.clearHistory()
        _ui.update { it.copy(history = emptyList()) }
    }

    fun setOnboarded(v: Boolean) = edit { it.setOnboarded(v) }

    /** Nombre que verán los demás, con el apodo de ajustes por delante. */
    suspend fun displayName(): String {
        val s = settings.settings.first()
        return s.deviceNickname.ifBlank {
            runCatching { android.os.Build.MODEL }.getOrDefault("Moon")
        }
    }

    fun sizeLabel(bytes: Long) = MoonLog.human(bytes)

    // ------------------------------------------------------------- diagnóstico
    /** Reporte de crash sin leer todavía (el usuario lo verá en un cartel). */
    fun pendingCrash(): String? {
        val app = getApplication<MoonApplication>()
        return if (com.moonshare.core.CrashReport.hasPending(app)) {
            com.moonshare.core.CrashReport.pendingText(app)
        } else null
    }

    fun copyCrash() {
        val app = getApplication<MoonApplication>()
        com.moonshare.core.CrashReport.copyToClipboard(app, com.moonshare.core.CrashReport.pendingText(app))
    }

    fun dismissCrash() {
        com.moonshare.core.CrashReport.acknowledge(getApplication())
    }

}

/** Factory mínima: la Activity ya tiene el [Application] a mano. */
class MoonViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        MoonViewModel(app) as T
}
/** Etiqueta legible del tamaño, para la grilla y las listas. */
fun FileItem.sizeLabel(): String = MoonLog.human(size)
fun Long.sizeLabel(): String = MoonLog.human(this)
