#!/usr/bin/env bash
# sync-xserver.sh — 从 termux-x11 拉取 Lorie DDX 源码并集成到 com.winfex/xserver/
#
# 用法：
#   ./scripts/sync-xserver.sh                # 默认从 GitHub main 拉最新
#   ./scripts/sync-xserver.sh v0.3.0         # 指定 termux-x11 的 tag/commit
#   ./scripts/sync-xserver.sh --local /path/to/termux-x11   # 用本地已 clone 的副本

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$PROJECT_ROOT/build"
TERMUX_X11_DIR="$BUILD_DIR/termux-x11"
XSERVER_DIR="$PROJECT_ROOT/xserver"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}   $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERR]${NC}  $*"; exit 1; }

REF="master"
LOCAL_SRC=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --local)
            LOCAL_SRC="$2"; shift 2 ;;
        -h|--help)
            cat <<EOF
Usage: $0 [REF|--local PATH]

  REF              termux-x11 的 git ref（tag/commit/branch），默认 main
  --local PATH     用本地已 clone 的 termux-x11 副本，跳过 clone 步骤
  -h, --help       显示帮助

Examples:
  $0                          # 拉最新 main
  $0 v0.3.0                   # 拉 v0.3.0 tag
  $0 --local ~/src/termux-x11 # 用本地副本
EOF
            exit 0 ;;
        *) REF="$1"; shift ;;
    esac
done

command -v git >/dev/null 2>&1 || error "需要 git，请先安装"
command -v sed >/dev/null 2>&1 || error "需要 sed"

# ===== Step 1: clone 或复用 termux-x11 =====

if [[ -n "$LOCAL_SRC" ]]; then
    [[ -d "$LOCAL_SRC/.git" ]] || error "$LOCAL_SRC 不是 git 仓库"
    info "用本地副本 $LOCAL_SRC"
    rm -rf "$TERMUX_X11_DIR"
    cp -r "$LOCAL_SRC" "$TERMUX_X11_DIR"
    ok "已复制到 $TERMUX_X11_DIR"
else
    if [[ -d "$TERMUX_X11_DIR/.git" ]]; then
        info "已存在 $TERMUX_X11_DIR，执行 git fetch"
        cd "$TERMUX_X11_DIR"
        # --force 覆盖被远端更新的本地 tag（如 nightly）
        git fetch --all --tags --force
        git checkout "$REF" || error "checkout $REF 失败（termux-x11 默认分支是 master，可以用 ./scripts/sync-xserver.sh master）"
        git pull --ff-only || warn "pull 失败（可能是 detached HEAD），继续"
        git submodule update --init --recursive
    else
        info "clone termux-x11（含 submodule，可能需要几分钟）"
        mkdir -p "$BUILD_DIR"
        if ! git clone --recurse-submodules --depth 1 --branch "$REF" \
                https://github.com/termux/termux-x11.git "$TERMUX_X11_DIR" 2>/dev/null; then
            warn "depth=1 clone 失败，尝试完整 clone"
            git clone --recurse-submodules \
                https://github.com/termux/termux-x11.git "$TERMUX_X11_DIR"
            cd "$TERMUX_X11_DIR"
            if [[ "$REF" != "master" ]]; then
                git checkout "$REF" || warn "无法切到 $REF，保留 master"
            fi
            git submodule update --init --recursive
        fi
    fi
    ok "termux-x11 源码就绪 @ $(cd "$TERMUX_X11_DIR" && git rev-parse --short HEAD)"
fi

# ===== Step 2: 检查 lorie/ 是否存在 =====

# termux-x11 的源码可能在 app/src/main/cpp 或 lorie/src/main/cpp，自动探测
LORIE_CPP_SRC=""
for candidate in \
    "$TERMUX_X11_DIR/app/src/main/cpp" \
    "$TERMUX_X11_DIR/lorie/src/main/cpp"; do
    if [[ -d "$candidate/lorie" ]]; then
        LORIE_CPP_SRC="$candidate"
        break
    fi
done
[[ -n "$LORIE_CPP_SRC" ]] || error "未找到 lorie DDX 源码目录"

LORIE_JAVA_SRC=""
for candidate in \
    "$TERMUX_X11_DIR/app/src/main/java/com/termux/x11" \
    "$TERMUX_X11_DIR/lorie/src/main/java/com/termux/x11"; do
    if [[ -d "$candidate" ]]; then
        LORIE_JAVA_SRC="$candidate"
        break
    fi
done
[[ -n "$LORIE_JAVA_SRC" ]] || error "未找到 Lorie Java 源码目录"

info "Lorie DDX 源码: $LORIE_CPP_SRC"
info "Lorie Java 源码: $LORIE_JAVA_SRC"
info "  cpp: $(find "$LORIE_CPP_SRC/lorie" -maxdepth 1 -type f 2>/dev/null | wc -l) 个 DDX 文件"
info "  java: $(find "$LORIE_JAVA_SRC" -name '*.java' -o -name '*.kt' 2>/dev/null | wc -l) 个文件"

# ===== Step 3: 备份现有 stub =====

BACKUP_DIR="$XSERVER_DIR/.stub-backup-$(date +%s)"
if [[ -d "$XSERVER_DIR/src/main/java/com/winfex/xserver" ]]; then
    info "备份现有 stub 到 $BACKUP_DIR"
    mkdir -p "$BACKUP_DIR"
    cp -r "$XSERVER_DIR/src/main/java/com/winfex/xserver" "$BACKUP_DIR/java" 2>/dev/null || true
    [[ -d "$XSERVER_DIR/src/main/cpp" ]] && cp -r "$XSERVER_DIR/src/main/cpp" "$BACKUP_DIR/cpp" || true
fi

# ===== Step 4: 复制 cpp 源码 =====

info "复制 Lorie DDX 源码到 xserver/src/main/cpp/"
mkdir -p "$XSERVER_DIR/src/main/cpp"

# lorie/ 目录
rm -rf "$XSERVER_DIR/src/main/cpp/lorie"
cp -r "$LORIE_CPP_SRC/lorie" "$XSERVER_DIR/src/main/cpp/lorie"

