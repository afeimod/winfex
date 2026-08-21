# Winfex

Android PC 模拟器壳子，包名 `com.winfex`，**完全对齐 MiceWine 架构**：

- 基于 **Wine + Box64 + DXVK + VKD3D + Turnip + Zink + PulseAudio**
- **内置 X Server**（基于 termux-x11 上游 Lorie DDX，DISPLAY 默认 `:13`，与系统 XSDL 等隔离）
- **可定制虚拟按键**（XTest 注入 + Winlator 风格 JSON profile + 可视化拖拽编辑器）
- **不使用 proot / chroot / binfmt_misc**，所有库用 Android NDK 交叉编译为 bionic libc
- **.rat 包独立管理**，可从 SAF 导入或内置在 assets/prebuilt_rat/
- **Wine 始终是 x86_64 ELF**，ARM64 设备通过 `box64 wine` 显式调用
- **DXVK/VKD3D/WineD3D 整包解压**，DLL 复制到 Wine prefix 的 `system32/` + `syswow64/`
- **Turnip 作为 .so 文件**，配合动态生成的 `vulkan_icd.json`

---

## 0. 快速开始

```bash
# 1. clone 本仓库
git clone <your-repo-url> winfex
cd winfex

# 2. 同步 X Server 源码（从 termux-x11 拉 Lorie DDX，~5 分钟）
./scripts/sync-xserver.sh

# 3. 下载 .rat 包并放入 assets/prebuilt_rat/（至少 Core/Wine/Box64/DXVK/VulkanDriver）
#    参考 §3

# 4. 用 Android Studio 打开 → Sync Gradle → Build APK
#    或命令行：
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

---

## 1. 与 MiceWine 的对应关系

| MiceWine | Winfex | 说明 |
|----------|--------|------|
| `MiceWine-Application` | 本工程 | Android APK |
| `shell_loader.c` (JNI) | `cpp/winfex_jni.c` + `process_executor.c` | fork + execve 桥 |
| `RatPackageManager.java` | `core/RatPackageManager.kt` | .rat 包安装/选择 |
| `EnvVars.java` | `core/WineWrapper.buildEnv()` | 环境变量构建 |
| `WineWrapper.java` | `core/WineWrapper.kt` | Wine 启动逻辑 |
| `MainActivity.installDXWrapper()` | `core/DXWrapperInstaller.install()` | DXVK/VKD3D 安装 |
| `WinePrefixManagerFragment` | `ui/prefixes/` | 前缀管理 UI |
| `RatManagerActivity` | `ui/packages/` | 包管理 UI |
| `SoundSettingsFragment` + `generatePAFile()` | `core/AudioService.kt` | PulseAudio 配置 |
| `.rat` 包格式 | 同 | tar.xz + pkg-header + makeSymlinks.sh |
| `usr/` 符号链接 → Core 包 | 同 | `applySelectionLinks()` |
| `vulkan_icd.json` 动态生成 | 同 | `applySelectionLinks()` |

---

## 2. 项目结构

```
winfex/
├── settings.gradle.kts                  ← include(":app") + include(":xserver")
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── gradlew / gradlew.bat
├── scripts/
│   └── sync-xserver.sh                  ← ★ 从 termux-x11 同步 Lorie DDX 源码
│
├── docs/
│   └── XSERVER_RESEARCH.md              ← X Server 方案调研报告
│
├── app/                                 ← 主 app
│   ├── build.gradle.kts                 ← compileOnly(project(":xserver"))
│   ├── proguard-rules.pro
│   │
│   └── src/main/
│       ├── AndroidManifest.xml
│       │
│       ├── assets/
│       │   └── prebuilt_rat/            ← ★ 预置 .rat 包放这里
│       │       └── README.txt
│       │
│       ├── cpp/                         ← JNI 桥，编译出 libwinfex.so
│       │   ├── CMakeLists.txt
│       │   ├── winfex_jni.c
│       │   ├── process_executor.{c,h}   ← fork + setsid + execve
│       │   └── cpu_affinity.c           ← CPU 亲和性 + OOM + killpg
│       │
│       ├── java/com/winfex/
│       │   ├── WinfexApp.kt
│       │   ├── native/
│       │   │   ├── NativeBridge.kt
│       │   │   └── NativeLoader.kt
│       │   ├── core/
│       │   │   ├── WinfexPaths.kt
│       │   │   ├── WinfexCrashHandler.kt
│       │   │   ├── RatPackageManager.kt     ← ★ .rat 包安装/选择
│       │   │   ├── WinePrefixManager.kt     ← Wine prefix CRUD
│       │   │   ├── WineWrapper.kt           ← ★ 启动 wine（DISPLAY=:13）
│       │   │   ├── XServerManager.kt        ← ★ X server 生命周期管理
│       │   │   ├── DXWrapperInstaller.kt    ← DXVK/VKD3D DLL 安装
│       │   │   ├── AudioService.kt          ← PulseAudio 配置+启动
│       │   │   ├── WineRunnerService.kt     ← 前台服务 + 自动起 X server
│       │   │   ├── ProcessExecutor.kt
│       │   │   ├── GameLibrary.kt
│       │   │   ├── ShortcutImporter.kt
│       │   │   └── InputController.kt
│       │   ├── model/                       ← data class
│       │   └── ui/                          ← 5 个 tab + settings
│       │
│       └── res/
│
├── xserver/                             ← ★ X Server Gradle module（默认 stub）
│   ├── build.gradle.kts                 ← sync 后追加 externalNativeBuild
│   ├── consumer-rules.pro
│   ├── README.md                        ← 详细说明
│   └── src/main/
│       ├── AndroidManifest.xml          ← 注册 XServerActivity
│       ├── java/com/winfex/xserver/
│       │   └── XServerActivity.kt        ← stub，sync 后被 Lorie Activity 覆盖
│       ├── cpp/                          ← sync 后注入 Lorie DDX + xorg-xserver
│       └── res/
│
└── README.md
```

---

## 3. ★ .rat 包获取 ★

### 3.1 方式 A：直接下载（推荐）

从 MiceWine 项目下载预编译的 .rat 包：

```bash
# 1. 下载所有包的 zip
wget https://github.com/KreitinnSoftware/MiceWine-RootFS-Generator/releases/latest/download/MiceWine-Packages.zip

