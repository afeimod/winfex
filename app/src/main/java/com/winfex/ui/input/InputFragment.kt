package com.winfex.ui.input

import android.content.Intent
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
import com.winfex.core.InputController
import com.winfex.databinding.FragmentInputBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InputFragment : Fragment() {

    private var _b: FragmentInputBinding? = null
    private val b get() = _b!!

    private val adapter = InputProfileAdapter(
        onActiveChange = { profile, isActive ->
            if (isActive) {
                InputController.setActive(profile)
                // 检查 overlay 权限
                if (!com.winfex.core.InputOverlayHost.canDrawOverlays(requireContext())) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("需要悬浮窗权限")
                        .setMessage("虚拟按键需要 SYSTEM_ALERT_WINDOW 权限才能显示在游戏画面之上。\n\n点击确定跳转到系统设置授权。")
                        .setPositiveButton(R.string.ok) { _, _ ->
                            com.winfex.core.InputOverlayHost.requestOverlayPermission(requireContext())
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    return@InputProfileAdapter
                }
                com.winfex.core.InputOverlayHost.setProfile(profile)
                com.winfex.core.InputOverlayHost.show(requireContext())
                Snackbar.make(b.root, "已激活：${profile.name}（叠加层已显示）", 2000).show()
            } else {
                InputController.setActive(null)
                com.winfex.core.InputOverlayHost.hide()
                Snackbar.make(b.root, "已停用", 1500).show()
            }
        },
        onLongClick = { showMenu(it) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentInputBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.fabAdd.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val p = InputController.createDefault("Profile-${System.currentTimeMillis() % 1000}")
                InputController.save(p)
                startActivity(Intent(requireContext(), ControlsEditorActivity::class.java).apply {
                    putExtra(ControlsEditorActivity.EXTRA_ID, p.id)
                })
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            InputController.profiles.collectLatest { list ->
                adapter.submitList(list)
                b.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showMenu(profile: com.winfex.model.InputProfile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(profile.name)
            .setItems(arrayOf("编辑布局", "复制", "重命名", "删除")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(requireContext(), ControlsEditorActivity::class.java).apply {
                        putExtra(ControlsEditorActivity.EXTRA_ID, profile.id)
                    })
                    1 -> viewLifecycleOwner.lifecycleScope.launch {
                        val copy = profile.copy(
                            id = java.util.UUID.randomUUID().toString().take(8),
                            name = "${profile.name} (副本)",
                            createdAt = System.currentTimeMillis()
                        )
                        InputController.save(copy)
                        Snackbar.make(b.root, "已复制", 1500).show()
                    }
                    2 -> showRenameDialog(profile)
                    3 -> MaterialAlertDialogBuilder(requireContext())
                        .setTitle(profile.name)
                        .setMessage("确定删除这个方案吗？")
                        .setPositiveButton(R.string.delete) { _, _ ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                InputController.delete(profile.id)
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun showRenameDialog(profile: com.winfex.model.InputProfile) {
        val edit = android.widget.EditText(requireContext()).apply {
            setText(profile.name)
            setSelection(profile.name.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("重命名")
            .setView(edit)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = edit.text.toString().trim()
                if (newName.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        InputController.save(profile.copy(name = newName, updatedAt = System.currentTimeMillis()))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
