package com.airgesture.transfer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.airgesture.transfer.R
import com.airgesture.transfer.databinding.ItemFileBinding
import com.airgesture.transfer.media.MediaEntry
import com.airgesture.transfer.util.Formatters

/**
 * Horizontal shelf of transferable files. DiffUtil keeps scrolling at 60 fps;
 * thumbnails are loaded asynchronously and cancelled by tag on recycle.
 */
class FileAdapter(
    private val onSelect: (MediaEntry) -> Unit
) : ListAdapter<MediaEntry, FileAdapter.Holder>(DIFF) {

    var selectedKey: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), getItem(position).key == selectedKey)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.applySelection(getItem(position).key == selectedKey)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        ThumbnailLoader.clear(holder.binding.thumb)
    }

    inner class Holder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: MediaEntry, selected: Boolean) {
            val context = binding.root.context
            binding.fileName.text = Formatters.shorten(entry.name, 24)
            binding.fileMeta.text = metaFor(entry)
            binding.fileCard.setOnClickListener { onSelect(entry) }
            binding.fileCard.contentDescription = "${entry.name}, ${metaFor(entry)}"

            ThumbnailLoader.load(context, entry, binding.thumb)
            applySelection(selected)
        }

        fun applySelection(selected: Boolean) {
            val context = binding.root.context
            binding.selectionDot.visibility = if (selected) android.view.View.VISIBLE else android.view.View.GONE
            binding.fileCard.strokeWidth = if (selected) dp(context, 1.6f) else dp(context, 1f)
            binding.fileCard.strokeColor = ContextCompat.getColor(
                context,
                if (selected) R.color.ink else R.color.hairline
            )
            binding.fileName.setTextColor(
                ContextCompat.getColor(context, if (selected) R.color.ink else R.color.ink_soft)
            )
        }

        private fun metaFor(entry: MediaEntry): String {
            val size = Formatters.bytes(entry.sizeBytes)
            val duration = Formatters.duration(entry.durationMs)
            return if (duration.isNotEmpty()) "$duration · $size" else size
        }

        private fun dp(context: android.content.Context, value: Float): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"

        private val DIFF = object : DiffUtil.ItemCallback<MediaEntry>() {
            override fun areItemsTheSame(oldItem: MediaEntry, newItem: MediaEntry): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: MediaEntry, newItem: MediaEntry): Boolean =
                oldItem == newItem
        }
    }
}
