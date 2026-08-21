package com.winfex.model

import com.squareup.moshi.JsonClass

/**
 * 输入控制方案 —— 参考 Winlator 的 .icp 格式重新设计。
 *
 * 一个 profile 包含若干 [Element]，每个 element 有 4 个 binding 槽：
 *   - BUTTON 元素：4 个同时触发的动作（如 Ctrl+C 一键）
 *   - DPAD / STICK 元素：上/右/下/左 4 个方向各一个 binding
 *   - TRACKPAD 元素：上/右/下/左 4 个鼠标移动方向
 *
 * 坐标系：相对屏幕，0..1。x=0 左边，y=0 顶部。
 *
 * 持久化在 /data/data/com.winfex/files/input/<id>.json
 */
@JsonClass(generateAdapter = true)
data class InputProfile(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val cursorSpeed: Float = 1.0f,
    val overlayOpacity: Float = 0.6f,
    val disableTouchpadOnButtons: Boolean = true,
    val elements: List<Element> = emptyList()
)

@JsonClass(generateAdapter = true)
data class Element(
    val id: String,
    val type: ElementType,
    val shape: Shape = Shape.CIRCLE,
    val x: Float,                // 0..1 屏幕相对坐标
    val y: Float,
    val scale: Float = 1.0f,
    val toggleSwitch: Boolean = false,
    val text: String = "",
    val bindings: List<Binding> = emptyList(),
    val deadZone: Float = 0.15f  // 仅 STICK
)

enum class ElementType {
    BUTTON,
    DPAD,
    STICK,
    TRACKPAD,
    RANGE_BUTTON
}

enum class Shape {
    CIRCLE,
    RECT,
    ROUND_RECT,
    SQUARE
}

/**
 * 绑定动作。一个元素可以同时绑多个动作（如 Ctrl+Shift+Esc 一键）。
 *
 * 用字符串 tag + value 表达，避免 Moshi polymorphic adapter 复杂度：
 *   tag = "KEY"       value = X keycode (如 38 = KEY_A)
 *   tag = "MOUSE"     value = X button (1=左 2=中 3=右 4=滚上 5=滚下)
 *   tag = "MOVE"      value = direction (0=UP 1=RIGHT 2=DOWN 3=LEFT)
 *   tag = "GAMEPAD"   value = (slot << 8) | button（v0.5+ 才接）
 *   tag = "NONE"      value = 0
 */
@JsonClass(generateAdapter = true)
data class Binding(
    val tag: String = "NONE",
    val value: Int = 0,
    val comment: String = ""
) {
    companion object {
        const val TAG_NONE = "NONE"
        const val TAG_KEY = "KEY"
        const val TAG_MOUSE = "MOUSE"
        const val TAG_MOVE = "MOVE"
        const val TAG_GAMEPAD = "GAMEPAD"

        fun key(xKeycode: Int) = Binding(TAG_KEY, xKeycode)
        fun mouse(button: Int) = Binding(TAG_MOUSE, button)
        fun move(direction: Direction) = Binding(TAG_MOVE, direction.ordinal)
    }
}

enum class Direction { UP, RIGHT, DOWN, LEFT }

/**
 * 预置的 X keycode（X11 协议层）。
 * 计算：Linux keycode + 8 = X keycode。
 * 例：KEY_A 的 Linux keycode 是 30，X keycode 是 38。
 */
object XKeycode {
    // 字母
    const val KEY_A = 38;  const val KEY_B = 56;  const val KEY_C = 54
    const val KEY_D = 40;  const val KEY_E = 26;  const val KEY_F = 41
    const val KEY_G = 42;  const val KEY_H = 43;  const val KEY_I = 31
    const val KEY_J = 44;  const val KEY_K = 45;  const val KEY_L = 46
    const val KEY_M = 58;  const val KEY_N = 57;  const val KEY_O = 32
    const val KEY_P = 33;  const val KEY_Q = 24;  const val KEY_R = 27
    const val KEY_S = 39;  const val KEY_T = 28;  const val KEY_U = 30
    const val KEY_V = 55;  const val KEY_W = 25;  const val KEY_X = 53
    const val KEY_Y = 29;  const val KEY_Z = 52

    // 数字
    const val KEY_0 = 19;  const val KEY_1 = 10;  const val KEY_2 = 11
    const val KEY_3 = 12;  const val KEY_4 = 13;  const val KEY_5 = 14
    const val KEY_6 = 15;  const val KEY_7 = 16;  const val KEY_8 = 17
    const val KEY_9 = 18

    // F 键
    const val KEY_F1 = 67;  const val KEY_F2 = 68;  const val KEY_F3 = 69
    const val KEY_F4 = 70;  const val KEY_F5 = 71;  const val KEY_F6 = 72
    const val KEY_F7 = 73;  const val KEY_F8 = 74;  const val KEY_F9 = 75
    const val KEY_F10 = 76; const val KEY_F11 = 95; const val KEY_F12 = 96

    // 修饰键
    const val KEY_SHIFT_L = 50;  const val KEY_SHIFT_R = 62
    const val KEY_CTRL_L = 37;   const val KEY_CTRL_R = 105
    const val KEY_ALT_L = 64;    const val KEY_ALT_R = 108
    const val KEY_TAB = 23;      const val KEY_CAPSLOCK = 66
    const val KEY_SPACE = 65;    const val KEY_ENTER = 36
    const val KEY_BACKSPACE = 22; const val KEY_ESC = 9

    // 方向
    const val KEY_UP = 111;    const val KEY_DOWN = 116
    const val KEY_LEFT = 113;  const val KEY_RIGHT = 114

    // 编辑
    const val KEY_INSERT = 118; const val KEY_DELETE = 119
    const val KEY_HOME = 110;   const val KEY_END = 115
    const val KEY_PAGEUP = 112; const val KEY_PAGEDOWN = 117

    // 符号
    const val KEY_MINUS = 20;     const val KEY_EQUAL = 21
    const val KEY_LEFTBRACE = 34; const val KEY_RIGHTBRACE = 35
    const val KEY_SEMICOLON = 47; const val KEY_APOSTROPHE = 48
    const val KEY_GRAVE = 49;     const val KEY_BACKSLASH = 51
    const val KEY_COMMA = 59;     const val KEY_DOT = 60
    const val KEY_SLASH = 61

    // 系统
    const val KEY_PRINT = 107; const val KEY_SCROLLLOCK = 78
    const val KEY_PAUSE = 127

    // 小键盘
    const val KEY_NUMLOCK = 77
    const val KEY_KP0 = 90;  const val KEY_KP1 = 87;  const val KEY_KP2 = 88
    const val KEY_KP3 = 89;  const val KEY_KP4 = 83;  const val KEY_KP5 = 84
    const val KEY_KP6 = 85;  const val KEY_KP7 = 79;  const val KEY_KP8 = 80
    const val KEY_KP9 = 81;  const val KEY_KPDOT = 91; const val KEY_KPPLUS = 86
    const val KEY_KPMINUS = 82; const val KEY_KPASTERISK = 63
    const val KEY_KPSLASH = 106; const val KEY_KPENTER = 104

    // 鼠标按钮（X button 编号，非 keycode）
    const val MOUSE_LEFT = 1
    const val MOUSE_MIDDLE = 2
    const val MOUSE_RIGHT = 3
    const val MOUSE_SCROLL_UP = 4
    const val MOUSE_SCROLL_DOWN = 5
}
