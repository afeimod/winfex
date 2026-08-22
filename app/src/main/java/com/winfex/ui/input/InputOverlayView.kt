package com.winfex.ui.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.winfex.core.InputController
import com.winfex.model.Binding
import com.winfex.model.Element
import com.winfex.model.ElementType
import com.winfex.model.InputProfile
import com.winfex.model.Shape
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * 虚拟按键叠加层 —— 参考 Winlator InputControlsView。
 *
 * 设计要点：
 *   - 坐标系：屏幕相对坐标 0..1，元素位置在 profile 里存相对值
 *   - 多指针：每个 pointerId 跟踪一个元素，松开时清理
 *   - 命中检测：先找命中元素，命中消费事件；未命中 return false 让事件传到下层
 *   - 视觉反馈：按下时填充半透明高亮
 *   - STICK/TRACKPAD：拖动产生方向 binding（UP/RIGHT/DOWN/LEFT）
 *
 * 嵌入方式：放在 LorieView 之上、其他 UI 之下，宽度高度 match_parent
 */
class InputOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var profile: InputProfile? = null
    private var editing: Boolean = false

    /** 编辑模式回调 */
    private var onElementTappedListener: ((String) -> Unit)? = null
    private var onBackgroundTappedListener: ((Pair<Float, Float>) -> Unit)? = null
    private var onElementMovedListener: ((String, Float, Float) -> Unit)? = null

    /** 编辑模式拖拽状态 */
    private var draggingElementId: String? = null
    private var draggingPointerId: Int = -1

    /** 当前按下的指针 → 命中的元素 */
    private val pointerElements = mutableMapOf<Int, TouchState>()

    /** 元素的视觉按下状态（用于 onDraw） */
    private val pressedElements = mutableSetOf<String>()

    /** STICK/TRACKPAD 当前角度（弧度），null=居中 */
    private val stickAngles = mutableMapOf<String, Float>()

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(40, 124, 92, 255)
    }
    private val paintPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 124, 92, 255)
    }
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(180, 124, 92, 255)
        strokeWidth = 3f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private data class TouchState(
        val elementId: String,
        val startRawX: Float,
        val startRawY: Float,
        val centerX: Float,
        val centerY: Float,
        val radius: Float
    )

    fun setProfile(p: InputProfile?) {
        profile = p
        pressedElements.clear()
        stickAngles.clear()
        pointerElements.clear()
        invalidate()
    }

    fun setEditing(editing: Boolean) {
        this.editing = editing
        invalidate()
    }

    fun setOnElementTappedListener(l: (String) -> Unit) { onElementTappedListener = l }
    fun setOnBackgroundTappedListener(l: (Pair<Float, Float>) -> Unit) { onBackgroundTappedListener = l }
    fun setOnElementMovedListener(l: (String, Float, Float) -> Unit) { onElementMovedListener = l }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = profile ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val minSide = minOf(w, h)

        for (element in p.elements) {
            val cx = element.x * w
            val cy = element.y * h
            val r = baseRadiusFor(element) * minSide * element.scale
            val isPressed = element.id in pressedElements

            val fillPaint = if (isPressed) paintPressed else paintFill
            when (element.shape) {
                Shape.CIRCLE, Shape.SQUARE -> {
                    if (element.shape == Shape.CIRCLE) {
                        canvas.drawCircle(cx, cy, r, fillPaint)
                        canvas.drawCircle(cx, cy, r, paintStroke)
                    } else {
                        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                        canvas.drawRect(rect, fillPaint)
                        canvas.drawRect(rect, paintStroke)
                    }
                }
                Shape.RECT, Shape.ROUND_RECT -> {
                    val rw = r * 1.6f
                    val rh = r * 0.8f
                    val rect = RectF(cx - rw, cy - rh, cx + rw, cy + rh)
                    if (element.shape == Shape.ROUND_RECT) {
                        canvas.drawRoundRect(rect, rh, rh, fillPaint)
                        canvas.drawRoundRect(rect, rh, rh, paintStroke)
                    } else {
                        canvas.drawRect(rect, fillPaint)
                        canvas.drawRect(rect, paintStroke)
                    }
                }
            }

            // STICK / TRACKPAD 画当前摇杆位置
            if (element.type == ElementType.STICK || element.type == ElementType.TRACKPAD) {
                val angle = stickAngles[element.id]
                if (angle != null) {
                    val px = cx + cos(angle) * r * 0.6f
                    val py = cy + sin(angle) * r * 0.6f
                    canvas.drawCircle(px, py, r * 0.25f, paintPressed)
                    canvas.drawCircle(px, py, r * 0.25f, paintStroke)
                } else {
                    canvas.drawCircle(cx, cy, r * 0.25f, paintStroke)
                }
            }

            // 文字
            if (element.text.isNotEmpty()) {
                canvas.drawText(element.text, cx, cy + paintText.textSize / 3, paintText)
            }
        }

        // 编辑模式画网格
        if (editing) {
            val gridPaint = Paint().apply {
                color = Color.argb(40, 255, 255, 255)
                strokeWidth = 1f
            }
            val cols = 12
            val rows = 8
            for (i in 0..cols) {
                val x = w * i / cols
                canvas.drawLine(x, 0f, x, h, gridPaint)
            }
            for (i in 0..rows) {
                val y = h * i / rows
                canvas.drawLine(0f, y, w, y, gridPaint)
            }
        }
    }

    private fun baseRadiusFor(element: Element): Float = when (element.type) {
        ElementType.BUTTON -> 0.06f
        ElementType.DPAD -> 0.08f
        ElementType.STICK -> 0.09f
        ElementType.TRACKPAD -> 0.10f
        ElementType.RANGE_BUTTON -> 0.06f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) return handleEditingTouch(event)

        val p = profile ?: return false
        val w = width.toFloat()
        val h = height.toFloat()
        val minSide = minOf(w, h)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i)
                val y = event.getY(i)
                val pid = event.getPointerId(i)
                val hit = findHitElement(p, x, y, w, h, minSide)
                if (hit != null) {
                    val (element, cx, cy, r) = hit
                    pointerElements[pid] = TouchState(element.id, x, y, cx, cy, r)
                    pressedElements.add(element.id)
                    handleElementDown(element, cx, cy, x, y)
                    invalidate()
                    return true
                }
                return false  // 未命中，让事件透传给下层 LorieView
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val state = pointerElements[pid] ?: continue
                    val element = p.elements.firstOrNull { it.id == state.elementId } ?: continue
                    val x = event.getX(i)
                    val y = event.getY(i)
                    handleElementMove(element, state, x, y)
                }
                return pointerElements.isNotEmpty()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val pid = event.getPointerId(i)
                val state = pointerElements.remove(pid) ?: return false
                val element = p.elements.firstOrNull { it.id == state.elementId }
                if (element != null) {
                    handleElementUp(element)
                    pressedElements.remove(element.id)
                    stickAngles.remove(element.id)
                    // 如果没有其他指针按着这个元素了，才真正松开
                    val stillPressed = pointerElements.values.any { it.elementId == element.id }
                    if (stillPressed) {
                        pressedElements.add(element.id)
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // 全部松开
                for (state in pointerElements.values) {
                    val element = p.elements.firstOrNull { it.id == state.elementId }
                    if (element != null) {
                        handleElementUp(element)
                    }
                }
                pointerElements.clear()
                pressedElements.clear()
                stickAngles.clear()
                invalidate()
                return true
            }
        }
        return false
    }

    private fun findHitElement(
        p: InputProfile, x: Float, y: Float, w: Float, h: Float, minSide: Float
    ): Hit? {
        // 从后往前找（后画的在上层）
        for (element in p.elements.reversed()) {
            val cx = element.x * w
            val cy = element.y * h
            val r = baseRadiusFor(element) * minSide * element.scale
            val dx = x - cx
            val dy = y - cy
            val hit = when (element.shape) {
                Shape.CIRCLE -> (dx * dx + dy * dy) <= r * r
                Shape.SQUARE -> abs(dx) <= r && abs(dy) <= r
                Shape.RECT, Shape.ROUND_RECT -> {
                    val rw = r * 1.6f; val rh = r * 0.8f
                    abs(dx) <= rw && abs(dy) <= rh
                }
            }
            if (hit) return Hit(element, cx, cy, r)
        }
        return null
    }

    private data class Hit(val element: Element, val cx: Float, val cy: Float, val r: Float)

    private fun handleElementDown(element: Element, cx: Float, cy: Float, x: Float, y: Float) {
        when (element.type) {
            ElementType.BUTTON, ElementType.DPAD -> {
                if (element.type == ElementType.BUTTON) {
                    // BUTTON：触发所有 binding
                    for (binding in element.bindings) {
                        InputController.dispatchBinding(binding, isDown = true, elementId = element.id)
                    }
                } else {
                    // DPAD：根据触摸点相对中心的方向触发对应 binding
                    val dir = directionFromDelta(x - cx, y - cy)
                    dispatchDirectionalBinding(element, dir, isDown = true)
                }
            }
            ElementType.STICK, ElementType.TRACKPAD -> {
                // 摇杆/触摸板：初始位置即中心
                stickAngles[element.id] = 0f
            }
            ElementType.RANGE_BUTTON -> { /* TODO */ }
        }
    }

    private fun handleElementMove(element: Element, state: TouchState, x: Float, y: Float) {
        when (element.type) {
            ElementType.STICK, ElementType.TRACKPAD -> {
                val dx = x - state.centerX
                val dy = y - state.centerY
                val dist = hypot(dx, dy)
                val r = state.radius

                if (dist < r * element.deadZone) {
                    // 在死区内，相当于居中
                    if (stickAngles[element.id] != null) {
                        // 之前有方向，松开所有方向 binding
                        for (dir in Direction4.entries) {
                            val binding = element.bindings.getOrNull(dir.ordinal) ?: continue
                            InputController.dispatchBinding(binding, isDown = false, elementId = element.id)
                        }
                        stickAngles.remove(element.id)
                        invalidate()
                    }
                    return
                }

                val angle = atan2(dy, dx)
                val newDir = directionFromAngle(angle)

                // 检查方向是否变化
                val oldAngle = stickAngles[element.id]
                if (oldAngle != null) {
                    val oldDir = directionFromAngle(oldAngle)
                    if (oldDir == newDir) {
                        // 同方向，仅更新角度
                        stickAngles[element.id] = angle
                        invalidate()
                        return
                    }
                    // 方向变了，松开旧方向
                    val oldBinding = element.bindings.getOrNull(oldDir.ordinal)
                    if (oldBinding != null) {
                        InputController.dispatchBinding(oldBinding, isDown = false, elementId = element.id)
                    }
                }

                // 触发新方向
                val newBinding = element.bindings.getOrNull(newDir.ordinal)
                if (newBinding != null) {
                    InputController.dispatchBinding(newBinding, isDown = true, elementId = element.id)
                }
                stickAngles[element.id] = angle
                invalidate()

                // TRACKPAD 还可以直接发鼠标相对移动
                if (element.type == ElementType.TRACKPAD) {
                    val normX = (dx / r).coerceIn(-1f, 1f)
                    val normY = (dy / r).coerceIn(-1f, 1f)
                    InputController.dispatchMouseMove(normX * 4f, normY * 4f)
                }
            }
            else -> { /* BUTTON 不处理 move */ }
        }
    }

    private fun handleElementUp(element: Element) {
        // 松开所有 binding
        InputController.releaseAllBindings(element)
    }

    private enum class Direction4 { RIGHT, DOWN, LEFT, UP }

    private fun directionFromDelta(dx: Float, dy: Float): Direction4 {
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) Direction4.RIGHT else Direction4.LEFT
        } else {
            if (dy > 0) Direction4.DOWN else Direction4.UP
        }
    }

    private fun directionFromAngle(angle: Float): Direction4 {
        // angle ∈ [-π, π]，0=右，π/2=下，π=左，-π/2=上
        val deg = Math.toDegrees(angle.toDouble())
        return when {
            deg >= -45 && deg < 45 -> Direction4.RIGHT
            deg >= 45 && deg < 135 -> Direction4.DOWN
            deg >= 135 || deg < -135 -> Direction4.LEFT
            else -> Direction4.UP
        }
    }

    private fun dispatchDirectionalBinding(element: Element, dir: Direction4, isDown: Boolean) {
        val binding = element.bindings.getOrNull(dir.ordinal) ?: return
        InputController.dispatchBinding(binding, isDown, elementId = element.id)
    }

    // ===== 编辑模式触摸处理 =====

    private fun handleEditingTouch(event: MotionEvent): Boolean {
        val p = profile ?: return false
        val w = width.toFloat()
        val h = height.toFloat()
        val minSide = minOf(w, h)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i)
                val y = event.getY(i)
                val pid = event.getPointerId(i)
                val hit = findHitElement(p, x, y, w, h, minSide)
                if (hit != null) {
                    draggingElementId = hit.element.id
                    draggingPointerId = pid
                    onElementTappedListener?.invoke(hit.element.id)
                    pressedElements.add(hit.element.id)
                    invalidate()
                    return true
                } else {
                    // 点击空白：通知添加元素
                    onBackgroundTappedListener?.invoke((x / w) to (y / h))
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingElementId == null) return false
                val i = event.findPointerIndex(draggingPointerId)
                if (i < 0) return false
                val x = event.getX(i)
                val y = event.getY(i)
                val relX = (x / w).coerceIn(0f, 1f)
                val relY = (y / h).coerceIn(0f, 1f)
                // 吸附到 1/24 网格
                val grid = 1f / 24
                val snappedX = (round(relX / grid) * grid)
                val snappedY = (round(relY / grid) * grid)
                onElementMovedListener?.invoke(draggingElementId!!, snappedX, snappedY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pressedElements.remove(draggingElementId)
                draggingElementId = null
                draggingPointerId = -1
                invalidate()
                return true
            }
        }
        return false
    }

    /**
     * 编辑模式下直接设置元素位置（由 ControlsEditorActivity 调用）。
     */
    fun updateElementPosition(elementId: String, x: Float, y: Float) {
        val p = profile ?: return
        profile = p.copy(
            elements = p.elements.map { el ->
                if (el.id == elementId) el.copy(x = x, y = y) else el
            }
        )
        invalidate()
    }
}
