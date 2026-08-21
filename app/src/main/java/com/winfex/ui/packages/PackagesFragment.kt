package com.winfex.ui.packages

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.winfex.R
import com.winfex.core.RatPackageManager
import com.winfex.core.WinfexPaths
import com.winfex.databinding.FragmentPackagesBinding
import com.winfex.model.RatPackage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PackagesFragment : Fragment() {

    private var _b: FragmentPackagesBinding? = null
    private val b get() = _b!!

    private val openRat = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                showProgress(true, "正在安装", "解析 .rat 包")
                val pkg = RatPackageManager.installFromUri(requireContext(), uri)
                showProgress(false)
                if (pkg != null) {
                    Snackbar.make(b.root, "已安装：${pkg.name} ${pkg.version}", 3000).show()
                } else {
                    Snackbar.make(b.root, "安装失败：包格式无效", 4000).show()
                }
            }
        }
    }

    private val adapter = PackageAdapter(
        selectedUuids = {
            val s = RatPackageManager.selected.value
            setOfNotNull(s.coreUuid, s.wineUuid, s.box64Uuid, s.dxvkUuid,
                s.vkd3dUuid, s.wineD3dUuid, s.vulkanDriverUuid, s.wineUtilsUuid)
        },
        onSelect = { pkg -> selectPackage(pkg) },
        onUninstall = { pkg -> confirmUninstall(pkg) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentPackagesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.refresh.isEnabled = false

        b.fabInstall.setOnClickListener {
            openRat.launch(arrayOf("*/*"))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            RatPackageManager.packages.collectLatest { list ->
                adapter.submitList(list)
                b.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            RatPackageManager.installing.collectLatest { p ->
                if (p == null) {
                    showProgress(false)
                } else {
                    showProgress(true, p.name, p.message)
                    b.pbInstall.progress = p.percent
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            RatPackageManager.selected.collectLatest {
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun selectPackage(pkg: RatPackage) {
        viewLifecycleOwner.lifecycleScope.launch {
            RatPackageManager.select(pkg)
            Snackbar.make(b.root, "已选中：${pkg.name}", 1500).show()
        }
    }

    private fun confirmUninstall(pkg: RatPackage) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${pkg.name} ${pkg.version}")
            .setMessage(R.string.packages_confirm_uninstall)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    RatPackageManager.uninstall(pkg.uuid)
                    Snackbar.make(b.root, "已卸载", 2000).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showProgress(visible: Boolean, name: String = "", msg: String = "") {
        b.installProgress.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            b.tvInstallName.text = name
            b.tvInstallMsg.text = msg
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
