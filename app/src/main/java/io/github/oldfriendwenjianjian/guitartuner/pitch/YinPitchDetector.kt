package io.github.oldfriendwenjianjian.guitartuner.pitch

/**
 * YIN 音高检测器
 *
 * 实现 de Cheveigné & Kawahara (2002) 的 YIN 算法，用于从 PCM 音频信号中
 * 提取基频 (fundamental frequency)。
 *
 * 算法流程：
 *  1. 差分函数 (Difference Function)
 *  2. 累积均值归一化差分函数 (Cumulative Mean Normalized Difference Function)
 *  3. 绝对阈值搜索 (Absolute Threshold)
 *  4. 抛物线插值 (Parabolic Interpolation)
 *  5. 频率计算与有效性检验
 *
 * @param sampleRate 音频采样率 (Hz)，默认 44100
 * @param threshold  YIN 绝对阈值 (0.0~1.0)，越低越严格。默认 0.20
 */
class YinPitchDetector(
    private val sampleRate: Int = 44100,
    private val threshold: Double = 0.20
) {

    // Hanning 窗函数系数缓存：仅在缓冲区长度变化时重新计算
    private var cachedWindowSize: Int = 0
    private var hanningWindow: DoubleArray = DoubleArray(0)

    init {
        require(sampleRate > 0) { "sampleRate 必须为正整数，当前值: $sampleRate" }
        require(threshold in 0.0..1.0) { "threshold 必须在 0.0~1.0 范围内，当前值: $threshold" }
    }

    /**
     * 音高检测结果
     *
     * @param frequency  检测到的基频 (Hz)；null 表示未检测到有效音高
     * @param confidence 置信度 (0.0~1.0)，值越高表示检测结果越可靠。
     *                   当 frequency 为 null 时 confidence 为 0.0
     */
    data class PitchResult(
        val frequency: Double?,
        val confidence: Double
    )

    companion object {
        /** 人耳可感知的最低频率下限 (Hz) */
        private const val MIN_FREQUENCY = 20.0

        /** 本检测器支持的最高频率上限 (Hz)，覆盖吉他/尤克里里高把位泛音 */
        private const val MAX_FREQUENCY = 5000.0

        /** τ 搜索起始偏移量，对应最高可检测频率 ≈ sampleRate / TAU_MIN */
        private const val TAU_MIN = 2

        /** 静音检测的 RMS 阈值：低于此值视为静音/噪声 */
        private const val SILENCE_RMS_THRESHOLD = 0.01f
    }

    /**
     * 对输入的 PCM 音频缓冲区执行音高检测。
     *
     * @param buffer PCM 浮点音频数据，取值范围通常为 -1.0 ~ 1.0。
     *               长度应 >= 64 个采样（否则数据太短无法有效检测）。
     * @return [PitchResult] 包含检测频率与置信度
     */
    fun detect(buffer: FloatArray): PitchResult {
        // --- 前置校验 ---
        if (buffer.size < 64) {
            // 缓冲区过短，无法执行有意义的音高检测
            return PitchResult(frequency = null, confidence = 0.0)
        }

        // 静音检测：在加窗之前对原始 buffer 执行，
        // 因为 Hanning 窗会改变信号能量，影响 RMS 判断准确性
        if (isSilent(buffer)) {
            return PitchResult(frequency = null, confidence = 0.0)
        }

        // --- Hanning 窗预处理 ---
        // 对输入 PCM 缓冲区应用 Hanning 窗函数，消除帧边缘不连续性
        // 在 CMNDF 中减少因截断效应产生的虚假谷底
        val window = getHanningWindow(buffer.size)
        val windowedBuffer = FloatArray(buffer.size) { i ->
            (buffer[i] * window[i]).toFloat()
        }

        // --- Step 1: 差分函数（使用加窗后的数据） ---
        val diff = difference(windowedBuffer)

        // --- Step 2: 累积均值归一化 ---
        val cmndf = cumulativeMeanNormalize(diff)

        // --- Step 3: 绝对阈值搜索 ---
        val tauEstimate = absoluteThreshold(cmndf)
        if (tauEstimate == -1) {
            // 未找到低于阈值的周期候选，视为无有效音高
            return PitchResult(frequency = null, confidence = 0.0)
        }

        // --- Step 4: 抛物线插值 ---
        val refinedTau = parabolicInterpolation(cmndf, tauEstimate)

        // --- Step 5: 频率计算与有效性检验 ---
        if (refinedTau <= 0.0) {
            // 抛物线插值结果异常（τ <= 0 无物理意义），防御性返回无效
            return PitchResult(frequency = null, confidence = 0.0)
        }

        val frequency = sampleRate.toDouble() / refinedTau

        // 频率有效范围检查
        if (frequency < MIN_FREQUENCY || frequency > MAX_FREQUENCY) {
            return PitchResult(frequency = null, confidence = 0.0)
        }

        // 置信度 = 1.0 - CMNDF(bestTau)
        // CMNDF 值越接近 0 表示自相关性越强，即置信度越高
        val confidence = (1.0 - cmndf[tauEstimate]).coerceIn(0.0, 1.0)

        return PitchResult(frequency = frequency, confidence = confidence)
    }

    // ==================== 私有算法步骤 ====================

    /**
     * Step 1: 差分函数 (Difference Function)
     *
     * 公式：d(τ) = Σ_{j=0}^{W-1} (x[j] - x[j + τ])^2
     * 其中 W = buffer.size / 2（半缓冲区长度）
     *
     * @param buffer 输入 PCM 缓冲区
     * @return DoubleArray，长度 = halfBufferSize，d[τ] 表示延迟 τ 的差分值
     */
    private fun difference(buffer: FloatArray): DoubleArray {
        val halfBufferSize = buffer.size / 2
        val diff = DoubleArray(halfBufferSize)

        // d(0) 定义为 0（信号与自身完全相同）
        diff[0] = 0.0

        for (tau in 1 until halfBufferSize) {
            var sum = 0.0
            for (j in 0 until halfBufferSize) {
                val delta = (buffer[j] - buffer[j + tau]).toDouble()
                sum += delta * delta
            }
            diff[tau] = sum
        }

        return diff
    }

    /**
     * Step 2: 累积均值归一化差分函数 (Cumulative Mean Normalized Difference Function)
     *
     * 公式：
     *   d'(0) = 1
     *   d'(τ) = d(τ) / ((1/τ) * Σ_{j=1}^{τ} d(j))
     *
     * 归一化消除了信号整体能量的影响，使阈值判断更鲁棒。
     *
     * @param diff 差分函数结果
     * @return DoubleArray，归一化后的 CMNDF 值
     */
    private fun cumulativeMeanNormalize(diff: DoubleArray): DoubleArray {
        val cmndf = DoubleArray(diff.size)

        // d'(0) 定义为 1（论文约定）
        cmndf[0] = 1.0

        var runningSum = 0.0

        for (tau in 1 until diff.size) {
            runningSum += diff[tau]

            if (runningSum == 0.0) {
                // 累积和为 0 意味着信号在此延迟范围内完全静音
                // 为避免除零，设为 1（中性值，不会被误判为峰值）
                cmndf[tau] = 1.0
            } else {
                // d'(τ) = d(τ) / ((1/τ) * runningSum) = d(τ) * τ / runningSum
                cmndf[tau] = diff[tau] * tau.toDouble() / runningSum
            }
        }

        return cmndf
    }

    /**
     * Step 3: 绝对阈值搜索 (Absolute Threshold) — 多候选 τ 择优
     *
     * 在 CMNDF 中从 τ_min 开始，收集**所有**低于 [threshold] 的局部最小值作为候选集，
     * 而非仅返回第一个命中的谷底。这避免了泛音对应的较小 τ 先被命中导致八度误判。
     *
     * 候选择优策略：
     *  1. 收集所有低于阈值的局部谷底 (cmndf[τ+1] >= cmndf[τ]) 作为候选
     *  2. 若存在多个候选且呈近似整数倍关系，优先选择较大的 τ（对应更低的基频），
     *     前提是其 CMNDF 值不超过最优候选 CMNDF 值的 1.5 倍
     *  3. 无倍数关系时选 CMNDF 最低的候选
     *
     * "近似整数倍"判定：largerTau / smallerTau 接近某个整数 N (N >= 2)，
     * 容差 ±10%，即 abs(ratio - round(ratio)) / round(ratio) < 0.10
     *
     * @param cmndf 累积均值归一化差分函数结果
     * @return 最佳 τ 估计值，未找到时返回 -1
     */
    private fun absoluteThreshold(cmndf: DoubleArray): Int {
        // τ 搜索范围：TAU_MIN ~ cmndf.size - 2
        // 上界 -2 是因为抛物线插值需要 τ+1 位置的值
        val tauMax = cmndf.size - 2

        if (TAU_MIN >= tauMax) {
            // 搜索范围不足，无法检测
            return -1
        }

        // --- 阶段一：收集所有低于阈值的局部最小值候选 ---
        // candidateTaus[i] 与 candidateValues[i] 一一对应
        val candidateTaus = mutableListOf<Int>()
        val candidateValues = mutableListOf<Double>()

        var tau = TAU_MIN
        while (tau <= tauMax) {
            if (cmndf[tau] < threshold) {
                // 追踪到当前下降区段的局部谷底
                while (tau + 1 <= tauMax && cmndf[tau + 1] < cmndf[tau]) {
                    tau++
                }
                // 记录此谷底作为候选
                candidateTaus.add(tau)
                candidateValues.add(cmndf[tau])
                // 跳过整个阈值以下区间，避免在同一区间内重复收集
                tau++
                while (tau <= tauMax && cmndf[tau] < threshold) {
                    tau++
                }
                continue
            }
            tau++
        }

        // --- 阶段二：从候选集中择优 ---

        if (candidateTaus.isEmpty()) {
            // 未找到低于阈值的候选
            return -1
        }

        if (candidateTaus.size == 1) {
            // 仅一个候选，无需择优
            return candidateTaus[0]
        }

        // 找到 CMNDF 值最低的候选作为基准 best
        var bestIdx = 0
        for (i in 1 until candidateValues.size) {
            if (candidateValues[i] < candidateValues[bestIdx]) {
                bestIdx = i
            }
        }
        val bestCmndf = candidateValues[bestIdx]
        // 可接受的 CMNDF 上限：最优候选值的 1.5 倍
        // 超过此上限的候选即使满足整数倍关系也不予采纳（CMNDF 太高意味着自相关性太弱）
        val acceptableCmndfLimit = bestCmndf * 1.5

        // --- 阶段三：检查整数倍关系，优先选择较大 τ（基频） ---
        // 从最大 τ（最低频率，最可能是基频）向最小 τ 遍历，
        // 找到第一个满足条件的即返回
        for (i in candidateTaus.indices.reversed()) {
            if (candidateValues[i] > acceptableCmndfLimit) {
                // CMNDF 太高，自相关性不足，跳过
                continue
            }
            // 检查当前候选是否是某个较小候选的近似整数倍
            for (j in 0 until candidateTaus.size) {
                if (candidateTaus[j] >= candidateTaus[i]) {
                    // 只与比自己小的候选比较
                    continue
                }
                val ratio = candidateTaus[i].toDouble() / candidateTaus[j].toDouble()
                val roundedRatio = Math.round(ratio).toDouble()
                if (roundedRatio >= 2.0 &&
                    Math.abs(ratio - roundedRatio) / roundedRatio < 0.10
                ) {
                    // 找到整数倍关系：当前较大 τ 对应基频，较小 τ 对应泛音
                    // 选择较大 τ（基频）以避免八度/泛音误判
                    return candidateTaus[i]
                }
            }
        }

        // 无整数倍关系，返回 CMNDF 值最低的候选
        return candidateTaus[bestIdx]
    }

    /**
     * Step 4: 抛物线插值 (Parabolic Interpolation)
     *
     * 对 CMNDF 在 tauEstimate 附近做三点抛物线拟合，
     * 获得亚采样精度的 τ 值，提升频率分辨率。
     *
     * 使用 tauEstimate-1、tauEstimate、tauEstimate+1 三个点拟合抛物线，
     * 计算极值点的 τ 偏移量。
     *
     * @param cmndf       累积均值归一化差分函数结果
     * @param tauEstimate 绝对阈值搜索得到的整数 τ 估计
     * @return 经插值精化后的 τ 值（浮点数）
     */
    private fun parabolicInterpolation(cmndf: DoubleArray, tauEstimate: Int): Double {
        // 边界保护：tauEstimate 在数组两端时无法做三点插值，直接返回原值
        if (tauEstimate < 1 || tauEstimate >= cmndf.size - 1) {
            return tauEstimate.toDouble()
        }

        val s0 = cmndf[tauEstimate - 1]  // 左邻点
        val s1 = cmndf[tauEstimate]       // 中心点（当前估计）
        val s2 = cmndf[tauEstimate + 1]   // 右邻点

        // 抛物线极值点偏移量：delta = (s0 - s2) / (2 * (s0 - 2*s1 + s2))
        val denominator = 2.0 * (s0 - 2.0 * s1 + s2)

        if (denominator == 0.0) {
            // 三点共线（分母为 0），退化为无插值
            return tauEstimate.toDouble()
        }

        val delta = (s0 - s2) / denominator

        // 安全限制：插值偏移量不应超过 ±1（否则拟合异常）
        val clampedDelta = delta.coerceIn(-1.0, 1.0)

        return tauEstimate.toDouble() + clampedDelta
    }

    // ==================== 窗函数 ====================

    /**
     * 获取指定长度的 Hanning 窗系数数组（带缓存）。
     *
     * Hanning 窗公式：w(n) = 0.5 * (1 - cos(2π * n / (N - 1)))
     *
     * 缓存策略：窗函数系数仅在缓冲区长度变化时重新计算，
     * 相同长度的连续调用直接返回已缓存的数组。
     *
     * @param size 缓冲区长度（N）
     * @return DoubleArray 长度为 size 的 Hanning 窗系数
     */
    private fun getHanningWindow(size: Int): DoubleArray {
        if (size == cachedWindowSize) {
            return hanningWindow
        }

        val window = DoubleArray(size)
        if (size <= 1) {
            // 长度为 0 或 1 时，窗系数为 1.0（退化为矩形窗）
            if (size == 1) window[0] = 1.0
        } else {
            val denominator = (size - 1).toDouble()
            for (n in 0 until size) {
                window[n] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * n / denominator))
            }
        }

        cachedWindowSize = size
        hanningWindow = window
        return window
    }

    // ==================== 辅助方法 ====================

    /**
     * 检测缓冲区是否为静音（RMS 能量低于阈值）。
     *
     * 对于低信噪比或纯噪声信号，提前返回无效结果可避免
     * 差分函数在噪声上产生虚假周期候选。
     *
     * @param buffer 输入 PCM 缓冲区
     * @return true 表示缓冲区为静音或低能量信号
     */
    private fun isSilent(buffer: FloatArray): Boolean {
        if (buffer.isEmpty()) return true

        var sumSquares = 0.0
        for (sample in buffer) {
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        val rms = Math.sqrt(sumSquares / buffer.size)

        return rms < SILENCE_RMS_THRESHOLD
    }
}