# 2. 解压
unzip MiceWine-Packages.zip -d rat-packages/

# 3. 你会得到这样的文件：
ls rat-packages/
# Core-<commit>-aarch64.rat
# Wine-<commit>-x86_64.rat
# Box64-0.3.8-aarch64.rat
# DXVK-2.4-1-gplasync-x86_64.rat
# VKD3D-2.8-x86_64.rat
# WineD3D-10.0-x86_64.rat
# mesa-vulkan-freedreno-25.1.4-aarch64.rat
# WineUtils-<commit>-x86_64.rat
# ... 还有许多其他依赖包
```

### 3.2 方式 B：自己编译

```bash
git clone https://github.com/KreitinnSoftware/MiceWine-RootFS-Generator
cd MiceWine-RootFS-Generator

# ARM64 设备（骁龙 8 Gen2/3，天玑 9200+ 等）
./build-all.sh aarch64 --ci

# 或者 x86_64 设备（Intel 平板/Chromebook）
./build-all.sh x86_64 --ci

# 打包成 .rat
./create-rootfs-rat.sh aarch64    # 或 x86_64

# 输出在 build/out/
ls build/out/
```

编译依赖：ArchLinux chroot（CI 脚本会自动 setup），Android NDK r26b，MinGW-w64。完整流程见 MiceWine-RootFS-Generator 的 README。

### 3.3 方式 C：放预置包

把下载的 .rat 文件放进 `app/src/main/assets/prebuilt_rat/`，应用首次启动时自动安装。

**注意**：所有 .rat 加起来可能超过 500MB，APK 会很大。如果不想内置，可以全部不放，让用户从「包」tab 用 SAF 导入。

### 3.4 必需的最小集合

| 类别 | 必需性 | 说明 |
|------|--------|------|
| `Core-*.rat` | ★ 必需 | 运行时库（libc++, X11, PulseAudio, Zink, libglvnd 等） |
| `Wine-*.rat` | ★ 必需 | Wine 二进制（x86_64 ELF，由 Box64 翻译执行） |
| `Box64-*.rat` | ★ ARM64 必需 | Box64 翻译器。x86_64 设备不需要 |
| `DXVK-*.rat` | ★ 必需 | DirectX 9/10/11 → Vulkan |
| `VulkanDriver-*.rat` | ★ 必需 | Turnip 驱动 (libvulkan_freedreno.so) |
| `VKD3D-*.rat` | 可选 | DirectX 12 → Vulkan |
| `WineD3D-*.rat` | 可选 | OpenGL 回退方案（DXVK 不可用时） |
| `WineUtils-*.rat` | 可选 | CoreFonts + DirectX runtime + OpenAL |

---

## 4. 编译指南

### 4.1 环境

- **Android Studio**: Hedgehog (2023.1.1) 或更高
- **JDK**: 17
- **Android SDK**: API 34（compileSdk），API 28（minSdk）
- **NDK**: r26b 或更高（**X Server 编译必需**）
- **CMake**: 3.22.1（NDK 自带，**X Server 编译必需**）
- **Git**: 跑 sync-xserver.sh 用
- 约 **2 GB 磁盘空间**（xserver submodule 较大）

### 4.2 首次编译完整流程

```bash
# 1. clone 本仓库
git clone <your-repo-url> winfex
cd winfex

