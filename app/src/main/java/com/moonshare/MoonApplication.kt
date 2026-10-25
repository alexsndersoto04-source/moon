package com.moonshare

import android.app.Application
import com.moonshare.core.MoonLog
import com.moonshare.core.sound.SfxPlayer
import com.moonshare.data.media.MediaRepository
import com.moonshare.data.settings.SettingsStore
import com.moonshare.transfer.TransferEngine
import com.moonshare.util.Notifx

/**
 * Composition root. La app a propósito no usa Hilt/Dagger: con 3 dependencias
 * (ajustes, media, motor) un grafo de inyección es puro ritual. Se instancia a mano
 * y punto, y eso además hace que el APK pese menos.
 */
class MoonApplication : Application() {

    val sfx: SfxPlayer by lazy { SfxPlayer(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val media: MediaRepository by lazy { MediaRepository(this) }

    /** Se crea acá y no en un lazy: el servicio puede resucitar sin la UI. */
    lateinit var engine: TransferEngine
        private set

    override fun onCreate() {
        super.onCreate()
        Notifx.ensureChannels(this)
        sfx.init()
        engine = TransferEngine(
            context = this,
            settings = settings,
            media = media,
            sfx = sfx,
        )
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
