package com.winfex.ui.input

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.winfex.R
import com.winfex.core.InputController
import com.winfex.model.Binding
import com.winfex.model.Element
import com.winfex.model.ElementType
import com.winfex.model.Shape
import com.winfex.model.XKeycode
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 输入布局可视化编辑器 —— 参考 Winlator ControlsEditorActivity。
 *
 * 功能：
 *   - 全屏显示 InputOverlayView + 编辑网格
 *   - 顶部工具栏：添加元素、保存、删除当前选中
 *   - 点击元素 → 选中 → 弹属性面板（绑定、形状、缩放、toggle）
 *   - 拖动元素 → 改 x/y（吸附到 1/24 网格）
 *
 * 这是 v0.4 的简化版，属性面板用对话框形式（Winlator 是侧边栏）。
 * v0.5 可以升级为侧边栏 + 实时预览。
 */
class ControlsEditorActivity : AppCompatActivity() {

    private lateinit var overlay: InputOverlayView
    private var profile: com.winfex.model.InputProfile? = null
    private var selectedElementId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profileId = intent.getStringExtra(EXTRA_ID)
        profile = profileId?.let { id ->
            InputController.profiles.value.firstOrNull { it.id == id }
        } ?: InputController.createDefault("New Profile").also { p ->
            lifecycleScope.launch { InputController.save(p) }
        }

        title = "编辑: ${profile?.name}"
        setContentView(buildUI())

