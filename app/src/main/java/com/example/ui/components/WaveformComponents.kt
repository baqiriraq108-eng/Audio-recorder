package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AudioFormatUtils
import com.example.data.RecordingStatus
import com.example.data.SilenceInterval
import com.example.ui.theme.DestructiveCrimson
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.PeachAccent
import com.example.ui.theme.PrimaryText
import com.example.ui.theme.SecondaryText
import com.example.ui.theme.SuccessSage
import com.example.ui.theme.TabularMonoStyle
import com.example.ui.theme.WarmSecondarySurface
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.WaveformActiveBar
import com.example.ui.theme.WaveformInactiveBar
import com.example.ui.theme.NavPillIndicator
import com.example.ui.theme.PeachAccentSoft
import com.example.ui.theme.WaveformPlayhead
import kotlin.math.pow

@Composable
fun AnimatedStatusBadge(
    status: RecordingStatus,
    isAppendMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val dotColor by animateColorAsState(
        targetValue = when (status) {
            RecordingStatus.READY -> PeachAccent
            RecordingStatus.RECORDING -> WaveformPlayhead
            RecordingStatus.PAUSED -> SecondaryText
            RecordingStatus.PROCESSING, RecordingStatus.SAVING -> WaveformPlayhead
            RecordingStatus.ERROR -> PeachAccent
        },
        animationSpec = tween(250),
        label = "badge_dot_color"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(ElevatedSurface)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("recording_status_badge")
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isAppendMode && status == RecordingStatus.RECORDING) {
                "Appending"
            } else {
                status.label
            },
            style = MaterialTheme.typography.labelMedium,
            color = PrimaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StudioInteractiveWaveform(
    peaks: List<Float>,
    isRecording: Boolean,
    elapsedMs: Long,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    liveDb: Float,
    skipSilenceEnabled: Boolean,
    silenceThresholdDb: Float,
    isSilenceJumping: Boolean,
    silenceIntervals: List<SilenceInterval> = emptyList(),
    appendOriginalDurationMs: Long? = null,
    isTrimMode: Boolean = false,
    trimStartFraction: Float = 0f,
    trimEndFraction: Float = 1f,
    onTrimRangeChange: ((Float, Float) -> Unit)? = null,
    onSeekToMs: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalDur = if (isRecording) {
        elapsedMs + (appendOriginalDurationMs ?: 0L)
    } else {
        playbackDurationMs.coerceAtLeast(1L)
    }
    val rawProgress = if (isRecording) {
        1f
    } else {
        (playbackPositionMs.toFloat() / totalDur.toFloat()).coerceIn(0f, 1f)
    }

    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(durationMillis = if (isSilenceJumping) 60 else 30),
        label = "waveform_cursor_progress"
    )

    val peakThreshold = AudioFormatUtils.dbToNormalizedPeak(silenceThresholdDb).coerceIn(0.05f, 0.65f)
    val currentTrimStart by androidx.compose.runtime.rememberUpdatedState(trimStartFraction.coerceIn(0f, 0.96f))
    val currentTrimEnd by androidx.compose.runtime.rememberUpdatedState(trimEndFraction.coerceIn(0.04f, 1f))
    val currentTrimCallback by androidx.compose.runtime.rememberUpdatedState(onTrimRangeChange)

    // Borderless rounded container (16 dp corner radius) using Surface Container #221B17
    // Shows exclusively the waveform bars, primary vertical playhead cursor, and optional trim handles inside the card.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WarmSecondarySurface)
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .graphicsLayer { clip = false }
    ) {
        // Seamless borderless waveform viewport merging directly into Surface Container (#221B17)
        // Unclipped so the circular thumb handle (9dp outer radius) remains 100% visible at 0% and 100% edges
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(136.dp)
                .background(WarmSecondarySurface)
                .graphicsLayer { clip = false }
                .testTag("waveform_canvas_viewport")
                .pointerInput(isRecording, totalDur, isTrimMode) {
                    if (!isRecording && totalDur > 0L) {
                        detectTapGestures { offset ->
                            val thumbInsetPx = 12.dp.toPx()
                            val usableTrackWidth = (size.width.toFloat() - thumbInsetPx * 2f).coerceAtLeast(1f)
                            val fraction = ((offset.x - thumbInsetPx) / usableTrackWidth).coerceIn(0f, 1f)
                            if (isTrimMode && currentTrimCallback != null) {
                                val handleTolerance = (24.dp.toPx() / usableTrackWidth).coerceIn(0.04f, 0.12f)
                                val distToStart = kotlin.math.abs(fraction - currentTrimStart)
                                val distToEnd = kotlin.math.abs(fraction - currentTrimEnd)
                                if (distToStart <= handleTolerance || distToEnd <= handleTolerance) {
                                    if (distToStart <= distToEnd) {
                                        val newStart = fraction.coerceIn(0f, (currentTrimEnd - 0.05f).coerceAtLeast(0f))
                                        currentTrimCallback?.invoke(newStart, currentTrimEnd)
                                        onSeekToMs((newStart * totalDur).toLong())
                                    } else {
                                        val newEnd = fraction.coerceIn((currentTrimStart + 0.05f).coerceAtMost(1f), 1f)
                                        currentTrimCallback?.invoke(currentTrimStart, newEnd)
                                    }
                                } else {
                                    // User tapped waveform in trim mode: clamp cursor strictly between trim boundaries
                                    val clampedFrac = fraction.coerceIn(currentTrimStart, currentTrimEnd)
                                    onSeekToMs((clampedFrac * totalDur).toLong())
                                }
                            } else {
                                onSeekToMs((fraction * totalDur).toLong())
                            }
                        }
                    }
                }
                .pointerInput(isRecording, totalDur, isTrimMode) {
                    if (!isRecording && totalDur > 0L) {
                        var dragMode = 0 // 0 = playhead cursor, 1 = start handle, 2 = end handle
                        detectHorizontalDragGestures(
                            onDragStart = { startOffset ->
                                val thumbInsetPx = 12.dp.toPx()
                                val usableTrackWidth = (size.width.toFloat() - thumbInsetPx * 2f).coerceAtLeast(1f)
                                val fraction = ((startOffset.x - thumbInsetPx) / usableTrackWidth).coerceIn(0f, 1f)
                                if (isTrimMode && currentTrimCallback != null) {
                                    val handleTolerance = (24.dp.toPx() / usableTrackWidth).coerceIn(0.04f, 0.12f)
                                    val distStart = kotlin.math.abs(fraction - currentTrimStart)
                                    val distEnd = kotlin.math.abs(fraction - currentTrimEnd)
                                    dragMode = when {
                                        distStart <= handleTolerance && distStart <= distEnd -> 1
                                        distEnd <= handleTolerance -> 2
                                        else -> 0
                                    }
                                } else {
                                    dragMode = 0
                                }
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val thumbInsetPx = 12.dp.toPx()
                                val usableTrackWidth = (size.width.toFloat() - thumbInsetPx * 2f).coerceAtLeast(1f)
                                val fraction = ((change.position.x - thumbInsetPx) / usableTrackWidth).coerceIn(0f, 1f)
                                if (isTrimMode && currentTrimCallback != null) {
                                    when (dragMode) {
                                        1 -> {
                                            val newStart = fraction.coerceIn(0f, (currentTrimEnd - 0.04f).coerceAtLeast(0f))
                                            currentTrimCallback?.invoke(newStart, currentTrimEnd)
                                            onSeekToMs((newStart * totalDur).toLong())
                                        }
                                        2 -> {
                                            val newEnd = fraction.coerceIn((currentTrimStart + 0.04f).coerceAtMost(1f), 1f)
                                            currentTrimCallback?.invoke(currentTrimStart, newEnd)
                                        }
                                        else -> {
                                            // Scrubbing playhead cursor strictly bounded between currentTrimStart and currentTrimEnd
                                            val clampedFrac = fraction.coerceIn(currentTrimStart, currentTrimEnd)
                                            onSeekToMs((clampedFrac * totalDur).toLong())
                                        }
                                    }
                                } else {
                                    onSeekToMs((fraction * totalDur).toLong())
                                }
                            }
                        )
                    }
                }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 10.dp)
                    .graphicsLayer { clip = false }
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val centerY = canvasHeight / 2f

                // Reserve full horizontal clearance (12dp >= 9dp outer thumb radius) at both start and end edges
                val horizontalThumbInset = 12.dp.toPx()
                val availableTrackWidth = (canvasWidth - horizontalThumbInset * 2f).coerceAtLeast(1f)
                val barWidth = 3.dp.toPx()
                val barSpacing = 2.dp.toPx()
                val step = barWidth + barSpacing
                val barCount = (availableTrackWidth / step).toInt().coerceIn(20, 96)
                val totalOccupiedWidth = (barCount * barWidth + (barCount - 1) * barSpacing).coerceAtLeast(1f)
                val startOffsetX = ((canvasWidth - totalOccupiedWidth) / 2f).coerceAtLeast(horizontalThumbInset)
                val trackEndX = startOffsetX + totalOccupiedWidth

                // Subtle center baseline guide in muted bar color (#382B24)
                drawLine(
                    color = WaveformInactiveBar,
                    start = Offset(startOffsetX, centerY),
                    end = Offset(trackEndX, centerY),
                    strokeWidth = 1.dp.toPx()
                )

                // Silence threshold guide lines when Skip Silence is enabled
                if (skipSilenceEnabled) {
                    val threshOffset = (peakThreshold * (canvasHeight * 0.44f))
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    drawLine(
                        color = WaveformPlayhead.copy(alpha = 0.35f),
                        start = Offset(startOffsetX, centerY - threshOffset),
                        end = Offset(trackEndX, centerY - threshOffset),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashEffect
                    )
                    drawLine(
                        color = WaveformPlayhead.copy(alpha = 0.35f),
                        start = Offset(startOffsetX, centerY + threshOffset),
                        end = Offset(trackEndX, centerY + threshOffset),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashEffect
                    )
                }

                val cursorX = (startOffsetX + animatedProgress * totalOccupiedWidth)
                    .coerceIn(startOffsetX, trackEndX)

                val trimStartX = (startOffsetX + currentTrimStart * totalOccupiedWidth)
                    .coerceIn(startOffsetX, trackEndX)
                val trimEndX = (startOffsetX + currentTrimEnd * totalOccupiedWidth)
                    .coerceIn(trimStartX, trackEndX)

                for (i in 0 until barCount) {
                    val hasRecordedData = if (isRecording) {
                        i < peaks.size
                    } else {
                        peaks.isNotEmpty()
                    }
                    val rawAmp = if (!hasRecordedData) {
                        0.06f
                    } else if (isRecording) {
                        val srcIdx = (peaks.size - barCount + i)
                        if (srcIdx in peaks.indices) peaks[srcIdx] else 0.06f
                    } else {
                        val srcIdx = ((i.toFloat() / barCount) * peaks.size).toInt().coerceIn(0, peaks.lastIndex)
                        peaks[srcIdx]
                    }

                    val amplitude = rawAmp.coerceIn(0.05f, 1.0f)
                    val barHeight = (amplitude * canvasHeight * 0.88f).coerceAtLeast(4.dp.toPx())
                    val x = startOffsetX + i * step
                    val topY = centerY - barHeight / 2f

                    val barCenter = x + barWidth * 0.5f
                    val isOutsideTrim = isTrimMode && (barCenter < trimStartX || barCenter > trimEndX)
                    val isPlayed = barCenter <= cursorX
                    val barStartMs = ((i.toFloat() / barCount.toFloat()) * totalDur.toFloat()).toLong()
                    val barEndMs = (((i + 1).toFloat() / barCount.toFloat()) * totalDur.toFloat()).toLong()
                    val barMidMs = (barStartMs + barEndMs) / 2L
                    val isSilentSegment = when {
                        isRecording -> amplitude <= peakThreshold * 0.65f
                        !skipSilenceEnabled -> false
                        else -> silenceIntervals.any { interval ->
                            barMidMs in interval.startMs..interval.endMs
                        }
                    }

                    // Active/recorded bars: #8A4A18 (WaveformActiveBar), Silent/Inactive slots: #382B24 (WaveformInactiveBar)
                    val barColor = when {
                        !hasRecordedData -> WaveformInactiveBar
                        isOutsideTrim -> WaveformInactiveBar.copy(alpha = 0.35f)
                        isSilentSegment -> WaveformInactiveBar.copy(alpha = 0.55f)
                        isRecording -> WaveformActiveBar
                        isPlayed -> WaveformPlayhead
                        else -> WaveformActiveBar.copy(alpha = 0.72f)
                    }

                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, topY),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }

                // Interactive Trim Range Overlay & Draggable Start/End Handles
                if (isTrimMode && !isRecording) {
                    // Dim excluded left & right regions
                    if (trimStartX > startOffsetX) {
                        drawRect(
                            color = ObsidianBackground.copy(alpha = 0.58f),
                            topLeft = Offset(0f, 0f),
                            size = Size(trimStartX, canvasHeight)
                        )
                    }
                    if (trimEndX < canvasWidth) {
                        drawRect(
                            color = ObsidianBackground.copy(alpha = 0.58f),
                            topLeft = Offset(trimEndX, 0f),
                            size = Size(canvasWidth - trimEndX, canvasHeight)
                        )
                    }

                    // Subtle kept-region highlight frame
                    val keptWidth = (trimEndX - trimStartX).coerceAtLeast(2.dp.toPx())
                    drawRect(
                        color = PeachAccent.copy(alpha = 0.09f),
                        topLeft = Offset(trimStartX, 0f),
                        size = Size(keptWidth, canvasHeight)
                    )
                    drawLine(
                        color = PeachAccent.copy(alpha = 0.75f),
                        start = Offset(trimStartX, 0f),
                        end = Offset(trimEndX, 0f),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawLine(
                        color = PeachAccent.copy(alpha = 0.75f),
                        start = Offset(trimStartX, canvasHeight),
                        end = Offset(trimEndX, canvasHeight),
                        strokeWidth = 2.dp.toPx()
                    )

                    // Start Handle Bar + Pill Grip
                    val handleStroke = 3.dp.toPx()
                    val gripW = 10.dp.toPx()
                    val gripH = 28.dp.toPx()
                    drawLine(
                        color = PeachAccent,
                        start = Offset(trimStartX, 0f),
                        end = Offset(trimStartX, canvasHeight),
                        strokeWidth = handleStroke
                    )
                    drawRoundRect(
                        color = PeachAccent,
                        topLeft = Offset(trimStartX - gripW / 2f, centerY - gripH / 2f),
                        size = Size(gripW, gripH),
                        cornerRadius = CornerRadius(gripW / 2f, gripW / 2f)
                    )

                    // End Handle Bar + Pill Grip
                    drawLine(
                        color = PeachAccent,
                        start = Offset(trimEndX, 0f),
                        end = Offset(trimEndX, canvasHeight),
                        strokeWidth = handleStroke
                    )
                    drawRoundRect(
                        color = PeachAccent,
                        topLeft = Offset(trimEndX - gripW / 2f, centerY - gripH / 2f),
                        size = Size(gripW, gripH),
                        cornerRadius = CornerRadius(gripW / 2f, gripW / 2f)
                    )
                }

                // Primary Playhead / Scrubbing Cursor & Knob
                val activeCursorX = if (isRecording) trackEndX else cursorX
                drawLine(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            PeachAccentSoft.copy(alpha = 0.25f),
                            PeachAccentSoft,
                            PeachAccentSoft.copy(alpha = 0.25f)
                        )
                    ),
                    start = Offset(activeCursorX, 0f),
                    end = Offset(activeCursorX, canvasHeight),
                    strokeWidth = 2.5.dp.toPx()
                )
                // Enhanced, larger playhead handle/knob (7.5dp radius) in warm ivory PeachAccentSoft with subtle border
                val playheadRadius = 7.5.dp.toPx()
                // Outer subtle dark contour for sharp contrast over both light and dark bars
                drawCircle(
                    color = ObsidianBackground,
                    radius = playheadRadius + 1.5.dp.toPx(),
                    center = Offset(activeCursorX, centerY)
                )
                // Main playhead knob in distinct warm ivory tone (#F4D7C7)
                drawCircle(
                    color = PeachAccentSoft,
                    radius = playheadRadius,
                    center = Offset(activeCursorX, centerY)
                )
                // Center accent core in NavPillIndicator
                drawCircle(
                    color = NavPillIndicator,
                    radius = 3.dp.toPx(),
                    center = Offset(activeCursorX, centerY)
                )
            }
        }
    }
}

@Composable
fun MiniWaveformThumbnail(
    peaks: List<Float>,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(width = 56.dp, height = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) ElevatedSurface else ObsidianBackground)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bars = 14
            val samplePeaks = if (peaks.isEmpty()) {
                List(bars) { idx -> 0.25f + 0.4f * kotlin.math.sin(idx * 0.7f).let { kotlin.math.abs(it) } }
            } else {
                List(bars) { idx ->
                    val src = ((idx.toFloat() / bars) * peaks.size).toInt().coerceIn(0, peaks.lastIndex)
                    peaks[src]
                }
            }
            val slot = size.width / bars
            val barW = (slot * 0.58f).coerceAtLeast(1.8.dp.toPx())
            val midY = size.height / 2f
            for (i in 0 until bars) {
                val h = (samplePeaks[i].coerceIn(0.12f, 1.0f) * size.height * 0.85f).coerceAtLeast(3.dp.toPx())
                val x = i * slot + (slot - barW) / 2f
                drawRoundRect(
                    color = if (isActive) WaveformPlayhead else WaveformActiveBar.copy(alpha = 0.75f),
                    topLeft = Offset(x, midY - h / 2f),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                )
            }
        }
    }
}
