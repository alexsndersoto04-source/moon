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
import com.moonshare.core.MoonLog
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
    val nfcSupported: Boolean = false,
    val nfcReading: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    val folderAccess: Boolean = false,
) {
    val selectedItems: List<FileItem>
        get() = catalog.items.filter { it.id in selected }
    val selectedBytes: Long get() = selectedItems.sumOf { it.size }
}

class MoonViewModel(app: Application) : AndroidViewModel(app) {

    private val moonApp = app as MoonApplication
    val engine: TransferEngine = moonApp.engine
    private val settings: SettingsStore = moonApp.settings
    private val media: MediaRepository = moonApp.media

    private val _ui = MutableStateFlow(
        UiState(nfcSupported = moonApp.engine.nfcSupported)
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Pulsos de la esfera + sonido, reemitidos desde el engine. */
    val pulses = moonApp.engine.pulses

    val engineState = moonApp.engine.state
    val pendingOffer = moonApp.engine.pendingOffer

    /** Token que llegó por NFC y todavía no se consumió (la UI lo muestra un instante). */
    private val _nfcToken = MutableStateFlow<PairingToken?>(null)
    val nfcToken: StateFlow<PairingToken?> = _nfcToken.asStateFlow()

    /** Uri pendientes de agregar, traídas por ACTION_SEND desde otra app. */
    private val _incomingShare = MutableSharedFlow<List<Uri>>(replay = 1, extraBufferCapacity = 4)
    val incomingShare = _incomingShare.asSharedFlow()

    init {
        viewModelScope.launch {
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
        viewModelScope.launch {
            if (debounce) kotlinx.coroutines.delay(220)
            val cat = _ui.value.catalog.category
            val q = _ui.value.catalog.query
            if (cat == MediaCategory.All && _ui.value.catalog.treeUri != null) {
                loadTree(seq)
                return@launch
            }
            _ui.update { it.copy(catalog = it.catalog.copy(loading = true, error = null)) }
            val items = media.query(cat, q)
            if (seq != loadSeq) return@launch
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
        viewModelScope.launch {
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
            val sel = it.selected.toMutableSet()
            if (!sel.remove(item.id)) sel.add(item.id)
            it.copy(selected = sel)
        }
    }

    fun selectAll() = _ui.update { st ->
        st.copy(selected = st.catalog.items.map { it.id }.toSet())
    }

    fun clearSelection() = _ui.update { it.copy(selected = emptySet()) }

    fun invertSelection() = _ui.update { st ->
        val all = st.catalog.items.map { it.id }.toSet()
        st.copy(selected = all - st.selected)
    }

    /** Total de la selección, ya sumando los ítems agregados desde otra app. */
    fun sendSelected(viaNfc: PairingToken? = null) {
        val st = _ui.value
        val items = st.selectedItems.ifEmpty { externalItems }
        if (items.isEmpty()) {
            engine.failWith("Elegí primero qué enviar")
            return
        }
        consumeNfc()
        TransferService.start(getApplication())
        engine.send(items, viaNfc)
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
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { media.fromUris(uris) }
            externalItems = items
            _incomingShare.emit(uris)
            _ui.update { st ->
                st.copy(
                    selected = st.selected + items.map { it.id },
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
        engine.nfcHandoffToken = token
        MoonLog.i("VM", "token NFC de ${token.deviceName}")
        if (_ui.value.selected.isNotEmpty() || externalItems.isNotEmpty()) {
            sendSelected(token)
        }
    }

    private fun consumeNfc() {
        _nfcToken.value = null
        engine.nfcHandoffToken = null
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
        engine.respondToOffer(accept)
    }

    fun cancelTransfer() {
        engine.cancelAll()
    }

    fun clearError() {
        engine.clearError()
    }

    /** Fuerza un `discoverPeers()` para refrescar la lista (botón "Buscar"). */
    fun refreshPeers() = engine.refreshPeers()

    // ------------------------------------------------------------- ajustes
    private inline fun edit(crossinline block: suspend (SettingsStore) -> Unit) {
        viewModelScope.launch { block(settings) }
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
        viewModelScope.launch {
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
