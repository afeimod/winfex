package com.winfex.core

import android.view.KeyEvent
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.winfex.input.XTestInjector
import com.winfex.model.Binding
import com.winfex.model.Direction
import com.winfex.model.Element
import com.winfex.model.ElementType
import com.winfex.model.InputProfile
import com.winfex.model.XKeycode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 输入配置仓库 + 运行时按键事件分发器。
 *
 * v0.4 改造：
 *   - profile 模型改为 Winlator 风格的 elements 列表
 *   - dispatchKey 不再只打 log，改为调 XTestInjector.injectKey
 *   - 新增 dispatchBinding 处理 Binding（KEY/MOUSE/MOVE/GAMEPAD）
 *   - 新增 MouseMoveTimer 用于持续移动（绑定 MOVE 的元素被按下时启动）
 */
object InputController {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(InputProfile::class.java)

    private val _profiles = MutableStateFlow<List<InputProfile>>(emptyList())
    val profiles: StateFlow<List<InputProfile>> = _profiles.asStateFlow()

    private var _active: InputProfile? = null
    val active: InputProfile? get() = _active

    /** 鼠标移动定时器（每 16ms 触发一次，约 60Hz） */
    private val moveTimer = java.util.Timer("winfex-mouse-move", isDaemon = true)
    private val activeMoves = java.util.concurrent.ConcurrentHashMap<String, Pair<Float, Float>>()

