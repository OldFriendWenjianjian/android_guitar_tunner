package com.example.guitartuner.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guitartuner.music.Instrument
import com.example.guitartuner.music.InstrumentPresets
import com.example.guitartuner.music.TuningMode
import com.example.guitartuner.ui.components.TuningMeter
import com.example.guitartuner.ui.theme.AccentGold
import com.example.guitartuner.ui.theme.Blue500
import com.example.guitartuner.ui.theme.DarkBackground
import com.example.guitartuner.ui.theme.DarkSurface
import com.example.guitartuner.ui.theme.Green500
import com.example.guitartuner.ui.theme.GuitarTunerTheme
import com.example.guitartuner.ui.theme.Red500
import com.example.guitartuner.ui.theme.TextPrimary
import com.example.guitartuner.ui.theme.TextSecondary
import com.example.guitartuner.viewmodel.TunerUiState
import com.example.guitartuner.viewmodel.TuningDirection

// ============================================================
// 公开 API
// ============================================================

/**
 * 调音器主界面 — 纯展示组件
 *
 * 集成所有 UI 元素：乐器选择、模式切换、音名显示、偏差仪表盘、
 * 频率显示、弦序列、状态提示。
 *
 * 不持有 ViewModel 引用，不处理权限逻辑——由 MainActivity (TODO-9) 负责
 * ViewModel 实例化、权限申请与状态收集。
 *
 * 布局从上到下依次为：
 * 1. 乐器选择器（Guitar / Ukulele / Bass）
 * 2. 调音模式切换（标准调弦 / 色度调音）
 * 3. 音名显示（大字 + 弦号）
 * 4. 偏差仪表盘
 * 5. 频率显示
 * 6. 弦序列（仅 STANDARD 模式）
 * 7. 状态提示
 *
 * @param uiState 由 ViewModel 提供的当前 UI 状态快照
 * @param onInstrumentSelected 用户选择乐器时的回调
 * @param onTuningModeSelected 用户切换调音模式时的回调
 * @param modifier 外部 Modifier
 */
