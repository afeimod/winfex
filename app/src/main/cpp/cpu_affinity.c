// cpu_affinity.c — CPU 亲和性 + OOM 调整
//
// 骁龙 8 Gen2 / Gen3 大小核结构：
//   - Cortex-X3/X4 (超大核)   : 1 个，跑 Wine 主线程
//   - Cortex-A715/A720 (大核) : 2-4 个，跑 Box64 翻译线程
//   - Cortex-A510/A520 (小核) : 3-4 个，留空，不调度
//
// 这里提供一个 mask 接口，Kotlin 侧根据设置页勾选决定绑定哪些核。
// 注意：Android 9+ 的 sched_setaffinity 是允许的，但 cgroup 也可能
// 强制覆盖。如果需要更严格的隔离，需要 root，这里不强求。

#define _GNU_SOURCE
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <android/log.h>

#define TAG "winfex-cpu"

void winfex_apply_cpu_affinity(unsigned long mask) {
    if (mask == 0) return;  // 0 = 不修改
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < 64; i++) {
        if (mask & (1UL << i)) CPU_SET(i, &set);
    }
    if (sched_setaffinity(0, sizeof(set), &set) != 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "sched_setaffinity mask=0x%lx failed: %s",
                            mask, strerror(errno));
    } else {
        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "sched_setaffinity mask=0x%lx ok", mask);
    }
}

void winfex_set_oom_score_adj(int adj) {
    char buf[16];
    int n = snprintf(buf, sizeof(buf), "%d", adj);
    int fd = open("/proc/self/oom_score_adj", O_WRONLY | O_CLOEXEC);
    if (fd < 0) return;
    write(fd, buf, (size_t) n);
    close(fd);
}

int winfex_kill_process_group(int pgid) {
    if (pgid <= 0) return -1;
    // 先 SIGTERM 给 Wine 一个优雅退出的机会
    if (kill(-pgid, SIGTERM) != 0) {
        return -errno;
    }
    // 等 1.5s
    int waited = 0;
    while (waited < 1500) {
        if (kill(-pgid, 0) != 0 && errno == ESRCH) return 0;
        usleep(100 * 1000);
        waited += 100;
    }
    // 还活着，SIGKILL
    if (kill(-pgid, SIGKILL) != 0) {
        return -errno;
    }
    return 0;
}