    init {
        // 启动持续移动定时器
        moveTimer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                if (activeMoves.isEmpty()) return
                val speed = _active?.cursorSpeed ?: 1.0f
                var totalDx = 0f
                var totalDy = 0f
                for ((_, v) in activeMoves) {
                    totalDx += v.first
                    totalDy += v.second
                }
                if (totalDx != 0f || totalDy != 0f) {
                    XTestInjector.injectMouseMoveRelative(
                        (totalDx * speed * 8f).toInt(),
                        (totalDy * speed * 8f).toInt()
                    )
                }
            }
        }, 0, 16)
    }

    suspend fun loadAll() = withContext(Dispatchers.IO) {
        val dir = WinfexPaths.inputDir
        val list = dir.listFiles { f -> f.extension == "json" }?.mapNotNull { f ->
            try { adapter.fromJson(f.readText()) } catch (_: Exception) { null }
        } ?: emptyList()
        _profiles.value = list

        // 首次启动：从 assets/preset_profiles/ 导入预置方案
        if (list.isEmpty()) {
            importPresetProfiles()
        }
    }

    /**
     * 从 assets/preset_profiles/ 导入所有预置 profile。
     * 仅在用户没有任何 profile 时调用。
     */
    private suspend fun importPresetProfiles() = withContext(Dispatchers.IO) {
        val assetList = try {
            WinfexPaths.appContext.assets.list("preset_profiles") ?: emptyArray()
        } catch (_: Exception) { emptyArray<String>() }

        val imported = mutableListOf<InputProfile>()
        for (name in assetList) {
            if (!name.endsWith(".json")) continue
            try {
                val text = WinfexPaths.appContext.assets.open("preset_profiles/$name").bufferedReader().use { it.readText() }
                val p = adapter.fromJson(text) ?: continue
                val dir = WinfexPaths.inputDir.apply { mkdirs() }
                java.io.File(dir, "${p.id}.json").writeText(adapter.toJson(p))
                imported.add(p)
                android.util.Log.i(TAG, "imported preset: ${p.name}")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "import preset $name failed: ${e.message}")
            }
        }
        if (imported.isNotEmpty()) {
            _profiles.value = imported
        }
    }

    suspend fun save(profile: InputProfile) = withContext(Dispatchers.IO) {
        val dir = WinfexPaths.inputDir.apply { mkdirs() }
        val f = File(dir, "${profile.id}.json")
        f.writeText(adapter.toJson(profile))
        _profiles.value = _profiles.value.filterNot { it.id == profile.id } + profile
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val f = File(WinfexPaths.inputDir, "$id.json")
        if (f.exists()) f.delete()
        _profiles.value = _profiles.value.filterNot { it.id == id }
        if (_active?.id == id) _active = null
    }

    fun setActive(profile: InputProfile?) {
        _active = profile
        // 切换 profile 时确保 XTest 连接
        if (profile != null) {
            XTestInjector.connect()
        } else {
            activeMoves.clear()
        }
    }

    /**
     * 由 InputOverlayView 调用：把触摸转化为按键事件。
     * v0.3 兼容方法：直接传 Android keyCode，内部转 X keycode。
     */
    fun dispatchKey(keyCode: Int, isDown: Boolean) {
        val xKeycode = androidKeyCodeToX(keyCode) ?: return
        XTestInjector.injectKey(xKeycode, isDown)
    }

    /**
     * 分发一个 Binding（元素被按下/松开时调）。
     * 根据 binding.tag 路由到不同的注入路径。
     */
    fun dispatchBinding(binding: Binding, isDown: Boolean, elementId: String) {
        when (binding.tag) {
            Binding.TAG_KEY -> {
                XTestInjector.injectKey(binding.value, isDown)
            }
            Binding.TAG_MOUSE -> {
                XTestInjector.injectMouseButton(binding.value, isDown)
            }
            Binding.TAG_MOVE -> {
                val dir = Direction.entries.getOrNull(binding.value) ?: return
                val delta = when (dir) {
                    Direction.UP -> 0f to -1f
                    Direction.RIGHT -> 1f to 0f
                    Direction.DOWN -> 0f to 1f
                    Direction.LEFT -> -1f to 0f
                }
                if (isDown) {
                    activeMoves[elementId + binding.tag + binding.value] = delta
                } else {
                    activeMoves.remove(elementId + binding.tag + binding.value)
                }
            }
            Binding.TAG_GAMEPAD -> {
                // v0.5+ 才接
                android.util.Log.d(TAG, "gamepad action (not implemented): slot=${binding.value shr 8} btn=${binding.value and 0xff} down=$isDown")
            }
            Binding.TAG_NONE -> { /* no-op */ }
        }
    }

    /**
     * 元素松开时清理所有相关 binding（避免漏掉松开事件）。
     */
    fun releaseAllBindings(element: Element) {
        for (binding in element.bindings) {
            if (binding.tag == Binding.TAG_MOVE) {
                activeMoves.remove(element.id + binding.tag + binding.value)
            } else if (binding.tag != Binding.TAG_NONE) {
                dispatchBinding(binding, isDown = false, elementId = element.id)
            }
        }
    }

    /**
     * 模拟鼠标移动（用于 TRACKPAD 元素）。
     */
    fun dispatchMouseMove(dx: Float, dy: Float) {
        val mult = _active?.cursorSpeed ?: 1.0f
        XTestInjector.injectMouseMoveRelative((dx * mult).toInt(), (dy * mult).toInt())
    }

    /**
     * 鼠标点击（用于 TRACKPAD 触发点击）。
     */
    fun dispatchMouseClick(button: Int = 1) {
        XTestInjector.injectMouseButton(button, true)
        try { Thread.sleep(20) } catch (_: InterruptedException) {}
        XTestInjector.injectMouseButton(button, false)
    }

    /**
     * Android KeyEvent.KEYCODE_* → X keycode。
     * 不全，只列常用的。完整映射在 MiceWine lorie.h: android_to_linux_keycode[304]。
     */
    private fun androidKeyCodeToX(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_A -> XKeycode.KEY_A
            KeyEvent.KEYCODE_B -> XKeycode.KEY_B
            KeyEvent.KEYCODE_C -> XKeycode.KEY_C
            KeyEvent.KEYCODE_D -> XKeycode.KEY_D
            KeyEvent.KEYCODE_E -> XKeycode.KEY_E
            KeyEvent.KEYCODE_F -> XKeycode.KEY_F
            KeyEvent.KEYCODE_G -> XKeycode.KEY_G
            KeyEvent.KEYCODE_H -> XKeycode.KEY_H
            KeyEvent.KEYCODE_I -> XKeycode.KEY_I
            KeyEvent.KEYCODE_J -> XKeycode.KEY_J
            KeyEvent.KEYCODE_K -> XKeycode.KEY_K
            KeyEvent.KEYCODE_L -> XKeycode.KEY_L
            KeyEvent.KEYCODE_M -> XKeycode.KEY_M
            KeyEvent.KEYCODE_N -> XKeycode.KEY_N
            KeyEvent.KEYCODE_O -> XKeycode.KEY_O
            KeyEvent.KEYCODE_P -> XKeycode.KEY_P
            KeyEvent.KEYCODE_Q -> XKeycode.KEY_Q
            KeyEvent.KEYCODE_R -> XKeycode.KEY_R
            KeyEvent.KEYCODE_S -> XKeycode.KEY_S
            KeyEvent.KEYCODE_T -> XKeycode.KEY_T
            KeyEvent.KEYCODE_U -> XKeycode.KEY_U
            KeyEvent.KEYCODE_V -> XKeycode.KEY_V
            KeyEvent.KEYCODE_W -> XKeycode.KEY_W
            KeyEvent.KEYCODE_X -> XKeycode.KEY_X
            KeyEvent.KEYCODE_Y -> XKeycode.KEY_Y
            KeyEvent.KEYCODE_Z -> XKeycode.KEY_Z
            KeyEvent.KEYCODE_0 -> XKeycode.KEY_0
            KeyEvent.KEYCODE_1 -> XKeycode.KEY_1
            KeyEvent.KEYCODE_2 -> XKeycode.KEY_2
            KeyEvent.KEYCODE_3 -> XKeycode.KEY_3
            KeyEvent.KEYCODE_4 -> XKeycode.KEY_4
            KeyEvent.KEYCODE_5 -> XKeycode.KEY_5
            KeyEvent.KEYCODE_6 -> XKeycode.KEY_6
            KeyEvent.KEYCODE_7 -> XKeycode.KEY_7
            KeyEvent.KEYCODE_8 -> XKeycode.KEY_8
            KeyEvent.KEYCODE_9 -> XKeycode.KEY_9
            KeyEvent.KEYCODE_F1 -> XKeycode.KEY_F1
            KeyEvent.KEYCODE_F2 -> XKeycode.KEY_F2
            KeyEvent.KEYCODE_F3 -> XKeycode.KEY_F3
            KeyEvent.KEYCODE_F4 -> XKeycode.KEY_F4
            KeyEvent.KEYCODE_F5 -> XKeycode.KEY_F5
            KeyEvent.KEYCODE_F6 -> XKeycode.KEY_F6
            KeyEvent.KEYCODE_F7 -> XKeycode.KEY_F7
            KeyEvent.KEYCODE_F8 -> XKeycode.KEY_F8
            KeyEvent.KEYCODE_F9 -> XKeycode.KEY_F9
            KeyEvent.KEYCODE_F10 -> XKeycode.KEY_F10
            KeyEvent.KEYCODE_F11 -> XKeycode.KEY_F11
            KeyEvent.KEYCODE_F12 -> XKeycode.KEY_F12
            KeyEvent.KEYCODE_SHIFT_LEFT -> XKeycode.KEY_SHIFT_L
            KeyEvent.KEYCODE_SHIFT_RIGHT -> XKeycode.KEY_SHIFT_R
            KeyEvent.KEYCODE_CTRL_LEFT -> XKeycode.KEY_CTRL_L
            KeyEvent.KEYCODE_CTRL_RIGHT -> XKeycode.KEY_CTRL_R
            KeyEvent.KEYCODE_ALT_LEFT -> XKeycode.KEY_ALT_L
            KeyEvent.KEYCODE_ALT_RIGHT -> XKeycode.KEY_ALT_R
            KeyEvent.KEYCODE_TAB -> XKeycode.KEY_TAB
            KeyEvent.KEYCODE_SPACE -> XKeycode.KEY_SPACE
            KeyEvent.KEYCODE_ENTER -> XKeycode.KEY_ENTER
            KeyEvent.KEYCODE_DEL -> XKeycode.KEY_BACKSPACE
            KeyEvent.KEYCODE_ESCAPE -> XKeycode.KEY_ESC
            KeyEvent.KEYCODE_DPAD_UP -> XKeycode.KEY_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> XKeycode.KEY_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> XKeycode.KEY_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> XKeycode.KEY_RIGHT
            else -> null
        }
    }

    fun createDefault(name: String = "Default"): InputProfile {
        return InputProfile(
            id = UUID.randomUUID().toString().take(8),
            name = name,
            createdAt = System.currentTimeMillis(),
            elements = listOf(
                // D-Pad 左下
                Element(
                    id = "dpad1",
                    type = ElementType.DPAD,
                    x = 0.15f, y = 0.72f,
                    bindings = listOf(
                        Binding.key(XKeycode.KEY_W),                            // 上
                        Binding.key(XKeycode.KEY_D),                            // 右
                        Binding.key(XKeycode.KEY_S),                            // 下
                        Binding.key(XKeycode.KEY_A)                             // 左
                    )
                ),
                // 4 个动作按钮右下
                Element(
                    id = "btn_a",
                    type = ElementType.BUTTON,
                    x = 0.85f, y = 0.72f,
                    text = "A",
                    bindings = listOf(Binding.key(XKeycode.KEY_SPACE))
                ),
                Element(
                    id = "btn_b",
                    type = ElementType.BUTTON,
                    x = 0.92f, y = 0.62f,
                    text = "B",
                    bindings = listOf(Binding.key(XKeycode.KEY_F))
                ),
                Element(
                    id = "btn_x",
                    type = ElementType.BUTTON,
                    x = 0.78f, y = 0.62f,
                    text = "X",
                    bindings = listOf(Binding.key(XKeycode.KEY_R))
                ),
                Element(
                    id = "btn_y",
                    type = ElementType.BUTTON,
                    x = 0.85f, y = 0.52f,
                    text = "Y",
                    bindings = listOf(Binding.key(XKeycode.KEY_E))
                ),
                // 鼠标左键（中间偏右）
                Element(
                    id = "btn_mouse_l",
                    type = ElementType.BUTTON,
                    x = 0.70f, y = 0.92f,
                    text = "LMB",
                    bindings = listOf(Binding.mouse(XKeycode.MOUSE_LEFT))
                ),
                // 鼠标右键
                Element(
                    id = "btn_mouse_r",
                    type = ElementType.BUTTON,
                    x = 0.82f, y = 0.92f,
                    text = "RMB",
                    bindings = listOf(Binding.mouse(XKeycode.MOUSE_RIGHT))
                ),
                // 鼠标移动（虚拟摇杆）
                Element(
                    id = "trackpad1",
                    type = ElementType.TRACKPAD,
                    x = 0.50f, y = 0.92f,
                    bindings = listOf(
                        Binding.move(Direction.UP),
                        Binding.move(Direction.RIGHT),
                        Binding.move(Direction.DOWN),
                        Binding.move(Direction.LEFT)
                    )
                ),
                // ESC
                Element(
                    id = "btn_esc",
                    type = ElementType.BUTTON,
                    x = 0.05f, y = 0.08f,
                    text = "ESC",
                    scale = 0.7f,
                    bindings = listOf(Binding.key(XKeycode.KEY_ESC))
                ),
                // Enter
                Element(
                    id = "btn_enter",
                    type = ElementType.BUTTON,
                    x = 0.12f, y = 0.08f,
                    text = "↵",
                    scale = 0.7f,
                    bindings = listOf(Binding.key(XKeycode.KEY_ENTER))
                )
            )
        )
    }

    private const val TAG = "InputController"
}
