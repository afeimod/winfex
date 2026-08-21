package com.winfex.model

import com.squareup.moshi.JsonClass

/**
 * Wine 前缀（相当于一个虚拟的 Windows 安装）。
 *
 * 持久化在 files/wine_prefixes/<id>/winfex.cfg
 * 目录结构（与 MiceWine 一致）：
 *   drive_c/windows/system32/    ← x64 DLL
 *   drive_c/windows/syswow64/    ← x86 DLL
 *   drive_c/windows/Fonts/
 *   drive_c/users/<user>/
 *   drive_c/ProgramData/Microsoft/Windows/Start Menu/
 *   dosdevices/                   ← c:, d: 等驱动器符号链接
 *   system.reg / user.reg / userdef.reg  ← wineboot 生成
 */
@JsonClass(generateAdapter = true)
data class WinePrefix(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,

    /** Windows 版本模拟 */
    val windowsVersion: String = "win10",

    /** 虚拟桌面分辨率，null=全屏 */
    val desktopResolution: String? = "1920x1080",

    /** 此 prefix 实际使用的 DX 包装器：DXVK / WineD3D */
    val d3dxRenderer: String = "DXVK",

    /** 是否启用 MangoHud 叠加 */
    val mangoHud: Boolean = false,

    /** DXVK HUD 元素 */
    val dxvkHud: List<String> = listOf("fps", "frametime"),

    /** Box64 调优 */
    val box64DynarecBigBlock: Int = 1,
    val box64DynarecStrongMem: Int = 0,
    val box64DynarecSafeFlags: Int = 1,
    val box64Mmap32: Int = 1,
    val box64Log: String = "0",

    /** ESYNC / FSYNC */
    val esync: Boolean = true,
    val fsync: Boolean = true,

    /** CPU 亲和性 mask（hex），0=不限制 */
    val cpuAffinityMask: Long = 0L,

    /** PulseAudio sink：SLES / AAudio */
    val audioSink: String = "SLES",

    /** PulseAudio 延迟（毫秒） */
    val pulseLatencyMs: Int = 60,

    /** VKD3D feature level：12_0 / 12_1 / 12_2 */
    val vkd3dFeatureLevel: String = "12_0",

    /** Turnip 调试 preset，空=关闭 */
    val tuDebugPreset: String = "",

    /** AdrenoTools 自定义驱动路径（可选） */
    val adrenotoolsDriverPath: String? = null,

    /** 此 prefix 上注册的快捷方式 / 游戏 */
    val shortcuts: List<String> = emptyList()
)
