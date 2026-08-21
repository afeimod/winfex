// winfex_jni.c — JNI 入口
//
// 暴露给 Kotlin 的方法：
//   - nativeExecBinary(...)        : int   fork + execve 启动外部进程
//   - nativeSetupProcess(mask,adj) : void  设置 CPU 亲和性 + OOM
//   - nativeKillProcessGroup(pgid) : int   杀进程组
//   - nativeSymlink(target, link)  : int   创建符号链接
//   - nativeChmod(path, mode)      : int   修改文件权限

#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <android/log.h>

#define TAG "winfex-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern int winfex_exec_binary(const char *path,
                              const char *const *argv,
                              const char *const *envp,
                              const char *working_dir,
                              int stdin_fd,
                              int stdout_fd,
                              int stderr_fd,
                              int *out_pid);

extern void winfex_apply_cpu_affinity(unsigned long mask);
extern void winfex_set_oom_score_adj(int adj);
extern int  winfex_kill_process_group(int pgid);

static const char *const *jobject_array_to_carray(JNIEnv *env, jobjectArray jarr) {
    if (jarr == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, jarr);
    const char **arr = calloc((size_t)n + 1, sizeof(char *));
    if (arr == NULL) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jarr, i);
        arr[i] = (s == NULL) ? NULL : (*env)->GetStringUTFChars(env, s, NULL);
    }
    arr[n] = NULL;
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_winfex_native_NativeBridge_nativeExecBinary(
        JNIEnv *env, jobject thiz,
        jstring j_path,
        jobjectArray j_argv,
        jobjectArray j_envp,
        jstring j_workdir,
        jint j_stdin_fd,
        jint j_stdout_fd,
        jint j_stderr_fd,
        jintArray j_out_pid) {

    const char *path    = (*env)->GetStringUTFChars(env, j_path, NULL);
    const char *workdir = j_workdir ? (*env)->GetStringUTFChars(env, j_workdir, NULL) : NULL;

    const char *const *argv = jobject_array_to_carray(env, j_argv);
    const char *const *envp = jobject_array_to_carray(env, j_envp);

    int pid = -1;
    int rc = winfex_exec_binary(path, argv, envp, workdir,
                                (int) j_stdin_fd, (int) j_stdout_fd, (int) j_stderr_fd,
                                &pid);

    if (j_out_pid != NULL && (*env)->GetArrayLength(env, j_out_pid) >= 1) {
        jint v = (jint) pid;
        (*env)->SetIntArrayRegion(env, j_out_pid, 0, 1, &v);
    }

    LOGI("exec %s rc=%d pid=%d", path, rc, pid);

    (*env)->ReleaseStringUTFChars(env, j_path, path);
    if (workdir) (*env)->ReleaseStringUTFChars(env, j_workdir, workdir);
    free((void *) argv);
    free((void *) envp);
    return (jint) rc;
}

JNIEXPORT void JNICALL
Java_com_winfex_native_NativeBridge_nativeSetupProcess(
        JNIEnv *env, jobject thiz,
        jlong j_cpu_mask,
        jint j_oom_adj) {
    winfex_apply_cpu_affinity((unsigned long) j_cpu_mask);
    winfex_set_oom_score_adj((int) j_oom_adj);
}

JNIEXPORT jint JNICALL
Java_com_winfex_native_NativeBridge_nativeKillProcessGroup(
        JNIEnv *env, jobject thiz, jint j_pgid) {
    return (jint) winfex_kill_process_group((int) j_pgid);
}

JNIEXPORT jint JNICALL
Java_com_winfex_native_NativeBridge_nativeSymlink(
        JNIEnv *env, jobject thiz,
        jstring j_target, jstring j_link) {
    const char *target = (*env)->GetStringUTFChars(env, j_target, NULL);
    const char *link   = (*env)->GetStringUTFChars(env, j_link,   NULL);
    int rc = symlink(target, link);   // 失败返回 -1，errno 通过 rc 间接传达
    (*env)->ReleaseStringUTFChars(env, j_target, target);
    (*env)->ReleaseStringUTFChars(env, j_link,   link);
    return (jint) rc;
}

JNIEXPORT jint JNICALL
Java_com_winfex_native_NativeBridge_nativeChmod(
        JNIEnv *env, jobject thiz,
        jstring j_path, jint j_mode) {
    const char *path = (*env)->GetStringUTFChars(env, j_path, NULL);
    int rc = chmod(path, (mode_t) j_mode);
    (*env)->ReleaseStringUTFChars(env, j_path, path);
    return (jint) rc;
}