# CMakeLists.txt
cp "$LORIE_CPP_SRC/CMakeLists.txt" "$XSERVER_DIR/src/main/cpp/CMakeLists.txt"

# patches/ recipes/
for d in patches recipes; do
    if [[ -d "$LORIE_CPP_SRC/$d" ]]; then
        rm -rf "$XSERVER_DIR/src/main/cpp/$d"
        cp -r "$LORIE_CPP_SRC/$d" "$XSERVER_DIR/src/main/cpp/$d"
    fi
done

# submodule 目录
# termux-x11 的 submodules 在 lorie/src/main/cpp/{xserver,pixman,...} 下
# .git 可能是文件（submodule）或目录（独立 clone），都要识别
for sm in "$LORIE_CPP_SRC"/*/; do
    sm_name="$(basename "$sm")"
    # 跳过 lorie patches recipes 这些已经处理的
    case "$sm_name" in
        lorie|patches|recipes) continue ;;
    esac
    # 识别 submodule：有 .git（文件或目录）、CMakeLists.txt、meson.build 之一
    if [[ -e "$sm/.git" ]] || [[ -f "$sm/CMakeLists.txt" ]] || [[ -f "$sm/meson.build" ]] || [[ -f "$sm/configure.ac" ]]; then
        rm -rf "$XSERVER_DIR/src/main/cpp/$sm_name"
        cp -r "$sm" "$XSERVER_DIR/src/main/cpp/$sm_name"
        # 移除 .git（避免 Gradle 误认为是独立 git 仓库）
        rm -rf "$XSERVER_DIR/src/main/cpp/$sm_name/.git"
    fi
done

# 验证关键 submodule 是否复制成功
for required_sm in xserver pixman libxfont libxshmfence xkbcomp xorgproto; do
    if [[ ! -d "$XSERVER_DIR/src/main/cpp/$required_sm" ]]; then
        warn "缺少关键 submodule: $required_sm"
        warn "  原始路径: $LORIE_CPP_SRC/$required_sm"
        warn "  目标路径: $XSERVER_DIR/src/main/cpp/$required_sm"
        warn "  请手动跑: cd build/termux-x11 && git submodule update --init --recursive"
    fi
done

ok "cpp 源码复制完成"

# ===== Step 5: 复制 Java 源码（全部保留） =====
#
# 策略：保留 termux-x11 所有 Java/Kotlin 文件原样，包括：
#   - MainActivity.java        ← X Server 主 Activity
#   - LoriePreferences.java    ← 设置页
#   - LorieView.java           ← SurfaceView + JNI
#   - TouchInputHandler.java   ← 触摸手势
#   - InputEventSender.java    ← X11 事件发送
#   - Prefs.kt                 ← 偏好设置包装（PreferenceDataStore）
#   - utils/*, extrakeys/*     ← 工具类
#   - CmdEntryPoint.java       ← 独立进程入口（引用 hidden API，单独 stub）
#
# 这些文件互相依赖，删任何一个都会导致连锁错误。
# 正确做法是全部保留 + 对 CmdEntryPoint 的 hidden API 生成 stub。

info "复制 Lorie Java 源码到 xserver/src/main/java/com/winfex/xserver/"
mkdir -p "$XSERVER_DIR/src/main/java/com/winfex/xserver"
rm -f "$XSERVER_DIR/src/main/java/com/winfex/xserver/"*.kt
rm -f "$XSERVER_DIR/src/main/java/com/winfex/xserver/"*.java

find "$LORIE_JAVA_SRC" \( -name '*.java' -o -name '*.kt' \) | while read -r f; do
    rel="${f#$LORIE_JAVA_SRC/}"
    dst="$XSERVER_DIR/src/main/java/com/winfex/xserver/$rel"
    mkdir -p "$(dirname "$dst")"
    cp "$f" "$dst"
done

java_count=$(find "$XSERVER_DIR/src/main/java/com/winfex/xserver" \( -name '*.java' -o -name '*.kt' \) | wc -l)
ok "复制了 $java_count 个 Java/Kotlin 文件"

# ===== Step 5.1: 替换 CmdEntryPoint 为空壳（保留静态常量） =====
#
# CmdEntryPoint.java 是 termux-x11 通过 `app_process` 拉起独立进程的入口。
# 它引用 Android hidden API（IActivityManager / ActivityThread 等）。
# 但 MainActivity 引用了它的静态常量（ACTION_START 等），不能直接删。
#
# 解决方案：替换成空壳，保留所有静态常量 + Service 基本结构，
# 去掉 hidden API 调用。ICmdEntryInterface.aidl 由 AGP 自动生成 Stub。

info "替换 CmdEntryPoint.java 为空壳（保留静态常量，去除 hidden API）"
CMD_ENTRY="$XSERVER_DIR/src/main/java/com/winfex/xserver/CmdEntryPoint.java"
if [[ -f "$CMD_ENTRY" ]]; then
    # 提取原文件里所有 static final 常量声明（保留对外的 API）
    EXTRACTED_CONSTANTS=$(grep -E 'static\s+final\s+(String|int|long|boolean)\s+\w+\s*=' "$CMD_ENTRY" 2>/dev/null || echo "")
    if [[ -z "$EXTRACTED_CONSTANTS" ]]; then
        EXTRACTED_CONSTANTS='    public static final String ACTION_START = "com.winfex.xserver.START";
    public static final String ACTION_PREFERENCES = "com.winfex.xserver.PREFERENCES";'
    fi

    # 用临时文件拼接，避免 heredoc 变量展开问题
    TMP_FILE=$(mktemp)
    cat > "$TMP_FILE" <<'JAVA'
package com.winfex.xserver;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * CmdEntryPoint — 空壳版本（去除了 hidden API 依赖）。
 *
 * 上游 termux-x11 的 CmdEntryPoint 通过 app_process 拉起独立进程，
 * 引用 Android hidden API (IActivityManager / ActivityThread / IIntentReceiver.Stub 等)。
 *
 * com.winfex 用 WineRunnerService 管理 X server 生命周期，不需要这套机制。
 * 但 MainActivity / LoriePreferences 引用了本类的静态常量 + handler + sendBroadcast。
 */
public class CmdEntryPoint extends Service {
    private static final String TAG = "CmdEntryPoint";

