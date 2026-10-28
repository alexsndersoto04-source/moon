package com.airgesture.transfer.vision

import com.airgesture.transfer.core.GestureState
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * One paint-ready sample for the overlay. Deliberately tiny: it is emitted at
 * detection rate (~15-25 Hz) and never retained.
 */
data class OverlayFrame(
    val landmarks: List<NormalizedLandmark>?,
    val state: GestureState,
    val thrust: Float,
    val fist: Float,
    /** 0..3 push-meter band, drives the progressive haptic ticks. */
    val pushZone: Int = 0
)
