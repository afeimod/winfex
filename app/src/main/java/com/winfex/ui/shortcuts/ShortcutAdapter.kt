package com.winfex.ui.shortcuts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.winfex.core.WinePrefixManager
import com.winfex.databinding.ItemShortcutBinding
import com.winfex.model.ShortcutEntry

class ShortcutAdapter(
    private val onPlay: (ShortcutEntry) -> Unit,
    private val onLongClick: (ShortcutEntry) -> Unit
) : ListAdapter<ShortcutEntry, ShortcutAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemShortcutBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemShortcutBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.btnPlay.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onPlay(getItem(pos))
            }
            b.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onLongClick(getItem(pos)); true
                } else false
            }
        }

        fun bind(item: ShortcutEntry) {
            b.tvName.text = item.name
            b.tvTarget.text = buildString {
                append(item.target)
                if (item.arguments.isNotEmpty()) {
                    append(" ").append(item.arguments)
                }
            }
            val prefix = WinePrefixManager.get(item.prefixId)
            b.tvContainer.text = prefix?.name ?: item.prefixId
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ShortcutEntry>() {
            override fun areItemsTheSame(o: ShortcutEntry, n: ShortcutEntry) = o.id == n.id
            override fun areContentsTheSame(o: ShortcutEntry, n: ShortcutEntry) = o == n
        }
    }
}
