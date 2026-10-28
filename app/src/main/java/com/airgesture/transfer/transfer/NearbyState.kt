package com.airgesture.transfer.transfer

/** Coarse transport state, mapped to human copy by the UI layer. */
enum class NearbyState {
    IDLE,
    PERMISSION_REQUIRED,
    SEARCHING,
    PEER_FOUND,
    CONNECTING,
    CONNECTED,
    ERROR
}

/** Snapshot of the last known transfer progress. */
data class TransferProgress(
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val outgoing: Boolean
) {
    val fraction: Float
        get() = when {
            totalBytes > 0L -> (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}
