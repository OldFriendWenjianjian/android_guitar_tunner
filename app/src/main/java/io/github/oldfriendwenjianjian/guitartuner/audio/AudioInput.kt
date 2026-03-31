package io.github.oldfriendwenjianjian.guitartuner.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 音频输入模块 - 负责从麦克风采集 PCM 音频数据
 *
 * 使用 AudioRecord API 进行实时音频采集，
 * 通过 SharedFlow 输出 FloatArray 格式的 PCM 数据块。
 *
 * 使用方式：
 * ```
 * val audioInput = AudioInput()
 * // 收集音频数据流
 * scope.launch {
 *     audioInput.audioData.collect { buffer ->
 *         // 处理 FloatArray PCM 数据（范围 -1.0 ~ 1.0）
 *     }
 * }
 * // 开始采集（需要 RECORD_AUDIO 权限）
 * val started = audioInput.start()
 * // 停止采集
 * audioInput.stop()
 * // 完全释放资源
 * audioInput.release()
 * ```
 */
class AudioInput {

    companion object {
        private const val TAG = "AudioInput"

        /** 采样率：44100Hz，CD 品质，适合音高检测 */
        const val SAMPLE_RATE = 44100

        /** 声道配置：单声道，调音器只需要单声道即可 */
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

        /** 音频编码：16-bit PCM */
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** 缓冲区采样数：4096 samples，YIN 算法需要足够的窗口大小 */
        const val BUFFER_SIZE_SAMPLES = 4096

        /** 16-bit PCM 每个采样占 2 字节 */
        private const val BYTES_PER_SAMPLE = 2

        /** Short.MAX_VALUE 的浮点形式，用于归一化转换，避免每次转换时重复计算 */
        private const val SHORT_MAX_FLOAT = 32767.0f
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 音频数据流 - 输出归一化后的 FloatArray PCM 数据块（范围 -1.0 ~ 1.0）
     *
     * replay = 0：不回放历史帧，新订阅者只收到订阅后的数据
     * extraBufferCapacity = 2：允许缓冲 2 帧，避免背压丢帧
     * onBufferOverflow = DROP_OLDEST：当下游处理慢时丢弃最旧帧，避免阻塞录音读取循环
     */
    private val _audioData = MutableSharedFlow<FloatArray>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioData: SharedFlow<FloatArray> = _audioData.asSharedFlow()

    /**
     * 录音状态流 - 暴露录音启停状态变化
     *
     * 当录音协程异常退出时，ViewModel 可通过此流感知并同步 UI 状态。
     * true 表示正在录音，false 表示录音已停止。
     */
    private val _recordingState = MutableStateFlow(false)
    val recordingState: StateFlow<Boolean> = _recordingState.asStateFlow()

    /**
     * 当前是否正在录音
     * 外部只读，内部可写
     */
    @Volatile
    var isRecording: Boolean = false
        private set

    /**
     * 开始录音
     *
     * 初始化 AudioRecord 并在 IO 协程中持续读取 PCM 数据，
     * 将 Short 格式音频归一化为 Float (-1.0 ~ 1.0) 后通过 [audioData] 发射。
     *
     * @return true 如果成功启动录音，false 如果初始化失败
     *         （可能原因：设备不支持指定参数、权限未授予、AudioRecord 初始化异常）
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): Boolean {
        if (isRecording) {
            Log.d(TAG, "start(): 已在录音状态，跳过重复启动")
            return true
        }

        // 1. 获取系统要求的最小缓冲区大小
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(
                TAG,
                "start(): getMinBufferSize 失败，返回值=$minBufferSize " +
                    "(sampleRate=$SAMPLE_RATE, channelConfig=$CHANNEL_CONFIG, audioFormat=$AUDIO_FORMAT)，" +
                    "可能设备不支持此音频参数配置"
            )
            return false
        }

        // 2. 取系统最小缓冲区与 YIN 算法所需缓冲区的较大值
        val bufferSizeInBytes = maxOf(minBufferSize, BUFFER_SIZE_SAMPLES * BYTES_PER_SAMPLE)
        Log.d(
            TAG,
            "start(): minBufferSize=${minBufferSize}B, " +
                "targetBuffer=${BUFFER_SIZE_SAMPLES * BYTES_PER_SAMPLE}B, " +
                "actualBuffer=${bufferSizeInBytes}B"
        )

        // 3. 创建 AudioRecord 实例
        val record: AudioRecord
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSizeInBytes
            )
        } catch (e: SecurityException) {
            // 权限未授予时 AudioRecord 构造函数可能抛出 SecurityException
            Log.e(TAG, "start(): 创建 AudioRecord 失败，权限异常: ${e.message}", e)
            return false
        } catch (e: IllegalArgumentException) {
            // 非法参数（理论上不应发生，因为参数已校验，但做防御性处理）
            Log.e(TAG, "start(): 创建 AudioRecord 失败，参数异常: ${e.message}", e)
            return false
        }

        // 4. 检查 AudioRecord 初始化状态
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(
                TAG,
                "start(): AudioRecord 初始化失败，state=${record.state}，" +
                    "请检查麦克风是否被其他应用占用"
            )
            record.release()
            return false
        }

        // 5. 启动录音
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            // AudioRecord 未处于正确状态时可能抛出
            Log.e(TAG, "start(): startRecording() 失败: ${e.message}", e)
            record.release()
            return false
        }

        audioRecord = record
        isRecording = true
        _recordingState.value = true
        Log.i(TAG, "start(): 录音已启动 (sampleRate=$SAMPLE_RATE, bufferSamples=$BUFFER_SIZE_SAMPLES)")

        // 6. 在 IO 协程中持续读取音频数据
        recordingJob = scope.launch {
            val shortBuffer = ShortArray(BUFFER_SIZE_SAMPLES)

            try {
                while (isActive && isRecording) {
                    val currentRecord = audioRecord ?: break

                    val readCount = try {
                        currentRecord.read(shortBuffer, 0, BUFFER_SIZE_SAMPLES)
                    } catch (e: Exception) {
                        // 防御性捕获：AudioRecord.read() 在异常状态下可能抛出
                        Log.e(TAG, "录音读取异常: ${e.message}", e)
                        break
                    }

                    when {
                        readCount > 0 -> {
                            // Short (-32768 ~ 32767) → Float (-1.0 ~ 1.0) 归一化
                            val floatBuffer = FloatArray(readCount) { i ->
                                shortBuffer[i].toFloat() / SHORT_MAX_FLOAT
                            }
                            if (!_audioData.tryEmit(floatBuffer)) {
                                Log.d(TAG, "audioData 缓冲已满，丢弃当前帧")
                            }
                        }
                        readCount == AudioRecord.ERROR_INVALID_OPERATION -> {
                            Log.e(TAG, "AudioRecord.read() 返回 ERROR_INVALID_OPERATION，停止采集")
                            break
                        }
                        readCount == AudioRecord.ERROR_BAD_VALUE -> {
                            Log.e(TAG, "AudioRecord.read() 返回 ERROR_BAD_VALUE，停止采集")
                            break
                        }
                        readCount == AudioRecord.ERROR_DEAD_OBJECT -> {
                            Log.e(TAG, "AudioRecord.read() 返回 ERROR_DEAD_OBJECT，音频设备可能已断开")
                            break
                        }
                        readCount == AudioRecord.ERROR -> {
                            Log.e(TAG, "AudioRecord.read() 返回通用错误 ERROR，停止采集")
                            break
                        }
                        // readCount == 0 表示暂无数据可读，继续循环
                    }
                }
            } finally {
                // 确保异常退出或正常退出时都释放 AudioRecord 资源
                val record = audioRecord
                audioRecord = null
                isRecording = false
                _recordingState.value = false
                if (record != null) {
                    try { record.stop() } catch (_: IllegalStateException) {}
                    record.release()
                    Log.i(TAG, "录音协程退出，AudioRecord 已释放")
                }
            }
        }

        return true
    }

    /**
     * 停止录音并释放 AudioRecord 资源
     *
     * 可安全重复调用；停止后可通过 [start] 重新开始录音。
     * 注意：此方法不会取消 [scope]，实例仍可复用。
     */
    fun stop() {
        if (!isRecording && audioRecord == null) {
            Log.d(TAG, "stop(): 未在录音状态，跳过")
            return
        }

        isRecording = false
        _recordingState.value = false
        recordingJob?.cancel()
        recordingJob = null

        val record = audioRecord
        audioRecord = null

        if (record != null) {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
                // AudioRecord 可能未处于录音状态（如初始化后未成功 startRecording），
                // 此异常可安全忽略，因为接下来会 release
                Log.d(TAG, "stop(): AudioRecord.stop() 抛出 IllegalStateException，已忽略")
            }
            record.release()
            Log.i(TAG, "stop(): AudioRecord 已停止并释放")
        }
    }

    /**
     * 释放所有资源（在不再需要此实例时调用）
     *
     * 调用后此实例不可再使用（协程作用域已取消）。
     * 若需重新采集音频，应创建新的 AudioInput 实例。
     */
    fun release() {
        Log.i(TAG, "release(): 释放所有资源")
        stop()
        scope.cancel()
    }
}