# 2. 同步 X Server 源码（必须！否则 X server 是 stub）
./scripts/sync-xserver.sh
# 脚本会：
#   - clone termux-x11 + 所有 submodule 到 build/termux-x11/
#   - 复制 lorie/ DDX 源码到 xserver/src/main/cpp/
#   - 复制 Java Activity 到 xserver/src/main/java/com/winfex/xserver/
#   - 全局替换包名 com.termux.x11 → com.winfex.xserver
#   - 修改默认 DISPLAY :0 → :13
#   - 重写 xserver/build.gradle.kts 加 externalNativeBuild
#   - 尝试下载 MiceWine 的 Wine 兼容 patch（可选）

# 3. (可选) 手动 apply MiceWine 的 Wine 兼容 patch
#    这个 patch 让 X server 不抢 Wine 需要的 SIGSEGV/SIGBUS 信号
cd xserver/src/main/cpp/xserver
patch -p1 < ../patches/micewine-wine-compat.patch
cd -

# 4. (可选) 放预置 .rat 包到 assets/prebuilt_rat/
#    至少需要：Core / Wine / Box64 / DXVK / VulkanDriver
#    参考 §3

# 5. 配置 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 6. 编译
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 4.3 重新生成完整 gradle wrapper

仓库里的 `gradlew` 是简化版，第一次 sync 后建议：

```bash
gradle wrapper --gradle-version 8.7 --distribution-type bin
```

### 4.4 不集成 X Server 也能编译吗？

**可以**。xserver module 默认是 stub 状态，`app` 对它的依赖是 `compileOnly`。
没跑 sync-xserver.sh 时：

- 工程能正常编译，APK 能装
- 但点「启动 X Server」按钮会失败（stub Activity 只显示提示文字）
- Wine 启动后没有显示目标（黑屏）

如果你只想先验证 .rat 包管理 + UI 流程，可以暂时跳过 X Server 集成。

---

## 4.5 X Server 集成深入说明

### 为什么用 termux-x11 上游 Lorie 而不是 MiceWine 那个？

MiceWine 的 Lorie 是从 termux-x11 vendor 后**裁剪 + 改写为 C** 的版本：
- 去掉了 GLX server、DRI3、Present 等高级特性
- `.cpp` 改成 `.c`，丢失 RAII 等现代 C++ 优势
- 跟 MiceWine 主项目深度耦合，难单独抽出

