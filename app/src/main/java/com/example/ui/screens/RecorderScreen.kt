package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.AudioFormatUtils
import com.example.data.RecorderSettings
import com.example.data.RecordingEntity
import com.example.data.RecordingStatus
import com.example.ui.components.AutoSizingActionButtonText
import com.example.ui.components.StudioInteractiveWaveform
import com.example.ui.components.UnifiedTrashConfirmDialog
import com.example.ui.theme.DestructiveCrimson
import com.example.ui.theme.DriveRecordFab
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.OnDriveRecordFab
import com.example.ui.theme.OnPeachAccent
import com.example.ui.theme.OutlineWarm
import com.example.ui.theme.PeachAccent
import com.example.ui.theme.PrimaryText
import com.example.ui.theme.SecondaryText
import com.example.ui.theme.TabularMonoStyle
import com.example.ui.theme.TonalDarkBrown
import com.example.ui.theme.WarmSecondarySurface
import com.example.ui.theme.m3PressScale
import java.util.Locale

/**
 * Custom vector icon for Skip Silence matching reference attachment image_d975db66.png:
 * - Two 5-bar audio waveforms (left and right)
 * - Exactly 4 horizontal dashes in the middle gap between them
 * - A compact arching arrow nestled in the valley between the two waveforms
 * - A complete, non-truncated open-stroke arrowhead with two rounded wings
 */
