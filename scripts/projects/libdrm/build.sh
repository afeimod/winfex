revision="libdrm-2.4.134"
url="https://gitlab.freedesktop.org/mesa/drm.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license='MIT'
args="
  -Damdgpu=disabled
  -Dcairo-tests=disabled
  -Detnaviv=disabled
  -Dfreedreno=enabled
  -Dfreedreno-kgsl=true
  -Dintel=disabled
  -Dman-pages=disabled
  -Dnouveau=disabled
  -Dradeon=disabled
  -Dtests=false
  -Dvalgrind=disabled
  -Dvc4=disabled
  -Dvmwgfx=disabled
"
pre_setup() {
  CFLAGS+=" -DANDROID"
}
