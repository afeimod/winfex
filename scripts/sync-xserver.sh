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

REF="main"
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
        git fetch --all --tags
        git checkout "$REF" || error "checkout $REF 失败"
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
            if [[ "$REF" != "main" ]]; then
                git checkout "$REF" || warn "无法切到 $REF，保留 main"
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

# ===== Step 5: 复制 Java 源码 =====

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

# 复制 AIDL 文件（termux-x11 用 ICmdEntryInterface.aidl 跨进程通信）
info "复制 AIDL 文件"
for aidl_src in \
    "$TERMUX_X11_DIR/app/src/main/aidl" \
    "$TERMUX_X11_DIR/lorie/src/main/aidl"; do
    if [[ -d "$aidl_src" ]]; then
        mkdir -p "$XSERVER_DIR/src/main/aidl"
        cp -r "$aidl_src/"* "$XSERVER_DIR/src/main/aidl/" 2>/dev/null || true
        # 包名替换
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

ok "Java 源码复制完成"

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

info "复制资源文件"

for d in drawable layout values; do
    src_dir="$TERMUX_X11_DIR/app/src/main/res/$d"
    [[ -d "$src_dir" ]] || continue
    mkdir -p "$XSERVER_DIR/src/main/res/$d"
    cp -rn "$src_dir/"* "$XSERVER_DIR/src/main/res/$d/" 2>/dev/null || true
done

ok "资源文件复制完成"

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

        // termux-x11 的 CmdEntryPoint.java 引用 BuildConfig.COMMIT
        // 注意：buildConfigField 对 String 类型，值必须含双引号（AGP 不会自动加）
        buildConfigField("String", "COMMIT", "\"unknown-synced\"")
        buildConfigField("String", "VERSION_NAME", "\"0.4.2\"")
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

# ===== Step 9.1: 生成 hidden API stub（让 CmdEntryPoint 能编译） =====
#
# termux-x11 的 CmdEntryPoint.java 用了 Android framework 的 hidden API：
#   - android.app.IActivityManager
#   - android.content.IIntentReceiver
#   - android.content.IIntentSender
# 这些类在公开 SDK 里没有，运行时存在（@hide），编译时缺。
# 我们生成最小 stub 让编译通过，运行时被真实的 framework 类替换（包名一致）。
# 注意：stub 必须放在独立的 source set，且不打入最终 APK，否则会与 framework 冲突。
# 这里用 buildFeatures.aidl + provided 模式不可行（IActivityManager 不是 AIDL）。
# 直接放到 src/main/java 里 + 用 lint 跳过重复类检查即可（运行时 framework 优先）。

info "生成 Android hidden API stub（让 CmdEntryPoint.java 编译通过）"

STUB_DIR="$XSERVER_DIR/src/main/java/com/winfex/xserver/stub"
mkdir -p "$STUB_DIR/android/app" "$STUB_DIR/android/content"

# IActivityManager stub
cat > "$STUB_DIR/android/app/IActivityManager.java" <<'JAVA'
package android.app;

/**
 * Stub for hidden API android.app.IActivityManager.
 * Real class exists at runtime; this stub only makes compilation pass.
 * DO NOT USE in production code paths.
 */
public interface IActivityManager {
    // 最小声明，只要能 import 通过即可。CmdEntryPoint 调用的方法反射访问。
}
JAVA

# IIntentReceiver stub
cat > "$STUB_DIR/android/content/IIntentReceiver.java" <<'JAVA'
package android.content;

public interface IIntentReceiver {
    void performReceive(Intent intent, int resultCode, String data, android.os.Bundle extras, boolean ordered, boolean sticky, int sendingUser);
}
JAVA

# IIntentSender stub
cat > "$STUB_DIR/android/content/IIntentSender.java" <<'JAVA'
package android.content;

public interface IIntentSender {
    int send(int code, Intent intent, String resolvedType, IIntentReceiver finishedReceiver, String requiredPermission, android.os.Bundle options);
}
JAVA

ok "  生成 3 个 hidden API stub 文件"

# ===== Step 9.2: 生成 Prefs stub（如果 termux-x11 没提供 Prefs.kt） =====
#
# termux-x11 上游有 Prefs.kt（Kotlin），但部分版本可能命名不同或被裁剪。
# 如果 sync 后 com.winfex.xserver.Prefs 类找不到，生成一个最小 stub。

if ! find "$XSERVER_DIR/src/main/java/com/winfex/xserver" -maxdepth 1 -name 'Prefs.*' | grep -q .; then
    info "未找到 Prefs 类，生成最小 stub"
    cat > "$XSERVER_DIR/src/main/java/com/winfex/xserver/Prefs.java" <<'JAVA'
package com.winfex.xserver;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stub for termux-x11 Prefs class.
 * Real Prefs.kt is in upstream termux-x11; this stub provides minimal API
 * surface used by MainActivity / LorieView / TouchInputHandler.
 *
 * 如果上游 Prefs.kt 存在但 sync 脚本没复制对，请手动从
 * termux-x11/app/src/main/java/com/termux/x11/Prefs.kt 复制过来并删除此 stub。
 */
public class Prefs {
    private static final String PREFS_NAME = "winfex_xserver_prefs";
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public SharedPreferences getSharedPreferences() { return sp; }

    public boolean getBoolean(String key, boolean def) { return sp.getBoolean(key, def); }
    public void putBoolean(String key, boolean val) { sp.edit().putBoolean(key, val).apply(); }

    public int getInt(String key, int def) { return sp.getInt(key, def); }
    public void putInt(String key, int val) { sp.edit().putInt(key, val).apply(); }

    public String getString(String key, String def) { return sp.getString(key, def); }
    public void putString(String key, String val) { sp.edit().putString(key, val).apply(); }

    public float getFloat(String key, float def) { return sp.getFloat(key, def); }
    public void putFloat(String key, float val) { sp.edit().putFloat(key, val).apply(); }
}
JAVA
    ok "  生成 Prefs.java stub"
else
    ok "  Prefs 类已存在，跳过 stub 生成"
fi

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
