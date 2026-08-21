# Android 内置 X Server 方案调研报告

> 面向 `com.winfex` 工程的落地建议。所有结论均基于对上游仓库源码、README、
> patch 文件、GitHub API 元数据的实际核查（截至 2026-08）。

---

## 0. TL;DR（太长不看版）

| # | 方案 | 推荐度 | 一句话结论 |
|---|------|--------|-----------|
| 1 | **Fork Termux-X11 的 Lorie DDX，vendor 进 com.winfex** | ★★★★★ | 唯一能用、不靠外部 APK、走 EGL/AHardwareBuffer、不依赖 SDL 的方案 |
| 2 | XSDL (pelya/xserver-xsdl) KDrive+SDL1.2 | ★★ | 老旧、用 SDL1.2、无硬件 GL、作者已转向 Lorie |
| 3 | 官方 X.org + 自写 DDX | ★ | 等于重新发明 Lorie，工作量 8k+ 行 C |
| 4 | Xwayland | ✗ | Android 上没有可用的 Wayland compositor，不成立 |
| 5 | VNC (Xvfb + x11vnc) | ★ | 性能差、多一层拷贝，仅 fallback |
| 6 | Wine `--with-sdl`（不要 X server） | ✗ | **基于误解**。Wine 没有 SDL 图形驱动，`--with-sdl` 只控音频/手柄 |

**关键澄清**：MiceWine 的 "Lorie 实现" 其实就是从 **termux/termux-x11** vendor 过来
的（MiceWine 把 `.cpp` 改写成 `.c` 并裁剪了功能）。Termux-X11 才是 Lorie 的上游和
正主。你说的"不用 MiceWine 的 Lorie"完全合理（MiceWine 那份是裁剪过的 C 版本），
但**不要因此排除 Termux-X11 的上游 Lorie**——它是目前 Android 上唯一活跃维护、
基于官方 xorg-xserver、EGL 渲染、支持 DRI3/Present 零拷贝的 X server DDX。

**最终推荐**：Fork `termux/termux-x11` 的 `lorie/` 模块，作为 Gradle module 嵌入
com.winfex，DISPLAY 默认 `:13`，socket 走 `$TMPDIR/.X11-unix/X13`，无需 root。
详见第 6 节。

---

## 1. Android 上能跑的开源 X Server 实现方案对比

### 1.1 pelya/xserver-xsdl（XSDL）

- 仓库：https://github.com/pelya/xserver-xsdl
- 默认分支：`xsdl-1.20`，**最近 commit 2026-08-19**（仍活跃，但主要是修编译）
- 357 stars，90 MB，16,947 commits
- 架构：X.Org **1.20** 全源码 + `hw/kdrive/sdl/` 自定义 KDrive DDX
- 渲染：SDL 1.2 `SDL_SetVideoMode()` → 每帧把 X framebuffer 拷到 SDL surface →
  SDL 内部上传成 GLES1 纹理 → `SDL_Flip()`。**无硬件 OpenGL 加速**（README 明确写）
- 输入：`hw/kdrive/sdl/sdl_input.c`（13.7 KB）把 SDL 事件转 KDrive 鼠标/键盘
- 共享内存：依赖 `android-shmem`（pelya 自己写的 SysV SHM → ashmem 模拟）
- 编译：autotools，`android/build.sh` 是个 34 KB 的怪物脚本，下 50+ 个 tarball
  交叉编译整个 X 依赖树
- 产物：`libapplication-$ARCH.so`，被 commandergenius 的 SDL Java 壳用
  NativeActivity 加载
- DISPLAY：默认 `:0`，可改命令行参数
- **致命缺点**：依赖 SDL 1.2（你已经要抛弃 SDL 走 Turnip/Vulkan）；无 GLX 硬件
  加速；作者 pelya 本人已把精力转到 termux-x11

### 1.2 termux/termux-x11（Lorie）—— 真正的现代方案

- 仓库：https://github.com/termux/termux-x11
- **最近 commit 2026-08-20**，4.6k stars，极度活跃
- 架构：**官方 `gitlab.freedesktop.org/xorg/xserver.git` 原样 submodule** + 自己的
  `lorie/` DDX（`InitOutput.c` / `InitInput.c` / `renderer.cpp` / `activity.cpp`）
- 渲染：**EGL + GLES2**，通过 `AHardwareBuffer` + `eglGetNativeClientBufferANDROID`
  做零拷贝 GPU 纹理共享，`AChoreographer` 帧同步，渲染到 `ANativeWindow`
  （Android Surface）—— **完全符合你"走 EGL/Vulkan 不要 SDL"的要求**
- 加速：实现了 **EXA 2D 加速** + **DRI3 + Present** + **GLX server**（可挂 Zink
  把 OpenGL→Vulkan，配合你的 Turnip 完美）