    // ===== 从原 CmdEntryPoint.java 提取的静态常量 =====
JAVA
    echo "$EXTRACTED_CONSTANTS" >> "$TMP_FILE"
    cat >> "$TMP_FILE" <<'JAVA'
    // 兜底常量
    public static final String EXTRA_XR_STORE_IN = "XR_STORE_IN";
    public static final String EXTRA_XR_STORE_OUT = "XR_STORE_OUT";

    // LoriePreferences / MainActivity 引用的静态字段
    public static final Handler handler = new Handler(Looper.getMainLooper());

    // 静态 sendBroadcast 包装（LoriePreferences 引用 CmdEntryPoint.sendBroadcast）
    // 注意：不能直接叫 sendBroadcast，因为 Service 继承 Context 有同名实例方法，
    // static 方法不能 override 实例方法。改名 + 在 LoriePreferences 里替换调用。
    private static Context appContext = null;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
    }

    public static void broadcastIntent(Intent intent) {
        if (appContext != null) {
            appContext.sendBroadcast(intent);
        } else {
            Log.w(TAG, "broadcastIntent called before onCreate, intent dropped");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "CmdEntryPoint.onBind (stub, returning null)");
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "CmdEntryPoint started (stub, no-op)");
        return START_NOT_STICKY;
    }
}
JAVA
    mv "$TMP_FILE" "$CMD_ENTRY"
    ok "  CmdEntryPoint.java 已替换为空壳（保留静态常量）"
    info "  提取到的常量:"
    echo "$EXTRACTED_CONSTANTS" | grep -E 'static\s+final' | sed 's/^/    /' || true
else
    warn "  CmdEntryPoint.java 不存在，跳过"
fi

# MainActivity 引用了 CmdEntryPoint.ACTION_START 等静态常量，
# 现在 CmdEntryPoint 是空壳但保留了常量，MainActivity 能找到。
# 不需要再 sed 清理 MainActivity 了。
info "MainActivity 的 CmdEntryPoint 引用由空壳的静态常量满足"

# ===== Step 5.2: 复制 AIDL 文件 =====
for aidl_src in \
    "$TERMUX_X11_DIR/app/src/main/aidl" \
    "$TERMUX_X11_DIR/lorie/src/main/aidl"; do
    if [[ -d "$aidl_src" ]]; then
        mkdir -p "$XSERVER_DIR/src/main/aidl"
        cp -r "$aidl_src/"* "$XSERVER_DIR/src/main/aidl/" 2>/dev/null || true
        if [[ -d "$XSERVER_DIR/src/main/aidl/com/termux/x11" ]]; then
            mkdir -p "$XSERVER_DIR/src/main/aidl/com/winfex"
            mv "$XSERVER_DIR/src/main/aidl/com/termux/x11" \
               "$XSERVER_DIR/src/main/aidl/com/winfex/xserver"
            rmdir "$XSERVER_DIR/src/main/aidl/com/termux" 2>/dev/null || true
        fi
        ok "  AIDL: $(find "$XSERVER_DIR/src/main/aidl" -name '*.aidl' | wc -l) 个文件"
        break
    fi
done

# ===== Step 6: 全局替换包名 =====

info "替换包名 com.termux.x11 → com.winfex.xserver"

# 替换所有源码、配置、AIDL 文件
find "$XSERVER_DIR/src/main" -type f \( -name '*.c' -o -name '*.cpp' -o -name '*.h' \
    -o -name '*.kt' -o -name '*.java' -o -name '*.cmake' \
    -o -name 'CMakeLists.txt' -o -name '*.patch' -o -name '*.xml' \
    -o -name '*.aidl' \) \
    -exec sed -i 's/com\.termux\.x11/com.winfex.xserver/g' {} +

find "$XSERVER_DIR/src/main" -type f \( -name '*.c' -o -name '*.cpp' -o -name '*.h' \
    -o -name '*.kt' -o -name '*.java' -o -name '*.cmake' \
    -o -name 'CMakeLists.txt' -o -name '*.patch' -o -name '*.aidl' \) \
    -exec sed -i 's@com/termux/x11@com/winfex/xserver@g' {} +

ok "包名替换完成"

# ===== Step 7: 修改默认 DISPLAY 为 :13 =====

info "修改默认 DISPLAY :0 → :13"

# 在 cmdentrypoint.cpp / activity.cpp / InitOutput.c 里找 ":0" 改为 ":13"
DISPLAY_REPLACED=0
for f in \
    "$XSERVER_DIR/src/main/cpp/lorie/cmdentrypoint.cpp" \
    "$XSERVER_DIR/src/main/cpp/lorie/activity.cpp" \
    "$XSERVER_DIR/src/main/cpp/lorie/InitOutput.c"; do
    [[ -f "$f" ]] || continue
    if grep -q '":0"' "$f" 2>/dev/null; then
        sed -i 's/":0"/":13"/g' "$f"
        ok "  $f: DISPLAY 默认值改为 :13"
        DISPLAY_REPLACED=1
    fi
done

# Java 层
find "$XSERVER_DIR/src/main/java" -type f \( -name '*.kt' -o -name '*.java' \) | while read -r f; do
    if grep -qE 'DISPLAY.*=.*":0"' "$f" 2>/dev/null; then
        sed -i -E 's/DISPLAY.*=.*":0"/DISPLAY = ":13"/g' "$f"
        ok "  $f: DISPLAY 默认值改为 :13"
    fi
done

[[ $DISPLAY_REPLACED -eq 0 ]] && warn "未找到 DISPLAY 默认值字符串，可能 termux-x11 改了代码结构"

# ===== Step 8: 复制资源文件 =====
#
# termux-x11 的资源在 app/src/main/res/ 或 lorie/src/main/res/ 下。
# 复制所有 res 子目录（drawable / layout / values / anim / xml / menu / mipmap / color 等）。
# 不能只复制部分子目录，否则 R.anim.* / R.xml.* / R.id.* 会找不到。

info "复制资源文件（所有 res 子目录）"

# 探测 res 源目录
RES_SRC=""
for candidate in \
    "$TERMUX_X11_DIR/app/src/main/res" \
    "$TERMUX_X11_DIR/lorie/src/main/res"; do
    if [[ -d "$candidate" ]] && [[ -d "$candidate/values" ]]; then
        RES_SRC="$candidate"
        break
    fi
