needPkgs=(
  git
  wget
  7zip
  base-devel
  ccache
  cmake
  meson
  ninja
  bzip2
  xz
  zstd
  lzip
  lzop
  ncompress
  lz4
  gobject-introspection
  gperf
)

wsDir="$(dirname $(readlink -f "$0"))"

guard_dev_null() {
  if [[ ! -c /dev/null ]]; then
    echo "/dev/null 不是字符设备，正在修复..."
    rm -rf /dev/null
    mknod -m 666 /dev/null c 1 3 || { echo "无法创建 /dev/null" && exit 1; }
  fi
}
guard_dev_null

if [[ -f ${wsDir}/custom.conf ]]; then
  echo "加载自定义配置文件"
  . ${wsDir}/custom.conf
elif [[ -f ${wsDir}/default.conf ]]; then
  . ${wsDir}/default.conf
fi
# 目标架构列表 (通过 --arch 指定)
targetArchs=()

# 重建模式标志（--rebuild-deps 和 --rebuild all 时使用，全量删除重建）
rebuildMode=false
# 指定要重建的目标包名（--rebuild pkg 时使用，仅删该包的 tar）
rebuildTarget=""

# 跳过源码获取标志（--has-src 时使用，src 下已有源码则跳过 get_src）
hasSrc=false

mkdir -p "${pkgDir}"
mkdir -p "${srcDir}"
mkdir -p "${ndkDir}"

# @declare
# 反向图: rgraph_[dep] = 需要 dep 的项目列表
declare -A _rgraph_
# 入度
declare -A _indeg_
# 访问
declare -A _visited_
declare -a _allPkgs_
declare -a _buildOrder_

# @prefix
create_pfx() {
  if [[ $prefix == / ]] || [[ -z $prefix ]] || [[ ! $prefix == /* ]]; then
    echo "非法的 \$prefix 变量! => ${prefix:-<空>}"
    exit 1
  fi
  rm -rf "${prefix}"
  bash "${wsDir}/generate-prefix.sh" "${prefix}" || { echo "错误:目标前缀创建失败!" && exit 1; }
}

# @license
# 扫描所有 projects/*/build.sh, 汇总第三方组件协议清单
# 输出到 ${prefix}/share/licenses/THIRD_PARTY_NOTICES

gen_notices() {
  local _noticesDir="${prefix}/share/licenses"
  mkdir -p "${_noticesDir}"
  local _notices="${_noticesDir}/THIRD_PARTY_NOTICES"

  {
    echo "Android Bionic Rootfs 第三方组件协议清单"
    echo "本文件由 build-rootfs-arch.sh 自动生成"
    echo
    printf "%-24s %-24s %s\n" "组件" "协议" "来源"
    echo "---------------------------------------------------------------"
    local _pjName
    for _pjName in "${_buildOrder_[@]}"; do
      local _buildFile="${wsDir}/projects/${_pjName}/build.sh"
      [[ -f "$_buildFile" ]] || continue
      . "${wsDir}/empty.sh"
      . "$_buildFile"
      printf "%-24s %-24s %s\n" "${_pjName}" "${license:-未知}" "${url:-无}"
    done
  } >"${_notices}"
  echo "第三方组件协议清单已生成 => ${_notices}"
}

# @help
usage() {
  echo "用法: $0 [选项]"
  echo "选项:"
  echo "  --build <pjName|all>         构建指定包或所有包"
  echo "  --rebuild <pjName|all>       仅重建指定包（依赖不解构）"
  echo "  --rebuild-deps <pjName|all>  重建指定包及其所有依赖"
  echo "  --arch <archs>              指定目标架构 (arm64|aarch64 x86_64|amd64|all), 默认 all"
  echo "  --has-src               src 下已有对应源码时跳过代码获取"
  echo "  --load-env [arch]     加载环境变量用于调试, 默认 aarch64"
  echo "  --clean-ccache        清理 ccache 缓存"
  echo "  --update-host-deps    更新主机依赖"
  echo "  --help                显示此帮助信息"
  exit 0
}

# 架构名归一化: arm64->aarch64, amd64->x86_64
normalize_arch() {
  case "$1" in
  arm64) echo "aarch64" ;;
  amd64) echo "x86_64" ;;
  *) echo "$1" ;;
  esac
}

# 检查包的 arch 是否包含指定架构
arch_contains() {
  local _pkgArchs="$1"
  local _target="$2"
  local _a
  for _a in $_pkgArchs; do
    [[ "$_a" == "$_target" ]] && return 0
  done
  return 1
}

