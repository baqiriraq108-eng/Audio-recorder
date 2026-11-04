package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.AudioFormatUtils
import com.example.data.PlaybackSpeedOption
import com.example.data.RecorderSettings
import com.example.data.RecordingEntity
import com.example.data.SilenceInterval
import com.example.ui.components.ShareExportActionSheet
import com.example.ui.components.StudioInteractiveWaveform
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.OnPeachAccent
import com.example.ui.theme.PeachAccent
import com.example.ui.theme.PlayerButtonLabelStyle
import com.example.ui.theme.PlayerCompactSecondaryStyle
import com.example.ui.theme.PlayerMainTimerStyle
import com.example.ui.theme.PlayerRecordingTitleStyle
import com.example.ui.theme.PlayerSecondaryInfoStyle
import com.example.ui.theme.PlayerSecondaryTimerStyle
import com.example.ui.theme.PlayerSkipSilentLabelStyle
import com.example.ui.theme.PlayerSpeedLabelStyle
import com.example.ui.theme.PrimaryText
import com.example.ui.theme.SecondaryText
import com.example.ui.theme.SuccessSage
import com.example.ui.theme.TonalDarkBrown
import com.example.ui.theme.WarmSecondarySurface
import com.example.ui.theme.m3PressScale
import androidx.compose.runtime.rememberCoroutineScope
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun SavedRecordingPlayerScreen(
    recording: RecordingEntity,
    settings: RecorderSettings,
    isPlaying: Boolean,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    playbackSpeed: Float,
    silenceSkippedSavedMs: Long,
    isSilenceJumping: Boolean,
    activeSilenceMap: List<SilenceInterval> = emptyList(),
    onClosePlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onSeekRelativeMs: (Long) -> Unit,
    onAddBookmark: () -> Unit,
    onRemoveBookmark: (Long) -> Unit,
    onContinueRecording: (RecordingEntity) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onToggleSkipSilence: (Boolean) -> Unit,
    onExportWithoutSilence: (RecordingEntity) -> Unit,
    onTrimRecording: (RecordingEntity, Long, Long, Boolean) -> Unit = { _, _, _, _ -> },
    onOpenExportConversion: (RecordingEntity) -> Unit = {},
    statusBannerContent: @Composable () -> Unit = {}
) {
    BackHandler(onBack = onClosePlayer)

    val context = LocalContext.current
    var showShareExportSheet by remember { mutableStateOf(false) }
    var isTrimMode by remember(recording.id) { mutableStateOf(false) }
    var trimStartFraction by remember(recording.id, recording.durationMs) { mutableFloatStateOf(0.10f) }
    var trimEndFraction by remember(recording.id, recording.durationMs) { mutableFloatStateOf(0.90f) }

    val effectiveDuration = playbackDurationMs.takeIf { it > 0L } ?: recording.durationMs.coerceAtLeast(1L)
    val trimStartMs = (trimStartFraction * effectiveDuration).toLong().coerceIn(0L, effectiveDuration)
    val trimEndMs = (trimEndFraction * effectiveDuration).toLong().coerceIn(trimStartMs, effectiveDuration)
    val trimmedSpanMs = (trimEndMs - trimStartMs).coerceAtLeast(0L)

    val bookmarks = recording.bookmarkList
    val (effThresholdDb, _, _) = settings.silenceConfig.resolveEffectiveParameters(recording.peakList)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag("saved_recording_player_screen"),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Top Header Bar (Back Button, Recording Title, Share/Export Action Button)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ElevatedSurface)
                    .m3PressScale(shape = CircleShape, onClick = onClosePlayer)
                    .testTag("player_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Library",
                    tint = PrimaryText,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recording.title,
                    style = PlayerRecordingTitleStyle,
                    color = PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${recording.format} · ${recording.sampleRate / 1000.0} kHz · ${AudioFormatUtils.formatFileSize(recording.sizeBytes)}",
                    style = PlayerSecondaryInfoStyle,
                    color = SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Playback Timer & Interactive Waveform Section
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(WarmSecondarySurface)
                    .padding(vertical = 20.dp, horizontal = 16.dp)
            ) {
                Text(
                    text = AudioFormatUtils.formatDurationPrecise(playbackPositionMs),
                    style = PlayerMainTimerStyle,
                    color = PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("player_current_time")
                )

                Spacer(modifier = Modifier.height(6.dp))

                val durationFormatted = AudioFormatUtils.formatDurationHMS(effectiveDuration)
                val showSavedTime = settings.silenceConfig.isEffectivelyEnabled && silenceSkippedSavedMs > 0L
                val savedFormatted = if (showSavedTime) {
                    " · Saved ${String.format(java.util.Locale.US, "%.1fs", silenceSkippedSavedMs / 1000f)}"
                } else ""
                Text(
                    text = "$durationFormatted$savedFormatted",
                    style = PlayerSecondaryTimerStyle,
                    color = if (showSavedTime) SuccessSage else SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("player_secondary_timer")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            val silenceIntervalsToRender = if (activeSilenceMap.isNotEmpty()) {
                activeSilenceMap
            } else {
                AudioFormatUtils.computeSilenceMapFromPeaks(
                    peaks = recording.peakList,
                    durationMs = effectiveDuration,
                    config = settings.silenceConfig
                )
            }
            val effectiveSeekTo: (Long) -> Unit = { rawMs ->
                if (isTrimMode) {
                    onSeekToMs(rawMs.coerceIn(trimStartMs, trimEndMs))
                } else {
                    onSeekToMs(rawMs.coerceIn(0L, effectiveDuration))
                }
            }

            // When isTrimMode is active, strictly confine playback and scrub position within selected trim boundaries
            LaunchedEffect(isTrimMode, playbackPositionMs, trimStartMs, trimEndMs, isPlaying) {
                if (isTrimMode) {
                    if (playbackPositionMs < trimStartMs) {
                        onSeekToMs(trimStartMs)
                    } else if (playbackPositionMs >= trimEndMs) {
                        if (isPlaying) {
                            onTogglePlayPause() // Pause playback at trimEndMs boundary
                        }
                        onSeekToMs(trimEndMs)
                    }
                }
            }

            StudioInteractiveWaveform(
                peaks = recording.peakList,
                isRecording = false,
                elapsedMs = 0L,
                playbackPositionMs = playbackPositionMs,
                playbackDurationMs = effectiveDuration,
                liveDb = -60f,
                skipSilenceEnabled = settings.silenceConfig.isEffectivelyEnabled,
                silenceThresholdDb = effThresholdDb,
                isSilenceJumping = isSilenceJumping,
                silenceIntervals = silenceIntervalsToRender,
                isTrimMode = isTrimMode,
                trimStartFraction = trimStartFraction,
                trimEndFraction = trimEndFraction,
                onTrimRangeChange = { newStart, newEnd ->
                    trimStartFraction = newStart
                    trimEndFraction = newEnd
                },
                onSeekToMs = effectiveSeekTo
            )

            // Saved Bookmarks Bar (tap to seek, tap X to remove)
            AnimatedVisibility(visible = bookmarks.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(
                        text = "Bookmarks (${bookmarks.size})",
                        style = PlayerCompactSecondaryStyle,
                        color = SecondaryText,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        bookmarks.forEachIndexed { idx, markMs ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .height(36.dp)
                                    .clip(CircleShape)
                                    .background(ElevatedSurface)
                                    .m3PressScale(
                                        shape = CircleShape,
                                        onClick = { onSeekToMs(markMs) }
                                    )
                                    .padding(horizontal = 12.dp)
                                    .testTag("bookmark_chip_$idx")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bookmark,
                                    contentDescription = "Jump to bookmark",
                                    tint = PeachAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = AudioFormatUtils.formatDurationCompact(markMs),
                                    style = PlayerCompactSecondaryStyle,
                                    color = PrimaryText,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .m3PressScale(
                                            shape = CircleShape,
                                            onClick = { onRemoveBookmark(markMs) }
                                        )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove bookmark",
                                        tint = SecondaryText,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Status Banner anchored strictly ABOVE the controls card container (Zero overlap)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("player_status_banner_anchor")
            ) {
                statusBannerContent()
            }

            // 3. Dedicated Player Controls Card (Borderless 16.dp Surface Container #221B17)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(WarmSecondarySurface)
                    .padding(16.dp)
                    .testTag("controls_card_container")
            ) {
            // Primary Transport & Action Row (5 controls)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Place Bookmark Button (48.dp circular button)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(ElevatedSurface)
                        .m3PressScale(
                            shape = CircleShape,
                            onClick = onAddBookmark
                        )
                        .testTag("player_add_bookmark_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdd,
                        contentDescription = "Add Bookmark",
                        tint = PeachAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // 2. Rewind -3s Button (12.dp rounded corners, 48.dp accessible touch target)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ElevatedSurface)
                        .m3PressScale(
                            shape = RoundedCornerShape(12.dp),
                            onClick = {
                                if (isTrimMode) {
                                    val target = (playbackPositionMs - 3_000L).coerceIn(trimStartMs, trimEndMs)
                                    onSeekToMs(target)
                                } else {
                                    onSeekRelativeMs(-3_000L)
                                }
                            }
                        )
                        .testTag("player_rewind_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FastRewind,
                        contentDescription = "Rewind 3 seconds",
                        tint = PrimaryText,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // 3. Central Play / Pause Button (Primary transport hero: 60.dp)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(PeachAccent)
                        .m3PressScale(
                            shape = CircleShape,
                            onClick = {
                                if (isTrimMode && !isPlaying) {
                                    if (playbackPositionMs < trimStartMs || playbackPositionMs >= trimEndMs - 50L) {
                                        onSeekToMs(trimStartMs)
                                    }
                                }
                                onTogglePlayPause()
                            }
                        )
                        .testTag("player_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = OnPeachAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // 4. Fast-Forward +3s Button (12.dp rounded corners, 48.dp accessible touch target)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ElevatedSurface)
                        .m3PressScale(
                            shape = RoundedCornerShape(12.dp),
                            onClick = {
                                if (isTrimMode) {
                                    val target = (playbackPositionMs + 3_000L).coerceIn(trimStartMs, trimEndMs)
                                    onSeekToMs(target)
                                } else {
                                    onSeekRelativeMs(3_000L)
                                }
                            }
                        )
                        .testTag("player_forward_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = "Fast forward 3 seconds",
                        tint = PrimaryText,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // 5. Continue Recording on Same File Button (48.dp circular button)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(ElevatedSurface)
                        .m3PressScale(
                            shape = CircleShape,
                            onClick = { onContinueRecording(recording) }
                        )
                        .testTag("player_continue_recording_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Continue Recording on this file",
                        tint = PeachAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Playback Speed Selector (Horizontally scrollable, 14sp Medium labels, comfortable touch targets)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlaybackSpeedOption.entries.forEach { option ->
                    val selected = kotlin.math.abs(playbackSpeed - option.multiplier) < 0.02f
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
                            .clip(CircleShape)
                            .background(if (selected) PeachAccent else ObsidianBackground)
                            .m3PressScale(
                                shape = CircleShape,
                                onClick = { onSelectSpeed(option.multiplier) }
                            )
                            .padding(horizontal = 10.dp)
                            .testTag("player_speed_chip_${option.label}")
                    ) {
                        Text(
                            text = option.label,
                            style = PlayerSpeedLabelStyle,
                            color = if (selected) OnPeachAccent else SecondaryText,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Silence-Skipping Control Row ("Skip Silent"), Scissors Button, and Share Button
            val canUseSilenceSkip = settings.silenceConfig.isConfiguredInSettings
            val isSkipSilenceActive = settings.silenceConfig.isEffectivelyEnabled
            val skipSilenceBg = when {
                !canUseSilenceSkip -> WarmSecondarySurface.copy(alpha = 0.55f)
                isSkipSilenceActive -> PeachAccent
                else -> WarmSecondarySurface
            }
            val skipSilenceIconTint = when {
                !canUseSilenceSkip -> SecondaryText.copy(alpha = 0.28f)
                isSkipSilenceActive -> OnPeachAccent
                else -> SecondaryText.copy(alpha = 0.4f)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianBackground)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Silence Skip Icon Button (48.dp Circle on the left)
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
                        .testTag("player_skip_silence_toggle")
                ) {
                    SkipSilenceCustomIcon(
                        tint = skipSilenceIconTint,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 2. "Skip Silent" Label (14 sp, Medium, single line, flexible width between left icon and right buttons)
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.skip_silence),
                        style = PlayerSkipSilentLabelStyle,
                        color = if (canUseSilenceSkip) PrimaryText else SecondaryText.copy(alpha = 0.5f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                        modifier = Modifier.testTag("player_skip_silent_label")
                    )
                }

                // 3. Scissors Audio Trim Button (48.dp Circle)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (isTrimMode) PeachAccent else WarmSecondarySurface)
                        .m3PressScale(
                            shape = CircleShape,
                            onClick = { isTrimMode = !isTrimMode }
                        )
                        .testTag("player_trim_toggle")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCut,
                        contentDescription = "Trim Audio",
                        tint = if (isTrimMode) OnPeachAccent else PeachAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 4. Share / Export Audio Button (48.dp Circle - Sole Export Hub)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(WarmSecondarySurface)
                        .m3PressScale(
                            shape = CircleShape,
                            onClick = { showShareExportSheet = true }
                        )
                        .testTag("player_export_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.IosShare,
                        contentDescription = "Share or Export Audio",
                        tint = PeachAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Expandable Audio Trimming Panel with Save as Copy & Apply to Current (Overwrite)
            AnimatedVisibility(visible = isTrimMode) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianBackground)
                        .padding(14.dp)
                        .testTag("player_trim_panel")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ContentCut,
                                contentDescription = null,
                                tint = PeachAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Trim Selection",
                                style = PlayerButtonLabelStyle,
                                color = PrimaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "Keep ${AudioFormatUtils.formatDurationCompact(trimmedSpanMs)}",
                            style = PlayerButtonLabelStyle,
                            color = PeachAccent,
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Start: ${AudioFormatUtils.formatDurationPrecise(trimStartMs)}",
                            style = PlayerCompactSecondaryStyle,
                            color = SecondaryText
                        )
                        Text(
                            text = "End: ${AudioFormatUtils.formatDurationPrecise(trimEndMs)}",
                            style = PlayerCompactSecondaryStyle,
                            color = SecondaryText
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                onTrimRecording(recording, trimStartMs, trimEndMs, false)
                                isTrimMode = false
                            },
                            shape = CircleShape,
                            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TonalDarkBrown,
                                contentColor = PrimaryText
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("player_trim_save_copy_button")
                        ) {
                            Text(
                                text = "Save Copy",
                                style = PlayerButtonLabelStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = {
                                onTrimRecording(recording, trimStartMs, trimEndMs, true)
                                isTrimMode = false
                            },
                            shape = CircleShape,
                            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TonalDarkBrown,
                                contentColor = PrimaryText
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("player_trim_overwrite_button")
                        ) {
                            Text(
                                text = "Apply",
                                style = PlayerButtonLabelStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    var isSharingInProgress by remember { mutableStateOf(false) }

    val shareAudioFile: (File, String) -> Unit = { fileToShare, subjectTitle ->
        runCatching {
            val shareUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                fileToShare
            )
            val ext = fileToShare.extension.lowercase(Locale.ROOT)
            val mimeType = when (ext) {
                "m4a", "mp4" -> "audio/mp4"
                "flac" -> "audio/flac"
                else -> "audio/wav"
            }
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, subjectTitle)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Share \"$subjectTitle\"").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val resInfoList = context.packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                context.grantUriPermission(packageName, shareUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
        }
    }

    if (showShareExportSheet) {
        ShareExportActionSheet(
            recording = recording,
            isTrimMode = isTrimMode,
            trimStartMs = trimStartMs,
            trimEndMs = trimEndMs,
            onShareOriginal = {
                showShareExportSheet = false
                shareAudioFile(File(recording.filePath), recording.title)
            },
            onShareTrimmed = if (isTrimMode) {
                {
                    showShareExportSheet = false
                    if (!isSharingInProgress) {
                        isSharingInProgress = true
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val sourceFile = File(recording.filePath)
                                val trimmedShareFile = File(
                                    context.cacheDir,
                                    "share_trimmed_${recording.id}_${trimStartMs}_${trimEndMs}.${sourceFile.extension.ifBlank { recording.format.lowercase(Locale.ROOT) }}"
                                )
                                val (trimmedDur, _) = com.example.audio.WavFileTools.trimWavToRange(
                                    sourceFile = sourceFile,
                                    targetFile = trimmedShareFile,
                                    startMs = trimStartMs,
                                    endMs = trimEndMs,
                                    fallbackSampleRate = recording.sampleRate,
                                    fallbackChannels = recording.channels,
                                    fallbackBitsPerSample = recording.bitDepth
                                )
                                withContext(Dispatchers.Main) {
                                    isSharingInProgress = false
                                    if (trimmedShareFile.exists() && trimmedShareFile.length() > 44L) {
                                        val subjectTitle = "${recording.title} (Trimmed)"
                                        shareAudioFile(trimmedShareFile, subjectTitle)
                                    } else {
                                        // Fallback to sharing original if trimming failed
                                        shareAudioFile(sourceFile, recording.title)
                                    }
                                }
                            } catch (_: Exception) {
                                withContext(Dispatchers.Main) {
                                    isSharingInProgress = false
                                    shareAudioFile(File(recording.filePath), recording.title)
                                }
                            }
                        }
                    }
                }
            } else null,
            onExportAudio = {
                showShareExportSheet = false
                onOpenExportConversion(recording)
            },
            onDismiss = { showShareExportSheet = false }
        )
    }
}

