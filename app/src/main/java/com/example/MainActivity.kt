package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.data.RecordingEntity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.ExportWithoutSilenceDialog
import com.example.ui.components.FormatConversionSheet
import com.example.ui.components.PostRecordingSaveSheet
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.RecorderScreen
import com.example.ui.screens.SavedRecordingPlayerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StorageScreen
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.M3MotionTokens
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NavActiveContent
import com.example.ui.theme.NavBarActiveLabelStyle
import com.example.ui.theme.NavBarInactiveLabelStyle
import com.example.ui.theme.NavInactiveContent
import com.example.ui.theme.NavPillIndicator
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.PeachAccent
import com.example.ui.theme.PrimaryText
import com.example.viewmodel.AppDestination
import com.example.viewmodel.RecorderViewModel

class MainActivity : ComponentActivity() {
    private var latestIntentAction by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        latestIntentAction = intent?.action
        enableEdgeToEdge()
        configureMaxRefreshRate()
        setContent {
            val recorderViewModel: RecorderViewModel = viewModel()

            LaunchedEffect(latestIntentAction) {
                when (latestIntentAction) {
                    com.example.service.RecordingForegroundService.ACTION_USER_STOP_SAVE -> {
                        recorderViewModel.stopAndSaveImmediatelyFromNotification()
                        latestIntentAction = null
                    }
                    com.example.service.RecordingForegroundService.ACTION_OPEN_LAST_SAVED_RECORDING -> {
                        recorderViewModel.openLastSavedRecordingInPlayer()
                        latestIntentAction = null
                    }
                    com.example.service.RecordingForegroundService.ACTION_PROMPT_APPEND_SAVE -> {
                        recorderViewModel.requestAppendSaveConfirmation()
                        latestIntentAction = null
                    }
                }
            }

            MyApplicationTheme {
                AudioRecorderStudioApp(viewModel = recorderViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.example.service.RecordingIslandOverlayManager.onAppForegroundChanged(this, true)
    }

    override fun onResume() {
        super.onResume()
        com.example.service.RecordingIslandOverlayManager.onAppForegroundChanged(this, true)
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            com.example.service.RecordingIslandOverlayManager.onAppForegroundChanged(this, false)
        }
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        latestIntentAction = intent.action
    }

    private fun configureMaxRefreshRate() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }
                val modes = display?.supportedModes
                val maxMode = modes?.maxByOrNull { it.refreshRate }
                if (maxMode != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = maxMode.modeId
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        params.preferredRefreshRate = maxMode.refreshRate
                    }
                    window.attributes = params
                }
            } catch (_: Exception) {
                // Gracefully fallback on devices that don't support custom display mode switching
            }
        }
    }
}

