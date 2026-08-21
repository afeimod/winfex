# Winfex X Server Module

这是 com.winfex 工程的 **X Server Gradle module**。

> ⚠️ 当前是 **stub 状态**：module 骨架已就位，但没有真正的 X server 源码。
> 跑 `scripts/sync-xserver.sh` 后会自动从 termux-x11 拉取 Lorie DDX 源码并集成。

---

## 1. 这个 module 是什么

把 [termux/termux-x11](https://github.com/termux/termux-x11) 的 Lorie DDX 集成进
com.winfex，作为内置 X server。**不依赖任何外部 X11 APK**（XSDL、termux-x11 APK 等），
DISPLAY 号默认 `:13`，与系统上可能存在的其他 X server 物理隔离。

## 2. 为什么不用 MiceWine 的 Lorie

MiceWine 的 Lorie 是从 termux-x11 vendor 过来后**裁剪 + 改写为 C** 的版本：
- 去掉了 GLX server、DRI3、Present 等高级特性
- 把 `.cpp` 改成 `.c`，丢失了 RAII 等现代 C++ 优势
- 跟 MiceWine 主项目深度耦合，难以单独抽出

我们用 **termux-x11 上游原版 Lorie**，功能更完整、维护更活跃。

## 3. 如何启用

### 3.1 跑同步脚本

在工程根目录执行：

```bash
./scripts/sync-xserver.sh
```

脚本做的事：

1. `git clone --recurse-submodules https://github.com/termux/termux-x11` 到 `build/termux-x11/`
2. 复制 `termux-x11/lorie/src/main/cpp/` 到 `xserver/src/main/cpp/`
3. 复制 `termux-x11/lorie/src/main/java/com/termux/x11/` 到
   `xserver/src/main/java/com/winfex/xserver/`
4. 全局替换包名 `com.termux.x11` → `com.winfex.xserver`
5. 修改 `cmdentrypoint.cpp` 默认 DISPLAY 为 `:13`
6. 合并 MiceWine 的 Wine 兼容 patch（信号处理 / SHM / xkb 符号）
7. 在 `xserver/build.gradle.kts` 末尾追加 `externalNativeBuild` 块

### 3.2 验证

```bash
./gradlew :xserver:assembleDebug
# 产物：xserver/build/outputs/aar/xserver-debug.aar
# 应包含 jni/arm64-v8a/libXserver.so 等几十个 .so
```

### 3.3 集成测试

跑完后，在主 app 的 `PrefixesFragment` 点击「启动 X Server」按钮，
应该会拉起 `XServerActivity`，看到黑屏 + 鼠标指针。

## 4. 目录结构（同步后）

```
xserver/
├── build.gradle.kts                      ← 同步后追加 externalNativeBuild
├── consumer-rules.pro
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/winfex/xserver/
│   │   ├── XServerActivity.kt            ← Lorie Activity（替换 stub）
│   │   ├── LorieView.kt
│   │   ├── EventHandler.kt
│   │   └── ...                           ← 由 sync 脚本注入
│   ├── cpp/
│   │   ├── CMakeLists.txt                ← termux-x11 原版
│   │   ├── lorie/                        ← DDX 源码
│   │   │   ├── InitOutput.c              ← screen/pixmap/exa/dri3/present (51 KB)
│   │   │   ├── InitInput.c               ← 鼠标/键盘设备 (11 KB)
│   │   │   ├── InputXKB.c                ← XKB 键盘映射 (30 KB)
│   │   │   ├── renderer.cpp              ← EGL/GLES2 渲染器 (52 KB)
│   │   │   ├── activity.cpp              ← Android Activity JNI 桥 (22 KB)
│   │   │   ├── cmdentrypoint.cpp         ← 命令行入口 (24 KB)
│   │   │   ├── buffer.c + .h             ← LorieBuffer 共享缓冲 (27 KB)
│   │   │   ├── clipboard.c               ← 剪贴板同步 (18 KB)
│   │   │   ├── lorie.h                   ← 核心头文件 (21 KB)
│   │   │   └── fbconfigs.h               ← GLX fbconfig 表 (256 KB)
│   │   ├── xserver/                      ← xorg-xserver submodule
│   │   ├── pixman/                       ← submodule
│   │   ├── libxfont/                     ← submodule
│   │   ├── libxshmfence/                 ← submodule
│   │   ├── xkbcomp/                      ← submodule
│   │   ├── xkeyboard-config/             ← submodule
│   │   ├── patches/                      ← 必须的 patch
│   │   │   ├── Xtrans.patch              ← socket 路径可配
│   │   │   ├── xserver.patch             ← 信号/SHM/xkb
│   │   │   └── ...
│   │   └── recipes/*.cmake               ← 各依赖的 CMake 配方
│   └── res/
└── README.md                             ← 本文件
```

## 5. DISPLAY 号说明

| 来源 | 默认 DISPLAY | socket 路径 |
|------|-------------|-------------|
| XSDL APK | `:0` | `/data/data/org.eqsoft.xsdl/files/.X11-unix/X0` |
| termux-x11 APK | `:0` | `$TMPDIR/.X11-unix/X0` |
| **Winfex（本工程）** | **`:13`** | `/data/data/com.winfex/cache/tmp/.X11-unix/X13` |

Wine 通过 `DISPLAY=:13` 环境变量连接到我们的 X server，不会与系统上其他 X server 冲突。

如需修改默认号，编辑 `XServerManager.DEFAULT_DISPLAY_NUMBER`。

## 6. 编译依赖

跑 sync 脚本 + 编译 xserver module 需要：

- **Android NDK** r26b+
- **CMake** 3.22.1+
- **Python 3** + `meson` + `ninja`（Lorie 的某些依赖用 meson）
- **Git**（拉 submodule）
- 约 **2 GB 磁盘空间**（submodules 较大）

## 7. 已知问题

- Lorie 的 `cmdentrypoint.cpp` 用 `app_process` 拉起独立进程的方式，本工程改成
  直接在 `XServerActivity` 内的 native 线程跑（更简单，但 Activity 重建会杀 X server）
- MiceWine 的 Wine 兼容 patch 需要手动 review 后合入，脚本只做自动合并的尝试
- ARM64 上的 Box64 + Wine + Lorie 三层调用栈较深，调试时建议开 `BOX64_LOG=1`

## 8. 与上游的差异

| 项 | termux-x11 上游 | com.winfex |
|----|-----------------|-----------|
| 包名 | `com.termux.x11` | `com.winfex.xserver` |
| 默认 DISPLAY | `:0` | `:13` |
| 渲染目标 | 自己的 Activity Surface | 同左 |
| 输入处理 | 触摸手势在 Activity | 同左（但桥到 InputController） |
| 与 Wine 集成 | 无 | WineWrapper 启动前确保 X ready |
| 信号 patch | 无 | 合并 MiceWine 的 Wine 兼容 patch |

## 9. 致谢

本 module 的 Lorie DDX 源码来自 [termux/termux-x11](https://github.com/termux/termux-x11)，
遵循其 GPL-3.0 license。