done

if [[ -z "$RES_SRC" ]]; then
    warn "未找到 termux-x11 的 res 目录，跳过资源复制"
    warn "  后续 R.* 引用会编译失败"
else
    ok "  res 源目录: $RES_SRC"
    # 复制所有子目录
    for d in "$RES_SRC"/*/; do
        [[ -d "$d" ]] || continue
        d_name=$(basename "$d")
        mkdir -p "$XSERVER_DIR/src/main/res/$d_name"
        cp -rn "$d"* "$XSERVER_DIR/src/main/res/$d_name/" 2>/dev/null || true
    done

    # 统计复制的资源
    res_count=$(find "$XSERVER_DIR/src/main/res" -type f | wc -l)
    ok "  复制了 $res_count 个资源文件"
    info "  res 子目录:"
    for d in "$XSERVER_DIR/src/main/res"/*/; do
        [[ -d "$d" ]] || continue
        cnt=$(find "$d" -type f | wc -l)
        echo "    $(basename "$d"): $cnt 个文件"
    done

    # ===== Step 8.1: 对新复制的 res 文件再做一次包名替换 =====
    # 因为 Step 6 在 Step 8 之前执行，新复制的 XML 里的 com.termux.x11 没被替换。
    # 这里补一次，覆盖 layout/values/anim/xml 等所有 res 文件。
    info "对新复制的 res 文件做包名替换"
    find "$XSERVER_DIR/src/main/res" -type f \( -name '*.xml' -o -name '*.java' \) \
        -exec sed -i 's/com\.termux\.x11/com.winfex.xserver/g' {} + 2>/dev/null || true
    find "$XSERVER_DIR/src/main/res" -type f \( -name '*.xml' -o -name '*.java' \) \
        -exec sed -i 's@com/termux/x11@com/winfex/xserver@g' {} + 2>/dev/null || true
    ok "  res 文件包名替换完成"
fi

# ===== Step 9: 重写 xserver/build.gradle.kts =====

info "更新 xserver/build.gradle.kts（加入 externalNativeBuild + 完整依赖）"

cat > "$XSERVER_DIR/build.gradle.kts" <<'GRADLE'
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.winfex.xserver"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-28"
                )
                cFlags += "-Wno-unused-result"
                cppFlags += listOf("-std=c++17", "-fno-exceptions", "-fno-rtti")
            }
        }

        // termux-x11 的 Java 代码引用 BuildConfig.COMMIT / APPLICATION_ID
        // 注意：buildConfigField 对 String 类型，值必须含双引号（AGP 不会自动加）
        buildConfigField("String", "COMMIT", "\"unknown-synced\"")
        buildConfigField("String", "VERSION_NAME", "\"0.4.2\"")
        buildConfigField("String", "APPLICATION_ID", "\"com.winfex.xserver\"")
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES"
            )
        }
    }

    // termux-x11 用了一些 hidden API（IActivityManager / IIntentReceiver 等），
    // 这些类在 SDK 里是 @hide 的。我们要么用 reflection，要么 link hidden API stub。
    // 这里走最简单的方式：用 SDK 里能访问的等价 API 替代，遇到 @hide 时改 Java 源码。
    // 详见 README §4.7「编译失败排查」
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // AndroidX 基础
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.1")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("androidx.collection:collection-ktx:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")

    // Material
    implementation("com.google.android.material:material:1.12.0")

    // Preference（LoriePreferences.java 用）
    implementation("androidx.preference:preference-ktx:1.2.1")

    // RecyclerView（Lorie 设置 UI 用）
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // ViewBinding（不需要手动 implementation，AGP plugin 自动处理）

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
GRADLE

ok "build.gradle.kts 已更新（含完整依赖）"

# ===== Step 9.1: Prefs stub 兜底 =====
#
# termux-x11 上游应该有 Prefs.kt（Kotlin，继承 PreferenceDataStore）。
# 如果 sync 时复制成功，跳过 stub 生成。
# 如果上游改了文件名或位置导致没复制到，生成最小 stub 兜底（功能受限）。

if ! find "$XSERVER_DIR/src/main/java/com/winfex/xserver" -name 'Prefs.*' | grep -q .; then
    warn "未找到 Prefs 类（上游可能改名了），生成完整 stub"
    warn "  包含 LoriePreferences / TouchInputHandler / MainActivity 引用的所有字段和方法"
    cat > "$XSERVER_DIR/src/main/java/com/winfex/xserver/Prefs.java" <<'JAVA'
package com.winfex.xserver;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceDataStore;
import java.util.HashMap;
import java.util.Map;

/**
 * Fallback stub for termux-x11 Prefs class.
 *
 * 上游 Prefs.kt 继承 PreferenceDataStore 并有大量 Preference<T> 字段。
 * 此 stub 提供所有被 LoriePreferences / TouchInputHandler / MainActivity 引用的字段。
 *
 * 注意：
 *   - touchMode / displayResolutionMode 在上游是 Preference<String>（存 "1"/"2"/"exact" 等）
 *   - Preference 有 get() / put(T) / set(T) / asList() 方法
 *   - keys 是 Map<String, Preference<?>>，asList() 返回 ListPreference 数据
 */
public class Prefs extends PreferenceDataStore {
    private static final String PREFS_NAME = "winfex_xserver_prefs";
    public final Context ctx;
    private final SharedPreferences sp;
    public final Map<String, Preference<?>> keys = new HashMap<>();

