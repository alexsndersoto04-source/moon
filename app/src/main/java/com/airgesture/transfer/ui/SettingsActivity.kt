package com.airgesture.transfer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.airgesture.transfer.BuildConfig
import com.airgesture.transfer.R
import android.view.View
import com.airgesture.transfer.core.AirGestureApp
import com.airgesture.transfer.core.Diag
import com.airgesture.transfer.databinding.ActivitySettingsBinding
import com.airgesture.transfer.util.Haptics
import kotlin.math.roundToInt

/**
 * Real settings. Every control writes straight into Prefs and the camera screen
 * re-reads them on resume (camera lens, sensitivity, overlays, screen wake).
 *
 * Deliberately built from boring widgets — TextView chips, SwitchCompat and the
 * platform SeekBar — so it cannot fail on an old or low-end device.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var haptics: Haptics
    private val prefs get() = (application as AirGestureApp).prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        haptics = Haptics(this)

        binding.cameraFront.setOnClickListener { setFrontCamera(true) }
        binding.cameraBack.setOnClickListener { setFrontCamera(false) }

        binding.sensitivitySlider.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress / 100f
                    updateSensitivityLabel(value)
                    if (fromUser) prefs.sensitivity = value
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            }
        )

        binding.switchFistSend.setOnCheckedChangeListener { _, checked ->
            prefs.fistToSend = checked
            haptics.tick()
        }

        binding.effectButton.setOnClickListener {
            haptics.tick()
            previewEffect()
        }

        binding.switchSkeleton.setOnCheckedChangeListener { _, checked ->
            prefs.skeletonVisible = checked
        }
        binding.switchHaptics.setOnCheckedChangeListener { _, checked ->
            prefs.hapticsEnabled = checked
            if (checked) haptics.confirm()
        }
        binding.switchAwake.setOnCheckedChangeListener { _, checked ->
            prefs.keepScreenOn = checked
        }
        binding.switchDiagnostics.setOnCheckedChangeListener { _, checked ->
            prefs.diagnosticsVisible = checked
        }

        binding.diagShowButton.setOnClickListener {
            val visible = binding.diagText.visibility != View.VISIBLE
            binding.diagText.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) binding.diagText.text = Diag.report(this)
        }

        binding.diagCopyButton.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("airgesture-diag", Diag.report(this))
            )
            android.widget.Toast.makeText(this, R.string.diag_copied, android.widget.Toast.LENGTH_SHORT)
                .show()
        }

        binding.calibrateButton.setOnClickListener {
            // Hand control goes back to the camera screen, which owns the overlay.
            setResult(RESULT_CALIBRATE)
            finish()
        }

        binding.resetButton.setOnClickListener {
            prefs.resetToDefaults()
            haptics.confirm()
            loadValues()
        }

        binding.versionLabel.text = getString(
            R.string.settings_version,
            BuildConfig.VERSION_NAME,
            NEARBY_VERSION
        )

        loadValues()
    }

    override fun onStart() {
        super.onStart()
        bindConnectionInfo()
    }

    private fun setFrontCamera(front: Boolean) {
        if (prefs.useFrontCamera == front) return
        prefs.useFrontCamera = front
        haptics.tick()
        updateCameraChips()
    }

    private fun updateCameraChips() {
        binding.cameraFront.isSelected = prefs.useFrontCamera
        binding.cameraBack.isSelected = !prefs.useFrontCamera
    }

    private fun updateSensitivityLabel(value: Float) {
        val percent = (value * 100f).roundToInt()
        val label = when {
            value < 0.35f -> R.string.settings_sensitivity_low
            value < 0.75f -> R.string.settings_sensitivity_medium
            else -> R.string.settings_sensitivity_high
        }
        binding.sensitivityValue.text =
            getString(R.string.settings_sensitivity_value, percent, getString(label))
    }

    private fun bindConnectionInfo() {
        val app = application as AirGestureApp
        binding.connectionStatus.text = getString(
            R.string.connection_detail,
            getString(R.string.connection_none),
            app.deviceLabel()
        )
    }

    /**
     * Plays the hand-off animation in a translucent dialog.
     *
     * This exists so the effect can be verified in one second without a second
     * phone and without sending anything: the same [TransferBeamView] and the
     * same call the real transfer uses, so what is seen here is what happens.
     */
    private fun previewEffect() {
        val container = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#22000000"))
        }
        val beam = TransferBeamView(this)
        container.addView(
            beam,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val dialog = android.app.Dialog(
            this,
            android.R.style.Theme_Translucent_NoTitleBar_Fullscreen
        )
        dialog.setContentView(container)
        dialog.setCancelable(true)
        dialog.show()

        // Origin in the middle of the dialog: same animation the real transfer
        // uses, so what is previewed is exactly what happens.
        beam.play(null, reduceMotion = false, isArrival = false, origin = android.graphics.PointF(0.5f, 0.55f))
        container.postDelayed({ dialog.dismiss() }, 1500L)
    }

    private fun loadValues() {
        updateCameraChips()
        val progress = (prefs.sensitivity.coerceIn(0f, 1f) * 100f).roundToInt()
        binding.sensitivitySlider.progress = progress
        updateSensitivityLabel(progress / 100f)
        binding.switchFistSend.isChecked = prefs.fistToSend
        binding.switchSkeleton.isChecked = prefs.skeletonVisible
        binding.switchHaptics.isChecked = prefs.hapticsEnabled
        binding.switchAwake.isChecked = prefs.keepScreenOn
        binding.switchDiagnostics.isChecked = prefs.diagnosticsVisible
    }

    companion object {
        private const val NEARBY_VERSION = "19.3.0"
        const val RESULT_CALIBRATE = 42

        fun intent(from: android.content.Context): Intent =
            Intent(from, SettingsActivity::class.java)
    }
}