- 输入：`InitInput.c` + `InputXKB.c`（共 ~42 KB），触摸→鼠标/键盘手势在
  `activity.cpp` 里做（支持 touchpad / simulated touchscreen 两种模式）
- 进程模型：**X server 和 Android Activity 分两个进程**，通过 Unix socket
  （端口 7892）+ memfd 共享状态通信。这样 Activity 被杀时 X server 不死
- 字体：xkbcomp + xkeyboard-config 走子模块编译，运行时从 `$XKB_CONFIG_ROOT` 读
- socket：patch Xtrans 让 `UNIX_DIR` 变成运行时可配的变量，启动时指向
  `$TMPDIR/.X11-unix`（见第 4 节）
- 产物：APK 里包含 `libXserver.so` + 一堆依赖 .so + Java Activity
- DISPLAY：可配置，`termux-x11 :1`、`:13` 都行

### 1.3 pelya/commandergenius 里的 xserver 子目录

- 用户给的 URL `master` 分支不存在。commandergenius 默认分支是 `sdl_android`
- 正确路径：`project/jni/application/xserver/`（不是 `project/jni/xserver/`）
- 里面是 XSDL 的 **Android 应用壳**：`main.c` + `gfx.c` + `AndroidBuild.sh` +
  `AndroidAppSettings.cfg` + `xserver`（submodule 指向 pelya/xserver-xsdl）
- `AndroidBuild.sh` 就是调用 xserver-xsdl 的 `android/<arch>/build.sh`
- **不能独立编译**，必须配合 commandergenius 的整个 SDL 1.2 移植层
- 本质上就是 XSDL 的构建脚手架，不是独立 X server 实现
- 用户记忆中"老的 Android X server 移植"指的就是这个，现在已被 xserver-xsdl
  仓库分离出去

### 1.4 官方 X.Org xserver

- 仓库：https://gitlab.freedesktop.org/xorg/xserver
- **不能直接交叉编译到 Android**，必须：
  1. 加一个 DDX（hw/ 下的设备相关层）—— Lorie 就是这个 DDX
  2. patch os/ 信号处理、Xtrans socket 路径、shm 等（见第 4 节）
  3. 交叉编译 pixman、libxfont2、libxshmfence、xkbcomp、xkeyboard-config 等
     一整套依赖
- 裸用官方源码 = 自己重写 Lorie。**不推荐**

### 1.5 TinyX / KDrive

- 历史上 Keith Packard 写的轻量 X server，Android-X86 早期用过 `Xvesa`
- 现在官方 xserver 里 `hw/kdrive/` 只剩 `ephyr`（在 X 里跑 X）和 `src/`
- XSDL 用的就是 KDrive 的 `hw/kdrive/sdl/` backend
- tinycorelinux/tinyx 是 1.2.0 时代的 fork，**只支持 X86 + VESA**，对 ARM/Android
  无意义
- **Android 上跑 KDrive = 跑 XSDL**，没有别的选择

### 1.6 XSDL (X Server SDL)

见 1.1。注意 SourceForge 上的 `XServer-XSDL-1.11.40.apk` 是 X.Org 1.11 时代的老
版本，Google Play 上的 XSDL 也长期不更新。xserver-xsdl 仓库本身在 1.20 分支上
偶尔修编译，但功能基本停滞。

### 1.7 VNC + x11vnc 方案

- 跑 `Xvfb`（无头 framebuffer X server）+ `x11vnc` 把 framebuffer 暴露成 VNC +
  Android 端 VNC client（bVNC 等）
- 优点：所有组件都纯 Linux，容易交叉编译
- 缺点：
  - **性能差**：每帧 framebuffer 要走 VNC 协议（RFB），即使本地也要 encode/decode
  - **无硬件 GL**：Xvfb 没 GLX，Wine 的 d3d→GL→Vulkan 链断了
  - 多一个 VNC client APK 依赖（违背"不依赖外部 APK"）
- 仅作为 last-resort fallback，不推荐

### 1.8 Xwayland

- Xwayland 本身**是个 X server**，但它需要一个 **Wayland compositor** 作为后端
- Android 上**没有可用的 Wayland compositor**（SurfaceFlinger 不是 Wayland）
- 理论上可以跑 `sway`/`weston` 在 Android 上，但这比直接跑 X server 还复杂
- MiceWine 早期 Issue 里有人提过 Xwayland 路线，最终放弃
- **不可行**

### 1.9 方案对比总表