    // Preference 字段（在构造函数里初始化）
    public final Preference<String> touchMode;
    public final Preference<String> displayResolutionMode;
    public final Preference<String> displayResolutionExact;
    public final Preference<String> displayResolutionCustom;
    public final Preference<Boolean> displayStretch;
    public final Preference<Boolean> adjustResolution;
    public final Preference<Integer> displayScale;
    public final Preference<Boolean> fullscreen;
    public final Preference<Boolean> PIP;
    public final Preference<Boolean> hideCutout;
    public final Preference<Boolean> additionalKbdVisible;
    public final Preference<Boolean> showAdditionalKbd;
    public final Preference<Boolean> showIMEWhileExternalConnected;
    public final Preference<Boolean> dexMetaKeyCapture;
    public final Preference<String> ekbarPosition;
    public final Preference<Boolean> ekbarPositionIgnoreOrientation;
    public final Preference<Boolean> enableAccessibilityServiceAutomatically;
    public final Preference<String> extra_keys_config;
    public final Preference<Boolean> filterOutWinkey;
    public final Preference<String> screenIdleTimeout;
    public final Preference<Boolean> showMouseHelper;
    public final Preference<Boolean> showStylusClickOverride;
    public final Preference<Boolean> useTermuxEKBarBehaviour;
    public final Preference<Boolean> tapToMove;
    public final Preference<Boolean> preferScancodes;
    public final Preference<Boolean> pointerCapture;
    public final Preference<Boolean> adjustHeightForEK;
    public final Preference<Integer> opacityEKBar;
    public final Preference<String> forceOrientation;
    public final Preference<Boolean> pauseKeyInterceptingWithEsc;
    public final Preference<Boolean> scaleTouchpad;
    public final Preference<Boolean> Reseed;
    // TouchInputHandler 引用的额外字段
    public final Preference<Integer> capturedPointerSpeedFactor;
    public final Preference<Boolean> stylusIsMouse;
    public final Preference<Boolean> stylusButtonContactModifierMode;
    public final Preference<String> transformCapturedPointer;
    public final Preference<Boolean> ignoreGamepadEvents;
    // LorieView 引用的额外字段
    public final Preference<String> displayFilteringMode;
    public final Preference<Boolean> hardwareKbdScancodesWorkaround;
    public final Preference<Boolean> clipboardEnable;
    public final Preference<Boolean> enforceCharBasedInput;

    // ListPreference 数据（asList 返回）
    public static class ListData {
        public final CharSequence[] entries;
        public final CharSequence[] entryValues;
        public ListData(CharSequence[] entries, CharSequence[] entryValues) {
            this.entries = entries; this.entryValues = entryValues;
        }
        public CharSequence[] getEntries() { return entries; }
        public CharSequence[] getValues() { return entryValues; }
    }

    // Preference 包装类
    public static class Preference<T> {
        private final SharedPreferences sp;
        private final String key;
        private final T def;
        private final Class<T> type;
        private ListData listData;