我们用 **termux-x11 上游原版 Lorie**，功能完整、维护活跃。详见
`docs/XSERVER_RESEARCH.md`。

### DISPLAY 号隔离

| 来源 | 默认 DISPLAY | socket 路径 |
|------|-------------|-------------|
| XSDL APK | `:0` | `/data/data/org.eqsoft.xsdl/files/.X11-unix/X0` |
| termux-x11 APK | `:0` | `$TMPDIR/.X11-unix/X0` |
| **Winfex（本工程）** | **`:13`** | `/data/data/com.winfex/cache/tmp/.X11-unix/X13` |

修改默认号：编辑 `app/src/main/java/com/winfex/core/XServerManager.kt` 的
`DEFAULT_DISPLAY_NUMBER` 常量，**同时**改 `xserver/src/main/cpp/lorie/cmdentrypoint.cpp`
里的 `":13"` 字符串（sync-xserver.sh 已经自动改了）。

### 两种启动模式

`XServerManager.start()` 支持两种模式：

1. **LORIE_ACTIVITY**（默认）：拉起 `com.winfex.xserver.XServerActivity`，
   X server 跑在 Activity 内的 native 线程，渲染到 Surface。**需要先跑 sync-xserver.sh**。
2. **HEADLESS**：直接 execve 一个 Xserver ELF 二进制。需要用户提供二进制路径
   （可以打包成 .rat 包）。适合无 GUI 的测试场景。

### 编译失败排查

| 现象 | 原因 | 解决 |
|------|------|------|
| `xserver/src/main/cpp/lorie/` 不存在 | 没跑 sync-xserver.sh | 跑 `./scripts/sync-xserver.sh` |
| `xserver/src/main/cpp/xserver/` 是空目录 | submodule 没拉全 | `cd build/termux-x11 && git submodule update --init --recursive`，再 `./scripts/sync-xserver.sh --local build/termux-x11` |
| CMake 找不到 python | 缺 python3 | `apt install python3 python3-pip` |
| meson 报错 | 缺 meson | `pip install meson ninja` |
| 找不到 NDK | 没装 NDK | Android Studio SDK Manager 装 NDK r26b |
| `libandroid-shmem` 找不到 | submodule 问题 | 同上，重新拉 submodule |

### 与上游 termux-x11 的差异

| 项 | termux-x11 上游 | com.winfex |
|----|-----------------|-----------|
| 包名 | `com.termux.x11` | `com.winfex.xserver` |
| 默认 DISPLAY | `:0` | `:13` |
| 与 Wine 集成 | 无 | WineWrapper 启动前确保 X ready |
| 信号 patch | 无 | 合并 MiceWine 的 Wine 兼容 patch |
| 渲染目标 | 自己的 Activity Surface | 同左 |

---

## 4.6 输入系统（v0.4 新增）

### 注入路径选择

| 方案 | root | 性能 | Wine 兼容 | 复杂度 | 选用 |
|------|------|------|----------|--------|------|
| **XTest 扩展** | 否 | <0.1ms/事件 | 完美 | 中 | ✅ |
| uinput | 是 | 最快 | Wine 看不到 | 高 | ✗ |
| Lorie JNI 私有 socket | 否 | 快 | 完美 | 低（但耦合） | ✗ |
| 改 winex11.drv 源码 | 否 | 最快 | 完美 | 极高 | ✗ |

**v0.4 选用 XTest 扩展**：
- Lorie 已经把 `xtest.c` 编译进 Xext（`recipes/xserver.cmake` 确认）
- 标准 X11 协议，跨进程，多 client 同时连
- Wine 的 `winex11.drv` 原生识别 XTest 事件（与真实硬件键盘等价）
- 不需要 root

### 输入叠加层架构

