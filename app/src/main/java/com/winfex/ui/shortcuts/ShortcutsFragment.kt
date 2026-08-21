package com.winfex.ui.shortcuts

import android.content.Intent
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
import com.winfex.core.AudioService
import com.winfex.core.DXWrapperInstaller
import com.winfex.core.RatPackageManager
import com.winfex.core.ShortcutImporter
import com.winfex.core.WinePrefixManager
import com.winfex.core.WineRunnerService
import com.winfex.core.WineWrapper
import com.winfex.databinding.FragmentShortcutsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ShortcutsFragment : Fragment() {

    private var _b: FragmentShortcutsBinding? = null
    private val b get() = _b!!

    private val adapter = ShortcutAdapter(
        onPlay = { launchShortcut(it) },
        onLongClick = { showMenu(it) }
    )

    private var pendingPrefixId: String? = null

    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && pendingPrefixId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val entry = ShortcutImporter.importFromUri(
                    requireContext(), uri, pendingPrefixId!!)
                if (entry != null) {
                    Snackbar.make(b.root, "已导入：${entry.name}", 2000).show()
                } else {
                    Snackbar.make(b.root, "导入失败：不支持的格式", 3000).show()
                }
                pendingPrefixId = null
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentShortcutsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.fabImport.setOnClickListener { showImportDialog() }
        b.fabScan.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val prefixes = WinePrefixManager.prefixes.value
                for (p in prefixes) {
                    ShortcutImporter.scanPrefixLinks(p.id)
                }
                Snackbar.make(b.root, "扫描完成", 2000).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            ShortcutImporter.shortcuts.collectLatest { list ->
                adapter.submitList(list)
                b.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showImportDialog() {
        val prefixes = WinePrefixManager.prefixes.value
        if (prefixes.isEmpty()) {
            Snackbar.make(b.root, "请先在「前缀」标签页创建一个前缀", 3000).show()
            return
        }
        val labels = prefixes.map { "${it.name} (${it.id})" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择目标前缀")
            .setItems(labels) { _, which ->
                pendingPrefixId = prefixes[which].id
                openDoc.launch(arrayOf("*/*"))
            }
            .show()
    }

    private fun launchShortcut(entry: com.winfex.model.ShortcutEntry) {
        val prefix = WinePrefixManager.get(entry.prefixId) ?: run {
            Snackbar.make(b.root, "前缀不存在", 3000).show()
            return
        }
        if (RatPackageManager.missingCategories().isNotEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.native_missing_title)
                .setMessage(getString(R.string.native_missing_message,
                    RatPackageManager.missingCategories().joinToString("\n\n") { "• $it" }))
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                DXWrapperInstaller.install(prefix)
                AudioService.start(prefix) { }
                val pgid = WineWrapper.launch(
                    WineWrapper.LaunchParams(
                        prefix = prefix,
                        exePath = entry.target,
                        arguments = entry.arguments,
                        workdir = entry.workingDir
                    )
                ) { }
                WineRunnerService.start(requireContext(), pgid, entry.name)
                Snackbar.make(b.root, "已启动：${entry.name} (pid=$pgid)", 3000).show()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("启动失败")
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showMenu(entry: com.winfex.model.ShortcutEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(entry.name)
            .setItems(arrayOf("启动", "删除")) { _, which ->
                when (which) {
                    0 -> launchShortcut(entry)
                    1 -> viewLifecycleOwner.lifecycleScope.launch {
                        ShortcutImporter.delete(entry.id)
                    }
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
