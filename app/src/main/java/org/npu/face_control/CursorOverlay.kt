package org.npu.face_control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * ============================================================
 * 手势光标悬浮窗 — 在屏幕上绘制圆形 + 十字准星
 *
 * 使用 WindowManager.TYPE_APPLICATION_OVERLAY 实现全局覆盖，
 * FLAG_NOT_TOUCHABLE 保证光标不会拦截触摸事件。
 *
 * 样式：
 *   - 默认：半透明白色圆环 + 十字线
 *   - 点击态：绿色填充圆点（捏合瞬间视觉反馈）
 * ============================================================
 */
class CursorOverlay(private val context: Context) {

    companion object {
        private const val TAG = "CursorOverlay"

        /** 光标 View 的尺寸（dp） */
        private const val VIEW_SIZE_DP = 80

        /** 圆环半径（dp） */
        private const val CIRCLE_RADIUS_DP = 20f

        /** 圆环描边宽度（dp） */
        private const val CIRCLE_STROKE_DP = 2f

        /** 十字线超出圆的长度（dp） */
        private const val CROSS_EXTEND_DP = 4f

        /** 中心圆点半径（dp） */
        private const val DOT_RADIUS_DP = 3f
    }

    // ---------- 颜色值（Int，使用 Color 工具类保证兼容性） ----------
    // 默认色：纯黑 — 在任何背景上都可见
    private val COLOR_DEFAULT: Int = android.graphics.Color.rgb(0, 0, 0)
    // 点击闪白色：配合黑色默认态，让用户感知到点击反馈
    private val COLOR_CLICK: Int = android.graphics.Color.rgb(255, 255, 255)

    // ---------- 像素缓存（懒计算） ----------
    private var density: Float = context.resources.displayMetrics.density
    private var viewSizePx: Int = (VIEW_SIZE_DP * density).toInt()
    private var circleRadiusPx: Float = CIRCLE_RADIUS_DP * density
    private var circleStrokePx: Float = CIRCLE_STROKE_DP * density
    private var crossExtendPx: Float = CROSS_EXTEND_DP * density
    private var dotRadiusPx: Float = DOT_RADIUS_DP * density

    // ---------- WindowManager ----------
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var isAdded = false

    // ---------- 绘制参数 ----------
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = CIRCLE_STROKE_DP * density
        color = COLOR_DEFAULT
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = COLOR_DEFAULT
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_CLICK
    }

    // ---------- 光标 View ----------
    private val cursorView = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f

            // 十字线（水平 + 垂直）
            canvas.drawLine(cx - circleRadiusPx - crossExtendPx, cy,
                cx + circleRadiusPx + crossExtendPx, cy, crossPaint)
            canvas.drawLine(cx, cy - circleRadiusPx - crossExtendPx,
                cx, cy + circleRadiusPx + crossExtendPx, crossPaint)

            // 圆环
            canvas.drawCircle(cx, cy, circleRadiusPx, circlePaint)

            // 中心圆点
            canvas.drawCircle(cx, cy, dotRadiusPx, fillPaint)
        }
    }

    // ---------- LayoutParams ----------
    private val params = WindowManager.LayoutParams(
        viewSizePx,
        viewSizePx,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        android.graphics.PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    // ============================================================
    // 公开接口
    // ============================================================

    /**
     * 显示光标（添加到 WindowManager）
     */
    fun show() {
        if (isAdded) return
        try {
            windowManager.addView(cursorView, params)
            isAdded = true
            Log.d(TAG, "光标悬浮窗已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示光标失败（可能未授予悬浮窗权限）", e)
        }
    }

    /**
     * 隐藏光标（从 WindowManager 移除）
     */
    fun hide() {
        if (!isAdded) return
        try {
            windowManager.removeView(cursorView)
        } catch (e: Exception) {
            Log.e(TAG, "移除光标悬浮窗失败", e)
        } finally {
            isAdded = false
        }
    }

    /**
     * 更新光标位置（屏幕像素坐标）
     * 光标 View 的 center 对齐到 (x, y)
     */
    fun updatePosition(x: Float, y: Float) {
        if (!isAdded) return
        params.x = (x - viewSizePx / 2f).toInt()
        params.y = (y - viewSizePx / 2f).toInt()
        try {
            windowManager.updateViewLayout(cursorView, params)
        } catch (e: Exception) {
            Log.e(TAG, "更新光标位置失败", e)
        }
    }

    /**
     * 设置捏合点击态
     * @param pinching true = 变绿色，false = 恢复默认白色
     */
    fun setPinching(pinching: Boolean) {
        val color = if (pinching) COLOR_CLICK else COLOR_DEFAULT
        circlePaint.color = color
        crossPaint.color = color
        // 点击态时中心圆点变亮
        fillPaint.color = if (pinching) COLOR_CLICK else COLOR_DEFAULT
        cursorView.invalidate()
    }

    /**
     * 释放资源（隐藏光标并清理）
     */
    fun release() {
        hide()
    }

    /**
     * 屏幕密度变化时重建像素缓存（横竖屏切换）
     */
    fun onConfigurationChanged(newDensity: Float) {
        density = newDensity
        viewSizePx = (VIEW_SIZE_DP * density).toInt()
        circleRadiusPx = CIRCLE_RADIUS_DP * density
        circleStrokePx = CIRCLE_STROKE_DP * density
        crossExtendPx = CROSS_EXTEND_DP * density
        dotRadiusPx = DOT_RADIUS_DP * density

        circlePaint.strokeWidth = circleStrokePx
        crossPaint.strokeWidth = 1.5f * density

        // 更新 View 尺寸
        cursorView.layoutParams = params.apply {
            width = viewSizePx
            height = viewSizePx
        }
    }
}