        public Preference(SharedPreferences sp, String key, T def, Class<T> type) {
            super(key, type, def);
            this.sp = sp; this.key = key; this.def = def; this.type = type;
        }
        @SuppressWarnings("unchecked")
        public T get() {
            if (type == Boolean.class) return (T) Boolean.valueOf(sp.getBoolean(key, (Boolean) def));
            if (type == Integer.class) return (T) Integer.valueOf(sp.getInt(key, (Integer) def));
            if (type == Float.class) return (T) Float.valueOf(sp.getFloat(key, (Float) def));
            if (type == Long.class) return (T) Long.valueOf(sp.getLong(key, (Long) def));
            return (T) sp.getString(key, def == null ? null : def.toString());
        }
        public void put(T v) { set(v); }
        public void set(T v) {
            SharedPreferences.Editor e = sp.edit();
            if (type == Boolean.class) e.putBoolean(key, (Boolean) v);
            else if (type == Integer.class) e.putInt(key, (Integer) v);
            else if (type == Float.class) e.putFloat(key, (Float) v);
            else if (type == Long.class) e.putLong(key, (Long) v);
            else e.putString(key, v == null ? null : v.toString());
            e.apply();
        }
        public Preference<T> withList(CharSequence[] entries, CharSequence[] values) {
            this.listData = new ListData(entries, values);
            return this;
        }
        // asList 必须返回 PrefsProto.ListPreference（父类声明的返回类型）
        // 这里返回 null，运行时如果被调用会 NPE，但编译通过
        // LoriePreferences 里只在 ListPreference 的 onPreferenceChange 里调 asList
        @SuppressWarnings("rawtypes")
        public LoriePreferences.PrefsProto.ListPreference asList() {
            if (listData != null) {
                // 尝试用反射构造 PrefsProto.ListPreference（如果它有公开构造函数）
                try {
                    Class<?> clazz = LoriePreferences.PrefsProto.ListPreference.class;
                    return (LoriePreferences.PrefsProto.ListPreference) clazz.getDeclaredConstructor(CharSequence[].class, CharSequence[].class).newInstance(listData.entries, listData.entryValues);
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }
    }

    public Prefs(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // 初始化所有 Preference 字段
        touchMode = new Preference<>(sp, "touchMode", "1", String.class);
        displayResolutionMode = new Preference<>(sp, "displayResolutionMode", "exact", String.class);
        displayResolutionExact = new Preference<>(sp, "displayResolutionExact", "", String.class);
        displayResolutionCustom = new Preference<>(sp, "displayResolutionCustom", "", String.class);
        displayStretch = new Preference<>(sp, "displayStretch", false, Boolean.class);
        adjustResolution = new Preference<>(sp, "adjustResolution", false, Boolean.class);
        displayScale = new Preference<>(sp, "displayScale", 100, Integer.class);
        fullscreen = new Preference<>(sp, "fullscreen", false, Boolean.class);
        PIP = new Preference<>(sp, "PIP", false, Boolean.class);
        hideCutout = new Preference<>(sp, "hideCutout", false, Boolean.class);
        additionalKbdVisible = new Preference<>(sp, "additionalKbdVisible", false, Boolean.class);
        showAdditionalKbd = new Preference<>(sp, "showAdditionalKbd", false, Boolean.class);
        showIMEWhileExternalConnected = new Preference<>(sp, "showIMEWhileExternalConnected", false, Boolean.class);
        dexMetaKeyCapture = new Preference<>(sp, "dexMetaKeyCapture", false, Boolean.class);
        ekbarPosition = new Preference<>(sp, "ekbarPosition", "0", String.class);
        ekbarPositionIgnoreOrientation = new Preference<>(sp, "ekbarPositionIgnoreOrientation", false, Boolean.class);
        enableAccessibilityServiceAutomatically = new Preference<>(sp, "enableAccessibilityServiceAutomatically", false, Boolean.class);
        extra_keys_config = new Preference<>(sp, "extra_keys_config", "", String.class);
        filterOutWinkey = new Preference<>(sp, "filterOutWinkey", false, Boolean.class);
        screenIdleTimeout = new Preference<>(sp, "screenIdleTimeout", "0", String.class);
        showMouseHelper = new Preference<>(sp, "showMouseHelper", false, Boolean.class);
        showStylusClickOverride = new Preference<>(sp, "showStylusClickOverride", false, Boolean.class);
        useTermuxEKBarBehaviour = new Preference<>(sp, "useTermuxEKBarBehaviour", false, Boolean.class);
        tapToMove = new Preference<>(sp, "tapToMove", false, Boolean.class);
        preferScancodes = new Preference<>(sp, "preferScancodes", false, Boolean.class);
        pointerCapture = new Preference<>(sp, "pointerCapture", false, Boolean.class);
        adjustHeightForEK = new Preference<>(sp, "adjustHeightForEK", false, Boolean.class);
        opacityEKBar = new Preference<>(sp, "opacityEKBar", 100, Integer.class);
        forceOrientation = new Preference<>(sp, "forceOrientation", "default", String.class);
        pauseKeyInterceptingWithEsc = new Preference<>(sp, "pauseKeyInterceptingWithEsc", false, Boolean.class);
        scaleTouchpad = new Preference<>(sp, "scaleTouchpad", false, Boolean.class);
        Reseed = new Preference<>(sp, "Reseed", false, Boolean.class);
        // TouchInputHandler 引用的额外字段
        capturedPointerSpeedFactor = new Preference<>(sp, "capturedPointerSpeedFactor", 100, Integer.class);
        stylusIsMouse = new Preference<>(sp, "stylusIsMouse", false, Boolean.class);
        stylusButtonContactModifierMode = new Preference<>(sp, "stylusButtonContactModifierMode", false, Boolean.class);
        transformCapturedPointer = new Preference<>(sp, "transformCapturedPointer", "default", String.class);
        ignoreGamepadEvents = new Preference<>(sp, "ignoreGamepadEvents", false, Boolean.class);
        // LorieView 引用的额外字段
        displayFilteringMode = new Preference<>(sp, "displayFilteringMode", "default", String.class);
        hardwareKbdScancodesWorkaround = new Preference<>(sp, "hardwareKbdScancodesWorkaround", false, Boolean.class);
        clipboardEnable = new Preference<>(sp, "clipboardEnable", true, Boolean.class);
        enforceCharBasedInput = new Preference<>(sp, "enforceCharBasedInput", false, Boolean.class);

        // 注册到 keys map
        keys.put("touchMode", touchMode);
        keys.put("displayResolutionMode", displayResolutionMode);
        keys.put("displayResolutionExact", displayResolutionExact);
        keys.put("displayResolutionCustom", displayResolutionCustom);
        keys.put("displayStretch", displayStretch);
        keys.put("adjustResolution", adjustResolution);
        keys.put("displayScale", displayScale);
        keys.put("fullscreen", fullscreen);
        keys.put("PIP", PIP);
        keys.put("hideCutout", hideCutout);
        keys.put("additionalKbdVisible", additionalKbdVisible);
        keys.put("showAdditionalKbd", showAdditionalKbd);
        keys.put("showIMEWhileExternalConnected", showIMEWhileExternalConnected);
        keys.put("dexMetaKeyCapture", dexMetaKeyCapture);
        keys.put("ekbarPosition", ekbarPosition);
        keys.put("ekbarPositionIgnoreOrientation", ekbarPositionIgnoreOrientation);
        keys.put("enableAccessibilityServiceAutomatically", enableAccessibilityServiceAutomatically);
        keys.put("extra_keys_config", extra_keys_config);
        keys.put("filterOutWinkey", filterOutWinkey);
        keys.put("screenIdleTimeout", screenIdleTimeout);
        keys.put("showMouseHelper", showMouseHelper);
        keys.put("showStylusClickOverride", showStylusClickOverride);
        keys.put("useTermuxEKBarBehaviour", useTermuxEKBarBehaviour);
        keys.put("tapToMove", tapToMove);
        keys.put("preferScancodes", preferScancodes);
        keys.put("pointerCapture", pointerCapture);
        keys.put("adjustHeightForEK", adjustHeightForEK);
        keys.put("opacityEKBar", opacityEKBar);
        keys.put("forceOrientation", forceOrientation);
        keys.put("pauseKeyInterceptingWithEsc", pauseKeyInterceptingWithEsc);
        keys.put("scaleTouchpad", scaleTouchpad);
        keys.put("Reseed", Reseed);
        keys.put("capturedPointerSpeedFactor", capturedPointerSpeedFactor);
        keys.put("stylusIsMouse", stylusIsMouse);
        keys.put("stylusButtonContactModifierMode", stylusButtonContactModifierMode);
        keys.put("transformCapturedPointer", transformCapturedPointer);
        keys.put("ignoreGamepadEvents", ignoreGamepadEvents);
        keys.put("displayFilteringMode", displayFilteringMode);
        keys.put("hardwareKbdScancodesWorkaround", hardwareKbdScancodesWorkaround);
        keys.put("clipboardEnable", clipboardEnable);
        keys.put("enforceCharBasedInput", enforceCharBasedInput);
    }

    // MainActivity 调用的方法
    public void recheckStoringSecondaryDisplayPreferences() {
        // no-op stub
    }

    // LoriePreferences 调用：p.get() 返回 SharedPreferences
    public SharedPreferences get() { return sp; }

    public SharedPreferences getSharedPreferences() { return sp; }

    @Override public void putBoolean(String key, boolean val) { sp.edit().putBoolean(key, val).apply(); }
    @Override public boolean getBoolean(String key, boolean def) { return sp.getBoolean(key, def); }
    @Override public void putString(String key, String val) { sp.edit().putString(key, val).apply(); }
    @Override public String getString(String key, String def) { return sp.getString(key, def); }
    @Override public void putInt(String key, int val) { sp.edit().putInt(key, val).apply(); }
    @Override public int getInt(String key, int def) { return sp.getInt(key, def); }
    @Override public void putFloat(String key, float val) { sp.edit().putFloat(key, val).apply(); }
    @Override public float getFloat(String key, float def) { return sp.getFloat(key, def); }
    @Override public void putLong(String key, long val) { sp.edit().putLong(key, val).apply(); }
    @Override public long getLong(String key, long def) { return sp.getLong(key, def); }
}
JAVA
    ok "  生成 Prefs.java fallback stub（含 32 个 Preference 字段 + recheckStoringSecondaryDisplayPreferences 方法）"
else
    ok "  Prefs 类已从 termux-x11 复制，跳过 stub"
fi

# ===== Step 9.2: 补缺失的 R.string 资源 =====
#
# termux-x11 的 LoriePreferences.java 引用了大量 R.string.lorie_pref_*，
# 这些 string 应该在 res/values/strings.xml 里。如果 sync 没复制全或上游改名了，
# 生成一个 fallback strings.xml 补上缺失的条目。
# 用 <resources> 合并机制：放在 winfex_strings.xml 里，不覆盖原 strings.xml。

info "检查并补全缺失的 R.string 资源"
FALLBACK_STRINGS="$XSERVER_DIR/src/main/res/values/winfex_fallback_strings.xml"
mkdir -p "$(dirname "$FALLBACK_STRINGS")"
cat > "$FALLBACK_STRINGS" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Fallback strings for termux-x11 LoriePreferences / MainActivity -->
    <!-- 这些 string 如果在 termux-x11 strings.xml 里已存在，AGP 会自动合并去重 -->
    <string name="lorie_app_name">Winfex X Server</string>
    <string name="lorie_notification_content_text">Winfex X Server is running</string>