```
┌────────────────────────────────────────────────────────────────┐
│  Android 屏幕                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  InputOverlayHost (WindowManager TYPE_APPLICATION_OVERLAY)│  │
│  │  ┌──────────────────────────────────────────────────┐    │  │
│  │  │  InputOverlayView                                │    │  │
│  │  │  ├── 按钮 A (0.85, 0.72)  → Binding.KEY(KEY_A)   │    │  │
│  │  │  ├── D-Pad (0.15, 0.72)   → 4 个 Binding.KEY     │    │  │
│  │  │  ├── 摇杆 (0.18, 0.78)    → 4 个 Binding.KEY     │    │  │
│  │  │  ├── 触摸板 (0.50, 0.92)  → Binding.MOVE         │    │  │
│  │  │  └── 鼠标左键 (0.65, 0.92) → Binding.MOUSE(1)    │    │  │
│  │  └──────────────────────────────────────────────────┘    │  │
│  │  (z-order 在所有 Activity 之上)                            │  │
│  └──────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Lorie XServerActivity (渲染 X server 画面)              │  │
│  │  └── LorieView (SurfaceView)                              │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
                              │
                              │ X11 socket
                              ▼
                    ┌──────────────────────┐
                    │ X server (Lorie) :13 │
                    │  └── xtest.c (Xext)   │
                    └──────────────────────┘
                              │
                              │ X11 协议
                              ▼
                    ┌──────────────────────┐
                    │ Wine (winex11.drv)    │
                    └──────────────────────┘
```

### 触摸事件流

1. 用户触摸屏幕 → InputOverlayView.onTouchEvent
2. 遍历 elements 找命中元素
3. 命中 → 处理元素事件（按下/移动/松开）→ InputController.dispatchBinding
4. 未命中 → `return false`，事件传到下层 LorieView（Lorie 自己处理为鼠标）

### Binding 类型

| tag | value | 说明 |
|-----|-------|------|
| `KEY` | X keycode | 键盘按键（如 38 = KEY_A） |
| `MOUSE` | X button | 鼠标按键（1=左 2=中 3=右 4=滚上 5=滚下） |
| `MOVE` | direction (0-3) | 鼠标相对移动方向（持续按住时 60Hz 发送） |
| `GAMEPAD` | (slot<<8)\|button | 虚拟手柄（v0.5+ 接） |
| `NONE` | 0 | 无绑定 |

### 预置 Profile

`assets/preset_profiles/` 下提供 3 个开箱即用的布局：

| 文件 | 用途 | 元素数 |
|------|------|--------|
| `default.json` | 通用布局（D-Pad + 4 按钮 + 触摸板 + ESC/Enter） | 10 |
| `fps.json` | FPS 游戏（大触摸板转视角 + 移动摇杆 + 射击/瞄准/换弹/跳/蹲） | 11 |
| `rpg.json` | RPG 游戏（移动摇杆 + 摄像机触摸板 + 互动/攻击/格挡/疾跑/地图） | 12 |

首次启动时自动导入到 `/data/data/com.winfex/files/input/`。

### 自定义编辑器

「输入」tab → 长按一个 profile → 编辑布局 → 进入 `ControlsEditorActivity`：
- 全屏网格背景
- 顶部工具栏：+ 元素 / 编辑选中 / 删除选中 / 保存
- 点空白处：弹添加元素对话框（按钮/D-Pad/摇杆/触摸板）
- 点元素：弹属性对话框（4 个绑定槽 / 形状 / 缩放 / toggle / 文字）
- 拖动元素：自动吸附到 1/24 网格

### SYSTEM_ALERT_WINDOW 权限

虚拟按键叠加层用 `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`，需要用户授权：

```kotlin
// 在 InputFragment 里检查并请求
if (!InputOverlayHost.canDrawOverlays(context)) {
    InputOverlayHost.requestOverlayPermission(context)
}
```

用户点「是」后跳到系统设置 → Winfex → 允许显示在其他应用上层。

### libX11.so / libXtst.so 来源

