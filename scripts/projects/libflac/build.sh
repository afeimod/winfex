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
  -DOGG_INCLUDE_DIR=${prefix}/include
  -DOGG_LIBRARY=${prefix}/lib/libogg.so
  -DCMAKE_FIND_ROOT_PATH=${prefix}
  -DICONV_INCLUDE_DIR=${prefix}/include
  -DICONV_LIBRARY=${prefix}/lib/libiconv.so
"
deps="libogg libiconv"

pre_setup() {
  export PKG_CONFIG_PATH="${prefix}/lib/pkgconfig:${prefix}/share/pkgconfig"
  LDFLAGS+=" -L${prefix}/lib -liconv"
}
