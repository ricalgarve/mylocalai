package com.pocketpal.server

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pocketpal.server.databinding.ItemChatAssistantBinding
import com.pocketpal.server.databinding.ItemChatUserBinding

data class ChatMessageModel(
    val role: String, // "user" or "assistant"
    var content: String
)

class ChatAdapter(
    private val messages: MutableList<ChatMessageModel>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].role == "user") TYPE_USER else TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            val binding = ItemChatUserBinding.inflate(inflater, parent, false)
            UserViewHolder(binding)
        } else {
            val binding = ItemChatAssistantBinding.inflate(inflater, parent, false)
            AssistantViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is UserViewHolder) {
            holder.binding.tvUserMessage.text = msg.content
        } else if (holder is AssistantViewHolder) {
            holder.binding.tvAssistantMessage.text = msg.content
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(msg: ChatMessageModel) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun appendToLastMessage(chunk: String) {
        if (messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            messages[lastIdx].content += chunk
            notifyItemChanged(lastIdx)
        }
    }

    fun updateLastMessage(content: String) {
        if (messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            messages[lastIdx].content = content
            notifyItemChanged(lastIdx)
        }
    }

    fun clearMessages() {
        val count = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, count)
    }

    class UserViewHolder(val binding: ItemChatUserBinding) : RecyclerView.ViewHolder(binding.root)
    class AssistantViewHolder(val binding: ItemChatAssistantBinding) : RecyclerView.ViewHolder(binding.root)
}
