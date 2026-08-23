# 组件 tar.xz 放置目录
#
# 把 CI 构建产出的以下文件放到此目录：
#
#   rootfs-aarch64.tar.xz    → 基础目录结构 + binfmt_misc 注册脚本 + start-container.sh
#   core-aarch64.tar.xz      → 运行时库（libc++, libX11.so, libXtst.so, libredirect.so 等）
#   turnip-aarch64.tar.xz    → Turnip Vulkan 驱动（libvulkan_freedreno.so + vulkan_icd.json）
#   box64-aarch64.tar.xz     → Box64 翻译器（辅助，binfmt_misc 不可用时回退）
#   fex-aarch64.tar.xz       → FEX-Emu 翻译器（主力，binfmt_misc 注册后自动翻译 x86 ELF）
#   wine-arm64ec.tar.xz      → Wine ARM64EC 原生编译（bin/wine, lib/wine/...）
#
# 或跑 CI: GitHub Actions → "Build Components" workflow → 手动触发
# 构建完成后下载 all-components artifact，把 .tar.xz 文件放到此目录
#
# 首次启动 APP 时，ImageFsInstaller 会自动解压这些文件到
# /data/data/com.winfex/files/imagefs/