# 获取包与目标架构的交集
filter_archs() {
  local _pkgArchs="$1"
  local _filtered=()
  local _a
  for _a in ${_pkgArchs[@]}; do
    if [[ ${#targetArchs[@]} -eq 0 ]]; then
      _filtered+=("$_a")
    else
      local _t
      for _t in "${targetArchs[@]}"; do
        [[ "$_a" == "$_t" ]] && {
          _filtered+=("$_a")
          break
        }
      done
    fi
  done
  echo "${_filtered[@]}"
}

clean_ccache() {
  echo "清理 ccache 缓存..."
  local _ccDir="${wsDir}/bionic_ccache"
  if [[ -d "$_ccDir" ]] && cd "$_ccDir"; then
    rm -rf ./*
    cd "$wsDir"
  fi
  echo "ccache 缓存已清理"
}

# @ndk-patch

apply_ndk_patches() {
  local _ndkVersion
  # 从ndkURL中提取版本号，例如"r29"
  _ndkVersion=$(echo "$ndkURL" | grep -oP 'r\d+' | head -1)
  [[ -z "$_ndkVersion" ]] && { echo "无法从ndkURL中提取NDK版本" && exit 1; }

  local _patchDir="${wsDir}/ndk_patches/${_ndkVersion}"
  [[ ! -d "$_patchDir" ]] && { echo "NDK补丁目录不存在: $_patchDir" && return 0; }

  local _patchFiles
  _patchFiles=($(ls "$_patchDir"/*.patch 2>/dev/null))
  [[ ${#_patchFiles[@]} -eq 0 ]] && { echo "NDK版本 $_ndkVersion 无需应用任何补丁" && return 0; }

  local _ndkSysroot="${ndkDir}/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
  [[ ! -d "$_ndkSysroot" ]] && { echo "NDK sysroot不存在: $_ndkSysroot" && return 0; }

  # 检查是否需要重新应用补丁（prefix变化或首次应用）
  local _stateFile="${ndkDir}/.ndk_patches_state"
  local _lastPrefix=""
  [[ -f "$_stateFile" ]] && _lastPrefix=$(cat "$_stateFile")

  if [[ "$_lastPrefix" == "$prefix" ]]; then
    echo "NDK补丁已应用，prefix未变化，跳过"
    return 0
  fi

  # prefix变化时，需要重新下载NDK并应用补丁
  if [[ -n "$_lastPrefix" ]]; then
    echo "NDK prefix从$_lastPrefix变化为$prefix，需要重新下载NDK..."
    rm -rf "$ndkDir"
    return 0
  fi

  echo "首次应用NDK补丁..."

  cd "$_ndkSysroot"

  for _patch in "${_patchFiles[@]}"; do
    if patch -p1 --dry-run <"$_patch" >/dev/null 2>&1; then
      echo "测试通过=> $_patch"
      echo "应用NDK补丁=> $_patch"
      patch --no-backup-if-mismatch -N -p1 <<<$(cat "$_patch" | sed "s|@TERMUX_PREFIX@|${prefix}|g" |  sed "s|@TERMUX_PREFIX_CLASSICAL@|${prefix}|g" | sed "s|@ROOTFS_PREFIX@|${prefix}|g" | sed "s|__TERMUX__|__ANDROID__|g") || { echo "NDK补丁应用失败=> $_patch" && exit 1; }
    else
      echo "NDK补丁应用失败=> $_patch" && exit 1
    fi
  done

  # 保存当前prefix到状态文件
  echo "$prefix" >"$_stateFile"

  cd -
}

# Install Deps
update_host_deps() {
  echo "更新主机依赖..."
  pacman-key --init
  pacman -Syu --noconfirm
  pacman -S --noconfirm --needed ${needPkgs[@]}
}

# 应用NDK补丁（会在prefix变化时重新下载NDK）
apply_ndk_patches

# 如果NDK目录不存在（可能被apply_ndk_patches删除），则下载
if [[ ! -d "${ndkDir}/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
  echo "NDK 不存在，开始下载..."
  mkdir -p "$ndkDir"
  wget -O "/tmp/ndk.zip" "$ndkURL"

  rm -rf /tmp/ndk-tmp
  mkdir /tmp/ndk-tmp

  7z x /tmp/ndk.zip -o/tmp/ndk-tmp

  mv /tmp/ndk-tmp/*/* "${ndkDir}/"

  # 下载后重新应用补丁
  apply_ndk_patches
fi

# 提取独立工具链
extract_standalone() {
  local _toolchainArch="$1"
  local _toolchainDir="${wsDir}/toolchain-${_toolchainArch}"
  if [[ -d "${_toolchainDir}/bin" ]]; then
    echo "独立工具链已存在: ${_toolchainDir}"
    return
  fi
  echo "提取独立工具链: ${_toolchainArch} ..."
  python3 "${ndkDir}/build/tools/make_standalone_toolchain.py" \
    --arch "${_toolchainArch}" \
    --api "${api}" \
    --install-dir "${_toolchainDir}" || { echo "错误: 独立工具链提取失败! => $_toolchainArch" && exit 1; }
}

#for _arch in arm64 x86_64; do
#  extract_standalone "$_arch"
#done

load_env() {
  local _targetArch="${1:-aarch64}"
  # aarch64 -> arm64, x86_64 -> x86_64
  local _toolchainArch="${_targetArch}"
  [[ "$_targetArch" == "aarch64" ]] && _toolchainArch="arm64"
  local _toolchainDir="${wsDir}/ndk/toolchains/llvm/prebuilt/linux-x86_64"
  local _toolchainBin="${_toolchainDir}/bin"
  local _toolchainSysroot="${_toolchainDir}/sysroot"

  export CC="ccache ${_toolchainBin}/${_targetArch}-linux-android${api}-clang"
  export CXX="ccache ${_toolchainBin}/${_targetArch}-linux-android${api}-clang++"
  export AR="${_toolchainBin}/llvm-ar"
  export READELF="${_toolchainBin}/llvm-readelf"
  export STRIP="${_toolchainBin}/llvm-strip"
  export RANLIB="${_toolchainBin}/llvm-ranlib"
  export LD="${_toolchainBin}/ld.lld"
  export ASM="${_toolchainBin}/yasm"

  # --sysroot=${_toolchainSysroot}
  export CFLAGS="--sysroot=${_toolchainSysroot} -I${prefix}/include -O3 -pipe"
  export CXXFLAGS="--sysroot=${_toolchainSysroot} -I${prefix}/include -O3 -pipe"
  export LDFLAGS="--sysroot=${_toolchainSysroot} -L${prefix}/lib -s"

  unset LIBS

  export PATH="${_toolchainBin}:$PATH"

  export PKG_CONFIG="pkg-config"
  export PKG_CONFIG_LIBDIR="${prefix}/lib/pkgconfig:${prefix}/share/pkgconfig"
  unset PKG_CONFIG_SYSROOT_DIR

  export USE_CCACHE=1
  export CCACHE_EXEC="/usr/bin/ccache"
  export CCACHE_DIR="${wsDir}/bionic_ccache/${targetArch}"
  mkdir -p "$CCACHE_DIR"
}

load_env_host() {
  # host glibc env
  local _hostArch
  _hostArch=$(uname -m)

  export CC="ccache gcc"
  export CXX="ccache g++"
  export AR="gcc-ar"
  export READELF="readelf"
  export STRIP="strip"
  export RANLIB="gcc-ranlib"
  export LD="ld"

  unset ASM
  unset LIBS

  export CFLAGS="-O2 -pipe"
  export CXXFLAGS="-O2 -pipe"
  export LDFLAGS="-s"

  export PKG_CONFIG="pkg-config"
  #export PKG_CONFIG_LIBDIR="${prefix}/lib/pkgconfig:${prefix}/share/pkgconfig"
  #export PKG_CONFIG_SYSROOT_DIR="${prefix}"

  export CCACHE_DIR="${wsDir}/bionic_ccache/host"

  mkdir -p "$CCACHE_DIR"
}

# @src

dl_src() {
  rm -rf /tmp/download-src
  mkdir -p /tmp/download-src
  wget --tries=3 --timeout=60 -P /tmp/download-src/ $url || {
    echo "下载 $url 失败"
    rm -rf /tmp/download-src
    exit 1
  }
}

get_src() {
  cd "${wsDir}/src"
  rm -rf $pjName
  mkdir $pjName
  guard_dev_null
  case $urlType in
  git)
    local _gitRetries=3
    local _gitTry
    for ((_gitTry = 1; _gitTry <= _gitRetries; _gitTry++)); do
      echo "克隆 ${pjName} (第 ${_gitTry}/${_gitRetries} 次尝试)"
      if timeout 120 git clone --recursive --depth=1 -b "${revision}" "${url}" $pjName; then
        _gitTry=0
        break
      fi
      if [[ $_gitTry -lt $_gitRetries ]]; then
        echo "源码克隆失败,5秒后重试..."
        rm -rf $pjName
        sleep 10
      fi
    done
    if [[ $_gitTry -ne 0 ]]; then
      echo "源码克隆失败!" && exit 1
    fi
    ;;
  tar)
    if dl_src; then
      cd ${wsDir}/src
      tar --strip-components=1 -xf /tmp/download-src/$(ls /tmp/download-src/) -C $pjName/ || { echo "文件解压失败！" && exit 1; }
      #cd "${wsDir}/src/$pjName"
    else
      echo "源码处理失败!" && exit 1
    fi
    ;;
  7z)
    if dl_src; then
      cd ${wsDir}/src
      rm -rf /tmp/7z-tmp
      mkdir /tmp/7z-tmp
      7z x /tmp/downlaod-src/* -o/tmp/7z-tmp/ || { echo "文件解压失败！" && exit 1; }
      mv /tmp/7z-tmp/*/* $pjName/
      #cd "${wsDir}/src/$pjName"
    else
      echo "源码下载失败!" && exit 1
    fi
    ;;
  wget)
    if dl_src; then
      echo "源码下载完成"
    fi
    ;;
  local)
    echo "此项目源码类型为本地"
    return 0
    ;;
  others)
    if ! declare -F custom_url >/dev/null; then
      echo "如果urlType为others,请定义custom_url函数!"
      exit 1
    else
      echo "存在自定义URL方法,开始执行"
      custom_url || { echo "源码下载失败!" && exit 1; }
    fi
    ;;
  esac
}