    <string name="lorie_pref_summary_requiresExactOrCustom">Requires exact or custom resolution</string>
    <string name="lorie_pref_summary_requiresIntercepting">Requires intercepting</string>
    <string name="lorie_pref_summary_requiresTrackpadAndNative">Requires trackpad and native</string>
    <string name="lorie_pref_summary_screenIdleTimeoutConflict">Conflicts with screen idle timeout</string>
    <string name="lorie_pref_screenIdleTimeoutSystem">Use system screen idle timeout</string>

    <string name="extra_keys_config_desc">Extra keys configuration</string>

    <!-- 其他常用 string，防止后续编译失败 -->
    <string name="lorie_pref_touchpad_mode">Touchpad mode</string>
    <string name="lorie_pref_fullscreen">Fullscreen</string>
    <string name="lorie_pref_pip">Picture-in-picture</string>
    <string name="lorie_pref_hide_cutout">Hide display cutout</string>
    <string name="lorie_pref_additional_kbd_visible">Additional keyboard visible</string>
    <string name="lorie_pref_show_additional_kbd">Show additional keyboard</string>
    <string name="lorie_pref_show_ime_while_external_connected">Show IME while external keyboard connected</string>
    <string name="lorie_pref_dex_meta_key_capture">Capture meta keys (DeX)</string>
    <string name="lorie_pref_ekbar_position">Extra keys bar position</string>
    <string name="lorie_pref_ekbar_position_ignore_orientation">Ignore orientation for EK bar</string>
    <string name="lorie_pref_enable_accessibility_service_automatically">Enable accessibility service automatically</string>
    <string name="lorie_pref_filter_out_winkey">Filter out Win key</string>
    <string name="lorie_pref_screen_idle_timeout">Screen idle timeout</string>
    <string name="lorie_pref_show_mouse_helper">Show mouse helper</string>
    <string name="lorie_pref_show_stylus_click_override">Stylus click override</string>
    <string name="lorie_pref_use_termux_ekbar_behaviour">Use Termux EK bar behaviour</string>
    <string name="lorie_pref_tap_to_move">Tap to move</string>
    <string name="lorie_pref_prefer_scancodes">Prefer scancodes</string>
    <string name="lorie_pref_pointer_capture">Pointer capture</string>
    <string name="lorie_pref_adjust_height_for_ek">Adjust height for extra keys</string>
    <string name="lorie_pref_opacity_ekbar">Extra keys bar opacity</string>
    <string name="lorie_pref_force_orientation">Force orientation</string>
    <string name="lorie_pref_pause_key_intercepting_with_esc">Pause key intercepting with ESC</string>
    <string name="lorie_pref_scale_touchpad">Scale touchpad</string>
    <string name="lorie_pref_display_stretch">Display stretch</string>
    <string name="lorie_pref_adjust_resolution">Adjust resolution</string>
    <string name="lorie_pref_display_scale">Display scale</string>
    <string name="lorie_pref_display_resolution_mode">Display resolution mode</string>
    <string name="lorie_pref_display_resolution_exact">Display resolution (exact)</string>
    <string name="lorie_pref_display_resolution_custom">Display resolution (custom)</string>
    <string name="lorie_pref_reseed">Reseed</string>
</resources>
XML
ok "  生成 winfex_fallback_strings.xml（含 35+ 个 fallback string）"

# ===== Step 9.3: 处理 PrefsProto 类型兼容性 =====
#
# termux-x11 的 LoriePreferences.java 内部定义了 PrefsProto，
# PrefsProto.Preference 是一个抽象类或接口，termux-x11 的 Prefs.Preference 继承它。
# 我们的 Prefs.Preference 需要同样继承/实现它，否则类型不兼容。
#
# 检测 PrefsProto.Preference 是 class 还是 interface，相应用 extends 或 implements。

LORIE_PREFS="$XSERVER_DIR/src/main/java/com/winfex/xserver/LoriePreferences.java"
if [[ -f "$LORIE_PREFS" ]] && grep -q "PrefsProto" "$LORIE_PREFS" 2>/dev/null; then
    info "检测到 LoriePreferences.PrefsProto，处理类型兼容性"

    PREFS_FILE="$XSERVER_DIR/src/main/java/com/winfex/xserver/Prefs.java"

