revision="1.5.0"
url="https://github.com/xiph/flac.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="cmake"
license="BSD-3-Clause"
args="
  -DBUILD_SHARED_LIBS=ON
  -DBUILD_STATIC_LIBS=OFF
  -DFLAC_BUILD_EXAMPLES=OFF
  -DFLAC_BUILD_TESTS=OFF
  -DFLAC_BUILD_DOCS=OFF
  -DINSTALL_MANPAGES=OFF
"
deps="libogg"