@Composable
fun TunerScreen(
    uiState: TunerUiState,
    onInstrumentSelected: (Instrument) -> Unit,
    onTuningModeSelected: (TuningMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ---- 1. 乐器选择器 ----
        InstrumentSelector(
            instruments = InstrumentPresets.allInstruments,
            selectedInstrument = uiState.currentInstrument,
            onSelected = onInstrumentSelected
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ---- 2. 调音模式切换 ----
        TuningModeSelector(
            currentMode = uiState.tuningMode,
            onModeSelected = onTuningModeSelected
        )

        Spacer(modifier = Modifier.height(28.dp))

        // ---- 3. 音名显示（大字 + 弦号） ----
        NoteDisplay(
            noteName = uiState.noteName,
            hasValidPitch = uiState.hasValidPitch,
            tuningDirection = uiState.tuningDirection,
            isInTune = uiState.isInTune,
            tuningMode = uiState.tuningMode,
            stringIndex = uiState.stringIndex
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ---- 4. 偏差仪表盘 ----
        TuningMeter(
            centDeviation = uiState.centDeviation,
            isActive = uiState.hasValidPitch,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ---- 5. 频率显示 ----
        FrequencyDisplay(
            frequency = uiState.detectedFrequency,
            hasValidPitch = uiState.hasValidPitch
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ---- 6. 弦序列（仅 STANDARD 模式，带动画切换） ----
        AnimatedVisibility(
            visible = uiState.tuningMode == TuningMode.STANDARD,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            StringSequenceRow(
                instrument = uiState.currentInstrument,
                activeStringIndex = uiState.stringIndex
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ---- 7. 状态提示 ----
        StatusHint(
            hasValidPitch = uiState.hasValidPitch,
            tuningDirection = uiState.tuningDirection,
            isInTune = uiState.isInTune
        )
    }
}

// ============================================================
// 内部 — 子组件
// ============================================================

/**
 * 乐器选择器 — 横向 SegmentedButton（Guitar / Ukulele / Bass）
 *
 * 当前选中的乐器以 [AccentGold] 高亮。
 */
@Composable
private fun InstrumentSelector(
    instruments: List<Instrument>,
    selectedInstrument: Instrument,
    onSelected: (Instrument) -> Unit,
    modifier: Modifier = Modifier
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.fillMaxWidth()
    ) {
        instruments.forEachIndexed { index, instrument ->
            SegmentedButton(
                selected = instrument.name == selectedInstrument.name,
                onClick = { onSelected(instrument) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = instruments.size
                ),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = AccentGold.copy(alpha = 0.15f),
                    activeContentColor = AccentGold,
                    activeBorderColor = AccentGold.copy(alpha = 0.6f),
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = TextSecondary,
                    inactiveBorderColor = TextSecondary.copy(alpha = 0.3f)
                ),
                icon = { /* 不显示默认勾选图标 */ }
            ) {
                Text(
                    text = instrument.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * 调音模式切换 — 双项 SegmentedButton（标准调弦 / 色度调音）
 *
 * 当前选中模式以 [Green500] 高亮。
 */
@Composable
private fun TuningModeSelector(
    currentMode: TuningMode,
    onModeSelected: (TuningMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = TuningMode.entries
    val modeLabels = mapOf(
        TuningMode.STANDARD to "标准调弦",
        TuningMode.CHROMATIC to "色度调音"
    )

    SingleChoiceSegmentedButtonRow(
        modifier = modifier.fillMaxWidth(0.7f)
    ) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == currentMode,
                onClick = { onModeSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = modes.size
                ),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Green500.copy(alpha = 0.15f),
                    activeContentColor = Green500,
                    activeBorderColor = Green500.copy(alpha = 0.5f),
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = TextSecondary,
                    inactiveBorderColor = TextSecondary.copy(alpha = 0.3f)
                ),
                icon = { /* 不显示默认勾选图标 */ }
            ) {
                Text(
                    text = modeLabels[mode] ?: mode.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * 音名显示区域 — 大号音名 + 弦号（STANDARD 模式）
 *
 * 大号音名随调音状态变色：
 * - 准确 → [Green500]
 * - 偏高 → [Red500]
 * - 偏低 → [Blue500]
 * - 无信号 → [TextSecondary] 半透明
 *
 * 外层 [Surface] 以 [DarkSurface] 半透明卡片呈现，增加层次感。
 *
 * @param noteName 音名（如 "A4"），空字符串表示无检测
 * @param hasValidPitch 是否有有效音高
 * @param tuningDirection 调音偏差方向
 * @param isInTune 是否在调准范围内
 * @param tuningMode 当前调音模式
 * @param stringIndex 匹配的弦号（0-based），仅 STANDARD 模式有值
 */
@Composable
private fun NoteDisplay(
    noteName: String,
    hasValidPitch: Boolean,
    tuningDirection: TuningDirection,
    isInTune: Boolean,
    tuningMode: TuningMode,
    stringIndex: Int?,
    modifier: Modifier = Modifier
) {
    // 音名颜色随调音状态平滑过渡
    val noteColor by animateColorAsState(
        targetValue = when {
            !hasValidPitch -> TextSecondary.copy(alpha = 0.5f)
            isInTune -> Green500
            tuningDirection == TuningDirection.SHARP -> Red500
            tuningDirection == TuningDirection.FLAT -> Blue500
            else -> AccentGold
        },
        label = "note_color"
    )

    // 有效时显示检测到的音名（如 "A4"），否则显示 em dash
    val displayNoteName = if (hasValidPitch && noteName.isNotEmpty()) noteName else "—"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = DarkSurface.copy(alpha = 0.5f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 音名 — displayLarge 大字
            Text(
                text = displayNoteName,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 72.sp,
                    lineHeight = 80.sp
                ),
                color = noteColor,
                textAlign = TextAlign.Center
            )

            // 弦号 — 仅在 STANDARD 模式且有有效弦号时显示
            if (tuningMode == TuningMode.STANDARD && hasValidPitch && stringIndex != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "弦 ${stringIndex + 1}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 频率显示区域
 *
 * 有信号时显示频率值保留 1 位小数（如 "440.0 Hz"），无信号时显示 "--- Hz"。
 * 使用 [TextSecondary] 颜色，视觉权重低于音名。
 */
@Composable
private fun FrequencyDisplay(
    frequency: Double?,
    hasValidPitch: Boolean,
    modifier: Modifier = Modifier
) {
    val frequencyText = if (hasValidPitch && frequency != null) {
        "%.1f Hz".format(frequency)
    } else {
        "--- Hz"
    }

    Text(
        text = frequencyText,
        style = MaterialTheme.typography.titleLarge.copy(
            fontSize = 20.sp
        ),
        color = if (hasValidPitch) TextPrimary.copy(alpha = 0.7f) else TextSecondary.copy(alpha = 0.5f),
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * 弦序列行 — 横向排列当前乐器的弦音名
 *
 * 当前匹配弦以 [AccentGold] 高亮，其余弦使用 [TextSecondary]。
 * 弦号从 1 开始，对应 strings 列表的 0-based 索引。
 * 仅在 STANDARD 模式下由上层控制显示。
 */
@Composable
private fun StringSequenceRow(
    instrument: Instrument,
    activeStringIndex: Int?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 标题
        Text(
            text = "${instrument.name} 标准调弦",
            color = TextSecondary.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 弦音名圆形标签行
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            instrument.strings.forEachIndexed { index, note ->
                val isActive = index == activeStringIndex

                // 颜色动画
                val stringColor by animateColorAsState(
                    targetValue = if (isActive) AccentGold else TextSecondary,
                    label = "string_color_$index"
                )
                val bgColor by animateColorAsState(
                    targetValue = if (isActive) AccentGold.copy(alpha = 0.18f)
                    else Color.Transparent,
                    label = "string_bg_$index"
                )
                val borderColor by animateColorAsState(
                    targetValue = if (isActive) AccentGold.copy(alpha = 0.6f)
                    else TextSecondary.copy(alpha = 0.25f),
                    label = "string_border_$index"
                )

                Box(
                    modifier = Modifier
                        .padding(horizontal = 5.dp)
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(bgColor)
                        .border(
                            width = if (isActive) 2.dp else 1.dp,
                            color = borderColor,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = note.fullName,
                        color = stringColor,
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 状态提示文本
 *
 * 根据当前调音状态给出可操作的文字提示：
 * - 无信号 → "未检测到稳定音高"
 * - 准确   → "音准正确！"
 * - 偏高   → "音高偏高，请松弦"
 * - 偏低   → "音高偏低，请紧弦"
 */
@Composable
private fun StatusHint(
    hasValidPitch: Boolean,
    tuningDirection: TuningDirection,
    isInTune: Boolean,
    modifier: Modifier = Modifier
) {
    val statusText = when {
        !hasValidPitch -> "未检测到稳定音高"
        isInTune -> "音准正确！"
        tuningDirection == TuningDirection.SHARP -> "音高偏高，请松弦"
        tuningDirection == TuningDirection.FLAT -> "音高偏低，请紧弦"
        else -> "检测中..."
    }

    val statusColor = when {
        !hasValidPitch -> TextSecondary.copy(alpha = 0.7f)
        isInTune -> Green500.copy(alpha = 0.9f)
        tuningDirection == TuningDirection.SHARP -> Red500.copy(alpha = 0.8f)
        tuningDirection == TuningDirection.FLAT -> Blue500.copy(alpha = 0.8f)
        else -> TextSecondary.copy(alpha = 0.7f)
    }

    Text(
        text = statusText,
        color = statusColor,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth()
    )
}

// ============================================================
// 预览
// ============================================================

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E, showSystemUi = true)
@Composable
private fun TunerScreenPreviewNoSignal() {
    GuitarTunerTheme {
        TunerScreen(
            uiState = TunerUiState(),
            onInstrumentSelected = {},
            onTuningModeSelected = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E, showSystemUi = true)
@Composable
private fun TunerScreenPreviewInTuneChromaticMode() {
    GuitarTunerTheme {
        TunerScreen(
            uiState = TunerUiState(
                isListening = true,
                hasValidPitch = true,
                noteName = "A4",
                detectedFrequency = 440.0,
                centDeviation = 2f,
                isInTune = true,
                tuningDirection = TuningDirection.IN_TUNE,
                stringIndex = null,
                tuningMode = TuningMode.CHROMATIC,
                confidence = 0.92
            ),
            onInstrumentSelected = {},
            onTuningModeSelected = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E, showSystemUi = true)
@Composable
private fun TunerScreenPreviewSharpStandardMode() {
    GuitarTunerTheme {
        TunerScreen(
            uiState = TunerUiState(
                isListening = true,
                hasValidPitch = true,
                noteName = "E4",
                detectedFrequency = 335.2,
                centDeviation = 18f,
                isInTune = false,
                tuningDirection = TuningDirection.SHARP,
                stringIndex = 5,
                currentInstrument = InstrumentPresets.guitar,
                tuningMode = TuningMode.STANDARD,
                confidence = 0.85
            ),
            onInstrumentSelected = {},
            onTuningModeSelected = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E, showSystemUi = true)
@Composable
private fun TunerScreenPreviewFlatStandardMode() {
    GuitarTunerTheme {
        TunerScreen(
            uiState = TunerUiState(
                isListening = true,
                hasValidPitch = true,
                noteName = "E2",
                detectedFrequency = 80.5,
                centDeviation = -25f,
                isInTune = false,
                tuningDirection = TuningDirection.FLAT,
                stringIndex = 0,
                currentInstrument = InstrumentPresets.guitar,
                tuningMode = TuningMode.STANDARD,
                confidence = 0.78
            ),
            onInstrumentSelected = {},
            onTuningModeSelected = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E, showSystemUi = true)
@Composable
private fun TunerScreenPreviewUkulele() {
    GuitarTunerTheme {
        TunerScreen(
            uiState = TunerUiState(
                isListening = true,
                hasValidPitch = true,
                noteName = "C4",
                detectedFrequency = 261.6,
                centDeviation = -8f,
                isInTune = false,
                tuningDirection = TuningDirection.FLAT,
                stringIndex = 1,
                currentInstrument = InstrumentPresets.ukulele,
                tuningMode = TuningMode.STANDARD,
                confidence = 0.88
            ),
            onInstrumentSelected = {},
            onTuningModeSelected = {}
        )
    }
}
