package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import com.example.MainActivity
import com.example.R

class RecordingForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var currentTitle: String = "Recording"
    private var currentStatus: String = ""
    private var isPausedState: Boolean = false
    private var isSkipSilenceActive: Boolean = false
    private var isAppendingSession: Boolean = false
    private var sessionStartTimeMs: Long = System.currentTimeMillis()
    private var pauseStartedAtMs: Long = 0L
    private var accumulatedPausedMs: Long = 0L

    // Playback state
    private var isPlaybackMode: Boolean = false
    private var playbackTitle: String = "Recording"
    private var playbackIsPlaying: Boolean = false
    private var playbackPositionMs: Long = 0L
    private var playbackDurationMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
    }

    internal fun dispatchPauseAction() {
        val handler = activePauseHandler
        if (handler != null) {
            handler.invoke()
        } else {
            sendBroadcast(Intent(ACTION_USER_PAUSE).setPackage(packageName))
        }
    }

    internal fun dispatchResumeAction() {
        val handler = activeResumeHandler
        if (handler != null) {
            handler.invoke()
        } else {
            sendBroadcast(Intent(ACTION_USER_RESUME).setPackage(packageName))
        }
    }

    internal fun dispatchStopSaveAction() {
        val handler = activeStopSaveHandler
        if (handler != null) {
            handler.invoke()
        } else {
            sendBroadcast(Intent(ACTION_USER_STOP_SAVE).setPackage(packageName))
        }
    }

    internal fun dispatchBookmarkAction() {
        val handler = activeBookmarkHandler
        if (handler != null) {
            handler.invoke()
        } else {
            sendBroadcast(Intent(ACTION_USER_BOOKMARK).setPackage(packageName))
        }
    }

    internal fun dispatchPlaybackBookmarkAction() {
        val handler = activePlaybackBookmarkHandler
        if (handler != null) {
            handler.invoke()
        } else {
            sendBroadcast(Intent(ACTION_USER_PLAYBACK_BOOKMARK).setPackage(packageName))
        }
    }

    internal fun dispatchPlaybackPrevAction() {
        val handler = activePlaybackPrevHandler
        if (handler != null) {
            handler.invoke()
        } else {
            sendBroadcast(Intent(ACTION_USER_PLAYBACK_PREV).setPackage(packageName))
        }
    }

    internal fun dispatchPlaybackPlayPauseAction() {
        val handler = activePlaybackPlayPauseHandler
        if (handler != null) {
            handler.invoke()
        } else {
            sendBroadcast(Intent(ACTION_USER_PLAYBACK_PLAY_PAUSE).setPackage(packageName))
        }
    }

    internal fun dispatchPlaybackNextAction() {
        val handler = activePlaybackNextHandler
        if (handler != null) {
            handler.invoke()
        } else {
            sendBroadcast(Intent(ACTION_USER_PLAYBACK_NEXT).setPackage(packageName))
        }
    }

    internal fun dispatchPlaybackContinueRecAction() {
        val handler = activePlaybackContinueRecHandler
        if (handler != null) {
            handler.invoke()
        } else {
            sendBroadcast(Intent(ACTION_USER_PLAYBACK_CONTINUE_REC).setPackage(packageName))
        }
    }

    internal fun dispatchPlaybackSeekAction(posMs: Long) {
        val handler = activePlaybackSeekHandler
        if (handler != null) {
            handler.invoke(posMs)
        } else {
            sendBroadcast(Intent(ACTION_USER_PLAYBACK_SEEK).putExtra(EXTRA_SEEK_POSITION_MS, posMs).setPackage(packageName))
        }
    }

    private fun initMediaSession() {
        try {
            mediaSession = MediaSession(this, "AudioRecorderStudioLiveSession").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPause() {
                        if (isPlaybackMode) {
                            dispatchPlaybackPlayPauseAction()
                        } else {
                            dispatchPauseAction()
                        }
                    }

                    override fun onPlay() {
                        if (isPlaybackMode) {
                            dispatchPlaybackPlayPauseAction()
                        } else {
                            dispatchResumeAction()
                        }
                    }

                    override fun onSeekTo(pos: Long) {
                        if (isPlaybackMode) {
                            dispatchPlaybackSeekAction(pos)
                        }
                    }

                    override fun onSkipToPrevious() {
                        if (isPlaybackMode) {
                            dispatchPlaybackPrevAction()
                        }
                    }

                    override fun onSkipToNext() {
                        if (isPlaybackMode) {
                            dispatchPlaybackNextAction()
                        }
                    }

                    override fun onStop() {
                        if (isPlaybackMode) {
                            stopForegroundInternal()
                        } else {
                            dispatchStopSaveAction()
                        }
                    }

                    override fun onCustomAction(action: String, extras: Bundle?) {
                        when (action) {
                            ACTION_NOTIFICATION_STOP_SAVE, ACTION_USER_STOP_SAVE -> {
                                dispatchStopSaveAction()
                            }
                            ACTION_NOTIFICATION_BOOKMARK, ACTION_USER_BOOKMARK -> {
                                dispatchBookmarkAction()
                            }
                            ACTION_NOTIFICATION_PLAYBACK_BOOKMARK, ACTION_USER_PLAYBACK_BOOKMARK -> {
                                dispatchPlaybackBookmarkAction()
                            }
                            ACTION_NOTIFICATION_PLAYBACK_PREV, ACTION_USER_PLAYBACK_PREV -> {
                                dispatchPlaybackPrevAction()
                            }
                            ACTION_NOTIFICATION_PLAYBACK_PLAY_PAUSE, ACTION_USER_PLAYBACK_PLAY_PAUSE -> {
                                dispatchPlaybackPlayPauseAction()
                            }
                            ACTION_NOTIFICATION_PLAYBACK_NEXT, ACTION_USER_PLAYBACK_NEXT -> {
                                dispatchPlaybackNextAction()
                            }
                            ACTION_NOTIFICATION_PLAYBACK_CONTINUE_REC, ACTION_USER_PLAYBACK_CONTINUE_REC -> {
                                dispatchPlaybackContinueRecAction()
                            }
                        }
                    }
                })
                isActive = true
            }
        } catch (_: Exception) {}
    }

    private fun updateMediaSessionRecordingState(
        title: String,
        statusText: String,
        isPaused: Boolean,
        isSkipSilence: Boolean,
        isAwaitingSave: Boolean
    ) {
        try {
            val session = mediaSession ?: return
            val stateCode = when {
                isAwaitingSave -> PlaybackState.STATE_STOPPED
                isPaused -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_PLAYING
            }
            val elapsed = (System.currentTimeMillis() - sessionStartTimeMs - accumulatedPausedMs).coerceAtLeast(0L)
            val playbackStateBuilder = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP
                )
                .setState(stateCode, elapsed, if (isPaused) 0f else 1f)

            if (!isAwaitingSave) {
                playbackStateBuilder.addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        ACTION_NOTIFICATION_BOOKMARK,
                        getString(R.string.notification_action_bookmark),
                        R.drawable.ic_notif_bookmark
                    ).build()
                )
            }

            session.setPlaybackState(playbackStateBuilder.build())

            val displaySubtitle = statusText.ifBlank {
                if (isPaused) getString(R.string.notification_status_paused)
                else getString(R.string.notification_status_recording)
            }
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, displaySubtitle)
                .build()
            session.setMetadata(metadata)
            if (!session.isActive) {
                session.isActive = true
            }
        } catch (_: Exception) {}
    }

    private fun updateMediaSessionPlaybackState(
        title: String,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        try {
            val session = mediaSession ?: return
            val stateCode = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            val playbackStateBuilder = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP
                )
                .setState(stateCode, positionMs, if (isPlaying) 1f else 0f)

            playbackStateBuilder.addCustomAction(
                PlaybackState.CustomAction.Builder(
                    ACTION_NOTIFICATION_PLAYBACK_BOOKMARK,
                    getString(R.string.notification_action_bookmark),
                    R.drawable.ic_notif_bookmark
                ).build()
            )
            playbackStateBuilder.addCustomAction(
                PlaybackState.CustomAction.Builder(
                    ACTION_NOTIFICATION_PLAYBACK_CONTINUE_REC,
                    getString(R.string.notification_action_continue_rec),
                    R.drawable.ic_notif_mic
                ).build()
            )

            session.setPlaybackState(playbackStateBuilder.build())

            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.coerceAtLeast(0L))
                .build()
            session.setMetadata(metadata)
            if (!session.isActive) {
                session.isActive = true
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                isPlaybackMode = false
                currentTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Recording"
                currentStatus = intent.getStringExtra(EXTRA_STATUS).orEmpty()
                isPausedState = intent.getBooleanExtra(EXTRA_IS_PAUSED, false)
                isSkipSilenceActive = intent.getBooleanExtra(EXTRA_IS_SKIP_SILENCE_ACTIVE, false)
                isAppendingSession = intent.getBooleanExtra(EXTRA_IS_APPENDING, false)
                sessionStartTimeMs = System.currentTimeMillis()
                pauseStartedAtMs = if (isPausedState) sessionStartTimeMs else 0L
                accumulatedPausedMs = 0L
                acquireWakeLock()

                val notification = buildRecordingNotification(
                    title = currentTitle,
                    statusText = currentStatus,
                    isPaused = isPausedState,
                    isSkipSilence = isSkipSilenceActive,
                    isAwaitingSave = false
                )
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (_: Exception) {
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                    } catch (_: Exception) {}
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(NOTIFICATION_ID, notification)
            }

            ACTION_UPDATE_NOTIFICATION -> {
                isPlaybackMode = false
                val previousPaused = isPausedState
                currentTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: currentTitle
                currentStatus = intent.getStringExtra(EXTRA_STATUS) ?: currentStatus
                isPausedState = intent.getBooleanExtra(EXTRA_IS_PAUSED, isPausedState)
                if (intent.hasExtra(EXTRA_IS_SKIP_SILENCE_ACTIVE)) {
                    isSkipSilenceActive = intent.getBooleanExtra(EXTRA_IS_SKIP_SILENCE_ACTIVE, isSkipSilenceActive)
                }
                if (intent.hasExtra(EXTRA_IS_APPENDING)) {
                    isAppendingSession = intent.getBooleanExtra(EXTRA_IS_APPENDING, isAppendingSession)
                }
                val now = System.currentTimeMillis()
                if (!previousPaused && isPausedState) {
                    pauseStartedAtMs = now
                } else if (previousPaused && !isPausedState && pauseStartedAtMs > 0L) {
                    accumulatedPausedMs += (now - pauseStartedAtMs).coerceAtLeast(0L)
                    pauseStartedAtMs = 0L
                }
                val isAwaitingSave = intent.getBooleanExtra(EXTRA_AWAITING_SAVE, false)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(
                    NOTIFICATION_ID,
                    buildRecordingNotification(
                        title = currentTitle,
                        statusText = currentStatus,
                        isPaused = isPausedState,
                        isSkipSilence = isSkipSilenceActive,
                        isAwaitingSave = isAwaitingSave
                    )
                )
            }

            ACTION_START_PLAYBACK -> {
                isPlaybackMode = true
                playbackTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Recording"
                playbackIsPlaying = intent.getBooleanExtra(EXTRA_PLAYBACK_IS_PLAYING, true)
                playbackPositionMs = intent.getLongExtra(EXTRA_PLAYBACK_POSITION_MS, 0L)
                playbackDurationMs = intent.getLongExtra(EXTRA_PLAYBACK_DURATION_MS, 0L)
                acquireWakeLock()

                val notification = buildPlaybackNotification(
                    title = playbackTitle,
                    isPlaying = playbackIsPlaying,
                    positionMs = playbackPositionMs,
                    durationMs = playbackDurationMs
                )
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (_: Exception) {
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                    } catch (_: Exception) {}
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(NOTIFICATION_ID, notification)
            }

            ACTION_UPDATE_PLAYBACK -> {
                isPlaybackMode = true
                playbackTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: playbackTitle
                playbackIsPlaying = intent.getBooleanExtra(EXTRA_PLAYBACK_IS_PLAYING, playbackIsPlaying)
                playbackPositionMs = intent.getLongExtra(EXTRA_PLAYBACK_POSITION_MS, playbackPositionMs)
                playbackDurationMs = intent.getLongExtra(EXTRA_PLAYBACK_DURATION_MS, playbackDurationMs)

                if (playbackIsPlaying) {
                    acquireWakeLock()
                }

                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(
                    NOTIFICATION_ID,
                    buildPlaybackNotification(
                        title = playbackTitle,
                        isPlaying = playbackIsPlaying,
                        positionMs = playbackPositionMs,
                        durationMs = playbackDurationMs
                    )
                )
            }

            ACTION_NOTIFICATION_PAUSE -> {
                dispatchPauseAction()
            }

            ACTION_NOTIFICATION_RESUME -> {
                dispatchResumeAction()
            }

            ACTION_NOTIFICATION_STOP_SAVE -> {
                if (isAppendingSession) {
                    dispatchStopSaveAction()
                    try {
                        val openAppIntent = Intent(this, MainActivity::class.java).apply {
                            action = ACTION_PROMPT_APPEND_SAVE
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            )
                        }
                        startActivity(openAppIntent)
                    } catch (_: Exception) {}
                } else {
                    showFinishedSavedConfirmation(getString(R.string.notification_finished_recording))
                    dispatchStopSaveAction()
                }
            }

            ACTION_SHOW_SAVED_CONFIRMATION -> {
                val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.notification_finished_recording)
                showFinishedSavedConfirmation(title)
            }

            ACTION_NOTIFICATION_BOOKMARK -> {
                dispatchBookmarkAction()
            }

            ACTION_NOTIFICATION_PLAYBACK_BOOKMARK -> {
                dispatchPlaybackBookmarkAction()
            }

            ACTION_NOTIFICATION_PLAYBACK_PREV -> {
                dispatchPlaybackPrevAction()
            }

            ACTION_NOTIFICATION_PLAYBACK_PLAY_PAUSE -> {
                dispatchPlaybackPlayPauseAction()
            }

            ACTION_NOTIFICATION_PLAYBACK_NEXT -> {
                dispatchPlaybackNextAction()
            }

            ACTION_NOTIFICATION_PLAYBACK_CONTINUE_REC -> {
                dispatchPlaybackContinueRecAction()
            }

            ACTION_NOTIFICATION_SKIP_SILENCE -> {
                val handler = activeSkipSilenceHandler
                if (handler != null) {
                    handler.invoke()
                } else {
                    sendBroadcast(Intent(ACTION_USER_TOGGLE_SKIP_SILENCE).setPackage(packageName))
                }
            }

            ACTION_NOTIFICATION_DISCARD -> {
                sendBroadcast(Intent(ACTION_USER_DISCARD).setPackage(packageName))
            }

            ACTION_STOP_FOREGROUND -> {
                stopForegroundInternal()
            }
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AudioRecorderStudio::BackgroundPlaybackRecordingWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // Up to 4 hours continuous background operation
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    internal fun buildNotification(
        title: String,
        statusText: String,
        isPaused: Boolean,
        isSkipSilence: Boolean = isSkipSilenceActive,
        isAwaitingSave: Boolean = false
    ): Notification = buildRecordingNotification(title, statusText, isPaused, isSkipSilence, isAwaitingSave)

    internal fun buildRecordingNotification(
        title: String,
        statusText: String,
        isPaused: Boolean,
        isSkipSilence: Boolean = isSkipSilenceActive,
        isAwaitingSave: Boolean = false
    ): Notification {
        createNotificationChannel()
        if (mediaSession == null) {
            initMediaSession()
        }
        updateMediaSessionRecordingState(title, statusText, isPaused, isSkipSilence, isAwaitingSave)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val effectiveWhenMs = (sessionStartTimeMs + accumulatedPausedMs).coerceAtMost(System.currentTimeMillis())

        val pauseOrResumePendingIntent = if (isPaused) {
            val resumeIntent = Intent(this, RecordingForegroundService::class.java).apply {
                action = ACTION_NOTIFICATION_RESUME
            }
            PendingIntent.getService(
                this,
                101,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val pauseIntent = Intent(this, RecordingForegroundService::class.java).apply {
                action = ACTION_NOTIFICATION_PAUSE
            }
            PendingIntent.getService(
                this,
                102,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val pendingStopSave = if (isAppendingSession) {
            val promptAppendIntent = Intent(this, MainActivity::class.java).apply {
                action = ACTION_PROMPT_APPEND_SAVE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            PendingIntent.getActivity(
                this,
                103,
                promptAppendIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val stopSaveServiceIntent = Intent(this, RecordingForegroundService::class.java).apply {
                action = ACTION_NOTIFICATION_STOP_SAVE
            }
            PendingIntent.getService(
                this,
                103,
                stopSaveServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val bookmarkServiceIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_NOTIFICATION_BOOKMARK
        }
        val pendingBookmark = PendingIntent.getService(
            this,
            104,
            bookmarkServiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subtitle = statusText.ifBlank {
            if (isPaused) getString(R.string.notification_status_paused)
            else getString(R.string.notification_status_recording)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(R.drawable.ic_notif_mic)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setColor(0xFFF08C4B.toInt())
            .setShowWhen(true)
            .setWhen(effectiveWhenMs)
            .setUsesChronometer(!isPaused && !isAwaitingSave)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        if (!isAwaitingSave) {
            // Action 0: Pause / Resume button
            val (pauseResumeIcon, pauseResumeTitle) = if (isPaused) {
                R.drawable.ic_notif_resume to getString(R.string.notification_action_resume)
            } else {
                R.drawable.ic_notif_pause to getString(R.string.notification_action_pause)
            }
            val pauseResumeAction = Notification.Action.Builder(
                Icon.createWithResource(this, pauseResumeIcon),
                pauseResumeTitle,
                pauseOrResumePendingIntent
            ).build()
            builder.addAction(pauseResumeAction)

            // Action 1: Stop and Save button
            val finishSaveAction = Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif_stop_save),
                getString(R.string.notification_action_stop_save),
                pendingStopSave
            ).build()
            builder.addAction(finishSaveAction)

            // Action 2: Bookmark button
            val bookmarkAction = Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif_bookmark),
                getString(R.string.notification_action_bookmark),
                pendingBookmark
            ).build()
            builder.addAction(bookmarkAction)
        }

        // MediaStyle with MediaSession token renders native media controls in notification shade,
        // lock screen, and Xiaomi HyperOS Dynamic Island (HyperIsland).
        val mediaStyle = Notification.MediaStyle()
        mediaSession?.sessionToken?.let { token ->
            mediaStyle.setMediaSession(token)
        }
        if (!isAwaitingSave) {
            // Strictly the 3 essential actions (Pause/Resume, Finish & Save, Bookmark) in compact view
            mediaStyle.setShowActionsInCompactView(0, 1, 2)
        }
        builder.setStyle(mediaStyle)

        val notification = builder.build()
        notification.extras.putIntArray(Notification.EXTRA_COMPACT_ACTIONS, intArrayOf(0, 1, 2))
        notification.extras.putBoolean("android.requestPromotedOngoing", true)
        return notification
    }

    internal fun buildPlaybackNotification(
        title: String,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long
    ): Notification {
        createNotificationChannel()
        if (mediaSession == null) {
            initMediaSession()
        }
        updateMediaSessionPlaybackState(title, isPlaying, positionMs, durationMs)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 1. Bookmark pending intent
        val bookmarkIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_NOTIFICATION_PLAYBACK_BOOKMARK
        }
        val pendingBookmark = PendingIntent.getService(
            this,
            201,
            bookmarkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Previous / Rewind pending intent
        val prevIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_NOTIFICATION_PLAYBACK_PREV
        }
        val pendingPrev = PendingIntent.getService(
            this,
            202,
            prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Play / Pause pending intent
        val playPauseIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_NOTIFICATION_PLAYBACK_PLAY_PAUSE
        }
        val pendingPlayPause = PendingIntent.getService(
            this,
            203,
            playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 4. Next / Fast Forward pending intent
        val nextIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_NOTIFICATION_PLAYBACK_NEXT
        }
        val pendingNext = PendingIntent.getService(
            this,
            204,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 5. Continue Recording pending intent
        val continueRecIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_NOTIFICATION_PLAYBACK_CONTINUE_REC
        }
        val pendingContinueRec = PendingIntent.getService(
            this,
            205,
            continueRecIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(R.drawable.ic_notif_mic)
            .setContentTitle(title)
            .setContentText(getString(R.string.notification_status_playback))
            .setContentIntent(pendingOpen)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setColor(0xFFF08C4B.toInt())
            .setShowWhen(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        // Action 0: Previous (-3s or previous recording)
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif_prev),
                getString(R.string.notification_action_previous),
                pendingPrev
            ).build()
        )

        // Action 1: Play / Pause
        val (playPauseIcon, playPauseTitle) = if (isPlaying) {
            R.drawable.ic_notif_pause to getString(R.string.notification_action_pause)
        } else {
            R.drawable.ic_notif_resume to getString(R.string.notification_action_resume)
        }
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, playPauseIcon),
                playPauseTitle,
                pendingPlayPause
            ).build()
        )

        // Action 2: Next (+3s or next recording)
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif_next),
                getString(R.string.notification_action_next),
                pendingNext
            ).build()
        )

        // Action 3: Place Bookmark
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif_bookmark),
                getString(R.string.notification_action_bookmark),
                pendingBookmark
            ).build()
        )

        // Action 4: Continue Recording (Directly append and resume recording in background)
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif_mic),
                getString(R.string.notification_action_continue_rec),
                pendingContinueRec
            ).build()
        )

        val mediaStyle = Notification.MediaStyle()
        mediaSession?.sessionToken?.let { token ->
            mediaStyle.setMediaSession(token)
        }
        // Standard compact view: Prev (0), Play/Pause (1), Next (2)
        mediaStyle.setShowActionsInCompactView(0, 1, 2)
        builder.setStyle(mediaStyle)

        val notification = builder.build()
        // HyperIsland compact actions array: all 5 in order [0, 1, 2, 3, 4]
        notification.extras.putIntArray(Notification.EXTRA_COMPACT_ACTIONS, intArrayOf(0, 1, 2, 3, 4))
        notification.extras.putBoolean("android.requestPromotedOngoing", true)
        return notification
    }

    /**
     * Transitions the HyperOS dynamic island and notification shade to the finished confirmation
     * capsule displaying "Finished recording" with the circular green checkmark badge.
     * Stays persistently until the user explicitly dismisses it (swipe to dismiss or tap to open).
     */
    fun showFinishedSavedConfirmation(title: String = getString(R.string.notification_finished_recording)) {
        releaseWakeLock()

        try {
            val session = mediaSession
            if (session != null) {
                val playbackState = PlaybackState.Builder()
                    .setActions(0L)
                    .setState(PlaybackState.STATE_NONE, 0L, 0f)
                    .build()
                session.setPlaybackState(playbackState)
                session.isActive = false
            }
        } catch (_: Exception) {}

        val notif = buildFinishedNotification(title)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
        } catch (_: Exception) {}

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, notif)
        stopSelf()
    }

    private fun stopForegroundInternal() {
        releaseWakeLock()
        try {
            mediaSession?.isActive = false
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    internal fun buildFinishedNotification(
        title: String = getString(R.string.notification_finished_recording)
    ): Notification {
        createNotificationChannel()

        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_LAST_SAVED_RECORDING
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            105,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val compactView = android.widget.RemoteViews(packageName, R.layout.notification_finished_compact).apply {
            setTextViewText(R.id.notif_finished_title, title)
            setImageViewResource(R.id.notif_finished_badge, R.drawable.ic_notif_finished_check)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        // Single-row compact notification without MediaStyle or LargeIcon (no seekbar, no 00:00, no nested circles)
        builder
            .setSmallIcon(R.drawable.ic_notif_finished_check)
            .setContentTitle(title)
            .setContentText("")
            .setContentIntent(pendingOpen)
            .setCustomContentView(compactView)
            .setCustomBigContentView(compactView)
            .setCustomHeadsUpContentView(compactView)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setColor(0xFF00C853.toInt())
            .setShowWhen(false)
            .setUsesChronometer(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val notification = builder.build()

        // HyperOS Dynamic Island (HyperIsland) compact capsule specification:
        // Left: Signature orange & white audio waveform icon
        // Center: "Finished recording" single-line white text
        // Right: Green circle ring with checkmark icon
        try {
            val focusPics = Bundle().apply {
                putParcelable(
                    "miui.focus.pic_island_left",
                    Icon.createWithResource(this@RecordingForegroundService, R.drawable.ic_island_waveform)
                )
                putParcelable(
                    "miui.focus.pic_island_right",
                    Icon.createWithResource(this@RecordingForegroundService, R.drawable.ic_island_check_ring)
                )
            }
            val safeTitle = title.replace("\"", "\\\"")
            val focusParamJson = """{"protocol":1,"enableFloat":false,"updatable":true,"islandProperty":{"islandPriority":1,"islandOrder":true,"dismissIsland":false,"bigIslandArea":{"imageTextInfoLeft":{"type":1,"picInfo":{"type":1,"pic":"miui.focus.pic_island_left"}},"textInfo":{"title":"$safeTitle","showHighlightColor":false},"imageTextInfoRight":{"type":1,"picInfo":{"type":1,"pic":"miui.focus.pic_island_right"}}},"smallIslandArea":{"picInfo":{"type":1,"pic":"miui.focus.pic_island_left"}}}}"""
            notification.extras.putBundle("miui.focus.pics", focusPics)
            notification.extras.putString("miui.focus.param", focusParamJson)
        } catch (_: Exception) {}

        notification.extras.putIntArray(Notification.EXTRA_COMPACT_ACTIONS, intArrayOf())
        notification.extras.putBoolean("android.requestPromotedOngoing", true)
        return notification
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            activeStopSaveHandler?.invoke()
        } catch (_: Exception) {}
        stopForegroundInternal()
    }

    override fun onDestroy() {
        releaseWakeLock()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Exception) {}
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "audio_recorder_studio_live_channel_v3"
        const val NOTIFICATION_ID = 4102

        // Recording actions
        const val ACTION_START_FOREGROUND = "com.example.action.START_FOREGROUND"
        const val ACTION_UPDATE_NOTIFICATION = "com.example.action.UPDATE_NOTIFICATION"
        const val ACTION_STOP_FOREGROUND = "com.example.action.STOP_FOREGROUND"
        const val ACTION_SHOW_SAVED_CONFIRMATION = "com.example.action.SHOW_SAVED_CONFIRMATION"
        const val ACTION_OPEN_LAST_SAVED_RECORDING = "com.example.action.OPEN_LAST_SAVED_RECORDING"
        const val ACTION_PROMPT_APPEND_SAVE = "com.example.action.PROMPT_APPEND_SAVE"
        const val ACTION_EXTERNAL_PAUSE = "com.example.action.EXTERNAL_PAUSE"

        const val ACTION_NOTIFICATION_PAUSE = "com.example.action.NOTIFICATION_PAUSE"
        const val ACTION_NOTIFICATION_RESUME = "com.example.action.NOTIFICATION_RESUME"
        const val ACTION_NOTIFICATION_STOP_SAVE = "com.example.action.NOTIFICATION_STOP_SAVE"
        const val ACTION_NOTIFICATION_BOOKMARK = "com.example.action.NOTIFICATION_BOOKMARK"
        const val ACTION_NOTIFICATION_SKIP_SILENCE = "com.example.action.NOTIFICATION_SKIP_SILENCE"
        const val ACTION_NOTIFICATION_DISCARD = "com.example.action.NOTIFICATION_DISCARD"

        const val ACTION_USER_PAUSE = "com.example.action.USER_PAUSE"
        const val ACTION_USER_RESUME = "com.example.action.USER_RESUME"
        const val ACTION_USER_STOP_SAVE = "com.example.action.USER_STOP_SAVE"
        const val ACTION_USER_BOOKMARK = "com.example.action.USER_BOOKMARK"
        const val ACTION_USER_TOGGLE_SKIP_SILENCE = "com.example.action.USER_TOGGLE_SKIP_SILENCE"
        const val ACTION_USER_DISCARD = "com.example.action.USER_DISCARD"

        // Playback actions
        const val ACTION_START_PLAYBACK = "com.example.action.START_PLAYBACK"
        const val ACTION_UPDATE_PLAYBACK = "com.example.action.UPDATE_PLAYBACK"

        const val ACTION_NOTIFICATION_PLAYBACK_BOOKMARK = "com.example.action.NOTIFICATION_PLAYBACK_BOOKMARK"
        const val ACTION_NOTIFICATION_PLAYBACK_PREV = "com.example.action.NOTIFICATION_PLAYBACK_PREV"
        const val ACTION_NOTIFICATION_PLAYBACK_PLAY_PAUSE = "com.example.action.NOTIFICATION_PLAYBACK_PLAY_PAUSE"
        const val ACTION_NOTIFICATION_PLAYBACK_NEXT = "com.example.action.NOTIFICATION_PLAYBACK_NEXT"
        const val ACTION_NOTIFICATION_PLAYBACK_CONTINUE_REC = "com.example.action.NOTIFICATION_PLAYBACK_CONTINUE_REC"

        const val ACTION_USER_PLAYBACK_BOOKMARK = "com.example.action.USER_PLAYBACK_BOOKMARK"
        const val ACTION_USER_PLAYBACK_PREV = "com.example.action.USER_PLAYBACK_PREV"
        const val ACTION_USER_PLAYBACK_PLAY_PAUSE = "com.example.action.USER_PLAYBACK_PLAY_PAUSE"
        const val ACTION_USER_PLAYBACK_NEXT = "com.example.action.USER_PLAYBACK_NEXT"
        const val ACTION_USER_PLAYBACK_CONTINUE_REC = "com.example.action.USER_PLAYBACK_CONTINUE_REC"
        const val ACTION_USER_PLAYBACK_SEEK = "com.example.action.USER_PLAYBACK_SEEK"

        // Extras
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_IS_PAUSED = "extra_is_paused"
        const val EXTRA_IS_SKIP_SILENCE_ACTIVE = "extra_is_skip_silence_active"
        const val EXTRA_IS_APPENDING = "extra_is_appending"
        const val EXTRA_AWAITING_SAVE = "extra_awaiting_save"

        const val EXTRA_PLAYBACK_IS_PLAYING = "extra_playback_is_playing"
        const val EXTRA_PLAYBACK_POSITION_MS = "extra_playback_position_ms"
        const val EXTRA_PLAYBACK_DURATION_MS = "extra_playback_duration_ms"
        const val EXTRA_SEEK_POSITION_MS = "extra_seek_position_ms"

        // Direct handlers for recording
        @Volatile
        var activeStopSaveHandler: (() -> Unit)? = null

        @Volatile
        var activePauseHandler: (() -> Unit)? = null

        @Volatile
        var activeResumeHandler: (() -> Unit)? = null

        @Volatile
        var activeBookmarkHandler: (() -> Unit)? = null

        @Volatile
        var activeSkipSilenceHandler: (() -> Unit)? = null

        // Direct handlers for saved recording playback
        @Volatile
        var activePlaybackBookmarkHandler: (() -> Unit)? = null

        @Volatile
        var activePlaybackPrevHandler: (() -> Unit)? = null

        @Volatile
        var activePlaybackPlayPauseHandler: (() -> Unit)? = null

        @Volatile
        var activePlaybackNextHandler: (() -> Unit)? = null

        @Volatile
        var activePlaybackContinueRecHandler: (() -> Unit)? = null

        @Volatile
        var activePlaybackSeekHandler: ((Long) -> Unit)? = null
    }
}