        overlay.setProfile(profile)
        overlay.setEditing(true)
    }

    private fun buildUI(): View {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 叠加层
        overlay = InputOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnElementTappedListener { id ->
                selectedElementId = id
                showEditElementDialog(id)
            }
            setOnBackgroundTappedListener { pos ->
                selectedElementId = null
                showAddElementDialog(pos.first, pos.second)
            }
            setOnElementMovedListener { id, x, y ->
                updateElementPosition(id, x, y)
            }
        }
        root.addView(overlay)

        // 顶部工具栏
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC151821.toInt())
            setPadding(24, 24, 24, 24)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val btnAdd = MaterialButton(this).apply {
            text = "+ 元素"
            setOnClickListener { showAddElementDialog(0.5f, 0.5f) }
        }
        val btnEdit = MaterialButton(this).apply {
            text = "编辑选中"
            setOnClickListener {
                val id = selectedElementId ?: run {
                    android.widget.Toast.makeText(this@ControlsEditorActivity,
                        "先点选一个元素", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                showEditElementDialog(id)
            }
        }
        val btnDelete = MaterialButton(this).apply {
            text = "删除选中"
        }
        btnDelete.setOnClickListener {
            val id = selectedElementId ?: return@setOnClickListener
            val current = profile ?: return@setOnClickListener
            profile = current.copy(elements = current.elements.filterNot { it.id == id })
            overlay.setProfile(profile)
            selectedElementId = null
        }
        val btnSave = MaterialButton(this).apply {
            text = "保存"
            setOnClickListener {
                lifecycleScope.launch {
                    profile?.let { p ->
                        InputController.save(p.copy(updatedAt = System.currentTimeMillis()))
                    }
                    android.widget.Toast.makeText(this@ControlsEditorActivity,
                        "已保存", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        toolbar.addView(btnAdd)
        toolbar.addView(btnEdit.apply { layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 12 } })
        toolbar.addView(btnDelete.apply { layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 12 } })
        toolbar.addView(btnSave.apply { layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 12 } })

        root.addView(toolbar)

        // 底部提示
        val hint = TextView(this).apply {
            text = "点击空白处添加元素 · 点击元素编辑 · 长按拖动移动"
            setTextColor(0xA2A8B5.toInt())
            textSize = 12f
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xAA151821.toInt())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }
        root.addView(hint)

        return root
    }

    private fun showAddElementDialog(relX: Float, relY: Float) {
        val types = arrayOf("按钮 (BUTTON)", "方向键 (DPAD)", "摇杆 (STICK)", "触摸板 (TRACKPAD)")
        MaterialAlertDialogBuilder(this)
            .setTitle("添加元素 @ (${(relX*100).toInt()}%, ${(relY*100).toInt()}%)")
            .setItems(types) { _, which ->
                val type = when (which) {
                    0 -> ElementType.BUTTON
                    1 -> ElementType.DPAD
                    2 -> ElementType.STICK
                    3 -> ElementType.TRACKPAD
                    else -> ElementType.BUTTON
                }
                val newElement = Element(
                    id = UUID.randomUUID().toString().take(6),
                    type = type,
                    x = relX, y = relY,
                    text = when (type) {
                        ElementType.BUTTON -> "B"
                        ElementType.DPAD -> "D"
                        ElementType.STICK -> "L"
                        ElementType.TRACKPAD -> "T"
                        else -> ""
                    },
                    bindings = when (type) {
                        ElementType.BUTTON -> listOf(Binding.key(XKeycode.KEY_SPACE))
                        ElementType.DPAD -> listOf(
                            Binding.key(XKeycode.KEY_W),
                            Binding.key(XKeycode.KEY_D),
                            Binding.key(XKeycode.KEY_S),
                            Binding.key(XKeycode.KEY_A)
                        )
                        ElementType.STICK, ElementType.TRACKPAD -> listOf(
                            Binding.move(com.winfex.model.Direction.UP),
                            Binding.move(com.winfex.model.Direction.RIGHT),
                            Binding.move(com.winfex.model.Direction.DOWN),
                            Binding.move(com.winfex.model.Direction.LEFT)
                        )
                        else -> emptyList()
                    }
                )
                profile = profile?.copy(elements = profile!!.elements + newElement)
                overlay.setProfile(profile)
                selectedElementId = newElement.id
            }
            .show()
    }

    private fun showEditElementDialog(elementId: String) {
        val element = profile?.elements?.firstOrNull { it.id == elementId } ?: return

        val items = arrayOf(
            "绑定 1: ${formatBinding(element.bindings.getOrNull(0))}",
            "绑定 2: ${formatBinding(element.bindings.getOrNull(1))}",
            "绑定 3: ${formatBinding(element.bindings.getOrNull(2))}",
            "绑定 4: ${formatBinding(element.bindings.getOrNull(3))}",
            "形状: ${element.shape}",
            "缩放: ${element.scale}x",
            "切换开关: ${if (element.toggleSwitch) "开" else "关"}",
            "文本: ${element.text}"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("编辑元素")
            .setItems(items) { _, which ->
                when (which) {
                    in 0..3 -> showBindingPickerDialog(element, which)
                    4 -> showShapePickerDialog(element)
                    5 -> showScaleDialog(element)
                    6 -> {
                        val updated = element.copy(toggleSwitch = !element.toggleSwitch)
                        updateElement(updated)
                    }
                    7 -> showTextDialog(element)
                }
            }
            .show()
    }

    private fun formatBinding(b: Binding?): String {
        if (b == null || b.tag == Binding.TAG_NONE) return "（无）"
        return when (b.tag) {
            Binding.TAG_KEY -> "KEY #$b.value"
            Binding.TAG_MOUSE -> "MOUSE #$b.value"
            Binding.TAG_MOVE -> "MOVE ${com.winfex.model.Direction.entries.getOrNull(b.value)}"
            Binding.TAG_GAMEPAD -> "GAMEPAD"
            else -> "？"
        }
    }

    private fun showBindingPickerDialog(element: Element, slot: Int) {
        val categories = arrayOf("键盘按键", "鼠标按键", "鼠标移动", "清除")
        MaterialAlertDialogBuilder(this)
            .setTitle("绑定槽 ${slot + 1}")
            .setItems(categories) { _, which ->
                when (which) {
                    0 -> showKeyPickerDialog(element, slot)
                    1 -> showMouseButtonPickerDialog(element, slot)
                    2 -> showMouseMovePickerDialog(element, slot)
                    3 -> {
                        val newBindings = element.bindings.toMutableList()
                        while (newBindings.size <= slot) newBindings.add(Binding())
                        newBindings[slot] = Binding()
                        updateElement(element.copy(bindings = newBindings))
                    }
                }
            }
            .show()
    }

    private fun showKeyPickerDialog(element: Element, slot: Int) {
        // 简化：列出常用键
        val keys = listOf(
            "W" to XKeycode.KEY_W, "A" to XKeycode.KEY_A, "S" to XKeycode.KEY_S, "D" to XKeycode.KEY_D,
            "Space" to XKeycode.KEY_SPACE, "Enter" to XKeycode.KEY_ENTER, "ESC" to XKeycode.KEY_ESC,
            "Shift" to XKeycode.KEY_SHIFT_L, "Ctrl" to XKeycode.KEY_CTRL_L, "Alt" to XKeycode.KEY_ALT_L,
            "Tab" to XKeycode.KEY_TAB, "E" to XKeycode.KEY_E, "Q" to XKeycode.KEY_Q,
            "R" to XKeycode.KEY_R, "F" to XKeycode.KEY_F, "1-6" to XKeycode.KEY_1,
            "F1-F4" to XKeycode.KEY_F1, "Up" to XKeycode.KEY_UP, "Down" to XKeycode.KEY_DOWN,
            "Left" to XKeycode.KEY_LEFT, "Right" to XKeycode.KEY_RIGHT
        )
        val labels = keys.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("选择按键")
            .setItems(labels) { _, which ->
                val newBindings = element.bindings.toMutableList()
                while (newBindings.size <= slot) newBindings.add(Binding())
                newBindings[slot] = Binding.key(keys[which].second)
                updateElement(element.copy(bindings = newBindings))
            }
            .show()
    }

    private fun showMouseButtonPickerDialog(element: Element, slot: Int) {
        val buttons = arrayOf("左键", "中键", "右键", "滚轮上", "滚轮下")
        val codes = intArrayOf(1, 2, 3, 4, 5)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择鼠标按键")
            .setItems(buttons) { _, which ->
                val newBindings = element.bindings.toMutableList()
                while (newBindings.size <= slot) newBindings.add(Binding())
                newBindings[slot] = Binding.mouse(codes[which])
                updateElement(element.copy(bindings = newBindings))
            }
            .show()
    }

    private fun showMouseMovePickerDialog(element: Element, slot: Int) {
        val dirs = arrayOf("上", "右", "下", "左")
        val values = com.winfex.model.Direction.entries
        MaterialAlertDialogBuilder(this)
            .setTitle("选择方向")
            .setItems(dirs) { _, which ->
                val newBindings = element.bindings.toMutableList()
                while (newBindings.size <= slot) newBindings.add(Binding())
                newBindings[slot] = Binding.move(values[which])
                updateElement(element.copy(bindings = newBindings))
            }
            .show()
    }

    private fun showShapePickerDialog(element: Element) {
        val shapes = Shape.entries.toTypedArray()
        val labels = shapes.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("选择形状")
            .setItems(labels) { _, which ->
                updateElement(element.copy(shape = shapes[which]))
            }
            .show()
    }

    private fun showScaleDialog(element: Element) {
        val scales = arrayOf("0.5x", "0.7x", "1.0x", "1.3x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.7f, 1.0f, 1.3f, 1.5f, 2.0f)
        MaterialAlertDialogBuilder(this)
            .setTitle("缩放")
            .setItems(scales) { _, which ->
                updateElement(element.copy(scale = values[which]))
            }
            .show()
    }

    private fun showTextDialog(element: Element) {
        val edit = android.widget.EditText(this).apply {
            setText(element.text)
            setSelection(element.text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("显示文字")
            .setView(edit)
            .setPositiveButton(R.string.save) { _, _ ->
                updateElement(element.copy(text = edit.text.toString()))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateElementPosition(elementId: String, x: Float, y: Float) {
        profile = profile?.copy(
            elements = profile!!.elements.map { el ->
                if (el.id == elementId) el.copy(x = x, y = y) else el
            }
        )
        // 不调 overlay.setProfile，避免重置触摸状态；让 overlay 内部更新
    }

    private fun updateElement(updated: Element) {
        profile = profile?.copy(
            elements = profile!!.elements.map { if (it.id == updated.id) updated else it }
        )
        overlay.setProfile(profile)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 离开时自动保存
        lifecycleScope.launch {
            profile?.let { p ->
                InputController.save(p.copy(updatedAt = System.currentTimeMillis()))
            }
        }
    }

    companion object {
        const val EXTRA_ID = "winfex.input.profile_id"

        fun start(context: Context, profileId: String) {
            context.startActivity(android.content.Intent(context, ControlsEditorActivity::class.java).apply {
                putExtra(EXTRA_ID, profileId)
            })
        }
    }
}
