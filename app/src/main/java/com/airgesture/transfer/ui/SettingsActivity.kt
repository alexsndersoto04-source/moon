package com.airgesture.transfer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.airgesture.transfer.BuildConfig
import com.airgesture.transfer.R
import com.airgesture.transfer.core.AirGestureApp
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

    private fun loadValues() {
        updateCameraChips()
        val progress = (prefs.sensitivity.coerceIn(0f, 1f) * 100f).roundToInt()
        binding.sensitivitySlider.progress = progress
        updateSensitivityLabel(progress / 100f)
        binding.switchSkeleton.isChecked = prefs.skeletonVisible
        binding.switchHaptics.isChecked = prefs.hapticsEnabled
        binding.switchAwake.isChecked = prefs.keepScreenOn
        binding.switchDiagnostics.isChecked = prefs.diagnosticsVisible
    }

    companion object {
        private const val NEARBY_VERSION = "19.3.0"

        fun intent(from: android.content.Context): Intent =
            Intent(from, SettingsActivity::class.java)
    }
}