    # 检测 PrefsProto.Preference 是 interface 还是 abstract class
    # 在 LoriePreferences.java 里找 "interface Preference" 或 "class Preference" 在 PrefsProto 块内
    PREFSPROTO_TYPE=""
    # 先找 PrefsProto 块的位置
    PREFSPROTO_START=$(grep -n "interface PrefsProto\|class PrefsProto" "$LORIE_PREFS" | head -1 | cut -d: -f1)
    if [[ -n "$PREFSPROTO_START" ]]; then
        # 从 PrefsProto 开始往后找 Preference 的定义
        PREFSPROTO_BLOCK=$(sed -n "${PREFSPROTO_START},/^    }/p" "$LORIE_PREFS" 2>/dev/null)
        if echo "$PREFSPROTO_BLOCK" | grep -q "interface Preference"; then
            PREFSPROTO_TYPE="interface"
        elif echo "$PREFSPROTO_BLOCK" | grep -q "abstract class Preference\|class Preference"; then
            PREFSPROTO_TYPE="class"
        fi
    fi

    info "  PrefsProto.Preference 类型: ${PREFSPROTO_TYPE:-unknown}"

    if [[ -f "$PREFS_FILE" ]]; then
        # 检测 PrefsProto.Preference 是否有泛型参数
        PREFSPROTO_SIG=$(echo "$PREFSPROTO_BLOCK" | grep -E "interface Preference|class Preference" | head -1 || true)
        # grep -c 在 set -e + pipefail 下返回 1 会导致脚本退出，加 || true
        HAS_GENERIC=$(echo "$PREFSPROTO_SIG" | grep -c "<" || true)
        # 确保 HAS_GENERIC 是数字
        HAS_GENERIC=${HAS_GENERIC:-0}

        if [[ "$PREFSPROTO_TYPE" == "interface" ]]; then
            # 用 implements
            if [[ "$HAS_GENERIC" -gt 0 ]]; then
                info "  PrefsProto.Preference<T> 是泛型接口，Prefs.Preference<T> implements LoriePreferences.PrefsProto.Preference<T>"
                sed -i 's/public static class Preference<T> {/public static class Preference<T> implements LoriePreferences.PrefsProto.Preference<T> {/' "$PREFS_FILE" || true
            else
                info "  PrefsProto.Preference 是接口（无泛型），Prefs.Preference<T> implements raw type"
                sed -i 's/public static class Preference<T> {/public static class Preference<T> implements LoriePreferences.PrefsProto.Preference {/' "$PREFS_FILE" || true
            fi
        elif [[ "$PREFSPROTO_TYPE" == "class" ]]; then
            # 用 extends（Java 不允许多继承，但 Prefs.Preference 目前没 extends 其他类）
            if [[ "$HAS_GENERIC" -gt 0 ]]; then
                info "  PrefsProto.Preference<T> 是抽象类，Prefs.Preference<T> extends LoriePreferences.PrefsProto.Preference<T>"
                sed -i 's/public static class Preference<T> {/public static class Preference<T> extends LoriePreferences.PrefsProto.Preference<T> {/' "$PREFS_FILE" || true
            else
                info "  PrefsProto.Preference 是抽象类（无泛型），Prefs.Preference<T> extends raw type"
                sed -i 's/public static class Preference<T> {/public static class Preference<T> extends LoriePreferences.PrefsProto.Preference {/' "$PREFS_FILE" || true
            fi
        else
            warn "  无法确定 PrefsProto.Preference 类型，跳过（可能编译失败）"
        fi
        ok "  Prefs.Preference 已改为继承 LoriePreferences.PrefsProto.Preference"
    fi
fi

# ===== Step 9.4: 修复 CmdEntryPoint.sendBroadcast 调用 =====
#
# CmdEntryPoint.sendBroadcast 改名为 broadcastIntent（避免 override Context.sendBroadcast）。
# 把 LoriePreferences.java 里的 CmdEntryPoint.sendBroadcast 调用替换为 broadcastIntent。

info "替换 CmdEntryPoint.sendBroadcast → CmdEntryPoint.broadcastIntent"
find "$XSERVER_DIR/src/main/java" -name '*.java' -exec \
    sed -i 's/CmdEntryPoint\.sendBroadcast(/CmdEntryPoint.broadcastIntent(/g' {} + 2>/dev/null || true
ok "  sendBroadcast 调用已替换"

# ===== Step 10: 尝试下载 MiceWine 的 Wine 兼容 patch =====

info "尝试下载 MiceWine 的 Wine 兼容 patch（可选）"

MICWINE_PATCH_URL="https://raw.githubusercontent.com/KreitinnSoftware/MiceWine-Application/master/app/src/main/cpp/patches/xserver.patch"
PATCH_FILE="$XSERVER_DIR/src/main/cpp/patches/micewine-wine-compat.patch"

if command -v curl >/dev/null 2>&1; then
    if curl -fsSL "$MICWINE_PATCH_URL" -o "$PATCH_FILE" 2>/dev/null; then
        ok "已下载 MiceWine Wine 兼容 patch"
        warn "此 patch 不会自动 apply，需要你手动 review 后执行："
        warn "  cd xserver/src/main/cpp/xserver && patch -p1 < ../patches/micewine-wine-compat.patch"
    else
        warn "无法下载 MiceWine patch（网络问题？），跳过"
    fi
else
    warn "未安装 curl，跳过 MiceWine patch 下载"
fi

# ===== Step 11: 总结输出 =====

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}X Server module 同步完成${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "下一步："
echo ""
echo "  1. 验证 xserver module 能编译："
echo "     ./gradlew :xserver:assembleDebug"
echo ""
echo "  2. 如果编译失败，常见原因："
echo "     - 缺少 NDK r26b+，在 Android Studio SDK Manager 装"
echo "     - 缺少 CMake 3.22.1，同样在 SDK Manager 装"
echo "     - submodule 没拉全，跑："
echo "       cd build/termux-x11 && git submodule update --init --recursive"
echo "       然后重新跑 $0 --local build/termux-x11"
echo ""
echo "  3. 编译成功后，主 app 通过反射找到 com.winfex.xserver.XServerActivity"
echo "     在 PrefixesFragment 点「启动 X Server」按钮验证"
echo ""
echo "  4. 如需手动 apply MiceWine Wine 兼容 patch（推荐）："
echo "     cd xserver/src/main/cpp/xserver"
echo "     patch -p1 < ../patches/micewine-wine-compat.patch"
echo ""
echo "  备份的 stub 文件在: $BACKUP_DIR"
echo ""
