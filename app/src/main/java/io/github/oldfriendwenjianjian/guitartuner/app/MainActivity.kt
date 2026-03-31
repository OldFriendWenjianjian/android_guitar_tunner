package io.github.oldfriendwenjianjian.guitartuner.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.oldfriendwenjianjian.guitartuner.ui.screens.TunerScreen
import io.github.oldfriendwenjianjian.guitartuner.ui.theme.DarkBackground
import io.github.oldfriendwenjianjian.guitartuner.ui.theme.GuitarTunerTheme
import io.github.oldfriendwenjianjian.guitartuner.ui.theme.TextPrimary
import io.github.oldfriendwenjianjian.guitartuner.ui.theme.TextSecondary
import io.github.oldfriendwenjianjian.guitartuner.viewmodel.TunerViewModel

/**
 * 吉他调音器主 Activity
 *
 * 负责：
 * 1. 麦克风权限请求流程（首次启动自动请求，拒绝时显示引导界面）
 * 2. Compose UI 内容挂载（GuitarTunerTheme 包裹）
 * 3. Activity 生命周期中的调音启停控制（onStop 停止以节省资源）
 * 4. 从系统设置返回后重新检查权限并自动恢复调音
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val viewModel: TunerViewModel by viewModels()

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 权限请求 launcher —— 必须在 Activity 创建阶段注册
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Log.i(TAG, "麦克风权限已授予，开始调音")
                viewModel.startTuning()
            } else {
                // 拒绝时不做额外处理，UI 会自动显示权限请求引导界面
                Log.w(TAG, "麦克风权限被拒绝，等待用户手动授权")
            }
        }

        setContent {
            GuitarTunerTheme {
                val uiState by viewModel.uiState.collectAsState()

                // 跟踪权限状态，初始化时检查当前权限
                var hasPermission by remember {
                    mutableStateOf(hasMicrophonePermission())
                }

                // 首次启动时：有权限则直接开始调音，无权限则请求
                LaunchedEffect(Unit) {
                    if (hasPermission) {
                        Log.i(TAG, "已有麦克风权限，首次启动直接开始调音")
                        viewModel.startTuning()
                    } else {
                        Log.i(TAG, "无麦克风权限，发起权限请求")
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                // 监听 Activity RESUMED 事件 —— 用户从系统设置返回后重新检查权限
                // 当用户在设置中手动开启权限后返回 App，此处能自动检测并恢复调音
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        val granted = hasMicrophonePermission()
                        hasPermission = granted
                        if (granted && !uiState.isListening) {
                            Log.i(TAG, "RESUMED: 权限已授予且未在调音，自动恢复调音")
                            viewModel.startTuning()
                        }
                    }
                }

                if (hasPermission) {
                    // 有权限：显示调音主界面
                    TunerScreen(
                        uiState = uiState,
                        onInstrumentSelected = { instrument ->
                            viewModel.selectInstrument(instrument)
                        },
                        onTuningModeSelected = { mode ->
                            viewModel.selectTuningMode(mode)
                        }
                    )
                } else {
                    // 无权限：显示权限请求引导界面
                    PermissionRequestScreen(
                        onRequestPermission = {
                            Log.i(TAG, "用户点击授权按钮，重新发起权限请求")
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Activity 不可见时停止调音，节省电量与系统资源（麦克风、CPU）
        Log.i(TAG, "onStop: Activity 不可见，停止调音")
        viewModel.stopTuning()
    }

    /**
     * 检查是否已持有麦克风录音权限
     *
     * @return true 如果 RECORD_AUDIO 权限已授予
     */
    private fun hasMicrophonePermission(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
}

/**
 * 权限请求引导界面
 *
 * 当用户未授予麦克风权限时显示此界面，
 * 说明调音器需要麦克风权限的原因，并提供"授予权限"按钮。
 *
 * @param onRequestPermission 用户点击"授予权限"按钮时的回调
 */
@Composable
private fun PermissionRequestScreen(
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 吉他图标
            Text(
                text = "\uD83C\uDFB8",
                fontSize = 64.sp
            )

            // 应用标题
            Text(
                text = "Guitar Tuner",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary
            )

            // 权限说明
            Text(
                text = "调音器需要使用麦克风来检测音高。\n请授予麦克风权限以开始使用。",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 授权按钮
            Button(
                onClick = onRequestPermission
            ) {
                Text("授予麦克风权限")
            }
        }
    }
}
