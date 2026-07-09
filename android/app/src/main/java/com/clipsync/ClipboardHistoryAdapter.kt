package com.clipsync

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.clipsync.data.ClipboardEntryPreview
import com.clipsync.databinding.ItemClipboardEntryBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for displaying clipboard history entries.
 *
 * Uses ViewBinding for type-safe view access and DiffUtil for efficient
 * list updates (only re-renders changed items instead of the whole list).
 *
 * Each item shows:
 * - Text preview (first 200 chars with ellipsis if longer)
 * - Timestamp formatted as "HH:mm:ss MMM dd"
 * - Character count formatted with locale-appropriate separators (e.g., "1,234 chars")
 */
class ClipboardHistoryAdapter(
    /** Lambda invoked when the user taps a history entry */
    private val onItemClick: (ClipboardEntryPreview) -> Unit
) : RecyclerView.Adapter<ClipboardHistoryAdapter.ViewHolder>() {

    /** Current list of entries displayed */
    private var entries: List<ClipboardEntryPreview> = emptyList()

    /** Date formatter for timestamps */
    private val dateFormat = SimpleDateFormat("HH:mm:ss MMM dd", Locale.getDefault())

    /** Number formatter for char counts (adds locale-specific separators) */
    private val numberFormat = NumberFormat.getIntegerInstance()

    /**
     * Update the displayed list with DiffUtil for efficient animations.
     *
     * DiffUtil compares old and new lists, computes the minimal set of changes,
     * and dispatches only those changes to the RecyclerView. This means:
     * - Items that didn't change aren't re-bound (saves CPU)
     * - Insert/remove animations play automatically
     * - Much better performance than notifyDataSetChanged()
     */
    fun submitList(newEntries: List<ClipboardEntryPreview>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = entries.size
            override fun getNewListSize() = newEntries.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                return entries[oldPos].id == newEntries[newPos].id
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                return entries[oldPos] == newEntries[newPos]
            }
        })

        entries = newEntries
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemClipboardEntryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount() = entries.size

    /**
     * ViewHolder that binds a ClipboardEntryPreview to the item layout.
     * Uses ViewBinding for type-safe access to views.
     */
    inner class ViewHolder(
        private val binding: ItemClipboardEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // Set click listener once during creation (not in bind)
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(entries[position])
                }
            }
        }

        /**
         * Bind an entry's data to the views.
         *
         * Shows a 200-char preview with ellipsis for longer texts,
         * the formatted timestamp, and the character count.
         */
        fun bind(entry: ClipboardEntryPreview) {
            // Show first 200 chars with ellipsis if the original text was longer
            val displayText = if (entry.preview.length > 200) {
                entry.preview.take(200) + "\u2026" // Unicode ellipsis
            } else {
                entry.preview
            }
            binding.previewText.text = displayText

            // Format timestamp: "14:23:05 Jul 09"
            binding.timestampText.text = dateFormat.format(Date(entry.timestamp))

            // Format char count with separators: "1,234 chars"
            binding.charCountText.text = "${numberFormat.format(entry.charCount)} chars"
        }
    }
}
