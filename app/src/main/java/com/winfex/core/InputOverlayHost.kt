package com.winfex.core

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.winfex.core.InputController
import com.winfex.model.InputProfile
import com.winfex.ui.input.InputOverlayView

/**
 * 系统级输入叠加层管理器。
 *
 * 用 WindowManager TYPE_APPLICATION_OVERLAY 把 InputOverlayView 加在所有应用之上，
 * 这样无论前台是 com.winfex.xserver.XServerActivity 还是 Wine 跑的窗口，
 * 虚拟按键都显示在屏幕上。
 *
 * 需要 android.permission.SYSTEM_ALERT_WINDOW 权限（用户在系统设置里授权）。
 *
 * 设计：
 *   - 单例，全局只有一个 overlay
 *   - setProfile(profile) 切换输入方案
 *   - show() / hide() 控制可见性
 *   - 触摸事件未命中元素时，让 InputController.dispatchMouseMove 转发到 X server
 *     （注意：这会"双重消费"触摸，因为 Lorie Activity 自己也在处理触摸。
 *      v0.4 的简化做法是：要么用 overlay 接管所有触摸，要么完全用 Lorie Activity。
 *      v0.5 引入"模式切换"：纯 Lorie / 纯 overlay / 混合）
 */
object InputOverlayHost {

    private const val TAG = "InputOverlayHost"

    private var windowManager: WindowManager? = null
    private var overlayView: InputOverlayView? = null
    private var overlayContainer: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    @Volatile private var added = false
    @Volatile private var profile: InputProfile? = null

    /**
     * 检查是否有 overlay 权限。
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    /**
     * 请求 overlay 权限（跳系统设置）。
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !canDrawOverlays(context)) {
            val intent = android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * 设置当前输入方案。
     */
    fun setProfile(p: InputProfile?) {
        profile = p
        overlayView?.setProfile(p)
        InputController.setActive(p)
    }

    /**
     * 显示叠加层。
     */
    fun show(context: Context) {
        if (!canDrawOverlays(context)) {
            Log.w(TAG, "no SYSTEM_ALERT_WINDOW permission, overlay disabled")
            return
        }
        if (added) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        layoutParams = params

        // 用 FrameLayout 包装，便于以后加更多 overlay 子 view
        val container = FrameLayout(context)
        val overlay = InputOverlayView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setProfile(profile)
        }
        container.addView(overlay)
        overlayContainer = container
        overlayView = overlay

        try {
            wm.addView(container, params)
            added = true
            Log.i(TAG, "overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
        }
    }

    /**
     * 隐藏叠加层。
     */
    fun hide() {
        if (!added) return
        val container = overlayContainer ?: return
        val wm = windowManager ?: return
        try {
            wm.removeView(container)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed: ${e.message}")
        }
        added = false
        overlayContainer = null
        overlayView = null
    }

    /**
     * 切换可见性。
     */
    fun toggle(context: Context) {
        if (added) hide() else show(context)
    }

    val isVisible: Boolean get() = added
}
