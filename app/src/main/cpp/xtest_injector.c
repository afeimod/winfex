// xtest_injector.c — XTEST 扩展注入器
//
// 用 XTEST 扩展把按键 / 鼠标事件注入到 X server，让 Wine 进程能收到。
//
// 优点（相比 uinput / Lorie JNI）：
//   - 不需要 root
//   - 标准 X11 协议，跨进程
//   - Wine 的 winex11.drv 原生识别（XTest 是核心键盘/指针事件）
//   - Lorie 已经把 xtest.c 编译进 Xext，开箱即用
//
// 依赖：libX11.so + libXtst.so（动态加载，运行时 dlopen）
//   - 在 com.winfex 的 Core .rat 包里应该包含这两个库
//   - 如果不存在，本注入器会优雅降级（fail-safe，不崩）

#define _GNU_SOURCE
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <android/log.h>
#include <unistd.h>

#define TAG "winfex-xtest"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

// X11 类型 forward declaration（只声明我们用到的）
typedef struct _XDisplay Display;
typedef unsigned long Window;
typedef unsigned long Atom;
typedef unsigned long Time;
typedef unsigned long KeyCode;
typedef unsigned int Bool;
#define CurrentTime 0L
#define True 1
#define False 0

// libX11 函数指针类型
typedef Display* (*XOpenDisplay_fn)(const char*);
typedef int      (*XCloseDisplay_fn)(Display*);
typedef int      (*XFlush_fn)(Display*);
typedef int      (*XSync_fn)(Display*, Bool);
typedef int      (*XTestFakeKeyEvent_fn)(Display*, unsigned int, Bool, unsigned long);
typedef int      (*XTestFakeButtonEvent_fn)(Display*, unsigned int, Bool, unsigned long);
typedef int      (*XTestFakeMotionEvent_fn)(Display*, int, int, int, unsigned long);
typedef int      (*XTestFakeRelativeMotionEvent_fn)(Display*, int, int, int, unsigned long);

// libXtst 函数指针类型
typedef Bool (*XTestQueryExtension_fn)(Display*, int*, int*, int*, int*);

// 全局状态
static struct {
    void* libX11;
    void* libXtst;
    Display* display;
    // libX11 函数
    XOpenDisplay_fn  XOpenDisplay;
    XCloseDisplay_fn XCloseDisplay;
    XFlush_fn        XFlush;
    XSync_fn         XSync;
    // libXtst 函数
    XTestFakeKeyEvent_fn             XTestFakeKeyEvent;
    XTestFakeButtonEvent_fn          XTestFakeButtonEvent;
    XTestFakeMotionEvent_fn          XTestFakeMotionEvent;
    XTestFakeRelativeMotionEvent_fn  XTestFakeRelativeMotionEvent;
} xtest = {0};

// JNI 全局 VM，用于 attach
static JavaVM* g_vm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// 在子线程里加载库（避免阻塞主线程）
static int load_libraries(const char* lib_dir) {
    if (xtest.libX11 && xtest.libXtst) return 0;

    char path[512];

    // libX11.so
    if (!xtest.libX11) {
        snprintf(path, sizeof(path), "%s/libX11.so", lib_dir);
        xtest.libX11 = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        if (!xtest.libX11) {
            LOGE("dlopen libX11.so failed: %s", dlerror());
            return -1;
        }
        xtest.XOpenDisplay  = (XOpenDisplay_fn)  dlsym(xtest.libX11, "XOpenDisplay");
        xtest.XCloseDisplay = (XCloseDisplay_fn) dlsym(xtest.libX11, "XCloseDisplay");
        xtest.XFlush         = (XFlush_fn)         dlsym(xtest.libX11, "XFlush");
        xtest.XSync          = (XSync_fn)          dlsym(xtest.libX11, "XSync");
        if (!xtest.XOpenDisplay || !xtest.XCloseDisplay || !xtest.XFlush || !xtest.XSync) {
            LOGE("libX11 missing symbols");
            return -1;
        }
        LOGI("libX11.so loaded from %s", path);
    }

    // libXtst.so
    if (!xtest.libXtst) {
        snprintf(path, sizeof(path), "%s/libXtst.so", lib_dir);
        xtest.libXtst = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        if (!xtest.libXtst) {
            LOGE("dlopen libXtst.so failed: %s", dlerror());
            return -1;
        }
        // XTest* 函数在 libXtst.so 里
        xtest.XTestFakeKeyEvent            = (XTestFakeKeyEvent_fn)            dlsym(xtest.libXtst, "XTestFakeKeyEvent");
        xtest.XTestFakeButtonEvent         = (XTestFakeButtonEvent_fn)         dlsym(xtest.libXtst, "XTestFakeButtonEvent");
        xtest.XTestFakeMotionEvent         = (XTestFakeMotionEvent_fn)         dlsym(xtest.libXtst, "XTestFakeMotionEvent");
        xtest.XTestFakeRelativeMotionEvent = (XTestFakeRelativeMotionEvent_fn) dlsym(xtest.libXtst, "XTestFakeRelativeMotionEvent");
        if (!xtest.XTestFakeKeyEvent || !xtest.XTestFakeButtonEvent
            || !xtest.XTestFakeMotionEvent || !xtest.XTestFakeRelativeMotionEvent) {
            LOGE("libXtst missing XTest symbols");
            return -1;
        }
        LOGI("libXtst.so loaded from %s", path);
    }

    return 0;
}