@Composable
fun SkipSilenceCustomIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(28.dp)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerY = h * 0.55f
        val stroke = (w * 0.045f).coerceAtLeast(1.3f)

        // Bar dimensions
        val barW = w * 0.044f
        val barGap = w * 0.025f
        val barStep = barW + barGap
        val clusterW = 4 * barStep + barW

        val leftStartX = w * 0.04f
        val rightEndX = w * 0.96f
        val rightStartX = rightEndX - clusterW

        val heights = floatArrayOf(0.15f, 0.29f, 0.46f, 0.29f, 0.15f)

        // 1. Left Waveform Cluster (5 rounded vertical bars)
        for (i in 0 until 5) {
            val barH = h * heights[i]
            val x = leftStartX + i * barStep
            val topY = centerY - barH / 2f
            drawRoundRect(
                color = tint,
                topLeft = Offset(x, topY),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
        val leftEndX = leftStartX + clusterW

        // 2. Right Waveform Cluster (5 rounded vertical bars)
        for (i in 0 until 5) {
            val barH = h * heights[i]
            val x = rightStartX + i * barStep
            val topY = centerY - barH / 2f
            drawRoundRect(
                color = tint,
                topLeft = Offset(x, topY),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }

        // 3. Middle 4 Dashes between left and right waveforms
        val dashMargin = w * 0.024f
        val dashRegionStart = leftEndX + dashMargin
        val dashRegionEnd = rightStartX - dashMargin
        val numDashes = 4
        val dashGap = w * 0.020f
        val dashRegionWidth = dashRegionEnd - dashRegionStart
        val dashW = ((dashRegionWidth - (numDashes - 1) * dashGap) / numDashes).coerceAtLeast(w * 0.03f)
        val dashH = barW * 0.72f

        for (d in 0 until numDashes) {
            val x = dashRegionStart + d * (dashW + dashGap)
            val topY = centerY - dashH / 2f
            drawRoundRect(
                color = tint,
                topLeft = Offset(x, topY),
                size = Size(dashW, dashH),
                cornerRadius = CornerRadius(dashH / 2f, dashH / 2f)
            )
        }

        // 4. Compact Arching Arrow nestled cleanly between the two waveforms
        val arcStartX = w * 0.31f
        val arcEndX = w * 0.67f
        val arcStartY = h * 0.33f
        val arcEndY = h * 0.33f
        val arcControlY = h * 0.14f

        val arcPath = Path().apply {
            moveTo(arcStartX, arcStartY)
            quadraticTo(
                w * 0.49f, arcControlY,
                arcEndX, arcEndY
            )
        }
        drawPath(
            path = arcPath,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Complete 2-wing open stroke arrowhead matching image_d975db66.png
        val barbLen = w * 0.095f
        val arrowHeadPath = Path().apply {
            moveTo(arcEndX - barbLen, arcEndY - barbLen * 0.08f) // Left wing
            lineTo(arcEndX, arcEndY)                            // Arrow tip
            lineTo(arcEndX + barbLen * 0.05f, arcEndY - barbLen) // Top wing
        }
        drawPath(
            path = arrowHeadPath,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

enum class ActiveMicIndicatorState {
    PHONE_MIC_ACTIVE,
    CONNECTING_BLUETOOTH,
    BLUETOOTH_ACTIVE,
    WIRED_HEADSET_ACTIVE
}

fun resolveActiveMicIndicatorState(
    activeAudioDeviceName: String,
    isConnectingBluetoothMic: Boolean
): ActiveMicIndicatorState {
    if (isConnectingBluetoothMic) return ActiveMicIndicatorState.CONNECTING_BLUETOOTH
    if (activeAudioDeviceName.contains("Bluetooth", ignoreCase = true) ||
        activeAudioDeviceName.contains("BLE", ignoreCase = true)
    ) {
        return ActiveMicIndicatorState.BLUETOOTH_ACTIVE
    }
    if (activeAudioDeviceName.contains("Wired", ignoreCase = true) ||
        activeAudioDeviceName.contains("USB", ignoreCase = true) ||
        activeAudioDeviceName.contains("External", ignoreCase = true)
    ) {
        return ActiveMicIndicatorState.WIRED_HEADSET_ACTIVE
    }
    return ActiveMicIndicatorState.PHONE_MIC_ACTIVE
}

/**
 * Minimalist icon-only active microphone indicator anchored at the trailing edge of the Top App Bar.
 * Locked to a fixed 40.dp circular tonal container so transitioning between Phone Mic, Connecting,
 * and Bluetooth Mic states incurs strictly zero layout shift.
 */
@Composable
private fun ActiveMicTopBarIndicator(
    state: ActiveMicIndicatorState,
    modifier: Modifier = Modifier
) {
    val isBluetoothVisual = state == ActiveMicIndicatorState.BLUETOOTH_ACTIVE ||
        state == ActiveMicIndicatorState.CONNECTING_BLUETOOTH

    val containerBg by animateColorAsState(
        targetValue = if (isBluetoothVisual) WarmSecondarySurface else ElevatedSurface,
        animationSpec = tween(durationMillis = 220),
        label = "active_mic_container_bg"
    )

    val iconTint by animateColorAsState(
        targetValue = when (state) {
            ActiveMicIndicatorState.CONNECTING_BLUETOOTH,
            ActiveMicIndicatorState.BLUETOOTH_ACTIVE -> PeachAccent
            ActiveMicIndicatorState.WIRED_HEADSET_ACTIVE -> PrimaryText
            ActiveMicIndicatorState.PHONE_MIC_ACTIVE -> SecondaryText
        },
        animationSpec = tween(durationMillis = 220),
        label = "active_mic_icon_tint"
    )

    val accessibilityLabel = when (state) {
        ActiveMicIndicatorState.CONNECTING_BLUETOOTH -> stringResource(R.string.mic_indicator_connecting_bluetooth)
        ActiveMicIndicatorState.BLUETOOTH_ACTIVE -> stringResource(R.string.mic_indicator_bluetooth)
        ActiveMicIndicatorState.WIRED_HEADSET_ACTIVE -> stringResource(R.string.mic_indicator_wired_headset)
        ActiveMicIndicatorState.PHONE_MIC_ACTIVE -> stringResource(R.string.mic_indicator_phone)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(containerBg)
            .semantics { contentDescription = accessibilityLabel }
            .testTag("recorder_active_mic_indicator")
    ) {
        Crossfade(
            targetState = state,
            animationSpec = tween(durationMillis = 200),
            label = "active_mic_icon_crossfade"
        ) { targetState ->
            when (targetState) {
                ActiveMicIndicatorState.CONNECTING_BLUETOOTH -> {
                    val pulseTransition = rememberInfiniteTransition(label = "bt_connecting_pulse")
                    val pulseAlpha by pulseTransition.animateFloat(
                        initialValue = 1.0f,
                        targetValue = 0.35f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "bt_connecting_alpha"
                    )
                    val pulseScale by pulseTransition.animateFloat(
                        initialValue = 1.04f,
                        targetValue = 0.90f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "bt_connecting_scale"
                    )
                    Icon(
                        imageVector = Icons.Default.BluetoothAudio,
                        contentDescription = null,
                        tint = PeachAccent,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer {
                                alpha = pulseAlpha
                                scaleX = pulseScale
                                scaleY = pulseScale
                            }
                            .testTag("recorder_connecting_bluetooth_icon")
                    )
                }
                ActiveMicIndicatorState.BLUETOOTH_ACTIVE -> {
                    Icon(
                        imageVector = Icons.Default.BluetoothAudio,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier
                            .size(20.dp)
                            .testTag("recorder_bluetooth_mic_icon")
                    )
                }
                ActiveMicIndicatorState.WIRED_HEADSET_ACTIVE -> {
                    Icon(
                        imageVector = Icons.Default.HeadsetMic,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier
                            .size(20.dp)
                            .testTag("recorder_wired_mic_icon")
                    )
                }
                ActiveMicIndicatorState.PHONE_MIC_ACTIVE -> {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier
                            .size(20.dp)
                            .testTag("recorder_phone_mic_icon")
                    )
                }
            }
        }
    }
}

@Composable
fun RecorderScreen(
    status: RecordingStatus,
    settings: RecorderSettings,
    elapsedMs: Long,
    liveDb: Float,
    livePeaks: List<Float>,
    liveBookmarks: List<Long>,
    appendTarget: RecordingEntity?,
    mergeProgress: Int? = null,
    isMergeCancelling: Boolean = false,
    onCancelMerge: () -> Unit = {},
    activeAudioDeviceName: String = "Phone Microphone",
    isConnectingBluetoothMic: Boolean = false,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onAddBookmark: () -> Unit,
    onResetRecording: () -> Unit,
    onCancelAppendMode: () -> Unit,
    showAppendSaveConfirmDialog: Boolean = false,
    onConfirmAppendSave: (String) -> Unit = {},
    onDismissAppendSaveConfirmDialog: () -> Unit = {},
    onDiscardAppendSave: () -> Unit = onResetRecording,
    onToggleSkipSilence: (Boolean) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onRefreshAudioDevices: () -> Unit = {},
    onPostMessage: (String) -> Unit,
    statusBannerContent: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        onRefreshAudioDevices()
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        onRefreshAudioDevices()
        val micGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            onStartRecording()
        } else {
            onPostMessage("Microphone permission required to record audio")
        }
    }

    val isRecordingOrPaused = status == RecordingStatus.RECORDING || status == RecordingStatus.PAUSED
    val displayedPeaks = if (isRecordingOrPaused) {
        livePeaks
    } else {
        appendTarget?.peakList ?: emptyList()
    }

    val activeElapsedMs = if (isRecordingOrPaused) elapsedMs else 0L
    val totalRecordingMs = if (appendTarget != null) {
        appendTarget.durationMs + activeElapsedMs
    } else {
        activeElapsedMs
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag("recorder_screen_root"),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Top App Bar: App Title & Audio Format on the left, minimalist icon-only active mic indicator on the right
        val micIndicatorState = resolveActiveMicIndicatorState(
            activeAudioDeviceName = activeAudioDeviceName,
            isConnectingBluetoothMic = isConnectingBluetoothMic
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("recorder_top_app_bar"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Audio Recorder",
                    style = MaterialTheme.typography.headlineMedium,
                    color = PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${settings.defaultFormat.label} • ${settings.sampleRate / 1000.0} kHz • ${settings.bitDepth}-bit",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            ActiveMicTopBarIndicator(state = micIndicatorState)
        }

        // 2. Single Combined Card: Contains Big Time Counter, LIVE INPUT indicator (shown ONLY during active recording), and Waveform Visualizer
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Optional Append Mode Banner (Hidden during merging so it switches cleanly to the merging progress banner)
            AnimatedVisibility(visible = appendTarget != null && mergeProgress == null) {
                appendTarget?.let { target ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(WarmSecondarySurface)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .testTag("continue_recording_banner")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Continue recording target",
                                tint = PeachAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Continuing: ${target.title}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = PrimaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Base: ${AudioFormatUtils.formatDurationHMS(target.durationMs)} • +${AudioFormatUtils.formatDurationHMS(elapsedMs)}",
                                    style = TabularMonoStyle.copy(fontSize = 11.sp),
                                    color = PeachAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(ElevatedSurface)
                                .m3PressScale(
                                    shape = CircleShape,
                                    onClick = onCancelAppendMode
                                )
                                .testTag("cancel_append_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel append session",
                                tint = PeachAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Single Large Container Card for Time & Waveform (No READY badge or green light)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(WarmSecondarySurface)
                    .padding(top = 32.dp, bottom = 20.dp, start = 16.dp, end = 16.dp)
                    .testTag("recorder_primary_card")
            ) {
                // Large Precise Duration Display
                Text(
                    text = AudioFormatUtils.formatDurationPrecise(totalRecordingMs),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    color = PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("recorder_primary_timer")
                )

                // LIVE INPUT indicator centered directly below the time display
                if (isRecordingOrPaused) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp)
                            .testTag("recorder_live_input_indicator"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val dbFormatted = String.format(Locale.US, "%.1f", liveDb)
                        Text(
                            text = "LIVE INPUT  •  $dbFormatted dB",
                            style = TabularMonoStyle.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.8.sp
                            ),
                            color = PeachAccent
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(28.dp))
                }

                // Studio Waveform Visualizer
                val (effectiveThreshDb, _, _) = settings.silenceConfig.resolveEffectiveParameters(displayedPeaks)
                StudioInteractiveWaveform(
                    peaks = displayedPeaks,
                    isRecording = true,
                    elapsedMs = totalRecordingMs,
                    playbackPositionMs = totalRecordingMs,
                    playbackDurationMs = totalRecordingMs.coerceAtLeast(1L),
                    liveDb = liveDb,
                    skipSilenceEnabled = settings.silenceConfig.isEffectivelyEnabled,
                    silenceThresholdDb = effectiveThreshDb,
                    isSilenceJumping = false,
                    appendOriginalDurationMs = appendTarget?.durationMs,
                    onSeekToMs = {}
                )

                // Live Bookmarks Row
                AnimatedVisibility(visible = liveBookmarks.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        liveBookmarks.forEachIndexed { idx, markMs ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(ElevatedSurface)
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bookmark,
                                    contentDescription = null,
                                    tint = PeachAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "#${idx + 1} ${AudioFormatUtils.formatDurationCompact(markMs)}",
                                    style = TabularMonoStyle.copy(fontSize = 11.sp),
                                    color = PrimaryText,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // Clean Merging Progress Banner directly above control buttons with Cancel button & reverse rollback animation
        AnimatedVisibility(visible = mergeProgress != null) {
            mergeProgress?.let { progress ->
                val animatedMergeArc by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = (progress / 100f).coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = if (isMergeCancelling) 70 else 140),
                    label = "recorder_merge_progress_arc"
                )
                val indicatorColor by animateColorAsState(
                    targetValue = if (isMergeCancelling) SecondaryText else PeachAccent,
                    animationSpec = tween(durationMillis = 180),
                    label = "recorder_merge_indicator_color"
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(WarmSecondarySurface)
                        .padding(start = 16.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                        .testTag("merging_progress_banner")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            progress = { animatedMergeArc },
                            modifier = Modifier
                                .size(20.dp)
                                .testTag("merging_progress_indicator"),
                            color = indicatorColor,
                            trackColor = ElevatedSurface,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isMergeCancelling) {
                                stringResource(R.string.merge_status_reverting, progress)
                            } else {
                                stringResource(R.string.merge_status_merging, progress)
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                fontFeatureSettings = "tnum"
                            ),
                            color = if (isMergeCancelling) SecondaryText else PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("merging_progress_text")
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (!isMergeCancelling) {
                        val cancelDesc = stringResource(R.string.btn_cancel_merge)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(48.dp)
                                .clip(CircleShape)
                                .m3PressScale(
                                    shape = CircleShape,
                                    onClick = onCancelMerge
                                )
                                .semantics { contentDescription = cancelDesc }
                                .testTag("merging_cancel_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(ElevatedSurface)
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = PeachAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = stringResource(R.string.btn_cancel_merge),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    ),
                                    color = PeachAccent,
                                    maxLines = 1
                                )
                            }
                        }
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("merging_undoing_badge")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(ElevatedSurface.copy(alpha = 0.65f))
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.merge_cancelling_badge),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp
                                    ),
                                    color = SecondaryText,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Status Banner anchored strictly ABOVE the recording action controls (Zero overlap)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recorder_status_banner_anchor")
            ) {
                statusBannerContent()
            }

            // 3. Main Recording Controls Row (5 Action Buttons matching exact container sizes and state colors)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .testTag("controls_card_container"),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
            // Button 1: Skip Silence Toggle Button (Far Left)
            // Disabled and grayed out when Silence Skip (both Smart & Manual) is turned OFF in Settings
            val canUseSilenceSkip = settings.silenceConfig.isConfiguredInSettings
            val isSkipSilenceActive = settings.silenceConfig.isEffectivelyEnabled

            val skipSilenceBg = when {
                !canUseSilenceSkip -> WarmSecondarySurface.copy(alpha = 0.55f)
                isSkipSilenceActive -> PeachAccent
                isRecordingOrPaused -> ElevatedSurface
                else -> WarmSecondarySurface
            }

            val skipSilenceIconTint = when {
                !canUseSilenceSkip -> SecondaryText.copy(alpha = 0.28f)
                isSkipSilenceActive -> OnPeachAccent
                isRecordingOrPaused -> PeachAccent
                else -> SecondaryText.copy(alpha = 0.4f)
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(skipSilenceBg)
                    .m3PressScale(
                        shape = CircleShape,
                        enabled = canUseSilenceSkip,
                        onClick = { onToggleSkipSilence(!isSkipSilenceActive) }
                    )
                    .testTag("recorder_skip_silence_button")
            ) {
                SkipSilenceCustomIcon(
                    tint = skipSilenceIconTint,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Button 2: Pause / Resume Tonal Circular Button
            val canPauseResume = status == RecordingStatus.RECORDING || status == RecordingStatus.PAUSED
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (canPauseResume) ElevatedSurface else WarmSecondarySurface)
                    .m3PressScale(
                        shape = CircleShape,
                        enabled = canPauseResume,
                        onClick = {
                            if (status == RecordingStatus.RECORDING) onPauseRecording()
                            else if (status == RecordingStatus.PAUSED) onResumeRecording()
                        }
                    )
                    .testTag("recorder_pause_resume_button")
            ) {
                Icon(
                    imageVector = if (status == RecordingStatus.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (status == RecordingStatus.PAUSED) "Resume Recording" else "Pause Recording",
                    tint = if (canPauseResume) PeachAccent else SecondaryText.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp)
                )
            }

            // Button 3 (Center): Primary Record / Stop FAB
            val buttonBgColor by animateColorAsState(
                targetValue = if (isRecordingOrPaused) DestructiveCrimson else DriveRecordFab,
                animationSpec = tween(250),
                label = "record_button_bg"
            )
            val fabCornerRadius by animateDpAsState(
                targetValue = if (isRecordingOrPaused) 22.dp else 20.dp,
                animationSpec = tween(250),
                label = "record_fab_corner"
            )
            val fabShape = RoundedCornerShape(fabCornerRadius)

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .clip(fabShape)
                    .background(buttonBgColor)
                    .m3PressScale(
                        shape = fabShape,
                        pressScale = 0.94f,
                        onClick = {
                            if (isRecordingOrPaused) {
                                onStopRecording()
                            } else {
                                val hasMicPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                val needsNotifPerm = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                val needsBtPerm = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    ) != PackageManager.PERMISSION_GRANTED

                                if (hasMicPerm && !needsNotifPerm && !needsBtPerm) {
                                    onRefreshAudioDevices()
                                    onStartRecording()
                                } else {
                                    val permsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                        permsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
                                    }
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                        permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    permissionsLauncher.launch(permsToRequest.toTypedArray())
                                }
                            }
                        }
                    )
                    .testTag("primary_record_stop_button")
            ) {
                Icon(
                    imageVector = if (isRecordingOrPaused) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecordingOrPaused) "Stop Recording" else "Start Recording",
                    tint = OnDriveRecordFab,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Button 4: Bookmark / Timestamp Marker Tonal Circular Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isRecordingOrPaused) ElevatedSurface else WarmSecondarySurface)
                    .m3PressScale(
                        shape = CircleShape,
                        enabled = isRecordingOrPaused,
                        onClick = onAddBookmark
                    )
                    .testTag("recorder_bookmark_button")
            ) {
                Icon(
                    imageVector = Icons.Default.BookmarkAdd,
                    contentDescription = "Add Bookmark Marker",
                    tint = if (isRecordingOrPaused) PeachAccent else SecondaryText.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp)
                )
            }

            // Button 5: Reset Tonal Circular Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isRecordingOrPaused) ElevatedSurface else WarmSecondarySurface)
                    .m3PressScale(
                        shape = CircleShape,
                        enabled = isRecordingOrPaused,
                        onClick = { showResetConfirmDialog = true }
                    )
                    .testTag("recorder_reset_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset Recording",
                    tint = if (isRecordingOrPaused) PeachAccent else SecondaryText.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp)
                )
            }
            }
        }
    }

    if (showResetConfirmDialog) {
        UnifiedTrashConfirmDialog(
            title = "Discard Recording?",
            message = "Are you sure you want to discard this active recording? All unsaved audio will be lost.",
            confirmText = "Confirm",
            onConfirm = {
                onResetRecording()
                showResetConfirmDialog = false
            },
            onDismiss = { showResetConfirmDialog = false },
            icon = Icons.Default.DeleteOutline
        )
    }

    if (showAppendSaveConfirmDialog) {
        var dialogRecordingTitle by remember(showAppendSaveConfirmDialog, appendTarget) {
            val defaultTitle = appendTarget?.title
                ?: com.example.service.RecordingIslandOverlayManager.currentEditedRecordingTitle.ifBlank {
                    com.example.service.RecordingIslandOverlayManager.generateDefaultTimestampedTitle()
                }
            com.example.service.RecordingIslandOverlayManager.currentEditedRecordingTitle = defaultTitle
            mutableStateOf(defaultTitle)
        }

        Dialog(
            onDismissRequest = onDismissAppendSaveConfirmDialog,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .testTag("append_confirm_dialog"),
                color = WarmSecondarySurface,
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 22.dp)
                ) {
                    Text(
                        text = if (appendTarget != null) "Append to Recording?" else "Save Recording?",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = PrimaryText,
                        modifier = Modifier.testTag("append_confirm_dialog_title")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (appendTarget != null) {
                            "Save and merge this new audio segment into the original file?"
                        } else {
                            "Save this new recording to your audio library?"
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        ),
                        color = SecondaryText,
                        modifier = Modifier.testTag("append_confirm_dialog_message")
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Three equal-width side-by-side action buttons on top:
                    // Left: Discard (reject & discard recording)
                    // Center: Cancel (dismiss dialog & leave recording paused at exact timestamp)
                    // Right: Save (finalize & save recording with title)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Left: Discard button container
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(CircleShape)
                                .background(TonalDarkBrown)
                                .m3PressScale(
                                    shape = CircleShape,
                                    onClick = onDiscardAppendSave
                                )
                                .padding(horizontal = 8.dp)
                                .testTag("append_confirm_discard_button")
                        ) {
                            AutoSizingActionButtonText(
                                text = "Discard",
                                color = PrimaryText
                            )
                        }

                        // 2. Center: Cancel button container (keeps recording paused at exact timestamp)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(CircleShape)
                                .background(TonalDarkBrown)
                                .m3PressScale(
                                    shape = CircleShape,
                                    onClick = onDismissAppendSaveConfirmDialog
                                )
                                .padding(horizontal = 8.dp)
                                .testTag("append_confirm_cancel_button")
                        ) {
                            AutoSizingActionButtonText(
                                text = "Cancel",
                                color = PrimaryText
                            )
                        }

                        // 3. Right: Confirm button container
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(CircleShape)
                                .background(TonalDarkBrown)
                                .m3PressScale(
                                    shape = CircleShape,
                                    onClick = {
                                        val exactTypedTitle = dialogRecordingTitle.trim()
                                        com.example.service.RecordingIslandOverlayManager.currentEditedRecordingTitle =
                                            exactTypedTitle
                                        onConfirmAppendSave(exactTypedTitle)
                                    }
                                )
                                .padding(horizontal = 8.dp)
                                .testTag("append_confirm_yes_save_button")
                        ) {
                            AutoSizingActionButtonText(
                                text = "Confirm",
                                color = PrimaryText
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Recording Name Input Field directly beneath the buttons
                    androidx.compose.material3.OutlinedTextField(
                        value = dialogRecordingTitle,
                        onValueChange = {
                            dialogRecordingTitle = it
                            com.example.service.RecordingIslandOverlayManager.currentEditedRecordingTitle = it
                        },
                        label = { Text("Recording Title", maxLines = 1) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            autoCorrectEnabled = false
                        ),
                        shape = RoundedCornerShape(14.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PeachAccent,
                            unfocusedBorderColor = OutlineWarm,
                            cursorColor = PeachAccent,
                            focusedLabelColor = PrimaryText,
                            unfocusedLabelColor = SecondaryText,
                            focusedTextColor = PrimaryText,
                            unfocusedTextColor = PrimaryText,
                            focusedContainerColor = ObsidianBackground,
                            unfocusedContainerColor = ObsidianBackground
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("append_confirm_title_input")
                    )
                }
            }
        }
    }
}