XTestInjector 通过 dlopen 动态加载这两个库：
- 路径：`/data/data/com.winfex/files/usr/lib/`
- 来源：由 **Core .rat 包**提供（MiceWine 的 Core 包含 libX11 + libXtst）
- 如果缺失：注入器优雅降级（不崩，但虚拟按键无效）

---

## 5. 运行时目录布局

应用安装后：

```
/data/data/com.winfex/files/
├── packages/                              ← 所有 .rat 包解压后
│   ├── Core-<uuid8>/files/usr/{lib,bin,etc,...}
│   ├── Wine-<uuid8>/files/wine/
│   │   ├── bin/{wine64, wineboot, winecfg, wineserver, regedit, ...}
│   │   ├── lib/wine/
│   │   │   ├── x86_64-unix/               ← Wine 的 Unix 侧 .so
│   │   │   ├── x86_64-windows/            ← Wine 的 PE 侧 .dll
│   │   │   ├── i386-unix/
│   │   │   └── i386-windows/
│   │   └── share/wine/{fonts, nls, inf, mono, gecko}
│   ├── Box64-<uuid8>/files/usr/bin/box64
│   ├── DXVK-<uuid8>/files/{x64,x32}/*.dll
│   ├── VKD3D-<uuid8>/files/{x64,x32}/*.dll
│   ├── WineD3D-<uuid8>/files/{x64,x32}/*.dll
│   ├── VulkanDriver-<uuid8>/files/usr/lib/libvulkan_freedreno.so
│   └── WineUtils-<uuid8>/files/wine-utils/{CoreFonts,DirectX,OpenAL,Addons}
│
├── usr/                                   ← 符号链接 → packages/<selectedCore>/files/usr
├── wine_prefixes/                         ← Wine 前缀
│   └── <id>/
│       ├── winfex.cfg                     ← JSON 配置
│       ├── drive_c/windows/system32/      ← x64 DLL
│       ├── drive_c/windows/syswow64/      ← x86 DLL
│       ├── drive_c/windows/Fonts/
│       ├── drive_c/users/winfex/Desktop/
│       ├── dosdevices/{c:,z:}             ← 驱动器符号链接
│       ├── system.reg / user.reg          ← wineboot 生成
│       └── dxvk-cache/
├── home/                                  ← Wine 进程的 HOME
├── selected_packages.json                 ← 当前选中的包 uuid
├── vulkan_icd.json                        ← 动态生成
├── pa_default.pa                          ← PulseAudio 配置
├── mangohud.conf                          ← MangoHud 配置
├── games.json                             ← 游戏库索引
├── shortcuts.json                         ← 快捷方式索引
├── input/<id>.json                        ← 输入方案
├── logs/
└── crash/

/data/data/com.winfex/cache/
└── tmp/                                   ← Wine 临时目录
```

---

## 6. 启动一个 Windows 程序的完整流程

