# 此目录用于存放预置的 .rat 包，应用首次启动时会自动安装。

# .rat 包是 MiceWine 风格的 tar.xz 压缩包，包含：
#   pkg-header         key=value 元数据
#   makeSymlinks.sh    符号链接创建脚本
#   files/             实际文件

# 推荐的获取方式：
#   1. 直接从 MiceWine release 下载：
#      https://github.com/KreitinnSoftware/MiceWine-RootFS-Generator/releases
#      下载 MiceWine-Packages.zip，解压后所有 *.rat 文件可以放进来
#
#   2. 用 MiceWine-RootFS-Generator 自己编译：
#      git clone https://github.com/KreitinnSoftware/MiceWine-RootFS-Generator
#      cd MiceWine-RootFS-Generator
#      ./build-all.sh aarch64
#      ./create-rootfs-rat.sh aarch64
#      输出在 build/ 目录下
#
# 至少需要这几类包才能启动：
#   - Core-*.rat               运行时库（libc++, X11, PulseAudio, Zink）
#   - Wine-*.rat                Wine 二进制（x86_64 ELF）
#   - Box64-*.rat               Box64 翻译器（ARM64 设备必需）
#   - DXVK-*.rat                DXVK DLL
#   - VulkanDriver-*.rat        Turnip 驱动
#
# 可选包：
#   - VKD3D-*.rat               D3D12 支持
#   - WineD3D-*.rat             OpenGL 回退方案
#   - WineUtils-*.rat           CoreFonts + DirectX runtime + OpenAL

# 注意：所有 .rat 文件加起来可能超过 500MB，APK 体积会很大。
# 如果不想内置，可以从本目录删除所有文件，让用户从「包」tab 自行导入。
