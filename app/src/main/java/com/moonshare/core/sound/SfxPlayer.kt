package com.moonshare.core.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.moonshare.R
import com.moonshare.core.MoonLog

/**
 * Reproduce las gotas de agua. Usa [SoundPool] (no MediaPlayer) porque el caso de
 * uso es "un archivo pasó → sonido corto", y en una ráfaga de 40 fotos suenan 40
 * gotas: SoundPool las mezcla sin el retardo de preparar un decoder por golpe.
 *
 * Los .wav están sintetizados por `tools/make_sfx.py`; están sin comprimir
 * (`noCompress += "wav"`) para que la carga sea casi instantánea.
 */
class SfxPlayer(private val context: Context) {

    private var pool: SoundPool? = null
    private var volIn = 0
    private var volOut = 0
    private var volPair = 0
    private var ready = false

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var volume: Float = 0.55f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyVolume()
        }

    @Volatile
    var hapticsEnabled: Boolean = true

    fun init() {
        if (ready) return
        try {
            initUnsafe()
        } catch (e: Throwable) {
            // Un SoundPool que no arma no puede tumbar la app: solo perdemos la gota
            // de agua, y el usuario puede seguir transfiriendo archivos.
            MoonLog.w("SFX", "SoundPool no disponible", e)
            ready = false
        }
    }

    private fun initUnsafe() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val built = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(attributes)
            .build()
        pool = built
        volIn = built.load(context, R.raw.sfx_water_in, 1)
        volOut = built.load(context, R.raw.sfx_water_out, 1)
        volPair = built.load(context, R.raw.sfx_pair, 1)
        ready = true
    }

    fun onFileReceived() = play(volIn, 1.0f)

    fun onFileSent() = play(volOut, 1.0f)

    fun onPaired() = play(volPair, 1.0f)

    /**
     * [rate] != 1 varía el tono sin cambiar la duración. Se usa para que una
     * ráfaga de archivos no suene como una ametralladora idéntica: cada gota
     * sube o baja un semitono según el índice dentro del lote.
     */
    private fun play(soundId: Int, rate: Float) {
        if (!enabled || !ready || volume <= 0.001f) return
        val p = pool ?: return
        val v = volume
        runCatching { p.play(soundId, v, v, 1, 0, rate) }
    }

    private fun applyVolume() = Unit

    fun tick() {
        if (!hapticsEnabled) return
        val vib = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vib.hasVibrator()) return
        runCatching {
            vib.vibrate(
                VibrationEffect.createOneShot(14, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    fun release() {
        runCatching { pool?.release() }
        pool = null
        ready = false
    }
}
