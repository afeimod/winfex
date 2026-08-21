package com.winfex.xserver

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * X Server 占位 Activity。
 *
 * **此文件会被 scripts/sync-xserver.sh 替换为 Lorie 的真实 Activity 实现**。
 *
 * 在你跑 sync-xserver.sh 之前，这个 stub 让 xserver module 能正常编译，
 * 但点击它只会显示一个提示页面，告诉用户去跑同步脚本。
 *
 * 跑完 sync-xserver.sh 后：
 *   - 这个文件会被 termux-x11 的 `LorieActivity.kt`（或等价文件）覆盖
 *   - `src/main/cpp/` 会包含完整的 Lorie DDX 源码 + xserver submodule
 *   - `src/main/cpp/CMakeLists.txt` 会被 termux-x11 的版本覆盖
 *   - `xserver/build.gradle.kts` 会自动加上 externalNativeBuild 块
 */
class XServerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv = TextView(this).apply {
            text = """
                |X Server 未集成
                |
                |此 Activity 是占位 stub。
                |请在工程根目录执行：
                |
                |    ./scripts/sync-xserver.sh
                |
                |此脚本会：
                |  1. clone termux-x11（含 xorg-xserver submodule）
                |  2. 复制 lorie/ 内容到此 module
                |  3. 全局替换 com.termux.x11 → com.winfex.xserver
                |  4. 修改默认 DISPLAY 为 :13
                |  5. 合并 MiceWine 的 Wine 兼容 patch
                |
                |详见 xserver/README.md。
            """.trimMargin()
            setPadding(48, 48, 48, 48)
            textSize = 14f
        }
        setContentView(tv)
    }
}
