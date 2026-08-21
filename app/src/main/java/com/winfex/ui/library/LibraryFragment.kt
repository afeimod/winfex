package com.winfex.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.winfex.R
import com.winfex.core.AudioService
import com.winfex.core.DXWrapperInstaller
import com.winfex.core.GameLibrary
import com.winfex.core.RatPackageManager
import com.winfex.core.WinePrefixManager
import com.winfex.core.WineRunnerService
import com.winfex.core.WineWrapper
import com.winfex.databinding.FragmentLibraryBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!

    private val adapter = GameAdapter(
        onPlay = { launchGame(it) },
        onLongClick = { showItemMenu(it) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentLibraryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.refresh.setOnRefreshListener { refreshAll() }
        b.fabScan.setOnClickListener { scanAll() }

        viewLifecycleOwner.lifecycleScope.launch {
            GameLibrary.games.collectLatest { list ->
                adapter.submitList(list)
                b.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                b.refresh.isRefreshing = false
            }
        }
    }

    private fun refreshAll() {
        viewLifecycleOwner.lifecycleScope.launch {
            val prefixes = WinePrefixManager.prefixes.value
            for (p in prefixes) {
                GameLibrary.scanPrefix(p.id)
            }
            b.refresh.isRefreshing = false
        }
    }

    private fun scanAll() {
        viewLifecycleOwner.lifecycleScope.launch {
            b.refresh.isRefreshing = true
            val prefixes = WinePrefixManager.prefixes.value
            if (prefixes.isEmpty()) {
                com.google.android.material.snackbar.Snackbar
                    .make(b.root, "请先在「前缀」标签页创建一个前缀", 4000)
                    .show()
                b.refresh.isRefreshing = false
                return@launch
            }
            for (p in prefixes) {
                GameLibrary.scanPrefix(p.id)
            }
            com.google.android.material.snackbar.Snackbar
                .make(b.root, "扫描完成", 2000).show()
        }
    }

    private fun launchGame(item: com.winfex.model.GameItem) {
        val prefix = WinePrefixManager.get(item.prefixId) ?: run {
            com.google.android.material.snackbar.Snackbar
                .make(b.root, "前缀不存在", 3000).show()
            return
        }
        if (RatPackageManager.missingCategories().isNotEmpty()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.native_missing_title)
                .setMessage(getString(R.string.native_missing_message,
                    RatPackageManager.missingCategories().joinToString("\n\n") { "• $it" }))
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 启动游戏前重新安装 DX 包装器（保证最新选中版本）
                DXWrapperInstaller.install(prefix)
                AudioService.start(prefix) { }
                val pgid = WineWrapper.launch(
                    WineWrapper.LaunchParams(
                        prefix = prefix,
                        exePath = item.exePath,
                        arguments = item.arguments
                    )
                ) { }
                WineRunnerService.start(requireContext(), pgid, item.name)
                GameLibrary.markPlayed(item.id)
                com.google.android.material.snackbar.Snackbar
                    .make(b.root, "已启动：${item.name} (pid=$pgid)", 3000).show()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("启动失败")
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showItemMenu(item: com.winfex.model.GameItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.name)
            .setItems(arrayOf("启动", "从库中移除")) { _, which ->
                when (which) {
                    0 -> launchGame(item)
                    1 -> viewLifecycleOwner.lifecycleScope.launch {
                        GameLibrary.remove(item.id)
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
