package com.example.guitartuner.music

import kotlin.math.pow

/**
 * 调音模式枚举
 * - STANDARD: 固定调弦模式，针对选定乐器的标准弦进行逐弦调音
 * - CHROMATIC: 色度调音模式，检测当前音高并匹配最近的半音
 */
enum class TuningMode {
    STANDARD,
    CHROMATIC
}

/**
 * 音符数据类
 *
 * @property name 音名，使用国际标准命名（C, C#, D, D#, E, F, F#, G, G#, A, A#, B）
 * @property octave 八度编号，如 4 表示第四八度
 * @property frequency 频率（Hz），基于 A4=440Hz 的等律标准
 */
data class Note(
    val name: String,
    val octave: Int,
    val frequency: Double
) {
    /** 完整音名，如 "A4"、"C#3" */
    val fullName: String get() = "$name$octave"

    /**
     * 对应的 MIDI 编号（0-127）
     * 公式：midi = (octave + 1) * 12 + noteIndex
     * 其中 noteIndex 为音名在 12 半音中的索引（C=0, C#=1, ..., B=11）
     */
    val midiNumber: Int
        get() {
            val noteIndex = InstrumentPresets.NOTE_NAMES.indexOf(name)
            // 若音名不在标准列表中，返回 -1 标识异常，调用方应检查
            if (noteIndex < 0) return -1
            return (octave + 1) * 12 + noteIndex
        }

    companion object {
        /**
         * 通过 MIDI 编号创建 Note 实例
         *
         * @param midiNumber MIDI 编号（有效范围 0-127）
         * @return Note 实例；若 midiNumber 超出 0-127 范围，会 clamp 到边界值
         */
        fun fromMidi(midiNumber: Int): Note {
            val clampedMidi = midiNumber.coerceIn(0, 127)
            val name = InstrumentPresets.NOTE_NAMES[clampedMidi % 12]
            val octave = (clampedMidi / 12) - 1
            val frequency = 440.0 * 2.0.pow((clampedMidi - 69).toDouble() / 12.0)
            return Note(name, octave, frequency)
        }
    }
}

/**
 * 乐器数据类
 *
 * @property name 乐器名称（英文）
 * @property strings 弦的标准调音列表，按弦序排列（通常从低音弦到高音弦，尤克里里例外）
 */
data class Instrument(
    val name: String,
    val strings: List<Note>
)

/**
 * 预设乐器与色度音阶
 *
 * 提供吉他、尤克里里、贝斯的标准调弦数据，以及覆盖 C1-B7 的完整色度音阶。
 * 所有频率均基于 A4=440Hz 的十二平均律计算：f = 440 × 2^((midi-69)/12)
 */
object InstrumentPresets {

    /** 标准 12 半音音名（C 起始） */
    val NOTE_NAMES: Array<String> = arrayOf(
        "C", "C#", "D", "D#", "E", "F",
        "F#", "G", "G#", "A", "A#", "B"
    )

    /**
     * 辅助函数：通过 MIDI 编号计算频率
     * 公式：f = 440 × 2^((midiNumber - 69) / 12)
     *
     * @param midiNumber MIDI 编号
     * @return 对应频率（Hz）
     */
    fun noteFrequency(midiNumber: Int): Double {
        return 440.0 * 2.0.pow((midiNumber - 69).toDouble() / 12.0)
    }

    /**
     * 完整色度音阶（惰性初始化）
     * 覆盖范围：C1 (MIDI 24) 到 B7 (MIDI 107)
     * 用于色度调音模式下匹配最近的半音
     */
    val chromaticScale: List<Note> by lazy {
        (24..107).map { midi ->
            Note.fromMidi(midi)
        }
    }

    // =============================================
    // 预设乐器：标准调弦
    // =============================================

    /**
     * 吉他标准调弦 EADGBE（6弦→1弦，低→高）
     *
     * | 弦号 | 音名 | MIDI |
     * |------|------|------|
     * | 6弦  | E2   | 40   |
     * | 5弦  | A2   | 45   |
     * | 4弦  | D3   | 50   |
     * | 3弦  | G3   | 55   |
     * | 2弦  | B3   | 59   |
     * | 1弦  | E4   | 64   |
     */
    val guitar: Instrument = Instrument(
        name = "Guitar",
        strings = listOf(
            Note("E", 2, noteFrequency(40)),  // 6弦 E2
            Note("A", 2, noteFrequency(45)),  // 5弦 A2
            Note("D", 3, noteFrequency(50)),  // 4弦 D3
            Note("G", 3, noteFrequency(55)),  // 3弦 G3
            Note("B", 3, noteFrequency(59)),  // 2弦 B3
            Note("E", 4, noteFrequency(64))   // 1弦 E4
        )
    )

    /**
     * 尤克里里标准调弦 GCEA（高G方案，4弦→1弦）
     *
     * | 弦号 | 音名 | MIDI |
     * |------|------|------|
     * | 4弦  | G4   | 67   |
     * | 3弦  | C4   | 60   |
     * | 2弦  | E4   | 64   |
     * | 1弦  | A4   | 69   |
     */
    val ukulele: Instrument = Instrument(
        name = "Ukulele",
        strings = listOf(
            Note("G", 4, noteFrequency(67)),  // 4弦 G4
            Note("C", 4, noteFrequency(60)),  // 3弦 C4
            Note("E", 4, noteFrequency(64)),  // 2弦 E4
            Note("A", 4, noteFrequency(69))   // 1弦 A4
        )
    )

    /**
     * 贝斯标准调弦 EADG（4弦→1弦，低→高）
     *
     * | 弦号 | 音名 | MIDI |
     * |------|------|------|
     * | 4弦  | E1   | 28   |
     * | 3弦  | A1   | 33   |
     * | 2弦  | D2   | 38   |
     * | 1弦  | G2   | 43   |
     */
    val bass: Instrument = Instrument(
        name = "Bass",
        strings = listOf(
            Note("E", 1, noteFrequency(28)),  // 4弦 E1
            Note("A", 1, noteFrequency(33)),  // 3弦 A1
            Note("D", 2, noteFrequency(38)),  // 2弦 D2
            Note("G", 2, noteFrequency(43))   // 1弦 G2
        )
    )

    /** 所有可用乐器列表（可用于 UI 选择器） */
    val allInstruments: List<Instrument> = listOf(guitar, ukulele, bass)

    /** 默认乐器（吉他） */
    val defaultInstrument: Instrument = guitar
}
