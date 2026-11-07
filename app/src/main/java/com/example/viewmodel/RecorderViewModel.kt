package com.example.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioStudioEngine
import com.example.audio.WavFileTools
import com.example.data.AppDatabase
import com.example.data.AudioFormatOption
import com.example.data.AudioFormatUtils
import com.example.data.AuthIntentStepType
import com.example.data.AuthenticatedGoogleProfile
import com.example.data.DriveFolderItem
import com.example.data.DriveQuotaInfo
import com.example.data.GoogleDriveAuthStepResult
import com.example.data.GoogleDriveRestClient
import com.example.data.GoogleDriveUserRecoverableException
import com.example.data.LibraryFilterChip
import com.example.data.LibrarySortDirection
import com.example.data.LibrarySortOption
import com.example.data.RecorderSettings
import com.example.data.RecordingEntity
import com.example.data.RecordingRepository
import com.example.data.RecordingStatus
import com.example.data.SilenceInterval
import com.example.data.SilenceSkipConfig
import com.example.data.StorageDestination
import com.example.service.RecordingForegroundService
import com.example.ui.screens.FolderBreadcrumb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppDestination(val route: String, val label: String) {
    RECORDER("recorder", "Recorder"),
    LIBRARY("library", "Library"),
    STORAGE("storage", "Storage"),
    SETTINGS("settings", "Settings")
}

@Immutable
data class PendingSaveSession(
    val tempFile: File,
    val suggestedTitle: String,
    val durationMs: Long,
    val selectedFormat: AudioFormatOption,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val peaks: List<Float>,
    val silenceMap: List<SilenceInterval> = emptyList(),
    val bookmarks: List<Long> = emptyList()
)

@Immutable
data class ConversionJobState(
    val source: RecordingEntity,
    val targetFormat: AudioFormatOption = AudioFormatOption.M4A,
    val sampleRate: Int = 48000,
    val bitDepth: Int = 16,
    val channels: Int = 1,
    val bitrate: Int = 192000,
    val exportTreeUri: String? = null,
    val exportFolderName: String = "Default App Storage",
    val isRunning: Boolean = false,
    val progress: Float = 0f
)

