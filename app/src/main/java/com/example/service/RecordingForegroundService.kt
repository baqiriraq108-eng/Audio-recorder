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
    private var isShowingAppendSaveConfirmation: Boolean = false
    private var hasEmittedFinishedConfirmation: Boolean = false
    private var sessionStartTimeMs: Long = System.currentTimeMillis()
    private var pauseStartedAtMs: Long = 0L
    private var accumulatedPausedMs: Long = 0L
    private var recordingElapsedMs: Long = 0L

    // Playback state
    private var isPlaybackMode: Boolean = false
    private var playbackTitle: String = "Recording"
    private var playbackIsPlaying: Boolean = false
    private var playbackPositionMs: Long = 0L
    private var playbackDurationMs: Long = 0L

    internal var lastRecordingPlaybackState: PlaybackState? = null
        private set

    internal var currentWaveformBars: FloatArray = createInitialRecordingWaveformBars()
        private set

    internal var lastRenderedWaveformBitmap: android.graphics.Bitmap? = null
        private set

    internal var lastRenderedWaveformOpacity: Float = 1.0f
        private set

    internal var lastRenderedWaveformAlpha: Int = 255
        private set

    internal var lastRenderedSplineHeights: FloatArray = FloatArray(0)
        private set

    internal var lastRenderedOscillatingDisplacements: FloatArray = FloatArray(0)
        private set

    internal var lastRenderedOscillatingYs: FloatArray = FloatArray(0)
        private set

    internal var lastRenderedColorPalette: IntArray = intArrayOf(
        WAVEFORM_CORAL_START_COLOR,
        WAVEFORM_CORAL_MID_COLOR,
        WAVEFORM_CORAL_END_COLOR
    )
        private set

    private var lastRecordedMetadataTitle: String? = null
    private var lastRecordedMetadataSubtitle: String? = null

    private var waveformAnimationStep: Int = 0

    internal fun isAppendSaveConfirmationVisible(): Boolean = isShowingAppendSaveConfirmation

    internal fun getActiveMediaSession(): MediaSession? = mediaSession

    private fun createInitialRecordingWaveformBars(): FloatArray {
        return FloatArray(WAVEFORM_BAR_COUNT) { idx ->
            val t = idx.toFloat() / (WAVEFORM_BAR_COUNT - 1).coerceAtLeast(1).toFloat()
            val windowEnvelope = kotlin.math.sin(t * Math.PI).toFloat().coerceIn(0.22f, 1.0f)
            val harmonicMod = 0.52f +
                0.24f * kotlin.math.abs(kotlin.math.sin((idx * 0.68f).toDouble()).toFloat()) +
                0.16f * kotlin.math.abs(kotlin.math.cos((idx * 1.25f).toDouble()).toFloat())
            (harmonicMod * (0.45f + 0.55f * windowEnvelope)).coerceIn(0.18f, 0.94f)
        }
    }

    /**
     * Advances the recording waveform by shifting existing control points left and appending
     * new live microphone voice peaks on the right to drive the horizontally centered oscillating wave.
     *
     * CRITICAL: When [isPaused] is true, this method is a strict no-op so the oscillating wave
     * remains frozen in place at its exact captured crests and troughs without flattening or resetting.
     */
    internal fun advanceRecordingWaveformBars(
        incomingPeaks: FloatArray? = null,
        isPaused: Boolean = isPausedState
    ) {
        if (isPaused) {
            // Freeze in place: preserve exact last peaks and oscillating wave shape without mutation.
            return
        }
        waveformAnimationStep++
        val bars = currentWaveformBars.copyOf()
        val shiftCount = if (incomingPeaks != null && incomingPeaks.size >= 2) 2 else 1
        for (i in 0 until (WAVEFORM_BAR_COUNT - shiftCount)) {
            bars[i] = bars[i + shiftCount]
        }

        for (s in 0 until shiftCount) {
            val targetIdx = WAVEFORM_BAR_COUNT - shiftCount + s
            val rawPeak = if (incomingPeaks != null && incomingPeaks.isNotEmpty()) {
                val pickIdx = (incomingPeaks.size - shiftCount + s).coerceIn(0, incomingPeaks.size - 1)
                incomingPeaks[pickIdx]
            } else {
                0f
            }
            val stepPhase = (waveformAnimationStep + s) * 0.52f
            val organicMotion = 0.24f +
                0.20f * kotlin.math.abs(kotlin.math.sin(stepPhase.toDouble()).toFloat()) +
                0.14f * kotlin.math.abs(kotlin.math.cos((stepPhase * 1.85f).toDouble()).toFloat())
            val combinedAmplitude = if (rawPeak > 0.02f) {
                (rawPeak * 0.85f + organicMotion * 0.25f).coerceIn(0.18f, 1.0f)
            } else {
                organicMotion.coerceIn(0.18f, 0.82f)
            }
            bars[targetIdx] = combinedAmplitude
        }
        currentWaveformBars = bars
    }

    /**
     * Computes signed vertical displacements (-1.0f..1.0f) around the horizontal midline (`centerY`)
     * so the cubic-Bezier waveform oscillates smoothly up and down (alternating crests above the
     * midline and troughs below the midline) across the full horizontal width.
     */
    internal fun computeOscillatingWaveDisplacements(bars: FloatArray = currentWaveformBars): FloatArray {
        val count = bars.size.coerceAtLeast(2)
        return FloatArray(count) { i ->
            val t = i.toFloat() / (count - 1).toFloat()
            // Smooth edge taper near 0.0 and 1.0 so the oscillating wave meets the midline cleanly at the ends,
            // while remaining full-amplitude across the horizontal span (NOT climbing diagonally upward).
            val edgeWindow = (0.32f + 0.68f * kotlin.math.sin(t * Math.PI).toFloat()).coerceIn(0.32f, 1.0f)
            val normalizedAmp = bars[i].coerceIn(0.14f, 1.0f) * edgeWindow
            val sign = if (i % 2 == 0) -1f else 1f
            (sign * normalizedAmp).coerceIn(-0.96f, 0.96f)
        }
    }

    /**
     * Renders a Single Smooth Continuous Oscillating Audio Waveform centered along the horizontal
     * midline (`centerY = heightPx / 2f`) using `Canvas.drawPath` with cubic-Bezier curves in the
     * Studio Coral-Orange linear gradient (`#E38851` -> `#F08C4B` -> `#FFB485`) and a subtle
     * translucent coral glow (`35%` opacity tapering to `0%` toward the center baseline).
     *
     * When [isPaused] is true:
     * - Freezes the exact oscillating wave shape in place.
     * - Dims the waveform opacity to 40% (`alpha = 102` / `opacity = 0.40f`).
     */
    internal fun renderRecordingWaveformBitmap(
        bars: FloatArray = currentWaveformBars,
        isPaused: Boolean = isPausedState
    ): android.graphics.Bitmap {
        val widthPx = WAVEFORM_BITMAP_WIDTH_PX
        val heightPx = WAVEFORM_BITMAP_HEIGHT_PX
        val bitmap = android.graphics.Bitmap.createBitmap(
            widthPx,
            heightPx,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)

        val opacityFactor = if (isPaused) PAUSED_WAVEFORM_OPACITY else ACTIVE_WAVEFORM_OPACITY
        val targetAlpha = if (isPaused) {
            PAUSED_WAVEFORM_ALPHA
        } else {
            (255f * opacityFactor).toInt().coerceIn(0, 255)
        }
        lastRenderedWaveformOpacity = opacityFactor
        lastRenderedWaveformAlpha = targetAlpha

        val displacements = computeOscillatingWaveDisplacements(bars)
        lastRenderedOscillatingDisplacements = displacements.copyOf()
        lastRenderedSplineHeights = FloatArray(displacements.size) { idx ->
            kotlin.math.abs(displacements[idx])
        }

        val pointCount = displacements.size.coerceAtLeast(2)
        val horizontalPadding = 6f
        val usableWidth = (widthPx - horizontalPadding * 2f).coerceAtLeast(1f)
        val centerY = heightPx / 2f
        val maxHalfAmplitude = heightPx * 0.42f

        val xs = FloatArray(pointCount)
        val ys = FloatArray(pointCount)
        for (i in 0 until pointCount) {
            val fraction = i.toFloat() / (pointCount - 1).toFloat()
            xs[i] = horizontalPadding + fraction * usableWidth
            ys[i] = (centerY + displacements[i] * maxHalfAmplitude)
                .coerceIn(heightPx * 0.06f, heightPx * 0.94f)
        }
        lastRenderedOscillatingYs = ys.copyOf()

        // 1. Subtle horizontal center axis baseline
        val centerBaselinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.5f
            color = WAVEFORM_CORAL_MID_COLOR
            alpha = (42f * opacityFactor).toInt().coerceIn(8, 255)
        }
        canvas.drawLine(horizontalPadding, centerY, widthPx - horizontalPadding, centerY, centerBaselinePaint)

        // 2. Construct smooth continuous cubic-Bezier oscillating wave path centered on centerY
        val wavePath = android.graphics.Path()
        val glowPath = android.graphics.Path()
        wavePath.moveTo(xs[0], ys[0])
        glowPath.moveTo(xs[0], centerY)
        glowPath.lineTo(xs[0], ys[0])

        val tension = 0.24f
        for (i in 0 until pointCount - 1) {
            val x0 = if (i > 0) xs[i - 1] else xs[i]
            val y0 = if (i > 0) ys[i - 1] else ys[i]
            val x1 = xs[i]
            val y1 = ys[i]
            val x2 = xs[i + 1]
            val y2 = ys[i + 1]
            val x3 = if (i + 2 < pointCount) xs[i + 2] else x2
            val y3 = if (i + 2 < pointCount) ys[i + 2] else y2

            val cp1x = x1 + (x2 - x0) * tension
            val cp1y = (y1 + (y2 - y0) * tension).coerceIn(heightPx * 0.04f, heightPx * 0.96f)
            val cp2x = x2 - (x3 - x1) * tension
            val cp2y = (y2 - (y3 - y1) * tension).coerceIn(heightPx * 0.04f, heightPx * 0.96f)

            wavePath.cubicTo(cp1x, cp1y, cp2x, cp2y, x2, y2)
            glowPath.cubicTo(cp1x, cp1y, cp2x, cp2y, x2, y2)
        }

        glowPath.lineTo(xs[pointCount - 1], centerY)
        glowPath.close()

        // 3. Subtle translucent coral glow (35% opacity = alpha 89 tapering to 0% at the center baseline)
        val crestGlowAlpha = (WAVEFORM_GLOW_MAX_ALPHA * opacityFactor).toInt().coerceIn(0, 255)
        val crestGlowColor = (crestGlowAlpha shl 24) or (WAVEFORM_CORAL_MID_COLOR and 0x00FFFFFF)
        val centerZeroColor = 0x00F08C4B
        val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            shader = android.graphics.LinearGradient(
                0f,
                heightPx * 0.06f,
                0f,
                heightPx * 0.94f,
                intArrayOf(crestGlowColor, centerZeroColor, crestGlowColor),
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(glowPath, glowPaint)

        // 4. Primary smooth continuous oscillating wave stroke in Studio Coral-Orange Gradient (#E38851 -> #F08C4B -> #FFB485)
        val palette = intArrayOf(
            WAVEFORM_CORAL_START_COLOR,
            WAVEFORM_CORAL_MID_COLOR,
            WAVEFORM_CORAL_END_COLOR
        )
        lastRenderedColorPalette = palette
        val waveStrokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4.5f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            shader = android.graphics.LinearGradient(
                horizontalPadding,
                0f,
                widthPx - horizontalPadding,
                0f,
                palette,
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            alpha = targetAlpha
        }
        canvas.drawPath(wavePath, waveStrokePaint)

        return bitmap
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
        RecordingIslandOverlayManager.bindCallbacks(
            onBookmark = { dispatchBookmarkAction() },
            onStopSave = { handleRecordingStopSaveFromNotification() },
            onPauseResume = {
                if (isPausedState) {
                    handleRecordingResumeFromNotification()
                } else {
                    handleRecordingPauseFromNotification()
                }
            },
            onConfirmAppendSave = { handleConfirmAppendSaveFromNotification() },
            onCancelAppendSave = { handleCancelAppendSaveFromNotification() }
        )
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

    internal fun handleRecordingPauseFromNotification() {
        val now = System.currentTimeMillis()
        if (!isPausedState) {
            isPausedState = true
            pauseStartedAtMs = now
            if (currentTitle == "Recording") {
                currentTitle = getString(R.string.notification_status_paused)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(
                NOTIFICATION_ID,
                buildRecordingNotification(
                    title = currentTitle,
                    statusText = currentStatus,
                    isPaused = true,
                    isSkipSilence = isSkipSilenceActive,
                    isAwaitingSave = false
                )
            )
        }
        dispatchPauseAction()
    }

    internal fun handleRecordingResumeFromNotification() {
        val now = System.currentTimeMillis()
        isShowingAppendSaveConfirmation = false
        hasEmittedFinishedConfirmation = false
        if (isPausedState) {
            isPausedState = false
            if (pauseStartedAtMs > 0L) {
                accumulatedPausedMs += (now - pauseStartedAtMs).coerceAtLeast(0L)
                pauseStartedAtMs = 0L
            }
            if (currentTitle == getString(R.string.notification_status_paused) || currentTitle == "Paused") {
                currentTitle = "Recording"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(
                NOTIFICATION_ID,
                buildRecordingNotification(
                    title = currentTitle,
                    statusText = currentStatus,
                    isPaused = false,
                    isSkipSilence = isSkipSilenceActive,
                    isAwaitingSave = false
                )
            )
        }
        dispatchResumeAction()
    }

    internal fun handleRecordingStopSaveFromNotification() {
        showAppendSaveConfirmation()
        dispatchStopSaveAction()
    }

    internal fun dispatchConfirmAppendSaveAction() {
        val handler = activeConfirmAppendSaveHandler
        if (handler != null) {
            handler.invoke()
        } else {
            sendBroadcast(Intent(ACTION_USER_CONFIRM_APPEND_SAVE).setPackage(packageName))
        }
    }

    internal fun dispatchCancelAppendSaveAction() {
        val handler = activeCancelAppendSaveHandler
        if (handler != null) {
            handler.invoke()
        } else {
            sendBroadcast(Intent(ACTION_USER_CANCEL_APPEND_SAVE).setPackage(packageName))
        }
    }

    internal fun handleConfirmAppendSaveFromNotification() {
        isShowingAppendSaveConfirmation = false
        isAppendingSession = false
        showFinishedSavedConfirmation(getString(R.string.notification_finished_recording))
        dispatchConfirmAppendSaveAction()
    }

    internal fun handleCancelAppendSaveFromNotification() {
        isShowingAppendSaveConfirmation = false
        isAppendingSession = false
        dispatchCancelAppendSaveAction()
        stopForegroundInternal()
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
                try {
                    setPlaybackToLocal(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                } catch (_: Exception) {}
                setCallback(object : MediaSession.Callback() {
                    override fun onPause() {
                        if (isPlaybackMode) {
                            dispatchPlaybackPlayPauseAction()
                        } else if (isPausedState) {
                            handleRecordingResumeFromNotification()
                        } else {
                            handleRecordingPauseFromNotification()
                        }
                    }

                    override fun onPlay() {
                        if (isPlaybackMode) {
                            dispatchPlaybackPlayPauseAction()
                        } else if (isPausedState) {
                            handleRecordingResumeFromNotification()
                        } else {
                            handleRecordingPauseFromNotification()
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
                        } else {
                            dispatchBookmarkAction()
                        }
                    }

                    override fun onSkipToNext() {
                        if (isPlaybackMode) {
                            dispatchPlaybackNextAction()
                        } else {
                            handleRecordingStopSaveFromNotification()
                        }
                    }

                    override fun onStop() {
                        if (isPlaybackMode) {
                            stopForegroundInternal()
                        } else {
                            handleRecordingStopSaveFromNotification()
                        }
                    }

                    override fun onCustomAction(action: String, extras: Bundle?) {
                        when (action) {
                            ACTION_NOTIFICATION_PAUSE, ACTION_USER_PAUSE -> {
                                handleRecordingPauseFromNotification()
                            }
                            ACTION_NOTIFICATION_RESUME, ACTION_USER_RESUME -> {
                                handleRecordingResumeFromNotification()
                            }
                            ACTION_NOTIFICATION_STOP_SAVE, ACTION_USER_STOP_SAVE -> {
                                handleRecordingStopSaveFromNotification()
                            }
                            ACTION_NOTIFICATION_CONFIRM_APPEND_SAVE, ACTION_USER_CONFIRM_APPEND_SAVE -> {
                                handleConfirmAppendSaveFromNotification()
                            }
                            ACTION_NOTIFICATION_CANCEL_APPEND_SAVE, ACTION_USER_CANCEL_APPEND_SAVE -> {
                                handleCancelAppendSaveFromNotification()
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
                isActive = false
            }
        } catch (_: Exception) {}
    }

    private fun formatElapsedTimer(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun computeCurrentRecordingElapsedMs(): Long {
        if (recordingElapsedMs > 0L) return recordingElapsedMs
        val now = System.currentTimeMillis()
        val activePauseDelta = if (isPausedState && pauseStartedAtMs > 0L) {
            (now - pauseStartedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return (now - sessionStartTimeMs - accumulatedPausedMs - activePauseDelta).coerceAtLeast(0L)
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
            val elapsed = computeCurrentRecordingElapsedMs()
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
                playbackStateBuilder.addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        ACTION_NOTIFICATION_STOP_SAVE,
                        getString(R.string.notification_action_stop_save),
                        R.drawable.ic_notif_stop_save
                    ).build()
                )
            }

            val builtState = playbackStateBuilder.build()
            lastRecordingPlaybackState = builtState
            // CRITICAL: During Recording (`isPlaybackMode == false`), NEVER activate MediaSession or
            // publish STATE_PLAYING to the OS MediaSessionService, because Android 13-16 and HyperOS
            // will hijack the notification into a MediaStyle carousel (injecting the 00:00 seekbar and Cast button).
            session.isActive = false
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
                isShowingAppendSaveConfirmation = false
                hasEmittedFinishedConfirmation = false
                currentTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Recording"
                currentStatus = intent.getStringExtra(EXTRA_STATUS).orEmpty()
                isPausedState = intent.getBooleanExtra(EXTRA_IS_PAUSED, false)
                isSkipSilenceActive = intent.getBooleanExtra(EXTRA_IS_SKIP_SILENCE_ACTIVE, false)
                isAppendingSession = intent.getBooleanExtra(EXTRA_IS_APPENDING, false)
                recordingElapsedMs = intent.getLongExtra(EXTRA_RECORDING_ELAPSED_MS, 0L).coerceAtLeast(0L)
                sessionStartTimeMs = (System.currentTimeMillis() - recordingElapsedMs).coerceAtMost(System.currentTimeMillis())
                pauseStartedAtMs = if (isPausedState) System.currentTimeMillis() else 0L
                accumulatedPausedMs = 0L
                val incomingStartPeaks = intent.getFloatArrayExtra(EXTRA_WAVEFORM_PEAKS)
                if (!isPausedState) {
                    advanceRecordingWaveformBars(incomingStartPeaks, isPaused = false)
                }
                acquireWakeLock()

                val notification = buildRecordingNotification(
                    title = currentTitle,
                    statusText = currentStatus,
                    isPaused = isPausedState,
                    isSkipSilence = isSkipSilenceActive,
                    isAwaitingSave = false
                )
                if (!RecordingIslandOverlayManager.canDrawSystemOverlay(this)) {
                    RecordingIslandOverlayManager.maybePromptOverlayPermissionOnce(this)
                }
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
            }

            ACTION_UPDATE_NOTIFICATION -> {
                isPlaybackMode = false
                val clearConfirm = intent.getBooleanExtra(EXTRA_CLEAR_APPEND_CONFIRMATION, false)
                if (clearConfirm) {
                    isShowingAppendSaveConfirmation = false
                }
                val previousPaused = isPausedState
                currentTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: currentTitle
                currentStatus = intent.getStringExtra(EXTRA_STATUS) ?: currentStatus
                if (!isShowingAppendSaveConfirmation) {
                    isPausedState = intent.getBooleanExtra(EXTRA_IS_PAUSED, isPausedState)
                }
                if (intent.hasExtra(EXTRA_IS_SKIP_SILENCE_ACTIVE)) {
                    isSkipSilenceActive = intent.getBooleanExtra(EXTRA_IS_SKIP_SILENCE_ACTIVE, isSkipSilenceActive)
                }
                if (intent.hasExtra(EXTRA_IS_APPENDING)) {
                    isAppendingSession = intent.getBooleanExtra(EXTRA_IS_APPENDING, isAppendingSession)
                }
                if (intent.hasExtra(EXTRA_RECORDING_ELAPSED_MS)) {
                    recordingElapsedMs = intent.getLongExtra(EXTRA_RECORDING_ELAPSED_MS, recordingElapsedMs).coerceAtLeast(0L)
                }
                val incomingPeaks = intent.getFloatArrayExtra(EXTRA_WAVEFORM_PEAKS)
                if (!isPausedState) {
                    advanceRecordingWaveformBars(incomingPeaks, isPaused = false)
                }
                val now = System.currentTimeMillis()
                if (!previousPaused && isPausedState) {
                    pauseStartedAtMs = now
                } else if (previousPaused && !isPausedState && pauseStartedAtMs > 0L) {
                    accumulatedPausedMs += (now - pauseStartedAtMs).coerceAtLeast(0L)
                    pauseStartedAtMs = 0L
                }
                val isAwaitingSave = intent.getBooleanExtra(EXTRA_AWAITING_SAVE, false)
                if (!isShowingAppendSaveConfirmation) {
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
                handleRecordingPauseFromNotification()
            }

            ACTION_NOTIFICATION_RESUME -> {
                handleRecordingResumeFromNotification()
            }

            ACTION_NOTIFICATION_STOP_SAVE -> {
                handleRecordingStopSaveFromNotification()
            }

            ACTION_SHOW_APPEND_SAVE_CONFIRMATION -> {
                showAppendSaveConfirmation()
            }

            ACTION_NOTIFICATION_CONFIRM_APPEND_SAVE -> {
                handleConfirmAppendSaveFromNotification()
            }

            ACTION_NOTIFICATION_CANCEL_APPEND_SAVE -> {
                handleCancelAppendSaveFromNotification()
            }

            ACTION_SHOW_SAVED_CONFIRMATION -> {
                val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.notification_finished_recording)
                showFinishedSavedConfirmation(title)
            }

            ACTION_DISMISS_FINISHED_NOTIFICATION -> {
                RecordingIslandOverlayManager.dismissBothFinishedNotificationAndOverlay(this)
                stopSelf()
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

        val nowMs = System.currentTimeMillis()
        val elapsedMs = computeCurrentRecordingElapsedMs()
        val effectiveWhenMs = if (recordingElapsedMs > 0L) {
            (nowMs - recordingElapsedMs).coerceAtMost(nowMs)
        } else {
            (sessionStartTimeMs + accumulatedPausedMs).coerceAtMost(nowMs)
        }

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

        val stopSaveServiceIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_NOTIFICATION_STOP_SAVE
        }
        val pendingStopSave = PendingIntent.getService(
            this,
            103,
            stopSaveServiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bookmarkServiceIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_NOTIFICATION_BOOKMARK
        }
        val pendingBookmark = PendingIntent.getService(
            this,
            104,
            bookmarkServiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val baseSubtitle = statusText.ifBlank {
            if (isPaused) getString(R.string.notification_status_paused)
            else getString(R.string.notification_status_recording)
        }
        val formattedTimer = formatElapsedTimer(elapsedMs)
        val subtitle = if (baseSubtitle.contains(formattedTimer)) {
            baseSubtitle
        } else {
            "$baseSubtitle • $formattedTimer"
        }

        val waveformBitmap = renderRecordingWaveformBitmap(
            bars = currentWaveformBars,
            isPaused = isPaused
        )
        lastRenderedWaveformBitmap = waveformBitmap

        val chronometerBaseRealtimeMs = (android.os.SystemClock.elapsedRealtime() - elapsedMs)
            .coerceAtMost(android.os.SystemClock.elapsedRealtime())
        val (pauseResumeIconRes, pauseResumeLabel) = if (isPaused) {
            R.drawable.ic_notif_resume to getString(R.string.notification_action_resume)
        } else {
            R.drawable.ic_notif_pause to getString(R.string.notification_action_pause)
        }
        val headerStatusTitle = if (isPaused) {
            getString(R.string.notification_status_paused)
        } else {
            title.ifBlank { getString(R.string.notification_status_recording) }
        }
        val blendedHeaderTitle = buildGradientBlendedEnglishTitle(headerStatusTitle)

        // Single fixed-height non-expandable dark card (#1A1614, 24dp radius, zero Cast button, zero seekbar)
        val recordingCardView = android.widget.RemoteViews(packageName, R.layout.notification_recording_live).apply {
            setOnClickPendingIntent(R.id.notif_recording_header, pendingOpen)
            setTextViewText(R.id.notif_recording_title, blendedHeaderTitle)
            if (isPaused || isAwaitingSave) {
                setViewVisibility(R.id.notif_recording_chronometer, android.view.View.GONE)
                setViewVisibility(R.id.notif_recording_paused_timer, android.view.View.VISIBLE)
                setTextViewText(R.id.notif_recording_paused_timer, formattedTimer)
            } else {
                setViewVisibility(R.id.notif_recording_paused_timer, android.view.View.GONE)
                setViewVisibility(R.id.notif_recording_chronometer, android.view.View.VISIBLE)
                setChronometer(R.id.notif_recording_chronometer, chronometerBaseRealtimeMs, null, true)
            }
            // In the notification shade, the waveform is hidden (zero waveform) while keeping the header and 3 action buttons
            setViewVisibility(R.id.notif_recording_waveform, android.view.View.GONE)
            // 1. Far-Left Edge: Bookmark button
            setOnClickPendingIntent(R.id.notif_recording_btn_bookmark, pendingBookmark)
            // 2. Center: Save (Stop & Save) button
            setOnClickPendingIntent(R.id.notif_recording_btn_stop_save, pendingStopSave)
            // 3. Far-Right Edge: Pause / Resume toggle button
            setImageViewResource(R.id.notif_action_pause_resume_icon, pauseResumeIconRes)
            setTextViewText(R.id.notif_action_pause_resume_text, pauseResumeLabel)
            setOnClickPendingIntent(R.id.notif_recording_btn_pause_resume, pauseOrResumePendingIntent)
        }

        // Synchronize Xiaomi-Style System Overlay Dynamic Capsule (`RecordingIslandOverlayManager`)
        if (!isPlaybackMode) {
            RecordingIslandOverlayManager.updateRecordingOverlayState(
                context = this,
                title = headerStatusTitle,
                isPaused = isPaused,
                elapsedMs = elapsedMs,
                waveformBitmap = waveformBitmap,
                oscillatingDisplacements = lastRenderedOscillatingDisplacements,
                isShowingAppendConfirmation = false
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        // CRITICAL ARCHITECTURAL RULES FOR RECORDING NOTIFICATION:
        // 1. DO NOT attach Notification.MediaStyle() or DecoratedCustomViewStyle() (builder.setStyle is NEVER called).
        // 2. DO NOT call setCustomBigContentView(...) (keep bigContentView = null so Android/HyperOS renders ZERO expand/collapse chevron arrows '^').
        // 3. DO NOT attach standard builder.addAction(...) footer rows.
        builder
            .setSmallIcon(R.drawable.ic_notif_mic)
            .setContentTitle(headerStatusTitle)
            .setContentText(subtitle)
            .setContentIntent(pendingOpen)
            .setCustomContentView(recordingCardView)
            .setCustomHeadsUpContentView(recordingCardView)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setColor(0xFFF08C4B.toInt())
            .setShowWhen(false)
            .setWhen(effectiveWhenMs)
            .setUsesChronometer(!isPaused && !isAwaitingSave)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setChronometerCountDown(false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val notification = builder.build()
        @Suppress("DEPRECATION")
        notification.bigContentView = null
        notification.actions = arrayOf()
        notification.extras.remove(Notification.EXTRA_MEDIA_SESSION)
        notification.extras.remove(Notification.EXTRA_TEMPLATE)
        notification.extras.remove(Notification.EXTRA_COMPACT_ACTIONS)
        notification.extras.putBoolean(Notification.EXTRA_SHOW_CHRONOMETER, !isPaused && !isAwaitingSave)
        notification.extras.putStringArray(
            EXTRA_RECORDING_CUSTOM_ACTION_ORDER,
            arrayOf(
                getString(R.string.notification_action_bookmark),
                getString(R.string.notification_action_stop_save),
                pauseResumeLabel
            )
        )
        notification.extras.putBoolean("android.requestPromotedOngoing", true)
        // Completely remove Cast / media-routing button in Recording state
        notification.extras.putBoolean(EXTRA_HIDE_CAST_BUTTON, true)
        notification.extras.putBoolean("android.hideMediaOutputSwitcher", true)
        notification.extras.putBoolean("miui.media.hideCastButton", true)

        // Dynamic Island / Status Bar Capsule configuration during active recording
        try {
            val focusPics = Bundle().apply {
                putParcelable(
                    "miui.focus.pic_island_left",
                    Icon.createWithResource(this@RecordingForegroundService, R.drawable.ic_island_waveform)
                )
                putParcelable(
                    "miui.focus.pic_island_right",
                    Icon.createWithResource(this@RecordingForegroundService, R.drawable.ic_notif_mic)
                )
            }
            val safeTitle = headerStatusTitle.replace("\"", "\\\"")
            val focusParamJson = """{"protocol":1,"enableFloat":true,"updatable":true,"islandProperty":{"islandPriority":2,"islandOrder":true,"dismissIsland":false,"bigIslandArea":{"imageTextInfoLeft":{"type":1,"picInfo":{"type":1,"pic":"miui.focus.pic_island_left"}},"textInfo":{"title":"$safeTitle","showHighlightColor":false},"imageTextInfoRight":{"type":1,"picInfo":{"type":1,"pic":"miui.focus.pic_island_right"}}},"smallIslandArea":{"picInfo":{"type":1,"pic":"miui.focus.pic_island_left"}}}}"""
            notification.extras.putBoolean("miui.focus.enable", true)
            notification.extras.putBundle("miui.focus.pics", focusPics)
            notification.extras.putString("miui.focus.param", focusParamJson)
        } catch (_: Exception) {}

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
     * Transitions the notification to the inline Append Save Confirmation message
     * featuring equal-sized Θ Don't and Ω Save buttons with warm gradient blending.
     */
    fun showAppendSaveConfirmation() {
        isShowingAppendSaveConfirmation = true
        hasEmittedFinishedConfirmation = false
        isPausedState = true

        try {
            val session = mediaSession
            if (session != null) {
                val playbackState = PlaybackState.Builder()
                    .setActions(0L)
                    .setState(PlaybackState.STATE_NONE, 0L, 0f)
                    .build()
                lastRecordingPlaybackState = playbackState
                session.setPlaybackState(playbackState)
                session.isActive = false
            }
        } catch (_: Exception) {}

        val notif = buildAppendSaveConfirmationNotification()
        val waveformBmp = lastRenderedWaveformBitmap ?: renderRecordingWaveformBitmap(currentWaveformBars, isPaused = true)
        RecordingIslandOverlayManager.updateRecordingOverlayState(
            context = this,
            title = getString(R.string.notification_status_paused),
            isPaused = true,
            elapsedMs = computeCurrentRecordingElapsedMs(),
            waveformBitmap = waveformBmp,
            oscillatingDisplacements = lastRenderedOscillatingDisplacements,
            isShowingAppendConfirmation = true
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, notif)
    }

    internal fun buildAppendSaveConfirmationNotification(): Notification {
        createNotificationChannel()

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            106,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val confirmSaveIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_NOTIFICATION_CONFIRM_APPEND_SAVE
        }
        val pendingConfirmSave = PendingIntent.getService(
            this,
            107,
            confirmSaveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelDontIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_NOTIFICATION_CANCEL_APPEND_SAVE
        }
        val pendingCancelDont = PendingIntent.getService(
            this,
            108,
            cancelDontIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val blendedSaveLabel = buildGradientBlendedEnglishTitle("Save")
        val blendedDontLabel = buildGradientBlendedEnglishTitle("Don't")

        val compactConfirmView = android.widget.RemoteViews(packageName, R.layout.notification_append_confirm_compact).apply {
            setImageViewResource(R.id.notif_append_dont_icon, R.drawable.ic_notif_greek_theta)
            setTextViewText(R.id.notif_append_dont_text, blendedDontLabel)
            setOnClickPendingIntent(R.id.notif_append_dont_button, pendingCancelDont)

            setImageViewResource(R.id.notif_append_save_icon, R.drawable.ic_notif_greek_sigma)
            setTextViewText(R.id.notif_append_save_text, blendedSaveLabel)
            setOnClickPendingIntent(R.id.notif_append_save_button, pendingConfirmSave)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val dontAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_greek_theta),
            buildGradientBlendedEnglishTitle("Θ Don't"),
            pendingCancelDont
        ).build()

        val saveAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_greek_sigma),
            buildGradientBlendedEnglishTitle("Σ Save"),
            pendingConfirmSave
        ).build()

        builder
            .setSmallIcon(R.drawable.ic_notif_finished_check)
            .setContentTitle(blendedSaveLabel)
            .setContentText("")
            .setContentIntent(pendingOpen)
            .setCustomContentView(compactConfirmView)
            .addAction(dontAction)
            .addAction(saveAction)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setColor(0xFFE38851.toInt())
            .setShowWhen(false)
            .setUsesChronometer(false)

        if (!RecordingIslandOverlayManager.canDrawSystemOverlay(this)) {
            builder.setCustomHeadsUpContentView(compactConfirmView)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val notification = builder.build()
        @Suppress("DEPRECATION")
        notification.bigContentView = null
        notification.extras.putIntArray(Notification.EXTRA_COMPACT_ACTIONS, intArrayOf(0, 1))
        notification.extras.putBoolean("android.requestPromotedOngoing", true)
        notification.extras.putBoolean("miui.focus.enable", false)
        notification.extras.putString(
            "miui.focus.param",
            """{"protocol":1,"enableFloat":false,"updatable":true,"islandProperty":{"dismissIsland":true}}"""
        )
        return notification
    }

    fun showFinishedSavedConfirmation(title: String = getString(R.string.notification_finished_recording)) {
        isShowingAppendSaveConfirmation = false
        RecordingIslandOverlayManager.showFinishedCompletionOverlay(this, title)
        if (hasEmittedFinishedConfirmation) {
            return
        }
        hasEmittedFinishedConfirmation = true
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
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, notif)
        stopSelf()
    }

    private fun stopForegroundInternal() {
        RecordingIslandOverlayManager.dismissAndReset()
        releaseWakeLock()
        lastRecordedMetadataTitle = null
        lastRecordedMetadataSubtitle = null
        try {
            mediaSession?.isActive = false
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    internal fun buildGradientBlendedEnglishTitle(text: String): android.text.SpannableString {
        val spannable = android.text.SpannableString(text)
        val length = text.length
        if (length == 0) return spannable

        val startR = 0xE3
        val startG = 0x88
        val startB = 0x51
        val endR = 0xF4
        val endG = 0xD7
        val endB = 0xC7

        for (i in 0 until length) {
            val fraction = if (length > 1) i.toFloat() / (length - 1).toFloat() else 0f
            val r = (startR + ((endR - startR) * fraction).toInt()).coerceIn(0, 255)
            val g = (startG + ((endG - startG) * fraction).toInt()).coerceIn(0, 255)
            val b = (startB + ((endB - startB) * fraction).toInt()).coerceIn(0, 255)
            val color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(color),
                i,
                i + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
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

        val dismissIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_DISMISS_FINISHED_NOTIFICATION
        }
        val pendingDismiss = PendingIntent.getService(
            this,
            109,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val blendedTitle = buildGradientBlendedEnglishTitle(title)
        val compactView = android.widget.RemoteViews(packageName, R.layout.notification_finished_compact).apply {
            setTextViewText(R.id.notif_finished_title, blendedTitle)
            setImageViewResource(R.id.notif_finished_badge, R.drawable.ic_notif_finished_check)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, FINISHED_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        // Minimalist single-row compact notification in the shade (no Heads-Up Notification banner)
        builder
            .setSmallIcon(R.drawable.ic_notif_finished_check)
            .setContentTitle(blendedTitle)
            .setContentText("")
            .setContentIntent(pendingOpen)
            .setDeleteIntent(pendingDismiss)
            .setCustomContentView(compactView)
            .setCustomBigContentView(compactView)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setColor(0xFFE38851.toInt())
            .setShowWhen(false)
            .setUsesChronometer(false)

        @Suppress("DEPRECATION")
        builder.setPriority(Notification.PRIORITY_DEFAULT)
        @Suppress("DEPRECATION")
        builder.setDefaults(0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        }

        val notification = builder.build()
        @Suppress("DEPRECATION")
        notification.headsUpContentView = null

        // HyperOS Dynamic Island (HyperIsland) compact capsule specification:
        // Left: Signature orange & white audio waveform icon
        // Center: "Finished recording" single-line English text
        // Right: Borderless Greek Omega (Ω) completion emblem with warm gradient blend
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
        notification.extras.putBoolean("android.requestPromotedOngoing", false)
        return notification
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val liveChannel = NotificationChannel(
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
            nm?.createNotificationChannel(liveChannel)

            val finishedChannel = NotificationChannel(
                FINISHED_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm?.createNotificationChannel(finishedChannel)
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
        if (!RecordingIslandOverlayManager.lastIsShowingFinishedCompletion) {
            RecordingIslandOverlayManager.dismissAndReset()
        }
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
        const val FINISHED_CHANNEL_ID = "audio_recorder_studio_finished_channel_v1"
        const val NOTIFICATION_ID = 4102
        const val FINISHED_NOTIFICATION_ID = 4103

        // Recording actions
        const val ACTION_START_FOREGROUND = "com.example.action.START_FOREGROUND"
        const val ACTION_UPDATE_NOTIFICATION = "com.example.action.UPDATE_NOTIFICATION"
        const val ACTION_STOP_FOREGROUND = "com.example.action.STOP_FOREGROUND"
        const val ACTION_SHOW_SAVED_CONFIRMATION = "com.example.action.SHOW_SAVED_CONFIRMATION"
        const val ACTION_DISMISS_FINISHED_NOTIFICATION = "com.example.action.DISMISS_FINISHED_NOTIFICATION"
        const val ACTION_SHOW_APPEND_SAVE_CONFIRMATION = "com.example.action.SHOW_APPEND_SAVE_CONFIRMATION"
        const val ACTION_OPEN_LAST_SAVED_RECORDING = "com.example.action.OPEN_LAST_SAVED_RECORDING"
        const val ACTION_PROMPT_APPEND_SAVE = "com.example.action.PROMPT_APPEND_SAVE"
        const val ACTION_EXTERNAL_PAUSE = "com.example.action.EXTERNAL_PAUSE"

        const val ACTION_NOTIFICATION_PAUSE = "com.example.action.NOTIFICATION_PAUSE"
        const val ACTION_NOTIFICATION_RESUME = "com.example.action.NOTIFICATION_RESUME"
        const val ACTION_NOTIFICATION_STOP_SAVE = "com.example.action.NOTIFICATION_STOP_SAVE"
        const val ACTION_NOTIFICATION_CONFIRM_APPEND_SAVE = "com.example.action.NOTIFICATION_CONFIRM_APPEND_SAVE"
        const val ACTION_NOTIFICATION_CANCEL_APPEND_SAVE = "com.example.action.NOTIFICATION_CANCEL_APPEND_SAVE"
        const val ACTION_NOTIFICATION_BOOKMARK = "com.example.action.NOTIFICATION_BOOKMARK"
        const val ACTION_NOTIFICATION_SKIP_SILENCE = "com.example.action.NOTIFICATION_SKIP_SILENCE"
        const val ACTION_NOTIFICATION_DISCARD = "com.example.action.NOTIFICATION_DISCARD"

        const val ACTION_USER_PAUSE = "com.example.action.USER_PAUSE"
        const val ACTION_USER_RESUME = "com.example.action.USER_RESUME"
        const val ACTION_USER_STOP_SAVE = "com.example.action.USER_STOP_SAVE"
        const val ACTION_USER_CONFIRM_APPEND_SAVE = "com.example.action.USER_CONFIRM_APPEND_SAVE"
        const val ACTION_USER_CANCEL_APPEND_SAVE = "com.example.action.USER_CANCEL_APPEND_SAVE"
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
        const val EXTRA_CLEAR_APPEND_CONFIRMATION = "extra_clear_append_confirmation"
        const val EXTRA_RECORDING_ELAPSED_MS = "extra_recording_elapsed_ms"
        const val EXTRA_WAVEFORM_PEAKS = "extra_waveform_peaks"
        const val EXTRA_HIDE_CAST_BUTTON = "android.media.extra.HIDE_CAST_BUTTON"
        const val EXTRA_RECORDING_CUSTOM_ACTION_ORDER = "com.example.extra.RECORDING_CUSTOM_ACTION_ORDER"

        const val WAVEFORM_BAR_COUNT = 32
        const val WAVEFORM_BITMAP_WIDTH_PX = 640
        const val WAVEFORM_BITMAP_HEIGHT_PX = 96

        const val WAVEFORM_CORAL_START_COLOR = 0xFFE38851.toInt()
        const val WAVEFORM_CORAL_MID_COLOR = 0xFFF08C4B.toInt()
        const val WAVEFORM_CORAL_END_COLOR = 0xFFFFB485.toInt()
        const val WAVEFORM_CORAL_HIGHLIGHT_COLOR = 0xFFF4D7C7.toInt()

        const val ACTIVE_WAVEFORM_OPACITY = 1.0f
        const val PAUSED_WAVEFORM_OPACITY = 0.40f
        const val PAUSED_WAVEFORM_ALPHA = 102
        const val WAVEFORM_GLOW_MAX_ALPHA = 89f // 35% opacity tapering to 0% toward center baseline

        const val EXTRA_PLAYBACK_IS_PLAYING = "extra_playback_is_playing"
        const val EXTRA_PLAYBACK_POSITION_MS = "extra_playback_position_ms"
        const val EXTRA_PLAYBACK_DURATION_MS = "extra_playback_duration_ms"
        const val EXTRA_SEEK_POSITION_MS = "extra_seek_position_ms"

        // Direct handlers for recording
        @Volatile
        var activeStopSaveHandler: (() -> Unit)? = null

        @Volatile
        var activeConfirmAppendSaveHandler: (() -> Unit)? = null

        @Volatile
        var activeCancelAppendSaveHandler: (() -> Unit)? = null

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
