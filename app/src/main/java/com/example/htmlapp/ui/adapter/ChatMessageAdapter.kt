package com.example.htmlapp.ui.adapter

import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.htmlapp.R
import com.example.htmlapp.databinding.ItemLoadMoreBinding
import com.example.htmlapp.databinding.ItemMessageAssistantBinding
import com.example.htmlapp.databinding.ItemMessageUserBinding
import com.example.htmlapp.ui.ChatMessageUi
import com.example.htmlapp.ui.ChatRole

class ChatMessageAdapter(
    private val onLoadMore: () -> Unit,
    private val onDelete: (String) -> Unit,
    private val onStopStreaming: () -> Unit,
    private val onRegenerate: (String) -> Unit,
) : ListAdapter<ChatMessageAdapter.Row, RecyclerView.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_LOAD_MORE -> LoadMoreViewHolder(ItemLoadMoreBinding.inflate(inflater, parent, false), onLoadMore)
            VIEW_TYPE_ASSISTANT -> AssistantViewHolder(ItemMessageAssistantBinding.inflate(inflater, parent, false), onDelete, onStopStreaming, onRegenerate)
            else -> UserViewHolder(ItemMessageUserBinding.inflate(inflater, parent, false), onDelete)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is LoadMoreViewHolder -> holder.bind()
            is AssistantViewHolder -> holder.bind(getItem(position) as Row.Message)
            is UserViewHolder -> holder.bind(getItem(position) as Row.Message)
        }
    }

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        Row.LoadMore -> VIEW_TYPE_LOAD_MORE
        is Row.Message -> if (item.message.role == ChatRole.Assistant) VIEW_TYPE_ASSISTANT else VIEW_TYPE_USER
    }

    fun buildItems(messages: List<ChatMessageUi>, canLoadMore: Boolean): List<Row> {
        val rows = mutableListOf<Row>()
        if (canLoadMore) {
            rows += Row.LoadMore
        }
        messages.forEachIndexed { index, message ->
            rows += Row.Message(index + 1, message)
        }
        return rows
    }

    class UserViewHolder(
        private val binding: ItemMessageUserBinding,
        private val onDelete: (String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row.Message) {
            val message = row.message
            binding.messageIndex.text = binding.root.context.getString(R.string.message_index_format, row.index)
            binding.messageContent.text = message.content
            binding.btnEdit.isVisible = false
            binding.btnDeleteMsg.setOnClickListener { onDelete(message.id) }
        }
    }

    class AssistantViewHolder(
        private val binding: ItemMessageAssistantBinding,
        private val onDelete: (String) -> Unit,
        private val onStopStreaming: () -> Unit,
        private val onRegenerate: (String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row.Message) {
            val message = row.message
            binding.messageIndex.text = binding.root.context.getString(R.string.message_index_format, row.index)
            binding.modelBadge.text = message.modelLabel ?: binding.root.context.getString(R.string.model_unknown)
            binding.messageContent.text = message.content
            binding.messageContent.movementMethod = LinkMovementMethod.getInstance()

            val showThinking = !message.thinking.isNullOrBlank()
            binding.thinkingCard.isVisible = showThinking
            if (showThinking) {
                binding.thinkingContent.text = message.thinking
                binding.thinkingContent.isVisible = true
                binding.thinkingHeader.setOnClickListener {
                    binding.thinkingContent.isVisible = !binding.thinkingContent.isVisible
                }
            } else {
                binding.thinkingContent.isVisible = false
            }

            if (message.isError) {
                binding.messageContent.setTextColor(ContextCompat.getColor(binding.root.context, R.color.danger))
                binding.messageContent.text = message.errorMessage ?: message.content
            } else {
                binding.messageContent.setTextColor(ContextCompat.getColor(binding.root.context, R.color.fg))
            }

            binding.btnDeleteMsg.setOnClickListener { onDelete(message.id) }
            binding.btnEdit.isVisible = false

            val isStreaming = message.isStreaming
            binding.btnRegen.text = if (isStreaming) {
                binding.root.context.getString(R.string.action_stop)
            } else {
                binding.root.context.getString(R.string.action_regenerate)
            }
            binding.btnRegen.setOnClickListener {
                if (isStreaming) {
                    onStopStreaming()
                } else {
                    onRegenerate(message.id)
                }
            }
        }
    }

    class LoadMoreViewHolder(
        private val binding: ItemLoadMoreBinding,
        private val onLoadMore: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind() {
            binding.btnLoadMore.setOnClickListener { onLoadMore() }
        }
    }

    sealed class Row {
        data object LoadMore : Row()
        data class Message(val index: Int, val message: ChatMessageUi) : Row()
    }

    private object DiffCallback : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
            oldItem is Row.LoadMore && newItem is Row.LoadMore -> true
            oldItem is Row.Message && newItem is Row.Message -> oldItem.message.id == newItem.message.id
            else -> false
        }

        override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean = oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_LOAD_MORE = 0
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
    }
}
