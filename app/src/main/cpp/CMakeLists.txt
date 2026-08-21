cmake_minimum_required(VERSION 3.22.1)

project(winfex LANGUAGES C CXX)

# ===== Winfex native bridge =====
# 这个 .so 不包含 Wine/Box64 本身，它只是一个轻量 JNI 桥：
#   1. 启动子进程（fork + execve）执行我们释放到 filesDir 的 Box64/Wine 二进制
#   2. 把 stdout/stderr 通过 pipe 回吐给 Kotlin 层
#   3. 提供环境变量、argv、cgroup CPU 亲和性、PR_SET_NO_NEW_PRIVS 等设置
#
# 真正的 wine64 / box64 / dxvk .so 由用户在 app/src/main/assets/{wine,box64,dxvk} 里
# 自行放入，运行时由 NativeLoader 解压到 /data/data/com.winfex/files/native/
# 再 exec。本工程不重新编译 Wine / Box64 源码。

add_library(winfex SHARED
        winfex_jni.c
        process_executor.c
        cpu_affinity.c
        xtest_injector.c
)

target_compile_options(winfex PRIVATE
        -Wall -Wextra -Wno-unused-parameter
        -O2 -fvisibility=hidden
        -ffunction-sections -fdata-sections
)

target_link_libraries(winfex
        log
        android
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(winfex ${log-lib} ${android-lib})
