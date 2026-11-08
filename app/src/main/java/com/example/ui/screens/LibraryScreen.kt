package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.CallMerge
import com.example.data.AudioFormatUtils
import com.example.data.LibraryFilterChip
import com.example.data.LibrarySortDirection
import com.example.data.LibrarySortOption
import com.example.data.RecordingEntity
import com.example.data.StorageDestination
import com.example.ui.components.EditRecordingDialog
import com.example.ui.components.ImportCloudWithArrowIcon
import com.example.ui.components.MergeRecordingsDialog
import com.example.ui.components.UploadCloudWithArrowIcon
import com.example.ui.components.MiniWaveformThumbnail
import com.example.ui.components.OutlinedDeleteSweepIcon
import com.example.ui.components.UnifiedTrashConfirmDialog
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.M3MotionTokens
import com.example.ui.theme.NavActiveContent
import com.example.ui.theme.NavInactiveContent
import com.example.ui.theme.NavPillIndicator
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.OnPeachAccent
import com.example.ui.theme.OutlineWarm
import com.example.ui.theme.PeachAccent
import com.example.ui.theme.PrimaryText
import com.example.ui.theme.SecondaryText
import com.example.ui.theme.SuccessSage
import com.example.ui.theme.TabularMonoStyle
import com.example.ui.theme.WarmSecondarySurface
import com.example.ui.theme.m3PressScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    activeStorageTab: StorageDestination,
    recordings: List<RecordingEntity>,
    localRecordings: List<RecordingEntity> = emptyList(),
    cloudRecordings: List<RecordingEntity> = emptyList(),
    trashRecordings: List<RecordingEntity> = emptyList(),
    isLibraryLoaded: Boolean = true,
    localTotalCount: Int,
    cloudTotalCount: Int,
    trashTotalCount: Int = 0,
    driveFolderName: String,
    activeRecording: RecordingEntity?,
    isPlaying: Boolean,
    isImportingDriveFiles: Boolean = false,
    searchQuery: String,
    selectedFilter: LibraryFilterChip,
    sortOption: LibrarySortOption = LibrarySortOption.DATE,
    sortDirection: LibrarySortDirection = LibrarySortDirection.DESCENDING,
    selectedIds: Set<Long>,
    syncingIds: Map<Long, Float> = emptyMap(),
    downloadingIds: Map<Long, Float> = emptyMap(),
    mergeProgress: Int? = null,
    onSelectStorageTab: (StorageDestination) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectFilter: (LibraryFilterChip) -> Unit,
    onSelectSortOption: (LibrarySortOption) -> Unit = {},
    onSelectSortDirection: (LibrarySortDirection) -> Unit = {},
    onToggleSelectRecording: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onMergeSelected: (String) -> Unit = {},
    onRestoreSelected: () -> Unit = {},
    onEmptyTrash: () -> Unit = {},
    onImportAudio: (List<Uri>, StorageDestination) -> Unit,
    onPlayRecording: (RecordingEntity) -> Unit,
    onContinueRecording: (RecordingEntity) -> Unit,
    onRepairRecording: (RecordingEntity) -> Unit = {},
    onRestoreRecording: (RecordingEntity) -> Unit = {},
    onExportWithoutSilence: (RecordingEntity) -> Unit,
    onOpenConversion: (RecordingEntity) -> Unit,
    onSyncToCloud: (RecordingEntity) -> Unit,
    onDownloadFromCloud: (RecordingEntity) -> Unit = {},
    onCopyToCloud: (RecordingEntity) -> Unit = {},
    onRenameRecording: (RecordingEntity, String, String, String) -> Unit,
    onDeleteRecording: (RecordingEntity) -> Unit
) {
    var editingRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var pendingDeleteRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }
    var showMergeSelectedDialog by remember { mutableStateOf(false) }
    var showEmptyTrashConfirm by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val isMultiSelectActive = selectedIds.isNotEmpty()

    // Instant Synchronous State Evaluation per tab:
    // Pre-evaluates both Local and Cloud sorted/filtered lists synchronously in composition
    // so switching between Local and Cloud NEVER lags by a frame or flashes the empty state.
    val localTabItems = remember(localRecordings, searchQuery, selectedFilter, sortOption, sortDirection) {
        AudioFormatUtils.filterAndSortRecordings(
            sourceList = localRecordings,
            query = searchQuery,
            filter = selectedFilter,
            sortOption = sortOption,
            sortDirection = sortDirection
        )
    }

    val cloudTabItems = remember(cloudRecordings, searchQuery, selectedFilter, sortOption, sortDirection) {
        AudioFormatUtils.filterAndSortRecordings(
            sourceList = cloudRecordings.filter { it.filePath.isNotBlank() },
            query = searchQuery,
            filter = selectedFilter,
            sortOption = sortOption,
            sortDirection = sortDirection
        )
    }

    val trashTabItems = remember(trashRecordings, searchQuery, selectedFilter, sortOption, sortDirection) {
        AudioFormatUtils.filterAndSortRecordings(
            sourceList = trashRecordings,
            query = searchQuery,
            filter = selectedFilter,
            sortOption = sortOption,
            sortDirection = sortDirection
        )
    }

    val latestLocalTabItems by rememberUpdatedState(localTabItems)
    val latestCloudTabItems by rememberUpdatedState(cloudTabItems)
    val latestTrashTabItems by rememberUpdatedState(trashTabItems)
    val latestLocalTotalCount by rememberUpdatedState(localTotalCount)
    val latestCloudTotalCount by rememberUpdatedState(cloudTotalCount)
    val latestTrashTotalCount by rememberUpdatedState(trashTotalCount)

    // Dedicated LazyListState instances per storage tab to preserve exact viewport scroll positions
    val localListState = rememberLazyListState()
    val cloudListState = rememberLazyListState()
    val trashListState = rememberLazyListState()

    // Context-aware multi-document audio picker for importing audio files into active tab
    val importAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportAudio(uris, activeStorageTab)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("library_screen_root")
    ) {
        // 1. Header Row (Prominent headlineMedium "Library" title + Icon-Only Sort Button + Context-Aware Import Button)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Library",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp
                ),
                color = PrimaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (isMultiSelectActive) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ElevatedSurface)
                            .border(1.dp, OutlineWarm, CircleShape)
                            .m3PressScale(shape = CircleShape, onClick = onSelectAll)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SelectAll,
                            contentDescription = "Select all",
                            tint = PeachAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (activeStorageTab != StorageDestination.TRASH && selectedIds.size >= 2) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(ElevatedSurface)
                                .border(1.dp, OutlineWarm, CircleShape)
                                .m3PressScale(
                                    shape = CircleShape,
                                    onClick = { showMergeSelectedDialog = true }
                                )
                                .testTag("library_merge_selected_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.CallMerge,
                                contentDescription = "Merge selected recordings",
                                tint = PeachAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (activeStorageTab == StorageDestination.TRASH) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(ElevatedSurface)
                                .border(1.dp, OutlineWarm, CircleShape)
                                .m3PressScale(shape = CircleShape, onClick = onRestoreSelected)
                                .testTag("library_restore_selected_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Restore,
                                contentDescription = "Restore selected",
                                tint = PeachAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ElevatedSurface)
                            .border(1.dp, OutlineWarm, CircleShape)
                            .m3PressScale(shape = CircleShape, onClick = { showDeleteSelectedConfirm = true })
                            .testTag("library_delete_selected_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = if (activeStorageTab == StorageDestination.TRASH) "Delete permanently" else "Move selected to Trash",
                            tint = PeachAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ElevatedSurface)
                            .border(1.dp, OutlineWarm, CircleShape)
                            .m3PressScale(shape = CircleShape, onClick = onClearSelection)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear selection",
                            tint = PeachAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else if (activeStorageTab == StorageDestination.TRASH) {
                val isTrashEmpty = latestTrashTotalCount == 0
                val emptyTrashContainerColor = if (isTrashEmpty) WarmSecondarySurface else ElevatedSurface
                val emptyTrashBorderColor = if (isTrashEmpty) OutlineWarm.copy(alpha = 0.5f) else OutlineWarm
                val emptyTrashIconTint = if (isTrashEmpty) SecondaryText.copy(alpha = 0.45f) else PeachAccent

                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(emptyTrashContainerColor)
                        .border(1.dp, emptyTrashBorderColor, CircleShape)
                        .m3PressScale(
                            shape = CircleShape,
                            enabled = !isTrashEmpty,
                            onClick = {
                                if (!isTrashEmpty) {
                                    showEmptyTrashConfirm = true
                                }
                            }
                        )
                        .testTag("library_empty_trash_button")
                ) {
                    Icon(
                        imageVector = OutlinedDeleteSweepIcon,
                        contentDescription = "Empty Trash",
                        tint = emptyTrashIconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Icon-Only Sort Dropdown Button (Strictly official Sort / SwapVert icon, no text label)
                    Box {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(ElevatedSurface)
                                .border(1.dp, OutlineWarm, CircleShape)
                                .m3PressScale(
                                    shape = CircleShape,
                                    onClick = { sortMenuExpanded = true }
                                )
                                .testTag("library_sort_icon_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "Sort recordings",
                                tint = PeachAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                            shape = RoundedCornerShape(12.dp),
                            containerColor = ElevatedSurface
                        ) {
                            // Sort Criteria Section
                            LibrarySortOption.entries.forEach { option ->
                                val isSelected = sortOption == option
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.SwapVert,
                                            contentDescription = null,
                                            tint = PeachAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    text = {
                                        Text(
                                            text = option.label,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (isSelected) PeachAccent else PrimaryText,
                                            maxLines = 1
                                        )
                                    },
                                    trailingIcon = if (isSelected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Active sort criterion",
                                                tint = PeachAccent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        onSelectSortOption(option)
                                        sortMenuExpanded = false
                                    },
                                    modifier = Modifier.testTag("library_sort_option_${option.id.lowercase()}")
                                )
                            }

                            HorizontalDivider(
                                color = OutlineWarm,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            // Sort Direction Section (Ascending ↑ / Descending ↓)
                            LibrarySortDirection.entries.forEach { direction ->
                                val isSelected = sortDirection == direction
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (direction == LibrarySortDirection.ASCENDING) {
                                                Icons.Default.ArrowUpward
                                            } else {
                                                Icons.Default.ArrowDownward
                                            },
                                            contentDescription = null,
                                            tint = PeachAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    text = {
                                        Text(
                                            text = direction.label,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (isSelected) PeachAccent else PrimaryText,
                                            maxLines = 1
                                        )
                                    },
                                    trailingIcon = if (isSelected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Active sort direction",
                                                tint = PeachAccent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        onSelectSortDirection(direction)
                                        sortMenuExpanded = false
                                    },
                                    modifier = Modifier.testTag("library_sort_direction_${direction.id.lowercase()}")
                                )
                            }
                        }
                    }

                    // Context-Aware Icon-Only Import Button:
                    // When Cloud tab: Cloud-import icon (no text)
                    // When Local tab: Device-folder import icon (no text)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ElevatedSurface)
                            .border(1.dp, OutlineWarm, CircleShape)
                            .m3PressScale(
                                shape = CircleShape,
                                enabled = !isImportingDriveFiles,
                                onClick = {
                                    importAudioLauncher.launch(arrayOf("audio/*"))
                                }
                            )
                            .testTag("library_import_icon_button")
                    ) {
                        if (isImportingDriveFiles) {
                            CircularProgressIndicator(
                                color = PeachAccent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (activeStorageTab == StorageDestination.CLOUD) {
                                    ImportCloudWithArrowIcon
                                } else {
                                    Icons.Default.FolderOpen
                                },
                                contentDescription = if (activeStorageTab == StorageDestination.CLOUD) {
                                    "Import from Google Drive"
                                } else {
                                    "Import from Device Storage"
                                },
                                tint = if (activeStorageTab == StorageDestination.CLOUD) {
                                    Color.Unspecified
                                } else {
                                    PeachAccent
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Material 3 Triple-Tab Switcher: Local vs. Cloud vs. Trash
        LibraryStorageTabSwitcher(
            activeTab = activeStorageTab,
            localCount = localTotalCount,
            cloudCount = cloudTotalCount,
            trashCount = trashTotalCount,
            mergeProgress = mergeProgress,
            onSelectTab = onSelectStorageTab
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Borderless 16.dp Rounded Search Bar (Contextual to active tab)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = {
                Text(
                    text = when (activeStorageTab) {
                        StorageDestination.LOCAL -> "Search local device recordings…"
                        StorageDestination.CLOUD -> "Search Google Drive cloud recordings…"
                        StorageDestination.TRASH -> "Search recordings in Trash…"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = PeachAccent
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear search",
                            tint = SecondaryText
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = WarmSecondarySurface,
                unfocusedContainerColor = WarmSecondarySurface,
                focusedTextColor = PrimaryText,
                unfocusedTextColor = PrimaryText,
                focusedPlaceholderColor = SecondaryText,
                unfocusedPlaceholderColor = SecondaryText
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("library_search_bar")
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Format Filter Chips styled with 8.dp corner radius (All, M4A, WAV, FLAC)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LibraryFilterChip.entries.forEach { chip ->
                val isSelected = selectedFilter == chip
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) PeachAccent else ElevatedSurface)
                        .m3PressScale(
                            shape = RoundedCornerShape(8.dp),
                            onClick = { onSelectFilter(chip) }
                        )
                        .padding(horizontal = 14.dp)
                        .testTag("library_filter_${chip.name.lowercase()}")
                ) {
                    Text(
                        text = chip.label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (isSelected) OnPeachAccent else PrimaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Isolated Content Pane with Material 3 Shared-Axis / Cross-Fade Transition
        AnimatedContent(
            targetState = activeStorageTab,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                val enter = slideInHorizontally(
                    animationSpec = tween(260, easing = M3MotionTokens.EmphasizedEasing),
                    initialOffsetX = { fullWidth -> if (forward) fullWidth / 6 else -fullWidth / 6 }
                ) + fadeIn(tween(240))
                val exit = slideOutHorizontally(
                    animationSpec = tween(240, easing = M3MotionTokens.EmphasizedEasing),
                    targetOffsetX = { fullWidth -> if (forward) -fullWidth / 6 else fullWidth / 6 }
                ) + fadeOut(tween(180))
                (enter togetherWith exit).using(SizeTransform(clip = false))
            },
            label = "library_storage_tab_content",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { tab ->
            val tabRecordings = when (tab) {
                StorageDestination.LOCAL -> latestLocalTabItems
                StorageDestination.CLOUD -> latestCloudTabItems
                StorageDestination.TRASH -> latestTrashTabItems
            }
            val rawTabTotalCount = when (tab) {
                StorageDestination.LOCAL -> latestLocalTotalCount
                StorageDestination.CLOUD -> latestCloudTotalCount
                StorageDestination.TRASH -> latestTrashTotalCount
            }
            val isUnfiltered = searchQuery.isBlank() && selectedFilter == LibraryFilterChip.ALL
            val shouldBlockEmptyState = !isLibraryLoaded || (isUnfiltered && rawTabTotalCount > 0)

            val currentListState = when (tab) {
                StorageDestination.LOCAL -> localListState
                StorageDestination.CLOUD -> cloudListState
                StorageDestination.TRASH -> trashListState
            }

            val selectionOrderMap = remember(selectedIds) {
                selectedIds.toList().mapIndexed { idx, id -> id to (idx + 1) }.toMap()
            }

            if (tabRecordings.isNotEmpty()) {
                LazyColumn(
                    state = currentListState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(items = tabRecordings, key = { it.id }) { item ->
                        val selectionNumber = selectionOrderMap[item.id]
                        val isSelected = selectionNumber != null
                        val isCurrentlyActive = activeRecording?.id == item.id
                        var menuExpanded by remember { mutableStateOf(false) }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    when {
                                        isSelected -> ElevatedSurface
                                        isCurrentlyActive -> ElevatedSurface
                                        else -> WarmSecondarySurface
                                    }
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (isMultiSelectActive) {
                                            onToggleSelectRecording(item.id)
                                        } else {
                                            onPlayRecording(item)
                                        }
                                    },
                                    onLongClick = {
                                        onToggleSelectRecording(item.id)
                                    }
                                )
                                .padding(14.dp)
                                .testTag("library_item_card_${item.id}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                AnimatedVisibility(visible = isMultiSelectActive) {
                                    IconButton(
                                        onClick = { onToggleSelectRecording(item.id) },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .testTag("library_item_select_badge_${item.id}")
                                    ) {
                                        if (selectionNumber != null) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(PeachAccent)
                                            ) {
                                                Text(
                                                    text = selectionNumber.toString(),
                                                    style = TabularMonoStyle.copy(
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    color = OnPeachAccent,
                                                    maxLines = 1
                                                )
                                            }
                                        } else {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .clip(CircleShape)
                                                    .border(
                                                        width = 1.8.dp,
                                                        color = SecondaryText,
                                                        shape = CircleShape
                                                    )
                                            ) {}
                                        }
                                    }
                                }

                                // Leading Element: Mini waveform thumbnail
                                MiniWaveformThumbnail(
                                    peaks = item.peakList,
                                    isActive = isCurrentlyActive && isPlaying
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                // Central Title & Metadata Block
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = PrimaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    val subtitleText = AudioFormatUtils.formatDurationCompact(item.durationMs)
                                    Text(
                                        text = subtitleText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SecondaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                // Trailing Controls Block
                                val isActivelyUploading = syncingIds.containsKey(item.id)
                                val isDownloadedToDevice = localRecordings.any { localRec ->
                                    (!item.cloudFileId.isNullOrBlank() && localRec.cloudFileId == item.cloudFileId) ||
                                        (item.filePath.isNotBlank() && localRec.filePath == item.filePath)
                                }
                                val isVerifiedUploaded = item.hasVerifiedCloudUpload

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Dynamic Cloud & Download Status Icon:
                                    // - Unuploaded Recordings: Orange cloud with upward arrow (UploadCloudWithArrowIcon in PeachAccent).
                                    // - Uploaded, Downloaded, or Drive-Retrieved Recordings: Green cloud with checkmark (CloudDone in SuccessSage).
                                    if (tab == StorageDestination.LOCAL) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(ElevatedSurface)
                                                .m3PressScale(
                                                    shape = CircleShape,
                                                    onClick = { onSyncToCloud(item) }
                                                )
                                                .testTag("quick_upload_to_cloud_${item.id}")
                                        ) {
                                            if (isActivelyUploading) {
                                                CircularProgressIndicator(
                                                    color = PeachAccent,
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = if (isVerifiedUploaded) {
                                                        Icons.Default.CloudDone
                                                    } else {
                                                        UploadCloudWithArrowIcon
                                                    },
                                                    contentDescription = if (isVerifiedUploaded) {
                                                        "Uploaded to Google Drive"
                                                    } else {
                                                        "Upload to Cloud"
                                                    },
                                                    tint = if (isVerifiedUploaded) {
                                                        SuccessSage
                                                    } else {
                                                        Color.Unspecified
                                                    },
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        val isActivelyDownloading = downloadingIds.containsKey(item.id)
                                        val isCloudSyncedOrDownloaded = isVerifiedUploaded || isDownloadedToDevice
                                        if (!isCloudSyncedOrDownloaded) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(ElevatedSurface)
                                                    .m3PressScale(
                                                        shape = CircleShape,
                                                        onClick = { onSyncToCloud(item) }
                                                    )
                                                    .testTag("quick_upload_to_cloud_${item.id}")
                                            ) {
                                                if (isActivelyUploading) {
                                                    CircularProgressIndicator(
                                                        color = PeachAccent,
                                                        strokeWidth = 2.dp,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = UploadCloudWithArrowIcon,
                                                        contentDescription = "Needs Re-upload to Cloud",
                                                        tint = Color.Unspecified,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        } else {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(ElevatedSurface)
                                                    .m3PressScale(
                                                        shape = CircleShape,
                                                        enabled = !isActivelyDownloading,
                                                        onClick = { onDownloadFromCloud(item) }
                                                    )
                                                    .testTag("download_cloud_recording_${item.id}")
                                            ) {
                                                if (isActivelyDownloading) {
                                                    CircularProgressIndicator(
                                                        color = PeachAccent,
                                                        strokeWidth = 2.dp,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Default.CloudDone,
                                                        contentDescription = "Synced & Downloaded Cloud Recording",
                                                        tint = SuccessSage,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(ObsidianBackground)
                                            .padding(horizontal = 7.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = item.format,
                                            style = TabularMonoStyle.copy(fontSize = 11.sp),
                                            color = PeachAccent,
                                            maxLines = 1
                                        )
                                    }

                                    Box {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(ElevatedSurface)
                                                .m3PressScale(
                                                    shape = CircleShape,
                                                    onClick = { menuExpanded = true }
                                                )
                                                .testTag("library_item_overflow_${item.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "Recording actions",
                                                tint = PeachAccent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = menuExpanded,
                                            onDismissRequest = { menuExpanded = false },
                                            shape = RoundedCornerShape(12.dp),
                                            containerColor = ElevatedSurface
                                        ) {
                                            DropdownMenuItem(
                                                leadingIcon = { Icon(Icons.Default.PlayArrow, null, tint = PeachAccent) },
                                                text = { Text("Play in Studio Deck", color = PrimaryText, maxLines = 1) },
                                                onClick = {
                                                    menuExpanded = false
                                                    onPlayRecording(item)
                                                }
                                            )
                                            if (tab == StorageDestination.TRASH) {
                                                DropdownMenuItem(
                                                    leadingIcon = { Icon(Icons.Default.Restore, null, tint = PeachAccent) },
                                                    text = { Text("Restore Recording", color = PrimaryText, maxLines = 1) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        onRestoreRecording(item)
                                                    },
                                                    modifier = Modifier.testTag("menu_restore_${item.id}")
                                                )
                                                DropdownMenuItem(
                                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = PeachAccent) },
                                                    text = { Text("Delete Permanently", color = PrimaryText, maxLines = 1) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        pendingDeleteRecording = item
                                                    },
                                                    modifier = Modifier.testTag("menu_delete_permanently_${item.id}")
                                                )
                                            } else {
                                                DropdownMenuItem(
                                                    leadingIcon = { Icon(Icons.Default.AddCircleOutline, null, tint = PeachAccent) },
                                                    text = { Text("Continue Recording", color = PrimaryText, maxLines = 1) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        onContinueRecording(item)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    modifier = Modifier.testTag("menu_repair_recording_${item.id}"),
                                                    leadingIcon = { Icon(Icons.Default.Build, null, tint = PeachAccent) },
                                                    text = { Text("Repair Recording", color = PrimaryText, maxLines = 1) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        onRepairRecording(item)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    leadingIcon = { Icon(Icons.Default.IosShare, null, tint = PeachAccent) },
                                                    text = { Text("Export Without Silence", color = PrimaryText, maxLines = 1) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        onExportWithoutSilence(item)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    leadingIcon = { Icon(Icons.Default.Transform, null, tint = PeachAccent) },
                                                    text = { Text("Convert Format…", color = PrimaryText, maxLines = 1) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        onOpenConversion(item)
                                                    }
                                                )
                                                if (tab == StorageDestination.LOCAL) {
                                                    DropdownMenuItem(
                                                        leadingIcon = {
                                                            Icon(
                                                                imageVector = if (isVerifiedUploaded) {
                                                                    Icons.Default.CloudDone
                                                                } else {
                                                                    UploadCloudWithArrowIcon
                                                                },
                                                                contentDescription = null,
                                                                tint = if (isVerifiedUploaded) SuccessSage else Color.Unspecified
                                                            )
                                                        },
                                                        text = {
                                                            Text(
                                                                text = if (isVerifiedUploaded) "Uploaded to Cloud" else "Upload to Cloud",
                                                                color = PrimaryText,
                                                                maxLines = 1
                                                            )
                                                        },
                                                        onClick = {
                                                            menuExpanded = false
                                                            onSyncToCloud(item)
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        leadingIcon = {
                                                            Icon(
                                                                imageVector = Icons.Default.ContentCopy,
                                                                contentDescription = null,
                                                                tint = PeachAccent
                                                            )
                                                        },
                                                        text = {
                                                            Text(
                                                                text = "Copy to Cloud",
                                                                color = PrimaryText,
                                                                maxLines = 1
                                                            )
                                                        },
                                                        onClick = {
                                                            menuExpanded = false
                                                            onCopyToCloud(item)
                                                        },
                                                        modifier = Modifier.testTag("menu_copy_to_cloud_${item.id}")
                                                    )
                                                } else {
                                                    val isCloudSyncedOrDownloaded = isVerifiedUploaded || isDownloadedToDevice
                                                    if (!isVerifiedUploaded) {
                                                        DropdownMenuItem(
                                                            leadingIcon = {
                                                                Icon(
                                                                    imageVector = UploadCloudWithArrowIcon,
                                                                    contentDescription = null,
                                                                    tint = Color.Unspecified
                                                                )
                                                            },
                                                            text = {
                                                                Text(
                                                                    text = "Upload to Cloud",
                                                                    color = PrimaryText,
                                                                    maxLines = 1
                                                                )
                                                            },
                                                            onClick = {
                                                                menuExpanded = false
                                                                onSyncToCloud(item)
                                                            }
                                                        )
                                                    }
                                                    DropdownMenuItem(
                                                        leadingIcon = {
                                                            Icon(
                                                                imageVector = if (isCloudSyncedOrDownloaded) {
                                                                    Icons.Default.CloudDone
                                                                } else {
                                                                    ImportCloudWithArrowIcon
                                                                },
                                                                contentDescription = null,
                                                                tint = if (isCloudSyncedOrDownloaded) SuccessSage else Color.Unspecified
                                                            )
                                                        },
                                                        text = {
                                                            Text(
                                                                text = if (isDownloadedToDevice) "Downloaded to Device" else "Download to Device",
                                                                color = PrimaryText,
                                                                maxLines = 1
                                                            )
                                                        },
                                                        onClick = {
                                                            menuExpanded = false
                                                            onDownloadFromCloud(item)
                                                        },
                                                        modifier = Modifier.testTag("menu_download_cloud_${item.id}")
                                                    )
                                                    DropdownMenuItem(
                                                        leadingIcon = {
                                                            Icon(
                                                                imageVector = Icons.Default.ContentCopy,
                                                                contentDescription = null,
                                                                tint = PeachAccent
                                                            )
                                                        },
                                                        text = {
                                                            Text(
                                                                text = "Copy to Local",
                                                                color = PrimaryText,
                                                                maxLines = 1
                                                            )
                                                        },
                                                        onClick = {
                                                            menuExpanded = false
                                                            onDownloadFromCloud(item)
                                                        },
                                                        modifier = Modifier.testTag("menu_copy_to_local_${item.id}")
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    leadingIcon = { Icon(Icons.Default.Edit, null, tint = PeachAccent) },
                                                    text = { Text("Edit Title & Notes", color = PrimaryText, maxLines = 1) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        editingRecording = item
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = PeachAccent) },
                                                    text = { Text("Move to Trash", color = PrimaryText, maxLines = 1) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        pendingDeleteRecording = item
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (!shouldBlockEmptyState) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(WarmSecondarySurface)
                        .padding(24.dp)
                        .testTag("library_empty_state_${tab.id.lowercase()}")
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (tab == StorageDestination.TRASH) Icons.Default.DeleteOutline else Icons.Default.GraphicEq,
                            contentDescription = "Empty state icon",
                            tint = PeachAccent,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = when (tab) {
                                StorageDestination.LOCAL -> "No local recordings"
                                StorageDestination.CLOUD -> "No cloud recordings"
                                StorageDestination.TRASH -> "Trash is Empty"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = PrimaryText,
                            maxLines = 1
                        )
                        if (tab == StorageDestination.TRASH) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Deleted recordings will appear here. You can restore them or empty the trash at any time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SecondaryText,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(WarmSecondarySurface)
                )
            }
        }
    }

    editingRecording?.let { target ->
        EditRecordingDialog(
            recording = target,
            onConfirm = { newTitle, newTags, newNotes ->
                onRenameRecording(target, newTitle, newTags, newNotes)
                editingRecording = null
            },
            onDismiss = { editingRecording = null }
        )
    }

    // Confirmation dialog for single recording deletion
    pendingDeleteRecording?.let { target ->
        val isTrash = activeStorageTab == StorageDestination.TRASH
        UnifiedTrashConfirmDialog(
            title = if (isTrash) "Delete Permanently?" else "Move to Trash?",
            message = if (isTrash) {
                "Are you sure you want to permanently delete '${target.title}'? This action cannot be undone."
            } else {
                "Move '${target.title}' to the Trash? You can restore it later at any time from the Trash tab."
            },
            confirmText = "Confirm",
            onConfirm = {
                onDeleteRecording(target)
                pendingDeleteRecording = null
            },
            onDismiss = { pendingDeleteRecording = null },
            icon = Icons.Default.DeleteOutline
        )
    }

    // Confirmation dialog for bulk deletion of selected recordings
    if (showDeleteSelectedConfirm) {
        val isTrash = activeStorageTab == StorageDestination.TRASH
        UnifiedTrashConfirmDialog(
            title = if (isTrash) "Delete Permanently?" else "Move to Trash?",
            message = if (isTrash) {
                "Are you sure you want to permanently delete ${selectedIds.size} selected recording(s)? This action cannot be undone."
            } else {
                "Move ${selectedIds.size} recording(s) to the Trash? You can restore them later from the Trash tab."
            },
            confirmText = "Confirm",
            onConfirm = {
                onDeleteSelected()
                showDeleteSelectedConfirm = false
            },
            onDismiss = { showDeleteSelectedConfirm = false },
            icon = Icons.Default.DeleteOutline
        )
    }

    // Confirmation dialog for emptying entire trash
    if (showEmptyTrashConfirm) {
        UnifiedTrashConfirmDialog(
            title = "Empty Trash?",
            message = "Are you sure you want to permanently delete all recordings in the Trash? This cannot be undone.",
            confirmText = "Confirm",
            onConfirm = {
                onEmptyTrash()
                showEmptyTrashConfirm = false
            },
            onDismiss = { showEmptyTrashConfirm = false },
            icon = OutlinedDeleteSweepIcon
        )
    }

    // Confirmation & naming dialog for merging 2 or more selected recordings
    if (showMergeSelectedDialog && selectedIds.size >= 2) {
        val defaultMergedTitle = remember(showMergeSelectedDialog) {
            val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
            "Merged Recording — $stamp"
        }
        MergeRecordingsDialog(
            selectedCount = selectedIds.size,
            defaultTitle = defaultMergedTitle,
            onConfirmMerge = { customTitle ->
                showMergeSelectedDialog = false
                onMergeSelected(customTitle)
            },
            onDismiss = { showMergeSelectedDialog = false }
        )
    }
}

@Composable
private fun LibraryStorageTabSwitcher(
    activeTab: StorageDestination,
    localCount: Int,
    cloudCount: Int,
    trashCount: Int,
    mergeProgress: Int? = null,
    onSelectTab: (StorageDestination) -> Unit
) {
    val tabs = StorageDestination.entries
    val selectedIndex = tabs.indexOf(activeTab).coerceAtLeast(0)
    val animatedMergeProgress by animateFloatAsState(
        targetValue = ((mergeProgress ?: 0).coerceIn(0, 100)) / 100f,
        animationSpec = tween(durationMillis = 150),
        label = "library_local_tab_merge_progress_anim"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(WarmSecondarySurface)
            .padding(4.dp)
            .testTag("library_storage_tab_switcher")
    ) {
        val tabWidth = maxWidth / tabs.size
        val indicatorOffsetX by animateDpAsState(
            targetValue = tabWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "library_tab_indicator_offset"
        )

        // Gliding Stadium Pill Indicator (#543D31)
        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationX = indicatorOffsetX.toPx()
                }
                .width(tabWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(NavPillIndicator)
        )

        Row(modifier = Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val isSelected = activeTab == tab
                val isLocalMerging = tab == StorageDestination.LOCAL && mergeProgress != null
                val count = when (tab) {
                    StorageDestination.LOCAL -> localCount
                    StorageDestination.CLOUD -> cloudCount
                    StorageDestination.TRASH -> trashCount
                }
                val contentTint by animateColorAsState(
                    targetValue = when {
                        isLocalMerging -> PeachAccent
                        isSelected -> NavActiveContent
                        else -> NavInactiveContent
                    },
                    animationSpec = tween(200),
                    label = "library_tab_tint_${tab.id}"
                )
                val interactionSource = remember(tab) { MutableInteractionSource() }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelectTab(tab) }
                        )
                        .testTag("library_storage_tab_${tab.id.lowercase()}")
                ) {
                    if (isLocalMerging) {
                        CircularProgressIndicator(
                            progress = { animatedMergeProgress },
                            color = PeachAccent,
                            trackColor = if (isSelected) ObsidianBackground else ElevatedSurface,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(16.dp)
                                .testTag("library_local_tab_merge_progress")
                        )
                    } else {
                        Icon(
                            imageVector = when (tab) {
                                StorageDestination.LOCAL -> Icons.Default.PhoneAndroid
                                StorageDestination.CLOUD -> Icons.Default.CloudDone
                                StorageDestination.TRASH -> Icons.Default.DeleteOutline
                            },
                            contentDescription = tab.shortTitle,
                            tint = contentTint,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isLocalMerging) "Merging" else tab.shortTitle,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (isSelected || isLocalMerging) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = contentTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!isLocalMerging) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isSelected) ObsidianBackground else ElevatedSurface)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = count.toString(),
                                style = TabularMonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                color = if (isSelected) PeachAccent else SecondaryText,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

