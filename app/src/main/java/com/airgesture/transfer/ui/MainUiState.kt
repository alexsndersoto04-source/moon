package com.airgesture.transfer.ui

import com.airgesture.transfer.core.GestureState
import com.airgesture.transfer.media.MediaEntry
import com.airgesture.transfer.media.MediaKind
import com.airgesture.transfer.transfer.NearbyState
import com.airgesture.transfer.transfer.TransferProgress

/** Everything the single screen needs, in one immutable snapshot. */
data class MainUiState(
    val kind: MediaKind = MediaKind.IMAGES,
    val items: List<MediaEntry> = emptyList(),
    val loading: Boolean = true,
    val selectedUri: String? = null,
    val gestureState: GestureState = GestureState.IDLE,
    val handVisible: Boolean = false,
    val nearbyState: NearbyState = NearbyState.IDLE,
    val peerName: String? = null,
    val transfer: TransferProgress? = null
)

/** One-shot notifications for the status line. */
sealed interface UiEvent {
    data class Sent(val name: String) : UiEvent
    data class Received(val name: String) : UiEvent
    data class Message(val text: String) : UiEvent
}