```
用户点击「启动」
   ↓
LibraryFragment / ShortcutsFragment.launchXxx()
   ↓
DXWrapperInstaller.install(prefix)          ← 复制 DXVK/VKD3D DLL 到 system32/syswow64
   ↓
AudioService.start(prefix)                   ← 生成 pa_default.pa + 启动 pulseaudio
   ↓
WineWrapper.launch(LaunchParams)
   ↓
   buildEnv(prefix):
     PATH=<usr>/bin:<winePkg>/files/wine/bin:<winePkg>/files/wine/lib/wine/x86_64-unix:<box64Pkg>/files/usr/bin
     LD_LIBRARY_PATH=/system/lib64:<usr>/lib
     WINEPREFIX=<prefixes>/<id>
     WINEARCH=win64
     WINEDEBUG=-all
     WINEDLLOVERRIDES=mscoree,mshtml=d;winemenubuilder.exe=d
     WINEESYNC=1 / WINEFSYNC=1
     VK_ICD_FILENAMES=<base>/vulkan_icd.json
     GALLIUM_DRIVER=zink
     ZINK_DESCRIPTORS=lazy
     TU_DEBUG=<preset>
     DXVK_ASYNC=1
     DXVK_STATE_CACHE_PATH=<home>/.cache/dxvk-shader-cache
     DXVK_HUD=fps,frametime
     VKD3D_FEATURE_LEVEL=12_0
     BOX64_LOG=0 / BOX64_DYNAREC_BIGBLOCK=1 / BOX64_MMAP32=1
     PULSE_LATENCY_MSEC=60
     HOME=<home>  TMPDIR=<cache>/tmp  DISPLAY=:0
   ↓
   buildArgv():
     ARM64: [box64, wine64, explorer, /desktop=shell,1920x1080, <exePath>, <args>]
     x86_64: [wine64, explorer, /desktop=shell,1920x1080, <exePath>, <args>]
   ↓
ProcessExecutor.start(spec)
   ↓
NativeBridge.nativeExecBinary() → fork + setsid + execve
   ↓
   父进程返回 pgid
   子进程：
     - 立即 setsid() 形成独立进程组
     - dup2 重定向 stdout/stderr 到 pipe
     - 设置 CPU 亲和性 + OOM adj
     - 清理 LD_PRELOAD 等危险环境变量
     - execve 启动 box64 → wine64
   ↓
WineRunnerService.startForeground(pgid)     ← 保活
```

---

## 7. 关键代码导读

### 7.1 `.rat` 包格式

```
my-package.rat (本质是 tar.xz)
├── pkg-header              ← 元数据
│   name=Wine (10.10-esync-xinput-dinput)
│   category=Wine
│   version=10.10-esync-xinput-dinput
│   architecture=x86_64
│   vkDriverLib=files/usr/lib/libvulkan_freedreno.so   ← 仅 VulkanDriver 类别
├── makeSymlinks.sh         ← 安装时执行的符号链接创建脚本
└── files/                  ← 实际文件
    └── ...
```

解析逻辑见 `RatPackageManager.parseRatHeader()` 和 `extractTarXz()`。

### 7.2 选中状态的副作用

当用户在「包」tab 点击「选中」时，`RatPackageManager.select()` 会：

1. 写入 `selected_packages.json`
2. 调用 `applySelectionLinks()`：
   - 如果是 `Core` 包：删除 `usr/`，重建符号链接指向新 Core 包的 `files/usr`
   - 如果是 `VulkanDriver` 包：重新生成 `vulkan_icd.json`，指向新驱动的 `.so`

### 7.3 DXVK 安装

每次启动游戏前，`DXWrapperInstaller.install(prefix)` 都会被调用一次（覆盖式拷贝）：

```kotlin
when (prefix.d3dxRenderer) {
    "DXVK" -> {
        copyDlls(File(src, "x64"), system32, "DXVK-x64")     // 64位 DLL
        copyDlls(File(src, "x32"), syswow64, "DXVK-x32")     // 32位 DLL
    }
    "WineD3D" -> { ... }
}
// VKD3D 总是安装
copyDlls(File(vkd3dSrc, "x64"), system32, "VKD3D-x64")
copyDlls(File(vkd3dSrc, "x32"), syswow64, "VKD3D-x32")
```

### 7.4 native 桥（C 层）

`cpp/winfex_jni.c` 暴露的方法：

| 方法 | 作用 |
|------|------|
| `nativeExecBinary` | fork + setsid + execve 启动外部进程 |
| `nativeSetupProcess` | 在子进程里设 CPU 亲和性 + OOM adj |
| `nativeKillProcessGroup` | kill(-pgid, SIGTERM/SIGKILL) |
| `nativeSymlink` | symlink(2) |
| `nativeChmod` | chmod(2) |

注意：我们走 execve 而不是 MiceWine 的 `execl("/system/bin/sh")`，省一层 shell 解析，但需要自己处理 argv/envp。

---

## 8. 已知限制 & 后续 TODO

### 8.1 当前未实现

