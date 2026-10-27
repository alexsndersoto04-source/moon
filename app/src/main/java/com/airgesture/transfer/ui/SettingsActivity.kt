package com.airgesture.transfer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.airgesture.transfer.BuildConfig
import com.airgesture.transfer.R
import com.airgesture.transfer.core.AirGestureApp
import com.airgesture.transfer.databinding.ActivitySettingsBinding
import com.airgesture.transfer.util.Haptics
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Real settings: every control writes straight into [com.airgesture.transfer.core.Prefs]
 * and takes effect the moment the user returns to the camera screen (camera source
 * is re-bound, the gesture engine re-reads its sensitivity).
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

        bindCamera()
        bindSensitivity()
        bindSwitches()
        bindConnectionInfo()

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

    // --------------------------------------------------------------- camera

    private fun bindCamera() {
        binding.cameraGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val front = checkedId == binding.cameraFront.id
            if (prefs.useFrontCamera != front) {
                prefs.useFrontCamera = front
                haptics.tick()
            }
        }
    }

    // ----------------------------------------------------------- sensitivity

    private fun bindSensitivity() {
        binding.sensitivitySlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            prefs.sensitivity = value
            updateSensitivityLabel(value)
            haptics.tick()
        }
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

    // ------------------------------------------------------------- switches

    private fun bindSwitches() {
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
        binding.cameraGroup.check(
            if (prefs.useFrontCamera) binding.cameraFront.id else binding.cameraBack.id
        )
        binding.sensitivitySlider.value = prefs.sensitivity.coerceIn(0f, 1f)
        updateSensitivityLabel(prefs.sensitivity)
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
