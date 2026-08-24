revision="0.46.4"
url="https://gitlab.freedesktop.org/pixman/pixman/-/archive/${revision}/pixman-${revision}.tar.gz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="meson"
license="MIT"
args="
  -Dlibpng=disabled
  -Dtests=disabled
"
deps="zlib"