- ~~**X Server**~~：✅ v0.3.0 已集成 termux-x11 上游 Lorie DDX，跑 `./scripts/sync-xserver.sh` 后可用
- ~~**输入注入（XTest）~~：✅ v0.4.0 已实现 XTest 注入路径 + Winlator 风格可定制虚拟按键
- **输入注入（手势）**：Lorie Activity 自带的触摸→鼠标手势（TRACKPAD 模式）目前与 InputOverlayHost 的 overlay 是并行的。v0.5 计划通过让 Lorie Activity 不处理触摸，全部交给 overlay 转发
- **WineUtils 的注册表导入**：MiceWine 会跑 `regedit DefaultDLLsOverrides.reg` 设置 DLL override，本工程还没接
- **图形驱动自动检测**：MiceWine 有一套复杂的 GPU 探测逻辑，本工程只在 About 里显示推荐
- **MiceWine Wine 兼容 patch**：sync-xserver.sh 会自动下载但不会自动 apply，需要手动 review
- **手柄支持（蓝牙 Xbox/PS5）**：v0.5 计划，走 UDP server → Wine 侧 xinput1_3.dll 替代品（参考 MiceWine controller.c）
- **软键盘输入法**：Lorie Activity 已实现 onCreateInputConnection，但需要测试 CJK IME（Gboard 中文）

### 8.2 与 MiceWine 的差异

| 维度 | MiceWine | Winfex |
|------|----------|--------|
| 进程启动 | fork + execl("/system/bin/sh") | fork + execve |
| 符号链接创建 | shell `ln -sf` | `Os.symlink` 或 `nativeSymlink` |
| 配置生成 | Java 字符串拼接 | Kotlin 字符串模板 |
| 包管理 UI | 独立 Activity | 嵌入底部 tab |
| 输入叠加 | Lorie + X11 events | 自绘 View + stub dispatch |
| MangoHud 集成 | 完整 | 配置文件生成 + env vars，未启动 |

### 8.3 性能调优建议

- 骁龙 8 Gen3 大核通常 bit 4/5/6，CPU mask 设 `0x70` 跑 Wine 主线程
- `BOX64_DYNAREC_BIGBLOCK=1` + `BOX64_DYNAREC_STRONGMEM=0` 是性能/兼容性平衡点
- `ZINK_DESCRIPTORS=lazy` 减少 Vulkan descriptor 开销
- `DXVK_ASYNC=1` 异步 shader 编译，避免卡顿
- `WINEESYNC=1` + `WINEFSYNC=1` 需要 kernel 5.15+ 支持

---

## 9. 致谢

本项目参考了 [MiceWine](https://github.com/KreitinnSoftware) 项目的架构设计，包括：

- `.rat` 包格式与 `pkg-header` 元数据规范
- `usr/` 符号链接 + `packages/<uuid>/files/` 目录布局
- Wine 启动的环境变量集合（PATH / LD_LIBRARY_PATH / WINEPREFIX / VK_ICD / GALLIUM_DRIVER / BOX64_* / DXVK_*）
- DXVK/VKD3D DLL 安装到 `system32/syswow64` 的方式
- PulseAudio + SLES/AAudio 音频链

底层组件：

- [Wine](https://www.winehq.org/) — Windows API 兼容层
- [Box86 / Box64](https://github.com/ptitSeb/box64) — x86/x86_64 → ARM 指令翻译
- [DXVK](https://github.com/doitsujin/dxvk) — DirectX 9/10/11 → Vulkan
- [VKD3D-Proton](https://github.com/HansKristian-Work/vkd3d-proton) — DirectX 12 → Vulkan
- [Mesa / Turnip](https://gitlab.freedesktop.org/mesa/mesa) — Adreno Vulkan 驱动
- [PulseAudio](https://www.freedesktop.org/wiki/Software/PulseAudio/) — 音频服务器

---

## 10. License

代码部分采用 **GPL-3.0**（与 Wine / Box64 / DXVK / VKD3D 的 license 保持一致）。
