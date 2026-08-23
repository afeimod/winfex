revision="14.3.1"
url="https://github.com/harfbuzz/harfbuzz.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="MIT"
args="
  -Dcpp_std=c++17
  -Ddocs=disabled
  -Dgobject=disabled
  -Dgraphite=disabled
  -Dintrospection=disabled
  -Dtests=disabled
  -Dfreetype=enabled
  -Ddocs=disabled
"
deps="freetype glib"
