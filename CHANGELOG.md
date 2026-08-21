# Changelog

本文件记录 Winfex 的所有版本变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 计划中
- Lorie Activity 触摸手势与 InputOverlayHost 共存方案
- 蓝牙手柄支持（UDP server + xinput1_3.dll 替代品）
- 软键盘 CJK IME 支持
- WineUtils 注册表自动导入
- 图形驱动自动检测

## [0.4.0] - 2026-08-21

### 新增
- **XTest 注入路径**：通过 XTEST 扩展把按键/鼠标事件注入 X server，无需 root
  - 新增 `cpp/xtest_injector.c` JNI 桥
  - 新增 `input/XTestInjector.kt` Kotlin 包装
- **Winlator 风格虚拟按键系统**：
  - `InputProfile` 重写为 elements 列表，支持 BUTTON / DPAD / STICK / TRACKPAD 4 种元素
  - 4 个 binding 槽，可同时绑键盘按键 / 鼠标按键 / 鼠标移动 / 手柄（v0.5+）
  - 坐标系相对屏幕 0..1，支持 scale / toggle / text
- **可视化布局编辑器** `ControlsEditorActivity`：
  - 全屏网格背景，拖拽放置元素，吸附到 1/24 网格
  - 点元素弹属性对话框（绑定槽 / 形状 / 缩放 / toggle / 文字）
- **系统级叠加层** `InputOverlayHost`：
  - WindowManager TYPE_APPLICATION_OVERLAY，跨 Activity 显示
  - 自动请求 SYSTEM_ALERT_WINDOW 权限
- **预置 3 个游戏布局**：default / fps / rpg，首次启动自动导入
- X server 启动后自动 XTestInjector.connect
- Application 启动时 XTestInjector.init 加载 libX11+libXtst

### 变更
- `InputController.dispatchKey` 从 stub 改为真正调 XTestInjector
- `InputController` 新增 `dispatchBinding` 处理 Binding tag/value
- `WineRunnerService` 加 30 秒宽限期，Wine 退出后保留 X server
- `WinfexApp.onCreate` 新增 XTestInjector.init
- `XServerManager` start 成功后自动 connect XTestInjector，stop 时 disconnect
- `AndroidManifest` 加 SYSTEM_ALERT_WINDOW 权限
- `ControlsEditorActivity` 替代旧的 `InputEditActivity`

## [0.3.0] - 2026-08-21

### 新增
- **内置 X Server**（基于 termux-x11 上游 Lorie DDX）：
  - DISPLAY 默认 `:13`，与系统 XSDL / termux-x11 的 `:0` 物理隔离
  - socket 走 `$TMPDIR/.X11-unix/X13`
  - 不需要 root
- **`xserver/` Gradle module**：默认 stub，跑 `scripts/sync-xserver.sh` 后变完整
- **`scripts/sync-xserver.sh`**：自动从 termux-x11 clone + 改包名 + 复制 lorie/ + 改 DISPLAY
- **`XServerManager`**：管理 X server 生命周期，支持 LORIE_ACTIVITY / HEADLESS 两种启动模式
- **`docs/XSERVER_RESEARCH.md`**：完整方案调研报告
- `WineWrapper` DISPLAY 从 `:0` 改为 `:13`
- `WineRunnerService` 启动 Wine 前自动 startXServer
- `PrefixesFragment` 顶部 X Server 状态条（红/黄/绿点 + 启动/停止按钮）
- `SettingsActivity` 显示 X server 状态 + socket 路径
- `app/build.gradle.kts` 加 `compileOnly(project(":xserver"))`

### 变更
- `settings.gradle.kts` 加 `include(":xserver")`
- `WineWrapper` env `DISPLAY` 改为 `XServerManager.displayString()`
- README 加 X Server 集成深入说明（§4.5）

## [0.2.0] - 2026-08-21

### 重写
- **完全对齐 MiceWine 架构**：
  - `.rat` 包格式（tar.xz + pkg-header + makeSymlinks.sh）
  - 多包共存 + 选中状态机
  - Core 包选中时重建 `usr/` 符号链接
  - VulkanDriver 包选中时动态生成 `vulkan_icd.json`
- **`RatPackageManager`**：解析 pkg-header、解压 tar.xz、执行 makeSymlinks.sh、chmod +x
- **`WineWrapper`** 完整 env vars：PATH / LD_LIBRARY_PATH / WINEPREFIX / VK_ICD / GALLIUM_DRIVER / ZINK_* / TU_DEBUG / DXVK_* / VKD3D_* / BOX64_* / PULSE_*
- ARM64 显式 `box64 wine64 explorer /desktop=shell,WxH exe`
- **`WinePrefixManager`**：drive_c/windows/{system32,syswow64,Fonts} 完整目录布局
- **`DXWrapperInstaller`**：DXVK/VKD3D DLL 复制到 system32 + syswow64
- **`AudioService`**：生成 PulseAudio 配置 + 启动 pulseaudio --start -nF
- 5 个 tab 改名：库 / 前缀 / 包 / 输入 / 快捷

### 变更
- `ContainerManager` → `WinePrefixManager`（"容器"改叫"前缀"，更准确）
- `GraphicsDriverManager` 删除，逻辑合并到 `DXWrapperInstaller`
- assets 目录从 `wine/box64/dxvk/turnip/rootfs/` 改为 `prebuilt_rat/`
- `commons-compress` 依赖（解 tar.xz）

## [0.1.0] - 2026-08-21

### 首次发布
- 包名 `com.winfex`，Kotlin + XML，Material 3 深色主题
- 5 个底部 tab：游戏库 / 容器 / 图形 / 输入 / 快捷方式
- `libwinfex.so` JNI 桥：fork + setsid + execve + CPU 亲和性 + killpg
- `NativeLoader` 从 assets 释放二进制到 filesDir
- `WineRunner` 启动 wine + box64（环境变量配置）
- `WineRunnerService` 前台服务保活
- `ContainerManager` + `GameLibrary` + `ShortcutImporter` + `InputController`
- 容器编辑对话框（图形驱动 / 分辨率 / CPU 亲和性 / ESYNC / FSYNC）
- Winlator 风深色紫蓝主题

[Unreleased]: https://github.com/winfex/winfex/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/winfex/winfex/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/winfex/winfex/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/winfex/winfex/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/winfex/winfex/releases/tag/v0.1.0
