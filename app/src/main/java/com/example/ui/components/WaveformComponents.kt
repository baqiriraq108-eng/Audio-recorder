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
            RecordingStatus.READY -> SuccessSage
            RecordingStatus.RECORDING -> WaveformPlayhead
            RecordingStatus.PAUSED -> WarningAmber
            RecordingStatus.PROCESSING, RecordingStatus.SAVING -> WaveformPlayhead
            RecordingStatus.ERROR -> DestructiveCrimson
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
            .padding(16.dp)
    ) {
        // Seamless borderless waveform viewport merging directly into Surface Container (#221B17)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(136.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(WarmSecondarySurface)
                .testTag("waveform_canvas_viewport")
                .pointerInput(isRecording, totalDur, isTrimMode) {
                    if (!isRecording && totalDur > 0L) {
                        detectTapGestures { offset ->
                            val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            if (isTrimMode && currentTrimCallback != null) {
                                val distToStart = kotlin.math.abs(fraction - currentTrimStart)
                                val distToEnd = kotlin.math.abs(fraction - currentTrimEnd)
                                if (distToStart <= distToEnd) {
                                    val newStart = fraction.coerceIn(0f, (currentTrimEnd - 0.05f).coerceAtLeast(0f))
                                    currentTrimCallback?.invoke(newStart, currentTrimEnd)
                                    onSeekToMs((newStart * totalDur).toLong())
                                } else {
                                    val newEnd = fraction.coerceIn((currentTrimStart + 0.05f).coerceAtMost(1f), 1f)
                                    currentTrimCallback?.invoke(currentTrimStart, newEnd)
                                }
                            } else {
                                onSeekToMs((fraction * totalDur).toLong())
                            }
                        }
                    }
                }
                .pointerInput(isRecording, totalDur, isTrimMode) {
                    if (!isRecording && totalDur > 0L) {
                        var draggingStartHandle = true
                        detectHorizontalDragGestures(
                            onDragStart = { startOffset ->
                                val fraction = (startOffset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                val distStart = kotlin.math.abs(fraction - currentTrimStart)
                                val distEnd = kotlin.math.abs(fraction - currentTrimEnd)
                                draggingStartHandle = distStart <= distEnd
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                if (isTrimMode && currentTrimCallback != null) {
                                    if (draggingStartHandle) {
                                        val newStart = fraction.coerceIn(0f, (currentTrimEnd - 0.04f).coerceAtLeast(0f))
                                        currentTrimCallback?.invoke(newStart, currentTrimEnd)
                                        onSeekToMs((newStart * totalDur).toLong())
                                    } else {
                                        val newEnd = fraction.coerceIn((currentTrimStart + 0.04f).coerceAtMost(1f), 1f)
                                        currentTrimCallback?.invoke(currentTrimStart, newEnd)
                                    }
                                } else {
                                    onSeekToMs((fraction * totalDur).toLong())
                                }
                            }
                        )
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 10.dp)) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val centerY = canvasHeight / 2f

                // Subtle center baseline guide in muted bar color (#382B24)
                drawLine(
                    color = WaveformInactiveBar,
                    start = Offset(0f, centerY),
                    end = Offset(canvasWidth, centerY),
                    strokeWidth = 1.dp.toPx()
                )

                // Silence threshold guide lines when Skip Silence is enabled
                if (skipSilenceEnabled) {
                    val threshOffset = (peakThreshold * (canvasHeight * 0.44f))
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    drawLine(
                        color = WaveformPlayhead.copy(alpha = 0.35f),
                        start = Offset(0f, centerY - threshOffset),
                        end = Offset(canvasWidth, centerY - threshOffset),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashEffect
                    )
                    drawLine(
                        color = WaveformPlayhead.copy(alpha = 0.35f),
                        start = Offset(0f, centerY + threshOffset),
                        end = Offset(canvasWidth, centerY + threshOffset),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashEffect
                    )
                }

                val barWidth = 3.dp.toPx()
                val barSpacing = 2.dp.toPx()
                val step = barWidth + barSpacing
                val barCount = (canvasWidth / step).toInt().coerceIn(24, 96)
                val totalOccupiedWidth = (barCount * barWidth + (barCount - 1) * barSpacing).coerceAtLeast(1f)
                val startOffsetX = ((canvasWidth - totalOccupiedWidth) / 2f).coerceAtLeast(0f)
                val cursorX = (startOffsetX + animatedProgress * totalOccupiedWidth)
                    .coerceIn(startOffsetX, startOffsetX + totalOccupiedWidth)

                val trimStartX = (startOffsetX + currentTrimStart * totalOccupiedWidth)
                    .coerceIn(startOffsetX, startOffsetX + totalOccupiedWidth)
                val trimEndX = (startOffsetX + currentTrimEnd * totalOccupiedWidth)
                    .coerceIn(trimStartX, startOffsetX + totalOccupiedWidth)

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

                // Primary Playhead / Scrubbing Cursor in #E38851
                val activeCursorX = if (isRecording) canvasWidth - 4.dp.toPx() else cursorX
                drawLine(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            WaveformPlayhead.copy(alpha = 0.2f),
                            WaveformPlayhead,
                            WaveformPlayhead.copy(alpha = 0.2f)
                        )
                    ),
                    start = Offset(activeCursorX, 0f),
                    end = Offset(activeCursorX, canvasHeight),
                    strokeWidth = 2.5.dp.toPx()
                )
                drawCircle(
                    color = WaveformPlayhead,
                    radius = 5.dp.toPx(),
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
