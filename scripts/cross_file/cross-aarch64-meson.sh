#!/bin/bash
# Strip ccache prefix from compiler paths for Meson cross file
# Meson handles ccache via USE_CCACHE=1 environment variable
_cOnly="${CC#ccache }"
_cppOnly="${CXX#ccache }"

# Use PKG_CONFIG_SYSROOT_DIR or default prefix
_prefix="${PKG_CONFIG_SYSROOT_DIR:-/tmp/bionic-prefix}"

cat > /tmp/cross-aarch64-meson.txt << EOF
[binaries]
c = '${_cOnly}'
cpp = '${_cppOnly}'
ar = '${AR}'
strip = '${STRIP}'
ranlib = '${RANLIB}'
ld = '${LD}'

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_args = ['-I${_prefix}/usr/include', '-I${_prefix}/include']
cpp_args = ['-I${_prefix}/usr/include', '-I${_prefix}/include']
c_link_args = ['-L${_prefix}/lib']
cpp_link_args = ['-L${_prefix}/lib']

[properties]
needs_exe_wrapper = true
EOF