# @patch

apply_patches() {
  local _patchFiles
  local _patch

  if declare -F custom_patch >/dev/null; then
    echo "使用自定义补丁"
    custom_patch || { echo "自定义补丁应用失败!" && exit 1; }
    return 0
  fi

  [[ -z $pjName ]] && { echo "pjName 未定义！" && exit 1; }
  _patchFiles=("${wsDir}"/projects/"${pjName}"/*.patch)
  [[ ! -f "${_patchFiles[0]}" ]] && _patchFiles=()
  [[ -z $_patchFiles ]] && { echo "$pjName 无需应用任何补丁" && return 0; }

  local _srcDir
  if [[ -n "${pkgSrcDir:-}" ]]; then
    _srcDir="${pkgSrcDir}"
  else
    _srcDir="${wsDir}/src/${pjName}"
  fi
  cd "$_srcDir"

  # <<< $(cat $_patch | sed "s/@TERMUX_PREFIX@/${prefix}/g" | sed "s/@ROOTFS_PREFIX@/${prefix}/g")

  for _patch in ${_patchFiles[@]}; do
    if patch -p1 --dry-run <"$_patch"; then
      echo "测试通过=> $_patch"
      echo "应用补丁=> $_patch"
      patch --no-backup-if-mismatch -N -p1 <<<$(cat $_patch | sed "s|@TERMUX_PREFIX@|${prefix}|g" | sed "s|@ROOTFS_PREFIX@|${prefix}|g" | sed "s|__TERMUX__|__ANDROID__|g") || { echo "补丁应用失败=> $_patch" && exit 1; }
    else
      echo "补丁应用失败=> $_patch" && exit 1
    fi
  done

  cd -
}

# @license

install_license() {
  local _srcDir="${pkgSrcDir:-${wsDir}/src/${pjName}}"
  [[ ! -d "${_srcDir}" ]] && { echo "警告: 源码目录不存在，跳过协议安装 => ${_srcDir}" && return 0; }

  local _licDir="${destDir}${prefix}/share/_licenses/${pjName}"
  mkdir -p "${_licDir}"

  # 收集协议文件：优先源码目录，否则回退父目录（如 icu pkgSrcDir=src/icu/source）
  local _licSrcDir=""
  local _f
  for _f in $(ls -A "$_srcDir" 2>/dev/null); do
    if [[ -f "${_srcDir}/${_f}" ]] && [[ "${_f^^}" =~ ^(LICENSE|LICENCE|COPYING|COPYRIGHT|NOTICE) ]]; then
      _licSrcDir="$_srcDir"
      break
    fi
  done
  if [[ -z "$_licSrcDir" && -n "${pkgSrcDir:-}" ]]; then
    local _parentDir="$(dirname "${pkgSrcDir}")"
    [[ "$_parentDir" != "$_srcDir" ]] && for _f in $(ls -A "$_parentDir" 2>/dev/null); do
      if [[ -f "${_parentDir}/${_f}" ]] && [[ "${_f^^}" =~ ^(LICENSE|LICENCE|COPYING|COPYRIGHT|NOTICE) ]]; then
        _licSrcDir="$_parentDir"
        break
      fi
    done
  fi

  local _found=0
  if [[ -n "$_licSrcDir" ]]; then
    for _f in $(ls -A "$_licSrcDir" 2>/dev/null); do
      if [[ -f "${_licSrcDir}/${_f}" ]] && [[ "${_f^^}" =~ ^(LICENSE|LICENCE|COPYING|COPYRIGHT|NOTICE) ]]; then
        cp -a "${_licSrcDir}/${_f}" "${_licDir}/"
        echo "安装协议文件 => ${pjName}/${_f}"
        _found=1
      fi
    done
  fi

  # 自定义协议文件: agreementTargetFile 指向的文件一并复制到协议目录
  # 支持多个文件/通配符（空格分隔），支持绝对路径或相对工作目录的路径
  if [[ -n "${agreementTargetFile:-}" ]]; then
    local _agreeSrc
    local _agree
    for _agreeSrc in ${agreementTargetFile}; do
      [[ "$_agreeSrc" != /* ]] && _agreeSrc="${wsDir}/${_agreeSrc}"
      local _agreeMatched=0
      for _agree in ${_agreeSrc}; do
        if [[ -f "$_agree" ]]; then
          cp -a "$_agree" "${_licDir}/"
          echo "安装自定义协议文件 => ${pjName}/$(basename "$_agree")"
          _found=1
          _agreeMatched=1
        fi
      done
      [[ $_agreeMatched -eq 0 ]] && echo "警告: 自定义协议文件不存在 => ${_agreeSrc}"
    done
  fi

  if [[ $_found -eq 0 ]]; then
    # 源码中无协议文件时，写入最小协议信息（含 SPDX 标识与上游地址）
    echo "警告: 未找到 ${pjName} 的协议文件，写入最小协议信息"
    {
      echo "Package: ${pjName}"
      echo "Version: ${revision}"
      echo "License: ${license:-unknown}"
      echo "URL: ${url}"
    } >"${_licDir}/LICENSE"
  fi
}

# @package

package() {
  [[ ! -d "${destDir}" ]] && { echo " destDir 不存在 => ${destDir}" && exit 1; }
  install_license
  cd "${destDir}"
  if [[ -e dev/null ]]; then
    echo "警告: ${pjName} 的 make install 在 DESTDIR 下创建了 dev/null，正在清理..."
    rm -rf dev/null
  fi
  tar -cf "${wsDir}/pkgs/${pjName}-${revision}-${targetArch}.tar" . 2>/dev/null || true
  rm -rf "${destDir}"
  cd "${wsDir}"
}

# @dep

get_dep() {
  local _buildFile="${wsDir}/projects/$1/build.sh"
  if [[ ! -f "$_buildFile" ]]; then
    echo "错误: 项目不存在 => $1" >&2
    return 1
  fi
  . "${wsDir}/empty.sh"
  . "$_buildFile"
  echo "${deps[@]}"
}

collect_dep() {
  local _project="$1"
  [[ -n "${_visited_[$_project]:-}" ]] && {
    return
  }
  _visited_[$_project]=1
  _allPkgs_+=("$_project")

  local _deps
  _deps=$(get_dep "$_project") || { echo "错误: 无法获取 $_project 的依赖" && exit 1; }
  local _dep
  for _dep in $_deps; do
    collect_dep "$_dep"
  done
}

# 拓补排序

toposort() {
  # 构建反向图: _rgraph_[dep] = 需要 dep 的项目列表
  # _indeg_[project] = project 依赖的包数量
  local _project
  for _project in "${_allPkgs_[@]}"; do
    local _deps
    _deps=$(get_dep "$_project") || { echo "错误: 无法获取 $_project 的依赖" && exit 1; }
    _indeg_["$_project"]=0
    local _dep
    for _dep in $_deps; do
      _indeg_["$_project"]=$((${_indeg_["$_project"]} + 1))
      _rgraph_["$_dep"]+="${_project} "
    done
  done

  # 初始化队列，入度为0的项目（无依赖）
  local _queue=()
  for _project in "${_allPkgs_[@]}"; do
    [[ ${_indeg_["$_project"]} -eq 0 ]] && {
      _queue+=("$_project")
    }
  done

  # Kahn 算法
  while [[ ${#_queue[@]} -gt 0 ]]; do
    local _project="${_queue[0]}"
    _queue=("${_queue[@]:1}") # 出队
    _buildOrder_+=("$_project")

    # 遍历所有依赖 _project 的包，减少其入度
    local _dependents="${_rgraph_["$_project"]}"
    local _dep
    for _dep in $_dependents; do
      _indeg_["$_dep"]=$((${_indeg_["$_dep"]} - 1))
      [[ ${_indeg_["$_dep"]} -eq 0 ]] && {
        _queue+=("$_dep")
      }
    done
  done

  # 检测循环依赖
  [[ ${#_buildOrder_[@]} -ne ${#_allPkgs_[@]} ]] && {
    echo "错误:存在循环依赖!"
    echo "未排序的包:"
    for _project in "${_allPkgs_[@]}"; do
      [[ ! " ${_buildOrder_[@]} " =~ " $_project " ]] && {
        echo "$_project"
      }
    done
    exit 1
  }
}

# @build

configure_err() {
  echo "构建失败"
  exit 1
}

compile_err() {
  echo "编译失败"
  exit 1
}

build_system() {
  local _buildArchs
  _buildArchs=$(filter_archs "${arch[@]}")
  [[ -z "$_buildArchs" ]] && {
    echo "跳过: 目标架构不在包的 arch 列表中"
    return 0
  }
  # 加载基础配置参数
  . "${wsDir}/base-configure-args.conf"
  #cat >> "${wsDir}/build_temp.sh" << "EOF"
  if [[ -n "${pkgSrcDir:-}" ]]; then
    echo "存在定义的pkgSrcDir,进入"
    local _srcDir="${pkgSrcDir}"
  else
    local _srcDir="${wsDir}/src/${pjName}"
  fi
  case $buildSys in
  cmake)
    for targetArch in $_buildArchs; do
      destDir="/tmp/build-${targetArch}"
      export CCACHE_DIR="${wsDir}/bionic_ccache/${targetArch}"
      mkdir -p "$CCACHE_DIR"
      echo "target arch=> $targetArch"
      echo "Use cmake..."
      cd "${_srcDir}"
      #load_env "$targetArch"
      rm -rf build
      mkdir build
      [[ ! $targetArch == $(uname -m) ]] && {
        cmakeBaseArgs+=" -DCMAKE_SYSTEM_PROCESSOR=$targetArch"
      }
      if [[ $targetArch == aarch64 ]]; then
        cmakeBaseArgs+=" -DANDROID_ABI=arm64-v8a"
      else
        cmakeBaseArgs+=" -DANDROID_ABI=x86_64"
      fi
      if declare -F pre_setup >/dev/null; then
        echo "存在前置操作,开始执行"
        pre_setup
      fi
      if [[ $doNotUseNinja == 1 ]]; then
        cd build
        cmake .. ${cmakeBaseArgs[@]} ${args[@]} || configure_err
        make -j$(nproc) || compile_err
      else
        cmake -G Ninja -S . -B build ${cmakeBaseArgs[@]} ${args[@]} || configure_err
        cmake --build build || compile_err
      fi

      if declare -F install >/dev/null; then
        DESTDIR="${destDir}" PREFIX="${prefix}" install
      else
        if [[ $doNotRunCMakeInstallOnCmake == 1 ]]; then
          DESTDIR="${destDir}" PREFIX="${prefix}" make install
        else
          DESTDIR="${destDir}" cmake --install build --prefix "${prefix}"
        fi
      fi
      package
    done
    ;;
  meson)
    for targetArch in $_buildArchs; do
      destDir="/tmp/build-${targetArch}"
      echo "target arch=> $targetArch"
      echo "Use meson..."
      cd "${_srcDir}"
      load_env "$targetArch"
      rm -rf build
      mkdir build
      #[[ ! $targetArch == $(uname -m) ]] && {
      bash "${wsDir}/cross_file/cross-${targetArch}-meson.sh"
      mesonBaseArgs+=(--cross-file=/tmp/cross-${targetArch}-meson.txt)
      #}
      if declare -F pre_setup >/dev/null; then
        echo "存在前置操作，开始执行"
        pre_setup
      fi
      meson setup build ${mesonBaseArgs[@]} ${args[@]} || configure_err
      meson compile -C build || compile_err
      if declare -F install >/dev/null; then
        DESTDIR="${destDir}" PREFIX="${prefix}" install
      else
        DESTDIR="${destDir}" PREFIX="${prefix}" meson install -C build
      fi
      package
    done
    ;;
  autotools | make)
    for targetArch in $_buildArchs; do
      destDir="/tmp/build-${targetArch}"
      echo "target arch=> $targetArch"
      echo "Use autotools..."
      cd "${_srcDir}"
      # Clean previous build artifacts to prevent arch contamination
      make distclean >/dev/null || true
      # Reload base args to prevent accumulation across iterations
      . "${wsDir}/base-configure-args.conf"
      load_env "$targetArch"
      configureBaseArgs+=(--host=${targetArch}-linux-android --build=$(uname -m)-linux-gnu)

      if declare -F pre_setup >/dev/null; then
        echo "存在前置操作,开始执行"
        pre_setup
      fi

      if [[ -f autogen.sh ]] && [[ ! $doNotUseAutogenSh == 1 ]] && [[ ! -f configure ]]; then
        if [[ ! $argsToAutogenSh == 1 ]]; then
          ./autogen.sh || { echo "autogen.sh: err" && exit 1; }
        else
          echo "构建参数传入autogen.sh"
          ./autogen.sh ${configureBaseArgs[@]} ${args[@]} || configure_err
        fi
      fi

      if declare -F custom_configure >/dev/null; then
        echo "存在自定义构建,开始执行"
        custom_configure
      else
        if [[ ! $argsToAutogenSh == 1 ]] && [[ ! $ForceAutoreconf == 1 ]]; then
          if [[ -f configure ]]; then
            ./configure ${configureBaseArgs[@]} ${args[@]} || configure_err
          else
            echo "未能找到configure,将通过autoreconf -fi自动生成"
            autoreconf -fi || { echo "autoreconf 失败!" && exit 1; }
            ./configure ${configureBaseArgs[@]} ${args[@]} || configure_err
          fi
        else
          echo "configure将通过autoreconf -fi自动生成"
          autoreconf -fi || { echo "autoreconf 失败!" && exit 1; }
          ./configure ${configureBaseArgs[@]} ${args[@]} || configure_err
        fi
      fi
      make -j$(nproc) || compile_err
      if declare -F install >/dev/null; then
        DESTDIR="${destDir}" PREFIX="${prefix}" install
      else
        PREFIX="${prefix}" make install DESTDIR="${destDir}"
      fi
      package
    done
    ;;
  debug)
    echo "加载调试环境"
    for targetArch in $_buildArchs; do
      echo "target arch=> $targetArch"
      load_env "$targetArch"
      bash
    done
    ;;
  others) if ! declare -F extra_fuction >/dev/null; then
    echo "如果buildSys 为others,那么你需要定义extra_fuction 函数!"
    exit 1
  else
    for targetArch in $_buildArchs; do
      destDir="/tmp/build-${targetArch}"
      mkdir -p "${destDir}"
      cd "${_srcDir}"
      load_env "$targetArch"
      extra_fuction || { echo "失败!" && exit 1; }
      package
    done
  fi ;;
  esac
  #EOF
}

make_pkg() {
  local _needBuild=false
  local _targetArch
  local _pkgArchs
  _pkgArchs=$(filter_archs "${arch[@]}")
  [[ -z "$_pkgArchs" ]] && {
    echo "跳过 $pjName: 目标架构不在包的 arch 列表中"
    return 0
  }

  # --rebuild pkg: 只删除指定包的 tar
  if [[ -n "${rebuildTarget:-}" && "$pjName" == "$rebuildTarget" ]]; then
    for _targetArch in $_pkgArchs; do
      local _pkgFile="${pkgDir}/${pjName}-${revision}-${_targetArch}.tar"
      if [[ -f "$_pkgFile" ]]; then
        echo "重建模式：删除已存在的包 => $_pkgFile"
        rm -f "$_pkgFile"
      fi
    done
  # --rebuild all / --rebuild-deps all: 全量删除重建
  elif [[ "$rebuildMode" == true ]]; then
    for _targetArch in $_pkgArchs; do
      local _pkgFile="${pkgDir}/${pjName}-${revision}-${_targetArch}.tar"
      if [[ -f "$_pkgFile" ]]; then
        echo "重建模式：删除已存在的包 => $_pkgFile"
        rm -f "$_pkgFile"
      fi
    done
  fi

  # 检查是否有任一架构的包缺失
  for _targetArch in $_pkgArchs; do
    local _pkgFile="${pkgDir}/${pjName}-${revision}-${_targetArch}.tar"
    if [[ ! -f "$_pkgFile" ]]; then
      _needBuild=true
      break
    fi
  done

  # 需要编译
  if $_needBuild; then
    if [[ "$hasSrc" == true ]]; then
      local _hasSrcDir="${pkgSrcDir:-${wsDir}/src/${pjName}}"
      if [[ -d "$_hasSrcDir" ]]; then
        echo "跳过源码获取: $_hasSrcDir"
      else
        echo "错误: --has-src 指定但源码目录不存在 => $_hasSrcDir"
        exit 1
      fi
    else
      get_src
    fi
    if [[ $doNotApplyPatch != 1 ]]; then
      apply_patches
    fi
    build_system
    # 构建完成后清理源码目录
    if [[ "$cleanSrcDir" == "1" ]]; then
      local _cleanTarget="${pkgSrcDir:-${wsDir}/src/${pjName}}"
      if [[ -d "$_cleanTarget" ]]; then
        echo "清理源码目录: $_cleanTarget"
        rm -rf "$_cleanTarget"
      fi
    fi
  fi

  # 只解压当前 targetArch 的包到 prefix
  for _targetArch in $_pkgArchs; do
    [[ "$_targetArch" == "$targetArch" ]] || continue
    local _pkgFile="${pkgDir}/${pjName}-${revision}-${_targetArch}.tar"
    if [[ -f "$_pkgFile" ]]; then
      tar -xf "$_pkgFile" -C / 2>/dev/null || true
      guard_dev_null
    else
      echo "错误: 包不存在 => $_pkgFile"
      exit 1
    fi
  done
}

build() {
  local _target="$1"
  create_pfx

  # 确定要构建的架构列表
  local _archs
  if [[ ${#targetArchs[@]} -eq 0 ]]; then
    _archs="aarch64 x86_64"
  else
    _archs="${targetArchs[*]}"
  fi

  # 顶层按架构循环：每次只构建一个架构，确保 prefix 中只有当前架构的包
  local _arch
  for _arch in $_archs; do
    echo "========== 构建架构: $_arch =========="
    # 临时将 targetArchs 和 targetArch 设为当前架构
    targetArchs=("$_arch")
    targetArch="$_arch"

    # 清除 prefix 中上一个架构遗留的库文件，避免架构交叉污染
    rm -f "${prefix}"/lib/*.so "${prefix}"/lib/*.a
    rm -f "${prefix}"/usr/lib/*.so "${prefix}"/usr/lib/*.a

    # 重置依赖解析状态
    unset _visited_ _rgraph_ _indeg_ _allPkgs_ _buildOrder_
    declare -A _rgraph_=() _indeg_=() _visited_=()
    declare -a _allPkgs_=() _buildOrder_=()

    collect_dep "$_target"
    toposort
    echo "构建顺序: ${_buildOrder_[*]}"
    local _project
    for _project in "${_buildOrder_[@]}"; do
      echo "构建=> $_project"
      . "${wsDir}/empty.sh"
      . "${wsDir}/projects/$_project/build.sh" || { echo "配置获取失败" && exit 1; }
      pjName="$_project"
      make_pkg || { echo "构建失败!" && exit 1; }
    done
  done
  gen_notices
}

# 重建所有包的所有依赖（全局去重，一次 pass）
build_all_deps() {
  create_pfx

  local _archs
  if [[ ${#targetArchs[@]} -eq 0 ]]; then
    _archs="aarch64 x86_64"
  else
    _archs="${targetArchs[*]}"
  fi

  local _arch
  for _arch in $_archs; do
    echo "========== 构建所有依赖: $_arch =========="
    targetArchs=("$_arch")
    targetArch="$_arch"

    rm -f "${prefix}"/lib/*.so "${prefix}"/lib/*.a
    rm -f "${prefix}"/usr/lib/*.so "${prefix}"/usr/lib/*.a

    unset _visited_ _rgraph_ _indeg_ _allPkgs_ _buildOrder_
    declare -A _rgraph_=() _indeg_=() _visited_=()
    declare -a _allPkgs_=() _buildOrder_=()

    local _pj
    for _pj in "${wsDir}"/projects/*/; do
      local _pjName=$(basename "$_pj")
      [[ -f "${_pj}/build.sh" ]] && collect_dep "$_pjName"
    done

    toposort
    echo "构建顺序: ${_buildOrder_[*]}"
    local _project
    for _project in "${_buildOrder_[@]}"; do
      echo "构建=> $_project"
      . "${wsDir}/empty.sh"
      . "${wsDir}/projects/$_project/build.sh" || { echo "配置获取失败" && exit 1; }
      pjName="$_project"
      make_pkg || { echo "构建失败!" && exit 1; }
    done
  done
  gen_notices
}

# 主逻辑
[[ $# -eq 0 ]] && usage

while [[ $# -gt 0 ]]; do
  case "$1" in
  --arch)
    [[ -z "$2" ]] && {
      echo "错误：--arch 需要指定架构"
      exit 1
    }
    if [[ "$2" == "all" ]]; then
      targetArchs=()
    else
      local _a
      for _a in $2; do
        targetArchs+=("$(normalize_arch "$_a")")
      done
    fi
    shift 2
    ;;
  --build)
    [[ -z "$2" ]] && {
      echo "错误：--build 需要指定包名或 all"
      exit 1
    }
    if [[ "$2" == "all" ]]; then
      for _pj in "${wsDir}"/projects/*/; do
        _pjName=$(basename "$_pj")
        [[ -f "${_pj}/build.sh" ]] && build "$_pjName"
      done
    else
      build "$2"
    fi
    shift 2
    ;;
  --rebuild)
    [[ -z "$2" ]] && {
      echo "错误：--rebuild 需要指定包名或 all"
      exit 1
    }
    if [[ "$2" == "all" ]]; then
      rebuildMode=true
      build_all_deps
    else
      rebuildTarget="$2"
      build "$2"
      unset rebuildTarget
    fi
    shift 2
    ;;
  --rebuild-deps)
    [[ -z "$2" ]] && {
      echo "错误：--rebuild-deps 需要指定包名或 all"
      exit 1
    }
    rebuildMode=true
    if [[ "$2" == "all" ]]; then
      build_all_deps
    else
      build "$2"
    fi
    shift 2
    ;;
  --clean-ccache)
    clean_ccache
    shift
    ;;
  --has-src)
    hasSrc=true
    shift
    ;;
  --update-host-deps)
    update_host_deps
    shift
    ;;
  --help | -h)
    usage
    ;;
  *)
    echo "未知选项: $1"
    usage
    ;;
  esac
done
