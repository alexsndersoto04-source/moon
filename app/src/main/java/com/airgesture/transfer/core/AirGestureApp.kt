package com.airgesture.transfer.core

import android.app.Application
import com.airgesture.transfer.media.MediaRepository

/**
 * Process-wide singletons. Everything here is cheap to create: no I/O, no model
 * loading. Heavy work (MediaPipe graph, MediaStore queries) is always owned by a
 * lifecycle-scoped component so a low-RAM device never keeps it alive in the
 * background.
 */
class AirGestureApp : Application() {

    val prefs: Prefs by lazy { Prefs(this) }
    val mediaRepository: MediaRepository by lazy { MediaRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: AirGestureApp
            private set
    }
}