| 方案 | 维护 | 代码量 | 编译难度 | 依赖 | DISPLAY 可配 | 渲染到 Surface |
|------|------|--------|----------|------|--------------|----------------|
| **Lorie (termux-x11)** | 极活跃 | ~8k 行 C/C++ DDX + 上游 xserver | 中（CMake，有现成 recipes） | EGL, AHardwareBuffer, android-shmem | ✓ 任意 | EGL/GLES2 → ANativeWindow |
| XSDL (pelya) | 勉强 | KDrive SDL ~2k 行 + 全树 | 高（autotools 怪物脚本） | SDL 1.2, GLES1 | ✓ | SDL1.2 → GLES1 纹理 |
| 官方 X.org 裸用 | 活跃 | 0（要自己写 DDX） | 极高 | 同 Lorie | ✓ | 自己实现 |
| TinyX/KDrive | 死 | - | - | X86 only | - | - |
| Xwayland | 活跃 | - | 需 Wayland compositor | Wayland | ✓ | 不可行 |
| VNC | 活跃 | 少 | 低 | VNC client | ✓ | 软件拷贝 |

---

## 2. XSDL (pelya/xserver-xsdl) 架构深挖

既然你点名要查，这里给详细信息，但结论是**不推荐用**。

### 2.1 基本信息
- 仓库 https://github.com/pelya/xserver-xsdl，分支 `xsdl-1.20`
- 最近 commit `fd07b29` (2026-08-19) "libunwind spoils me a build"——只是修编译
- 357 stars，commits 16,947（包含整个 xorg 历史）

### 2.2 渲染管线
```
X app --(MIT-SHM/SHM)--> X framebuffer (内存)
                              │ 每帧拷贝
                              ▼
                         SDL surface (SDL_SetVideoMode 返回)
                              │ SDL_Flip() 内部
                              ▼
                         GLES1 纹理 → Android Surface
```
- 用的是 **SDL 1.2**（不是 SDL2），`#include <SDL/SDL.h>`
- 渲染入口 `hw/kdrive/sdl/sdl.c`（15.6 KB）
- **没有 GLX**，所以 Wine 的 wined3d→OpenGL 走不通，只能纯软件渲染
- README 原话："No OpenGL hardware acceleration, even using OpenGL ES internally"

### 2.3 输入处理
- `hw/kdrive/sdl/sdl_input.c`（13.7 KB）：SDL 键盘/鼠标事件 → KDrive KdPointer/KdKeyboard
- `hw/kdrive/sdl/sdl_send_text.c`（5.4 KB）：文本输入（hacky，README 自己承认）
- `hw/kdrive/sdl/sdl_screen_buttons.c`（5.6 KB）：屏幕上的虚拟按键
- 触摸→鼠标转换在 SDL 层做（commandergenius 的 SDL 移植里有 `SDL_screenkeyboard`）

### 2.4 内部 X.org 版本
- X.Org Server **1.20.x**（2018-2020 时代），`configure.ac` 顶上写着
- 已经落后主线 2-3 个大版本（主线现在是 24.x）

### 2.5 编译产物
- 不是单一 .so，是 `libapplication-$ARCH.so`（被 SDL 的 Java NativeActivity 加载）
- 还要附带一堆：busybox、xhost、xkbcomp、xloadimage、xsel（静态链接的 Linux 二进制）
- 整个打包成 APK，靠 commandergenius 的 SDL Java 壳跑

### 2.6 能否只取 X server 部分做 .so？
- 理论上可以：`hw/kdrive/sdl/` + `dix/` + `fb/` + `os/` 等编译成 `libXserver.so`
- 但你还得自己写 Java 层 + 输入桥，等于把 commandergenius 的 SDL 壳重写一遍
- **而且还是 SDL1.2 + 无 GLX**，对你没好处

### 2.7 DISPLAY 分配
- 命令行参数 `-display :N` 可配，默认 `:0`

### 2.8 结论
**不要用 XSDL**。它用的 SDL1.2 和你工程方向冲突，没有 GLX 硬件加速，
作者精力已转到 termux-x11。它唯一的历史价值是证明了"Android 上能跑完整 X.org"。

---

## 3. pelya/commandergenius 的 xserver 子目录

- **正确路径**：`project/jni/application/xserver/`（在 `sdl_android` 分支，不是 master）
- 内容：
  - `AndroidBuild.sh`（4 KB）—— 调用 xserver-xsdl 的 `android/<arch>/build.sh`
  - `main.c` + `gfx.c` —— SDL↔X server 的 C 桥
  - `AndroidAppSettings.cfg` —— commandergenius 通用 APK 配置
  - `xserver` —— git submodule，指向 `https://github.com/pelya/xserver.git`
    （= pelya/xserver-xsdl，GitHub 会重定向）
  - `pulseaudio/` —— 可选的 PulseAudio 集成
  - `screen-keys/` —— 屏幕虚拟键盘资源
