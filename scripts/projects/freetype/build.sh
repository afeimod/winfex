revision="VER-2-14-3"
url="https://github.com/freetype/freetype.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="FTL-1.1 OR GPL-2.0-or-later"
args="
  -Dbrotli=enabled
  -Dbzip2=disabled
  -Dharfbuzz=auto
  -Dmmap=enabled
  -Dpng=enabled
  -Dtests=disabled
  -Dzlib=disabled
"
deps="brotli bzip2 libpng zlib"