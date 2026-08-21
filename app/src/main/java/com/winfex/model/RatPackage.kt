package com.winfex.model

import com.squareup.moshi.JsonClass

/**
 * MiceWine 风格的 .rat 包元数据，对应 pkg-header 文件。
 *
 * .rat 包本质是 tar.xz，包含三部分：
 *   - pkg-header             ← 此对象
 *   - makeSymlinks.sh        ← 安装时执行的符号链接创建脚本
 *   - files/                 ← 实际文件
 *
 * 一个 .rat 包代表一个可独立选择/替换的组件：Wine 版本、Box64 版本、
 * Turnip 驱动、DXVK 版本、Core 运行时库等。
 *
 * 每个包有唯一的 UUID（安装时生成），同一种类可以有多个版本共存，
 * 但同一时刻只有一个被"选中"。
 */
@JsonClass(generateAdapter = true)
data class RatPackage(
    /** 安装时生成的 UUID，作为 packages/ 下的目录名 */
    val uuid: String,

    /** 包名，如 "Wine" / "Box64" / "DXVK" / "VulkanDriver" */
    val name: String,

    /** 分类，决定 UI 分组与选中状态机的字段：
     *   Core / Wine / Box64 / DXVK / VKD3D / WineD3D / VulkanDriver / WineUtils
     */
    val category: String,

    /** 版本字符串，如 "10.10-esync-xinput-dinput" / "0.3.8" / "2.4-1-gplasync" */
    val version: String,

    /** 架构：aarch64 / x86_64 */
    val architecture: String,

    /** 仅 VulkanDriver 类别有：实际 .so 文件相对于 files/ 的路径 */
    val vkDriverLib: String? = null,

    /** 安装时间戳 */
    val installedAt: Long,

    /** 原始 .rat 文件名（仅作记录） */
    val sourceFileName: String
) {
    /** 该包在 packages/ 下的目录 */
    fun dirName(): String = "${category}-${uuid.take(8)}"

    companion object {
        const val CAT_CORE         = "Core"
        const val CAT_WINE         = "Wine"
        const val CAT_BOX64        = "Box64"
        const val CAT_DXVK         = "DXVK"
        const val CAT_VKD3D        = "VKD3D"
        const val CAT_WINED3D      = "WineD3D"
        const val CAT_VULKAN_DRIVER = "VulkanDriver"
        const val CAT_WINE_UTILS   = "WineUtils"
    }
}
