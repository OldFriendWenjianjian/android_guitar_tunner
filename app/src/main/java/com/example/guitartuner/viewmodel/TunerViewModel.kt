package com.example.guitartuner.viewmodel

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.guitartuner.audio.AudioInput
import com.example.guitartuner.music.Instrument
import com.example.guitartuner.music.InstrumentPresets
import com.example.guitartuner.music.NoteMapResult
import com.example.guitartuner.music.NoteMapper
import com.example.guitartuner.music.TuningMode
import com.example.guitartuner.pitch.YinPitchDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * 调音器 UI 状态
 *
 * 包含调音器界面所需的所有状态信息，由 [TunerViewModel] 通过 [StateFlow] 驱动 UI 更新。
 *
 * @property isListening 是否正在采集音频
 * @property detectedFrequency 检测到的频率 (Hz)；null 表示无有效检测
 * @property noteName 音名 (如 "A4"、"E2")；空字符串表示无检测
 * @property centDeviation cent 偏差，已 clamp 到 -50~+50 范围
 * @property isInTune 是否在调准范围内 (|cent| <= 5)
 * @property tuningDirection 偏高/偏低/准确/无信号
 * @property hasValidPitch 是否有有效音高检测结果
 * @property currentInstrument 当前选择的乐器
 * @property tuningMode 当前调音模式（固定调弦 / 色度）
 * @property stringIndex 当前匹配的弦号（0-based，仅固定调弦模式有值）
 * @property confidence 检测置信度 (0.0~1.0)
 */
data class TunerUiState(
    val isListening: Boolean = false,
    val detectedFrequency: Double? = null,
    val noteName: String = "",
    val centDeviation: Float = 0f,
    val isInTune: Boolean = false,
    val tuningDirection: TuningDirection = TuningDirection.NONE,
    val hasValidPitch: Boolean = false,
    val currentInstrument: Instrument = InstrumentPresets.defaultInstrument,
    val tuningMode: TuningMode = TuningMode.STANDARD,
    val stringIndex: Int? = null,
    val confidence: Double = 0.0
)

/**
 * 调音偏差方向
 */
enum class TuningDirection {
    /** 检测频率偏高于目标 */
    SHARP,
    /** 检测频率偏低于目标 */
    FLAT,
    /** 在调准范围内 (|cent| <= 5) */
    IN_TUNE,
    /** 无有效信号 */
    NONE
}

/**
 * 调音器 ViewModel
 *
 * 连接 [AudioInput] → [YinPitchDetector] → [NoteMapper] → UI State 完整信号链，
 * 实现音频采集、音高检测、音符映射，并通过 [StateFlow] 驱动 UI 更新。
 *
 * ## 信号平滑策略
 * - 使用指数移动平均 (EMA) 平滑频率，alpha=0.3
 * - 当频率跳变超过一个半音（约6%）时重置平滑（防止换弦时平滑延迟）
 *
 * ## 稳定策略
 * - 置信度阈值 0.5：低于此值视为无效检测
 * - 连续 [VALID_FRAME_THRESHOLD] 帧有效检测后才开始更新 UI（防止噪声偶发触发）
 * - 连续 [INVALID_FRAME_THRESHOLD] 帧无效检测后才清除 UI（防止偶尔丢帧闪烁）
 *
 * ## 生命周期
 * - [viewModelScope] 自动管理协程生命周期
 * - [onCleared] 中释放 [AudioInput] 资源
 */
class TunerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TunerViewModel"

        /**
         * EMA 平滑系数 (0 < alpha <= 1)
         * 越小越平滑但响应越慢；0.3 在调音器场景下兼顾平滑与响应速度
         */
        private const val SMOOTHING_ALPHA = 0.3

        /**
         * 置信度阈值：低于此值的检测结果视为不可靠
         * YIN 的 confidence 约 0.5 以上时结果较稳定
         */
        private const val CONFIDENCE_THRESHOLD = 0.5

        /**
         * 频率跳变阈值比率（半音约6%）
         * 当新频率与平滑频率的比值超出 [1-JUMP_RATIO, 1+JUMP_RATIO] 范围时，
         * 判定为换弦/换音，重置平滑状态以避免过渡延迟
         */
        private const val JUMP_RATIO = 0.06

        /** 连续无效帧阈值：超过此值才清除 UI 显示，防止偶尔丢帧导致闪烁 */
        private const val INVALID_FRAME_THRESHOLD = 5

        /** 连续有效帧阈值：至少连续此数量有效帧才开始更新 UI，过滤噪声偶发 */
        private const val VALID_FRAME_THRESHOLD = 2

        /** cent 偏差 clamp 范围下限 */
        private const val CENT_MIN = -50f

        /** cent 偏差 clamp 范围上限 */
        private const val CENT_MAX = 50f

        /** 调准判定阈值 (cent)：|centDeviation| <= 此值视为"准确" */
        private const val IN_TUNE_THRESHOLD = 5f

        /**
         * 跳变确认所需的连续帧数
         * 检测到频率跳变后，需要连续此数量帧指向同一目标频率区间，
         * 才确认跳变为真实换弦/换音（而非泛音误检测的单帧突变）
         */
        private const val JUMP_CONFIRM_FRAMES = 3
    }

    // ---- 核心模块 ----
    private val audioInput = AudioInput()
    private val pitchDetector = YinPitchDetector(
        sampleRate = AudioInput.SAMPLE_RATE
    )

    // ---- UI 状态 ----
    private val _uiState = MutableStateFlow(TunerUiState())
    val uiState: StateFlow<TunerUiState> = _uiState.asStateFlow()

    // ---- 平滑状态 ----
    /**
     * 当前指数移动平均平滑后的频率值
     * null 表示尚未建立平滑基准（初始状态或已重置）
     */
    private var smoothedFrequency: Double? = null

    /** 连续无效帧计数器 */
    private var consecutiveInvalidFrames = 0

    /** 连续有效帧计数器 */
    private var consecutiveValidFrames = 0

    /**
     * 跳变待确认的目标频率
     * null 表示当前不在跳变确认流程中；非 null 表示正等待后续帧确认该目标频率
     */
    private var pendingJumpFrequency: Double? = null

    /** 跳变确认帧计数器：已有多少连续帧指向 [pendingJumpFrequency] 所在区间 */
    private var jumpConfirmFrames: Int = 0

    /**
     * FIX-03: 保护平滑状态变量的协程 Mutex
     * smoothedFrequency、pendingJumpFrequency、jumpConfirmFrames、
     * consecutiveInvalidFrames、consecutiveValidFrames 在 Dispatchers.Default
     * （processAudioBuffer）和 Main（stopTuning、selectInstrument 等）之间共享，
     * 需要同步保护以避免数据竞争。
     * Mutex.withLock 是挂起函数，不会阻塞线程。
     */
    private val smoothingMutex = Mutex()

    init {
        // 在 Dispatchers.Default 上收集音频数据流并执行 YIN 音高检测
        // YIN 算法是 CPU 密集计算，避免阻塞主线程（Dispatchers.Main）
        // MutableStateFlow 本身是线程安全的，可从任何线程更新 _uiState
        viewModelScope.launch(Dispatchers.Default) {
            audioInput.audioData.collect { buffer ->
                processAudioBuffer(buffer)
            }
        }

        // 收集录音状态变化，当录音异常退出时同步 UI 状态
        viewModelScope.launch {
            audioInput.recordingState.collect { recording ->
                if (!recording && _uiState.value.isListening) {
                    // StateFlow 线程安全，无需 Mutex
                    _uiState.value = _uiState.value.copy(
                        isListening = false,
                        hasValidPitch = false,
                        noteName = "",
                        centDeviation = 0f,
                        tuningDirection = TuningDirection.NONE,
                        stringIndex = null,
                        confidence = 0.0
                    )
                    // FIX-03: 通过 Mutex 保护平滑状态重置
                    smoothingMutex.withLock {
                        smoothedFrequency = null
                        pendingJumpFrequency = null
                        jumpConfirmFrames = 0
                        consecutiveInvalidFrames = 0
                        consecutiveValidFrames = 0
                    }
                }
            }
        }

        Log.d(TAG, "TunerViewModel 初始化完成，音频数据收集协程已启动")
    }

    /**
     * 处理音频缓冲区：检测 → 映射 → 平滑 → 更新 UI 状态
     *
     * 完整信号处理链路：
     * 1. [YinPitchDetector.detect] 检测基频与置信度
     * 2. 判断有效性（frequency != null && confidence > [CONFIDENCE_THRESHOLD]）
     * 3. EMA 平滑频率（大跳变时重置）
     * 4. [NoteMapper.map] 将频率映射到音符
     * 5. 更新 [_uiState]
     *
     * @param buffer PCM 浮点音频缓冲区（-1.0 ~ 1.0）
     */
    private suspend fun processAudioBuffer(buffer: FloatArray) {
        // 防御性检查：空缓冲区不处理
        if (buffer.isEmpty()) {
            Log.w(TAG, "processAudioBuffer: 收到空缓冲区，跳过处理")
            return
        }

        val pitchResult: YinPitchDetector.PitchResult
        try {
            pitchResult = pitchDetector.detect(buffer)
        } catch (e: Exception) {
            // 防御性捕获：YinPitchDetector.detect 内部应不抛异常，
            // 但对外部模块做防护以保证 ViewModel 稳定性
            Log.e(TAG, "processAudioBuffer: 音高检测异常，bufferSize=${buffer.size}", e)
            return
        }

        val currentState = _uiState.value

        // FIX-03: Mutex 保护平滑状态变量，防止 Dispatchers.Default 与主线程间数据竞争
        smoothingMutex.withLock {
            if (pitchResult.frequency != null && pitchResult.confidence > CONFIDENCE_THRESHOLD) {
                // ---- 有效检测结果 ----
                consecutiveInvalidFrames = 0
                consecutiveValidFrames++

                val freq = pitchResult.frequency
                // 指数移动平均平滑
                smoothedFrequency = applyEmaSmoothing(freq)

                // 至少连续 N 帧有效才更新显示，过滤噪声偶发检测
                if (consecutiveValidFrames >= VALID_FRAME_THRESHOLD) {
                    // smoothedFrequency 在 applyEmaSmoothing 后必定非 null（已在内部赋值）
                    val smoothedFreq = smoothedFrequency ?: return@withLock

                    val mapResult: NoteMapResult? = try {
                        NoteMapper.map(
                            smoothedFreq,
                            currentState.tuningMode,
                            currentState.currentInstrument
                        )
                    } catch (e: Exception) {
                        // 防御性捕获：NoteMapper 理论上不应抛异常，但做防护
                        Log.e(
                            TAG,
                            "processAudioBuffer: 音符映射异常，" +
                                "freq=$smoothedFreq, mode=${currentState.tuningMode}, " +
                                "instrument=${currentState.currentInstrument.name}",
                            e
                        )
                        null
                    }

                    if (mapResult != null) {
                        val centClamped = mapResult.centDeviation.toFloat()
                            .coerceIn(CENT_MIN, CENT_MAX)

                        val direction = when {
                            abs(centClamped) <= IN_TUNE_THRESHOLD -> TuningDirection.IN_TUNE
                            centClamped > 0 -> TuningDirection.SHARP
                            else -> TuningDirection.FLAT
                        }

                        _uiState.value = currentState.copy(
                            detectedFrequency = smoothedFreq,
                            noteName = mapResult.note.fullName,
                            centDeviation = centClamped,
                            isInTune = direction == TuningDirection.IN_TUNE,
                            tuningDirection = direction,
                            hasValidPitch = true,
                            stringIndex = mapResult.stringIndex,
                            confidence = pitchResult.confidence
                        )
                    } else {
                        // 映射返回 null（频率超出有效音域范围），视为无效检测
                        Log.d(
                            TAG,
                            "processAudioBuffer: NoteMapper 返回 null，smoothedFreq=$smoothedFreq"
                        )
                        consecutiveValidFrames = 0
                        // FIX-02: 映射失败视为无效帧，立即重置跳变确认状态
                        pendingJumpFrequency = null
                        jumpConfirmFrames = 0
                        consecutiveInvalidFrames++
                        if (consecutiveInvalidFrames >= INVALID_FRAME_THRESHOLD) {
                            smoothedFrequency = null
                            pendingJumpFrequency = null
                            jumpConfirmFrames = 0
                            _uiState.value = currentState.copy(
                                detectedFrequency = null,
                                noteName = "",
                                centDeviation = 0f,
                                isInTune = false,
                                tuningDirection = TuningDirection.NONE,
                                hasValidPitch = false,
                                stringIndex = null,
                                confidence = 0.0
                            )
                        }
                    }
                }
            } else {
                // ---- 无效检测结果 ----
                consecutiveValidFrames = 0
                // FIX-02: 无效帧中断连续性，立即重置跳变确认状态
                pendingJumpFrequency = null
                jumpConfirmFrames = 0
                consecutiveInvalidFrames++

                // 连续 N 帧无效才清除显示，避免偶尔丢帧导致闪烁
                if (consecutiveInvalidFrames >= INVALID_FRAME_THRESHOLD) {
                    smoothedFrequency = null
                    pendingJumpFrequency = null
                    jumpConfirmFrames = 0
                    _uiState.value = currentState.copy(
                        detectedFrequency = null,
                        noteName = "",
                        centDeviation = 0f,
                        isInTune = false,
                        tuningDirection = TuningDirection.NONE,
                        hasValidPitch = false,
                        stringIndex = null,
                        confidence = 0.0
                    )
                }
            }
        }
    }

    /**
     * 对检测频率应用指数移动平均 (EMA) 平滑，并集成跳变确认状态机
     *
     * ## 正常范围
     * smoothedFreq = alpha * newFreq + (1 - alpha) * smoothedFreq
     *
     * ## 跳变确认机制
     * 当新频率与当前平滑频率的比率超出 [1 - JUMP_RATIO, 1 + JUMP_RATIO]（约一个半音）时，
     * 不再立即采纳新频率（避免泛音误检测的单帧突变透传到 UI），而是进入待确认状态：
     * - 后续连续 [JUMP_CONFIRM_FRAMES] 帧均指向相同目标区间 → 确认跳变，切换到新频率
     * - 任一帧回到原频率区间 → 判定为误检测，丢弃待确认频率
     * - 任一帧指向第三个频率 → 视为新的跳变目标，重新开始确认
     *
     * 待确认期间返回当前 smoothedFrequency（UI 不更新、不闪烁）。
     *
     * @param newFrequency 本帧检测到的原始频率 (Hz)
     * @return 平滑后的频率 (Hz)；待确认期间返回上一个稳定值
     */
    private fun applyEmaSmoothing(newFrequency: Double): Double {
        val currentSmoothed = smoothedFrequency

        if (currentSmoothed == null) {
            // 首帧或重置后，直接使用新频率作为平滑基准
            pendingJumpFrequency = null
            jumpConfirmFrames = 0
            return newFrequency
        }

        // 检测大跳变：新频率与平滑频率的比率是否超出半音范围
        val ratio = newFrequency / currentSmoothed
        val isJump = ratio < (1.0 - JUMP_RATIO) || ratio > (1.0 + JUMP_RATIO)

        if (!isJump) {
            // 正常范围内，取消任何待确认状态并执行 EMA 平滑
            if (pendingJumpFrequency != null) {
                Log.d(
                    TAG,
                    "applyEmaSmoothing: 频率回到原区间，丢弃待确认跳变" +
                        "（pending=${"%.2f".format(pendingJumpFrequency)}Hz）"
                )
            }
            pendingJumpFrequency = null
            jumpConfirmFrames = 0
            return SMOOTHING_ALPHA * newFrequency + (1.0 - SMOOTHING_ALPHA) * currentSmoothed
        }

        // --- 检测到跳变 ---
        val pendingFreq = pendingJumpFrequency

        if (pendingFreq == null) {
            // 第一次检测到跳变，进入待确认状态
            pendingJumpFrequency = newFrequency
            jumpConfirmFrames = 1
            Log.d(
                TAG,
                "applyEmaSmoothing: 频率跳变检测，进入待确认状态，" +
                    "ratio=${"%.4f".format(ratio)}，" +
                    "current=${"%.2f".format(currentSmoothed)}Hz，" +
                    "pending=${"%.2f".format(newFrequency)}Hz（1/$JUMP_CONFIRM_FRAMES）"
            )
            // UI 不更新，返回上一个稳定值
            return currentSmoothed
        }

        // 已在待确认状态，检查新频率与待确认频率的关系
        val pendingRatio = newFrequency / pendingFreq
        val isInPendingRange =
            pendingRatio >= (1.0 - JUMP_RATIO) && pendingRatio <= (1.0 + JUMP_RATIO)

        if (isInPendingRange) {
            // 与待确认频率一致，累计确认帧
            jumpConfirmFrames++
            if (jumpConfirmFrames >= JUMP_CONFIRM_FRAMES) {
                // 确认跳变！切换到新频率
                Log.d(
                    TAG,
                    "applyEmaSmoothing: 跳变确认完成（$jumpConfirmFrames/$JUMP_CONFIRM_FRAMES），" +
                        "old=${"%.2f".format(currentSmoothed)}Hz → " +
                        "new=${"%.2f".format(newFrequency)}Hz"
                )
                pendingJumpFrequency = null
                jumpConfirmFrames = 0
                // 返回新频率，processAudioBuffer 会将其赋给 smoothedFrequency
                return newFrequency
            } else {
                Log.d(
                    TAG,
                    "applyEmaSmoothing: 跳变确认中（$jumpConfirmFrames/$JUMP_CONFIRM_FRAMES），" +
                        "pending=${"%.2f".format(pendingFreq)}Hz，" +
                        "frame=${"%.2f".format(newFrequency)}Hz"
                )
                // 仍在确认中，UI 不更新
                return currentSmoothed
            }
        } else {
            // 新频率既不在原区间也不在待确认区间 → 视为新的跳变目标，重新开始确认
            Log.d(
                TAG,
                "applyEmaSmoothing: 新的跳变目标，重新开始确认，" +
                    "oldPending=${"%.2f".format(pendingFreq)}Hz → " +
                    "newPending=${"%.2f".format(newFrequency)}Hz"
            )
            pendingJumpFrequency = newFrequency
            jumpConfirmFrames = 1
            // UI 不更新
            return currentSmoothed
        }
    }

    /**
     * 开始调音（启动音频采集）
     *
     * 需要 [Manifest.permission.RECORD_AUDIO] 权限。
     * 调用前应确保权限已授予，否则 [AudioInput.start] 将失败。
     *
     * @return true 如果成功启动采集，false 如果启动失败
     *         （可能原因：权限未授予、麦克风被占用、AudioRecord 初始化失败）
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startTuning(): Boolean {
        if (_uiState.value.isListening) {
            Log.d(TAG, "startTuning: 已在采集状态，跳过重复启动")
            return true
        }

        val started = audioInput.start()
        if (started) {
            _uiState.value = _uiState.value.copy(isListening = true)
            Log.i(TAG, "startTuning: 调音已启动")
        } else {
            Log.e(TAG, "startTuning: AudioInput 启动失败，请检查麦克风权限与设备状态")
        }
        return started
    }

    /**
     * 停止调音（停止音频采集并重置状态）
     *
     * 停止后 UI 状态将重置为默认值，但保留当前的乐器选择和调音模式。
     * 可安全重复调用。
     */
    fun stopTuning() {
        audioInput.stop()

        // FIX-03: 通过 Mutex 保护平滑状态重置，防止与 processAudioBuffer 的数据竞争
        viewModelScope.launch(Dispatchers.Default) {
            smoothingMutex.withLock {
                smoothedFrequency = null
                pendingJumpFrequency = null
                jumpConfirmFrames = 0
                consecutiveInvalidFrames = 0
                consecutiveValidFrames = 0
            }
        }

        // 重置 UI 状态，但保留用户的乐器和模式选择（StateFlow 线程安全，无需 Mutex）
        val preservedInstrument = _uiState.value.currentInstrument
        val preservedMode = _uiState.value.tuningMode
        _uiState.value = TunerUiState(
            currentInstrument = preservedInstrument,
            tuningMode = preservedMode
        )

        Log.i(TAG, "stopTuning: 调音已停止，状态已重置")
    }

    /**
     * 切换乐器
     *
     * 切换时重置平滑状态和检测结果，因为不同乐器的弦频率不同，
     * 旧的平滑值会影响新乐器的匹配结果。
     *
     * @param instrument 要切换到的目标乐器
     */
    fun selectInstrument(instrument: Instrument) {
        // FIX-03: 通过 Mutex 保护平滑状态重置，防止与 processAudioBuffer 的数据竞争
        viewModelScope.launch(Dispatchers.Default) {
            smoothingMutex.withLock {
                smoothedFrequency = null
                pendingJumpFrequency = null
                jumpConfirmFrames = 0
                consecutiveInvalidFrames = 0
                consecutiveValidFrames = 0
            }
        }

        // StateFlow 线程安全，无需 Mutex
        _uiState.value = _uiState.value.copy(
            currentInstrument = instrument,
            hasValidPitch = false,
            noteName = "",
            centDeviation = 0f,
            tuningDirection = TuningDirection.NONE,
            stringIndex = null
        )

        Log.i(TAG, "selectInstrument: 切换乐器为 ${instrument.name}")
    }

    /**
     * 切换调音模式
     *
     * 切换时重置平滑状态和检测结果，因为不同模式的映射逻辑不同，
     * 需要从干净状态重新开始检测。
     *
     * @param mode 要切换到的目标调音模式
     */
    fun selectTuningMode(mode: TuningMode) {
        // FIX-03: 通过 Mutex 保护平滑状态重置，防止与 processAudioBuffer 的数据竞争
        viewModelScope.launch(Dispatchers.Default) {
            smoothingMutex.withLock {
                smoothedFrequency = null
                pendingJumpFrequency = null
                jumpConfirmFrames = 0
                consecutiveInvalidFrames = 0
                consecutiveValidFrames = 0
            }
        }

        // StateFlow 线程安全，无需 Mutex
        _uiState.value = _uiState.value.copy(
            tuningMode = mode,
            hasValidPitch = false,
            noteName = "",
            centDeviation = 0f,
            tuningDirection = TuningDirection.NONE,
            stringIndex = null
        )

        Log.i(TAG, "selectTuningMode: 切换调音模式为 $mode")
    }

    /**
     * ViewModel 销毁时释放音频资源
     *
     * 由 Android Framework 在 Activity/Fragment 销毁时自动调用。
     * 释放 [AudioInput] 实例（包括停止录音、取消协程、释放 AudioRecord）。
     */
    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "onCleared: 释放 AudioInput 资源")
        audioInput.release()
    }
}
