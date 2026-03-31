package io.github.oldfriendwenjianjian.guitartuner.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.oldfriendwenjianjian.guitartuner.ui.theme.AccentGold
import io.github.oldfriendwenjianjian.guitartuner.ui.theme.Blue500
import io.github.oldfriendwenjianjian.guitartuner.ui.theme.DarkBackground
import io.github.oldfriendwenjianjian.guitartuner.ui.theme.DarkSurface
import io.github.oldfriendwenjianjian.guitartuner.ui.theme.Green500
import io.github.oldfriendwenjianjian.guitartuner.ui.theme.Red500
import io.github.oldfriendwenjianjian.guitartuner.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================
// 公开 API
// ============================================================

/**
 * TuningMeter — 仪表盘风格的调音偏差可视化组件。
 *
 * 半圆弧仪表盘，范围 -50 ~ +50 cent，带有平滑动画指针和颜色区分：
 * - 准确 (|偏差| <= 5 cent) → 绿色 [Green500]
 * - 偏高 (偏差 > 5 cent)   → 红色 [Red500]
 * - 偏低 (偏差 < -5 cent)  → 蓝色 [Blue500]
 * - 无信号                  → 灰色 [TextSecondary]，指针居中
 *
 * @param centDeviation 偏差值，范围 -50 到 +50 cent；超出范围会被 clamp
 * @param isActive      是否有有效音频信号输入
 * @param modifier      外部 Modifier
 */
