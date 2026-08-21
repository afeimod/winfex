package com.winfex.ui.prefixes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.winfex.databinding.ItemPrefixBinding
import com.winfex.model.WinePrefix

class PrefixAdapter(
    private val onRun: (WinePrefix) -> Unit,
    private val onInit: (WinePrefix) -> Unit,
    private val onMore: (WinePrefix) -> Unit
) : ListAdapter<WinePrefix, PrefixAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPrefixBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemPrefixBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.btnRun.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRun(getItem(pos))
            }
            b.btnInit.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onInit(getItem(pos))
            }
            b.btnMore.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onMore(getItem(pos))
            }
        }

        fun bind(item: WinePrefix) {
            b.tvName.text = item.name
            b.tvId.text = "id: ${item.id}"
            b.tvWinver.text = item.windowsVersion
            b.tvRenderer.text = item.d3dxRenderer
            b.tvAudio.text = item.audioSink
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<WinePrefix>() {
            override fun areItemsTheSame(o: WinePrefix, n: WinePrefix) = o.id == n.id
            override fun areContentsTheSame(o: WinePrefix, n: WinePrefix) = o == n
        }
    }
}
