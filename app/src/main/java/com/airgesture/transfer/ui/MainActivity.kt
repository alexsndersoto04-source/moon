package com.airgesture.transfer.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.PointF
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airgesture.transfer.R
import com.airgesture.transfer.core.AirGestureApp
import com.airgesture.transfer.core.GestureState
import com.airgesture.transfer.databinding.ActivityMainBinding
import com.airgesture.transfer.media.MediaEntry
import com.airgesture.transfer.media.MediaKind
import com.airgesture.transfer.transfer.NearbyState
import com.airgesture.transfer.util.Haptics
import com.airgesture.transfer.util.Radios
import com.airgesture.transfer.util.Permissions
import com.airgesture.transfer.vision.OverlayFrame
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Single-screen experience: camera, live gesture feedback, the local file shelf
 * and the transfer status. No settings, no pairing dialogs, no second activity.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: FileAdapter
    private lateinit var haptics: Haptics
    private val prefs get() = (application as AirGestureApp).prefs

    private val cameraExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "airgesture-camera").apply { priority = Thread.NORM_PRIORITY }
    }

    private val uiHandler = Handler(Looper.getMainLooper())

    private var providerRef: ProcessCameraProvider? = null
    private var cameraStarted = false
    private var cameraRequestPending = false
    private var permissionRequested = false
    private var diagnosticsVisible = false
    private var boundFrontCamera: Boolean? = null

    private var lastItems: List<MediaEntry>? = null
    private var lastSelectedUri: String? = null
    private var transientMessage: String? = null
    private var transientUntilMs = 0L

    private enum class Blocker { NONE, PERMISSION, BLUETOOTH, LOCATION }
    private var blocker: Blocker = Blocker.NONE
    private var lastPushZone = 0
    private var lastHandSeenAtMs = 0L
    private var handChipState: Boolean? = null
    private var currentState = MainUiState()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        applyPermissions()
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == SettingsActivity.RESULT_CALIBRATE) viewModel.startCalibration()
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshBlocker()
    }

    private val clearTransient = Runnable {
        transientMessage = null
        render(currentState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        haptics = Haptics(this)

        adapter = FileAdapter(
            onSelect = viewModel::selectItem,
            onSendNow = { entry -> viewModel.sendItem(entry) }
        )
        binding.filesList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            adapter = this@MainActivity.adapter
            itemAnimator = null
            setHasFixedSize(true)
        }

        bindTabs()

        binding.permButton.setOnClickListener { requestPermissions() }
        binding.permSettings.setOnClickListener { openAppSettings() }

        binding.bannerAction.setOnClickListener { fixBlocker() }
        binding.calibrationCancel.setOnClickListener { viewModel.cancelCalibration() }
        binding.calibrateLink.setOnClickListener {
            haptics.tick()
            viewModel.startCalibration()
        }
        binding.calibrationDone.setOnClickListener {
            haptics.confirm()
            viewModel.finishCalibration()
        }

        binding.sendButton.setOnClickListener {
            haptics.tick()
            viewModel.sendSelectedNow()
        }

        binding.transferCancel.setOnClickListener {
            viewModel.cancelTransfer()
        }

        binding.settingsButton.setOnClickListener {
            haptics.tick()
            settingsLauncher.launch(SettingsActivity.intent(this))
        }

        // Long-press the title: diagnostics HUD (fps, inference time, source format,
        // closure and push values). Essential to verify the pipeline on real hardware.
        binding.brandTitle.setOnLongClickListener {
            diagnosticsVisible = !diagnosticsVisible
            binding.diagnostics.isVisible = diagnosticsVisible
            haptics.tick()
            true
        }

        collectFlows()
        applyPermissions()
        showCrashReportIfAny()
    }

    /**
     * A side-loaded build has no crash reporting, so the last uncaught exception
     * is shown here with a copy button.
     */
    private fun showCrashReportIfAny() {
        val report = com.airgesture.transfer.core.CrashLogger.readAndClear(this) ?: return
        val scroll = android.widget.ScrollView(this).apply {
            val text = android.widget.TextView(this@MainActivity).apply {
                text = report.take(8000)
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(32, 24, 32, 24)
            }
            addView(text)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_title)
            .setMessage(R.string.crash_body)
            .setView(scroll)
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("crash", report)
                )
                showTransient(getString(R.string.crash_copied))
            }
            .setNegativeButton(R.string.crash_close, null)
            .show()
    }

    /**
     * Settings are re-read on every resume: toggles apply immediately and the
     * camera is re-bound only when the front/back choice actually changed.
     */
    override fun onResume() {
        super.onResume()
        applyPreferencesToUi()
    }

    private fun applyPreferencesToUi() {
        binding.handOverlay.setSkeletonVisible(prefs.skeletonVisible)
        diagnosticsVisible = prefs.diagnosticsVisible
        binding.diagnostics.isVisible = diagnosticsVisible
        viewModel.applyPreferences()

        refreshBlocker()

        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (boundFrontCamera != null && boundFrontCamera != prefs.useFrontCamera) {
            // Camera source changed: drop the binding and rebuild it now.
            runCatching { providerRef?.unbindAll() }
            cameraStarted = false
            boundFrontCamera = null
            viewModel.onCameraSourceChanged()
            startCameraIfNeeded()
        }
    }

    override fun onStart() {
        super.onStart()
        applyPermissions()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopSession()
        runCatching { providerRef?.unbindAll() }
        cameraStarted = false
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(clearTransient)
        cameraExecutor.shutdown()
    }

    // ------------------------------------------------------------- bindings

    private fun bindTabs() {
        listOf(
            binding.tabImages to MediaKind.IMAGES,
            binding.tabVideos to MediaKind.VIDEOS,
            binding.tabPdfs to MediaKind.PDFS,
            binding.tabAudio to MediaKind.AUDIO
        ).forEach { (view, kind) ->
            view.setOnClickListener {
                haptics.tick()
                viewModel.selectKind(kind)
            }
        }
    }

    private fun collectFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.frames.collect(::onFrame) }
                launch { viewModel.stats.collect(::onStats) }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is UiEvent.Sent -> showTransient(getString(R.string.sent_named, event.name))
                            is UiEvent.Received -> showTransient(getString(R.string.received_named, event.name))
                            is UiEvent.Message -> showTransient(event.text)
                            is UiEvent.Handoff -> playHandoff(event.name)
                            is UiEvent.Arrival -> playArrival(event.name)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------- live feedback

    private fun onFrame(frame: OverlayFrame) {
        binding.handOverlay.submit(frame)
        binding.pushMeter.setProgressCompat((frame.thrust * 100f).toInt().coerceIn(0, 100), false)

        // Progressive haptic: the meter "charges" under the finger so the gesture
        // feels intentional instead of random.
        if (lastPushZone != frame.pushZone) {
            if (frame.pushZone > lastPushZone) haptics.charge(frame.pushZone)
            lastPushZone = frame.pushZone
        }

        val handVisible = frame.landmarks != null
        if (handVisible) lastHandSeenAtMs = SystemClock.uptimeMillis()

        if (handChipState != handVisible) {
            handChipState = handVisible
            binding.handChip.setText(
                if (handVisible) R.string.hand_chip_detected else R.string.hand_chip_none
            )
            if (handVisible) haptics.tick()
            updateHint()
        }
    }

    private var lastStats = TrackingStats()

    private fun onStats(stats: TrackingStats) {
        lastStats = stats
        // The hint depends on how long ago the hand was last seen, so it is
        // refreshed from the stats tick even when the HUD is hidden.
        updateHint()
        refreshBlocker()
        if (!diagnosticsVisible) return
        binding.diagnostics.text = buildString {
            append(String.format(Locale.US, "fps %d · %d ms\n", stats.fps, stats.inferenceMs))
            append("mano ${stats.handFrames} · ${stats.conversionMode}\n")
            append(String.format(Locale.US, "cierre %.2f · empuje %.2f\n", stats.closure, stats.push))
            append(String.format(Locale.US, "acerca %.2f\n", stats.approach))
            append("color ${stats.conversionMode} · ${stats.colorProbe}\n")
            append("cuadros ${stats.frames} · fallos ${stats.conversionFailures}\n")
            append(if (stats.modelReady) "modelo ok · dedos ${stats.landmarks}" else "modelo: ${stats.modelError}")
        }
    }

    // -------------------------------------------------------------- rendering

    private fun render(state: MainUiState) {
        currentState = state

        binding.tabImages.isSelected = state.kind == MediaKind.IMAGES
        binding.tabVideos.isSelected = state.kind == MediaKind.VIDEOS
        binding.tabPdfs.isSelected = state.kind == MediaKind.PDFS
        binding.tabAudio.isSelected = state.kind == MediaKind.AUDIO

        if (lastItems !== state.items) {
            lastItems = state.items
            adapter.submitList(state.items)
        }

        binding.emptyText.isVisible = state.items.isEmpty() && !state.loading
        binding.emptyText.setText(state.kind.emptyRes)
        adapter.selectedKey = state.selectedUri

        if (state.selectedUri != null && state.selectedUri != lastSelectedUri) {
            val index = state.items.indexOfFirst { it.key == state.selectedUri }
            if (index >= 0) binding.filesList.smoothScrollToPosition(index)
            lastSelectedUri = state.selectedUri
        }

        val calibration = state.calibration
        binding.calibrationPanel.isVisible = calibration != null
        binding.calibrateLink.isVisible = calibration == null && !viewModel.isCalibrated
        if (calibration != null) {
            binding.calibrationHint.text = calibration.hint
            val shown = (calibration.attempt + 1).coerceAtMost(calibration.total)
            binding.calibrationCounter.text = getString(R.string.cal_counter, shown, calibration.total)
            // The bar is the live push level: it is the proof that the push is
            // being registered, long before an attempt completes.
            val level = if (calibration.finished) 1f else calibration.livePush
            binding.calibrationProgress.setProgressCompat(
                (level * 100f).toInt().coerceIn(0, 100), false
            )
            binding.calibrationDone.isVisible = calibration.finished
        }

        binding.statusPill.text = statusText(state)
        // The pill reflects the link at a glance: neutral while searching,
        // warm ink when a peer is near, green once the pipe is real.
        val pillColor = when (state.nearbyState) {
            NearbyState.CONNECTED -> getColor(R.color.success)
            NearbyState.CONNECTING, NearbyState.PEER_FOUND -> getColor(R.color.accent)
            NearbyState.ERROR, NearbyState.PERMISSION_REQUIRED -> getColor(R.color.ink_soft)
            else -> getColor(R.color.ink_soft)
        }
        binding.statusPill.setTextColor(pillColor)
        binding.logText.text = state.logLines.joinToString("\n")
        updateHint()

        val transfer = state.transfer
        binding.transferCard.isVisible = transfer != null
        if (transfer != null) {
            binding.transferName.text = transfer.fileName
            binding.transferCancel.isVisible = transfer.outgoing
            if (transfer.totalBytes <= 0L) {
                if (!binding.progressBar.isIndeterminate) binding.progressBar.isIndeterminate = true
                binding.transferPercent.text = ""
            } else {
                if (binding.progressBar.isIndeterminate) binding.progressBar.isIndeterminate = false
                val percent = (transfer.fraction * 100f).toInt().coerceIn(0, 100)
                binding.progressBar.setProgressCompat(percent, true)
                binding.transferPercent.text = getString(R.string.percent_value, percent)
            }
        }
    }

    private fun statusText(state: MainUiState): String {
        state.transfer?.let { transfer ->
            val percent = (transfer.fraction * 100f).toInt().coerceIn(0, 100)
            return if (transfer.outgoing) {
                getString(R.string.status_sending_short, percent)
            } else {
                getString(R.string.status_receiving_short, percent)
            }
        }
        return when (state.nearbyState) {
            NearbyState.CONNECTED -> state.peerName?.take(14) ?: getString(R.string.status_connected)
            NearbyState.CONNECTING, NearbyState.PEER_FOUND -> getString(R.string.status_peer_short)
            NearbyState.SEARCHING, NearbyState.IDLE -> getString(R.string.status_searching_short)
            NearbyState.ERROR -> getString(R.string.status_offline_short)
            NearbyState.PERMISSION_REQUIRED -> getString(R.string.perm_denied)
        }
    }

    private fun updateHint() {
        val now = SystemClock.uptimeMillis()
        if (transientMessage != null && now < transientUntilMs) {
            binding.hintText.text = transientMessage
            return
        }
        val noHandFor = if (lastHandSeenAtMs == 0L) Long.MAX_VALUE else now - lastHandSeenAtMs
        val linkReady = currentState.nearbyState == NearbyState.CONNECTED
        val stats = lastStats
        // Gesture feedback always wins over the connection status: while the
        // phones are still finding each other the user must still see that the
        // hand is being read.
        binding.hintText.text = when {
            // The camera feed itself is the first thing to prove. If MediaPipe
            // never loaded, or no frame can be decoded, no gesture can ever work
            // and the user must be told instead of staring at a live preview.
            !stats.modelReady && stats.modelError != null ->
                getString(R.string.hint_model_missing, stats.modelError.orEmpty())
            stats.frames == 0L && stats.conversionFailures > 30L ->
                getString(R.string.hint_no_frames)
            currentState.calibration != null -> currentState.calibration!!.hint
            currentState.gestureState == GestureState.THROW_VECTOR -> getString(R.string.hint_sending)
            currentState.gestureState == GestureState.GRAB_FIST -> getString(R.string.hint_grab)
            noHandFor > NO_HAND_HINT_MS -> getString(R.string.hint_no_hand)
            currentState.queuedCount > 0 && !linkReady ->
                getString(R.string.hint_queued, currentState.queuedCount)
            !linkReady && currentState.transfer == null -> getString(R.string.hint_no_link)
            else -> getString(R.string.hint_idle)
        }
    }

    /**
     * Detects the conditions that make Nearby discovery fail *silently* and
     * surfaces them with a button that fixes them.
     */
    private fun refreshBlocker() {
        val neededPermission = Permissions.missingForDiscovery(this)
        val next = when {
            neededPermission != null -> Blocker.PERMISSION
            !Radios.bluetoothEnabled(this) -> Blocker.BLUETOOTH
            !Radios.locationEnabled(this) -> Blocker.LOCATION
            else -> Blocker.NONE
        }
        if (next == blocker) return
        blocker = next

        val visible = next != Blocker.NONE
        binding.connectionBanner.isVisible = visible
        if (!visible) return

        binding.bannerText.setText(
            when (next) {
                Blocker.PERMISSION -> R.string.banner_permission
                Blocker.BLUETOOTH -> R.string.banner_bluetooth_off
                Blocker.LOCATION -> R.string.banner_location_off
                Blocker.NONE -> R.string.status_searching_short
            }
        )
        binding.bannerAction.setText(
            when (next) {
                Blocker.PERMISSION -> R.string.banner_action_permission
                Blocker.BLUETOOTH -> R.string.banner_action_bluetooth
                else -> R.string.banner_action_location
            }
        )
    }

    private fun fixBlocker() {
        haptics.tick()
        when (blocker) {
            Blocker.PERMISSION -> requestPermissions()
            Blocker.BLUETOOTH -> {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                runCatching { enableBluetoothLauncher.launch(intent) }
                    .onFailure {
                        runCatching {
                            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    }
            }
            Blocker.LOCATION -> runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            Blocker.NONE -> Unit
        }
    }

    /**
     * First run only: offer the 20-second calibration instead of letting the user
     * discover the tuning by trial and error.
     */
    private fun promptCalibrationIfNeeded() {
        if (viewModel.isCalibrated || !viewModel.shouldOfferCalibration) return
        viewModel.markCalibrationOffered()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cal_title)
            .setMessage(R.string.cal_intro)
            .setPositiveButton(R.string.calibrate_action) { _, _ -> viewModel.startCalibration() }
            .setNegativeButton(R.string.cal_later, null)
            .show()
    }

    /**
     * The real hand-off: the selected file leaves the phone with the same
     * decelerating motion iOS uses, and the card carries its own thumbnail.
     */
    private fun playHandoff(name: String) {
        // Never skip the effect: if the phone has animations turned off, the
        // drop is shortened instead of disappearing. Deleting it leaves the user
        // with no confirmation at all, which is worse.
        val entry = viewModel.selectedEntry()
        val origin = handoffOrigin()
        ThumbnailLoader.bitmap(this, entry) { bitmap ->
            binding.beam.play(
                bitmap,
                reduceMotion = animationsDisabled(),
                isArrival = false,
                origin = origin
            )
        }
        showTransient(getString(R.string.handoff_sending, name))
    }

    /**
     * The stone has to hit the water where the file actually is: the ripple is
     * anchored on the selected card in the shelf, not in the middle of the
     * screen. Expressed as a fraction of the overlay so the view can scale it.
     */
    private fun handoffOrigin(): PointF? {
        val beamWidth = binding.beam.width
        val beamHeight = binding.beam.height
        if (beamWidth <= 0 || beamHeight <= 0) return null

        val list = IntArray(2)
        binding.filesList.getLocationOnScreen(list)
        val overlay = IntArray(2)
        binding.beam.getLocationOnScreen(overlay)

        val x = (list[0] + binding.filesList.width / 2f - overlay[0]) / beamWidth
        val y = (list[1] + binding.filesList.height / 2f - overlay[1]) / beamHeight
        return PointF(x.coerceIn(0.18f, 0.82f), y.coerceIn(0.18f, 0.82f))
    }

    /** Landing on this phone: the same language, played backwards. */
    private fun playArrival(name: String) {
        // The "recibido" message and the pulse come from the transfer callback;
        // here only the animation, so nothing is announced twice.
        binding.beam.play(
            null,
            reduceMotion = animationsDisabled(),
            isArrival = true,
            origin = handoffOrigin()
        )
    }

    /** True when the system (or MIUI) has turned animations off. */
    private fun animationsDisabled(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }.getOrDefault(false)

    private fun showTransient(text: String) {
        transientMessage = text
        transientUntilMs = SystemClock.uptimeMillis() + TRANSIENT_MS
        uiHandler.removeCallbacks(clearTransient)
        uiHandler.postDelayed(clearTransient, TRANSIENT_MS)
        updateHint()
    }

    // ------------------------------------------------------------ permissions

    private fun applyPermissions() {
        val essentials = Permissions.essentialsGranted(this)
        binding.permissionPanel.isVisible = !essentials
        binding.permSettings.isVisible = !essentials && permissionRequested
        if (!essentials) return

        viewModel.startSession()
        startCameraIfNeeded()
        // Only offer the calibration once the camera is actually available,
        // never on top of the permission gate.
        promptCalibrationIfNeeded()
    }

    private fun requestPermissions() {
        val missing = Permissions.missing(this)
        if (missing.isEmpty()) {
            applyPermissions()
            return
        }
        permissionRequested = true
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    // ----------------------------------------------------------------- camera

    private fun startCameraIfNeeded() {
        if (cameraStarted || cameraRequestPending) return
        if (!Permissions.granted(this, Manifest.permission.CAMERA)) return

        cameraRequestPending = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraRequestPending = false
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            providerRef = provider
            bindCamera(provider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        // Preview and analysis get different budgets on purpose. The preview is
        // what the user looks at; the analysis only has to be big enough for the
        // detector, which works at ~224 px internally.
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(PREVIEW_WIDTH, PREVIEW_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(cameraExecutor, viewModel.analyzer) }

        val preview = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        val selector = pickSelector(provider)
        if (selector == null) {
            showTransient(getString(R.string.camera_unavailable))
            return
        }

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
            cameraStarted = true
            binding.handOverlay.mirrorHorizontally = boundFrontCamera == true
        }.onFailure { error ->
            cameraStarted = false
            showTransient(error.message ?: getString(R.string.camera_unavailable))
        }
    }

    /**
     * Honours the user's front/back preference, falling back to whichever lens
     * the device actually exposes.
     */
    private fun pickSelector(provider: ProcessCameraProvider): CameraSelector? {
        val preferFront = prefs.useFrontCamera
        val hasFront = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }
            .getOrDefault(false)
        val hasBack = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }
            .getOrDefault(false)

        val useFront = when {
            preferFront && hasFront -> true
            !preferFront && hasBack -> false
            hasFront -> true
            hasBack -> false
            else -> return null
        }

        boundFrontCamera = useFront
        return if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    companion object {
        // Analysis at 480x360: a third of the pixels to copy, and the detector
        // downscales to 320 anyway. This alone buys ~10 ms per frame.
        private const val ANALYSIS_WIDTH = 480
        private const val ANALYSIS_HEIGHT = 360
        private const val PREVIEW_WIDTH = 640
        private const val PREVIEW_HEIGHT = 480
        private const val TRANSIENT_MS = 2800L
        private const val NO_HAND_HINT_MS = 5000L
    }
}
