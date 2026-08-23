package com.winfex.ui.packages

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.winfex.core.ImageFsInstaller
import com.winfex.databinding.ItemPackageBinding

class ComponentStatusAdapter(
    private val items: List<ImageFsInstaller.ComponentStatus>
) : RecyclerView.Adapter<ComponentStatusAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPackageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemPackageBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ImageFsInstaller.ComponentStatus) {
            b.tvName.text = item.displayName
            b.tvMeta.text = if (item.installed) "✓ 已安装" else "✗ 未安装"
            b.chipSelected.visibility = if (item.installed) View.VISIBLE else View.GONE
            b.btnSelect.visibility = View.GONE
            b.btnUninstall.visibility = View.GONE
        }
    }
}