@Composable
fun AudioRecorderStudioApp(viewModel: RecorderViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentDestination by viewModel.currentDestination.collectAsStateWithLifecycle()
    val openedPlayerRecording by viewModel.openedPlayerRecording.collectAsStateWithLifecycle()
    val pendingSaveSession by viewModel.pendingSaveSession.collectAsStateWithLifecycle()
    val conversionState by viewModel.conversionState.collectAsStateWithLifecycle()
    val silenceExportState by viewModel.silenceExportState.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val pendingDriveConsent by viewModel.pendingDriveConsent.collectAsStateWithLifecycle()
    val pendingDriveActivityIntent by viewModel.pendingDriveActivityIntent.collectAsStateWithLifecycle()
    val pendingDriveFolderPicker by viewModel.pendingDriveFolderPicker.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // Global native Document Tree Picker (OpenDocumentTree) for Google Drive storage integration
    val globalOpenDocumentTreeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.selectDriveDocumentTreeFolder(treeUri)
        } else {
            viewModel.cancelDriveFolderPicker()
        }
    }

    LaunchedEffect(pendingDriveFolderPicker) {
        if (pendingDriveFolderPicker != null) {
            try {
                globalOpenDocumentTreeLauncher.launch(null)
            } catch (_: Exception) {
                viewModel.cancelDriveFolderPicker()
            }
        }
    }

    val globalDriveActivityLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == android.app.Activity.RESULT_OK
        viewModel.handleDriveActivityIntentResult(context, granted, result.data)
    }

    val globalDriveConsentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val granted = result.resultCode == android.app.Activity.RESULT_OK
        viewModel.handleDriveConsentResult(context, granted, result.data)
    }

    LaunchedEffect(pendingDriveActivityIntent, currentDestination) {
        if (pendingDriveActivityIntent != null && currentDestination != AppDestination.STORAGE) {
            if (pendingDriveActivityIntent?.stepType == com.example.data.AuthIntentStepType.OAUTH_CONSENT) {
                // Prevent launching unverified OAuth consent screen that results in Error 403: access_denied
                viewModel.handleDriveActivityIntentResult(context, false, null)
                viewModel.requestDriveFolderPicker()
            } else {
                try {
                    globalDriveActivityLauncher.launch(pendingDriveActivityIntent!!.intent)
                } catch (_: Exception) {
                    viewModel.handleDriveActivityIntentResult(context, false, null)
                }
            }
        }
    }

    LaunchedEffect(pendingDriveConsent, currentDestination) {
        if (pendingDriveConsent != null && currentDestination != AppDestination.STORAGE) {
            // Prevent launching unverified OAuth consent sender
            viewModel.handleDriveConsentResult(context, false, null)
            viewModel.requestDriveFolderPicker()
        }
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    // Predictive back / BackHandler on secondary destinations returns to Recorder when player isn't open
    BackHandler(enabled = openedPlayerRecording == null && currentDestination != AppDestination.RECORDER) {
        viewModel.navigateTo(AppDestination.RECORDER)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
    ) {
        val isWideScreen = maxWidth >= 680.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = ObsidianBackground,
            snackbarHost = {
                if (openedPlayerRecording == null && currentDestination != AppDestination.RECORDER) {
                    StudioAnchoredStatusBanner(
                        snackbarHostState = snackbarHostState,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("studio_status_snackbar_host")
                    )
                }
            },
            bottomBar = {
                if (!isWideScreen && openedPlayerRecording == null) {
                    StudioBottomNavigationBar(
                        currentDestination = currentDestination,
                        onNavigate = { viewModel.navigateTo(it) }
                    )
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen && openedPlayerRecording == null) {
                    StudioNavigationRail(
                        currentDestination = currentDestination,
                        onNavigate = { viewModel.navigateTo(it) }
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Flagship Google Material 3 Fade-Through Destination Transitions (Zero layout shifts, zero size transform, 144Hz lock)
                    AnimatedContent(
                        targetState = currentDestination,
                        transitionSpec = {
                            val enterTransition = fadeIn(
                                animationSpec = tween(
                                    durationMillis = 210,
                                    delayMillis = 35,
                                    easing = M3MotionTokens.EmphasizedDecelerateEasing
                                )
                            ) + scaleIn(
                                initialScale = 0.98f,
                                animationSpec = tween(
                                    durationMillis = 210,
                                    delayMillis = 35,
                                    easing = M3MotionTokens.EmphasizedDecelerateEasing
                                )
                            )
                            val exitTransition = fadeOut(
                                animationSpec = tween(
                                    durationMillis = 90,
                                    easing = M3MotionTokens.EmphasizedAccelerateEasing
                                )
                            )
                            enterTransition togetherWith exitTransition
                        },
                        label = "main_destination_transition",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer()
                    ) { destination ->
                        when (destination) {
                            AppDestination.RECORDER -> {
                                RecorderDestination(
                                    viewModel = viewModel,
                                    onNavigateToSettings = { viewModel.navigateTo(AppDestination.SETTINGS) },
                                    statusBannerContent = {
                                        if (openedPlayerRecording == null) {
                                            StudioAnchoredStatusBanner(
                                                snackbarHostState = snackbarHostState,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 12.dp)
                                                    .testTag("studio_status_snackbar_host")
                                            )
                                        }
                                    }
                                )
                            }
                            AppDestination.LIBRARY -> {
                                LibraryDestination(viewModel = viewModel)
                            }
                            AppDestination.STORAGE -> {
                                StorageDestinationScreen(viewModel = viewModel)
                            }
                            AppDestination.SETTINGS -> {
                                SettingsDestination(viewModel = viewModel)
                            }
                        }
                    }

                    // Independent Saved Recording Player View with smooth entrance
                    androidx.compose.animation.AnimatedVisibility(
                        visible = openedPlayerRecording != null,
                        enter = fadeIn(tween(220, easing = M3MotionTokens.EmphasizedDecelerateEasing)) +
                            scaleIn(initialScale = 0.98f, animationSpec = tween(220, easing = M3MotionTokens.EmphasizedDecelerateEasing)),
                        exit = fadeOut(tween(140, easing = M3MotionTokens.EmphasizedAccelerateEasing)) +
                            scaleOut(targetScale = 0.98f, animationSpec = tween(140, easing = M3MotionTokens.EmphasizedAccelerateEasing))
                    ) {
                        openedPlayerRecording?.let { playerRec ->
                            PlayerDestination(
                                viewModel = viewModel,
                                recording = playerRec,
                                statusBannerContent = {
                                    StudioAnchoredStatusBanner(
                                        snackbarHostState = snackbarHostState,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 12.dp)
                                            .testTag("studio_status_snackbar_host")
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Post-recording Save Sheet
    pendingSaveSession?.let { pending ->
        PostRecordingSaveSheet(
            pending = pending,
            onSave = { title, skipSilenceOnSave ->
                viewModel.confirmSaveSession(title, skipSilenceOnSave)
            },
            onCancelKeepPaused = {
                viewModel.dismissAppendSaveConfirmDialog()
            },
            onDiscard = {
                viewModel.discardPendingSession()
            }
        )
    }

    // Export Without Silence Modal
    silenceExportState?.let { exportState ->
        ExportWithoutSilenceDialog(
            state = exportState,
            onConfirm = { viewModel.startConfirmedSilenceExport() },
            onDismiss = { viewModel.dismissSilenceExportModal() }
        )
    }

    // Format Conversion Sheet
    conversionState?.let { convState ->
        FormatConversionSheet(
            state = convState,
            onUpdateOptions = { fmt, sr, bd, ch, br ->
                viewModel.updateConversionOptions(fmt, sr, bd, ch, br)
            },
            onSelectExportFolder = { treeUri ->
                viewModel.selectExportFolderForConversion(context, treeUri)
            },
            onResetExportFolder = {
                viewModel.resetExportFolderForConversion()
            },
            onStartConversion = { viewModel.runFormatConversion() },
            onCancel = { viewModel.cancelFormatConversion() }
        )
    }
}

@Composable
private fun StudioAnchoredStatusBanner(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier
    ) { data ->
        Snackbar(
            shape = RoundedCornerShape(14.dp),
            containerColor = ElevatedSurface,
            contentColor = PrimaryText,
            actionContentColor = PeachAccent,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RecorderDestination(
    viewModel: RecorderViewModel,
    onNavigateToSettings: () -> Unit,
    statusBannerContent: @Composable () -> Unit = {}
) {
    val recordingStatus by viewModel.recordingStatus.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val appendTarget by viewModel.appendTarget.collectAsStateWithLifecycle()
    val mergeProgress by viewModel.mergeProgress.collectAsStateWithLifecycle()
    val showAppendSaveConfirmDialog by viewModel.showAppendSaveConfirmDialog.collectAsStateWithLifecycle()
    val liveBookmarks by viewModel.liveBookmarks.collectAsStateWithLifecycle()
    val elapsedMs by viewModel.audioEngine.elapsedMs.collectAsStateWithLifecycle()
    val liveDb by viewModel.audioEngine.liveDb.collectAsStateWithLifecycle()
    val livePeaks by viewModel.audioEngine.livePeaks.collectAsStateWithLifecycle()
    val activeAudioDeviceName by viewModel.activeAudioDeviceName.collectAsStateWithLifecycle()

    RecorderScreen(
        status = recordingStatus,
        settings = settings,
        elapsedMs = elapsedMs,
        liveDb = liveDb,
        livePeaks = livePeaks,
        liveBookmarks = liveBookmarks,
        appendTarget = appendTarget,
        mergeProgress = mergeProgress,
        activeAudioDeviceName = activeAudioDeviceName,
        onStartRecording = { viewModel.startRecording() },
        onPauseRecording = { viewModel.pauseRecording() },
        onResumeRecording = { viewModel.resumeRecording() },
        onStopRecording = { viewModel.stopAndPromptSave() },
        onAddBookmark = { viewModel.addLiveBookmark() },
        onResetRecording = { viewModel.resetActiveRecording() },
        onCancelAppendMode = { viewModel.cancelContinueRecordingMode() },
        showAppendSaveConfirmDialog = showAppendSaveConfirmDialog,
        onConfirmAppendSave = {
            viewModel.confirmSaveFromNotification(
                com.example.service.RecordingIslandOverlayManager.currentEditedRecordingTitle
            )
        },
        onDiscardAppendSave = { viewModel.cancelSaveFromNotification() },
        onDismissAppendSaveConfirmDialog = { viewModel.dismissAppendSaveConfirmDialog() },
        onToggleSkipSilence = { viewModel.toggleSkipSilence(it) },
        onNavigateToSettings = onNavigateToSettings,
        onRefreshAudioDevices = { viewModel.refreshConnectedAudioDevices() },
        onPostMessage = { viewModel.postSnackbar(it) },
        statusBannerContent = statusBannerContent
    )
}

@Composable
private fun LibraryDestination(viewModel: RecorderViewModel) {
    val activeLibraryStorageTab by viewModel.activeLibraryStorageTab.collectAsStateWithLifecycle()
    val filteredRecordings by viewModel.filteredRecordings.collectAsStateWithLifecycle()
    val localRecordings by viewModel.localRecordings.collectAsStateWithLifecycle()
    val cloudRecordings by viewModel.cloudRecordings.collectAsStateWithLifecycle()
    val trashRecordings by viewModel.trashRecordings.collectAsStateWithLifecycle()
    val trashTotalCount by viewModel.trashTotalCount.collectAsStateWithLifecycle()
    val isLibraryLoaded by viewModel.isLibraryLoaded.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val openedPlayerRecording by viewModel.openedPlayerRecording.collectAsStateWithLifecycle()
    val isPlaying by viewModel.audioEngine.isPlaying.collectAsStateWithLifecycle()
    val isImportingDriveFiles by viewModel.isImportingDriveFiles.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()
    val sortDirection by viewModel.sortDirection.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedRecordingIds.collectAsStateWithLifecycle()
    val cloudSyncingIds by viewModel.cloudSyncingIds.collectAsStateWithLifecycle()
    val downloadingCloudIds by viewModel.downloadingCloudIds.collectAsStateWithLifecycle()
    val mergeProgress by viewModel.mergeProgress.collectAsStateWithLifecycle()

    LibraryScreen(
        activeStorageTab = activeLibraryStorageTab,
        recordings = filteredRecordings,
        localRecordings = localRecordings,
        cloudRecordings = cloudRecordings,
        trashRecordings = trashRecordings,
        localTotalCount = localRecordings.size,
        cloudTotalCount = cloudRecordings.size,
        trashTotalCount = trashTotalCount,
        isLibraryLoaded = isLibraryLoaded,
        driveFolderName = settings.driveFolderName,
        activeRecording = openedPlayerRecording,
        isPlaying = isPlaying,
        isImportingDriveFiles = isImportingDriveFiles,
        searchQuery = searchQuery,
        selectedFilter = selectedFilter,
        sortOption = sortOption,
        sortDirection = sortDirection,
        selectedIds = selectedIds,
        syncingIds = cloudSyncingIds,
        downloadingIds = downloadingCloudIds,
        mergeProgress = mergeProgress,
        onSelectStorageTab = { viewModel.selectLibraryStorageTab(it) },
        onSearchQueryChange = { viewModel.updateSearchQuery(it) },
        onSelectFilter = { viewModel.selectFilterChip(it) },
        onSelectSortOption = { viewModel.selectSortOption(it) },
        onSelectSortDirection = { viewModel.selectSortDirection(it) },
        onToggleSelectRecording = { viewModel.toggleRecordingSelection(it) },
        onSelectAll = { viewModel.selectAllVisible() },
        onClearSelection = { viewModel.clearSelection() },
        onDeleteSelected = { viewModel.deleteSelectedRecordings() },
        onRestoreSelected = { viewModel.restoreSelectedRecordings() },
        onMergeSelected = { customTitle -> viewModel.mergeSelectedRecordings(customTitle) },
        onEmptyTrash = { viewModel.emptyTrash() },
        onImportAudio = { uris, targetLocation ->
            viewModel.importAudioFiles(uris, targetLocation)
        },
        onPlayRecording = { rec ->
            viewModel.openSavedRecordingPlayer(rec, autoPlay = true)
        },
        onContinueRecording = { rec ->
            viewModel.startContinueRecordingFlow(rec)
        },
        onRepairRecording = { rec ->
            viewModel.repairRecording(rec)
        },
        onRestoreRecording = { rec ->
            viewModel.restoreRecording(rec)
        },
        onExportWithoutSilence = { rec ->
            viewModel.exportRecordingWithoutSilence(rec)
        },
        onOpenConversion = { rec ->
            viewModel.openConversionSheet(rec)
        },
        onSyncToCloud = { rec ->
            viewModel.syncRecordingToCloud(rec, isManualTrigger = true)
        },
        onDownloadFromCloud = { rec ->
            viewModel.downloadCloudRecordingToLocal(rec)
        },
        onCopyToCloud = { rec ->
            viewModel.copyRecordingToCloud(rec)
        },
        onRenameRecording = { rec, title, tags, notes ->
            viewModel.renameRecording(rec, title, tags, notes)
        },
        onDeleteRecording = { rec ->
            viewModel.deleteSingleRecording(rec)
        }
    )
}

@Composable
private fun StorageDestinationScreen(viewModel: RecorderViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val localRecordings by viewModel.localRecordings.collectAsStateWithLifecycle()
    val cloudRecordings by viewModel.cloudRecordings.collectAsStateWithLifecycle()
    val driveFolders by viewModel.driveFolders.collectAsStateWithLifecycle()
    val currentBrowseFolder by viewModel.currentBrowseFolder.collectAsStateWithLifecycle()
    val folderBreadcrumbs by viewModel.folderBreadcrumbs.collectAsStateWithLifecycle()
    val localPhoneFolders by viewModel.localPhoneFolders.collectAsStateWithLifecycle()
    val currentLocalBrowseFolder by viewModel.currentLocalBrowseFolder.collectAsStateWithLifecycle()
    val localFolderBreadcrumbs by viewModel.localFolderBreadcrumbs.collectAsStateWithLifecycle()
    val isFolderLoading by viewModel.isFolderLoading.collectAsStateWithLifecycle()
    val driveQuota by viewModel.driveQuota.collectAsStateWithLifecycle()
    val isAuthenticatingDrive by viewModel.isAuthenticatingDrive.collectAsStateWithLifecycle()
    val isImportingDriveFiles by viewModel.isImportingDriveFiles.collectAsStateWithLifecycle()
    val pendingDriveConsent by viewModel.pendingDriveConsent.collectAsStateWithLifecycle()
    val pendingDriveActivityIntent by viewModel.pendingDriveActivityIntent.collectAsStateWithLifecycle()
    val cloudSyncingIds by viewModel.cloudSyncingIds.collectAsStateWithLifecycle()

    StorageScreen(
        settings = settings,
        localRecordings = localRecordings,
        cloudRecordings = cloudRecordings,
        driveFolders = driveFolders,
        currentBrowseFolder = currentBrowseFolder,
        folderBreadcrumbs = folderBreadcrumbs,
        localPhoneFolders = localPhoneFolders,
        currentLocalBrowseFolder = currentLocalBrowseFolder,
        localFolderBreadcrumbs = localFolderBreadcrumbs,
        isFolderLoading = isFolderLoading,
        driveQuota = driveQuota,
        isAuthenticatingDrive = isAuthenticatingDrive,
        isImportingDriveFiles = isImportingDriveFiles,
        pendingDriveConsent = pendingDriveConsent,
        pendingDriveActivityIntent = pendingDriveActivityIntent,
        syncingProgressMap = cloudSyncingIds,
        onSelectDefaultDestination = { dest, ctx ->
            viewModel.selectDefaultStorageDestination(dest, ctx)
        },
        onConnectDriveWithGoogle = { ctx ->
            viewModel.connectGoogleDrive(ctx)
        },
        onDriveConsentResult = { ctx, granted, resultIntent ->
            viewModel.handleDriveConsentResult(ctx, granted, resultIntent)
        },
        onDriveActivityIntentResult = { ctx, granted, resultIntent ->
            viewModel.handleDriveActivityIntentResult(ctx, granted, resultIntent)
        },
        onDisconnectDrive = { viewModel.disconnectGoogleDrive() },
        onToggleAutoSync = { enabled ->
            viewModel.toggleAutoSync(enabled)
        },
        onBrowseDriveFolder = { crumb ->
            viewModel.browseDriveFolder(crumb)
        },
        onCreateDriveFolder = { folderName, selectNow ->
            viewModel.createDriveFolder(folderName, selectNow)
        },
        onSelectTargetDriveFolder = { id, name, path ->
            viewModel.selectTargetDriveFolder(id, name, path)
        },
        onSelectDriveDocumentTree = { treeUri ->
            viewModel.selectDriveDocumentTreeFolder(treeUri)
        },
        onBrowseLocalFolder = { crumb ->
            viewModel.browseLocalPhoneFolder(crumb)
        },
        onCreateLocalFolder = { folderName, selectNow ->
            viewModel.createLocalPhoneFolder(folderName, selectNow)
        },
        onSelectTargetLocalFolder = { name, path ->
            viewModel.selectTargetLocalFolder(name, path)
        },
        onSelectLocalDocumentTree = { ctx, treeUri ->
            viewModel.selectLocalDocumentTreeFolder(ctx, treeUri)
        },
        onResetLocalFolderToDefault = { ctx ->
            viewModel.resetLocalFolderToDefault(ctx)
        },
        onRefreshDriveFolders = {
            viewModel.refreshDriveFoldersAndQuota()
        },
        onImportFromDrive = { uris ->
            viewModel.importAudioFilesFromDrive(uris)
        },
        onSyncRecordingToCloud = { viewModel.syncRecordingToCloud(it) },
        onSyncAllLocalToCloud = { viewModel.syncAllRecordingsToCloud() },
        onPlayRecording = { rec ->
            viewModel.openSavedRecordingPlayer(rec, autoPlay = true)
        },
        onPrepareExploreDriveFolder = { viewModel.prepareExploreDriveFolder() },
        onPrepareChooseDriveFolder = { viewModel.prepareChooseDriveFolder() },
        onPrepareExploreLocalFolder = { viewModel.prepareExploreLocalFolder() },
        onPrepareChooseLocalFolder = { viewModel.prepareChooseLocalFolder() }
    )
}

@Composable
private fun SettingsDestination(viewModel: RecorderViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    SettingsScreen(
        settings = settings,
        onUpdateSettings = { viewModel.updateSettings(it) },
        onToggleSmartSilenceMode = { viewModel.toggleSmartSilenceMode(it) },
        onToggleManualSilenceMode = { viewModel.toggleManualSilenceMode(it) },
        onUpdateSilenceParams = { db, minDur, pad ->
            viewModel.updateSilenceParameters(db, minDur, pad)
        },
        onResetSilenceDefaults = { viewModel.resetSilenceParametersToDefault() },
        onCleanLocalCache = { viewModel.cleanUpLocalCache() }
    )
}

@Composable
private fun PlayerDestination(
    viewModel: RecorderViewModel,
    recording: RecordingEntity,
    statusBannerContent: @Composable () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isPlaying by viewModel.audioEngine.isPlaying.collectAsStateWithLifecycle()
    val playbackPositionMs by viewModel.audioEngine.playbackPositionMs.collectAsStateWithLifecycle()
    val playbackDurationMs by viewModel.audioEngine.playbackDurationMs.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.audioEngine.playbackSpeed.collectAsStateWithLifecycle()
    val silenceSkippedSavedMs by viewModel.audioEngine.silenceSkippedSavedMs.collectAsStateWithLifecycle()
    val isSilenceJumping by viewModel.audioEngine.activeSilenceJumping.collectAsStateWithLifecycle()
    val activeSilenceMap by viewModel.audioEngine.activeSilenceMap.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().graphicsLayer()) {
        SavedRecordingPlayerScreen(
            recording = recording,
            settings = settings,
            isPlaying = isPlaying,
            playbackPositionMs = playbackPositionMs,
            playbackDurationMs = playbackDurationMs,
            playbackSpeed = playbackSpeed,
            silenceSkippedSavedMs = silenceSkippedSavedMs,
            isSilenceJumping = isSilenceJumping,
            activeSilenceMap = activeSilenceMap,
            onClosePlayer = { viewModel.closeSavedRecordingPlayer() },
            onTogglePlayPause = { viewModel.togglePlayPauseInPlayer() },
            onSeekToMs = { viewModel.seekPlaybackTo(it) },
            onSeekRelativeMs = { viewModel.seekPlaybackRelative(it) },
            onAddBookmark = { viewModel.addBookmarkToOpenedRecording() },
            onRemoveBookmark = { viewModel.removeBookmarkFromOpenedRecording(it) },
            onContinueRecording = { viewModel.startContinueRecordingFlow(it) },
            onSelectSpeed = { viewModel.setPlaybackSpeed(it) },
            onToggleSkipSilence = { viewModel.toggleSkipSilence(it) },
            onExportWithoutSilence = { viewModel.exportRecordingWithoutSilence(it) },
            onTrimRecording = { rec, startMs, endMs, overwrite ->
                viewModel.trimRecording(rec, startMs, endMs, overwrite)
            },
            onOpenExportConversion = { viewModel.openConversionSheet(it) },
            statusBannerContent = statusBannerContent
        )
    }
}

private fun destinationIcons(dest: AppDestination): Pair<ImageVector, ImageVector> = when (dest) {
    AppDestination.RECORDER -> Icons.Filled.Mic to Icons.Outlined.MicNone
    AppDestination.LIBRARY -> Icons.Filled.LibraryMusic to Icons.Outlined.LibraryMusic
    AppDestination.STORAGE -> Icons.Filled.Storage to Icons.Outlined.Storage
    AppDestination.SETTINGS -> Icons.Filled.Settings to Icons.Outlined.Settings
}

@Composable
fun StudioBottomNavigationBar(
    currentDestination: AppDestination,
    onNavigate: (AppDestination) -> Unit
) {
    val destinations = AppDestination.entries

    // Google Drive Material 3 Navigation Bar (4 Tabs: Recorder, Library, Storage, Settings):
    // - 80.dp height
    // - Seamless #14100D (ObsidianBackground) container matching main screen background with 0 dividing stroke/shadow
    // - Single 64x32.dp #543D31 (NavPillIndicator) stadium pill that glides horizontally via spring physics
    // - Fixed 4.dp vertical spacer strictly between indicator pill and single-line 12sp Google Sans Text label
    Surface(
        color = ObsidianBackground,
        shape = RectangleShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(80.dp)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 32.dp Icon & Gliding Pill Track
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                ) {
                    val slotWidth = maxWidth / destinations.size
                    val targetPillOffsetX = slotWidth * currentDestination.ordinal + (slotWidth - 64.dp) / 2f

                    val pillAnimatable = remember { Animatable(targetPillOffsetX.value) }
                    var isPillInitialized by remember { mutableStateOf(false) }

                    LaunchedEffect(targetPillOffsetX, maxWidth) {
                        if (maxWidth > 0.dp) {
                            val targetVal = targetPillOffsetX.value
                            if (!isPillInitialized) {
                                pillAnimatable.snapTo(targetVal)
                                isPillInitialized = true
                            } else {
                                pillAnimatable.animateTo(
                                    targetValue = targetVal,
                                    animationSpec = tween(
                                        durationMillis = 240,
                                        easing = M3MotionTokens.EmphasizedEasing
                                    )
                                )
                            }
                        }
                    }

                    // Single Active Tab Pill Indicator (64.dp x 32.dp CircleShape filled with flat #543D31)
                    // Rendered via graphicsLayer translationX on GPU RenderThread with smooth M3 EmphasizedEasing
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                translationX = pillAnimatable.value.dp.toPx()
                            }
                            .size(width = 64.dp, height = 32.dp)
                            .clip(CircleShape)
                            .background(NavPillIndicator)
                    )

                    // Destination Icons Row (Single static icon per tab with smooth tint transition - zero size shift or flickering glow)
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        destinations.forEach { dest ->
                            val isSelected = currentDestination == dest
                            val (filledIcon, outlinedIcon) = destinationIcons(dest)
                            val iconTint by animateColorAsState(
                                targetValue = if (isSelected) NavActiveContent else NavInactiveContent,
                                animationSpec = tween(
                                    durationMillis = 220,
                                    easing = M3MotionTokens.StandardEasing
                                ),
                                label = "nav_icon_tint_${dest.route}"
                            )

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                Icon(
                                    imageVector = if (isSelected) filledIcon else outlinedIcon,
                                    contentDescription = dest.label,
                                    tint = iconTint,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                // Fixed 4.dp vertical spacer strictly below the 32.dp indicator pill
                Spacer(modifier = Modifier.height(4.dp))

                // Destination Single-Line Labels Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    destinations.forEach { dest ->
                        val isSelected = currentDestination == dest
                        val labelTint by animateColorAsState(
                            targetValue = if (isSelected) NavActiveContent else NavInactiveContent,
                            animationSpec = tween(
                                durationMillis = 220,
                                easing = M3MotionTokens.StandardEasing
                            ),
                            label = "nav_label_tint_${dest.route}"
                        )

                        Text(
                            text = dest.label,
                            style = if (isSelected) NavBarActiveLabelStyle else NavBarInactiveLabelStyle,
                            color = labelTint,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Full-height transparent touch targets without duplicate ripple/ghost highlights
            Row(modifier = Modifier.fillMaxSize()) {
                destinations.forEach { dest ->
                    val interactionSource = remember(dest) { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onNavigate(dest) }
                            )
                            .testTag("nav_tab_${dest.route}")
                    )
                }
            }
        }
    }
}

@Composable
fun StudioNavigationRail(
    currentDestination: AppDestination,
    onNavigate: (AppDestination) -> Unit
) {
    NavigationRail(
        containerColor = ObsidianBackground,
        contentColor = PrimaryText,
        modifier = Modifier
            .fillMaxHeight()
            .graphicsLayer()
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        AppDestination.entries.forEach { dest ->
            val isSelected = currentDestination == dest
            val (filledIcon, outlinedIcon) = destinationIcons(dest)
            val contentTint = if (isSelected) NavActiveContent else NavInactiveContent
            val interactionSource = remember(dest) { MutableInteractionSource() }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(vertical = 8.dp, horizontal = 8.dp)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onNavigate(dest) }
                    )
                    .testTag("nav_rail_${dest.route}")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 64.dp, height = 32.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) NavPillIndicator else Color.Transparent)
                ) {
                    Icon(
                        imageVector = if (isSelected) filledIcon else outlinedIcon,
                        contentDescription = dest.label,
                        tint = contentTint,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = dest.label,
                    style = if (isSelected) NavBarActiveLabelStyle else NavBarInactiveLabelStyle,
                    color = contentTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
