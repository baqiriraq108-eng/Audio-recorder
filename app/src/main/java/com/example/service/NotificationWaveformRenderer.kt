package com.example.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.sin

object NotificationWaveformRenderer {

    private const val DEFAULT_WIDTH = 640
    private const val DEFAULT_HEIGHT = 96
    private const val POINT_COUNT = 32

    /**
     * Renders a full-width smooth continuous oscillating audio waveform centered along
     * the horizontal midline (`centerY = height / 2f`) with cubic-Bezier curves,
     * studio coral-orange linear gradient (`#E38851` -> `#F08C4B` -> `#FFB485`),
     * and a subtle translucent coral glow (`35%` opacity tapering to `0%` toward the center baseline).
     */
    fun renderWaveformBitmap(
        livePeaks: List<Float>,
        isPaused: Boolean,
        fallbackSeed: Long = 0L,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val opacityFactor = if (isPaused) {
            RecordingForegroundService.PAUSED_WAVEFORM_OPACITY
        } else {
            RecordingForegroundService.ACTIVE_WAVEFORM_OPACITY
        }
        val targetAlpha = if (isPaused) {
            RecordingForegroundService.PAUSED_WAVEFORM_ALPHA
        } else {
            (255f * opacityFactor).toInt().coerceIn(0, 255)
        }

        val centerY = height / 2f
        val horizontalPadding = 6f
        val usableWidth = (width - horizontalPadding * 2f).coerceAtLeast(1f)
        val maxHalfHeight = (height * 0.42f).coerceAtLeast(8f)

        val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = RecordingForegroundService.WAVEFORM_CORAL_MID_COLOR
            alpha = (42f * opacityFactor).toInt().coerceIn(8, 255)
        }
        canvas.drawLine(horizontalPadding, centerY, width - horizontalPadding, centerY, baselinePaint)

        val validPeaks = livePeaks.filter { it.isFinite() && it >= 0f }
        val peakSize = validPeaks.size
        val xs = FloatArray(POINT_COUNT)
        val ys = FloatArray(POINT_COUNT)

        for (i in 0 until POINT_COUNT) {
            val t = i.toFloat() / (POINT_COUNT - 1).toFloat()
            val edgeWindow = (0.32f + 0.68f * sin(t * Math.PI).toFloat()).coerceIn(0.32f, 1.0f)
            val rawAmp = if (peakSize > 0) {
                val indexInPeaks = peakSize - POINT_COUNT + i
                if (indexInPeaks >= 0) {
                    validPeaks[indexInPeaks].coerceIn(0.14f, 1.0f)
                } else {
                    validPeaks[i % peakSize].coerceIn(0.14f, 1.0f)
                }
            } else {
                val phase = (i * 0.42f) + (fallbackSeed * 0.18f)
                val wave = ((sin(phase.toDouble()).toFloat() + 1f) / 2f) * 0.55f + 0.22f
                wave.coerceIn(0.18f, 0.85f)
            }
            val sign = if (i % 2 == 0) -1f else 1f
            val displacement = (sign * rawAmp * edgeWindow).coerceIn(-0.96f, 0.96f)
            xs[i] = horizontalPadding + t * usableWidth
            ys[i] = (centerY + displacement * maxHalfHeight).coerceIn(height * 0.06f, height * 0.94f)
        }

        val wavePath = Path()
        val glowPath = Path()
        wavePath.moveTo(xs[0], ys[0])
        glowPath.moveTo(xs[0], centerY)
        glowPath.lineTo(xs[0], ys[0])

        val tension = 0.24f
        for (i in 0 until POINT_COUNT - 1) {
            val x0 = if (i > 0) xs[i - 1] else xs[i]
            val y0 = if (i > 0) ys[i - 1] else ys[i]
            val x1 = xs[i]
            val y1 = ys[i]
            val x2 = xs[i + 1]
            val y2 = ys[i + 1]
            val x3 = if (i + 2 < POINT_COUNT) xs[i + 2] else x2
            val y3 = if (i + 2 < POINT_COUNT) ys[i + 2] else y2

            val cp1x = x1 + (x2 - x0) * tension
            val cp1y = (y1 + (y2 - y0) * tension).coerceIn(height * 0.04f, height * 0.96f)
            val cp2x = x2 - (x3 - x1) * tension
            val cp2y = (y2 - (y3 - y1) * tension).coerceIn(height * 0.04f, height * 0.96f)

            wavePath.cubicTo(cp1x, cp1y, cp2x, cp2y, x2, y2)
            glowPath.cubicTo(cp1x, cp1y, cp2x, cp2y, x2, y2)
        }

        glowPath.lineTo(xs[POINT_COUNT - 1], centerY)
        glowPath.close()

        val crestGlowAlpha = (RecordingForegroundService.WAVEFORM_GLOW_MAX_ALPHA * opacityFactor).toInt().coerceIn(0, 255)
        val crestGlowColor = (crestGlowAlpha shl 24) or (RecordingForegroundService.WAVEFORM_CORAL_MID_COLOR and 0x00FFFFFF)
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f,
                height * 0.06f,
                0f,
                height * 0.94f,
                intArrayOf(crestGlowColor, 0x00F08C4B, crestGlowColor),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(glowPath, glowPaint)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            shader = LinearGradient(
                horizontalPadding,
                0f,
                width - horizontalPadding,
                0f,
                intArrayOf(
                    RecordingForegroundService.WAVEFORM_CORAL_START_COLOR,
                    RecordingForegroundService.WAVEFORM_CORAL_MID_COLOR,
                    RecordingForegroundService.WAVEFORM_CORAL_END_COLOR
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            alpha = targetAlpha
        }
        canvas.drawPath(wavePath, strokePaint)

        return bitmap
    }
}