- **不是独立代码**，是 XSDL 的 Gradle/NDK 构建脚手架
- **不是 KDrive fork**，它链接的是上游 X.org + `hw/kdrive/sdl/`（KDrive 的 SDL backend）
- 代码量：`main.c`+`gfx.c` 合计约 1-2k 行，其余都是 xserver-xsdl 的
- **不能独立编译**，必须 clone 整个 commandergenius 跑 `build.sh`

---

## 4. Android 上启动 X server 的关键问题

### 4.1 socket 路径 `/tmp/.X11-unix/X0`

Android 没有 `/tmp`。三种解法：

1. **patch Xtrans（Lorie 用的方案，推荐）**
   - termux-x11 的 `patches/Xtrans.patch` 把硬编码的
     `#define UNIX_DIR "/tmp/.X11-unix"` 改成运行时变量
     `extern char* xtrans_unix_dir_x11;`
   - DDX 启动时把变量设成 `$TMPDIR/.X11-unix`
   - **X server 和 libX11（Wine 链的）都要打这个 patch**
   - 客户端（Wine winex11.drv）也读同一个变量 → 自动对齐
   - com.winfex 已设 `TMPDIR=<cacheDir>/tmp`，所以 socket 会落在
     `/data/data/com.winfex/cache/tmp/.X11-unix/X13`

2. **抽象 socket `@/tmp/.X11-unix/X13`**
   - Linux 抽象 namespace socket 不需要文件系统路径，Android 原生支持
   - X.org 默认**同时**创建文件系统 socket 和抽象 socket
   - 但 Wine/Box64 里的 libX11 要能连抽象 socket（默认会连）
   - 问题：跨 mount namespace/proot 时抽象 socket 不可见，所以 Lorie 选了方案 1

3. **符号链接 `/tmp` → `$TMPDIR`**：需要 root，不推荐

### 4.2 是否需要 root 创建 `/tmp/.X11-unix/`？

- **不需要**。用方案 1（Xtrans patch）把目录指到 app 私有目录
  `/data/data/com.winfex/cache/tmp/.X11-unix/`，app 自己有写权限
- Xtrans.patch 还把 `geteuid() != 0` 的检查改成 `if (0)`，跳过 root 权限校验

### 4.3 bionic libc 缺失的 syscall

bionic 不支持以下 X server 需要的 POSIX 接口：

| 接口 | 用途 | 解法 |
|------|------|------|
| **SysV SHM**（shmget/shmat/shmdt） | X MIT-SHM 扩展、字体缓存 | 链接 `libandroid-shmem`（pelya 或 termux 版，用 ashmem/memfd 模拟） |
| **robust mutex** | X server↔renderer 共享锁 | Lorie 用 `pthread_mutex_timedlock` + 超时重置模拟（lorie.h 注释明确说） |
| **mqueue** | 不用 | - |
| `SIGRTMIN` 行为差异 | Wine 自己处理 | 与 X server 无关 |
| `geteuid()==0` 假设 | Xtrans 创建 socket 目录 | patch 掉 |

bionic 本身的 syscall 覆盖对 X server 够用，主要坑就是 SysV SHM。
官方说明：https://android.googlesource.com/platform/ndk/.../SYSV-IPC.TXT
"Android does not support System V IPCs by design"

### 4.4 字体目录、rgb.txt

- **字体**：现代 X.org 默认用 `libxfont2` + 内置 bitmap 字体 + fontconfig。Lorie 把
  `xkbcomp` + `xkeyboard-config` 编进 APK，运行时设 `XKB_CONFIG_ROOT`
- `rgb.txt`：X.org 1.20+ 已不强制，编译时 `--disable-rgb`，颜色走 RENDER 扩展
- Lorie 在 `dix-config.h.in` 里把这些裁掉了

### 4.5 /dev/mem 内存映射

- **不需要**。X server 历史上用 `/dev/mem` 直接访问显卡 framebuffer（XF86 特权
  操作），但 Lorie 走 EGL/AHardwareBuffer，完全不碰 `/dev/mem`
- KDrive 也不需要。只有老的 `Xorg` + xf86视频驱动才要 root 开 `/dev/mem`

### 4.6 信号处理冲突（Wine + X server 同进程/同进程组）

MiceWine 的 `patches/xserver.patch` 里有关键改动：
```c
// os/osinit.c
- int siglist[] = { SIGSEGV, SIGQUIT, SIGILL, SIGFPE, SIGBUS, SIGABRT, SIGSYS, ... }
+ int siglist[] = { SIGQUIT, SIGILL, SIGFPE, SIGXCPU, ... }
```
因为 Wine/Box64 要自己装 SIGSEGV/SIGBUS handler 做 JIT 异常恢复，X server 不能
抢。**如果你把 X server 和 Wine 跑在同一进程组，这个 patch 必须打**。

---

## 5. 多个 X server 共存

### 5.1 socket 路径冲突？

