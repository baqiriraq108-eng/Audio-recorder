package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.AudioFormatOption
import com.example.data.AudioFormatUtils
import com.example.data.RecordingEntity
import com.example.ui.theme.CloudArrowBrown
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
import com.example.ui.theme.SuccessSage
import com.example.ui.theme.TonalDarkBrown
import com.example.ui.theme.WarmSecondarySurface
import com.example.ui.theme.m3PressScale
import com.example.viewmodel.ConversionJobState
import com.example.viewmodel.PendingSaveSession
import com.example.viewmodel.SilenceExportState

import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.DialogProperties

@Composable
fun PostRecordingSaveSheet(
    pending: PendingSaveSession,
    onSave: (title: String, skipSilenceOnSave: Boolean) -> Unit,
    onDiscard: () -> Unit
) {
    var titleText by remember(pending) { mutableStateOf(pending.suggestedTitle) }

    // Block back navigation while the mandatory save title dialog is displayed
    BackHandler(enabled = true) {
        // Strictly non-dismissible: user must explicitly tap Save or Don't Save
    }

    Dialog(
        onDismissRequest = {
            // Strictly non-dismissible: cannot be bypassed by clicking outside or pressing back
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = WarmSecondarySurface,
            contentColor = PrimaryText,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(24.dp))
                .graphicsLayer()
                .testTag("post_recording_save_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
                Text(
                    text = "Save Recording",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Duration: ${AudioFormatUtils.formatDurationHMS(pending.durationMs)}" +
                        if (pending.bookmarks.isNotEmpty()) " • ${pending.bookmarks.size} Bookmark(s)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Enter a title for this recording. Audio is saved in its full original state; you can toggle silence skipping anytime during playback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("Recording Title", maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryText,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedLabelColor = PrimaryText,
                        unfocusedLabelColor = SecondaryText,
                        focusedTextColor = PrimaryText,
                        unfocusedTextColor = PrimaryText,
                        focusedContainerColor = ObsidianBackground,
                        unfocusedContainerColor = ObsidianBackground
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_sheet_title_input")
                )

                Spacer(modifier = Modifier.height(22.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onDiscard,
                        shape = CircleShape,
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TonalDarkBrown,
                            contentColor = PrimaryText
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("save_sheet_discard_button")
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = PrimaryText,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = { onSave(titleText, false) },
                        shape = CircleShape,
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TonalDarkBrown,
                            contentColor = PrimaryText
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("save_sheet_confirm_button")
                    ) {
                        Text(
                            text = "Confirm",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = PrimaryText,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExportWithoutSilenceDialog(
    state: SilenceExportState,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = WarmSecondarySurface,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer()
                .animateContentSize()
                .testTag("export_without_silence_modal")
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(ElevatedSurface)
                ) {
                    Icon(
                        imageVector = if (state.isRunning) Icons.Default.IosShare else Icons.Default.CheckCircle,
                        contentDescription = "Silence export status",
                        tint = if (state.isRunning) PrimaryText else SuccessSage,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = if (state.isRunning) "Exporting Without Silence…" else "Export Complete",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (state.isRunning) {
                        "Rendering non-destructive trimmed copy of \"${state.source.title}\""
                    } else {
                        "Saved as \"${state.completedTitle ?: state.source.title}\" in Library"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryText
                )

                Spacer(modifier = Modifier.height(18.dp))

                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    color = if (state.isRunning) PrimaryText else SuccessSage,
                    trackColor = ObsidianBackground,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = PrimaryText,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(22.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = CircleShape,
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TonalDarkBrown,
                            contentColor = PrimaryText
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = PrimaryText,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = onDismiss,
                        shape = CircleShape,
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TonalDarkBrown,
                            contentColor = PrimaryText
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(
                            text = "Confirm",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = PrimaryText,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareExportActionSheet(
    recording: RecordingEntity,
    isTrimMode: Boolean = false,
    trimStartMs: Long = 0L,
    trimEndMs: Long = 0L,
    onShareOriginal: () -> Unit,
    onShareTrimmed: (() -> Unit)? = null,
    onExportAudio: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = WarmSecondarySurface,
        contentColor = PrimaryText
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("share_export_action_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Text(
                    text = "Share Recording",
                    style = MaterialTheme.typography.titleLarge,
                    color = PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${recording.title} • ${recording.format} • ${AudioFormatUtils.formatFileSize(recording.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Option: Trimmed Audio (Shown when trim mode is active or trim range specified)
            if (isTrimMode && onShareTrimmed != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ElevatedSurface)
                        .m3PressScale(
                            shape = RoundedCornerShape(14.dp),
                            onClick = onShareTrimmed
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("action_share_trimmed")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ObsidianBackground)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCut,
                            contentDescription = "Share Trimmed",
                            tint = PeachAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Trimmed",
                            style = MaterialTheme.typography.titleMedium,
                            color = PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val trimmedSpan = (trimEndMs - trimStartMs).coerceAtLeast(0L)
                        Text(
                            text = "${AudioFormatUtils.formatDurationCompact(trimStartMs)} – ${AudioFormatUtils.formatDurationCompact(trimEndMs)} (${AudioFormatUtils.formatDurationCompact(trimmedSpan)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Option: Original Audio
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ElevatedSurface)
                    .m3PressScale(
                        shape = RoundedCornerShape(14.dp),
                        onClick = onShareOriginal
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("action_share_via_apps")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(ObsidianBackground)
                ) {
                    Icon(
                        imageVector = Icons.Default.IosShare,
                        contentDescription = "Share Original",
                        tint = PeachAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isTrimMode) "Original" else "Share via Apps",
                        style = MaterialTheme.typography.titleMedium,
                        color = PrimaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isTrimMode) "Full original file (${AudioFormatUtils.formatDurationHMS(recording.durationMs)})" else "Send audio directly via Android Sharesheet",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Option 2: Export Audio (Opens detailed audio conversion & export dialog)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ElevatedSurface)
                    .m3PressScale(
                        shape = RoundedCornerShape(16.dp),
                        onClick = onExportAudio
                    )
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .testTag("action_export_audio")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(ObsidianBackground)
                ) {
                    Icon(
                        imageVector = Icons.Default.Transform,
                        contentDescription = "Export Audio",
                        tint = PeachAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Convert Format...",
                        style = MaterialTheme.typography.titleMedium,
                        color = PrimaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Select format, bitrate/sample rate quality & target device folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatConversionSheet(
    state: ConversionJobState,
    onUpdateOptions: (AudioFormatOption, Int, Int, Int, Int) -> Unit,
    onSelectExportFolder: (Uri) -> Unit = {},
    onResetExportFolder: () -> Unit = {},
    onStartConversion: () -> Unit,
    onCancel: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val selectExportFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            onSelectExportFolder(treeUri)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = WarmSecondarySurface,
        contentColor = PrimaryText
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .testTag("format_conversion_sheet")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Transform,
                    contentDescription = "Export & Convert Audio",
                    tint = PeachAccent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Export & Convert Audio",
                        style = MaterialTheme.typography.headlineSmall,
                        color = PrimaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Source: ${state.source.title} (${state.source.format})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Target Encoder Format", style = MaterialTheme.typography.labelLarge, color = SecondaryText, maxLines = 1)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                AudioFormatOption.entries.forEach { fmt ->
                    val selected = state.targetFormat == fmt
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(if (selected) PeachAccent else ElevatedSurface)
                            .m3PressScale(
                                shape = CircleShape,
                                enabled = !state.isRunning,
                                onClick = {
                                    onUpdateOptions(fmt, state.sampleRate, state.bitDepth, state.channels, fmt.defaultBitrate)
                                }
                            )
                    ) {
                        Text(
                            text = fmt.id,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (selected) OnPeachAccent else PrimaryText,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Sample Rate", style = MaterialTheme.typography.labelLarge, color = SecondaryText, maxLines = 1)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(22050 to "22.05 kHz", 44100 to "44.1 kHz", 48000 to "48 kHz").forEach { (rate, label) ->
                    val selected = state.sampleRate == rate
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(if (selected) PeachAccent else ElevatedSurface)
                            .m3PressScale(
                                shape = CircleShape,
                                enabled = !state.isRunning,
                                onClick = {
                                    onUpdateOptions(state.targetFormat, rate, state.bitDepth, state.channels, state.bitrate)
                                }
                            )
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (selected) OnPeachAccent else PrimaryText,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bit Depth", style = MaterialTheme.typography.labelLarge, color = SecondaryText, maxLines = 1)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(16, 24).forEach { depth ->
                            val selected = state.bitDepth == depth
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) PeachAccent else ElevatedSurface)
                                    .m3PressScale(
                                        shape = CircleShape,
                                        enabled = !state.isRunning,
                                        onClick = {
                                            onUpdateOptions(state.targetFormat, state.sampleRate, depth, state.channels, state.bitrate)
                                        }
                                    )
                            ) {
                                Text(
                                    text = "${depth}-bit",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = if (selected) OnPeachAccent else PrimaryText,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Channels", style = MaterialTheme.typography.labelLarge, color = SecondaryText, maxLines = 1)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1 to "Mono", 2 to "Stereo").forEach { (ch, label) ->
                            val selected = state.channels == ch
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) PeachAccent else ElevatedSurface)
                                    .m3PressScale(
                                        shape = CircleShape,
                                        enabled = !state.isRunning,
                                        onClick = {
                                            onUpdateOptions(state.targetFormat, state.sampleRate, state.bitDepth, ch, state.bitrate)
                                        }
                                    )
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = if (selected) OnPeachAccent else PrimaryText,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Target Bitrate", style = MaterialTheme.typography.labelLarge, color = SecondaryText, maxLines = 1)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(128_000 to "128 kbps", 192_000 to "192 kbps", 256_000 to "256 kbps").forEach { (br, label) ->
                    val selected = state.bitrate == br
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(if (selected) PeachAccent else ElevatedSurface)
                            .m3PressScale(
                                shape = CircleShape,
                                enabled = !state.isRunning,
                                onClick = {
                                    onUpdateOptions(state.targetFormat, state.sampleRate, state.bitDepth, state.channels, br)
                                }
                            )
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (selected) OnPeachAccent else PrimaryText,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Target Storage Folder", style = MaterialTheme.typography.labelLarge, color = SecondaryText, maxLines = 1)
            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ElevatedSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .testTag("export_destination_folder_card")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Export Folder",
                            tint = PeachAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = state.exportFolderName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (state.exportTreeUri != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ObsidianBackground,
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = !state.isRunning) {
                                        onResetExportFolder()
                                    }
                                    .testTag("reset_export_folder_button")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Reset Default",
                                        tint = SecondaryText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = TonalDarkBrown,
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !state.isRunning) {
                                    selectExportFolderLauncher.launch(null)
                                }
                                .testTag("select_export_folder_button")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = "Change",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = PrimaryText,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            if (state.isRunning) {
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    color = PeachAccent,
                    trackColor = ObsidianBackground,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Encoding ${state.targetFormat.label}… ${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = PeachAccent,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onCancel,
                    shape = CircleShape,
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TonalDarkBrown,
                        contentColor = PrimaryText
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        ),
                        color = PrimaryText,
                        maxLines = 1
                    )
                }

                Button(
                    onClick = { if (!state.isRunning) onStartConversion() },
                    enabled = !state.isRunning,
                    shape = CircleShape,
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TonalDarkBrown,
                        contentColor = PrimaryText,
                        disabledContainerColor = TonalDarkBrown.copy(alpha = 0.6f),
                        disabledContentColor = PrimaryText.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("start_conversion_button")
                ) {
                    Text(
                        text = "Confirm",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        ),
                        color = PrimaryText,
                        maxLines = 1
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun EditRecordingDialog(
    recording: RecordingEntity,
    onConfirm: (title: String, tags: String, notes: String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(recording) { mutableStateOf(recording.title) }
    var tags by remember(recording) { mutableStateOf(recording.tags) }
    var notes by remember(recording) { mutableStateOf(recording.notes) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = WarmSecondarySurface,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(24.dp))
                .graphicsLayer()
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
                Text(
                    text = "Edit Recording Details",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title", maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryText,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedLabelColor = PrimaryText,
                        unfocusedLabelColor = SecondaryText,
                        focusedTextColor = PrimaryText,
                        unfocusedTextColor = PrimaryText,
                        focusedContainerColor = ObsidianBackground,
                        unfocusedContainerColor = ObsidianBackground
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags", maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryText,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedLabelColor = PrimaryText,
                        unfocusedLabelColor = SecondaryText,
                        focusedTextColor = PrimaryText,
                        unfocusedTextColor = PrimaryText,
                        focusedContainerColor = ObsidianBackground,
                        unfocusedContainerColor = ObsidianBackground
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes", maxLines = 1) },
                    minLines = 2,
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryText,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedLabelColor = PrimaryText,
                        unfocusedLabelColor = SecondaryText,
                        focusedTextColor = PrimaryText,
                        unfocusedTextColor = PrimaryText,
                        focusedContainerColor = ObsidianBackground,
                        unfocusedContainerColor = ObsidianBackground
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(22.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = CircleShape,
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TonalDarkBrown,
                            contentColor = PrimaryText
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = PrimaryText,
                            maxLines = 1
                        )
                    }
                    Button(
                        onClick = { onConfirm(title, tags, notes) },
                        shape = CircleShape,
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TonalDarkBrown,
                            contentColor = PrimaryText
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(
                            text = "Confirm",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = PrimaryText,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Two-tone Upload Cloud icon where the cloud body is [PeachAccent] and the inner upward
 * arrow is [CloudArrowBrown] (matching the MP4 format badge container [ObsidianBackground]),
 * perfectly unifying upload/download cloud arrows across the app.
 */
val UploadCloudWithArrowIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "UploadCloudWithArrow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 1. Solid Cloud Body in PeachAccent
        path(
            fill = SolidColor(PeachAccent),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
            curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
            curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
            curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
            horizontalLineTo(19f)
            curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
            curveTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
            close()
        }
        // 2. Upward Upload Arrow in CloudArrowBrown (matching MP4 badge container ObsidianBackground)
        path(
            fill = SolidColor(CloudArrowBrown),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(12f, 8f)
            lineTo(7f, 13f)
            horizontalLineTo(10f)
            verticalLineTo(17f)
            horizontalLineTo(14f)
            verticalLineTo(13f)
            horizontalLineTo(17f)
            close()
        }
    }.build()
}

/**
 * Two-tone Import Cloud icon where the cloud body is [PeachAccent] and the inner downward
 * arrow is [CloudArrowBrown], matching the 3 sweep lines of [OutlinedDeleteSweepIcon].
 */
val ImportCloudWithArrowIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ImportCloudWithArrow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 1. Solid Cloud Body in PeachAccent
        path(
            fill = SolidColor(PeachAccent),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
            curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
            curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
            curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
            horizontalLineTo(19f)
            curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
            curveTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
            close()
        }
        // 2. Downward Import Arrow in CloudArrowBrown
        path(
            fill = SolidColor(CloudArrowBrown),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(17f, 13f)
            lineTo(12f, 18f)
            lineTo(7f, 13f)
            horizontalLineTo(10f)
            verticalLineTo(9f)
            horizontalLineTo(14f)
            verticalLineTo(13f)
            horizontalLineTo(17f)
            close()
        }
    }.build()
}

/**
 * Custom vector icon combining the hollow/outlined Trash Bin silhouette from the Trash tab
 * (with a rectangular center cutout showing the container background) in [PeachAccent] and
 * 3 horizontal side sweep lines in [CloudArrowBrown] (matching the arrow in the Import Cloud).
 */
val OutlinedDeleteSweepIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "OutlinedDeleteSweep",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 1. Outlined Trash Bin Lid & Top Handle
        path(
            fill = SolidColor(PeachAccent),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(10.5f, 5f)
            lineTo(9.5f, 4f)
            horizontalLineTo(6.5f)
            lineTo(5.5f, 5f)
            horizontalLineTo(2.5f)
            verticalLineTo(7f)
            horizontalLineTo(13.5f)
            verticalLineTo(5f)
            close()
        }
        // 2. Outlined Trash Bin Body with Hollow Rectangular Opening in the Center
        path(
            fill = SolidColor(PeachAccent),
            pathFillType = PathFillType.EvenOdd
        ) {
            // Outer bin wall
            moveTo(3.5f, 18.2f)
            curveTo(3.5f, 19.2f, 4.3f, 20f, 5.3f, 20f)
            horizontalLineTo(10.7f)
            curveTo(11.7f, 20f, 12.5f, 19.2f, 12.5f, 18.2f)
            verticalLineTo(8f)
            horizontalLineTo(3.5f)
            verticalLineTo(18.2f)
            close()
            // Inner rectangular cutout (shows container color in the middle of the bin)
            moveTo(5.5f, 10f)
            horizontalLineTo(10.5f)
            verticalLineTo(18f)
            horizontalLineTo(5.5f)
            close()
        }
        // 3. Three Horizontal Side Sweep Lines (same color as the arrow in the Import Cloud)
        path(
            fill = SolidColor(CloudArrowBrown),
            pathFillType = PathFillType.NonZero
        ) {
            // Top bar
            moveTo(15f, 9f)
            horizontalLineTo(22f)
            verticalLineTo(11f)
            horizontalLineTo(15f)
            close()
            // Middle bar
            moveTo(15f, 13f)
            horizontalLineTo(20.5f)
            verticalLineTo(15f)
            horizontalLineTo(15f)
            close()
            // Bottom bar
            moveTo(15f, 17f)
            horizontalLineTo(19f)
            verticalLineTo(19f)
            horizontalLineTo(15f)
            close()
        }
    }.build()
}

@Composable
fun UnifiedTrashConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector = Icons.Default.DeleteOutline,
    dialogTestTag: String? = null
) {
    val resolvedIconTint = if (icon === OutlinedDeleteSweepIcon) Color.Unspecified else PrimaryText
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(24.dp))
                .let { mod -> if (dialogTestTag != null) mod.testTag(dialogTestTag) else mod },
            color = WarmSecondarySurface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(ElevatedSurface)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = resolvedIconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = PrimaryText,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    color = SecondaryText,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(CircleShape)
                            .background(TonalDarkBrown)
                            .m3PressScale(
                                shape = CircleShape,
                                onClick = onDismiss
                            )
                            .testTag("unified_dialog_cancel_button")
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(CircleShape)
                            .background(TonalDarkBrown)
                            .m3PressScale(
                                shape = CircleShape,
                                onClick = onConfirm
                            )
                            .testTag("unified_dialog_confirm_button")
                    ) {
                        Text(
                            text = "Confirm",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

