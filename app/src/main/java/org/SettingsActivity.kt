package org.npu.face_control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class SettingsActivity : ComponentActivity() {

    private lateinit var config: SensitivityConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = SensitivityConfig(this)

        setContent {
            SettingsScreen(
                config = config,
                onBack = { finish() }  // 点击返回按钮关闭当前页面
            )
        }
    }
}

@Composable
fun SettingsScreen(
    config: SensitivityConfig,
    onBack: () -> Unit
) {
    // 使用 mutableState 来让 UI 随滑块变化而更新
    var earThreshold by remember { mutableStateOf(config.earThreshold) }
    var shakeThreshold by remember { mutableStateOf(config.shakeThreshold) }
    var nodThreshold by remember { mutableStateOf(config.nodThreshold) }
    var marThreshold by remember { mutableStateOf(config.marThreshold) }

    // 保存按钮点击时，将当前 UI 的值保存到 SharedPreferences
    fun saveAndApply() {
        val thresholds = FaceAnalyzer.Thresholds(
            earClose = earThreshold,
            shakeLeftRatio = shakeThreshold,
            shakeRightRatio = 1 - shakeThreshold,
            nodRatio = nodThreshold,
            mouthOpenMar = marThreshold
        )
        config.saveThresholds(thresholds)
        // 这里可以调用 FaceAnalyzer 的 updateThresholds 方法，但需要拿到 FaceAnalyzer 实例。
        // 由于 FaceAnalyzer 在 Service 中，我们可以通过 Service 暴露一个更新方法，
        // 或者下次 Service 启动时自动加载最新配置（我们后续优化）。
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("灵敏度设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        // 眨眼灵敏度
        Text("眨眼灵敏度: ${String.format("%.2f", earThreshold)}")
        Slider(
            value = earThreshold,
            onValueChange = { earThreshold = it },
            valueRange = 0.1f..0.4f,
            steps = 6
        )
        Spacer(modifier = Modifier.height(16.dp))

        // 张嘴灵敏度
        Text("张嘴灵敏度: ${String.format("%.2f", marThreshold)}")
        Slider(
            value = marThreshold,
            onValueChange = { marThreshold = it },
            valueRange = 0.2f..0.8f,
            steps = 6
        )
        Spacer(modifier = Modifier.height(16.dp))

        // 点头灵敏度
        Text("点头灵敏度: ${String.format("%.2f", nodThreshold)}")
        Slider(
            value = nodThreshold,
            onValueChange = { nodThreshold = it },
            valueRange = 0.4f..0.8f,
            steps = 4
        )
        Spacer(modifier = Modifier.height(16.dp))

        // 摇头灵敏度
        Text("摇头灵敏度: ${String.format("%.2f", shakeThreshold)}")
        Slider(
            value = shakeThreshold,
            onValueChange = { shakeThreshold = it },
            valueRange = 0.5f..0.9f,
            steps = 4
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onBack) {
                Text("取消")
            }
            Button(
                onClick = { saveAndApply() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("保存")
            }
        }
    }
}