- 假设系统上 XSDL APK 在 `:0`，我们的 Lorie 在 `:13`
- socket 文件分别是 `$TMPDIR_A/.X11-unix/X0` 和 `$TMPDIR_B/.X11-unix/X13`
- **即使 TMPDIR 相同**（都是 `/data/data/.../cache/tmp`），不同 app 的 cache 目录
  本来就隔离（Android sandbox），物理上不冲突
- 即使同 app 内跑两个 display，`:0` 和 `:13` 也是不同文件名，不冲突
- 抽象 socket `@/tmp/.X11-unix/X0` vs `@/tmp/.X11-unix/X13` 也不冲突
- ✓ **完全隔离**

### 5.2 Wine 怎么找到 `:13`？

- `DISPLAY=:13` 环境变量。com.winfex 现在的 `WineWrapper.kt:177` 写死 `:0`，
  改成 `:13` 即可
- libX11 解析 `DISPLAY=:13` → 连 `$TMPDIR/.X11-unix/X13`（打了 Xtrans patch）
- **Wine 自己不关心 display 号**，它只调 libX11，libX11 负责连接

### 5.3 抽象 socket 会怎样？

- 如果 Wine 在 proot/chroot 里跑，`$TMPDIR` 可能映射到不同路径
- 此时文件系统 socket 路径不一致，连不上
- 抽象 socket `@/tmp/.X11-unix/X13` 跨 mount namespace 可见（但跨 netns 不可见）
- Lorie 的解法：让 X server 和 Wine 共享同一个 `$TMPDIR`（com.winfex 已经这么做了）
- termux-x11 README 明确说：proot 要用 `--shared-tmp`，chroot 要设 `TMPDIR`

### 5.4 com.winfex 的具体情况

你的 `WineWrapper.kt` 已设：
```kotlin
env["DISPLAY"] = ":0"                      // 改成 ":13"
env["XDG_RUNTIME_DIR"] = cacheDir          // /data/data/com.winfex/cache/tmp
env["TMPDIR"] = "${cacheDir}/tmp"          // 同上
```
只要把 DISPLAY 改成 `:13`，X server 和 Wine 都用同一个 TMPDIR，socket 自动对齐。
**不需要改 WineWrapper 的 TMPDIR 逻辑**。

---

## 6. 推荐方案：Fork Termux-X11 Lorie，vendor 进 com.winfex

### 6.1 为什么是 Termux-X11 Lorie 而不是别的

| 你的要求 | Lorie 满足度 |
|---------|-------------|
| 不用 MiceWine/Lorie | ⚠ 需澄清：MiceWine 的 Lorie 是从 termux-x11 vendor 的裁剪 C 版。用 termux-x11 原版 C++ Lorie ≠ 用 MiceWine 的。**强烈建议重新评估这条约束** |
| 不用 root | ✓ 全程 app 私有目录 |
| 编译为 .so | ✓ `libXserver.so` + 依赖 .so 一组 |
| DISPLAY 可配（默认 :13） | ✓ 命令行参数 |
| 渲染走 EGL/Vulkan 不要 SDL | ✓ EGL/GLES2 + AHardwareBuffer，零 SDL |
| 触摸转鼠标键盘 | ✓ activity.cpp 内置 touchpad/simulated 两种模式 |
| 代码量小 | ✓ DDX ~8k 行 C/C++，其余是上游 xserver（不改动） |
| 塞进 com.winfex | ✓ 作为 Gradle module 引入 |

### 6.2 如果"绝对不能用 Lorie 这个名字/代码"

那只剩两条路：
1. **基于 Lorie 的设计重写一个 DDX**（~8k 行 C，工作量 2-3 人月，没意义）
2. **用 XSDL + 自己加 GLX**（等于把 Lorie 干的事重做一遍，更没意义）

现实点说：**Android 上做内置 X server，Lorie 是唯一活路**。MiceWine、Winlator、
termux-x11 三个主流项目全用它。你的"不用 MiceWine 的 Lorie"应该理解为"不用 MiceWine
那份裁剪过的 C 版本，用上游 termux-x11 的完整 C++ 版本"。

### 6.3 具体落地步骤

#### Step 1: Fork termux-x11，抽出 lorie 模块

```bash
git clone --recurse-submodules https://github.com/termux/termux-x11
cd termux-x11
# lorie/ 是个独立 Gradle module，自带 build.gradle
```

关键路径：
- `lorie/src/main/cpp/lorie/` —— DDX 本体（8 个 .c/.cpp + fbconfigs.h）
- `lorie/src/main/cpp/xserver` —— submodule，上游 xorg-xserver
- `lorie/src/main/cpp/{pixman,libxfont,libxshmfence,...}` —— 依赖 submodule
- `lorie/src/main/cpp/patches/` —— 必须的 patch（Xtrans, xserver, xkbcomp, ...）
- `lorie/src/main/cpp/recipes/*.cmake` —— 各依赖的 CMake 构建脚本
- `lorie/src/main/cpp/CMakeLists.txt` —— 顶层构建入口
- `lorie/src/main/java/com/termux/x11/` —— Android Activity + JNI

