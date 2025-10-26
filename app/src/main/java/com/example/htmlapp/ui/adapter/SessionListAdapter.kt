package com.example.htmlapp.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.htmlapp.R
import com.example.htmlapp.databinding.ItemSessionBinding
import com.example.htmlapp.ui.ChatSessionUi

class SessionListAdapter(
    private val onOpen: (String) -> Unit,
    private val onDelete: (String) -> Unit,
) : ListAdapter<ChatSessionUi, SessionListAdapter.SessionViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SessionViewHolder(binding, onOpen, onDelete)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SessionViewHolder(
        private val binding: ItemSessionBinding,
        private val onOpen: (String) -> Unit,
        private val onDelete: (String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChatSessionUi) {
            binding.sessionName.text = item.title
            binding.promptInfo.text = item.subtitle
            binding.modelBadge.isVisible = item.isActive
            binding.modelBadge.text = binding.root.context.getString(R.string.session_active_badge)

            val accent = ContextCompat.getColor(binding.root.context, R.color.accent2)
            val border = ContextCompat.getColor(binding.root.context, R.color.border)
            binding.card.strokeColor = if (item.isActive) accent else border

            binding.btnOpen.setOnClickListener { onOpen(item.id) }
            binding.btnDelete.setOnClickListener { onDelete(item.id) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatSessionUi>() {
        override fun areItemsTheSame(oldItem: ChatSessionUi, newItem: ChatSessionUi): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatSessionUi, newItem: ChatSessionUi): Boolean =
            oldItem == newItem
    }
}
