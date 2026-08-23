# icu4c
revision="78.3"
url="https://github.com/unicode-org/icu/releases/download/release-${revision}/icu4c-${revision}-sources.tgz"
urlType="tar"
license="Unicode-DFS-2016"
pkgSrcDir="${wsDir}/src/icu/source"
arch="aarch64 x86_64"
buildSys="autotools"
args="--disable-samples --disable-tests"
custom_configure() {
  if [[ ! -f host_build/Makefile ]]; then
    mkdir -p host_build
    cd host_build || { echo "ICU: cd host_build failed" && exit 1; }
    load_env_host
    ../runConfigureICU Linux/gcc || { echo "ICU: host configure failed" && exit 1; }
    make -j$(nproc) || { echo "ICU: host build failed" && exit 1; }
  fi
  load_env "$targetArch"
  cd "${pkgSrcDir}" || { echo "ICU: cd pkgSrcDir failed" && exit 1; }
  mkdir -p "${targetArch}_build"
  cd "${targetArch}_build" || { echo "ICU: cd ${targetArch}_build failed" && exit 1; }
  ../configure ${configureBaseArgs[@]} ${args[@]} --with-cross-build="${pkgSrcDir}/host_build" || configure_err
}