@Composable
fun TuningMeter(
    centDeviation: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    // 防御 NaN / Infinity，然后 clamp 到合法范围
    val clampedDeviation = if (centDeviation.isNaN() || centDeviation.isInfinite()) {
        0f
    } else {
        centDeviation.coerceIn(CENT_MIN, CENT_MAX)
    }

    // ---- 动画 ----
    // 指针位置：spring 弹簧动画实现平滑移动（含轻微过冲）
    val animatedDeviation by animateFloatAsState(
        targetValue = if (isActive) clampedDeviation else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "needle_deviation"
    )

    // 指针/数值颜色：平滑过渡
    val accentColor by animateColorAsState(
        targetValue = when {
            !isActive -> TextSecondary
            abs(clampedDeviation) <= IN_TUNE_THRESHOLD -> Green500
            clampedDeviation > IN_TUNE_THRESHOLD -> Red500
            else -> Blue500
        },
        label = "accent_color"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ---------- 仪表盘绘制 ----------
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(GAUGE_ASPECT_RATIO)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val centerX = size.width / 2f
            val centerY = size.height * CENTER_Y_RATIO

            // 弧半径：取宽度与高度中较小的约束，确保不溢出画布
            val arcRadius = (size.width * 0.42f).coerceAtMost(size.height * 0.85f)

            // 1) 背景弧线（灰色轨道）
            drawGaugeArc(centerX, centerY, arcRadius, DarkSurface, strokeWidth = 12f)

            // 2) 刻度线
            drawTicks(
                centerX = centerX,
                centerY = centerY,
                radius = arcRadius,
                color = if (isActive) TextSecondary else TextSecondary.copy(alpha = 0.35f)
            )

            // 3) 中心 0-cent 标记
            drawCenterMark(
                centerX = centerX,
                centerY = centerY,
                radius = arcRadius,
                color = if (isActive) AccentGold else TextSecondary.copy(alpha = 0.45f)
            )

            // 4) 偏差弧段（从中心向偏差方向的高亮弧）
            if (isActive && abs(animatedDeviation) > 0.5f) {
                drawDeviationArc(
                    centerX = centerX,
                    centerY = centerY,
                    radius = arcRadius,
                    deviation = animatedDeviation,
                    color = accentColor,
                    strokeWidth = 6f
                )
            }

            // 5) 指针
            drawNeedle(centerX, centerY, arcRadius, animatedDeviation, accentColor)

            // 6) 枢轴圆点
            drawCircle(color = accentColor, radius = 8f, center = Offset(centerX, centerY))
            drawCircle(color = DarkBackground, radius = 4f, center = Offset(centerX, centerY))
        }

        // ---------- 偏差数值 ----------
        val displayCent = if (isActive) clampedDeviation.roundToInt() else 0
        val deviationText = when {
            !isActive -> "-- cents"
            displayCent == 0 -> "0 cents"
            displayCent > 0 -> "+$displayCent cents"
            else -> "$displayCent cents"
        }

        Text(
            text = deviationText,
            color = accentColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp)
        )

        // ---------- 状态提示 ----------
        val statusText = when {
            !isActive -> "等待信号..."
            abs(clampedDeviation) <= IN_TUNE_THRESHOLD -> "准确！"
            clampedDeviation > 0f -> "偏高 ↑"
            else -> "偏低 ↓"
        }

        Text(
            text = statusText,
            color = if (isActive) accentColor.copy(alpha = 0.7f) else TextSecondary.copy(alpha = 0.5f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

// ============================================================
// 常量
// ============================================================

/** cent 偏差最小值 */
private const val CENT_MIN = -50f

/** cent 偏差最大值 */
private const val CENT_MAX = 50f

/** "准确"阈值（|偏差| <= 此值视为准确） */
private const val IN_TUNE_THRESHOLD = 5f

/** 仪表盘 Canvas 宽高比 (width / height) */
private const val GAUGE_ASPECT_RATIO = 1.8f

/** 弧心 Y 坐标占 Canvas 高度的比例（靠下方，留出弧线向上延伸空间） */
private const val CENTER_Y_RATIO = 0.88f

/** 指针长度占弧半径的比例 */
private const val NEEDLE_LENGTH_RATIO = 0.80f

// ============================================================
// 角度映射
// ============================================================

/**
 * 将 cent 偏差映射到 Canvas 角度（度）。
 *
 * Canvas 坐标系: 0° = 3 点钟方向，顺时针为正。
 * 映射关系:
 * - -50 cent → 180° (左/9 点钟)
 * -   0 cent → 270° (上/12 点钟)
 * - +50 cent → 360° (右/3 点钟)
 */
private fun deviationToAngleDeg(deviation: Float): Float {
    return 180f + ((deviation - CENT_MIN) / (CENT_MAX - CENT_MIN)) * 180f
}

/** 角度(度) → 弧度 */
private fun Float.toRadians(): Float = (this * PI / 180.0).toFloat()

// ============================================================
// Canvas 绘制辅助
// ============================================================

/**
 * 绘制仪表盘背景弧线（半圆，180° → 360°）。
 */
private fun DrawScope.drawGaugeArc(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color,
    strokeWidth: Float
) {
    drawArc(
        color = color,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(centerX - radius, centerY - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
}

/**
 * 绘制刻度线。
 *
 * 主刻度（每 10 cent）较长且较粗；副刻度（每 5 cent）较短且较细。
 * 0 cent 刻度由 [drawCenterMark] 单独处理，此处跳过。
 */
private fun DrawScope.drawTicks(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color
) {
    for (cent in -50..50 step 5) {
        // 0 cent 位置由专门的中心标记绘制，刻度线跳过
        if (cent == 0) continue

        val isMajor = cent % 10 == 0
        val angleRad = deviationToAngleDeg(cent.toFloat()).toRadians()

        // 刻度线从弧线外侧向内延伸
        val outerRadius = radius + 2f
        val tickLength = if (isMajor) 16f else 10f
        val innerRadius = outerRadius - tickLength

        val outerX = centerX + outerRadius * cos(angleRad)
        val outerY = centerY + outerRadius * sin(angleRad)
        val innerX = centerX + innerRadius * cos(angleRad)
        val innerY = centerY + innerRadius * sin(angleRad)

        drawLine(
            color = color.copy(alpha = if (isMajor) 1f else 0.6f),
            start = Offset(innerX, innerY),
            end = Offset(outerX, outerY),
            strokeWidth = if (isMajor) 2.5f else 1.5f,
            cap = StrokeCap.Round
        )
    }
}

/**
 * 绘制中心标记（0 cent 位置，弧线正上方的高亮圆点）。
 */
private fun DrawScope.drawCenterMark(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color
) {
    val angleRad = deviationToAngleDeg(0f).toRadians() // 270°（正上方）
    val markRadius = radius + 10f
    val markX = centerX + markRadius * cos(angleRad)
    val markY = centerY + markRadius * sin(angleRad)

    drawCircle(
        color = color,
        radius = 5f,
        center = Offset(markX, markY)
    )
}

/**
 * 绘制偏差高亮弧段（从 0-cent 中心向偏差方向延伸的彩色弧线）。
 *
 * sweep 方向：正偏差顺时针（向右），负偏差逆时针（向左），
 * 与指针运动方向一致，提供直观的"偏了多少"视觉反馈。
 */
private fun DrawScope.drawDeviationArc(
    centerX: Float,
    centerY: Float,
    radius: Float,
    deviation: Float,
    color: Color,
    strokeWidth: Float
) {
    // sweepAngle: 偏差映射到 ±90° 范围 (50 cent = 90°)
    val sweepAngle = (deviation / CENT_MAX) * 90f

    drawArc(
        color = color.copy(alpha = 0.4f),
        startAngle = 270f, // 从正上方（0 cent）开始
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = Offset(centerX - radius, centerY - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
}

/**
 * 绘制指针（从枢轴到偏差位置的线段 + 尖端圆点）。
 */
private fun DrawScope.drawNeedle(
    centerX: Float,
    centerY: Float,
    radius: Float,
    deviation: Float,
    color: Color
) {
    val angleRad = deviationToAngleDeg(deviation).toRadians()
    val needleLen = radius * NEEDLE_LENGTH_RATIO

    val tipX = centerX + needleLen * cos(angleRad)
    val tipY = centerY + needleLen * sin(angleRad)

    // 主指针线
    drawLine(
        color = color,
        start = Offset(centerX, centerY),
        end = Offset(tipX, tipY),
        strokeWidth = 4f,
        cap = StrokeCap.Round
    )

    // 尖端圆点
    drawCircle(
        color = color,
        radius = 5f,
        center = Offset(tipX, tipY)
    )
}

// ============================================================
// 预览
// ============================================================

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun TuningMeterPreviewInTune() {
    TuningMeter(centDeviation = 0f, isActive = true)
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun TuningMeterPreviewSharp() {
    TuningMeter(centDeviation = 25f, isActive = true)
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun TuningMeterPreviewFlat() {
    TuningMeter(centDeviation = -30f, isActive = true)
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun TuningMeterPreviewInactive() {
    TuningMeter(centDeviation = 0f, isActive = false)
}
