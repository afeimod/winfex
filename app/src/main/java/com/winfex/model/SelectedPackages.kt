package com.winfex.model

import com.squareup.moshi.JsonClass

/**
 * 选中的包集合 —— 决定了运行时用哪个 Wine / Box64 / DXVK 等。
 *
 * 持久化在 files/selected_packages.json
 */
@JsonClass(generateAdapter = true)
data class SelectedPackages(
    /** Core 运行时库（libc++、X11、PulseAudio、Zink 等）。决定 usr/ 符号链接指向 */
    var coreUuid: String? = null,

    /** Wine 版本（始终是 x86_64 ELF） */
    var wineUuid: String? = null,

    /** Box64 翻译器（仅 ARM64 设备需要，x86_64 设备为 null） */
    var box64Uuid: String? = null,

    /** DXVK 包（与 wineD3dUuid 互斥） */
    var dxvkUuid: String? = null,

    /** WineD3D 包（与 dxvkUuid 互斥） */
    var wineD3dUuid: String? = null,

    /** VKD3D 包（独立，可与 DXVK / WineD3D 共存） */
    var vkd3dUuid: String? = null,

    /** Vulkan 驱动（Turnip / 系统 wrapper 等） */
    var vulkanDriverUuid: String? = null,

    /** WineUtils（CoreFonts / DirectX / Addons） */
    var wineUtilsUuid: String? = null
) {
    fun uuidFor(category: String): String? = when (category) {
        RatPackage.CAT_CORE          -> coreUuid
        RatPackage.CAT_WINE          -> wineUuid
        RatPackage.CAT_BOX64         -> box64Uuid
        RatPackage.CAT_DXVK          -> dxvkUuid
        RatPackage.CAT_VKD3D         -> vkd3dUuid
        RatPackage.CAT_WINED3D       -> wineD3dUuid
        RatPackage.CAT_VULKAN_DRIVER -> vulkanDriverUuid
        RatPackage.CAT_WINE_UTILS    -> wineUtilsUuid
        else -> null
    }

    fun setUuidFor(category: String, uuid: String?) {
        when (category) {
            RatPackage.CAT_CORE          -> coreUuid = uuid
            RatPackage.CAT_WINE          -> wineUuid = uuid
            RatPackage.CAT_BOX64         -> box64Uuid = uuid
            RatPackage.CAT_DXVK          -> dxvkUuid = uuid
            RatPackage.CAT_VKD3D         -> vkd3dUuid = uuid
            RatPackage.CAT_WINED3D       -> wineD3dUuid = uuid
            RatPackage.CAT_VULKAN_DRIVER -> vulkanDriverUuid = uuid
            RatPackage.CAT_WINE_UTILS    -> wineUtilsUuid = uuid
        }
    }
}
