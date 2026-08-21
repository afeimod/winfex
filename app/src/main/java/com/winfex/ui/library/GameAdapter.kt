package com.winfex.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.winfex.core.WinePrefixManager
import com.winfex.databinding.ItemGameBinding
import com.winfex.model.GameItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GameAdapter(
    private val onPlay: (GameItem) -> Unit,
    private val onLongClick: (GameItem) -> Unit
) : ListAdapter<GameItem, GameAdapter.VH>(DIFF) {

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemGameBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val b: ItemGameBinding) : RecyclerView.ViewHolder(b.root) {
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

        fun bind(item: GameItem) {
            b.tvName.text = item.name
            val prefix = WinePrefixManager.get(item.prefixId)
            b.tvContainer.text = "${prefix?.name ?: item.prefixId} · ${item.exePath}"
            val meta = if (item.lastPlayedAt > 0) {
                "已游玩 ${item.playCount} 次 · 最近 ${sdf.format(Date(item.lastPlayedAt))}"
            } else {
                "已游玩 ${item.playCount} 次"
            }
            b.tvMeta.text = meta
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<GameItem>() {
            override fun areItemsTheSame(o: GameItem, n: GameItem) = o.id == n.id
            override fun areContentsTheSame(o: GameItem, n: GameItem) = o == n
        }
    }
}
