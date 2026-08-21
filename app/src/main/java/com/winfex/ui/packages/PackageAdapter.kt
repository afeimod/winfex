package com.winfex.ui.packages

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.winfex.core.WinfexPaths
import com.winfex.databinding.ItemPackageBinding
import com.winfex.model.RatPackage
import java.io.File

class PackageAdapter(
    private val selectedUuids: () -> Set<String>,
    private val onSelect: (RatPackage) -> Unit,
    private val onUninstall: (RatPackage) -> Unit
) : ListAdapter<RatPackage, PackageAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPackageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemPackageBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.btnSelect.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onSelect(getItem(pos))
            }
            b.btnUninstall.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onUninstall(getItem(pos))
            }
        }

        fun bind(item: RatPackage) {
            b.tvName.text = "${item.name} ${item.version}"
            val size = computeSize(item)
            b.tvMeta.text = "${item.category} · ${item.architecture} · ${size}"

            val isSelected = item.uuid in selectedUuids()
            b.chipSelected.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE
            b.btnSelect.text = if (isSelected) "已选中" else "选中"
            b.btnSelect.isEnabled = !isSelected
        }

        private fun computeSize(pkg: RatPackage): String {
            return try {
                val dir = WinfexPaths.packageDir(pkg.uuid, pkg.category)
                if (!dir.exists()) return "—"
                val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                when {
                    bytes < 1024 -> "$bytes B"
                    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                    bytes < 1024 * 1024 * 1024 -> "${bytes / 1024 / 1024} MB"
                    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
                }
            } catch (_: Exception) { "—" }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RatPackage>() {
            override fun areItemsTheSame(o: RatPackage, n: RatPackage) = o.uuid == n.uuid
            override fun areContentsTheSame(o: RatPackage, n: RatPackage) = o == n
        }
    }
}
