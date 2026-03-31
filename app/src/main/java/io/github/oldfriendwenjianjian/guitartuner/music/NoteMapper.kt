package io.github.oldfriendwenjianjian.guitartuner.music

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 音符映射结果
 *
 * @property note 匹配到的音符
 * @property centDeviation 偏差（cent），正值=偏高，负值=偏低
 * @property frequency 检测到的原始频率（Hz）
 * @property stringIndex 在固定调弦模式下对应的弦号（0-based），色度模式为 null
 */
data class NoteMapResult(
    val note: Note,
    val centDeviation: Double,
    val frequency: Double,
    val stringIndex: Int? = null
)

/**
 * 音符映射器
 *
 * 将检测到的频率映射到最近的音符，并计算 cent 偏差。
 * 支持两种模式：
 * - 色度模式（CHROMATIC）：在完整色度音阶中匹配最近的半音
 * - 固定调弦模式（STANDARD）：在给定乐器的弦列表中匹配最近的目标弦
 *
 * cent 偏差公式：cents = 1200 × log₂(f_detected / f_target)
 * 正值表示检测频率偏高，负值表示偏低。
 */
object NoteMapper {

    /** ln(2) 预计算常量，用于 log₂ 换算：log₂(x) = ln(x) / ln(2) */
    private const val LN2 = 0.6931471805599453

    /** 色度音阶 MIDI 范围下界（C1） */
    private const val CHROMATIC_MIDI_MIN = 24

    /** 色度音阶 MIDI 范围上界（B7） */
    private const val CHROMATIC_MIDI_MAX = 107

    /**
     * 计算两个频率之间的偏差（cent）
     *
     * 公式：cents = 1200 × log₂(detected / target) = 1200 × ln(detected / target) / ln(2)
     * - 正值：检测频率高于目标频率
     * - 负值：检测频率低于目标频率
     * - 0.0：完全匹配，或输入无效（频率 <= 0）
     *
     * @param detectedFreq 检测到的频率（Hz），必须 > 0
     * @param targetFreq 目标频率（Hz），必须 > 0
     * @return cent 偏差值；若任一频率 <= 0 则返回 0.0（无效输入的安全降级）
     */
    fun calculateCents(detectedFreq: Double, targetFreq: Double): Double {
        // 边界防护：频率 <= 0 无物理意义，安全降级为 0 偏差
        if (detectedFreq <= 0.0 || targetFreq <= 0.0) return 0.0
        // NaN / Infinity 防护：ln 对 NaN/Inf 输入的结果仍为 NaN/Inf，此处提前拦截
        if (detectedFreq.isNaN() || detectedFreq.isInfinite() ||
            targetFreq.isNaN() || targetFreq.isInfinite()
        ) {
            return 0.0
        }
        return 1200.0 * ln(detectedFreq / targetFreq) / LN2
    }

    /**
     * 色度模式：匹配最近的半音
     *
     * 使用数学公式直接计算最近的 MIDI 编号（O(1) 复杂度），
     * 而非遍历色度音阶列表（O(n)），效率更高。
     *
     * 公式：midi = 69 + 12 × log₂(f / 440)
     *
     * @param frequency 检测到的频率（Hz）
     * @return 映射结果；若频率 <= 0 或超出色度音阶覆盖范围（MIDI 24-107）则返回 null
     */
    fun mapChromatic(frequency: Double): NoteMapResult? {
        // 边界防护：无效频率
        if (frequency <= 0.0 || frequency.isNaN() || frequency.isInfinite()) return null

        // 通过数学公式直接计算最近 MIDI 编号，避免遍历
        // midi = 69 + 12 * log2(f / 440) = 69 + 12 * ln(f / 440) / ln(2)
        val midiFloat = 69.0 + 12.0 * ln(frequency / 440.0) / LN2
        val nearestMidi = midiFloat.roundToInt()

        // 检查 MIDI 编号是否在色度音阶覆盖范围内（C1=24 到 B7=107）
        // 超出范围说明频率过低或过高，不在调音器有效音域内
        if (nearestMidi < CHROMATIC_MIDI_MIN || nearestMidi > CHROMATIC_MIDI_MAX) return null

        val note = Note.fromMidi(nearestMidi)
        val cents = calculateCents(frequency, note.frequency)

        return NoteMapResult(
            note = note,
            centDeviation = cents,
            frequency = frequency
        )
    }

    /**
     * 固定调弦模式：匹配乐器中最近的目标弦
     *
     * 在给定乐器的弦列表中，按绝对 cent 偏差找到与检测频率最接近的弦。
     * 这种方式比按频率差找最近更符合音乐感知，因为 cent 是对数尺度。
     *
     * @param frequency 检测到的频率（Hz）
     * @param instrument 当前选择的乐器（包含弦列表）
     * @return 映射结果（含弦号 stringIndex）；若频率 <= 0 或乐器弦列表为空则返回 null
     */
    fun mapToInstrument(frequency: Double, instrument: Instrument): NoteMapResult? {
        // 边界防护：无效频率或空弦列表
        if (frequency <= 0.0 || frequency.isNaN() || frequency.isInfinite()) return null
        if (instrument.strings.isEmpty()) return null

        var bestIndex = 0
        var bestCentAbs = Double.MAX_VALUE

        // 遍历所有弦，找绝对 cent 偏差最小的弦
        instrument.strings.forEachIndexed { index, stringNote ->
            // 弦频率应 > 0（由 Instruments.kt 保证），但做防御性检查
            if (stringNote.frequency <= 0.0) return@forEachIndexed
            val centAbs = abs(calculateCents(frequency, stringNote.frequency))
            if (centAbs < bestCentAbs) {
                bestCentAbs = centAbs
                bestIndex = index
            }
        }

        val targetNote = instrument.strings[bestIndex]
        // 二次防护：如果最佳匹配弦的频率无效（理论上不应发生），返回 null
        if (targetNote.frequency <= 0.0) return null

        val cents = calculateCents(frequency, targetNote.frequency)

        return NoteMapResult(
            note = targetNote,
            centDeviation = cents,
            frequency = frequency,
            stringIndex = bestIndex
        )
    }

    /**
     * 通用映射方法：根据调音模式自动选择映射策略
     *
     * - CHROMATIC 模式：调用 [mapChromatic]，在完整色度音阶中匹配
     * - STANDARD 模式：调用 [mapToInstrument]，在乐器弦列表中匹配
     *
     * @param frequency 检测到的频率（Hz）
     * @param mode 当前调音模式
     * @param instrument 当前选择的乐器（仅 STANDARD 模式使用）
     * @return 映射结果；若频率无效或超出范围则返回 null
     */
    fun map(
        frequency: Double,
        mode: TuningMode,
        instrument: Instrument
    ): NoteMapResult? {
        return when (mode) {
            TuningMode.CHROMATIC -> mapChromatic(frequency)
            TuningMode.STANDARD -> mapToInstrument(frequency, instrument)
        }
    }
}
