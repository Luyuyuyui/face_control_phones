package org.npu.face_control

import android.content.Context
import android.content.SharedPreferences

/**
 * 灵敏度配置管理类
 * 负责保存和加载用户设置的阈值
 */
class SensitivityConfig(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("face_control_prefs", Context.MODE_PRIVATE)

    // 默认阈值（与 FaceAnalyzer.Thresholds 默认值保持一致）
    var earThreshold: Float
        get() = prefs.getFloat("ear_threshold", 0.2f)
        set(value) = prefs.edit().putFloat("ear_threshold", value).apply()

    var shakeThreshold: Float
        get() = prefs.getFloat("shake_threshold", 0.75f)
        set(value) = prefs.edit().putFloat("shake_threshold", value).apply()

    var nodThreshold: Float
        get() = prefs.getFloat("nod_threshold", 0.6f)
        set(value) = prefs.edit().putFloat("nod_threshold", value).apply()

    var marThreshold: Float
        get() = prefs.getFloat("mar_threshold", 0.5f)
        set(value) = prefs.edit().putFloat("mar_threshold", value).apply()

    // 加载所有配置，返回一个 Thresholds 对象，用于更新 FaceAnalyzer
    fun loadThresholds(): FaceAnalyzer.Thresholds {
        return FaceAnalyzer.Thresholds(
            earClose = earThreshold,
            shakeLeftRatio = shakeThreshold,
            shakeRightRatio = 1 - shakeThreshold, // 右侧阈值 = 1 - 左侧阈值
            nodRatio = nodThreshold,
            mouthOpenMar = marThreshold
        )
    }

    // 保存所有配置（从 UI 传入的 Thresholds 保存到 SharedPreferences）
    fun saveThresholds(thresholds: FaceAnalyzer.Thresholds) {
        earThreshold = thresholds.earClose
        shakeThreshold = thresholds.shakeLeftRatio
        nodThreshold = thresholds.nodRatio
        marThreshold = thresholds.mouthOpenMar
    }
}