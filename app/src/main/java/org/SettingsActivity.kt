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
                onBack = { finish() }
            )
        }
    }
}

@Composable
fun SettingsScreen(
    config: SensitivityConfig,
    onBack: () -> Unit
) {
    var earThreshold by remember { mutableStateOf(config.earThreshold) }
    var shakeThreshold by remember { mutableStateOf(config.shakeThreshold) }
    var nodThreshold by remember { mutableStateOf(config.nodThreshold) }
    var marThreshold by remember { mutableStateOf(config.marThreshold) }
    var cursorSpeed by remember { mutableStateOf(config.cursorSpeed) }

    fun saveAndApply() {
        val thresholds = FaceAnalyzer.Thresholds(
            earClose = earThreshold,
            shakeLeftRatio = shakeThreshold,
            shakeRightRatio = 1 - shakeThreshold,
            nodRatio = nodThreshold,
            mouthOpenMar = marThreshold
        )
        config.saveThresholds(thresholds)
        config.cursorSpeed = cursorSpeed
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

        // ---------- 光标移动速度 ----------
        Text(
            text = "光标移动速度: ${String.format("%.1f", cursorSpeed)}  (慢 ← → 快)",
            style = MaterialTheme.typography.bodyLarge
        )
        Slider(
            value = cursorSpeed,
            onValueChange = { cursorSpeed = it },
            valueRange = 0.1f..1.0f,
            steps = 8
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
