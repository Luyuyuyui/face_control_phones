package org.npu.face_control

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * ============================================================
 * 手部动作识别引擎 — 检测手掌姿态用于光标控制
 *
 * 检测逻辑：
 *   1. 五指张开（开掌）→ 显示光标并跟随掌心移动
 *   2. 握拳 → 触发点击动作
 *   3. 无手/手离开 → 隐藏光标
 *
 * 手部 21 个关键点（MediaPipe Hand Landmarker）：
 *   0=手腕  4=拇指尖  8=食指尖  12=中指尖  16=无名指尖  20=小指尖
 *   5=食指MCP  9=中指MCP  13=无名指MCP  17=小指MCP
 *
 * v2 变更: 从捏合改为握拳点击；添加释放宽限期避免过渡帧闪烁
 * ============================================================
 */
class HandCursorController(
    context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val listener: HandCursorListener,
    /** 初始化失败回调 — 用于给用户弹 Toast 等 */
    private val onInitFailed: ((Throwable) -> Unit)? = null
) {

    companion object {
        private const val TAG = "HandCursorController"

        // ---- 手部关键点索引 ----
        private const val LANDMARK_WRIST = 0
        private const val LANDMARK_THUMB_TIP = 4
        private const val LANDMARK_INDEX_TIP = 8
        private const val LANDMARK_INDEX_MCP = 5
        private const val LANDMARK_MIDDLE_MCP = 9
        private const val LANDMARK_MIDDLE_TIP = 12
        private const val LANDMARK_RING_TIP = 16
        private const val LANDMARK_PINKY_TIP = 20

        // ---- 手势阈值（归一化坐标） ----
        // 手指伸展判定：指尖到掌心的距离大于此值表示伸直
        private const val FINGER_SPREAD_THRESHOLD = 0.18f
        // 握拳判定：伸直的手指数量 <= 此值即为握拳
        private const val FIST_SPREAD_MAX = 0
        // 开掌判定需要至少有几根手指伸直
        private const val MIN_SPREAD_FINGERS = 2

        // ---- 坐标平滑 ----
        // 默认 0.3f；值越大响应越快（越不平滑），值越小越平滑（延迟越大）
        // 可由外部通过 updateSmoothingFactor() 运行时调节
        private const val DEFAULT_SMOOTHING_FACTOR = 0.3f

        // ---- 点击防抖 ----
        // 握拳帧数确认（避免眨眼等误触）
        private const val FIST_CONFIRM_FRAMES = 2
        // 点击冷却
        private const val CLICK_COOLDOWN_MS = 500L
        // 握拳松开后光标保持可见的宽限期（ms），
        // 避免握拳→开掌过渡帧中 spreadCount 短暂不足导致光标闪烁
        private const val FIST_RELEASE_GRACE_MS = 400L
    }

    // ---------- MediaPipe 手部关键点检测器 ----------
    private var handLandmarker: HandLandmarker? = null

    // ---------- 状态 ----------
    @Volatile private var isReleased = false
    private var isHandVisible = false
    private var isCursorShowing = false

    // 握拳点击状态
    private var fistFrameCount = 0
    private var lastClickTime = 0L
    private var isFisting = false
    private var fistReleasedTime = 0L      // 上次握拳释放的时间戳（宽限期用）

    // 光标平滑坐标
    private var smoothedX = -1f
    private var smoothedY = -1f

    /** 当前 EMA 平滑因子，默认 0.3f，可通过 [updateSmoothingFactor] 调节 */
    @Volatile
    private var smoothingFactor: Float = DEFAULT_SMOOTHING_FACTOR

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1) // 只检测最近的一只手
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ -> processResult(result) }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.i(TAG, "HandLandmarker 初始化成功")
        } catch (e: Throwable) {
            Log.e(TAG, "HandLandmarker 初始化失败", e)
            handLandmarker = null
            onInitFailed?.invoke(e)
        }
    }

    // ============================================================
    // 外部接口 — 由 FaceAnalyzer 每帧调用
    // ============================================================

    /**
     * 处理一帧图片（从 FaceAnalyzer.analyze() 透传过来）
     * 与 FaceAnalyzer 共用同一个 Bitmap，避免重复转换
     */
    fun processFrame(bitmap: Bitmap, timestampMs: Long) {
        val landmarker = handLandmarker
        if (isReleased || landmarker == null) return

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            landmarker.detectAsync(mpImage, timestampMs)
        } catch (e: Throwable) {
            Log.e(TAG, "processFrame failed", e)
        }
    }

    // ============================================================
    // MediaPipe 结果回调
    // ============================================================

    private fun processResult(result: HandLandmarkerResult) {
        if (isReleased) return

        val landmarksList: List<List<NormalizedLandmark>>? = result.landmarks()

        if (landmarksList.isNullOrEmpty()) {
            // 没有检测到手 → 隐藏光标
            if (isHandVisible) {
                isHandVisible = false
                isCursorShowing = false
                isFisting = false
                fistFrameCount = 0
                listener.onCursorVisibilityChanged(false)
            }
            return
        }

        // 取第一只手（最靠近相机的）
        val landmarks: List<NormalizedLandmark> = landmarksList[0]
        isHandVisible = true

        // ---- 检测手势 ----
        analyzeGesture(landmarks)
    }

    // ============================================================
    // 手势分析核心
    // ============================================================

    private fun analyzeGesture(landmarks: List<NormalizedLandmark>) {
        // 1. 计算掌心坐标（手腕 + 中指MCP(9) 的中点）
        val wrist = landmarks[LANDMARK_WRIST]
        val middleMcp = landmarks[LANDMARK_MIDDLE_MCP]
        val palmX = (wrist.x() + middleMcp.x()) / 2f
        val palmY = (wrist.y() + middleMcp.y()) / 2f

        // 2. 计算各指尖到掌心的距离，判断手指伸展/蜷缩
        val indexTip = landmarks[LANDMARK_INDEX_TIP]
        val middleTip = landmarks[LANDMARK_MIDDLE_TIP]
        val ringTip = landmarks[LANDMARK_RING_TIP]
        val pinkyTip = landmarks[LANDMARK_PINKY_TIP]

        val indexSpread = dist(indexTip, palmX, palmY)
        val middleSpread = dist(middleTip, palmX, palmY)
        val ringSpread = dist(ringTip, palmX, palmY)
        val pinkySpread = dist(pinkyTip, palmX, palmY)

        // 3. 判断手指伸直数量
        val spreadCount = listOf(indexSpread, middleSpread, ringSpread, pinkySpread)
            .count { it > FINGER_SPREAD_THRESHOLD }

        // 4. 开掌判定
        val isPalmOpen = spreadCount >= MIN_SPREAD_FINGERS

        // 5. 握拳判定：没有任何手指伸直
        val isFistNow = spreadCount <= FIST_SPREAD_MAX

        // ============================================================
        // 坐标映射 + 平滑（始终计算，不依赖手势类型）
        // ============================================================

        // 前摄镜像翻转 + 屏幕映射
        val rawScreenX = (1f - palmX) * screenWidth
        val rawScreenY = palmY * screenHeight

        // EMA 平滑
        if (smoothedX < 0f) {
            smoothedX = rawScreenX
            smoothedY = rawScreenY
        } else {
            smoothedX += (rawScreenX - smoothedX) * smoothingFactor
            smoothedY += (rawScreenY - smoothedY) * smoothingFactor
        }
        listener.onCursorMove(smoothedX, smoothedY)

        // ============================================================
        // 状态机
        // ============================================================

        // 握拳松开后的宽限期：避免过渡帧 spreadCount 短暂不足导致光标闪烁
        val now = System.currentTimeMillis()
        val isInGracePeriod = fistReleasedTime > 0 &&
            (now - fistReleasedTime) < FIST_RELEASE_GRACE_MS

        // 开掌 / 握拳 / 握拳释放宽限期 → 保持光标可见
        val shouldShowCursor = isPalmOpen || isFistNow || isFisting || isInGracePeriod

        if (shouldShowCursor) {
            if (!isCursorShowing) {
                isCursorShowing = true
                listener.onCursorVisibilityChanged(true)
            }
            handleFistDetection(isFistNow, now)
        } else {
            if (isCursorShowing) {
                isCursorShowing = false
                listener.onCursorVisibilityChanged(false)
            }
            if (isFisting) {
                isFisting = false
                listener.onFistReleased()
            }
            fistFrameCount = 0
            // 光标完全隐藏时才重置平滑坐标
            smoothedX = -1f
            smoothedY = -1f
        }
    }

    /**
     * 握拳点击检测：连续多帧确认 + 冷却 + 释放宽限期
     */
    private fun handleFistDetection(isFistNow: Boolean, now: Long) {
        if (isFistNow) {
            // 正在握拳中
            if (!isFisting) {
                fistFrameCount++
                if (fistFrameCount >= FIST_CONFIRM_FRAMES) {
                    if (now - lastClickTime > CLICK_COOLDOWN_MS) {
                        isFisting = true
                        fistReleasedTime = 0  // 清除之前的宽限期
                        lastClickTime = now
                        listener.onFistClick(smoothedX, smoothedY)
                    }
                    fistFrameCount = 0
                }
            }
        } else {
            // 握拳释放
            if (isFisting) {
                isFisting = false
                fistReleasedTime = now
                listener.onFistReleased()
            }
            fistFrameCount = 0
        }
    }

    // ============================================================
    // 几何工具
    // ============================================================

    /** 计算两个关键点之间的 2D 欧氏距离（归一化坐标系） */
    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** 计算关键点到某坐标的 2D 欧氏距离 */
    private fun dist(a: NormalizedLandmark, px: Float, py: Float): Float {
        val dx = a.x() - px
        val dy = a.y() - py
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // ============================================================
    // 运行时配置
    // ============================================================

    /**
     * 调节光标移动平滑度/速度
     * @param factor 0.1~1.0，越大响应越快（不平滑），越小越平滑（延迟大）
     */
    fun updateSmoothingFactor(factor: Float) {
        smoothingFactor = factor.coerceIn(0.1f, 1.0f)
    }

    // ============================================================
    // 资源释放
    // ============================================================

    /**
     * 更新屏幕尺寸（横竖屏切换时）
     */
    fun updateScreenSize(width: Int, height: Int) {
        // 如果屏幕尺寸变了，平滑坐标需要重置
        if (width != screenWidth || height != screenHeight) {
            smoothedX = -1f
            smoothedY = -1f
        }
    }

    fun close() {
        isReleased = true
        try {
            handLandmarker?.close()
        } catch (e: Throwable) {
            Log.e(TAG, "关闭 HandLandmarker 失败", e)
        } finally {
            handLandmarker = null
        }
    }

    // ============================================================
    // 回调接口
    // ============================================================

    interface HandCursorListener {
        /** 光标位置更新 */
        fun onCursorMove(x: Float, y: Float)

        /** 光标显隐状态变化 */
        fun onCursorVisibilityChanged(visible: Boolean)

        /** 握拳点击触发（x, y 为当前光标屏幕像素坐标） */
        fun onFistClick(x: Float, y: Float)

        /** 握拳释放 */
        fun onFistReleased()
    }
}
