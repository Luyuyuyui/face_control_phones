package org.npu.face_control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ============================================================
 * 前台服务 + 调度中枢 — 本 App 的"大脑"
 *
 * 职责链：
 *   CameraX (前置摄像头)
 *       ↓ 逐帧
 *   FaceAnalyzer (MediaPipe 人脸检测)
 *       ↓ 识别出 FaceAction
 *   当前方法 (判断横/竖屏，映射为具体手势)
 *       ↓ 调用
 *   FaceAccessibilityService (无障碍手势执行)
 *       ↓
 *   系统触摸事件 → 被控制 App 响应
 * ============================================================
 */
class FaceControlForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "FaceControlService"
        private const val CHANNEL_ID = "face_control_channel"
        private const val NOTIFICATION_ID = 1
        private const val ERROR_NOTIFICATION_ID = 2

        // ============================================================
        // 手势坐标常量（竖屏模式）
        // ============================================================

        // 滑动坐标
        private const val SWIPE_START_X_CENTER = 0.5f
        private const val SWIPE_END_X_CENTER = 0.5f
        private const val SWIPE_START_Y_BOTTOM = 0.75f
        private const val SWIPE_END_Y_TOP = 0.25f

        // 左右滑动坐标
        private const val SWIPE_X_RIGHT = 0.9f
        private const val SWIPE_X_LEFT = 0.1f
        private const val SWIPE_Y_CENTER = 0.5f

        // 点击/按压坐标
        private const val CLICK_X_CENTER = 0.5f
        private const val CLICK_Y_CENTER = 0.5f

        // ============================================================
        // 手势坐标常量（横屏模式）
        // ============================================================

        // 快进/倒退滑动坐标
        private const val SWIPE_FAST_FORWARD_START_X = 0.2f
        private const val SWIPE_FAST_FORWARD_END_X = 0.8f
        private const val SWIPE_REWIND_START_X = 0.8f
        private const val SWIPE_REWIND_END_X = 0.2f
        private const val SWIPE_Y_VIDEO = 0.5f

        // 横屏点击/按压坐标
        private const val CLICK_X_VIDEO_CENTER = 0.5f
        private const val CLICK_Y_VIDEO_CENTER = 0.5f
    }

    /** 相机分析运行在独立线程，不阻塞主线程 */
    private lateinit var cameraExecutor: ExecutorService

    /** CameraX 相机提供者，用于资源释放 */
    private var cameraProvider: ProcessCameraProvider? = null

    /** 人脸分析器实例，用于释放资源 */
    private var faceAnalyzer: FaceAnalyzer? = null

    /** 屏幕尺寸，用于百分比坐标换算（动态更新） */
    private var screenWidth = 1080
    private var screenHeight = 2400

    // ================================================================
    // 生命周期
    // ================================================================

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        updateScreenSize()

        // 1. 创建前台服务通知（系统必须，否则 Android 8+ 会崩溃）
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // 2. 启动 CameraX + 人脸检测流水线
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 如果相机被释放了，重新启动
        if (cameraProvider == null) {
            startCamera()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseResources()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null  // 非绑定服务
    }

    /**
     * 屏幕方向变化时重新获取尺寸，保证坐标计算准确
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenSize()
    }

    // ================================================================
    // 屏幕尺寸工具
    // ================================================================

    /**
     * 动态获取屏幕真实尺寸，兼容不同 API 版本
     */
    private fun updateScreenSize() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        Log.d(TAG, "屏幕尺寸更新: ${screenWidth}x${screenHeight}")
    }

    /** 百分比 X 坐标 → 像素值 */
    private fun px(percentX: Float): Float = screenWidth * percentX

    /** 百分比 Y 坐标 → 像素值 */
    private fun py(percentY: Float): Float = screenHeight * percentY

    // ================================================================
    // 前台服务通知
    // ================================================================

    /** 创建通知渠道（Android 8+ 必须） */
    private fun createNotificationChannel() {
        val channelName = getString(R.string.notification_channel_name)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    /** 构建通知内容 */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    /**
     * 显示错误通知（摄像头启动失败时）
     */
    private fun showErrorNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.error_notification_title))
            .setContentText(getString(R.string.error_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .build()
        manager.notify(ERROR_NOTIFICATION_ID, notification)
    }

    // ================================================================
    // CameraX 相机流水线
    // ================================================================

    /**
     * 启动前置摄像头 + 人脸分析
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // ----- 图像分析器配置 -----
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                // ----- 人脸分析器 -----
                val analyzer = FaceAnalyzer(
                    context = this,
                    onActionDetected = { action ->
                        handleFaceAction(action)
                    },
                    onInitFailed = { error ->
                        Log.e(TAG, "人脸识别模型初始化失败", error)
                        handleCameraFailure()
                    }
                )
                faceAnalyzer = analyzer
                imageAnalysis.setAnalyzer(cameraExecutor, analyzer)

                // ----- 选择前置摄像头 -----
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                // 先解绑所有用例
                cameraProvider?.unbindAll()

                // 绑定到当前 Service 的 Lifecycle
                cameraProvider?.bindToLifecycle(this, cameraSelector, imageAnalysis)

                Log.d(TAG, "摄像头启动成功")

            } catch (e: Exception) {
                Log.e(TAG, "摄像头启动失败", e)
                handleCameraFailure()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 摄像头打开失败时的处理：通知用户 + 停止服务
     */
    private fun handleCameraFailure() {
        Toast.makeText(
            this,
            "无法启动摄像头，FaceControl 服务已停止",
            Toast.LENGTH_LONG
        ).show()

        showErrorNotification()
        stopSelf()
    }

    // ================================================================
    // 资源释放
    // ================================================================

    /**
     * 统一释放摄像头及线程资源
     */
    private fun releaseResources() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "解绑摄像头时出错", e)
        } finally {
            cameraProvider = null
        }

        try {
            faceAnalyzer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭 FaceAnalyzer 时出错", e)
        } finally {
            faceAnalyzer = null
        }

        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
    }

    // ================================================================
    // 动作→手势 映射调度
    // ================================================================

    private fun handleFaceAction(action: FaceAnalyzer.FaceAction) {
        val service = FaceAccessibilityService.instance ?: return
        val isPortrait =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        if (isPortrait) {
            handlePortraitMode(action, service)
        } else {
            handleLandscapeMode(action, service)
        }
    }

    // ================================================================
    // 方案一：竖屏手势映射
    // ================================================================
    private fun handlePortraitMode(
        action: FaceAnalyzer.FaceAction,
        service: FaceAccessibilityService
    ) {
        when (action) {
            FaceAnalyzer.FaceAction.DOUBLE_BLINK -> {
                service.performSwipeAction(
                    px(SWIPE_START_X_CENTER), py(SWIPE_START_Y_BOTTOM),
                    px(SWIPE_END_X_CENTER), py(SWIPE_END_Y_TOP)
                )
            }
            FaceAnalyzer.FaceAction.SHAKE_LEFT -> {
                service.performSwipeAction(
                    px(SWIPE_X_RIGHT), py(SWIPE_Y_CENTER),
                    px(SWIPE_X_LEFT), py(SWIPE_Y_CENTER)
                )
            }
            FaceAnalyzer.FaceAction.SHAKE_RIGHT -> {
                service.performSwipeAction(
                    px(SWIPE_X_LEFT), py(SWIPE_Y_CENTER),
                    px(SWIPE_X_RIGHT), py(SWIPE_Y_CENTER)
                )
            }
            FaceAnalyzer.FaceAction.MOUTH_OPEN -> {
                service.startContinuousPress(px(CLICK_X_CENTER), py(CLICK_Y_CENTER))
            }
            FaceAnalyzer.FaceAction.MOUTH_CLOSE -> {
                service.stopContinuousPress(px(CLICK_X_CENTER), py(CLICK_Y_CENTER))
            }
            FaceAnalyzer.FaceAction.NOD -> {
                service.performClickAction(px(CLICK_X_CENTER), py(CLICK_Y_CENTER))
            }
            else -> {}
        }
    }

    // ================================================================
    // 方案二：横屏手势映射
    // ================================================================
    private fun handleLandscapeMode(
        action: FaceAnalyzer.FaceAction,
        service: FaceAccessibilityService
    ) {
        when (action) {
            FaceAnalyzer.FaceAction.DOUBLE_BLINK -> {
                service.performSwipeAction(
                    px(SWIPE_FAST_FORWARD_START_X), py(SWIPE_Y_VIDEO),
                    px(SWIPE_FAST_FORWARD_END_X), py(SWIPE_Y_VIDEO)
                )
            }
            FaceAnalyzer.FaceAction.NOD -> {
                service.performSwipeAction(
                    px(SWIPE_REWIND_START_X), py(SWIPE_Y_VIDEO),
                    px(SWIPE_REWIND_END_X), py(SWIPE_Y_VIDEO)
                )
            }
            FaceAnalyzer.FaceAction.MOUTH_OPEN -> {
                service.startContinuousPress(px(CLICK_X_VIDEO_CENTER), py(CLICK_Y_VIDEO_CENTER))
            }
            FaceAnalyzer.FaceAction.MOUTH_CLOSE -> {
                service.stopContinuousPress(px(CLICK_X_VIDEO_CENTER), py(CLICK_Y_VIDEO_CENTER))
            }
            else -> {}
        }
    }
}