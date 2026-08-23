package com.winfex.ui.prefixes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.winfex.R
import com.winfex.core.AudioService
import com.winfex.core.DXWrapperInstaller
import com.winfex.core.RatPackageManager
import com.winfex.core.WinePrefixManager
import com.winfex.core.WineRunnerService
import com.winfex.core.WineWrapper
import com.winfex.core.XServerManager
import com.winfex.databinding.FragmentPrefixesBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PrefixesFragment : Fragment() {

    private var _b: FragmentPrefixesBinding? = null
    private val b get() = _b!!

    private val adapter = PrefixAdapter(
        onRun = { runPrefix(it) },
        onInit = { initPrefix(it) },
        onMore = { showMoreMenu(it) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentPrefixesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.refresh.setOnRefreshListener {
            viewLifecycleOwner.lifecycleScope.launch {
                WinePrefixManager.loadAll()
                b.refresh.isRefreshing = false
            }
        }

        b.fabAdd.setOnClickListener { showCreateDialog() }

        // X Server 状态条
        b.btnXserverToggle.setOnClickListener {
            when (XServerManager.state.value) {
                XServerManager.State.READY -> {
                    XServerManager.stop()
                    Snackbar.make(b.root, "X Server 已停止", 1500).show()
                }
                XServerManager.State.STOPPED,
                XServerManager.State.FAILED -> {
                    Snackbar.make(b.root, "正在启动 X Server...", 1000).show()
                    viewLifecycleOwner.lifecycleScope.launch {
                        val ok = XServerManager.start(requireContext(),
                            XServerManager.StartMode.AUTO)
                        if (ok) {
                            Snackbar.make(b.root,
                                "X Server 已就绪 ${XServerManager.displayString()}",
                                2000).show()
                        } else {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("X Server 启动失败")
                                .setMessage("""
                                    可能原因：
                                    1. xserver module 未集成 — 跑 scripts/sync-xserver.sh
                                    2. Wine/Box64 包未选中 — 去「包」tab 选中
                                    3. socket 冲突 — 重启应用

                                    日志: ${com.winfex.core.WinfexPaths.logsDir.absolutePath}
                                """.trimIndent())
                                .setPositiveButton(R.string.ok, null)
                                .show()
                        }
                    }
                }
                XServerManager.State.STARTING -> {
                    Snackbar.make(b.root, "X Server 正在启动，请稍等", 1500).show()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            WinePrefixManager.prefixes.collectLatest { list ->
                adapter.submitList(list)
                b.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            XServerManager.state.collectLatest { state ->
                updateXServerStatus(state)
            }
        }
    }

    private fun updateXServerStatus(state: XServerManager.State) {
        val (dotColor, text, btnText) = when (state) {
            XServerManager.State.STOPPED -> Triple(
                R.color.status_err, "未启动 · ${XServerManager.displayString()}", "启动")
            XServerManager.State.STARTING -> Triple(
                R.color.status_warn, "启动中... · ${XServerManager.displayString()}", "...")
            XServerManager.State.READY -> Triple(
                R.color.status_ok, "运行中 · ${XServerManager.displayString()}", "停止")
            XServerManager.State.FAILED -> Triple(
                R.color.status_err, "启动失败 · ${XServerManager.displayString()}", "重试")
        }
        b.dotXserver.setBackgroundColor(
            android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(requireContext(), dotColor)
            ).defaultColor
        )
        b.tvXserverStatus.text = text
        b.btnXserverToggle.text = btnText
    }

    private fun showCreateDialog() {
        val dlg = PrefixEditDialog.newInstance(null)
        dlg.setOnSave { cfg ->
            viewLifecycleOwner.lifecycleScope.launch {
                WinePrefixManager.create(cfg.name, template = cfg)
                Snackbar.make(b.root, "已创建：${cfg.name}（首次启动会自动初始化）", 3000).show()
            }
        }
        dlg.show(parentFragmentManager, "create")
    }

    private fun showMoreMenu(cfg: com.winfex.model.WinePrefix) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(cfg.name)
            .setItems(arrayOf("编辑", "克隆", "删除", "Wine 配置", "注册表")) { _, which ->
                when (which) {
                    0 -> showEditDialog(cfg)
                    1 -> viewLifecycleOwner.lifecycleScope.launch {
                        val cloned = WinePrefixManager.clone(cfg.id, "${cfg.name} (副本)")
                        Snackbar.make(b.root, "已克隆：${cloned.name}", 2000).show()
                    }
                    2 -> confirmDelete(cfg)
                    3 -> viewLifecycleOwner.lifecycleScope.launch {
                        ensurePackagesOrWarn() ?: return@launch
                        try {
                            val pgid = WineWrapper.runWinecfg(cfg) { }
                            WineRunnerService.start(requireContext(), pgid, "winecfg:${cfg.name}")
                        } catch (e: Exception) {
                            Snackbar.make(b.root, "启动失败：${e.message}", 4000).show()
                        }
                    }
                    4 -> viewLifecycleOwner.lifecycleScope.launch {
                        ensurePackagesOrWarn() ?: return@launch
                        try {
                            val pgid = WineWrapper.runRegedit(cfg) { }
                            WineRunnerService.start(requireContext(), pgid, "regedit:${cfg.name}")
                        } catch (e: Exception) {
                            Snackbar.make(b.root, "启动失败：${e.message}", 4000).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun showEditDialog(cfg: com.winfex.model.WinePrefix) {
        val dlg = PrefixEditDialog.newInstance(cfg.id)
        dlg.setOnSave { updated ->
            viewLifecycleOwner.lifecycleScope.launch {
                WinePrefixManager.update(updated)
                Snackbar.make(b.root, "已保存", 1500).show()
            }
        }
        dlg.show(parentFragmentManager, "edit")
    }

    private fun confirmDelete(cfg: com.winfex.model.WinePrefix) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(cfg.name)
            .setMessage(R.string.prefix_confirm_delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    WinePrefixManager.delete(cfg.id)
                    Snackbar.make(b.root, "已删除：${cfg.name}", 2000).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun ensurePackagesOrWarn(): Unit? {
        val missing = RatPackageManager.missingCategories()
        if (missing.isEmpty()) return Unit
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.native_missing_title)
            .setMessage(getString(R.string.native_missing_message,
                missing.joinToString("\n\n") { "• $it" }))
            .setPositiveButton(R.string.ok, null)
            .show()
        return null
    }

    private fun initPrefix(cfg: com.winfex.model.WinePrefix) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (ensurePackagesOrWarn() == null) return@launch
            try {
                // 先装 DX 包装器（wineboot 之前可能没 system32 目录，会跳过）
                DXWrapperInstaller.install(cfg)
                // 启动 PulseAudio（如果可用）
                AudioService.start(cfg) { }
                val pgid = WineWrapper.initPrefix(cfg) { }
                WineRunnerService.start(requireContext(), pgid, "init:${cfg.name}")
                Snackbar.make(b.root, "初始化中 (pid=$pgid)，请等待 10-60 秒", 4000).show()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("初始化失败")
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    private fun runPrefix(cfg: com.winfex.model.WinePrefix) {
        viewLifecycleOwner.lifecycleScope.launch {
            // 检查 ImageFS 是否已安装
            if (!com.winfex.core.ImageFsInstaller.isInstalled()) {
                Snackbar.make(b.root, "ImageFS 正在安装中，请稍等...", 3000).show()
                return@launch
            }
            try {
                // 同时启动 X Server + Wine 容器
                // X Server 在 Activity 内运行，Wine 通过 ProcessBuilder 启动
                val xReady = com.winfex.core.XServerManager.state.value == com.winfex.core.XServerManager.State.READY
                if (!xReady) {
                    Snackbar.make(b.root, "正在启动 X Server...", 2000).show()
                    val xOk = com.winfex.core.XServerManager.start(requireContext(),
                        com.winfex.core.XServerManager.StartMode.AUTO)
                    if (!xOk) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("X Server 启动失败")
                            .setMessage("""
                                可能原因：
                                1. xserver module 未集成 — 跑 scripts/sync-xserver.sh
                                2. libX11.so / libXtst.so 不在 imagefs/usr/lib/
                                3. socket 冲突 — 重启应用

                                日志: ${com.winfex.core.WinfexPaths.logsDir.absolutePath}
                            """.trimIndent())
                            .setPositiveButton(R.string.ok, null)
                            .show()
                        return@launch
                    }
                }

                // 安装 DXVK DLL + 启动 PulseAudio
                com.winfex.core.DXWrapperInstaller.install(cfg)
                com.winfex.core.AudioService.start(cfg) { }

                // 启动 Wine
                // ARM64EC + FEX DLL 模式（无 root，性能最优）
                // 如果 FEX DLL 不存在，自动回退到 Box64 模式
                val pgid = com.winfex.core.WineWrapper.launch(
                    com.winfex.core.WineWrapper.LaunchParams(
                        prefix = cfg,
                        exePath = "explorer",
                        arguments = "/desktop=shell"
                    )
                ) { }
                com.winfex.core.WineRunnerService.start(requireContext(), pgid, cfg.name)
                Snackbar.make(b.root, "已启动：${cfg.name} (pid=$pgid)", 3000).show()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("启动失败")
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
