package com.airgesture.transfer.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.airgesture.transfer.R
import com.airgesture.transfer.databinding.ActivityShareBinding
import com.airgesture.transfer.transfer.TransferService
import com.airgesture.transfer.util.Motion
import com.airgesture.transfer.util.Permissions

/**
 * The share-sheet target: "…→ Compartir → AirGesture".
 *
 * This is the feature that makes the app usable without opening it. The user
 * picks photos in Google Photos, Files or any gallery, shares them here, and this
 * translucent card hands the URIs to [TransferService] and disappears in about a
 * second. From that moment the foreground notification owns the experience; the
 * activity never blocks the transfer.
 *
 * It deliberately requests only *transport* permissions (BLE + location +
 * notifications): the file arrives with its own read grant from the sharing app,
 * so no gallery permission is needed or asked for.
 */
class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private var uris: List<Uri> = emptyList()
    private var dispatched = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Granted or not, the send is attempted: the service explains the
        // missing permission in the notification instead of failing silently.
        dispatch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.alpha = 0f
        binding.root.animate().alpha(1f).setDuration(140L).start()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        uris = extract(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_SHORT).show()
            close()
            return
        }

        binding.shareTitle.text = if (uris.size == 1) {
            getString(R.string.share_sending_one)
        } else {
            getString(R.string.share_sending_many, uris.size)
        }
        val missing = Permissions.missingTransport(this)
        if (missing.isEmpty()) {
            dispatch()
        } else {
            binding.shareDetail.setText(R.string.share_hint_permission)
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun dispatch() {
        if (dispatched) return

        // Denied Bluetooth/location would let the service start and fail with no
        // explanation: say it here, where the user still sees the dialog result.
        if (Permissions.missingForSending(this).isNotEmpty()) {
            Toast.makeText(this, R.string.share_need_permissions, Toast.LENGTH_LONG).show()
            dispatched = true
            close()
            return
        }

        dispatched = true
        TransferService.sendUris(this, uris)
        Motion.crossfade(binding.shareDetail, getString(R.string.share_hint_running))
        // Leave quickly: the notification is the interface from here on.
        binding.root.postDelayed({ close() }, LINGER_MS)
    }

    private fun close() {
        if (isFinishing || isDestroyed) return
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun extract(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val found = LinkedHashSet<Uri>()

        when (intent.action) {
            Intent.ACTION_SEND -> singleUri(intent)?.let { found += it }
            Intent.ACTION_SEND_MULTIPLE -> multipleUris(intent)?.let { found.addAll(it) }
        }

        // Modern choosers deliver streams in the ClipData as well; take whatever
        // is there, deduplicating by URI.
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { found += it }
            }
        }

        return found.filter { it.scheme == "content" || it.scheme == "file" }
    }

    @Suppress("DEPRECATION")
    private fun singleUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun multipleUris(intent: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }

    private companion object {
        const val LINGER_MS = 1100L
    }
}