@Immutable
data class SilenceExportState(
    val source: RecordingEntity,
    val isRunning: Boolean = true,
    val progress: Float = 0f,
    val completedTitle: String? = null
)

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    val repository = RecordingRepository(application, database.recordingDao())
    val driveClient = GoogleDriveRestClient(application)
    val audioEngine = AudioStudioEngine(application)

    private val _currentDestination = MutableStateFlow(AppDestination.RECORDER)
    val currentDestination: StateFlow<AppDestination> = _currentDestination.asStateFlow()

    private val _settings = MutableStateFlow(repository.loadSettings())
    val settings: StateFlow<RecorderSettings> = _settings.asStateFlow()

    private val _recordingStatus = MutableStateFlow(RecordingStatus.READY)
    val recordingStatus: StateFlow<RecordingStatus> = _recordingStatus.asStateFlow()

    // Bookmarks dropped during the current live recording session
    private val _liveBookmarks = MutableStateFlow<List<Long>>(emptyList())
    val liveBookmarks: StateFlow<List<Long>> = _liveBookmarks.asStateFlow()

    // Continue / Append target recording
    private val _appendTarget = MutableStateFlow<RecordingEntity?>(null)
    val appendTarget: StateFlow<RecordingEntity?> = _appendTarget.asStateFlow()

    // Merging progress percentage (e.g. 45 or null when not merging)
    private val _mergeProgress = MutableStateFlow<Int?>(null)
    val mergeProgress: StateFlow<Int?> = _mergeProgress.asStateFlow()

    // Confirmation popup when pressing Save while appending to an existing recording
    private val _showAppendSaveConfirmDialog = MutableStateFlow(false)
    val showAppendSaveConfirmDialog: StateFlow<Boolean> = _showAppendSaveConfirmDialog.asStateFlow()

    private val _lastSavedRecordingId = MutableStateFlow<Long?>(null)
    val lastSavedRecordingId: StateFlow<Long?> = _lastSavedRecordingId.asStateFlow()

    // Post-recording Save Sheet
    private val _pendingSaveSession = MutableStateFlow<PendingSaveSession?>(null)
    val pendingSaveSession: StateFlow<PendingSaveSession?> = _pendingSaveSession.asStateFlow()

    // Dedicated Saved Recording Player View state (independent from Recorder tab)
    private val _openedPlayerRecording = MutableStateFlow<RecordingEntity?>(null)
    val openedPlayerRecording: StateFlow<RecordingEntity?> = _openedPlayerRecording.asStateFlow()

    // Library active storage tab (Local vs. Cloud — complete data isolation)
    private val _activeLibraryStorageTab = MutableStateFlow(_settings.value.defaultStorageDestination)
    val activeLibraryStorageTab: StateFlow<StorageDestination> = _activeLibraryStorageTab.asStateFlow()

    // Library search, filter & multi-select
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow(LibraryFilterChip.ALL)
    val selectedFilter: StateFlow<LibraryFilterChip> = _selectedFilter.asStateFlow()

    private val _sortOption = MutableStateFlow(LibrarySortOption.DATE)
    val sortOption: StateFlow<LibrarySortOption> = _sortOption.asStateFlow()

    private val _sortDirection = MutableStateFlow(LibrarySortDirection.DESCENDING)
    val sortDirection: StateFlow<LibrarySortDirection> = _sortDirection.asStateFlow()

    private val _isLibraryLoaded = MutableStateFlow(false)
    val isLibraryLoaded: StateFlow<Boolean> = _isLibraryLoaded.asStateFlow()

    private val _selectedRecordingIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedRecordingIds: StateFlow<Set<Long>> = _selectedRecordingIds.asStateFlow()

    // Conversion & Silence Export states
    private val _conversionState = MutableStateFlow<ConversionJobState?>(null)
    val conversionState: StateFlow<ConversionJobState?> = _conversionState.asStateFlow()
    private var conversionJob: Job? = null

    private val _silenceExportState = MutableStateFlow<SilenceExportState?>(null)
    val silenceExportState: StateFlow<SilenceExportState?> = _silenceExportState.asStateFlow()
    private var silenceExportJob: Job? = null

    // Cloud sync progress, manual cloud download progress & Google Drive folder browser state
    private val _cloudSyncingIds = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val cloudSyncingIds: StateFlow<Map<Long, Float>> = _cloudSyncingIds.asStateFlow()

    private val _downloadingCloudIds = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val downloadingCloudIds: StateFlow<Map<Long, Float>> = _downloadingCloudIds.asStateFlow()

    private val rootBreadcrumb = FolderBreadcrumb(id = "root", name = "My Drive", path = "/My Drive")
    private val _currentBrowseFolder = MutableStateFlow(rootBreadcrumb)
    val currentBrowseFolder: StateFlow<FolderBreadcrumb> = _currentBrowseFolder.asStateFlow()

    private val _folderBreadcrumbs = MutableStateFlow(listOf(rootBreadcrumb))
    val folderBreadcrumbs: StateFlow<List<FolderBreadcrumb>> = _folderBreadcrumbs.asStateFlow()

    private val _driveFolders = MutableStateFlow<List<DriveFolderItem>>(emptyList())
    val driveFolders: StateFlow<List<DriveFolderItem>> = _driveFolders.asStateFlow()

    // Local phone folders (strictly isolated from Google Drive folders)
    private val localRootBreadcrumb = FolderBreadcrumb(
        id = "local_root",
        name = "Internal Storage",
        path = "/Internal Storage"
    )
    private val _currentLocalBrowseFolder = MutableStateFlow(localRootBreadcrumb)
    val currentLocalBrowseFolder: StateFlow<FolderBreadcrumb> = _currentLocalBrowseFolder.asStateFlow()

    private val _localFolderBreadcrumbs = MutableStateFlow(listOf(localRootBreadcrumb))
    val localFolderBreadcrumbs: StateFlow<List<FolderBreadcrumb>> = _localFolderBreadcrumbs.asStateFlow()

    private val _localPhoneFolders = MutableStateFlow<List<DriveFolderItem>>(emptyList())
    val localPhoneFolders: StateFlow<List<DriveFolderItem>> = _localPhoneFolders.asStateFlow()

    private val _isFolderLoading = MutableStateFlow(false)
    val isFolderLoading: StateFlow<Boolean> = _isFolderLoading.asStateFlow()

    private val _driveQuota = MutableStateFlow(DriveQuotaInfo())
    val driveQuota: StateFlow<DriveQuotaInfo> = _driveQuota.asStateFlow()

    private val _isAuthenticatingDrive = MutableStateFlow(false)
    val isAuthenticatingDrive: StateFlow<Boolean> = _isAuthenticatingDrive.asStateFlow()

    private val _isImportingDriveFiles = MutableStateFlow(false)
    val isImportingDriveFiles: StateFlow<Boolean> = _isImportingDriveFiles.asStateFlow()

    private val _pendingDriveConsent = MutableStateFlow<GoogleDriveAuthStepResult.NeedsConsentResolution?>(null)
    val pendingDriveConsent: StateFlow<GoogleDriveAuthStepResult.NeedsConsentResolution?> = _pendingDriveConsent.asStateFlow()

    private val _pendingDriveActivityIntent = MutableStateFlow<GoogleDriveAuthStepResult.NeedsActivityIntentResolution?>(null)
    val pendingDriveActivityIntent: StateFlow<GoogleDriveAuthStepResult.NeedsActivityIntentResolution?> = _pendingDriveActivityIntent.asStateFlow()

    private val _pendingDriveFolderPicker = MutableStateFlow<RecordingEntity?>(null)
    val pendingDriveFolderPicker: StateFlow<RecordingEntity?> = _pendingDriveFolderPicker.asStateFlow()

    fun requestDriveFolderPicker(recording: RecordingEntity? = null) {
        _pendingDriveFolderPicker.value = recording
    }

    fun cancelDriveFolderPicker() {
        _pendingDriveFolderPicker.value = null
    }

    private var pendingSetCloudDefaultAfterAuth: Boolean = false
    private var pendingUploadAfterAuth: RecordingEntity? = null

    // Non-intrusive toast/snackbar message
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    val allRecordings: StateFlow<List<RecordingEntity>> = repository.allRecordings
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Strictly isolated Room DB flows for Local vs. Cloud tabs (SharingStarted.Eagerly for instant zero-delay tab access)
    val localRecordings: StateFlow<List<RecordingEntity>> = repository.localRecordings
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val cloudRecordings: StateFlow<List<RecordingEntity>> = repository.cloudRecordings
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val trashRecordings: StateFlow<List<RecordingEntity>> = repository.trashRecordings
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val trashTotalCount: StateFlow<Int> = repository.trashCount
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val activeAudioDeviceName: StateFlow<String> = audioEngine.activeAudioDeviceName

    val filteredRecordings: StateFlow<List<RecordingEntity>> = combine(
        combine(localRecordings, cloudRecordings, trashRecordings, _activeLibraryStorageTab) { localList, cloudList, trashList, storageTab ->
            when (storageTab) {
                StorageDestination.LOCAL -> localList
                StorageDestination.CLOUD -> cloudList
                StorageDestination.TRASH -> trashList
            }
        },
        _searchQuery,
        _selectedFilter,
        _sortOption,
        _sortDirection
    ) { sourceList, query, filter, sortOpt, sortDir ->
        AudioFormatUtils.filterAndSortRecordings(
            sourceList = sourceList,
            query = query,
            filter = filter,
            sortOption = sortOpt,
            sortDirection = sortDir
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val externalPauseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RecordingForegroundService.ACTION_EXTERNAL_PAUSE,
                RecordingForegroundService.ACTION_USER_PAUSE -> {
                    if (_recordingStatus.value == RecordingStatus.RECORDING) {
                        pauseRecording()
                    }
                }
                RecordingForegroundService.ACTION_USER_RESUME -> {
                    if (_recordingStatus.value == RecordingStatus.PAUSED) {
                        resumeRecording()
                    }
                }
                RecordingForegroundService.ACTION_USER_STOP_SAVE -> {
                    if (_recordingStatus.value == RecordingStatus.RECORDING ||
                        _recordingStatus.value == RecordingStatus.PAUSED
                    ) {
                        stopAndSaveImmediatelyFromNotification()
                    }
                }
                RecordingForegroundService.ACTION_USER_CONFIRM_APPEND_SAVE -> {
                    confirmSaveFromNotification()
                }
                RecordingForegroundService.ACTION_USER_CANCEL_APPEND_SAVE -> {
                    cancelSaveFromNotification()
                }
                RecordingForegroundService.ACTION_USER_BOOKMARK -> {
                    if (_recordingStatus.value == RecordingStatus.RECORDING ||
                        _recordingStatus.value == RecordingStatus.PAUSED
                    ) {
                        addLiveBookmark()
                    }
                }
                RecordingForegroundService.ACTION_USER_TOGGLE_SKIP_SILENCE -> {
                    if (_recordingStatus.value == RecordingStatus.RECORDING ||
                        _recordingStatus.value == RecordingStatus.PAUSED
                    ) {
                        toggleSkipSilenceFromNotification()
                    }
                }
                RecordingForegroundService.ACTION_USER_DISCARD -> {
                    if (_recordingStatus.value == RecordingStatus.RECORDING ||
                        _recordingStatus.value == RecordingStatus.PAUSED
                    ) {
                        resetActiveRecording()
                    }
                }
                RecordingForegroundService.ACTION_USER_PLAYBACK_BOOKMARK -> {
                    addBookmarkToOpenedRecording()
                }
                RecordingForegroundService.ACTION_USER_PLAYBACK_PREV -> {
                    playPreviousInPlayer()
                }
                RecordingForegroundService.ACTION_USER_PLAYBACK_PLAY_PAUSE -> {
                    togglePlayPauseInPlayer()
                }
                RecordingForegroundService.ACTION_USER_PLAYBACK_NEXT -> {
                    playNextInPlayer()
                }
                RecordingForegroundService.ACTION_USER_PLAYBACK_CONTINUE_REC -> {
                    startContinueRecordingFromNotification()
                }
            }
        }
    }

    init {
        audioEngine.updateSilenceSkipConfig(_settings.value.silenceConfig)
        audioEngine.setPlaybackSpeed(_settings.value.defaultPlaybackSpeed)

        viewModelScope.launch {
            repository.removePreinstalledSamplesIfPresent()
            repository.purgeMockCloudDataAndResetFalseSyncStates()
            repository.autoRepairCorruptedRecordings()
            refreshLocalPhoneFolders()
            refreshDriveFoldersAndQuota()

            // Keep openedPlayerRecording synced if updated in Room DB, and run Auto-Sync if enabled and authenticated
            var initialAutoSyncTriggered = false
            repository.allRecordings.collect { list ->
                _isLibraryLoaded.value = true
                val opened = _openedPlayerRecording.value
                if (opened != null) {
                    val refreshed = list.find { it.id == opened.id }
                    if (refreshed != null) {
                        _openedPlayerRecording.value = refreshed
                    } else {
                        closeSavedRecordingPlayer()
                    }
                }

                if (!initialAutoSyncTriggered &&
                    _settings.value.driveAutoSync &&
                    isDriveSessionReadyForUpload()
                ) {
                    initialAutoSyncTriggered = true
                    autoSyncUnsyncedRecordings()
                }
            }
        }

        // Real-time synchronization of playback notification with AudioStudioEngine
        viewModelScope.launch {
            combine(
                _openedPlayerRecording,
                audioEngine.isPlaying
            ) { openedRec, isPlaying ->
                openedRec to isPlaying
            }.collect { (rec, isPlaying) ->
                if (rec != null && _recordingStatus.value == RecordingStatus.READY) {
                    updatePlaybackForegroundServiceNotification(
                        title = rec.title,
                        isPlaying = isPlaying,
                        positionMs = audioEngine.playbackPositionMs.value,
                        durationMs = rec.durationMs
                    )
                }
            }
        }

        // Real-time synchronization of live recording notification waveform & MediaSession timer
        viewModelScope.launch(Dispatchers.Default) {
            var lastNotifiedBucket = -1L
            combine(
                audioEngine.elapsedMs,
                audioEngine.livePeaks
            ) { elapsed, peaks ->
                elapsed to peaks
            }.conflate().collect { (elapsed, peaks) ->
                val status = _recordingStatus.value
                if (status == RecordingStatus.RECORDING && !_showAppendSaveConfirmDialog.value) {
                    val baseOffset = _appendTarget.value?.durationMs ?: 0L
                    val totalElapsedMs = (baseOffset + elapsed).coerceAtLeast(0L)
                    val currentBucket = totalElapsedMs / 1000L
                    if (currentBucket != lastNotifiedBucket) {
                        lastNotifiedBucket = currentBucket
                        val title = _appendTarget.value?.title ?: "Recording"
                        updateForegroundServiceNotification(
                            title = title,
                            status = "",
                            isPaused = false,
                            elapsedMs = totalElapsedMs,
                            waveformPeaks = peaks.takeLast(8).toFloatArray()
                        )
                    }
                } else if (status != RecordingStatus.PAUSED) {
                    lastNotifiedBucket = -1L
                }
            }
        }

        try {
            val filter = IntentFilter().apply {
                addAction(RecordingForegroundService.ACTION_EXTERNAL_PAUSE)
                addAction(RecordingForegroundService.ACTION_USER_PAUSE)
                addAction(RecordingForegroundService.ACTION_USER_RESUME)
                addAction(RecordingForegroundService.ACTION_USER_STOP_SAVE)
                addAction(RecordingForegroundService.ACTION_USER_CONFIRM_APPEND_SAVE)
                addAction(RecordingForegroundService.ACTION_USER_CANCEL_APPEND_SAVE)
                addAction(RecordingForegroundService.ACTION_USER_BOOKMARK)
                addAction(RecordingForegroundService.ACTION_USER_TOGGLE_SKIP_SILENCE)
                addAction(RecordingForegroundService.ACTION_USER_DISCARD)
                addAction(RecordingForegroundService.ACTION_USER_PLAYBACK_BOOKMARK)
                addAction(RecordingForegroundService.ACTION_USER_PLAYBACK_PREV)
                addAction(RecordingForegroundService.ACTION_USER_PLAYBACK_PLAY_PAUSE)
                addAction(RecordingForegroundService.ACTION_USER_PLAYBACK_NEXT)
                addAction(RecordingForegroundService.ACTION_USER_PLAYBACK_CONTINUE_REC)
            }
            ContextCompat.registerReceiver(
                application,
                externalPauseReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}

        // Connect direct service callbacks for instant, background-safe execution
        RecordingForegroundService.activeStopSaveHandler = { stopAndSaveImmediatelyFromNotification() }
        RecordingForegroundService.activeConfirmAppendSaveHandler = { confirmSaveFromNotification() }
        RecordingForegroundService.activeConfirmAppendSaveWithTitleHandler = { customTitle -> confirmSaveFromNotification(customTitle) }
        RecordingForegroundService.activeCancelAppendSaveHandler = { cancelSaveFromNotification() }
        RecordingForegroundService.activeDiscardHandler = { cancelSaveFromNotification() }
        RecordingForegroundService.activePauseHandler = { pauseRecording() }
        RecordingForegroundService.activeResumeHandler = { resumeRecording() }
        RecordingForegroundService.activeBookmarkHandler = { addLiveBookmark() }
        RecordingForegroundService.activeSkipSilenceHandler = { toggleSkipSilenceFromNotification() }

        // Saved recording playback controls
        RecordingForegroundService.activePlaybackBookmarkHandler = { addBookmarkToOpenedRecording() }
        RecordingForegroundService.activePlaybackPrevHandler = { playPreviousInPlayer() }
        RecordingForegroundService.activePlaybackPlayPauseHandler = { togglePlayPauseInPlayer() }
        RecordingForegroundService.activePlaybackNextHandler = { playNextInPlayer() }
        RecordingForegroundService.activePlaybackContinueRecHandler = { startContinueRecordingFromNotification() }
        RecordingForegroundService.activePlaybackSeekHandler = { posMs -> audioEngine.seekTo(posMs) }
    }

    fun navigateTo(destination: AppDestination) {
        if (_openedPlayerRecording.value != null) {
            closeSavedRecordingPlayer()
        }
        _currentDestination.value = destination
        if (destination == AppDestination.STORAGE) {
            refreshLocalPhoneFolders()
            refreshDriveFoldersAndQuota()
        }
    }

    fun postSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    // --- Direct Live Recording Workflow ---

    fun startRecording() {
        if (_openedPlayerRecording.value != null) {
            closeSavedRecordingPlayer()
        }
        val currentSettings = _settings.value
        val appendTargetItem = _appendTarget.value
        val isM4aAppend = appendTargetItem != null && (
            appendTargetItem.format.equals("M4A", ignoreCase = true) ||
                appendTargetItem.filePath.endsWith(".m4a", ignoreCase = true)
            )
        val tempFileName = if (appendTargetItem != null) {
            val ext = if (isM4aAppend) "m4a" else "wav"
            "append_segment_${System.currentTimeMillis()}.$ext"
        } else {
            "session_${System.currentTimeMillis()}.${currentSettings.defaultFormat.extension}"
        }
        val tempFile = File(repository.getRecordingsDir(), tempFileName)

        val initialPeaks = appendTargetItem?.peakList ?: emptyList()
        val sampleRate = appendTargetItem?.sampleRate ?: currentSettings.sampleRate
        val channels = appendTargetItem?.channels ?: currentSettings.channels
        val baseM4aFile = if (isM4aAppend && appendTargetItem != null) {
            File(appendTargetItem.filePath)
        } else null

        _liveBookmarks.value = appendTargetItem?.bookmarkList ?: emptyList()
        _recordingStatus.value = RecordingStatus.RECORDING
        audioEngine.startRecordingSession(
            outputFile = tempFile,
            sampleRate = sampleRate,
            channels = channels,
            initialPeaks = initialPeaks,
            echoCancellationEnabled = currentSettings.echoCancellationEnabled,
            noiseSuppressionEnabled = currentSettings.noiseSuppressionEnabled,
            pauseExternalMediaOnRecord = currentSettings.pauseExternalMediaOnRecord,
            baseM4aForAppend = baseM4aFile
        )
        startForegroundServiceNotification(
            title = if (appendTargetItem != null) appendTargetItem.title else "Recording",
            status = ""
        )
    }

    fun toggleEchoCancellation(enabled: Boolean) {
        updateSettings { it.copy(echoCancellationEnabled = enabled) }
    }

    fun toggleNoiseSuppression(enabled: Boolean) {
        updateSettings { it.copy(noiseSuppressionEnabled = enabled) }
    }

    fun togglePauseExternalMediaOnRecord(enabled: Boolean) {
        updateSettings { it.copy(pauseExternalMediaOnRecord = enabled) }
    }

    fun pauseRecording() {
        if (_recordingStatus.value == RecordingStatus.RECORDING) {
            audioEngine.pauseRecording()
            _recordingStatus.value = RecordingStatus.PAUSED
            updateForegroundServiceNotification(
                title = "Paused",
                status = "",
                isPaused = true
            )
        }
    }

    fun resumeRecording() {
        if (_recordingStatus.value == RecordingStatus.PAUSED) {
            _showAppendSaveConfirmDialog.value = false
            audioEngine.resumeRecording()
            _recordingStatus.value = RecordingStatus.RECORDING
            updateForegroundServiceNotification(
                title = "Recording",
                status = "",
                isPaused = false,
                clearAppendConfirmation = true
            )
        }
    }

    fun addLiveBookmark() {
        if (_recordingStatus.value != RecordingStatus.RECORDING &&
            _recordingStatus.value != RecordingStatus.PAUSED
        ) return
        val baseOffset = _appendTarget.value?.durationMs ?: 0L
        val markMs = (baseOffset + audioEngine.elapsedMs.value).coerceAtLeast(0L)
        val currentBookmarks = _liveBookmarks.value
        if (currentBookmarks.any { kotlin.math.abs(it - markMs) < 120L }) return
        val updated = (currentBookmarks + markMs).distinct().sorted()
        _liveBookmarks.value = updated
        postSnackbar("Bookmark added at ${AudioFormatUtils.formatDurationCompact(markMs)}")
    }

    fun resetActiveRecording() {
        _recordingStatus.value = RecordingStatus.READY
        _showAppendSaveConfirmDialog.value = false
        _pendingSaveSession.value = null
        _liveBookmarks.value = emptyList()
        audioEngine.cancelAndResetRecording()
        stopForegroundServiceNotification()
        postSnackbar("Recording session reset")
    }

    fun requestAppendSaveConfirmation() {
        if (_recordingStatus.value == RecordingStatus.RECORDING) {
            audioEngine.pauseRecording()
            _recordingStatus.value = RecordingStatus.PAUSED
        }
        _openedPlayerRecording.value = null
        _currentDestination.value = AppDestination.RECORDER
        _showAppendSaveConfirmDialog.value = true
        showAppendSaveConfirmationNotification()
    }

    fun dismissAppendSaveConfirmDialog() {
        _showAppendSaveConfirmDialog.value = false
        syncForegroundNotificationIfRecording(clearAppendConfirmation = true)
    }

    fun confirmAppendAndMergeRecording(customTitle: String? = null) {
        _showAppendSaveConfirmDialog.value = false
        stopAndSaveImmediatelyFromNotification(forceMergeAppend = true, customTitle = customTitle)
    }

    fun confirmSaveFromNotification(customTitle: String? = null) {
        val resolvedTitle = customTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: com.example.service.RecordingIslandOverlayManager.currentEditedRecordingTitle.trim().takeIf { it.isNotEmpty() }
        val pending = _pendingSaveSession.value
        if (pending != null) {
            _showAppendSaveConfirmDialog.value = false
            val finalTitle = resolvedTitle ?: pending.suggestedTitle
            confirmSaveSession(finalTitle)
            return
        }
        if (_recordingStatus.value == RecordingStatus.RECORDING ||
            _recordingStatus.value == RecordingStatus.PAUSED
        ) {
            confirmAppendAndMergeRecording(resolvedTitle)
        }
    }

    fun cancelSaveFromNotification() {
        if (_appendTarget.value != null) {
            cancelContinueRecordingMode()
        } else if (_pendingSaveSession.value != null) {
            discardPendingSession()
        } else {
            resetActiveRecording()
        }
    }

    fun stopAndPromptSave() {
        if (_recordingStatus.value != RecordingStatus.RECORDING &&
            _recordingStatus.value != RecordingStatus.PAUSED
        ) return

        // If appending to an existing recording, pause and display the "Append to Recording?" confirmation popup
        if (_appendTarget.value != null) {
            requestAppendSaveConfirmation()
            return
        }

        viewModelScope.launch {
            _recordingStatus.value = RecordingStatus.PROCESSING
            showAppendSaveConfirmationNotification()
            val (sessionFile, elapsedDuration) = audioEngine.stopRecordingAndFinalize()
            val finalDuration = elapsedDuration.coerceAtLeast(1_000L)

            if (sessionFile == null || !sessionFile.exists()) {
                stopForegroundServiceNotification()
                _recordingStatus.value = RecordingStatus.ERROR
                postSnackbar("Unable to finalize recording buffer.")
                return@launch
            }

            val dateStamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
            val defaultTitle = "Recording — $dateStamp"
            val peaks = audioEngine.livePeaks.value.ifEmpty {
                WavFileTools.extractWaveformFromFile(sessionFile)
            }
            audioEngine.resetSessionDisplayState()
            val effSampleRate = _settings.value.sampleRate
            val effBitDepth = _settings.value.bitDepth
            val effChannels = _settings.value.channels
            val silenceMapIndex = withContext(Dispatchers.IO) {
                WavFileTools.generateSilenceMapIndex(
                    sourceFile = sessionFile,
                    durationMs = finalDuration,
                    peaks = peaks,
                    config = _settings.value.silenceConfig,
                    fallbackSampleRate = effSampleRate,
                    fallbackChannels = effChannels,
                    fallbackBitsPerSample = effBitDepth
                )
            }

            _pendingSaveSession.value = PendingSaveSession(
                tempFile = sessionFile,
                suggestedTitle = defaultTitle,
                durationMs = finalDuration,
                selectedFormat = _settings.value.defaultFormat,
                sampleRate = effSampleRate,
                bitDepth = effBitDepth,
                channels = effChannels,
                peaks = peaks,
                silenceMap = silenceMapIndex,
                bookmarks = _liveBookmarks.value
            )
            _recordingStatus.value = RecordingStatus.SAVING
        }
    }

    private val isStopSavingInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Triggered when the user taps the Stop and Save button from the notification / HyperIsland,
     * or when confirming the save dialog (`forceMergeAppend = true`).
     */
    fun stopAndSaveImmediatelyFromNotification(forceMergeAppend: Boolean = false, customTitle: String? = null) {
        if (!forceMergeAppend) {
            requestAppendSaveConfirmation()
            return
        }
        if (!isStopSavingInProgress.compareAndSet(false, true)) return
        if (_recordingStatus.value != RecordingStatus.RECORDING &&
            _recordingStatus.value != RecordingStatus.PAUSED
        ) {
            isStopSavingInProgress.set(false)
            return
        }

        viewModelScope.launch(NonCancellable) {
            try {
                _recordingStatus.value = RecordingStatus.PROCESSING
                // 1. Immediately halt and release physical microphone hardware (0ms delay)
                val (sessionFile, elapsedDuration) = audioEngine.stopRecordingAndFinalize()
                val finalDuration = elapsedDuration.coerceAtLeast(1_000L)

                if (sessionFile == null || !sessionFile.exists()) {
                    stopForegroundServiceNotification()
                    _recordingStatus.value = RecordingStatus.ERROR
                    postSnackbar("Unable to finalize recording buffer.")
                    return@launch
                }

            val appendTargetItem = _appendTarget.value
            val dateStamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
            val cleanTitle = customTitle?.takeIf { it.isNotBlank() } ?: appendTargetItem?.title ?: "Recording — $dateStamp"
            val peaks = audioEngine.livePeaks.value.ifEmpty {
                withContext(Dispatchers.IO) {
                    WavFileTools.extractWaveformFromFile(sessionFile)
                }
            }
            val effSampleRate = appendTargetItem?.sampleRate ?: _settings.value.sampleRate
            val effBitDepth = appendTargetItem?.bitDepth ?: _settings.value.bitDepth
            val effChannels = appendTargetItem?.channels ?: _settings.value.channels
            val capturedBookmarks = _liveBookmarks.value

            if (appendTargetItem != null) {
                _showAppendSaveConfirmDialog.value = false
                _appendTarget.value = null
                _liveBookmarks.value = emptyList()
                _pendingSaveSession.value = null
                _recordingStatus.value = RecordingStatus.READY
                isStopSavingInProgress.set(false)

                viewModelScope.launch(Dispatchers.IO + NonCancellable) {
                    try {
                        _mergeProgress.value = 0
                        val origFile = File(appendTargetItem.filePath)
                        val appendResult = WavFileTools.appendAudioFilesUniversal(
                            originalFile = origFile,
                            appendedPcmFile = sessionFile,
                            sampleRate = appendTargetItem.sampleRate,
                            channels = appendTargetItem.channels,
                            bitsPerSample = appendTargetItem.bitDepth,
                            onProgress = { ratio ->
                                _mergeProgress.value = (ratio * 100).toInt().coerceIn(0, 100)
                            }
                        )

                        if (appendResult != null && appendResult.success) {
                            _mergeProgress.value = 100
                            val finalFile = appendResult.finalFile
                            if (origFile.exists() && origFile.absolutePath != finalFile.absolutePath) {
                                try { origFile.delete() } catch (_: Exception) {}
                            }
                            val fallbackCombinedDuration = appendTargetItem.durationMs + finalDuration
                            val combinedDuration = if (appendResult.durationMs > appendTargetItem.durationMs) {
                                appendResult.durationMs
                            } else {
                                fallbackCombinedDuration
                            }
                            val updatedFileSize = finalFile.length()

                            // Immediately persist new duration, file size, and unsynced status so Local and Cloud
                            // Library lists refresh without delay.
                            val immediateEntity = appendTargetItem.copy(
                                title = cleanTitle,
                                filePath = finalFile.absolutePath,
                                format = appendResult.format,
                                durationMs = combinedDuration,
                                sizeBytes = updatedFileSize,
                                bookmarks = AudioFormatUtils.serializeBookmarks(capturedBookmarks),
                                appendedCount = appendTargetItem.appendedCount + 1,
                                storageLocation = appendTargetItem.storageLocation,
                                isCloudSynced = false,
                                cloudSyncedAt = null
                            )
                            repository.updateRecordingAndLinkedPaths(immediateEntity, origFile.absolutePath)
                            if (_openedPlayerRecording.value?.id == immediateEntity.id) {
                                _openedPlayerRecording.value = immediateEntity
                            }

                            val mergedPeaks = WavFileTools.extractWaveformFromFile(finalFile)
                            val mergedSilenceMap = WavFileTools.generateSilenceMapIndex(
                                sourceFile = finalFile,
                                durationMs = combinedDuration,
                                peaks = mergedPeaks,
                                config = _settings.value.silenceConfig,
                                fallbackSampleRate = appendTargetItem.sampleRate,
                                fallbackChannels = appendTargetItem.channels,
                                fallbackBitsPerSample = appendTargetItem.bitDepth
                            )
                            val updatedEntity = immediateEntity.copy(
                                sizeBytes = finalFile.length().takeIf { it > 0L } ?: updatedFileSize,
                                waveformPeaks = AudioFormatUtils.serializePeaks(mergedPeaks),
                                silenceMap = AudioFormatUtils.serializeSilenceMap(mergedSilenceMap)
                            )
                            repository.updateRecordingAndLinkedPaths(updatedEntity, origFile.absolutePath)
                            if (_openedPlayerRecording.value?.id == updatedEntity.id) {
                                _openedPlayerRecording.value = updatedEntity
                            }
                            _lastSavedRecordingId.value = updatedEntity.id
                            showSavedConfirmationNotification("Finished recording")
                            audioEngine.resetSessionDisplayState()

                            val shouldAutoUploadAppend = _settings.value.driveAutoSync &&
                                isDriveSessionReadyForUpload()
                            if (shouldAutoUploadAppend) {
                                syncRecordingToCloud(updatedEntity, isManualTrigger = false)
                            }
                            postSnackbar("Saved & appended +${AudioFormatUtils.formatDurationCompact(finalDuration)} to \"$cleanTitle\"")
                        } else {
                            stopForegroundServiceNotification()
                            saveOrphanedSegmentAsRecoveryRecording(
                                segmentFile = sessionFile,
                                baseTitle = cleanTitle,
                                durationMs = finalDuration,
                                sampleRate = effSampleRate,
                                channels = effChannels,
                                bitDepth = effBitDepth
                            )
                        }
                    } catch (e: Exception) {
                        saveOrphanedSegmentAsRecoveryRecording(
                            segmentFile = sessionFile,
                            baseTitle = cleanTitle,
                            durationMs = finalDuration,
                            sampleRate = effSampleRate,
                            channels = effChannels,
                            bitDepth = effBitDepth
                        )
                    } finally {
                        _mergeProgress.value = null
                    }
                }
            } else {
                _showAppendSaveConfirmDialog.value = false
                val format = _settings.value.defaultFormat
                val safeFileName = cleanTitle.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
                    .take(36)
                    .ifBlank { "Recording" }
                val targetLocalDir = repository.getRecordingsDir(
                    subFolderName = _settings.value.localFolderName,
                    folderPath = _settings.value.localFolderPath
                )
                targetLocalDir.mkdirs()
                val finalFile = File(
                    targetLocalDir,
                    "${safeFileName}_${System.currentTimeMillis() % 10000}.${format.extension}"
                )
                withContext(Dispatchers.IO) {
                    val renamed = sessionFile.renameTo(finalFile)
                    if (!renamed && sessionFile.exists()) {
                        try {
                            sessionFile.copyTo(finalFile, overwrite = true)
                            sessionFile.delete()
                        } catch (_: Exception) {}
                    }
                }
                val actualFile = if (finalFile.exists()) finalFile else sessionFile
                val headerInfo = withContext(Dispatchers.IO) {
                    WavFileTools.readWavHeaderInfo(
                        file = actualFile,
                        fallbackSampleRate = effSampleRate,
                        fallbackChannels = effChannels,
                        fallbackBitsPerSample = effBitDepth
                    )
                }
                val silenceIntervalsToSave = withContext(Dispatchers.IO) {
                    WavFileTools.generateSilenceMapIndex(
                        sourceFile = actualFile,
                        durationMs = finalDuration,
                        peaks = peaks,
                        config = _settings.value.silenceConfig,
                        fallbackSampleRate = headerInfo.sampleRate,
                        fallbackChannels = headerInfo.channels,
                        fallbackBitsPerSample = headerInfo.bitsPerSample
                    )
                }
                val shouldAutoUpload = _settings.value.driveAutoSync &&
                    isDriveSessionReadyForUpload()

                val entity = RecordingEntity(
                    title = cleanTitle,
                    filePath = actualFile.absolutePath,
                    durationMs = finalDuration,
                    format = format.id,
                    sampleRate = headerInfo.sampleRate,
                    bitDepth = headerInfo.bitsPerSample,
                    channels = headerInfo.channels,
                    bitrate = format.defaultBitrate,
                    sizeBytes = actualFile.length(),
                    waveformPeaks = AudioFormatUtils.serializePeaks(peaks),
                    bookmarks = AudioFormatUtils.serializeBookmarks(capturedBookmarks),
                    silenceMap = AudioFormatUtils.serializeSilenceMap(silenceIntervalsToSave),
                    storageLocation = StorageDestination.LOCAL.id,
                    isCloudSynced = false,
                    cloudFileId = null,
                    cloudSyncedAt = null,
                    driveFolderId = null,
                    driveFolderName = null,
                    tags = "Studio",
                    notes = ""
                )
                val id = repository.insertRecording(entity)
                val savedEntity = entity.copy(id = id)
                _lastSavedRecordingId.value = id
                // File and DB record are safely persisted; now transition Dynamic Island & Notification shade to Finished recording
                showSavedConfirmationNotification("Finished recording")
                audioEngine.resetSessionDisplayState()
                _liveBookmarks.value = emptyList()
                _pendingSaveSession.value = null
                _recordingStatus.value = RecordingStatus.READY
                _activeLibraryStorageTab.value = StorageDestination.LOCAL

                if (shouldAutoUpload) {
                    syncRecordingToCloud(savedEntity, isManualTrigger = false)
                } else {
                    postSnackbar("Recording \"$cleanTitle\" saved to Local Storage")
                }
            }
        } finally {
            isStopSavingInProgress.set(false)
        }
    }
}

    /**
     * Always saves the complete, original audio file without cutting or destroying silent audio data,
     * and stores the generated Silence Map Index (`silenceMap`) alongside the recording for dynamic
     * real-time playback skipping.
     */
    fun confirmSaveSession(
        title: String,
        skipSilenceOnSave: Boolean = false
    ) {
        val pending = _pendingSaveSession.value ?: return
        viewModelScope.launch {
            _recordingStatus.value = RecordingStatus.SAVING
            val cleanTitle = title.trim().ifBlank { pending.suggestedTitle }

            // Always preserve 100% of the original recording stream on save
            val effectiveFile = pending.tempFile
            val effectiveDurationMs = pending.durationMs
            val effectivePeaks = pending.peaks

            val format = pending.selectedFormat
            val safeFileName = cleanTitle.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
                .take(36)
                .ifBlank { "Recording" }
            val targetLocalDir = repository.getRecordingsDir(
                subFolderName = _settings.value.localFolderName,
                folderPath = _settings.value.localFolderPath
            )
            val finalFile = File(
                targetLocalDir,
                "${safeFileName}_${System.currentTimeMillis() % 10000}.${format.extension}"
            )
            withContext(Dispatchers.IO) {
                repository.moveFileSafely(effectiveFile, finalFile)
            }
            val actualFile = if (finalFile.exists()) finalFile else effectiveFile
            val headerInfo = withContext(Dispatchers.IO) {
                WavFileTools.readWavHeaderInfo(
                    file = actualFile,
                    fallbackSampleRate = pending.sampleRate,
                    fallbackChannels = pending.channels,
                    fallbackBitsPerSample = pending.bitDepth
                )
            }
            val silenceIntervalsToSave = pending.silenceMap.ifEmpty {
                withContext(Dispatchers.IO) {
                    WavFileTools.generateSilenceMapIndex(
                        sourceFile = actualFile,
                        durationMs = effectiveDurationMs,
                        peaks = effectivePeaks,
                        config = _settings.value.silenceConfig,
                        fallbackSampleRate = headerInfo.sampleRate,
                        fallbackChannels = headerInfo.channels,
                        fallbackBitsPerSample = headerInfo.bitsPerSample
                    )
                }
            }
            val shouldAutoUpload = _settings.value.driveAutoSync &&
                isDriveSessionReadyForUpload()

            // Strictly save new recordings to LOCAL first with unuploaded state (isCloudSynced = false, cloudFileId = null).
            // A recording is NEVER placed in the Cloud tab or marked green until a real Google Drive upload explicitly succeeds.
            val entity = RecordingEntity(
                title = cleanTitle,
                filePath = actualFile.absolutePath,
                durationMs = effectiveDurationMs,
                format = format.id,
                sampleRate = headerInfo.sampleRate,
                bitDepth = headerInfo.bitsPerSample,
                channels = headerInfo.channels,
                bitrate = format.defaultBitrate,
                sizeBytes = actualFile.length(),
                waveformPeaks = AudioFormatUtils.serializePeaks(effectivePeaks),
                bookmarks = AudioFormatUtils.serializeBookmarks(pending.bookmarks),
                silenceMap = AudioFormatUtils.serializeSilenceMap(silenceIntervalsToSave),
                storageLocation = StorageDestination.LOCAL.id,
                isCloudSynced = false,
                cloudFileId = null,
                cloudSyncedAt = null,
                driveFolderId = null,
                driveFolderName = null,
                tags = "Studio",
                notes = ""
            )
            val id = repository.insertRecording(entity)
            val savedEntity = entity.copy(id = id)
            _lastSavedRecordingId.value = id
            showSavedConfirmationNotification("Finished recording")
            audioEngine.resetSessionDisplayState()
            _showAppendSaveConfirmDialog.value = false
            _liveBookmarks.value = emptyList()
            _pendingSaveSession.value = null
            _recordingStatus.value = RecordingStatus.READY
            _activeLibraryStorageTab.value = StorageDestination.LOCAL

            if (shouldAutoUpload) {
                syncRecordingToCloud(savedEntity, isManualTrigger = false)
            } else {
                postSnackbar("Recording \"$cleanTitle\" saved to Local Storage")
            }
        }
    }

    fun discardPendingSession() {
        _recordingStatus.value = RecordingStatus.READY
        _showAppendSaveConfirmDialog.value = false
        val pending = _pendingSaveSession.value
        _pendingSaveSession.value = null
        _liveBookmarks.value = emptyList()
        if (pending != null) {
            try {
                pending.tempFile.delete()
            } catch (_: Exception) {}
        }
        audioEngine.cancelAndResetRecording()
        stopForegroundServiceNotification()
        postSnackbar("Recording discarded")
    }

    // --- Continue Recording (Append) ---

    fun startContinueRecordingFlow(recording: RecordingEntity) {
        audioEngine.stopPlayback()
        _openedPlayerRecording.value = null
        _showAppendSaveConfirmDialog.value = false
        _appendTarget.value = recording
        _liveBookmarks.value = recording.bookmarkList
        _currentDestination.value = AppDestination.RECORDER
        postSnackbar("Ready to continue recording into \"${recording.title}\"")
    }

    fun cancelContinueRecordingMode() {
        _recordingStatus.value = RecordingStatus.READY
        _showAppendSaveConfirmDialog.value = false
        _pendingSaveSession.value = null
        _appendTarget.value = null
        _liveBookmarks.value = emptyList()
        audioEngine.cancelAndResetRecording()
        stopForegroundServiceNotification()
        postSnackbar("Continuation canceled — original recording untouched")
    }

    fun openLastSavedRecordingInPlayer() {
        val targetId = _lastSavedRecordingId.value
        val list = allRecordings.value
        val rec = if (targetId != null) {
            list.find { it.id == targetId } ?: list.firstOrNull()
        } else {
            list.firstOrNull()
        }
        if (rec != null) {
            openSavedRecordingPlayer(rec, autoPlay = false)
        }
    }

    // --- Dedicated Saved Recording Player View ---

    fun openSavedRecordingPlayer(recording: RecordingEntity, autoPlay: Boolean = true) {
        val origFile = File(recording.filePath)
        val repairedFile = if (origFile.exists()) {
            WavFileTools.repairCorruptedAudioFileIfNeeded(origFile)
        } else {
            origFile
        }
        val isWav = WavFileTools.isRiffWaveFile(repairedFile)
        val effectiveRecording = if (repairedFile.absolutePath != recording.filePath || (isWav && recording.format != "WAV")) {
            val updated = recording.copy(
                filePath = repairedFile.absolutePath,
                format = if (isWav) "WAV" else recording.format,
                sizeBytes = repairedFile.length()
            )
            viewModelScope.launch { repository.updateRecording(updated) }
            updated
        } else {
            recording
        }
        _openedPlayerRecording.value = effectiveRecording
        val file = repairedFile
        val currentSilence = _settings.value.silenceConfig
        if (autoPlay && file.exists()) {
            audioEngine.loadAndPlayFile(
                file = file,
                peaks = effectiveRecording.peakList,
                startPositionMs = 0L,
                speed = audioEngine.playbackSpeed.value,
                silenceConfig = currentSilence,
                preindexedSilenceMap = if (currentSilence.smartAutoMode) {
                    effectiveRecording.silenceIntervals
                } else {
                    emptyList()
                }
            )
        }
        startPlaybackForegroundServiceNotification(
            title = effectiveRecording.title,
            isPlaying = autoPlay,
            positionMs = 0L,
            durationMs = recording.durationMs
        )
    }

    fun closeSavedRecordingPlayer() {
        audioEngine.stopPlayback()
        _openedPlayerRecording.value = null
        if (_recordingStatus.value == RecordingStatus.READY) {
            stopForegroundServiceNotification()
        }
    }

    fun togglePlayPauseInPlayer() {
        val rec = _openedPlayerRecording.value ?: return
        val currentSilence = _settings.value.silenceConfig
        audioEngine.togglePlayPause(
            currentFile = File(rec.filePath),
            peaks = rec.peakList,
            preindexedSilenceMap = if (currentSilence.smartAutoMode) {
                rec.silenceIntervals
            } else {
                emptyList()
            }
        )
        updatePlaybackForegroundServiceNotification(
            title = rec.title,
            isPlaying = audioEngine.isPlaying.value,
            positionMs = audioEngine.playbackPositionMs.value,
            durationMs = rec.durationMs
        )
    }

    fun playPreviousInPlayer() {
        val currentPos = audioEngine.playbackPositionMs.value
        if (currentPos > 3000L) {
            seekPlaybackRelative(-3000L)
        } else {
            val rec = _openedPlayerRecording.value ?: return
            val list = allRecordings.value
            val idx = list.indexOfFirst { it.id == rec.id }
            if (idx > 0) {
                openSavedRecordingPlayer(list[idx - 1], autoPlay = audioEngine.isPlaying.value)
            } else {
                seekPlaybackTo(0L)
            }
        }
    }

    fun playNextInPlayer() {
        val rec = _openedPlayerRecording.value ?: return
        val currentPos = audioEngine.playbackPositionMs.value
        val dur = audioEngine.playbackDurationMs.value
        if (dur > 0L && currentPos < dur - 3000L) {
            seekPlaybackRelative(3000L)
        } else {
            val list = allRecordings.value
            val idx = list.indexOfFirst { it.id == rec.id }
            if (idx in 0 until list.size - 1) {
                openSavedRecordingPlayer(list[idx + 1], autoPlay = audioEngine.isPlaying.value)
            } else {
                seekPlaybackRelative(3000L)
            }
        }
    }

    fun startContinueRecordingFromNotification() {
        val rec = _openedPlayerRecording.value ?: return
        startContinueRecordingFlow(rec)
        startRecording()
    }

    fun openLatestSavedRecordingFromNotification() {
        val latest = allRecordings.value.maxByOrNull { it.createdAt } ?: return
        openSavedRecordingPlayer(latest, autoPlay = false)
    }

    fun addBookmarkToOpenedRecording() {
        val rec = _openedPlayerRecording.value ?: return
        val currentPos = audioEngine.playbackPositionMs.value.coerceIn(0L, rec.durationMs)
        val updatedList = (rec.bookmarkList + currentPos).distinct().sorted()
        val updatedEntity = rec.copy(bookmarks = AudioFormatUtils.serializeBookmarks(updatedList))
        viewModelScope.launch {
            repository.updateRecording(updatedEntity)
            _openedPlayerRecording.value = updatedEntity
            postSnackbar("Bookmark added at ${AudioFormatUtils.formatDurationCompact(currentPos)}")
        }
    }

    fun removeBookmarkFromOpenedRecording(bookmarkMs: Long) {
        val rec = _openedPlayerRecording.value ?: return
        val updatedList = rec.bookmarkList.filter { it != bookmarkMs }
        val updatedEntity = rec.copy(bookmarks = AudioFormatUtils.serializeBookmarks(updatedList))
        viewModelScope.launch {
            repository.updateRecording(updatedEntity)
            _openedPlayerRecording.value = updatedEntity
        }
    }

    fun seekPlaybackTo(positionMs: Long) {
        audioEngine.seekTo(positionMs)
    }

    fun seekPlaybackRelative(deltaMs: Long) {
        audioEngine.seekRelative(deltaMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        audioEngine.setPlaybackSpeed(speed)
    }

    fun toggleSkipSilence(enabled: Boolean) {
        val currentSilence = _settings.value.silenceConfig
        // Master Settings Gate: Prevent activation if Silence Skip (Smart & Manual) is turned OFF in Settings
        if (!currentSilence.isConfiguredInSettings) {
            if (currentSilence.enabled) {
                val forcedOff = currentSilence.copy(enabled = false)
                val updatedSettings = _settings.value.copy(silenceConfig = forcedOff)
                _settings.value = updatedSettings
                repository.saveSettings(updatedSettings)
                audioEngine.updateSilenceSkipConfig(forcedOff)
                syncForegroundNotificationIfRecording()
            }
            return
        }
        val updatedSilence = currentSilence.copy(enabled = enabled)
        val updatedSettings = _settings.value.copy(silenceConfig = updatedSilence)
        _settings.value = updatedSettings
        repository.saveSettings(updatedSettings)
        audioEngine.updateSilenceSkipConfig(updatedSilence)
        syncForegroundNotificationIfRecording()
    }

    fun toggleSkipSilenceFromNotification() {
        val currentSilence = _settings.value.silenceConfig
        val nextEnabled = !currentSilence.isEffectivelyEnabled
        val updatedSilence = if (nextEnabled && !currentSilence.isConfiguredInSettings) {
            currentSilence.copy(
                smartAutoMode = true,
                enabled = true
            )
        } else {
            currentSilence.copy(enabled = nextEnabled)
        }
        val updatedSettings = _settings.value.copy(silenceConfig = updatedSilence)
        _settings.value = updatedSettings
        repository.saveSettings(updatedSettings)
        audioEngine.updateSilenceSkipConfig(updatedSilence)
        syncForegroundNotificationIfRecording()
    }

    fun toggleSmartSilenceMode(smartAutoMode: Boolean) {
        val currentSilence = _settings.value.silenceConfig
        val updatedSilence = if (smartAutoMode) {
            // Enabling Smart Silence Skip disables and dims Manual Silence Skip & manual controls
            currentSilence.copy(
                smartAutoMode = true,
                manualModeEnabled = false
            )
        } else {
            val stillConfigured = currentSilence.manualModeEnabled
            currentSilence.copy(
                smartAutoMode = false,
                enabled = if (stillConfigured) currentSilence.enabled else false
            )
        }
        val updatedSettings = _settings.value.copy(silenceConfig = updatedSilence)
        _settings.value = updatedSettings
        repository.saveSettings(updatedSettings)
        audioEngine.updateSilenceSkipConfig(updatedSilence)
        syncForegroundNotificationIfRecording()
    }

    fun toggleManualSilenceMode(manualModeEnabled: Boolean) {
        val currentSilence = _settings.value.silenceConfig
        if (currentSilence.smartAutoMode) return
        val updatedSilence = currentSilence.copy(
            manualModeEnabled = manualModeEnabled,
            smartAutoMode = false,
            enabled = if (manualModeEnabled) currentSilence.enabled else false
        )
        val updatedSettings = _settings.value.copy(silenceConfig = updatedSilence)
        _settings.value = updatedSettings
        repository.saveSettings(updatedSettings)
        audioEngine.updateSilenceSkipConfig(updatedSilence)
        syncForegroundNotificationIfRecording()
    }

    fun updateSilenceParameters(
        thresholdDb: Float,
        minSilenceDurationMs: Int,
        resumePaddingMs: Int
    ) {
        val currentSilence = _settings.value.silenceConfig
        if (currentSilence.smartAutoMode || !currentSilence.manualModeEnabled) return
        val updatedSilence = currentSilence.copy(
            thresholdDb = thresholdDb,
            minSilenceDurationMs = minSilenceDurationMs,
            resumePaddingMs = resumePaddingMs
        )
        val updatedSettings = _settings.value.copy(silenceConfig = updatedSilence)
        _settings.value = updatedSettings
        repository.saveSettings(updatedSettings)
        audioEngine.updateSilenceSkipConfig(updatedSilence)
    }

    fun resetSilenceParametersToDefault() {
        val currentSilence = _settings.value.silenceConfig
        if (currentSilence.smartAutoMode) return
        val defaultSilence = SilenceSkipConfig(
            enabled = currentSilence.enabled,
            smartAutoMode = currentSilence.smartAutoMode,
            manualModeEnabled = currentSilence.manualModeEnabled,
            thresholdDb = -35f,
            minSilenceDurationMs = 400,
            resumePaddingMs = 140
        )
        val updatedSettings = _settings.value.copy(silenceConfig = defaultSilence)
        _settings.value = updatedSettings
        repository.saveSettings(updatedSettings)
        audioEngine.updateSilenceSkipConfig(defaultSilence)
        postSnackbar("Parameters Reset")
    }

    /**
     * Trims the selected range [startMs, endMs] from [recording], keeping only the audio inside
     * the range. Supports either saving as a new copy (`overwriteCurrent = false`) or applying to
     * the current recording (`overwriteCurrent = true`).
     */
    fun trimRecording(
        recording: RecordingEntity,
        startMs: Long,
        endMs: Long,
        overwriteCurrent: Boolean
    ) {
        val safeStartMs = startMs.coerceIn(0L, (recording.durationMs - 100L).coerceAtLeast(0L))
        val safeEndMs = endMs.coerceIn(safeStartMs + 100L, recording.durationMs.coerceAtLeast(safeStartMs + 100L))

        viewModelScope.launch {
            try {
                val srcFile = File(recording.filePath)
                if (!srcFile.exists()) {
                    postSnackbar("Audio file not found on storage")
                    return@launch
                }

                if (overwriteCurrent) {
                    audioEngine.stopPlayback()
                }

                val targetFile = if (overwriteCurrent) {
                    srcFile
                } else {
                    val copyTitle = "${recording.title} (Trimmed)"
                    val safeName = copyTitle.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(36)
                    val ext = srcFile.extension.ifBlank {
                        AudioFormatOption.fromId(recording.format).extension
                    }
                    File(
                        srcFile.parentFile ?: repository.getRecordingsDir(),
                        "${safeName}_${System.currentTimeMillis() % 10000}.$ext"
                    )
                }

                val (trimmedDurationMs, trimmedPeaks) = withContext(Dispatchers.IO) {
                    WavFileTools.trimWavToRange(
                        sourceFile = srcFile,
                        targetFile = targetFile,
                        startMs = safeStartMs,
                        endMs = safeEndMs,
                        fallbackSampleRate = recording.sampleRate,
                        fallbackChannels = recording.channels,
                        fallbackBitsPerSample = recording.bitDepth
                    )
                }

                if (trimmedDurationMs <= 0L || !targetFile.exists()) {
                    postSnackbar("Unable to trim audio range")
                    return@launch
                }

                val updatedSilenceMap = withContext(Dispatchers.IO) {
                    WavFileTools.generateSilenceMapIndex(
                        sourceFile = targetFile,
                        durationMs = trimmedDurationMs,
                        peaks = trimmedPeaks,
                        config = _settings.value.silenceConfig,
                        fallbackSampleRate = recording.sampleRate,
                        fallbackChannels = recording.channels,
                        fallbackBitsPerSample = recording.bitDepth
                    )
                }

                val shiftedBookmarks = recording.bookmarkList
                    .filter { it in safeStartMs..safeEndMs }
                    .map { (it - safeStartMs).coerceIn(0L, trimmedDurationMs) }
                    .distinct()
                    .sorted()

                if (overwriteCurrent) {
                    val updatedEntity = recording.copy(
                        durationMs = trimmedDurationMs,
                        sizeBytes = targetFile.length(),
                        waveformPeaks = AudioFormatUtils.serializePeaks(trimmedPeaks),
                        bookmarks = AudioFormatUtils.serializeBookmarks(shiftedBookmarks),
                        silenceMap = AudioFormatUtils.serializeSilenceMap(updatedSilenceMap),
                        isCloudSynced = false,
                        cloudFileId = null,
                        cloudSyncedAt = null
                    )
                    repository.updateRecordingAndLinkedPaths(updatedEntity)
                    _openedPlayerRecording.value = updatedEntity
                    if (_settings.value.driveAutoSync &&
                        isDriveSessionReadyForUpload()
                    ) {
                        syncRecordingToCloud(updatedEntity, isManualTrigger = false)
                    }
                    val currentSilence = _settings.value.silenceConfig
                    audioEngine.loadAndPlayFile(
                        file = targetFile,
                        peaks = trimmedPeaks,
                        startPositionMs = 0L,
                        speed = audioEngine.playbackSpeed.value,
                        silenceConfig = currentSilence,
                        preindexedSilenceMap = if (currentSilence.smartAutoMode) updatedSilenceMap else emptyList()
                    )
                    postSnackbar("Applied trim to \"${recording.title}\" (${AudioFormatUtils.formatDurationCompact(trimmedDurationMs)})")
                } else {
                    val copyTitle = "${recording.title} (Trimmed)"
                    val newEntity = recording.copy(
                        id = 0,
                        title = copyTitle,
                        filePath = targetFile.absolutePath,
                        createdAt = System.currentTimeMillis(),
                        durationMs = trimmedDurationMs,
                        sizeBytes = targetFile.length(),
                        waveformPeaks = AudioFormatUtils.serializePeaks(trimmedPeaks),
                        bookmarks = AudioFormatUtils.serializeBookmarks(shiftedBookmarks),
                        silenceMap = AudioFormatUtils.serializeSilenceMap(updatedSilenceMap),
                        storageLocation = StorageDestination.LOCAL.id,
                        isCloudSynced = false,
                        cloudFileId = null,
                        cloudSyncedAt = null,
                        tags = "${recording.tags}, Trimmed".trim(',', ' ')
                    )
                    val newId = repository.insertRecording(newEntity)
                    if (_settings.value.driveAutoSync &&
                        isDriveSessionReadyForUpload()
                    ) {
                        syncRecordingToCloud(newEntity.copy(id = newId), isManualTrigger = false)
                    }
                    postSnackbar("Saved trimmed copy \"$copyTitle\" (${AudioFormatUtils.formatDurationCompact(trimmedDurationMs)})")
                }
            } catch (e: Exception) {
                postSnackbar("Trim failed: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun exportRecordingWithoutSilence(recording: RecordingEntity) {
        silenceExportJob?.cancel()
        _silenceExportState.value = SilenceExportState(
            source = recording,
            isRunning = true,
            progress = 0.05f
        )
        silenceExportJob = viewModelScope.launch {
            try {
                val srcFile = File(recording.filePath)
                val outTitle = "${recording.title} (No Silence)"
                val safeName = outTitle.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(36)
                val ext = srcFile.extension.ifBlank { AudioFormatOption.fromId(recording.format).extension }
                val outFile = File(
                    repository.getRecordingsDir(),
                    "${safeName}_${System.currentTimeMillis() % 10000}.$ext"
                )
                val cfg = _settings.value.silenceConfig
                val (effThreshDb, effMinDurMs, effPadMs) = cfg.resolveEffectiveParameters(recording.peakList)
                val (newDurationMs, newPeaks) = withContext(Dispatchers.IO) {
                    WavFileTools.exportWithoutSilence(
                        sourceFile = srcFile,
                        targetFile = outFile,
                        sampleRate = recording.sampleRate,
                        thresholdDb = effThreshDb,
                        minSilenceDurationMs = effMinDurMs,
                        resumePaddingMs = effPadMs,
                        channels = recording.channels,
                        bitsPerSample = recording.bitDepth,
                        onProgress = { prog ->
                            _silenceExportState.value = _silenceExportState.value?.copy(progress = prog)
                        }
                    )
                }
                val exportedHeader = withContext(Dispatchers.IO) {
                    WavFileTools.readWavHeaderInfo(
                        file = outFile,
                        fallbackSampleRate = recording.sampleRate,
                        fallbackChannels = recording.channels,
                        fallbackBitsPerSample = recording.bitDepth
                    )
                }

                val exportedEntity = RecordingEntity(
                    title = outTitle,
                    filePath = outFile.absolutePath,
                    durationMs = newDurationMs,
                    format = recording.format,
                    sampleRate = exportedHeader.sampleRate,
                    bitDepth = exportedHeader.bitsPerSample,
                    channels = exportedHeader.channels,
                    bitrate = recording.bitrate,
                    sizeBytes = outFile.length(),
                    waveformPeaks = AudioFormatUtils.serializePeaks(newPeaks),
                    storageLocation = StorageDestination.LOCAL.id,
                    isCloudSynced = false,
                    cloudFileId = null,
                    cloudSyncedAt = null,
                    driveFolderId = null,
                    driveFolderName = null,
                    tags = "${recording.tags}, Silence Stripped".trim(',', ' '),
                    notes = "Processed non-destructively (Threshold: ${effThreshDb.toInt()} dB, Min Duration: $effMinDurMs ms)."
                )
                val newId = repository.insertRecording(exportedEntity)
                if (_settings.value.driveAutoSync &&
                    isDriveSessionReadyForUpload()
                ) {
                    syncRecordingToCloud(exportedEntity.copy(id = newId), isManualTrigger = false)
                }
                _silenceExportState.value = _silenceExportState.value?.copy(
                    isRunning = false,
                    progress = 1.0f,
                    completedTitle = outTitle
                )
                postSnackbar("Exported \"$outTitle\"")
            } catch (e: CancellationException) {
                _silenceExportState.value = null
            } catch (e: Exception) {
                _silenceExportState.value = null
                postSnackbar("Silence export failed: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun dismissSilenceExportModal() {
        silenceExportJob?.cancel()
        _silenceExportState.value = null
    }

    // --- Library Management (Dual Tabs: Local vs. Cloud with Complete Data Isolation) ---

    fun selectLibraryStorageTab(tab: StorageDestination) {
        _selectedRecordingIds.value = emptySet()
        _activeLibraryStorageTab.value = tab
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectFilterChip(chip: LibraryFilterChip) {
        _selectedFilter.value = chip
    }

    fun selectSortOption(option: LibrarySortOption) {
        _sortOption.value = option
    }

    fun selectSortDirection(direction: LibrarySortDirection) {
        _sortDirection.value = direction
    }

    fun toggleRecordingSelection(id: Long) {
        val current = _selectedRecordingIds.value.toMutableSet()
        if (!current.add(id)) {
            current.remove(id)
        }
        _selectedRecordingIds.value = current
    }

    fun clearSelection() {
        _selectedRecordingIds.value = emptySet()
    }

    fun selectAllVisible() {
        _selectedRecordingIds.value = filteredRecordings.value.map { it.id }.toSet()
    }

    fun deleteSelectedRecordings() {
        val ids = _selectedRecordingIds.value
        if (ids.isEmpty()) return
        val currentTab = _activeLibraryStorageTab.value
        val items = if (currentTab == StorageDestination.TRASH) {
            trashRecordings.value.filter { it.id in ids }
        } else {
            allRecordings.value.filter { it.id in ids }
        }
        viewModelScope.launch {
            if (_openedPlayerRecording.value?.id in ids) {
                closeSavedRecordingPlayer()
            }
            if (currentTab == StorageDestination.TRASH) {
                repository.deletePermanently(items)
                postSnackbar("Permanently deleted ${items.size} recording(s)")
            } else {
                repository.moveToTrash(items)
                postSnackbar("Moved ${items.size} recording(s) to Trash")
            }
            _selectedRecordingIds.value = emptySet()
        }
    }

    fun deleteSingleRecording(recording: RecordingEntity) {
        viewModelScope.launch {
            if (_openedPlayerRecording.value?.id == recording.id) {
                closeSavedRecordingPlayer()
            }
            if (recording.isInTrash || _activeLibraryStorageTab.value == StorageDestination.TRASH) {
                repository.deletePermanently(recording)
                postSnackbar("Permanently deleted \"${recording.title}\"")
            } else {
                repository.moveToTrash(recording)
                postSnackbar("Moved \"${recording.title}\" to Trash")
            }
            _selectedRecordingIds.value = _selectedRecordingIds.value - recording.id
        }
    }

    fun restoreRecording(recording: RecordingEntity) {
        viewModelScope.launch {
            repository.restoreFromTrash(recording)
            _selectedRecordingIds.value = _selectedRecordingIds.value - recording.id
            postSnackbar("Restored \"${recording.title}\" to Library")
        }
    }

    fun restoreSelectedRecordings() {
        val ids = _selectedRecordingIds.value
        if (ids.isEmpty()) return
        val items = trashRecordings.value.filter { it.id in ids }
        viewModelScope.launch {
            repository.restoreFromTrash(items)
            _selectedRecordingIds.value = emptySet()
            postSnackbar("Restored ${items.size} recording(s) to Library")
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            val count = repository.emptyTrash()
            _selectedRecordingIds.value = emptySet()
            postSnackbar(if (count > 0) "Emptied $count recording(s) from Trash" else "Trash is empty")
        }
    }

    fun renameRecording(recording: RecordingEntity, newTitle: String, newTags: String, newNotes: String) {
        val clean = newTitle.trim().ifBlank { recording.title }
        viewModelScope.launch {
            val updated = recording.copy(
                title = clean,
                tags = newTags.trim().ifBlank { "Studio" },
                notes = newNotes.trim()
            )
            repository.updateRecording(updated)
            if (_openedPlayerRecording.value?.id == recording.id) {
                _openedPlayerRecording.value = updated
            }
            postSnackbar("Updated \"${updated.title}\"")
        }
    }

    // --- Format Conversion ---

    fun openConversionSheet(recording: RecordingEntity) {
        _conversionState.value = ConversionJobState(
            source = recording,
            targetFormat = when (recording.format) {
                "M4A" -> AudioFormatOption.WAV
                "WAV" -> AudioFormatOption.FLAC
                else -> AudioFormatOption.M4A
            },
            sampleRate = recording.sampleRate,
            bitDepth = recording.bitDepth,
            channels = recording.channels,
            bitrate = recording.bitrate
        )
    }

    fun updateConversionOptions(
        format: AudioFormatOption,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        bitrate: Int
    ) {
        _conversionState.value = _conversionState.value?.copy(
            targetFormat = format,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            bitrate = bitrate
        )
    }

    fun selectExportFolderForConversion(context: Context, treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    context.contentResolver.takePersistableUriPermission(treeUri, flags)
                } catch (_: Exception) {}

                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                val docName = docFile?.name
                val lastSegment = treeUri.lastPathSegment?.substringAfterLast(':')
                val folderName = when {
                    !docName.isNullOrBlank() -> docName
                    !lastSegment.isNullOrBlank() -> lastSegment
                    else -> "Selected Folder"
                }

                _conversionState.value = _conversionState.value?.copy(
                    exportTreeUri = treeUri.toString(),
                    exportFolderName = folderName
                )
            } catch (_: Exception) {}
        }
    }

    fun resetExportFolderForConversion() {
        _conversionState.value = _conversionState.value?.copy(
            exportTreeUri = null,
            exportFolderName = "Default App Storage"
        )
    }

    fun runFormatConversion() {
        val current = _conversionState.value ?: return
        if (current.isRunning) return

        conversionJob?.cancel()
        _conversionState.value = current.copy(isRunning = true, progress = 0.05f)
        conversionJob = viewModelScope.launch {
            val res = repository.convertRecordingFormat(
                source = current.source,
                targetFormat = current.targetFormat,
                targetSampleRate = current.sampleRate,
                targetBitDepth = current.bitDepth,
                targetChannels = current.channels,
                targetBitrate = current.bitrate,
                exportTreeUri = current.exportTreeUri,
                onProgress = { p ->
                    _conversionState.value = _conversionState.value?.copy(progress = p)
                }
            )
            res.fold(
                onSuccess = { converted ->
                    val withStorage = converted.copy(
                        storageLocation = StorageDestination.LOCAL.id,
                        isCloudSynced = false,
                        cloudFileId = null,
                        cloudSyncedAt = null,
                        driveFolderId = null,
                        driveFolderName = null
                    )
                    repository.updateRecording(withStorage)
                    if (_settings.value.driveAutoSync &&
                        isDriveSessionReadyForUpload()
                    ) {
                        syncRecordingToCloud(withStorage, isManualTrigger = false)
                    }
                    _conversionState.value = null
                    postSnackbar("Converted to ${converted.format} (${converted.sampleRate / 1000.0} kHz)")
                },
                onFailure = { err ->
                    _conversionState.value = null
                    postSnackbar("Conversion error: ${err.localizedMessage ?: "Failed"}")
                }
            )
        }
    }

    fun cancelFormatConversion() {
        conversionJob?.cancel()
        _conversionState.value = null
        postSnackbar("Format conversion canceled")
    }

    // --- Storage Destination & Real Google Drive REST API v3 Integration ---

    fun selectDefaultStorageDestination(
        destination: StorageDestination,
        activityContext: Context? = null
    ) {
        if (destination == StorageDestination.CLOUD &&
            (!_settings.value.driveConnected || _settings.value.driveAccountEmail.isBlank())
        ) {
            if (activityContext != null) {
                pendingSetCloudDefaultAfterAuth = true
                connectGoogleDrive(activityContext)
            } else {
                postSnackbar("Please connect your Google Drive account first to enable Cloud Storage.")
                _currentDestination.value = AppDestination.STORAGE
            }
            return
        }

        val updated = _settings.value.copy(defaultStorageDestination = destination)
        _settings.value = updated
        repository.saveSettings(updated)
        _activeLibraryStorageTab.value = destination
        if (destination == StorageDestination.LOCAL) {
            refreshLocalPhoneFolders()
        } else {
            refreshDriveFoldersAndQuota()
        }
        postSnackbar("Default storage set to ${destination.label}")
    }

    /**
     * Launches Android's native system account picker (`AccountManager.newChooseAccountIntent`
     * filtered for `"com.google"`) to connect or switch Google accounts without any Error 403 block.
     */
    fun connectGoogleDrive(activityContext: Context) {
        if (_isAuthenticatingDrive.value) return
        _isAuthenticatingDrive.value = true

        viewModelScope.launch {
            processDriveAuthStep(driveClient.launchInteractiveSignInAndDriveAuth(activityContext))
        }
    }

    private fun processDriveAuthStep(step: GoogleDriveAuthStepResult) {
        when (step) {
            is GoogleDriveAuthStepResult.Authenticated -> {
                applyAuthenticatedDriveSession(step.profile)
            }

            is GoogleDriveAuthStepResult.NeedsConsentResolution -> {
                _isAuthenticatingDrive.value = false
                _pendingDriveConsent.value = step
            }

            is GoogleDriveAuthStepResult.NeedsActivityIntentResolution -> {
                _isAuthenticatingDrive.value = false
                _pendingDriveActivityIntent.value = step
            }

            is GoogleDriveAuthStepResult.Cancelled -> {
                pendingSetCloudDefaultAfterAuth = false
                pendingUploadAfterAuth = null
                _isAuthenticatingDrive.value = false
                postSnackbar(step.message)
            }

            is GoogleDriveAuthStepResult.Error -> {
                pendingSetCloudDefaultAfterAuth = false
                pendingUploadAfterAuth = null
                _isAuthenticatingDrive.value = false
                postSnackbar(step.message)
            }
        }
    }

    fun handleDriveConsentResult(
        activityContext: Context,
        granted: Boolean,
        resultIntent: Intent?
    ) {
        val pending = _pendingDriveConsent.value
        _pendingDriveConsent.value = null

        if (pending == null) {
            pendingSetCloudDefaultAfterAuth = false
            _isAuthenticatingDrive.value = false
            return
        }

        _isAuthenticatingDrive.value = true
        viewModelScope.launch {
            val step = driveClient.completeDriveConsentFromIntent(
                activityContext = activityContext,
                resultIntent = if (granted) resultIntent else null,
                pendingEmail = pending.email,
                pendingDisplayName = pending.displayName,
                pendingPhotoUrl = pending.photoUrl
            )
            processDriveAuthStep(step)
        }
    }

    /**
     * Completes the native Android system account picker (`AccountManager.newChooseAccountIntent`) result.
     */
    fun handleDriveActivityIntentResult(
        activityContext: Context,
        granted: Boolean,
        resultIntent: Intent?
    ) {
        val pending = _pendingDriveActivityIntent.value
        _pendingDriveActivityIntent.value = null

        if (pending == null) {
            pendingSetCloudDefaultAfterAuth = false
            _isAuthenticatingDrive.value = false
            return
        }

        _isAuthenticatingDrive.value = true
        viewModelScope.launch {
            val step = driveClient.completeActivityIntentResolution(
                activityContext = activityContext,
                stepType = pending.stepType,
                granted = granted,
                resultIntent = resultIntent,
                pendingEmail = pending.email,
                pendingDisplayName = pending.displayName,
                pendingPhotoUrl = pending.photoUrl
            )
            processDriveAuthStep(step)
        }
    }

    private fun applyAuthenticatedDriveSession(profile: AuthenticatedGoogleProfile) {
        repository.storeAuthenticatedDriveSession(profile)
        val newDefaultDest = if (pendingSetCloudDefaultAfterAuth) {
            StorageDestination.CLOUD
        } else {
            _settings.value.defaultStorageDestination
        }
        val recordingToUpload = pendingUploadAfterAuth
        pendingSetCloudDefaultAfterAuth = false
        pendingUploadAfterAuth = null
        _isAuthenticatingDrive.value = false

        val updated = _settings.value.copy(
            driveConnected = true,
            driveAccountEmail = profile.email,
            driveAccountName = profile.displayName,
            driveAccountPhotoUrl = profile.photoUrl,
            driveAccessToken = profile.accessToken,
            defaultStorageDestination = newDefaultDest,
            lastCloudSyncMs = System.currentTimeMillis()
        )
        _settings.value = updated
        repository.saveSettings(updated)
        if (newDefaultDest == StorageDestination.CLOUD && recordingToUpload == null) {
            _activeLibraryStorageTab.value = StorageDestination.CLOUD
        }
        refreshDriveFoldersAndQuota()
        if (recordingToUpload != null) {
            syncRecordingToCloud(recordingToUpload, isManualTrigger = true)
        } else {
            postSnackbar("Connected to Google Drive (${profile.email})")
        }
    }

    /**
     * Clears the connected Google account, resets state, and returns to Local Device Storage.
     */
    fun disconnectGoogleDrive() {
        val activeToken = _settings.value.driveAccessToken
        pendingSetCloudDefaultAfterAuth = false
        _pendingDriveConsent.value = null
        _pendingDriveActivityIntent.value = null
        _isAuthenticatingDrive.value = false

        repository.disconnectCloudAccount()
        val updated = _settings.value.copy(
            driveConnected = false,
            driveAccountEmail = "",
            driveAccountName = "",
            driveAccountPhotoUrl = "",
            driveAccessToken = "",
            driveAutoSync = false,
            lastCloudSyncMs = 0L,
            defaultStorageDestination = StorageDestination.LOCAL
        )
        _settings.value = updated
        repository.saveSettings(updated)
        _driveFolders.value = emptyList()
        _driveQuota.value = DriveQuotaInfo()
        _currentBrowseFolder.value = rootBreadcrumb
        _folderBreadcrumbs.value = listOf(rootBreadcrumb)
        _activeLibraryStorageTab.value = StorageDestination.LOCAL

        viewModelScope.launch {
            driveClient.revokeAndClearDriveSession(getApplication(), activeToken)
        }
        postSnackbar("Disconnected from Google Drive")
    }

    /**
     * Handles a folder selected from Android's native `ActivityResultContracts.OpenDocumentTree`
     * integrated with Google Drive (`com.google.android.apps.docs.storage`).
     */
    fun selectDriveDocumentTreeFolder(treeUri: Uri) {
        val pendingUpload = _pendingDriveFolderPicker.value
        _pendingDriveFolderPicker.value = null
        viewModelScope.launch {
            _isFolderLoading.value = true
            val folderItem = driveClient.resolveSelectedTreeFolder(treeUri)
            val updated = _settings.value.copy(
                driveConnected = true,
                driveAccountName = _settings.value.driveAccountName.ifBlank { "Google Drive (Storage Access)" },
                driveFolderId = folderItem.id,
                driveFolderName = folderItem.name,
                driveFolderPath = folderItem.path,
                driveFolderUri = treeUri.toString()
            )
            _settings.value = updated
            repository.saveSettings(updated)
            val rootCrumb = FolderBreadcrumb(
                id = folderItem.id,
                name = folderItem.name,
                path = folderItem.path
            )
            _currentBrowseFolder.value = rootCrumb
            _folderBreadcrumbs.value = listOf(rootBreadcrumb, rootCrumb)
            refreshDriveFoldersAndQuota()
            _isFolderLoading.value = false
            postSnackbar("Selected Google Drive folder: ${folderItem.name}")

            if (pendingUpload != null) {
                syncRecordingToCloud(pendingUpload, isManualTrigger = true)
            }
        }
    }

    /**
     * Checks whether a real Google Drive account or SAF Drive DocumentTree URI is connected for uploads.
     */
    private fun isDriveSessionReadyForUpload(): Boolean {
        val current = _settings.value
        val hasSafUri = current.driveFolderId.startsWith("content://") ||
            current.driveFolderUri.startsWith("content://")
        val hasRealToken = current.driveConnected &&
            current.driveAccountEmail.isNotBlank() &&
            current.driveAccessToken.isNotBlank() &&
            !current.driveAccessToken.startsWith("ya29.studio_")
        return hasSafUri || hasRealToken
    }

    fun refreshDriveFoldersAndQuota() {
        val currentSettings = _settings.value
        val token = currentSettings.driveAccessToken
        val email = currentSettings.driveAccountEmail

        viewModelScope.launch {
            _isFolderLoading.value = true
            val currentCrumb = _currentBrowseFolder.value
            val folders = driveClient.listFolders(
                accessToken = token,
                parentFolderId = currentCrumb.id,
                parentPath = currentCrumb.path,
                accountEmail = email
            )
            _driveFolders.value = folders
            val quota = driveClient.fetchDriveQuota(
                accessToken = token,
                accountEmail = email
            )
            _driveQuota.value = quota
            _isFolderLoading.value = false
        }
    }

    /**
     * Explicitly downloads a single user-selected Cloud recording to the configured local storage
     * directory (`getRecordingsDir`). Triggered strictly by the user's manual Download action.
     */
    fun downloadCloudRecordingToLocal(recording: RecordingEntity) {
        if (_downloadingCloudIds.value.containsKey(recording.id)) return

        viewModelScope.launch {
            _downloadingCloudIds.value = _downloadingCloudIds.value + (recording.id to 0.05f)
            val result = repository.downloadCloudRecordingToLocal(
                cloudRecording = recording,
                driveClient = driveClient,
                settings = _settings.value,
                onProgress = { ratio ->
                    _downloadingCloudIds.value = _downloadingCloudIds.value + (recording.id to ratio)
                }
            )
            _downloadingCloudIds.value = _downloadingCloudIds.value - recording.id
            result.fold(
                onSuccess = { localEntity ->
                    postSnackbar("Downloaded \"${localEntity.title}\" to Local Storage")
                },
                onFailure = { err ->
                    postSnackbar("Download failed: ${err.localizedMessage ?: "Unable to download from Google Drive"}")
                }
            )
        }
    }

    fun browseDriveFolder(crumb: FolderBreadcrumb) {
        viewModelScope.launch {
            _isFolderLoading.value = true
            _currentBrowseFolder.value = crumb
            val currentList = _folderBreadcrumbs.value
            val existingIdx = currentList.indexOfFirst { it.id == crumb.id }
            _folderBreadcrumbs.value = if (existingIdx >= 0) {
                currentList.take(existingIdx + 1)
            } else {
                currentList + crumb
            }
            val folders = driveClient.listFolders(
                accessToken = _settings.value.driveAccessToken,
                parentFolderId = crumb.id,
                parentPath = crumb.path,
                accountEmail = _settings.value.driveAccountEmail
            )
            _driveFolders.value = folders
            _isFolderLoading.value = false
        }
    }

    fun createDriveFolder(folderName: String, selectImmediately: Boolean) {
        viewModelScope.launch {
            _isFolderLoading.value = true
            val parentCrumb = if (selectImmediately) rootBreadcrumb else _currentBrowseFolder.value
            val res = driveClient.createFolder(
                accessToken = _settings.value.driveAccessToken,
                folderName = folderName,
                parentFolderId = parentCrumb.id,
                parentPath = parentCrumb.path,
                treeUriString = _settings.value.driveFolderUri,
                accountEmail = _settings.value.driveAccountEmail
            )
            res.onSuccess { created ->
                val folders = driveClient.listFolders(
                    accessToken = _settings.value.driveAccessToken,
                    parentFolderId = _currentBrowseFolder.value.id,
                    parentPath = _currentBrowseFolder.value.path,
                    accountEmail = _settings.value.driveAccountEmail
                )
                _driveFolders.value = folders
                if (selectImmediately) {
                    selectTargetDriveFolder(created.id, created.name, created.path)
                } else {
                    postSnackbar("Created Google Drive folder \"${created.name}\"")
                }
            }.onFailure { err ->
                postSnackbar(err.localizedMessage ?: "Failed to create Google Drive folder")
            }
            _isFolderLoading.value = false
        }
    }

    fun selectTargetDriveFolder(folderId: String, folderName: String, folderPath: String) {
        val newUri = if (folderId.startsWith("content://")) folderId else _settings.value.driveFolderUri
        val updated = _settings.value.copy(
            driveFolderId = folderId,
            driveFolderName = folderName,
            driveFolderPath = folderPath,
            driveFolderUri = newUri
        )
        _settings.value = updated
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveSettings(updated)
        }
        postSnackbar("Selected Google Drive folder: $folderName")
    }

    // --- Local Phone Folder Browsing (Strictly Internal Device Folders Only) ---

    fun refreshLocalPhoneFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentCrumb = _currentLocalBrowseFolder.value
            val folders = driveClient.listLocalPhoneFolders(currentCrumb.path)
            _localPhoneFolders.value = folders
        }
    }

    fun browseLocalPhoneFolder(crumb: FolderBreadcrumb) {
        viewModelScope.launch(Dispatchers.IO) {
            _currentLocalBrowseFolder.value = crumb
            val currentList = _localFolderBreadcrumbs.value
            val existingIdx = currentList.indexOfFirst { it.path == crumb.path }
            _localFolderBreadcrumbs.value = if (existingIdx >= 0) {
                currentList.take(existingIdx + 1)
            } else {
                currentList + crumb
            }
            val folders = driveClient.listLocalPhoneFolders(crumb.path)
            _localPhoneFolders.value = folders
        }
    }

    fun createLocalPhoneFolder(folderName: String, selectImmediately: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val parentCrumb = if (selectImmediately) localRootBreadcrumb else _currentLocalBrowseFolder.value
            val res = driveClient.createLocalPhoneFolder(folderName, parentCrumb.path)
            res.onSuccess { created ->
                val folders = driveClient.listLocalPhoneFolders(_currentLocalBrowseFolder.value.path)
                _localPhoneFolders.value = folders
                if (selectImmediately) {
                    selectTargetLocalFolder(created.name, created.path)
                } else {
                    postSnackbar("Created internal phone folder \"${created.name}\"")
                }
            }
        }
    }

    fun selectTargetLocalFolder(folderName: String, folderPath: String) {
        val updated = _settings.value.copy(
            localFolderName = folderName,
            localFolderPath = folderPath,
            storageDirectoryLabel = folderPath
        )
        _settings.value = updated
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveSettings(updated)
        }
        postSnackbar("Selected local phone folder: $folderName")
    }

    /**
     * Handles custom folder selection from Android's native Storage Access Framework (SAF) `OpenDocumentTree` picker.
     * Takes persistable URI read/write permissions and updates settings.
     */
    fun selectLocalDocumentTreeFolder(context: Context, treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    context.contentResolver.takePersistableUriPermission(treeUri, flags)
                } catch (_: Exception) {}

                val oldUriStr = _settings.value.customStorageTreeUri
                if (oldUriStr.isNotBlank() && oldUriStr != treeUri.toString()) {
                    try {
                        context.contentResolver.releasePersistableUriPermission(Uri.parse(oldUriStr), flags)
                    } catch (_: Exception) {}
                }

                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                val docName = docFile?.name
                val lastSegment = treeUri.lastPathSegment?.substringAfterLast(':')
                val folderName = when {
                    !docName.isNullOrBlank() -> docName
                    !lastSegment.isNullOrBlank() -> lastSegment
                    else -> "Selected Folder"
                }

                val displayPath = if (!lastSegment.isNullOrBlank()) {
                    "Device Storage / $lastSegment"
                } else {
                    "Custom Folder: $folderName"
                }

                val updated = _settings.value.copy(
                    localFolderName = folderName,
                    localFolderPath = displayPath,
                    customStorageTreeUri = treeUri.toString(),
                    storageDirectoryLabel = displayPath
                )
                _settings.value = updated
                repository.saveSettings(updated)
                postSnackbar("Save location set to: $folderName")
            } catch (e: Exception) {
                postSnackbar("Failed to set storage location: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Resets local storage location back to the app's default internal storage directory.
     */
    fun resetLocalFolderToDefault(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val oldUriStr = _settings.value.customStorageTreeUri
            if (oldUriStr.isNotBlank()) {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.releasePersistableUriPermission(Uri.parse(oldUriStr), flags)
                } catch (_: Exception) {}
            }

            val updated = _settings.value.copy(
                localFolderName = "Recordings",
                localFolderPath = "Default App Storage",
                customStorageTreeUri = "",
                storageDirectoryLabel = "Default App Storage"
            )
            _settings.value = updated
            repository.saveSettings(updated)
            postSnackbar("Reset save location to default app storage")
        }
    }

    fun prepareExploreDriveFolder() {
        val currentFolderId = _settings.value.driveFolderId.ifBlank { "root" }
        val currentFolderName = _settings.value.driveFolderName.ifBlank { "My Drive" }
        val currentFolderPath = _settings.value.driveFolderPath.ifBlank { "/My Drive" }

        if (currentFolderId == "root" || currentFolderPath == "/My Drive") {
            _currentBrowseFolder.value = rootBreadcrumb
            _folderBreadcrumbs.value = listOf(rootBreadcrumb)
        } else {
            val targetCrumb = FolderBreadcrumb(id = currentFolderId, name = currentFolderName, path = currentFolderPath)
            _currentBrowseFolder.value = targetCrumb
            _folderBreadcrumbs.value = listOf(rootBreadcrumb, targetCrumb)
        }
        refreshDriveFoldersAndQuota()
    }

    fun prepareChooseDriveFolder() {
        val currentFolderId = _settings.value.driveFolderId.ifBlank { "root" }
        val currentFolderName = _settings.value.driveFolderName.ifBlank { "My Drive" }
        val currentFolderPath = _settings.value.driveFolderPath.ifBlank { "/My Drive" }

        if (currentFolderId == "root" || currentFolderPath == "/My Drive") {
            _currentBrowseFolder.value = rootBreadcrumb
            _folderBreadcrumbs.value = listOf(rootBreadcrumb)
        } else {
            val targetCrumb = FolderBreadcrumb(id = currentFolderId, name = currentFolderName, path = currentFolderPath)
            _currentBrowseFolder.value = targetCrumb
            _folderBreadcrumbs.value = listOf(rootBreadcrumb, targetCrumb)
        }
        refreshDriveFoldersAndQuota()
    }

    fun prepareExploreLocalFolder() {
        val currentFolderName = _settings.value.localFolderName.ifBlank { "Recordings" }
        val currentFolderPath = _settings.value.localFolderPath.ifBlank { "/Internal Storage/Recordings" }

        if (currentFolderPath == "/Internal Storage") {
            _currentLocalBrowseFolder.value = localRootBreadcrumb
            _localFolderBreadcrumbs.value = listOf(localRootBreadcrumb)
        } else {
            val targetCrumb = FolderBreadcrumb(
                id = "local_folder:$currentFolderName",
                name = currentFolderName,
                path = currentFolderPath
            )
            _currentLocalBrowseFolder.value = targetCrumb
            _localFolderBreadcrumbs.value = listOf(localRootBreadcrumb, targetCrumb)
        }
        refreshLocalPhoneFolders()
    }

    fun prepareChooseLocalFolder() {
        val currentFolderName = _settings.value.localFolderName.ifBlank { "Recordings" }
        val currentFolderPath = _settings.value.localFolderPath.ifBlank { "/Internal Storage/Recordings" }

        if (currentFolderPath == "/Internal Storage") {
            _currentLocalBrowseFolder.value = localRootBreadcrumb
            _localFolderBreadcrumbs.value = listOf(localRootBreadcrumb)
        } else {
            val targetCrumb = FolderBreadcrumb(
                id = "local_folder:$currentFolderName",
                name = currentFolderName,
                path = currentFolderPath
            )
            _currentLocalBrowseFolder.value = targetCrumb
            _localFolderBreadcrumbs.value = listOf(localRootBreadcrumb, targetCrumb)
        }
        refreshLocalPhoneFolders()
    }


    /**
     * Imports single or multiple audio files from any storage provider (Google Drive or Device Storage)
     * and stores them in the specified repository tab (Cloud or Local).
     */
    fun importAudioFiles(uris: List<Uri>, targetLocation: StorageDestination = _activeLibraryStorageTab.value) {
        if (uris.isEmpty() || _isImportingDriveFiles.value) return
        _isImportingDriveFiles.value = true
        viewModelScope.launch {
            var importedCount = 0
            var lastImportedTitle = ""
            var firstError: String? = null
            val isCloud = targetLocation == StorageDestination.CLOUD
            val targetFolderId = if (isCloud) _settings.value.driveFolderId else null
            val targetFolderName = if (isCloud) _settings.value.driveFolderName.ifBlank { "My Drive" } else _settings.value.localFolderName

            for (uri in uris) {
                val res = repository.importAudioFromUri(
                    uri = uri,
                    targetStorageLocation = targetLocation,
                    driveFolderId = targetFolderId,
                    driveFolderName = targetFolderName,
                    onProgress = {}
                )
                res.onSuccess { entity ->
                    importedCount++
                    lastImportedTitle = entity.title
                }.onFailure { err ->
                    if (firstError == null) {
                        firstError = err.localizedMessage
                    }
                }
            }

            _isImportingDriveFiles.value = false
            if (importedCount > 0) {
                _activeLibraryStorageTab.value = targetLocation
                if (isCloud) refreshDriveFoldersAndQuota()
                val destLabel = if (isCloud) "Google Drive" else "Local Storage"
                if (importedCount == 1) {
                    postSnackbar("Imported \"$lastImportedTitle\" to $destLabel")
                } else {
                    postSnackbar("Imported $importedCount audio file(s) to $destLabel")
                }
            } else {
                postSnackbar("Import failed: ${firstError ?: "Unsupported or unreadable audio file"}")
            }
        }
    }

    fun importAudioFilesFromDrive(uris: List<Uri>) {
        importAudioFiles(uris, StorageDestination.CLOUD)
    }

    /**
     * Uploads a recording to the user's selected Google Drive folder via the real Google Drive API
     * and updates its status to green (and adds to the Cloud tab) ONLY after receiving an explicit
     * upload confirmation with a real Google Drive file ID.
     */
    fun syncRecordingToCloud(
        recording: RecordingEntity,
        isManualTrigger: Boolean = true
    ) {
        if (_cloudSyncingIds.value.containsKey(recording.id)) return

        // Upload Tracking & Deduplication:
        // Skip previously uploaded files during automatic sync so a recording is never uploaded twice
        // unless explicitly triggered manually by the user.
        val isLocal = recording.storageLocation.equals(StorageDestination.LOCAL.id, ignoreCase = true)
        if (!isManualTrigger && isLocal && recording.hasVerifiedCloudUpload) {
            return
        }

        val activeSettings = _settings.value
        if (!isDriveSessionReadyForUpload()) {
            if (isManualTrigger) {
                postSnackbar("Please select your Google Drive folder to upload")
                requestDriveFolderPicker(recording)
            }
            return
        }

        viewModelScope.launch {
            _cloudSyncingIds.value = _cloudSyncingIds.value + (recording.id to 0.05f)
            val audioFile = repository.resolveAudioFile(recording) ?: File(recording.filePath)
            if (!audioFile.exists() || audioFile.length() <= 0L) {
                _cloudSyncingIds.value = _cloudSyncingIds.value - recording.id
                postSnackbar("Cannot upload \"${recording.title}\" — audio file not found on device.")
                return@launch
            }
            val ext = audioFile.extension.ifBlank { AudioFormatOption.fromId(recording.format).extension }
            val targetFolderId = activeSettings.driveFolderId.ifBlank { "root" }
            val targetFolderName = activeSettings.driveFolderName.ifBlank { "My Drive" }
            val targetFolderPath = activeSettings.driveFolderPath.ifBlank { "/My Drive" }

            val uploadResult = driveClient.uploadAudioFileMultipart(
                accessToken = activeSettings.driveAccessToken,
                accountEmail = activeSettings.driveAccountEmail,
                audioFile = audioFile,
                title = recording.title,
                formatExtension = ext,
                folderId = targetFolderId,
                folderName = targetFolderName,
                folderPath = targetFolderPath,
                treeUriString = activeSettings.driveFolderUri,
                onProgress = { ratio ->
                    _cloudSyncingIds.value = _cloudSyncingIds.value + (recording.id to ratio)
                }
            )

            uploadResult.fold(
                onSuccess = { res ->
                    if (res.fileId.isBlank() || res.fileId.startsWith("drive_") || res.fileId.startsWith("/")) {
                        _cloudSyncingIds.value = _cloudSyncingIds.value - recording.id
                        postSnackbar("Drive upload failed: Invalid file confirmation from Google Drive")
                        return@fold
                    }

                    // Fetch latest DB state so durationMs/sizeBytes/storageLocation are never overwritten with stale values
                    val currentEntity = repository.getRecordingById(recording.id) ?: recording
                    val updatedOriginal = currentEntity.copy(
                        storageLocation = currentEntity.storageLocation,
                        isCloudSynced = true,
                        cloudFileId = res.fileId,
                        cloudSyncedAt = res.syncedAtMs,
                        driveFolderId = res.folderId,
                        driveFolderName = targetFolderName,
                        driveWebViewLink = res.webViewLink
                    )
                    repository.updateRecordingAndLinkedPaths(updatedOriginal)

                    _cloudSyncingIds.value = _cloudSyncingIds.value - recording.id

                    val newToken = res.refreshedAccessToken.ifBlank { _settings.value.driveAccessToken }
                    val updatedSettings = _settings.value.copy(
                        driveAccessToken = newToken,
                        lastCloudSyncMs = res.syncedAtMs
                    )
                    _settings.value = updatedSettings
                    viewModelScope.launch(Dispatchers.IO) {
                        if (res.refreshedAccessToken.isNotBlank()) {
                            repository.storeAuthenticatedDriveSession(
                                AuthenticatedGoogleProfile(
                                    email = updatedSettings.driveAccountEmail,
                                    displayName = updatedSettings.driveAccountName,
                                    photoUrl = updatedSettings.driveAccountPhotoUrl,
                                    accessToken = res.refreshedAccessToken
                                )
                            )
                        }
                        repository.saveSettings(updatedSettings)
                    }
                    refreshDriveFoldersAndQuota()
                    postSnackbar("Uploaded \"${recording.title}\" to Google Drive ($targetFolderName)")
                },
                onFailure = { err ->
                    _cloudSyncingIds.value = _cloudSyncingIds.value - recording.id
                    val errMsg = err.localizedMessage.orEmpty()
                    if (err is GoogleDriveUserRecoverableException ||
                        errMsg.contains("unverified", ignoreCase = true) ||
                        errMsg.contains("Storage Access", ignoreCase = true) ||
                        errMsg.contains("403", ignoreCase = true) ||
                        errMsg.contains("access_denied", ignoreCase = true) ||
                        errMsg.contains("Google account", ignoreCase = true)
                    ) {
                        postSnackbar("Direct Google sign-in is unverified. Select your Google Drive folder to sync.")
                        requestDriveFolderPicker(recording)
                    } else {
                        postSnackbar("Drive upload failed: ${err.localizedMessage ?: "Unable to complete upload"}")
                    }
                }
            )
        }
    }

    /**
     * Copies a Local recording into Google Drive (Cloud tab) while keeping the original in Local tab.
     */
    fun copyRecordingToCloud(recording: RecordingEntity) {
        syncRecordingToCloud(recording, isManualTrigger = true)
    }

    /**
     * Toggles Auto Sync and immediately synchronizes any unsynced local recordings when enabled.
     */
    fun toggleAutoSync(enabled: Boolean) {
        val updated = _settings.value.copy(driveAutoSync = enabled)
        _settings.value = updated
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveSettings(updated)
        }
        if (enabled) {
            if (isDriveSessionReadyForUpload()) {
                autoSyncUnsyncedRecordings()
            } else {
                connectGoogleDrive(getApplication())
            }
        }
    }

    /**
     * Automatically uploads only unsynced local recordings to the designated Google Drive folder,
     * skipping any recording that has already been uploaded.
     */
    fun autoSyncUnsyncedRecordings() {
        if (!isDriveSessionReadyForUpload()) return
        val unsyncedLocal = localRecordings.value.filter { localRec ->
            !localRec.hasVerifiedCloudUpload
        }
        unsyncedLocal.forEach { rec ->
            syncRecordingToCloud(rec, isManualTrigger = false)
        }
    }

    fun syncAllRecordingsToCloud() {
        val localList = localRecordings.value
        if (localList.isEmpty()) {
            postSnackbar("No local recordings to upload")
            return
        }
        val unsyncedLocal = localList.filter { localRec ->
            !localRec.hasVerifiedCloudUpload
        }
        if (unsyncedLocal.isEmpty()) {
            postSnackbar("All local recordings are already uploaded to Google Drive")
            return
        }
        if (!isDriveSessionReadyForUpload()) {
            pendingUploadAfterAuth = unsyncedLocal.first()
            connectGoogleDrive(getApplication())
            return
        }
        unsyncedLocal.forEach { syncRecordingToCloud(it, isManualTrigger = true) }
    }

    // --- Settings Updates ---

    fun updateSettings(transform: (RecorderSettings) -> RecorderSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveSettings(updated)
        }
        audioEngine.updateSilenceSkipConfig(updated.silenceConfig)
        audioEngine.updateAudioIsolationConfig(
            echoCancellationEnabled = updated.echoCancellationEnabled,
            noiseSuppressionEnabled = updated.noiseSuppressionEnabled,
            pauseExternalMediaOnRecord = updated.pauseExternalMediaOnRecord
        )
    }

    fun cleanUpLocalCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val freed = repository.clearLocalTempCache()
            withContext(Dispatchers.Main) {
                postSnackbar("Cleaned temporary cache (${AudioFormatUtils.formatFileSize(freed)} freed)")
            }
        }
    }

    /**
     * Deep-scans, unpacks, and rebuilds a corrupted or damaged recording file (M4A or WAV)
     * triggered from the recording options menu ("Repair Recording").
     * Updates duration, file size, container format, waveform peaks, and silence map in the database.
     */
    fun repairRecording(recording: RecordingEntity) {
        if (_openedPlayerRecording.value?.id == recording.id && audioEngine.isPlaying.value) {
            audioEngine.stopPlayback()
        }
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                _mergeProgress.value = 5
                val sourceFile = repository.resolveAudioFile(recording) ?: File(recording.filePath)
                val repairResult = WavFileTools.repairAndRebuildCorruptedRecording(
                    file = sourceFile,
                    fallbackSampleRate = recording.sampleRate,
                    fallbackChannels = recording.channels,
                    fallbackBitsPerSample = recording.bitDepth,
                    onProgress = { ratio ->
                        _mergeProgress.value = (ratio * 100).toInt().coerceIn(5, 95)
                    }
                )
                if (repairResult.success && repairResult.finalFile.exists()) {
                    val repairedFile = repairResult.finalFile
                    val repairedDurationMs = if (repairResult.durationMs > 0L) {
                        repairResult.durationMs
                    } else {
                        recording.durationMs.coerceAtLeast(1000L)
                    }
                    val repairedPeaks = WavFileTools.extractWaveformFromFile(repairedFile)
                    val repairedSilenceMap = WavFileTools.generateSilenceMapIndex(
                        sourceFile = repairedFile,
                        durationMs = repairedDurationMs,
                        peaks = repairedPeaks,
                        config = _settings.value.silenceConfig,
                        fallbackSampleRate = recording.sampleRate,
                        fallbackChannels = recording.channels,
                        fallbackBitsPerSample = recording.bitDepth
                    )
                    val updatedEntity = recording.copy(
                        filePath = repairedFile.absolutePath,
                        format = repairResult.format.uppercase(Locale.US),
                        durationMs = repairedDurationMs,
                        sizeBytes = repairedFile.length(),
                        waveformPeaks = AudioFormatUtils.serializePeaks(repairedPeaks),
                        silenceMap = AudioFormatUtils.serializeSilenceMap(repairedSilenceMap)
                    )
                    repository.updateRecordingAndLinkedPaths(updatedEntity, recording.filePath)
                    if (_openedPlayerRecording.value?.id == updatedEntity.id) {
                        _openedPlayerRecording.value = updatedEntity
                    }
                    _mergeProgress.value = 100
                    postSnackbar(
                        "Recording \"${recording.title}\" repaired (${AudioFormatUtils.formatDurationCompact(repairedDurationMs)})"
                    )
                } else {
                    postSnackbar("Unable to repair \"${recording.title}\" — file is missing or unreadable.")
                }
            } catch (e: Exception) {
                postSnackbar("Repair failed: ${e.localizedMessage ?: "Unexpected error"}")
            } finally {
                _mergeProgress.value = null
            }
        }
    }

    /**
     * Ensures newly recorded audio is never lost if the target base file was deleted or unmergeable.
     * Saves [segmentFile] as a standalone recording in the Library.
     */
    private suspend fun saveOrphanedSegmentAsRecoveryRecording(
        segmentFile: File,
        baseTitle: String,
        durationMs: Long,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int
    ) {
        if (!segmentFile.exists() || segmentFile.length() == 0L) {
            postSnackbar("Failed to merge audio segments; original file preserved safely.")
            return
        }
        try {
            val targetDir = repository.getRecordingsDir(
                subFolderName = _settings.value.localFolderName,
                folderPath = _settings.value.localFolderPath
            )
            targetDir.mkdirs()
            val ext = segmentFile.extension.ifBlank { "wav" }.lowercase(Locale.US)
            val recoveryTitle = "$baseTitle (Recovered)"
            val destFile = repository.resolveUniqueLocalFile(targetDir, recoveryTitle, ext)
            if (!segmentFile.renameTo(destFile)) {
                segmentFile.copyTo(destFile, overwrite = true)
                segmentFile.delete()
            }
            val finalSavedFile = if (destFile.exists()) destFile else segmentFile
            val peaks = WavFileTools.extractWaveformFromFile(finalSavedFile)
            val silenceMap = WavFileTools.generateSilenceMapIndex(
                sourceFile = finalSavedFile,
                durationMs = durationMs,
                peaks = peaks,
                config = _settings.value.silenceConfig,
                fallbackSampleRate = sampleRate,
                fallbackChannels = channels,
                fallbackBitsPerSample = bitDepth
            )
            val entity = RecordingEntity(
                title = recoveryTitle,
                filePath = finalSavedFile.absolutePath,
                durationMs = durationMs.coerceAtLeast(1000L),
                format = ext.uppercase(Locale.US),
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                channels = channels,
                bitrate = 128_000,
                sizeBytes = finalSavedFile.length(),
                waveformPeaks = AudioFormatUtils.serializePeaks(peaks),
                silenceMap = AudioFormatUtils.serializeSilenceMap(silenceMap),
                storageLocation = StorageDestination.LOCAL.id,
                isCloudSynced = false,
                tags = "Recovered",
                notes = "Saved automatically after merge interruption"
            )
            repository.insertRecording(entity)
            postSnackbar("Merge fallback: saved new audio as \"$recoveryTitle\"")
        } catch (_: Exception) {
            postSnackbar("Failed to merge audio segments; original file preserved safely.")
        }
    }

    private fun syncForegroundNotificationIfRecording(clearAppendConfirmation: Boolean = false) {
        val currentStatus = _recordingStatus.value
        if (currentStatus == RecordingStatus.RECORDING || currentStatus == RecordingStatus.PAUSED) {
            val isPaused = currentStatus == RecordingStatus.PAUSED
            val title = if (isPaused) {
                "Paused"
            } else {
                _appendTarget.value?.title ?: "Recording"
            }
            updateForegroundServiceNotification(
                title = title,
                status = "",
                isPaused = isPaused,
                clearAppendConfirmation = clearAppendConfirmation
            )
        }
    }

    private fun startForegroundServiceNotification(title: String, status: String) {
        try {
            val context = getApplication<Application>()
            val baseOffset = _appendTarget.value?.durationMs ?: 0L
            val initialElapsedMs = (baseOffset + audioEngine.elapsedMs.value).coerceAtLeast(0L)
            val initialPeaks = audioEngine.livePeaks.value.takeLast(8).toFloatArray()
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_START_FOREGROUND
                putExtra(RecordingForegroundService.EXTRA_TITLE, title)
                putExtra(RecordingForegroundService.EXTRA_STATUS, status)
                putExtra(RecordingForegroundService.EXTRA_IS_PAUSED, false)
                putExtra(RecordingForegroundService.EXTRA_IS_APPENDING, _appendTarget.value != null)
                putExtra(RecordingForegroundService.EXTRA_RECORDING_ELAPSED_MS, initialElapsedMs)
                if (initialPeaks.isNotEmpty()) {
                    putExtra(RecordingForegroundService.EXTRA_WAVEFORM_PEAKS, initialPeaks)
                }
                putExtra(
                    RecordingForegroundService.EXTRA_IS_SKIP_SILENCE_ACTIVE,
                    _settings.value.silenceConfig.isEffectivelyEnabled
                )
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {}
    }

    private fun updateForegroundServiceNotification(
        title: String,
        status: String,
        isPaused: Boolean = false,
        elapsedMs: Long = ((_appendTarget.value?.durationMs ?: 0L) + audioEngine.elapsedMs.value).coerceAtLeast(0L),
        waveformPeaks: FloatArray? = null,
        clearAppendConfirmation: Boolean = false
    ) {
        try {
            val context = getApplication<Application>()
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_UPDATE_NOTIFICATION
                putExtra(RecordingForegroundService.EXTRA_TITLE, title)
                putExtra(RecordingForegroundService.EXTRA_STATUS, status)
                putExtra(RecordingForegroundService.EXTRA_IS_PAUSED, isPaused)
                putExtra(RecordingForegroundService.EXTRA_IS_APPENDING, _appendTarget.value != null)
                putExtra(RecordingForegroundService.EXTRA_RECORDING_ELAPSED_MS, elapsedMs)
                putExtra(RecordingForegroundService.EXTRA_CLEAR_APPEND_CONFIRMATION, clearAppendConfirmation)
                if (!isPaused && waveformPeaks != null && waveformPeaks.isNotEmpty()) {
                    putExtra(RecordingForegroundService.EXTRA_WAVEFORM_PEAKS, waveformPeaks)
                }
                putExtra(
                    RecordingForegroundService.EXTRA_IS_SKIP_SILENCE_ACTIVE,
                    _settings.value.silenceConfig.isEffectivelyEnabled
                )
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    private fun showAppendSaveConfirmationNotification() {
        try {
            val context = getApplication<Application>()
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_SHOW_APPEND_SAVE_CONFIRMATION
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    private fun showSavedConfirmationNotification(title: String = "Finished recording") {
        try {
            val context = getApplication<Application>()
            if (com.example.service.RecordingIslandOverlayManager.lastIsShowingFinishedCompletion) {
                return
            }
            if (com.example.service.RecordingIslandOverlayManager.isRecordingSessionActive ||
                com.example.service.RecordingIslandOverlayManager.lastIsShowingAppendConfirm
            ) {
                com.example.service.RecordingIslandOverlayManager.showFinishedCompletionOverlay(context, title)
            }
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_SHOW_SAVED_CONFIRMATION
                putExtra(RecordingForegroundService.EXTRA_TITLE, title)
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    private fun startPlaybackForegroundServiceNotification(
        title: String,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        try {
            val context = getApplication<Application>()
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_START_PLAYBACK
                putExtra(RecordingForegroundService.EXTRA_TITLE, title)
                putExtra(RecordingForegroundService.EXTRA_PLAYBACK_IS_PLAYING, isPlaying)
                putExtra(RecordingForegroundService.EXTRA_PLAYBACK_POSITION_MS, positionMs)
                putExtra(RecordingForegroundService.EXTRA_PLAYBACK_DURATION_MS, durationMs)
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {}
    }

    private fun updatePlaybackForegroundServiceNotification(
        title: String,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        try {
            val context = getApplication<Application>()
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_UPDATE_PLAYBACK
                putExtra(RecordingForegroundService.EXTRA_TITLE, title)
                putExtra(RecordingForegroundService.EXTRA_PLAYBACK_IS_PLAYING, isPlaying)
                putExtra(RecordingForegroundService.EXTRA_PLAYBACK_POSITION_MS, positionMs)
                putExtra(RecordingForegroundService.EXTRA_PLAYBACK_DURATION_MS, durationMs)
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    private fun stopForegroundServiceNotification() {
        try {
            com.example.service.RecordingIslandOverlayManager.dismissAndReset()
            val context = getApplication<Application>()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            nm?.cancel(RecordingForegroundService.NOTIFICATION_ID)
            nm?.cancel(RecordingForegroundService.FINISHED_NOTIFICATION_ID)
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_STOP_FOREGROUND
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    fun refreshConnectedAudioDevices() {
        audioEngine.refreshConnectedAudioDevices()
    }

    override fun onCleared() {
        RecordingForegroundService.activeStopSaveHandler = null
        RecordingForegroundService.activeConfirmAppendSaveHandler = null
        RecordingForegroundService.activeConfirmAppendSaveWithTitleHandler = null
        RecordingForegroundService.activeCancelAppendSaveHandler = null
        RecordingForegroundService.activeDiscardHandler = null
        RecordingForegroundService.activePauseHandler = null
        RecordingForegroundService.activeResumeHandler = null
        RecordingForegroundService.activeBookmarkHandler = null
        RecordingForegroundService.activeSkipSilenceHandler = null
        RecordingForegroundService.activePlaybackBookmarkHandler = null
        RecordingForegroundService.activePlaybackPrevHandler = null
        RecordingForegroundService.activePlaybackPlayPauseHandler = null
        RecordingForegroundService.activePlaybackNextHandler = null
        RecordingForegroundService.activePlaybackContinueRecHandler = null
        try {
            getApplication<Application>().unregisterReceiver(externalPauseReceiver)
        } catch (_: Exception) {}
        audioEngine.releaseEngineResources()
        super.onCleared()
    }
}