#### Step 2: 把 lorie/ 复制成 com.winfex 的子 module

把 `lorie/` 改名为 `xserver/`，集成进 com.winfex 工程：
```
winfex/
├── app/                          ← 你现有的主 app
├── xserver/                      ← 新增，从 termux-x11/lorie/ 复制
│   ├── build.gradle
│   ├── src/main/cpp/
│   │   ├── CMakeLists.txt
│   │   ├── lorie/                ← DDX 源码
│   │   ├── xserver/              ← submodule
│   │   ├── pixman/ ...           ← 依赖 submodule
│   │   ├── patches/
│   │   └── recipes/
│   └── src/main/java/com/winfex/xserver/  ← Activity（改名）
└── settings.gradle.kts           ← 加 include(":xserver")
```

#### Step 3: 改包名 & Activity 名

- `com.termux.x11` → `com.winfex.xserver`
- `MainActivity` → `XServerActivity`（或直接复用你现有 MainActivity）
- `CmdEntryPoint` → 你的 `NativeBridge` 里加个 `startXServer(display: Int)` 方法

#### Step 4: 改默认 DISPLAY

在 `InitOutput.c` 的命令行解析处，或 `activity.cpp` 启动 X server 时：
```cpp
// 默认 argv 里传 :13
const char* defaultDisplay = ":13";
```
对应 Kotlin：
```kotlin
// WineWrapper.kt:177
env["DISPLAY"] = ":13"   // 原来是 ":0"
```

#### Step 5: 让 X server 跑在独立进程

Lorie 默认用 `CmdEntryPoint` 通过 `app_process` 拉起 X server 进程（参考
termux-x11 README 的 chroot 部分）。com.winfex 可以：
- **简单做法**：在 `WineRunnerService` 里 fork 一个子进程跑 X server，
  设 `TMPDIR`/`DISPLAY` 后再 fork Wine
- **Lorie 原生做法**：用 `am start` 拉起 `XServerActivity`，Activity 内 native
  线程跑 X server 主循环，渲染到 Activity 的 Surface

推荐 Lorie 原生做法，因为它已经处理好 Activity 生命周期 + Surface 重建。

#### Step 6: 编译

```bash
cd winfex
./gradlew :xserver:assembleDebug
# 产物：xserver/build/outputs/aar/xserver-debug.aar
# 内含 jni/arm64-v8a/libXserver.so + 依赖 .so + classes.jar
```

com.winfex 主 app `build.gradle.kts` 加：
```kotlin
dependencies {
    implementation(project(":xserver"))
}
```

### 6.4 关键源码文件清单（你要重点看的）

| 文件 | 作用 | 大小 |
|------|------|------|
| `lorie/src/main/cpp/lorie/InitOutput.c` | DDX 主入口，screen/pixmap/exa/dri3/present | 51 KB |
| `lorie/src/main/cpp/lorie/InitInput.c` | 鼠标/键盘设备初始化 | 11 KB |
| `lorie/src/main/cpp/lorie/InputXKB.c` | XKB 键盘映射 | 30 KB |
| `lorie/src/main/cpp/lorie/renderer.cpp` | EGL/GLES2 渲染器，AHardwareBuffer | 52 KB |
| `lorie/src/main/cpp/lorie/activity.cpp` | Android Activity JNI 桥，触摸手势 | 22 KB |
| `lorie/src/main/cpp/lorie/cmdentrypoint.cpp` | 命令行入口，进程拉起 | 24 KB |
| `lorie/src/main/cpp/lorie/buffer.c` + `.h` | LorieBuffer 共享缓冲 | 27 KB |
| `lorie/src/main/cpp/lorie/clipboard.c` | 剪贴板同步 | 18 KB |
| `lorie/src/main/cpp/lorie/lorie.h` | 核心头文件，事件枚举，mutex 工具 | 21 KB |
| `lorie/src/main/cpp/lorie/fbconfigs.h` | GLX fbconfig 表（生成） | 256 KB |
| `lorie/src/main/cpp/patches/xserver.patch` | xorg 信号/SHM/xkb 符号 patch | 20 KB |
| `lorie/src/main/cpp/patches/Xtrans.patch` | socket 路径可配化 | 1.5 KB |
| `lorie/src/main/cpp/CMakeLists.txt` | 顶层构建 | - |
| `lorie/src/main/cpp/recipes/xserver.cmake` | xserver 编译配方 | - |

### 6.5 需要从 MiceWine/xserver.patch 借鉴的额外 patch

MiceWine 的 `patches/xserver.patch` 比 termux-x11 的多了几条针对 Wine 共存的改动，
**建议合并过来**：