// JNI 接口

JNIEXPORT jboolean JNICALL
Java_com_winfex_input_XTestInjector_nativeInit(JNIEnv* env, jclass cls, jstring j_lib_dir) {
    const char* lib_dir = (*env)->GetStringUTFChars(env, j_lib_dir, NULL);
    int rc = load_libraries(lib_dir);
    (*env)->ReleaseStringUTFChars(env, j_lib_dir, lib_dir);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_winfex_input_XTestInjector_nativeConnect(JNIEnv* env, jclass cls, jstring j_display) {
    if (!xtest.XOpenDisplay) {
        LOGE("libX11 not loaded, call nativeInit first");
        return JNI_FALSE;
    }
    if (xtest.display) {
        return JNI_TRUE;  // 已连接
    }
    const char* display = j_display ? (*env)->GetStringUTFChars(env, j_display, NULL) : NULL;
    xtest.display = xtest.XOpenDisplay(display);
    if (display) (*env)->ReleaseStringUTFChars(env, j_display, display);
    if (!xtest.display) {
        LOGE("XOpenDisplay failed");
        return JNI_FALSE;
    }

    // 检查 XTEST 扩展是否可用
    int ev_base = 0, err_base = 0, major = 0, minor = 0;
    XTestQueryExtension_fn query = (XTestQueryExtension_fn) dlsym(xtest.libXtst, "XTestQueryExtension");
    if (query) {
        if (!query(xtest.display, &ev_base, &err_base, &major, &minor)) {
            LOGE("XTEST extension not available on this X server");
            xtest.XCloseDisplay(xtest.display);
            xtest.display = NULL;
            return JNI_FALSE;
        }
        LOGI("XTEST extension ready (v%d.%d)", major, minor);
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_winfex_input_XTestInjector_nativeDisconnect(JNIEnv* env, jclass cls) {
    if (xtest.display && xtest.XCloseDisplay) {
        xtest.XCloseDisplay(xtest.display);
        xtest.display = NULL;
        LOGI("X server disconnected");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_winfex_input_XTestInjector_nativeInjectKey(JNIEnv* env, jclass cls,
        jint x_keycode, jboolean is_down) {
    if (!xtest.display || !xtest.XTestFakeKeyEvent) return JNI_FALSE;
    int rc = xtest.XTestFakeKeyEvent(xtest.display, (unsigned int) x_keycode,
                                     is_down ? True : False, CurrentTime);
    xtest.XFlush(xtest.display);
    return rc != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_winfex_input_XTestInjector_nativeInjectMouseButton(JNIEnv* env, jclass cls,
        jint button, jboolean is_down) {
    if (!xtest.display || !xtest.XTestFakeButtonEvent) return JNI_FALSE;
    int rc = xtest.XTestFakeButtonEvent(xtest.display, (unsigned int) button,
                                        is_down ? True : False, CurrentTime);
    xtest.XFlush(xtest.display);
    return rc != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_winfex_input_XTestInjector_nativeInjectMouseAbs(JNIEnv* env, jclass cls,
        jint x, jint y) {
    if (!xtest.display || !xtest.XTestFakeMotionEvent) return JNI_FALSE;
    // screen = -1 表示当前默认 screen
    int rc = xtest.XTestFakeMotionEvent(xtest.display, -1, x, y, CurrentTime);
    xtest.XFlush(xtest.display);
    return rc != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_winfex_input_XTestInjector_nativeInjectMouseMoveRelative(JNIEnv* env, jclass cls,
        jint dx, jint dy) {
    if (!xtest.display || !xtest.XTestFakeRelativeMotionEvent) return JNI_FALSE;
    int rc = xtest.XTestFakeRelativeMotionEvent(xtest.display, -1, dx, dy, CurrentTime);
    xtest.XFlush(xtest.display);
    return rc != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_winfex_input_XTestInjector_nativeIsReady(JNIEnv* env, jclass cls) {
    return (xtest.display != NULL) ? JNI_TRUE : JNI_FALSE;
}
