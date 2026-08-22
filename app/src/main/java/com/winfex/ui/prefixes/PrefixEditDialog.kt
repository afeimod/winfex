package com.winfex.ui.prefixes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.app.Dialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.winfex.R
import com.winfex.core.WinePrefixManager
import com.winfex.databinding.DialogPrefixEditBinding
import com.winfex.model.WinePrefix

/**
 * 创建 / 编辑 Wine 前缀对话框。
 */
class PrefixEditDialog : DialogFragment() {

    private var existingId: String? = null
    private var existing: WinePrefix? = null
    private var onSave: ((WinePrefix) -> Unit)? = null

    private var _b: DialogPrefixEditBinding? = null
    private val b get() = _b!!

    fun setOnSave(cb: (WinePrefix) -> Unit) { onSave = cb }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        existingId = arguments?.getString(ARG_ID)
        existing = existingId?.let { WinePrefixManager.get(it) }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _b = DialogPrefixEditBinding.inflate(layoutInflater)
        setupDropdowns()
        existing?.let { populate(it) }
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.prefix_create else R.string.edit)
            .setView(b.root)
            .setPositiveButton(R.string.save) { _, _ -> save() }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    private fun setupDropdowns() {
        // Windows 版本
        b.actvWinver.setAdapter(android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1,
            arrayOf("win10", "win7", "winxp", "win98", "win2003", "win81")
        ))
        b.actvWinver.setText("win10", false)

        // D3D 渲染器
        b.actvRenderer.setAdapter(android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1,
            arrayOf("DXVK", "WineD3D")
        ))
        b.actvRenderer.setText("DXVK", false)

        // 音频后端
        b.actvAudio.setAdapter(android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1,
            arrayOf("SLES", "AAudio")
        ))
        b.actvAudio.setText("SLES", false)

        // VKD3D 特性级
        b.actvVkd3d.setAdapter(android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1,
            arrayOf("12_0", "12_1", "12_2")
        ))
        b.actvVkd3d.setText("12_0", false)

        // TU_DEBUG 预设
        b.actvTuDebug.setAdapter(android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1,
            arrayOf("", "nir", "ir3", "conform", "flushall", "sysmem", "gmem")
        ))
        b.actvTuDebug.setText("", false)

        // Box64 log
        b.actvBox64Log.setAdapter(android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1,
            arrayOf("0", "1", "2", "3")
        ))
        b.actvBox64Log.setText("0", false)

        // 分辨率
        b.actvResolution.setAdapter(android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1,
            arrayOf("1280x720", "1920x1080", "2560x1440", "")
        ))
        b.actvResolution.setText("1920x1080", false)
    }

    private fun populate(cfg: WinePrefix) {
        b.etName.setText(cfg.name)
        b.actvWinver.setText(cfg.windowsVersion, false)
        b.actvRenderer.setText(cfg.d3dxRenderer, false)
        b.actvAudio.setText(cfg.audioSink, false)
        b.actvVkd3d.setText(cfg.vkd3dFeatureLevel, false)
        b.actvTuDebug.setText(cfg.tuDebugPreset, false)
        b.actvBox64Log.setText(cfg.box64Log, false)
        b.actvResolution.setText(cfg.desktopResolution ?: "", false)
        b.etCpuMask.setText(if (cfg.cpuAffinityMask == 0L) "" else cfg.cpuAffinityMask.toString(16))
        b.etPulseLatency.setText(cfg.pulseLatencyMs.toString())
        b.swEsync.isChecked = cfg.esync
        b.swFsync.isChecked = cfg.fsync
        b.swMangoHud.isChecked = cfg.mangoHud
    }

    private fun save() {
        val name = b.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            android.widget.Toast.makeText(requireContext(),
                "请输入前缀名称", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val cpuMask = b.etCpuMask.text?.toString()?.trim().let {
            if (it.isNullOrEmpty()) 0L else it.toLong(16)
        }
        val pulseLatency = b.etPulseLatency.text?.toString()?.trim()?.toIntOrNull() ?: 60

        val now = System.currentTimeMillis()
        val cfg = (existing ?: WinePrefix(
            id = java.util.UUID.randomUUID().toString().take(8),
            name = name,
            createdAt = now,
            updatedAt = now
        )).copy(
            name = name,
            windowsVersion = b.actvWinver.text.toString(),
            d3dxRenderer = b.actvRenderer.text.toString(),
            audioSink = b.actvAudio.text.toString(),
            vkd3dFeatureLevel = b.actvVkd3d.text.toString(),
            tuDebugPreset = b.actvTuDebug.text.toString(),
            box64Log = b.actvBox64Log.text.toString(),
            desktopResolution = b.actvResolution.text?.toString()?.trim()?.ifEmpty { null },
            cpuAffinityMask = cpuMask,
            pulseLatencyMs = pulseLatency,
            esync = b.swEsync.isChecked,
            fsync = b.swFsync.isChecked,
            mangoHud = b.swMangoHud.isChecked,
            updatedAt = now
        )
        onSave?.invoke(cfg)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val ARG_ID = "id"
        fun newInstance(existingId: String?): PrefixEditDialog {
            return PrefixEditDialog().apply {
                arguments = Bundle().apply {
                    if (existingId != null) putString(ARG_ID, existingId)
                }
            }
        }
    }
}