1. `os/osinit.c` 去掉 SIGSEGV/SIGBUS/SIGABRT/SIGSYS（Wine/Box64 要用）
2. `dix/main.c` 加 `ddxReady()` 调用（让 DDX 在 server ready 时通知父进程）
3. `Xext/shmint.h` 暴露 `ShmGetDevPrivateKeyRec()`（DDX 要拿 SHM pixmap key）
4. `include/xkbfile.h` 把 Xkb* 符号重命名成 XXkb*（避免和 libxkbfile 链接冲突）

### 6.6 编译步骤总结

```bash
# 1. clone termux-x11（含 submodule）
git clone --recurse-submodules https://github.com/termux/termux-x11
cd termux-x11

# 2. 验证能原样编译
./gradlew :lorie:assembleDebug
# 产物在 lorie/build/outputs/apk/debug/

# 3. 复制 lorie/ 到 winfex 工程，改名 xserver/
cp -r lorie /path/to/winfex/xserver

# 4. 改包名 com.termux.x11 → com.winfex.xserver（全局替换）
cd /path/to/winfex/xserver
git ls-files | xargs sed -i 's/com\.termux\.x11/com.winfex.xserver/g'

# 5. settings.gradle.kts 加 include(":xserver")
echo 'include(":xserver")' >> /path/to/winfex/settings.gradle.kts

# 6. app/build.gradle.kts 加依赖
# dependencies { implementation(project(":xserver")) }

# 7. 改默认 DISPLAY 为 :13
# lorie/src/main/cpp/lorie/cmdentrypoint.cpp 里默认参数
# WineWrapper.kt 里 env["DISPLAY"] = ":13"

# 8. 合并 MiceWine 的 xserver.patch 里 Wine 相关改动到
#    xserver/src/main/cpp/patches/xserver.patch

# 9. 编译整个工程
cd /path/to/winfex
./gradlew assembleDebug
```

---

## 7. 简化方案（Wine SDL2 backend）——不可行，基于误解

### 7.1 误解澄清

你提的"把 Wine 编译成 SDL2 backend（--with-sdl），Wine 直接通过 SDL2 创建窗口"
**基于一个常见误解**。实际情况：

- Wine 的 `configure` 确实有 `--with-sdl` / `--without-sdl`
- 但这个开关**只控制**：
  - `sdl2` **音频驱动**（dlls/sdl2.drv/，音频输出）
  - `sdl2` **手柄/joystick 输入驱动**（dlls/winexinput.drv 的一部分）
- Wine **没有** `winesdl.drv` 这样的图形显示驱动
- Wine 的图形驱动只有：`winex11.drv`、`winewayland.drv`、`winemac.drv`、
  `wineandroid.drv`、`winetest`、`wineconsole`
