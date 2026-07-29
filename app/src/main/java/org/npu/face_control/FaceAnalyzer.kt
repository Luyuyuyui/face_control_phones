package org.npu.face_control

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * ============================================================
 * 人脸动作识别引擎 — 本 App 的核心算法模块
 *
 * 职责：
 *   1. 加载 MediaPipe FaceLandmarker 模型（face_landmarker.task）
 *   2. 接收 CameraX 的每一帧图像
 *   3. 用 468 个面部关键点检测：眨眼、扭头、点头、张嘴
 *   4. 将识别结果通过 onActionDetected 回调通知出去
 *
 * 检测的 7 种动作：
 *   BLINK         → 单次生理性眨眼（可参与双眨眼判定）
 *   DOUBLE_BLINK  → 双眨眼（100~569ms 内两次眨眼）
 *   LONG_BLINK    → 主动长闭眼（350~600ms）
 *   SHAKE_LEFT    → 向左扭头
 *   SHAKE_RIGHT   → 向右扭头
 *   NOD           → 点头（低头）
 *   MOUTH_OPEN    → 张嘴
 *   MOUTH_CLOSE   → 闭嘴
 * ============================================================
 */
class FaceAnalyzer(
    context: Context,
    private val onActionDetected: (FaceAction) -> Unit,
    /** 初始化失败回调，交由上层决定后续动作（例如停止 Service 并通知用户） */
    private val onInitFailed: ((Throwable) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FaceAnalyzer"

        // ------- 眨眼时间分类阈值（ms）-------
        private const val PHYSIO_BLINK_MAX_MS = 180L      // < 180ms 视为生理性眨眼（可参与双眨眼判定）
        private const val IGNORE_BLINK_MAX_MS = 350L      // 180~350ms 忽略，避免误触
        private const val LONG_BLINK_MAX_MS = 600L        // 350~600ms 主动长闭眼
        // > 600ms 视为闭眼休息，忽略

        // ------- 双眨眼时间窗口 -------
        private const val DOUBLE_BLINK_MIN_INTERVAL = 100L
        private const val DOUBLE_BLINK_MAX_INTERVAL = 569L

        // ------- 其他动作后眨眼禁用时长 -------
        private const val BLINK_DISABLE_DURATION_MS = 1000L

        // ------- 摇头动作节流 -------
        private const val SHAKE_LOCK_DURATION_MS = 1000L

        // ------- 人脸丢失超时重置 -------
        private const val FACE_LOST_TIMEOUT_MS = 2000L
    }

    /**
     * 可运行时调节的阈值集合。通过 [updateThresholds] 修改。
     */
    data class Thresholds(
        var earClose: Float = 0.2f,          // 眼睛闭合的 EAR 阈值
        var shakeLeftRatio: Float = 0.75f,   // 摇头（向左侧）阈值
        var shakeRightRatio: Float = 0.25f,  // 摇头（向右侧）阈值
        var nodRatio: Float = 0.6f,          // 点头阈值
        var mouthOpenMar: Float = 0.5f       // 张嘴 MAR 阈值
    )

    @Volatile
    var thresholds: Thresholds = Thresholds()
        private set

    fun updateThresholds(newThresholds: Thresholds) {
        thresholds = newThresholds
    }

    // ---------- MediaPipe 人脸关键点检测器 ----------
    private var faceLandmarker: FaceLandmarker? = null

    // ---------- 线程和状态 ----------
    private val mainHandler = Handler(Looper.getMainLooper())

    // -------- 眨眼状态 --------
    private var isEyesClosed = false
    private var eyesClosedStartTime: Long = 0L
    private var lastPhysioBlinkTimestamp: Long = 0L

    // 眨眼控制开关：识别到其他动作后临时禁用
    @Volatile
    private var isBlinkControlEnabled = true

    // -------- 其他动作状态 --------
    private var isShakeLocked = false
    private var isMouthOpened = false

    // -------- 人脸跟踪状态 --------
    private var lastFaceDetectedTime: Long = 0L

    // 用于禁用眨眼的延迟任务，方便统一清理
    private val enableBlinkRunnable = Runnable { isBlinkControlEnabled = true }
    private val unlockShakeRunnable = Runnable { isShakeLocked = false }

    // 标记 Analyzer 是否已释放，防止在关闭后再处理帧
    @Volatile
    private var isReleased = false

    // ---------- 手部分析处理器（可选，由服务层挂接） ----------
    private var handFrameProcessor: HandCursorController? = null

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ -> processResult(result) }
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (e: Throwable) {
            Log.e(TAG, "FaceLandmarker 初始化失败", e)
            faceLandmarker = null
            // 抛给上层去处理（停止服务 / 通知用户等）
            onInitFailed?.invoke(e)
        }
    }

    // ============================================================
    // CameraX 分析器接口 — 每来一帧就调用一次
    // ============================================================
    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val landmarker = faceLandmarker
        if (isReleased || landmarker == null) {
            image.close()
            return
        }

        try {
            // ---------- 1. 将相机帧转成 Bitmap ----------
            val originalBitmap = image.toBitmap()

            // ---------- 2. 处理旋转 ----------
            val rotatedBitmap = if (image.imageInfo.rotationDegrees != 0) {
                val matrix = Matrix().apply {
                    postRotate(image.imageInfo.rotationDegrees.toFloat())
                }
                Bitmap.createBitmap(
                    originalBitmap, 0, 0,
                    originalBitmap.width, originalBitmap.height,
                    matrix, true
                )
            } else {
                originalBitmap
            }

            // ---------- 3. 转成 MediaPipe 的 MP Image 格式 ----------
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()

            // ---------- 4. 异步检测 ----------
            // 关键修改：将纳秒转换为毫秒
            // image.imageInfo.timestamp 返回纳秒，MediaPipe 需要毫秒
            val timestampMs = image.imageInfo.timestamp / 1_000_000
            landmarker.detectAsync(mpImage, timestampMs)

            // ---------- 5. 将同一帧透传给手部分析器（光标控制） ----------
            handFrameProcessor?.processFrame(rotatedBitmap, timestampMs)

            // 不在此处回收 Bitmap。
            // 因为 detectAsync 是异步的，MediaPipe 内部会在后台线程处理图像数据。
            // 手动 recycle() 会导致后台线程访问已回收的内存，造成崩溃。
            // Android 8.0+ 的 Bitmap 像素数据在 Native 堆，GC 会自动回收。
        } catch (e: Throwable) {
            Log.e(TAG, "analyze frame failed", e)
        } finally {
            // 只释放 ImageProxy，Bitmap 交由 GC 管理
            image.close()
        }
    }

    // ============================================================
    // MediaPipe 检测结果处理器 — 每帧检测完成后回调
    // ============================================================
    private fun processResult(result: FaceLandmarkerResult) {
        if (isReleased) return

        val landmarksList = result.faceLandmarks()
        if (landmarksList.isNullOrEmpty()) {
            // 超过 2 秒没检测到人脸 → 重置所有状态，防止回到画面时误触发
            if (System.currentTimeMillis() - lastFaceDetectedTime > FACE_LOST_TIMEOUT_MS) {
                isEyesClosed = false
                isMouthOpened = false
                isShakeLocked = false
                lastPhysioBlinkTimestamp = 0L
                lastFaceDetectedTime = System.currentTimeMillis()
            }
            return
        }
        lastFaceDetectedTime = System.currentTimeMillis()

        // 只处理第一张人脸（单用户场景）
        val landmarks = landmarksList[0]
        val th = thresholds

        // ---------------- 1. 眨眼逻辑 ----------------
        val leftEar = calculateEAR(landmarks, 362, 385, 387, 263, 373, 380)
        val rightEar = calculateEAR(landmarks, 33, 160, 158, 133, 153, 144)
        val avgEar = (leftEar + rightEar) / 2f
        handleBlink(avgEar < th.earClose)

        // ---------------- 2. 摇头逻辑 ----------------
        val nose = landmarks[1]
        val rightFaceEdge = landmarks[234]
        val leftFaceEdge = landmarks[454]
        val faceWidth = (leftFaceEdge.x() - rightFaceEdge.x())
        if (faceWidth > 0) {
            val ratio = (nose.x() - rightFaceEdge.x()) / faceWidth
            if (ratio > th.shakeLeftRatio) {
                triggerShake(FaceAction.SHAKE_LEFT)
            } else if (ratio < th.shakeRightRatio) {
                triggerShake(FaceAction.SHAKE_RIGHT)
            }
        }

        // ---------------- 3. 点头逻辑 ----------------
        val noseTip = landmarks[1]
        val forehead = landmarks[10]
        val chin = landmarks[152]
        val faceHeight = dist(forehead, chin)
        if (faceHeight > 0) {
            val nodRatio = (noseTip.y() - forehead.y()) / faceHeight
            if (nodRatio > th.nodRatio) {
                triggerShake(FaceAction.NOD)
            }
        }

        // ---------------- 4. 张嘴逻辑（几何 MAR 计算）---------------
        val upperLip = landmarks[13]
        val lowerLip = landmarks[14]
        val leftMouth = landmarks[78]
        val rightMouth = landmarks[308]
        val mouthHeight = dist(upperLip, lowerLip)
        val mouthWidth = dist(leftMouth, rightMouth)
        if (mouthWidth > 0) {
            val mar = mouthHeight / mouthWidth
            if (mar > th.mouthOpenMar) {
                if (!isMouthOpened) {
                    isMouthOpened = true
                    dispatchNonBlinkAction(FaceAction.MOUTH_OPEN)
                }
            } else {
                if (isMouthOpened) {
                    isMouthOpened = false
                    dispatchNonBlinkAction(FaceAction.MOUTH_CLOSE)
                }
            }
        }
    }

    // ============================================================
    // 眨眼状态机
    // ============================================================

    /**
     * 眨眼状态机：
     * - 记录闭眼开始时间
     * - 睁眼时按持续时长分类处理
     */
    private fun handleBlink(eyesClosedNow: Boolean) {
        val now = System.currentTimeMillis()

        if (eyesClosedNow) {
            if (!isEyesClosed) {
                isEyesClosed = true
                eyesClosedStartTime = now
            }
            return
        }

        // 从闭 → 睁
        if (isEyesClosed) {
            isEyesClosed = false
            val duration = now - eyesClosedStartTime

            // 眨眼控制被禁用时，直接丢弃本次事件
            if (!isBlinkControlEnabled) {
                lastPhysioBlinkTimestamp = 0L
                return
            }

            when {
                duration < PHYSIO_BLINK_MAX_MS -> {
                    // 生理性眨眼：不直接触发动作，但参与双眨眼判定
                    checkDoubleBlink(now)
                }
                duration < IGNORE_BLINK_MAX_MS -> {
                    // 180~350ms 忽略，同时重置双眨眼计时避免误配
                    lastPhysioBlinkTimestamp = 0L
                }
                duration < LONG_BLINK_MAX_MS -> {
                    // 350~600ms 主动长闭眼
                    lastPhysioBlinkTimestamp = 0L
                    onActionDetected(FaceAction.LONG_BLINK)
                }
                else -> {
                    // > 600ms 视为休息，忽略
                    lastPhysioBlinkTimestamp = 0L
                }
            }
        }
    }

    private fun checkDoubleBlink(now: Long) {
        val interval = now - lastPhysioBlinkTimestamp
        if (lastPhysioBlinkTimestamp != 0L &&
            interval in DOUBLE_BLINK_MIN_INTERVAL..DOUBLE_BLINK_MAX_INTERVAL
        ) {
            onActionDetected(FaceAction.DOUBLE_BLINK)
            lastPhysioBlinkTimestamp = 0L
        } else {
            lastPhysioBlinkTimestamp = now
            // 单次生理性眨眼保留 BLINK 事件以便上层可选使用
            onActionDetected(FaceAction.BLINK)
        }
    }

    // ============================================================
    // 动作触发辅助方法
    // ============================================================

    private fun triggerShake(action: FaceAction) {
        if (isShakeLocked) return
        isShakeLocked = true
        dispatchNonBlinkAction(action)
        mainHandler.postDelayed(unlockShakeRunnable, SHAKE_LOCK_DURATION_MS)
    }

    /**
     * 非眨眼动作触发：
     * 1. 通知上层
     * 2. 临时禁用眨眼识别，避免头动/张嘴瞬间的眼形变化被误判成眨眼
     */
    private fun dispatchNonBlinkAction(action: FaceAction) {
        onActionDetected(action)
        disableBlinkTemporarily()
    }

    private fun disableBlinkTemporarily() {
        isBlinkControlEnabled = false
        // 重置眨眼相关状态
        isEyesClosed = false
        lastPhysioBlinkTimestamp = 0L
        mainHandler.removeCallbacks(enableBlinkRunnable)
        mainHandler.postDelayed(enableBlinkRunnable, BLINK_DISABLE_DURATION_MS)
    }

    // ============================================================
    // 几何计算工具方法
    // ============================================================

    /**
     * 计算 Eye Aspect Ratio (EAR)
     * 用于判断眼睛开合状态，值越小表示眼睛闭合程度越高
     *
     * 公式：(dist(p2,p6) + dist(p3,p5)) / (2 * dist(p1,p4))
     *   p1/p4 → 眼睛左右眼角
     *   p2-p6、p3-p5 → 上下眼睑对应点
     */
    private fun calculateEAR(
        l: List<NormalizedLandmark>,
        p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int
    ): Float {
        return FaceMath.calculateEAR(
            l[p1].x(), l[p1].y(),
            l[p2].x(), l[p2].y(),
            l[p3].x(), l[p3].y(),
            l[p4].x(), l[p4].y(),
            l[p5].x(), l[p5].y(),
            l[p6].x(), l[p6].y(),
        )
    }

    /**
     * 计算两个关键点之间的 2D 欧氏距离
     * 委托给 FaceMath.dist() 实现，方便单测验证
     */
    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float =
        FaceMath.dist(a.x(), a.y(), b.x(), b.y())

    // ============================================================
    // 资源释放
    // ============================================================

    // ============================================================
    // 手部分析挂接（与服务层共享同一帧，避免重复转换）
    // ============================================================

    /**
     * 挂接手部分析处理器。从 [analyze] 方法中每帧透传 Bitmap，
     * 使 HandCursorController 与 FaceAnalyzer 共享同一帧图像，
     * 避免两次 [ImageProxy.toBitmap] 转换。
     */
    fun attachHandProcessor(processor: HandCursorController) {
        handFrameProcessor = processor
    }

    // ============================================================
    // 资源释放
    // ============================================================

    /**
     * 释放资源。上层（Service.onDestroy）必须调用，否则 FaceLandmarker
     * 和 Handler 延迟任务会导致内存泄漏。
     */
    fun close() {
        isReleased = true
        mainHandler.removeCallbacksAndMessages(null)
        try {
            faceLandmarker?.close()
        } catch (e: Throwable) {
            Log.e(TAG, "关闭 FaceLandmarker 失败", e)
        } finally {
            faceLandmarker = null
        }
    }

    // ============================================================
    // 动作枚举
    // ============================================================

    enum class FaceAction {
        BLINK,          // 单次生理性眨眼（可选使用）
        DOUBLE_BLINK,   // 双眨眼
        LONG_BLINK,     // 主动长闭眼（350~600ms）
        NOD,
        SHAKE_LEFT,
        SHAKE_RIGHT,
        MOUTH_OPEN,
        MOUTH_CLOSE
    }
}