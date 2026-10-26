package com.moonshare

import android.app.Application
import com.moonshare.core.CrashReport
import com.moonshare.core.MoonLog
import com.moonshare.core.Startup
import com.moonshare.core.sound.SfxPlayer
import com.moonshare.data.media.MediaRepository
import com.moonshare.data.settings.SettingsStore
import com.moonshare.transfer.TransferEngine
import com.moonshare.util.Notifx

/**
 * Composition root. La app a propósito no usa Hilt/Dagger: con 3 dependencias
 * (ajustes, media, motor) un grafo de inyección es puro ritual. Se instancia a mano
 * y punto, y eso además hace que el APK pese menos.
 *
 * Cada paso del arranque queda aislado en su propio `runCatching` y marcado en
 * [Startup]: si el sonido o las notificaciones revientan en una ROM rara, la app
 * tiene que poder abrir igual y decirte qué falló, no quedarse en negro.
 */
class MoonApplication : Application() {

    val sfx: SfxPlayer by lazy { SfxPlayer(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val media: MediaRepository by lazy { MediaRepository(this) }

    /** Se crea acá y no en un lazy: el servicio puede resucitar sin la UI. */
    lateinit var engine: TransferEngine
        private set

    /** true si el motor logró armarse. La pantalla de diagnóstico lo muestra. */
    var engineHealthy: Boolean = false
        private set

    /**
     * Registra un subsistema roto con su causa, para mostrarlo en la app. Importa el
     * nombre: "no se pudo crear TransferEngine" sin más esconde que lo que revienta
     * puede ser una dependencia (sonido, ajustes, media) y no el motor.
     */
    fun warn(what: String, t: Throwable) {
        val trace = runCatching {
            // printStackTrace no acepta Writer: solo PrintStream o PrintWriter.
            val sw = java.io.StringWriter()
            java.io.PrintWriter(sw).use { t.printStackTrace(it) }
            sw.toString().lines().drop(1).take(5).joinToString("\n").trim()
        }.getOrDefault("")
        val cause = generateSequence(t.cause) { it.cause }.lastOrNull() ?: t
        val line = buildString {
            append("· $what — ${t.javaClass.name}: ${t.message}")
            if (cause !== t) append(" | causa raíz: ${cause.javaClass.name}: ${cause.message}")
            if (trace.isNotBlank()) append("\n").append(trace)
        }
        MoonLog.e("App", line, t)
        runCatching { CrashReport.addBootWarning(this, line) }
        _bootWarnings.add(line)
    }

    private val _bootWarnings = mutableListOf<String>()

    /** Qué se rompió antes de dibujar (vacío = arranque limpio). */
    fun bootWarnings(): List<String> = _bootWarnings.toList()

    /** Acceso tolerante: si el motor no se creó, el llamador decide (no revienta). */
    fun engineOrNull(): TransferEngine? = if (::engine.isInitialized) engine else null

    /** Para el smoke test de arranque: fase del motor como texto, sin exponer el Flow. */
    internal fun engineStateForTest(): String =
        engineOrNull()?.let { it.state.value.phase.name } ?: ""

    override fun onCreate() {
        // 1) El handler de crash va ANTES de todo: es lo único que tiene que
        //    sobrevivir a cualquier fallo posterior.
        Startup.set(Startup.Phase.APP_BEGIN)
        CrashReport.install(this)
        super.onCreate()

        Startup.set(Startup.Phase.NOTIF)
        runCatching { Notifx.ensureChannels(this) }.onFailure { warn("notificaciones", it) }

        Startup.set(Startup.Phase.SFX)
        runCatching { sfx.init() }.onFailure { warn("sonido (SoundPool)", it) }

        Startup.set(Startup.Phase.ENGINE)
        runCatching {
            engine = TransferEngine(
                context = this,
                settings = settings,
                media = media,
                sfx = sfx,
            )
            engineHealthy = true
        }.onFailure { err ->
            // Sin motor no hay transferencia, pero la app abre y dice por qué. El aviso
            // se persiste: es el único modo de saberlo sin adb ni logcat.
            warn("motor de transferencia", err)
        }

        MoonLog.i("App", "MoonShare ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) listo")
    }

    override fun onTerminate() {
        // onTerminate no corre en devices reales; lo dejamos por si el proceso se
        // detiene en un emulador y además cubre el shutdown explícito del motor.
        runCatching { if (::engine.isInitialized) engine.shutdown() }
        runCatching { sfx.release() }
        super.onTerminate()
    }
}