- WineHQ 官方论坛明确回答（2011，至今未变）：
  > "As-is - no, it's not possible. SDL is not X11 compatible
  >  (it's not a drop-in replacement for X11)."

### 7.2 那 Wine 的 SDL2 不是能创建窗口吗？

能，但那是 **Windows 程序用了 SDL2**（比如游戏链接 SDL2.dll）时，SDL2 自己用
Android backend 创建窗口。这是 SDL2 的事，不是 Wine 的事。Wine 自己的 GUI
（explorer.exe 桌面、winecfg、所有 GDI 程序）**必须**走 winex11.drv 或
wineandroid.drv。

### 7.3 wineandroid.drv 呢？

- Wine **有**原生的 `wineandroid.drv`（`dlls/wineandroid.drv/`）
- 历史上长期半死不活，Wine 7.11 (2022) 转成 PE，之后基本停滞
- **Wine 11.6 (2026-04) 刚开始"reviving its Android driver"**——还没成熟
- 它的设计是让 Wine 直接调 Android WindowManager/ SurfaceFlinger，不经过 X11
- 但目前功能不完整，MiceWine/Winlator 都没用它
- **未来可能可行，但现在（2026-08）不能用**

### 7.4 为什么 MiceWine 显式 `--without-sdl`？

两个原因（都不意味着 MiceWine 用 SDL 做图形）：
1. **避免符号冲突**：MiceWine 的 X server（Lorie）和 Wine 可能都链 SDL2，
   去掉 Wine 侧的 SDL 减少冲突
2. **没用上**：MiceWine 用 Turnip+Vulkan，音频走 PulseAudio（不靠 SDL2 audio），
   手柄走 Android InputController（不靠 SDL2 joystick），所以 SDL2 整个没用了

### 7.5 com.winfex 能走 Wine SDL2 backend 路线吗？

**不能**，因为这条路不存在。你的选择只有：
1. **winex11.drv + 内置 X server（Lorie）** ← 推荐
2. **wineandroid.drv**（等 Wine 11.6+ 成熟，至少半年到一年）
3. **winewayland.drv + 自己写 Android Wayland compositor**（比 X server 还难）

### 7.6 唯一真实的"简化"方向

如果你嫌 Lorie 8k 行代码太多，可以裁剪 Lorie：
- 砍掉 clipboard（省 18 KB）
- 砍掉 DRI3/Present（省 InitOutput.c 一半，但会失去零拷贝，性能下降）
- 砍掉 GLX（省 fbconfigs.h 256 KB，但 Wine 的 OpenGL 走不通，DXVK 仍可用
  因为 DXVK 直接走 Vulkan 不经 GLX）
- 砍掉 EXA（纯软件 fb，慢但简单）

**不建议裁**。Lorie 现在的体积对 com.winfex 这种工程完全可接受，裁了反而维护
成本上升。

---

## 8. 行动清单（Next Actions）

### 立即可做（1-2 天）
- [ ] 在本地 clone termux-x11，原样编译出 APK，验证在你设备上能跑
- [ ] 确认 termux-x11 的 `:13` display 能被一个简单 X client（`xterm` via box64）
      连上
- [ ] 决定：是否接受"用 termux-x11 上游 Lorie"（强烈建议接受）

### 短期（1-2 周）
- [ ] Fork termux-x11，把 `lorie/` module 改名 `xserver/`，集成进 com.winfex
- [ ] 全局替换包名 `com.termux.x11` → `com.winfex.xserver`
- [ ] 合并 MiceWine `xserver.patch` 里 Wine 相关的信号/SHM/xkb 改动
- [ ] `WineWrapper.kt` 把 `DISPLAY` 改成 `:13`，启动顺序改为先起 X server 再起 Wine
- [ ] `WineRunnerService` 增加 X server 进程管理

### 中期（1 个月）
- [ ] 把 Lorie 的 Activity UI 融入 com.winfex 的导航（不要两个 Activity 跳来跳去）
- [ ] 输入：把 Lorie 的触摸手势接到你现有的 `InputController.kt`
- [ ] 剪贴板、IME 集成测试
- [ ] 性能 profiling：DXVK + Zink + Turnip + Lorie DRI3 零拷贝是否走通

### 长期可选
- [ ] 评估 Wine 11.6+ `wineandroid.drv` 成熟度，若可行则去掉 X server 层
- [ ] 考虑把 Lorie 的两进程模型改成单进程（省一次 IPC，但 Activity 重建会杀 X server）

---

## 附录 A：关键仓库元数据速查

| 仓库 | 默认分支 | 最近活跃 | Stars | 用途 |
|------|---------|---------|-------|------|
| termux/termux-x11 | master | 2026-08-20 | 4.6k | **推荐 fork** |
| pelya/xserver-xsdl | xsdl-1.20 | 2026-08-19 | 357 | 不推荐，SDL1.2 |
| KreitinnSoftware/MiceWine-Application | master | 2026-07-01 | 1.2k | 借 patch，不借代码 |
| KreitinnSoftware/xserver | main | 2025-03-16 | - | xorg 镜像，不用 |
| pelya/commandergenius | sdl_android | 偶尔 | 578 | XSDL 构建壳，不用 |
| pelya/android-shmem | - | - | - | SysV SHM 模拟，必用 |
| termux/libandroid-shmem | - | - | - | 同上替代品 |
| gitlab.freedesktop.org/xorg/xserver | master | 活跃 | - | 上游 X server |

## 附录 B：bionic libc 已知坑位

1. 无 SysV IPC（shmget/shmat/semget/msgget）→ libandroid-shmem
2. 无 robust mutex（PTHREAD_MUTEX_ROBUST）→ timedlock 模拟（lorie.h）
3. `geteuid()` 对 app 总是非 0 → patch Xtrans 跳过权限检查
4. `tmpfile()` 默认走 `/tmp` → 设 `TMPDIR`
5. `dlopen("libGL.so")` 找不到 → 链 GLES/Zink
6. `fork()` 后子进程失去 JNIEnv → Lorie 两进程模型规避

## 附录 C：com.winfex 现有代码改动点

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/winfex/core/WineWrapper.kt:177` | `env["DISPLAY"] = ":0"` → `":13"` |
| `app/src/main/java/com/winfex/core/WineRunnerService.kt` | 增加 X server 进程启动/等待逻辑 |
| `settings.gradle.kts` | `include(":xserver")` |
| `app/build.gradle.kts` | `implementation(project(":xserver"))` |
| `app/src/main/AndroidManifest.xml` | 注册 `XServerActivity` |
| `app/src/main/cpp/CMakeLists.txt` | 无需改（X server 是独立 module） |
| 新增 `xserver/` module | 从 termux-x11/lorie/ 复制改造 |
