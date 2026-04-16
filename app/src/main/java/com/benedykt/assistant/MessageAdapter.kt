package com.benedykt.assistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = mutableListOf<Message>()

    fun add(m: Message) {
        items.add(m)
        notifyItemInserted(items.size - 1)
    }

    override fun getItemViewType(position: Int): Int = items[position].role.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (Message.Role.values()[viewType]) {
            Message.Role.USER -> R.layout.item_message_user
            Message.Role.ASSISTANT -> R.layout.item_message_assistant
            Message.Role.SYSTEM -> R.layout.item_message_system
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = items[position].text
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.messageText)
    }
}
