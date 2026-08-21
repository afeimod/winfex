package com.winfex.ui.input

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.winfex.core.InputController
import com.winfex.databinding.ItemInputProfileBinding
import com.winfex.model.InputProfile

class InputProfileAdapter(
    private val onActiveChange: (InputProfile, Boolean) -> Unit,
    private val onLongClick: (InputProfile) -> Unit
) : ListAdapter<InputProfile, InputProfileAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemInputProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemInputProfileBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.swActive.setOnCheckedChangeListener { _, isChecked ->
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onActiveChange(getItem(pos), isChecked)
                }
            }
            b.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onLongClick(getItem(pos)); true
                } else false
            }
        }

        fun bind(item: InputProfile) {
            b.tvName.text = item.name
            val btnCount = item.elements.count { it.type == com.winfex.model.ElementType.BUTTON }
            val dpadCount = item.elements.count { it.type == com.winfex.model.ElementType.DPAD }
            val stickCount = item.elements.count {
                it.type == com.winfex.model.ElementType.STICK ||
                it.type == com.winfex.model.ElementType.TRACKPAD
            }
            b.tvMeta.text = "$btnCount buttons · ${if (dpadCount > 0) "dpad" else "no dpad"} · $stickCount sticks"
            b.swActive.isChecked = (InputController.active?.id == item.id)
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<InputProfile>() {
            override fun areItemsTheSame(o: InputProfile, n: InputProfile) = o.id == n.id
            override fun areContentsTheSame(o: InputProfile, n: InputProfile) = o == n
        }
    }
}
