package com.example.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Chronometer
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dynamic Island System Overlay Capsule (`RecordingIslandOverlayManager`) for active recording.
 *
 * - Appears ONLY when the app moves to the background / Home Screen (`!isAppInForeground`) during
 *   an active or paused recording session (`isRecordingSessionActive`).
 * - Automatically hides whenever `MainActivity` is visible in the foreground.
 * - Implements **Pure In-Place Geometric Morphing** (like YouTube Music / Dynamic Island):
 *   1. Keeps the exact same `WindowManager` host view and persistent `morphCapsuleShell` attached
 *      across [OverlayMode.DOCKED_PILL] <-> [OverlayMode.EXPANDED_CARD] transitions without destroying
 *      or re-creating views, and disables all system window transitions (`windowAnimations = 0`).
 *   2. Eliminates all rotation, horizontal X-axis sliding, scale distortion, and bouncy overshoot.
 *      The horizontal center of the capsule remains strictly anchored to top-center throughout
 *      every frame of expansion and collapse.
 *   3. Uses a single synchronized `ValueAnimator` (`PathInterpolator(0.2f, 0f, 0f, 1f)`) to smoothly
 *      interpolate the capsule's pixel width (`pillWidthPx` <-> `expandedWidthPx`), pixel height
 *      (`pillHeightPx` <-> `expandedHeightPx`), corner radius (`pillHeightPx / 2f` <-> `24dp`),
 *      and surface color while crossfading the internal pill content and expanded controls in place.
 */
object RecordingIslandOverlayManager {

    enum class OverlayMode {
        DOCKED_PILL,
        EXPANDED_CARD
    }

    private const val PREFS_NAME = "dynamic_island_overlay_prefs"
    private const val KEY_PROMPTED_ONCE = "overlay_permission_prompted_once"

    private const val MINI_WAVEFORM_WIDTH_PX = 84
    private const val MINI_WAVEFORM_HEIGHT_PX = 32

    // Sleek compact capsule dimensions & physical screen-right timer alignment
    internal const val DOCKED_PILL_PAD_LEFT_DP = 11f
    internal const val DOCKED_PILL_PAD_RIGHT_DP = 14f
    internal const val DOCKED_PILL_PAD_VERTICAL_DP = 5f
    internal const val DOCKED_PILL_WAVEFORM_WIDTH_DP = 34f
    internal const val DOCKED_PILL_WAVEFORM_HEIGHT_DP = 15f
    internal const val DOCKED_PILL_TIMER_LEFT_MARGIN_DP = 14f
    internal const val DOCKED_PILL_TIMER_TEXT_SIZE_SP = 11.5f

    // In-place geometric morph constants (width, height, corner radius, crossfade)
    internal const val MORPH_EXPAND_DURATION_MS = 220L
    internal const val MORPH_COLLAPSE_DURATION_MS = 190L
    internal const val TARGET_HIGH_REFRESH_RATE_HZ = 120f
    private const val EXPANDED_CARD_CORNER_RADIUS_DP = 24f
    private const val EXPANDED_HORIZONTAL_MARGIN_DP = 12f

    private const val PILL_BG_COLOR = 0xFF0D0B0A.toInt()
    private const val CARD_BG_COLOR = 0xFF1A1614.toInt()
    private const val PILL_STROKE_COLOR = 0x33F08C4B
    private const val CARD_STROKE_COLOR = 0x26F08C4B

    private const val DEFAULT_TOP_DOCK_DP = 6f
    private const val TRASH_WINDOW_SIZE_DP = 96f
    private const val TRASH_TARGET_SIZE_DP = 56f
    private const val TRASH_ICON_SIZE_DP = 26f
    private const val TRASH_BOTTOM_MARGIN_DP = 48f
    private const val TRASH_WINDOW_BOTTOM_MARGIN_DP = 28f // 28dp + (96dp - 56dp)/2 = 48dp visual bottom margin
    private const val TRASH_CAPTURE_RADIUS_DP = 88f
    private const val LONG_PRESS_DRAG_TIMEOUT_MS = 120L

    private const val TRASH_CONTAINER_IDLE_COLOR = 0xFF33251E.toInt()
    private const val TRASH_CONTAINER_HOVER_COLOR = 0xFF422D24.toInt()
    private const val TRASH_BORDER_IDLE_COLOR = 0xFF4A382E.toInt()
    private const val TRASH_BORDER_HOVER_COLOR = 0xFFF4D7C7.toInt()
    private const val TRASH_ICON_LIGHT_BEIGE = 0xFFF4D7C7.toInt()
    private const val TRASH_HOVER_SCALE = 1.22f

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    @Volatile
    var isRecordingSessionActive: Boolean = false
        private set

    @Volatile
    var currentMode: OverlayMode = OverlayMode.DOCKED_PILL
        private set

    @Volatile
    var isOverlayAttachedToWindow: Boolean = false
        private set

    @Volatile
    var lastStateTitle: String = "Recording"
        private set

    @Volatile
    var lastIsPaused: Boolean = false
        private set

    @Volatile
    var lastElapsedMs: Long = 0L
        private set

    @Volatile
    var lastIsShowingAppendConfirm: Boolean = false
        private set

    @Volatile
    var lastIsShowingFinishedCompletion: Boolean = false
        private set

    @Volatile
    var lastFinishedTitle: String = "Finished recording"
        private set

    @Volatile
    var suggestedRecordingTitle: String = ""
        private set

    @Volatile
    var currentEditedRecordingTitle: String = ""
        internal set

    @Volatile
    var isDraggingPill: Boolean = false
        private set

    @Volatile
    var isBottomTrashTargetVisible: Boolean = false
        private set

    @Volatile
    var isTrashTargetHovered: Boolean = false
        private set

    @Volatile
    var pillDragOffsetX: Int = 0
        private set

    @Volatile
    var pillDragOffsetY: Int = 0
        private set

    @Volatile
    var lastMorphPivotY: Float = 0f
        private set

    @Volatile
    var lastMorphRefreshRateHz: Float = TARGET_HIGH_REFRESH_RATE_HZ
        private set

    @Volatile
    var currentMorphProgress: Float = 0f
        private set

    @Volatile
    var currentMorphWidthPx: Int = 0
        private set

    @Volatile
    var currentMorphHeightPx: Int = 0
        private set

    @Volatile
    var currentMorphCornerRadiusPx: Float = 0f
        private set

    @Volatile
    var morphShellCreationCount: Int = 0
        private set

    internal var currentOverlayRootView: View? = null
        private set

    /**
     * Persistent single capsule container that animates its width, height, and corner radius in-place
     * while crossfading [persistentDockedPillLayer] and [persistentExpandedCardLayer].
     */
    internal var currentMorphCapsuleShell: FrameLayout? = null
        private set

    internal var persistentDockedPillLayer: LinearLayout? = null
        private set

    internal var persistentExpandedCardLayer: FrameLayout? = null
        private set

    private var morphCapsuleBackground: GradientDrawable? = null
    private var measuredPillWidthPx: Int = 0
    private var measuredPillHeightPx: Int = 0
    private var measuredExpandedWidthPx: Int = 0
    private var measuredExpandedHeightPx: Int = 0

    /**
     * Points to the inner `56dp x 56dp` circular container housing strictly the single `ImageView`
     * trash icon (`childCount == 1`, zero `TextView`s).
     */
    internal var currentTrashTargetRootView: View? = null
        private set

    /**
     * Points to the outer `96dp x 96dp` unclipped window wrapper (`clipChildren = false`,
     * `clipToPadding = false`) that gives the `56dp` inner circle `20dp` of transparent margin
     * on all 4 sides so scaling to `1.22x` on hover never clips against the WindowManager surface.
     */
    internal var currentTrashOverlayWindowView: FrameLayout? = null
        private set

    internal var lastCreatedWindowLayoutParams: WindowManager.LayoutParams? = null
        private set

    internal var lastCreatedTrashWindowLayoutParams: WindowManager.LayoutParams? = null
        private set

    private var activePillWindowParams: WindowManager.LayoutParams? = null
    private var attachedWindowView: FrameLayout? = null
    private var attachedTrashWindowView: View? = null
    private var attachedConfirmState: Boolean? = null
    private var attachedFinishedState: Boolean? = null

    private var snapBackAnimator: ValueAnimator? = null
    private var morphAnimator: ValueAnimator? = null
    private var windowManager: WindowManager? = null
    private var appContext: Context? = null
    private var lastWaveformBitmap: Bitmap? = null
    private var lastOscillatingDisplacements: FloatArray = FloatArray(0)

    private var onBookmarkCallback: (() -> Unit)? = null
    private var onStopSaveCallback: (() -> Unit)? = null
    private var onPauseResumeCallback: (() -> Unit)? = null
    private var onConfirmAppendSaveCallback: (() -> Unit)? = null
    private var onConfirmAppendSaveWithTitleCallback: ((String?) -> Unit)? = null
    private var onCancelAppendSaveCallback: (() -> Unit)? = null
    private var onDiscardRecordingCallback: (() -> Unit)? = null

    private fun defaultTopY(context: Context): Int {
        return (DEFAULT_TOP_DOCK_DP * context.resources.displayMetrics.density).toInt()
    }

    internal fun generateDefaultTimestampedTitle(customBaseTitle: String? = null): String {
        val candidate = customBaseTitle?.trim().orEmpty()
        if (candidate.isNotEmpty() &&
            !candidate.equals("Recording", ignoreCase = true) &&
            !candidate.equals("Paused", ignoreCase = true)
        ) {
            return candidate
        }
        val dateStamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
        return "Recording — $dateStamp"
    }

    fun canDrawSystemOverlay(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Settings.canDrawOverlays(context)
            } catch (_: Exception) {
                false
            }
        } else {
            true
        }
    }

    /**
     * Proactively prompts the user once to grant `SYSTEM_ALERT_WINDOW` ("Display over other apps")
     * when starting a recording or moving to the background for the first time if not yet granted.
     * Falls back seamlessly without crashing if declined or unavailable.
     */
    fun maybePromptOverlayPermissionOnce(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (canDrawSystemOverlay(context)) return false
        val prefs = try {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            return false
        }
        if (prefs.getBoolean(KEY_PROMPTED_ONCE, false)) {
            return false
        }
        prefs.edit().putBoolean(KEY_PROMPTED_ONCE, true).apply()
        return launchOverlayPermissionSettings(context)
    }

    fun launchOverlayPermissionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun shouldDisplayOverlay(): Boolean {
        return (isRecordingSessionActive || lastIsShowingFinishedCompletion) && !isAppInForeground
    }

    fun onAppForegroundChanged(context: Context, inForeground: Boolean) {
        appContext = context.applicationContext
        val previousInForeground = isAppInForeground
        isAppInForeground = inForeground
        if (inForeground) {
            // Hide overlay capsule immediately when MainActivity is in the foreground
            lastIsShowingFinishedCompletion = false
            hideBottomTrashTarget()
            detachOverlayWindow()
        } else if (isRecordingSessionActive || lastIsShowingFinishedCompletion) {
            if (previousInForeground && !canDrawSystemOverlay(context)) {
                maybePromptOverlayPermissionOnce(context)
            }
            if (!lastIsShowingAppendConfirm && !lastIsShowingFinishedCompletion) {
                currentMode = OverlayMode.DOCKED_PILL
                currentMorphProgress = 0f
                pillDragOffsetX = 0
                pillDragOffsetY = defaultTopY(context)
            }
            syncOverlayView(context.applicationContext)
        }
    }

    fun bindCallbacks(
        onBookmark: () -> Unit,
        onStopSave: () -> Unit,
        onPauseResume: () -> Unit,
        onConfirmAppendSave: () -> Unit,
        onCancelAppendSave: () -> Unit,
        onDiscardRecording: (() -> Unit)? = null,
        onConfirmAppendSaveWithTitle: ((String?) -> Unit)? = null
    ) {
        onBookmarkCallback = onBookmark
        onStopSaveCallback = onStopSave
        onPauseResumeCallback = onPauseResume
        onConfirmAppendSaveCallback = onConfirmAppendSave
        onConfirmAppendSaveWithTitleCallback = onConfirmAppendSaveWithTitle
        onCancelAppendSaveCallback = onCancelAppendSave
        onDiscardRecordingCallback = onDiscardRecording
    }

    fun updateRecordingOverlayState(
        context: Context,
        title: String,
        isPaused: Boolean,
        elapsedMs: Long,
        waveformBitmap: Bitmap,
        oscillatingDisplacements: FloatArray,
        isShowingAppendConfirmation: Boolean = false,
        defaultSuggestedTitle: String? = null
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val wasShowingConfirm = lastIsShowingAppendConfirm
        isRecordingSessionActive = true
        lastIsShowingFinishedCompletion = false
        lastStateTitle = title
        lastIsPaused = isPaused
        lastElapsedMs = elapsedMs.coerceAtLeast(0L)
        lastWaveformBitmap = waveformBitmap
        lastOscillatingDisplacements = oscillatingDisplacements.copyOf()
        lastIsShowingAppendConfirm = isShowingAppendConfirmation
        if (isShowingAppendConfirmation) {
            isDraggingPill = false
            hideBottomTrashTarget()
            pillDragOffsetX = 0
            pillDragOffsetY = defaultTopY(appCtx)
            if (!wasShowingConfirm || suggestedRecordingTitle.isBlank()) {
                val initialTitle = generateDefaultTimestampedTitle(defaultSuggestedTitle ?: title)
                suggestedRecordingTitle = initialTitle
                currentEditedRecordingTitle = initialTitle
            }
            // Recording capsule transitions to Greek Save/Cancel confirmation card
            currentMode = OverlayMode.EXPANDED_CARD
        }

        if (shouldDisplayOverlay()) {
            // While the user is actively dragging the compact pill, only update in-place text/waveform
            // and NEVER recreate layout params or interrupt the 1:1 finger drag!
            if (isDraggingPill && currentMode == OverlayMode.DOCKED_PILL && !isShowingAppendConfirmation) {
                updatePersistentRecordingLayersInPlace(appCtx)
                return
            }
            syncOverlayView(appCtx)
        } else {
            hideBottomTrashTarget()
            detachOverlayWindow()
        }
    }

    /**
     * Displays the "Finished recording" completion capsule directly on the active screen
     * at the exact same moment it is posted to the notification shade.
     * Transitions the attached overlay window in-place without spawning a duplicate window.
     */
    fun showFinishedCompletionOverlay(
        context: Context,
        title: String = "Finished recording"
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val resolvedTitle = title.ifBlank { "Finished recording" }

        isDraggingPill = false
        hideBottomTrashTarget()
        pillDragOffsetX = 0
        pillDragOffsetY = defaultTopY(appCtx)

        // Deduplicate: if already showing the finished completion capsule, update title in-place and return
        if (lastIsShowingFinishedCompletion && currentOverlayRootView != null) {
            lastFinishedTitle = resolvedTitle
            currentOverlayRootView?.findViewById<TextView>(R.id.notif_finished_title)?.text =
                buildGradientBlendedTitle(resolvedTitle)
            return
        }

        isRecordingSessionActive = false
        lastIsShowingAppendConfirm = false
        lastIsShowingFinishedCompletion = true
        lastFinishedTitle = resolvedTitle
        currentMode = OverlayMode.EXPANDED_CARD

        if (shouldDisplayOverlay()) {
            syncOverlayView(appCtx, forceRecreate = true)
        } else {
            lastCreatedWindowLayoutParams = createWindowLayoutParams(appCtx)
            currentOverlayRootView = buildOverlayContentView(appCtx)
            detachWindowOnly()
        }
    }

    /**
     * Pure in-place geometric expansion from the docked top-center pill to the expanded recording card.
     * Does NOT destroy or re-create the view or window; animates width, height, and corner radius
     * outward from top-center and downward from the top edge while crossfading the internal layers.
     */
    fun expandFromDockedPill() {
        val ctx = appContext ?: return
        if (lastIsShowingAppendConfirm || lastIsShowingFinishedCompletion) return
        cancelSnapBackAnimator()
        isDraggingPill = false
        hideBottomTrashTarget()
        pillDragOffsetX = 0
        pillDragOffsetY = defaultTopY(ctx)
        currentMode = OverlayMode.EXPANDED_CARD
        lastMorphPivotY = 0f
        lastMorphRefreshRateHz = TARGET_HIGH_REFRESH_RATE_HZ

        // Ensure the persistent morph shell exists without destroying it if already built
        ensurePersistentMorphShell(ctx)
        updatePersistentRecordingLayersInPlace(ctx)

        val lp = createWindowLayoutParams(ctx)
        activePillWindowParams = lp
        lastCreatedWindowLayoutParams = lp

        persistentDockedPillLayer?.isClickable = false
        persistentExpandedCardLayer?.let { cardLayer ->
            cardLayer.visibility = View.VISIBLE
            cardLayer.isClickable = true
            currentOverlayRootView = cardLayer
        }

        // Expand window minimum height prior to starting the in-place geometric morph so zero
        // mid-animation WindowManager relayout calls occur while width & height expand.
        attachedWindowView?.minimumHeight = measuredExpandedHeightPx
        if (isOverlayAttachedToWindow && attachedWindowView != null) {
            try {
                windowManager?.updateViewLayout(attachedWindowView, lp)
            } catch (_: Exception) {}
        }

        startInPlaceGeometricMorph(ctx, targetProgress = 1f)
    }

    /**
     * Pure in-place geometric collapse from the expanded card back into the docked top-center pill.
     * Does NOT destroy or re-create the view or window; smoothly contracts width, height, and corner
     * radius inward toward top-center and upward toward the top edge while crossfading back to the pill.
     */
    fun collapseToDockedPill() {
        val ctx = appContext ?: return
        if (lastIsShowingAppendConfirm || lastIsShowingFinishedCompletion) return
        cancelSnapBackAnimator()
        isDraggingPill = false
        hideBottomTrashTarget()
        pillDragOffsetX = 0
        pillDragOffsetY = defaultTopY(ctx)
        currentMode = OverlayMode.DOCKED_PILL
        lastMorphPivotY = 0f
        lastMorphRefreshRateHz = TARGET_HIGH_REFRESH_RATE_HZ

        ensurePersistentMorphShell(ctx)
        updatePersistentRecordingLayersInPlace(ctx)

        val lp = createWindowLayoutParams(ctx)
        activePillWindowParams = lp
        lastCreatedWindowLayoutParams = lp

        persistentExpandedCardLayer?.isClickable = false
        persistentDockedPillLayer?.let { pillLayer ->
            pillLayer.visibility = View.VISIBLE
            pillLayer.isClickable = true
            currentOverlayRootView = pillLayer
        }

        startInPlaceGeometricMorph(ctx, targetProgress = 0f)
    }

    fun handleHeaderTapOpenApp(context: Context) {
        try {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(openIntent)
        } catch (_: Exception) {}
        isAppInForeground = true
        lastIsShowingFinishedCompletion = false
        currentMode = OverlayMode.DOCKED_PILL
        currentMorphProgress = 0f
        hideBottomTrashTarget()
        detachOverlayWindow()
    }

    fun handleFinishedCapsuleTapOpenSavedRecording(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            nm?.cancel(RecordingForegroundService.NOTIFICATION_ID)
            nm?.cancel(RecordingForegroundService.FINISHED_NOTIFICATION_ID)
        } catch (_: Exception) {}
        try {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                action = RecordingForegroundService.ACTION_OPEN_LAST_SAVED_RECORDING
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(openIntent)
        } catch (_: Exception) {}
        isAppInForeground = true
        dismissAndReset()
    }

    fun dismissBothFinishedNotificationAndOverlay(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            nm?.cancel(RecordingForegroundService.NOTIFICATION_ID)
            nm?.cancel(RecordingForegroundService.FINISHED_NOTIFICATION_ID)
        } catch (_: Exception) {}
        dismissAndReset()
    }

    /**
     * Immediately discards and rejects the active recording without saving, removes the foreground
     * notification, terminates `RecordingForegroundService`, and closes both overlay windows.
     * Triggered when the compact pill is dragged and dropped onto the bottom trash bin target.
     */
    fun handleDragToTrashDiscard(context: Context) {
        val appCtx = context.applicationContext
        cancelSnapBackAnimator()
        cancelMorphAnimator()
        hideBottomTrashTarget()
        pillDragOffsetX = 0
        pillDragOffsetY = defaultTopY(appCtx)
        dismissAndReset()

        try {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            nm?.cancel(RecordingForegroundService.NOTIFICATION_ID)
            nm?.cancel(RecordingForegroundService.FINISHED_NOTIFICATION_ID)
        } catch (_: Exception) {}

        // 1. Immediately invoke direct ViewModel/Service discard handlers for 0ms audio hardware & state reset
        var handledDirectly = false
        RecordingForegroundService.activeDiscardHandler?.let { handler ->
            try {
                handler.invoke()
                handledDirectly = true
            } catch (_: Exception) {}
        }
        if (!handledDirectly) {
            RecordingForegroundService.activeCancelAppendSaveHandler?.let { handler ->
                try {
                    handler.invoke()
                    handledDirectly = true
                } catch (_: Exception) {}
            }
        }
        onDiscardRecordingCallback?.let { cb ->
            try {
                cb.invoke()
            } catch (_: Exception) {}
        } ?: onCancelAppendSaveCallback?.let { cb ->
            try {
                cb.invoke()
            } catch (_: Exception) {}
        }

        // 2. Always send dedicated ACTION_DISCARD_RECORDING intent to RecordingForegroundService
        //    to guarantee stopForeground(STOP_FOREGROUND_REMOVE), nm.cancel(NOTIFICATION_ID), and stopSelf()
        try {
            val discardIntent = Intent(appCtx, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_DISCARD_RECORDING
            }
            appCtx.startService(discardIntent)
        } catch (_: Exception) {}

        // 3. Broadcast ACTION_USER_DISCARD so any registered receiver also resets UI state
        try {
            appCtx.sendBroadcast(
                Intent(RecordingForegroundService.ACTION_USER_DISCARD).setPackage(appCtx.packageName)
            )
        } catch (_: Exception) {}
    }

    fun dismissAndReset() {
        cancelSnapBackAnimator()
        cancelMorphAnimator()
        hideBottomTrashTarget()
        isRecordingSessionActive = false
        lastIsShowingAppendConfirm = false
        lastIsShowingFinishedCompletion = false
        suggestedRecordingTitle = ""
        currentEditedRecordingTitle = ""
        isDraggingPill = false
        pillDragOffsetX = 0
        appContext?.let { pillDragOffsetY = defaultTopY(it) }
        currentMode = OverlayMode.DOCKED_PILL
        currentMorphProgress = 0f
        detachOverlayWindow()
    }

    private fun cancelSnapBackAnimator() {
        try {
            snapBackAnimator?.cancel()
        } catch (_: Exception) {}
        snapBackAnimator = null
    }

    private fun cancelMorphAnimator() {
        try {
            morphAnimator?.cancel()
        } catch (_: Exception) {}
        morphAnimator = null
    }

    /**
     * Builds the inner `56dp x 56dp` strictly icon-only circular trash bin target view.
     * Uses the exact outlined trash bin silhouette (`R.drawable.ic_island_trash_outline`) from the
     * "Move to Trash?" dialog in light beige (`#F4D7C7`), inside a circular warm dark container (`#33251E`),
     * with ZERO text labels, captions, or names.
     */
    internal fun buildBottomTrashTargetView(context: Context, hovered: Boolean = false): FrameLayout {
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val containerSizePx = (TRASH_TARGET_SIZE_DP * density).toInt().coerceAtLeast(48)
        val iconSizePx = (TRASH_ICON_SIZE_DP * density).toInt().coerceAtLeast(24)

        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (hovered) TRASH_CONTAINER_HOVER_COLOR else TRASH_CONTAINER_IDLE_COLOR)
            val strokeWidthPx = ((if (hovered) 1.5f else 1f) * density).toInt().coerceAtLeast(1)
            val strokeColor = if (hovered) TRASH_BORDER_HOVER_COLOR else TRASH_BORDER_IDLE_COLOR
            setStroke(strokeWidthPx, strokeColor)
        }

        val innerCircleContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                containerSizePx,
                containerSizePx,
                Gravity.CENTER
            )
            background = circleBg
            clipChildren = false
            clipToPadding = false
            isClickable = false
            isFocusable = false
            val targetScale = if (hovered) TRASH_HOVER_SCALE else 1.0f
            scaleX = targetScale
            scaleY = targetScale
        }

        val trashIcon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                iconSizePx,
                iconSizePx,
                Gravity.CENTER
            )
            setImageResource(R.drawable.ic_island_trash_outline)
            setColorFilter(TRASH_ICON_LIGHT_BEIGE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = false
            isFocusable = false
            contentDescription = null
        }

        innerCircleContainer.addView(trashIcon)
        return innerCircleContainer
    }

    /**
     * Wraps the `56dp x 56dp` inner circular target inside a generous `96dp x 96dp` transparent
     * window root with `clipChildren = false` and `clipToPadding = false` so scaling the inner
     * circle to `1.22x` (`68.3dp`) never clips against the WindowManager surface boundaries.
     */
    internal fun buildBottomTrashOverlayWindowRoot(
        context: Context,
        innerCircle: FrameLayout
    ): FrameLayout {
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val windowSizePx = (TRASH_WINDOW_SIZE_DP * density).toInt().coerceAtLeast(96)
        val circleSizePx = (TRASH_TARGET_SIZE_DP * density).toInt().coerceAtLeast(48)

        return FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(windowSizePx, windowSizePx)
            clipChildren = false
            clipToPadding = false
            isClickable = false
            isFocusable = false
            (innerCircle.parent as? ViewGroup)?.removeView(innerCircle)
            addView(
                innerCircle,
                FrameLayout.LayoutParams(
                    circleSizePx,
                    circleSizePx,
                    Gravity.CENTER
                )
            )
        }
    }

    internal fun createTrashWindowLayoutParams(context: Context): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val windowSizePx = (TRASH_WINDOW_SIZE_DP * density).toInt().coerceAtLeast(96)
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            windowSizePx,
            windowSizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (TRASH_WINDOW_BOTTOM_MARGIN_DP * density).toInt()
            windowAnimations = 0
            applyHighRefreshRateHint(this)
        }
    }

    private fun applyHighRefreshRateHint(lp: WindowManager.LayoutParams) {
        lastMorphRefreshRateHz = TARGET_HIGH_REFRESH_RATE_HZ
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                lp.preferredRefreshRate = TARGET_HIGH_REFRESH_RATE_HZ
            } catch (_: Exception) {}
        }
    }

    private fun updateTrashTargetHoverVisuals(context: Context, hovered: Boolean) {
        if (isTrashTargetHovered == hovered && currentTrashTargetRootView != null) return
        isTrashTargetHovered = hovered
        val innerCircle = currentTrashTargetRootView as? FrameLayout ?: return
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (hovered) TRASH_CONTAINER_HOVER_COLOR else TRASH_CONTAINER_IDLE_COLOR)
            val strokeWidthPx = ((if (hovered) 1.5f else 1f) * density).toInt().coerceAtLeast(1)
            val strokeColor = if (hovered) TRASH_BORDER_HOVER_COLOR else TRASH_BORDER_IDLE_COLOR
            setStroke(strokeWidthPx, strokeColor)
        }
        innerCircle.background = circleBg
        val targetScale = if (hovered) TRASH_HOVER_SCALE else 1.0f
        innerCircle.scaleX = targetScale
        innerCircle.scaleY = targetScale
        innerCircle.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(90L)
            .start()
    }

    internal fun showBottomTrashTarget(context: Context) {
        val appCtx = context.applicationContext
        val innerCircle = currentTrashTargetRootView as? FrameLayout ?: buildBottomTrashTargetView(appCtx, hovered = false).also {
            currentTrashTargetRootView = it
        }
        val outerWindowRoot = currentTrashOverlayWindowView ?: buildBottomTrashOverlayWindowRoot(appCtx, innerCircle).also {
            currentTrashOverlayWindowView = it
        }
        val trashLp = createTrashWindowLayoutParams(appCtx)
        lastCreatedTrashWindowLayoutParams = trashLp
        isBottomTrashTargetVisible = true
        isTrashTargetHovered = false
        updateTrashTargetHoverVisuals(appCtx, hovered = false)

        if (!canDrawSystemOverlay(appCtx) || attachedTrashWindowView != null) {
            return
        }

        try {
            val wm = (windowManager ?: (appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager))
                ?.also { windowManager = it } ?: return
            outerWindowRoot.alpha = 0f
            wm.addView(outerWindowRoot, trashLp)
            attachedTrashWindowView = outerWindowRoot
            outerWindowRoot.animate()
                .alpha(1f)
                .setDuration(100L)
                .start()
        } catch (_: Exception) {
            attachedTrashWindowView = null
        }
    }

    internal fun hideBottomTrashTarget() {
        isBottomTrashTargetVisible = false
        isTrashTargetHovered = false
        val viewToRemove = attachedTrashWindowView
        attachedTrashWindowView = null
        currentTrashOverlayWindowView = null
        currentTrashTargetRootView = null
        if (viewToRemove != null) {
            try {
                windowManager?.removeViewImmediate(viewToRemove)
            } catch (_: Exception) {
                try {
                    windowManager?.removeView(viewToRemove)
                } catch (_: Exception) {}
            }
        }
    }

    private fun resolveFullScreenDimensions(context: Context): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        var width = dm.widthPixels.takeIf { it > 0 } ?: 1080
        var height = dm.heightPixels.takeIf { it > 0 } ?: 2400
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val wm = windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                val bounds = wm?.currentWindowMetrics?.bounds
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    width = maxOf(width, bounds.width())
                    height = maxOf(height, bounds.height())
                }
            } catch (_: Exception) {}
        }
        return width to height
    }

    /**
     * Checks whether the dragged compact pill or touch coordinate `(rawX, rawY)` is inside the
     * bottom-center trash target's capture zone. Uses the real on-screen coordinates of the attached
     * trash circle when available on device, with display-metrics fallback for unit tests.
     */
    internal fun isPointOverBottomTrashTarget(context: Context, rawX: Float, rawY: Float): Boolean {
        val dm = context.resources.displayMetrics
        val density = dm.density.takeIf { it > 0f } ?: 1f
        val (screenWidth, screenHeight) = resolveFullScreenDimensions(context)

        var trashCenterX = screenWidth / 2f
        val trashBottomMarginPx = TRASH_BOTTOM_MARGIN_DP * density
        val trashRadiusPx = (TRASH_TARGET_SIZE_DP * density) / 2f
        var trashCenterY = screenHeight - trashBottomMarginPx - trashRadiusPx

        // If the inner trash circle is laid out on a real screen, read its exact screen coordinates
        val innerCircle = currentTrashTargetRootView
        if (innerCircle != null && innerCircle.width > 0 && innerCircle.height > 0) {
            val loc = IntArray(2)
            innerCircle.getLocationOnScreen(loc)
            if (loc[1] > 0) {
                trashCenterX = loc[0] + innerCircle.width / 2f
                trashCenterY = loc[1] + innerCircle.height / 2f
            }
        }

        val captureRadiusPx = TRASH_CAPTURE_RADIUS_DP * density
        val captureRadiusSq = captureRadiusPx * captureRadiusPx

        // Check finger coordinate (rawX, rawY)
        val dxFinger = rawX - trashCenterX
        val dyFinger = rawY - trashCenterY
        if (dxFinger * dxFinger + dyFinger * dyFinger <= captureRadiusSq) {
            return true
        }

        // Also check the dragged pill's visual center coordinate
        val pillHalfH = ((measuredPillHeightPx.takeIf { it > 0 } ?: (26f * density).toInt()) / 2f)
        val pillCenterX = (screenWidth / 2f) + pillDragOffsetX
        val pillCenterY = pillDragOffsetY + pillHalfH
        val dxPill = pillCenterX - trashCenterX
        val dyPill = pillCenterY - trashCenterY
        return (dxPill * dxPill + dyPill * dyPill <= captureRadiusSq)
    }

    /**
     * Ultra-smooth 1:1 window position update that mutates the cached `WindowManager.LayoutParams`
     * in-place without object allocations or throttling.
     */
    private fun updateDockedPillWindowPositionDirect(context: Context, newX: Int, newY: Int) {
        pillDragOffsetX = newX
        pillDragOffsetY = newY.coerceAtLeast(0)
        val lp = (activePillWindowParams ?: lastCreatedWindowLayoutParams ?: createWindowLayoutParams(context)).also {
            it.x = pillDragOffsetX
            it.y = pillDragOffsetY
            it.windowAnimations = 0
            activePillWindowParams = it
            lastCreatedWindowLayoutParams = it
        }
        val host = attachedWindowView ?: return
        val wm = windowManager ?: return
        try {
            wm.updateViewLayout(host, lp)
        } catch (_: Exception) {}
    }

    private fun animatePillSnapBackToTopDock(context: Context) {
        val appCtx = context.applicationContext
        val startX = pillDragOffsetX
        val startY = pillDragOffsetY
        val targetX = 0
        val targetY = defaultTopY(appCtx)

        if (startX == targetX && startY == targetY) {
            isDraggingPill = false
            return
        }

        cancelSnapBackAnimator()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val curX = (startX + ((targetX - startX) * fraction)).toInt()
                val curY = (startY + ((targetY - startY) * fraction)).toInt()
                updateDockedPillWindowPositionDirect(appCtx, curX, curY)
            }
        }
        snapBackAnimator = animator
        // Set target position immediately for synchronous unit tests while animating smoothly on device
        updateDockedPillWindowPositionDirect(appCtx, targetX, targetY)
        isDraggingPill = false
        try {
            animator.start()
        } catch (_: Exception) {
            updateDockedPillWindowPositionDirect(appCtx, targetX, targetY)
        }
    }

    /**
     * Attaches 1:1 raw-coordinate finger tracking and drag-to-discard handling to the compact pill
     * (`OverlayMode.DOCKED_PILL`).
     * - Short tap: expands the pill into [OverlayMode.EXPANDED_CARD] via an in-place geometric morph.
     * - Drag or long-press: binds `(params.x, params.y)` directly 1:1 to `(event.rawX, event.rawY)` deltas,
     *   shows the unclipped `96dp x 96dp` bottom trash target, highlights the `56dp` inner circle on hover,
     *   and immediately discards the recording + stops the service when dropped in the capture zone.
     */
    private fun attachDockedPillTouchAndDragListener(context: Context, pillView: View) {
        val appCtx = context.applicationContext
        val density = appCtx.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val touchSlopPx = 3f * density

        var downRawX = 0f
        var downRawY = 0f
        var startOffsetX = 0
        var startOffsetY = defaultTopY(appCtx)
        var pointerDown = false

        val longPressRunnable = Runnable {
            if (pointerDown && !isDraggingPill && currentMode == OverlayMode.DOCKED_PILL) {
                isDraggingPill = true
                try {
                    pillView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                } catch (_: Exception) {}
                showBottomTrashTarget(appCtx)
            }
        }

        pillView.setOnTouchListener { view, event ->
            if (currentMode != OverlayMode.DOCKED_PILL) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelSnapBackAnimator()
                    pointerDown = true
                    isDraggingPill = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    if (pillDragOffsetY == 0) {
                        pillDragOffsetY = defaultTopY(appCtx)
                    }
                    startOffsetX = pillDragOffsetX
                    startOffsetY = pillDragOffsetY
                    view.postDelayed(longPressRunnable, LONG_PRESS_DRAG_TIMEOUT_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDraggingPill && (kotlin.math.abs(dx) > touchSlopPx || kotlin.math.abs(dy) > touchSlopPx)) {
                        view.removeCallbacks(longPressRunnable)
                        isDraggingPill = true
                        showBottomTrashTarget(appCtx)
                    }
                    if (isDraggingPill) {
                        val newX = (startOffsetX + dx).toInt()
                        val newY = (startOffsetY + dy).toInt().coerceAtLeast(0)
                        updateDockedPillWindowPositionDirect(appCtx, newX, newY)
                        val overTrash = isPointOverBottomTrashTarget(appCtx, event.rawX, event.rawY)
                        updateTrashTargetHoverVisuals(appCtx, overTrash)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pointerDown = false
                    view.removeCallbacks(longPressRunnable)
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    val heldDurationMs = event.eventTime - event.downTime
                    if (!isDraggingPill && heldDurationMs >= LONG_PRESS_DRAG_TIMEOUT_MS) {
                        isDraggingPill = true
                        showBottomTrashTarget(appCtx)
                    }

                    if (!isDraggingPill && kotlin.math.abs(dx) <= touchSlopPx && kotlin.math.abs(dy) <= touchSlopPx) {
                        hideBottomTrashTarget()
                        view.performClick()
                    } else {
                        val newX = (startOffsetX + dx).toInt()
                        val newY = (startOffsetY + dy).toInt().coerceAtLeast(0)
                        pillDragOffsetX = newX
                        pillDragOffsetY = newY
                        val droppedOnTrash = isTrashTargetHovered || isPointOverBottomTrashTarget(appCtx, event.rawX, event.rawY)
                        hideBottomTrashTarget()
                        if (droppedOnTrash) {
                            handleDragToTrashDiscard(appCtx)
                        } else {
                            animatePillSnapBackToTopDock(appCtx)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pointerDown = false
                    view.removeCallbacks(longPressRunnable)
                    hideBottomTrashTarget()
                    if (isDraggingPill) {
                        animatePillSnapBackToTopDock(appCtx)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun attachFinishedCardSwipeAndTapListener(context: Context, finishedCard: View) {
        val density = context.resources.displayMetrics.density
        val touchSlopPx = 10f * density
        val horizontalSwipeThresholdPx = 48f * density
        val upwardSwipeThresholdPx = 28f * density

        var downRawX = 0f
        var downRawY = 0f
        var isDragging = false

        finishedCard.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    isDragging = false
                    view.animate().cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && (kotlin.math.abs(dx) > touchSlopPx || kotlin.math.abs(dy) > touchSlopPx)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val clampedDy = dy.coerceAtMost(0f)
                        view.translationX = dx
                        view.translationY = clampedDy
                        val dragProgress = maxOf(
                            kotlin.math.abs(dx) / (horizontalSwipeThresholdPx * 2.2f),
                            kotlin.math.abs(clampedDy) / (upwardSwipeThresholdPx * 2.2f)
                        ).coerceIn(0f, 0.85f)
                        view.alpha = 1f - dragProgress
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    val swipedUpward = dy < -upwardSwipeThresholdPx && kotlin.math.abs(dy) >= kotlin.math.abs(dx) * 0.5f
                    val swipedHorizontally = kotlin.math.abs(dx) > horizontalSwipeThresholdPx

                    if (!isDragging && kotlin.math.abs(dx) <= touchSlopPx && kotlin.math.abs(dy) <= touchSlopPx) {
                        view.translationX = 0f
                        view.translationY = 0f
                        view.alpha = 1f
                        view.performClick()
                    } else if (swipedUpward) {
                        val targetY = -((view.height.takeIf { it > 0 } ?: (96 * density).toInt()).toFloat() + 64f * density)
                        view.animate()
                            .translationY(targetY)
                            .alpha(0f)
                            .setDuration(150L)
                            .withEndAction {
                                dismissBothFinishedNotificationAndOverlay(context)
                            }
                            .start()
                    } else if (swipedHorizontally) {
                        val direction = if (dx > 0f) 1f else -1f
                        val screenWidth = context.resources.displayMetrics.widthPixels.takeIf { it > 0 } ?: 1080
                        view.animate()
                            .translationX(direction * screenWidth.toFloat())
                            .alpha(0f)
                            .setDuration(160L)
                            .withEndAction {
                                dismissBothFinishedNotificationAndOverlay(context)
                            }
                            .start()
                    } else {
                        view.animate()
                            .translationX(0f)
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(140L)
                            .start()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(140L)
                        .start()
                    true
                }
                else -> false
            }
        }
    }

    internal fun buildOverlayContentView(context: Context): View {
        return if (!lastIsShowingAppendConfirm && !lastIsShowingFinishedCompletion) {
            ensurePersistentMorphShell(context)
            if (currentMode == OverlayMode.DOCKED_PILL) {
                persistentDockedPillLayer!!
            } else {
                persistentExpandedCardLayer!!
            }
        } else {
            buildSpecialStateCardView(context)
        }
    }

    /**
     * Builds the sleeker compact docked capsule layer at the top-center of the screen.
     * - Enforces physical Left-to-Right layout (`View.LAYOUT_DIRECTION_LTR`) so RTL system locales
     *   NEVER invert coordinates or flip the timer to the left.
     * - Places the mini oscillating waveform (`34dp x 15dp`) on the physical left and shifts the
     *   monospace timer (`11.5sp`) toward the physical right side of the screen (`leftMargin = 14dp`)
     *   with `14dp` of physical right padding (`padRightPx`) so the seconds digits are completely
     *   visible and never clipped by the capsule's curved outer border.
     */
    private fun buildDockedPillView(context: Context): LinearLayout {
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val padLeftPx = (DOCKED_PILL_PAD_LEFT_DP * density).toInt()
        val padRightPx = (DOCKED_PILL_PAD_RIGHT_DP * density).toInt()
        val padVPx = (DOCKED_PILL_PAD_VERTICAL_DP * density).toInt()
        val waveformWidthPx = (DOCKED_PILL_WAVEFORM_WIDTH_DP * density).toInt()
        val waveformHeightPx = (DOCKED_PILL_WAVEFORM_HEIGHT_DP * density).toInt()
        val timerLeftMarginPx = (DOCKED_PILL_TIMER_LEFT_MARGIN_DP * density).toInt()

        val pillContainer = LinearLayout(context).apply {
            id = R.id.notif_recording_compact_root
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            // Background & border are rendered by the parent morphCapsuleShell so there is zero double-border during morph
            background = null
            setPadding(padLeftPx, padVPx, padRightPx, padVPx)
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = true
            setOnClickListener {
                expandFromDockedPill()
            }
        }
        attachDockedPillTouchAndDragListener(context, pillContainer)

        val miniWaveformImage = ImageView(context).apply {
            id = R.id.notif_recording_waveform
            layoutParams = LinearLayout.LayoutParams(
                waveformWidthPx,
                waveformHeightPx
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            }
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            isFocusable = false
            setImageBitmap(
                renderMiniOscillatingWaveformBitmap(
                    displacements = lastOscillatingDisplacements,
                    isPaused = lastIsPaused
                )
            )
        }
        pillContainer.addView(miniWaveformImage)

        val timerText = TextView(context).apply {
            id = R.id.notif_recording_paused_timer
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = timerLeftMarginPx
                rightMargin = 0
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            }
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            includeFontPadding = false
            isSingleLine = true
            maxLines = 1
            setPadding(0, 0, (2f * density).toInt(), 0)
            isClickable = false
            isFocusable = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DOCKED_PILL_TIMER_TEXT_SIZE_SP)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(if (lastIsPaused) 0x99F4D7C7.toInt() else 0xFFF08C4B.toInt())
            text = formatTimerMs(lastElapsedMs)
        }
        pillContainer.addView(timerText)

        return pillContainer
    }

    /**
     * Builds the persistent expanded live recording controls layer that lives permanently inside
     * [currentMorphCapsuleShell] alongside [persistentDockedPillLayer].
     */
    private fun buildExpandedLiveRecordingLayer(context: Context): FrameLayout {
        val inflater = LayoutInflater.from(context)
        val layerWrapper = object : FrameLayout(context) {
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                val action = event?.actionMasked ?: event?.action
                if (action == MotionEvent.ACTION_OUTSIDE) {
                    handleOutsideTouchEvent(context)
                    return false
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = false
            setOnClickListener {
                handleHeaderTapOpenApp(context)
            }
        }

        val cardView = inflater.inflate(R.layout.notification_recording_live, layerWrapper, false)
        // Outer rounded background & border are drawn by morphCapsuleShell so the corner radius morphs cleanly
        cardView.background = null
        cardView.isClickable = true
        cardView.setOnClickListener {
            handleHeaderTapOpenApp(context)
        }

        val headerView = cardView.findViewById<View>(R.id.notif_recording_header)
        headerView?.isClickable = true
        headerView?.setOnClickListener {
            handleHeaderTapOpenApp(context)
        }

        bindExpandedRecordingCardViews(context, cardView)

        val bookmarkBtn = cardView.findViewById<View>(R.id.notif_recording_btn_bookmark)
        val stopSaveBtn = cardView.findViewById<View>(R.id.notif_recording_btn_stop_save)
        val pauseResumeBtn = cardView.findViewById<View>(R.id.notif_recording_btn_pause_resume)

        bookmarkBtn?.setOnClickListener {
            onBookmarkCallback?.invoke()
        }
        stopSaveBtn?.setOnClickListener {
            val callback = onStopSaveCallback
            if (callback != null) {
                callback.invoke()
            } else {
                val defaultTitle = generateDefaultTimestampedTitle(lastStateTitle)
                suggestedRecordingTitle = defaultTitle
                currentEditedRecordingTitle = defaultTitle
                lastIsShowingAppendConfirm = true
                lastIsShowingFinishedCompletion = false
                currentMode = OverlayMode.EXPANDED_CARD
                syncOverlayView(context.applicationContext, forceRecreate = true)
            }
        }
        pauseResumeBtn?.setOnClickListener {
            onPauseResumeCallback?.invoke()
        }

        layerWrapper.addView(
            cardView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
        return layerWrapper
    }

    /**
     * Ensures the persistent [currentMorphCapsuleShell] and its two internal layers
     * ([persistentDockedPillLayer] and [persistentExpandedCardLayer]) exist and are pre-measured
     * to their exact pixel bounds. Never destroys or re-creates them across expand/collapse!
     */
    private fun ensurePersistentMorphShell(context: Context): FrameLayout {
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val (screenWidth, _) = resolveFullScreenDimensions(context)
        val horizontalMarginPx = (EXPANDED_HORIZONTAL_MARGIN_DP * density).toInt()
        val targetExpandedWidthPx = (screenWidth - horizontalMarginPx * 2).coerceAtLeast((280f * density).toInt())

        val existingShell = currentMorphCapsuleShell
        val existingPill = persistentDockedPillLayer
        val existingCard = persistentExpandedCardLayer
        if (existingShell != null && existingPill != null && existingCard != null) {
            measurePersistentLayers(context, existingPill, existingCard, targetExpandedWidthPx)
            return existingShell
        }

        val pillLayer = buildDockedPillView(context)
        val cardLayer = buildExpandedLiveRecordingLayer(context)
        persistentDockedPillLayer = pillLayer
        persistentExpandedCardLayer = cardLayer
        measurePersistentLayers(context, pillLayer, cardLayer, targetExpandedWidthPx)

        val initialProgress = if (currentMode == OverlayMode.EXPANDED_CARD) 1f else 0f
        currentMorphProgress = initialProgress
        val initialW = lerpInt(measuredPillWidthPx, measuredExpandedWidthPx, initialProgress)
        val initialH = lerpInt(measuredPillHeightPx, measuredExpandedHeightPx, initialProgress)
        val pillRadiusPx = measuredPillHeightPx / 2f
        val expandedRadiusPx = EXPANDED_CARD_CORNER_RADIUS_DP * density
        val initialRadius = lerpFloat(pillRadiusPx, expandedRadiusPx, initialProgress)

        currentMorphWidthPx = initialW
        currentMorphHeightPx = initialH
        currentMorphCornerRadiusPx = initialRadius

        val strokeWidthPx = (1f * density).toInt().coerceAtLeast(1)
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = initialRadius
            setColor(lerpArgb(PILL_BG_COLOR, CARD_BG_COLOR, initialProgress))
            setStroke(strokeWidthPx, lerpArgb(PILL_STROKE_COLOR, CARD_STROKE_COLOR, initialProgress))
        }
        morphCapsuleBackground = bgDrawable

        val shell = object : FrameLayout(context) {
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                val action = event?.actionMasked ?: event?.action
                if (action == MotionEvent.ACTION_OUTSIDE) {
                    handleOutsideTouchEvent(context)
                    return false
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                initialW,
                initialH,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
            background = bgDrawable
            clipChildren = true
            clipToPadding = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val w = view.width.takeIf { it > 0 } ?: currentMorphWidthPx.coerceAtLeast(1)
                    val h = view.height.takeIf { it > 0 } ?: currentMorphHeightPx.coerceAtLeast(1)
                    outline.setRoundRect(0, 0, w, h, currentMorphCornerRadiusPx)
                }
            }
            // Enforce strict zero-twist, zero-slide, zero-overshoot geometry
            translationX = 0f
            translationY = 0f
            rotation = 0f
            scaleX = 1f
            scaleY = 1f
        }

        // Add both persistent layers anchored strictly at TOP | CENTER_HORIZONTAL with fixed internal sizes
        // so internal views NEVER shift, squish, or reflow while the outer shell morphs its width & height!
        shell.addView(
            pillLayer,
            FrameLayout.LayoutParams(
                measuredPillWidthPx,
                measuredPillHeightPx,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
        shell.addView(
            cardLayer,
            FrameLayout.LayoutParams(
                measuredExpandedWidthPx,
                measuredExpandedHeightPx,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )

        currentMorphCapsuleShell = shell
        morphShellCreationCount += 1
        applyMorphProgress(context, initialProgress)
        return shell
    }

    private fun measurePersistentLayers(
        context: Context,
        pillLayer: LinearLayout,
        cardLayer: FrameLayout,
        targetExpandedWidthPx: Int
    ) {
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val (screenWidth, _) = resolveFullScreenDimensions(context)

        pillLayer.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        measuredPillWidthPx = pillLayer.measuredWidth.takeIf { it > 0 } ?: (104f * density).toInt()
        measuredPillHeightPx = pillLayer.measuredHeight.takeIf { it > 0 } ?: (26f * density).toInt()

        measuredExpandedWidthPx = targetExpandedWidthPx
        cardLayer.measure(
            View.MeasureSpec.makeMeasureSpec(targetExpandedWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        measuredExpandedHeightPx = cardLayer.measuredHeight.takeIf { it > 0 } ?: (136f * density).toInt()

        // Keep child layout params in sync with exact measured dimensions
        (pillLayer.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.width != measuredPillWidthPx || lp.height != measuredPillHeightPx) {
                lp.width = measuredPillWidthPx
                lp.height = measuredPillHeightPx
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                pillLayer.layoutParams = lp
            }
        }
        (cardLayer.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.width != measuredExpandedWidthPx || lp.height != measuredExpandedHeightPx) {
                lp.width = measuredExpandedWidthPx
                lp.height = measuredExpandedHeightPx
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                cardLayer.layoutParams = lp
            }
        }
    }

    /**
     * Applies a single frame of the in-place geometric morph (`progress` in `0f..1f`):
     * - Width: `lerp(measuredPillWidthPx, measuredExpandedWidthPx, progress)`
     * - Height: `lerp(measuredPillHeightPx, measuredExpandedHeightPx, progress)`
     * - Corner Radius: `lerp(pillHeightPx / 2f, 24dp, progress)`
     * - Crossfade: fades out `persistentDockedPillLayer` over `0.0..0.30` and fades in
     *   `persistentExpandedCardLayer` over `0.18..1.0` without any rotation, X-translation, or scale.
     */
    internal fun applyMorphProgress(context: Context, progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        currentMorphProgress = clamped
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f

        val w = lerpInt(measuredPillWidthPx, measuredExpandedWidthPx, clamped)
        val h = lerpInt(measuredPillHeightPx, measuredExpandedHeightPx, clamped)
        val pillRadiusPx = measuredPillHeightPx / 2f
        val expandedRadiusPx = EXPANDED_CARD_CORNER_RADIUS_DP * density
        val radius = lerpFloat(pillRadiusPx, expandedRadiusPx, clamped)

        currentMorphWidthPx = w
        currentMorphHeightPx = h
        currentMorphCornerRadiusPx = radius

        val strokeWidthPx = (1f * density).toInt().coerceAtLeast(1)
        morphCapsuleBackground?.apply {
            cornerRadius = radius
            setColor(lerpArgb(PILL_BG_COLOR, CARD_BG_COLOR, clamped))
            setStroke(strokeWidthPx, lerpArgb(PILL_STROKE_COLOR, CARD_STROKE_COLOR, clamped))
        }

        currentMorphCapsuleShell?.let { shell ->
            // Lock transform properties so twisting, sliding, or dancing is impossible
            shell.translationX = 0f
            shell.translationY = 0f
            shell.rotation = 0f
            shell.scaleX = 1f
            shell.scaleY = 1f

            val shellLp = (shell.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            shellLp.width = w
            shellLp.height = h
            shellLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            shell.layoutParams = shellLp
            shell.invalidateOutline()
            shell.requestLayout()
        }

        val pillAlpha = ((0.30f - clamped) / 0.30f).coerceIn(0f, 1f)
        val cardAlpha = ((clamped - 0.18f) / 0.82f).coerceIn(0f, 1f)

        persistentDockedPillLayer?.let { pill ->
            pill.translationX = 0f
            pill.translationY = 0f
            pill.rotation = 0f
            pill.scaleX = 1f
            pill.scaleY = 1f
            pill.alpha = pillAlpha
            pill.visibility = if (clamped < 0.99f || currentMode == OverlayMode.DOCKED_PILL) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
        }

        persistentExpandedCardLayer?.let { card ->
            card.translationX = 0f
            card.translationY = 0f
            card.rotation = 0f
            card.scaleX = 1f
            card.scaleY = 1f
            card.alpha = cardAlpha
            card.visibility = if (clamped > 0.01f || currentMode == OverlayMode.EXPANDED_CARD) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
        }
    }

    /**
     * Runs the single synchronized in-place geometric morph animator between `currentMorphProgress`
     * and `targetProgress` (`1f` for expand, `0f` for collapse) using a monotonic, non-overshooting
     * decelerate curve (`PathInterpolator(0.2f, 0f, 0f, 1f)`).
     */
    private fun startInPlaceGeometricMorph(context: Context, targetProgress: Float) {
        cancelMorphAnimator()
        val startProgress = currentMorphProgress
        val shell = currentMorphCapsuleShell ?: return

        if (kotlin.math.abs(startProgress - targetProgress) < 0.001f) {
            applyMorphProgress(context, targetProgress)
            if (targetProgress == 0f) {
                attachedWindowView?.minimumHeight = measuredPillHeightPx
            }
            return
        }

        shell.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val durationMs = if (targetProgress > startProgress) {
            MORPH_EXPAND_DURATION_MS
        } else {
            MORPH_COLLAPSE_DURATION_MS
        }
        // Monotonic decelerate curve with zero bouncy overshoot
        val interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

        val animator = ValueAnimator.ofFloat(startProgress, targetProgress).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                applyMorphProgress(context, p)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    applyMorphProgress(context, targetProgress)
                    shell.setLayerType(View.LAYER_TYPE_NONE, null)
                    if (targetProgress == 0f) {
                        attachedWindowView?.minimumHeight = measuredPillHeightPx
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    shell.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
        }
        morphAnimator = animator
        try {
            animator.start()
        } catch (_: Exception) {
            applyMorphProgress(context, targetProgress)
            shell.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private fun lerpInt(start: Int, end: Int, fraction: Float): Int {
        return (start + ((end - start) * fraction)).toInt()
    }

    private fun lerpFloat(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    private fun lerpArgb(startColor: Int, endColor: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val startA = (startColor ushr 24) and 0xFF
        val startR = (startColor ushr 16) and 0xFF
        val startG = (startColor ushr 8) and 0xFF
        val startB = startColor and 0xFF

        val endA = (endColor ushr 24) and 0xFF
        val endR = (endColor ushr 16) and 0xFF
        val endG = (endColor ushr 8) and 0xFF
        val endB = endColor and 0xFF

        val a = (startA + ((endA - startA) * f).toInt()).coerceIn(0, 255)
        val r = (startR + ((endR - startR) * f).toInt()).coerceIn(0, 255)
        val g = (startG + ((endG - startG) * f).toInt()).coerceIn(0, 255)
        val b = (startB + ((endB - startB) * f).toInt()).coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun handleOutsideTouchEvent(context: Context): Boolean {
        if (lastIsShowingFinishedCompletion) {
            dismissBothFinishedNotificationAndOverlay(context)
            return true
        } else if (currentMode == OverlayMode.EXPANDED_CARD && !lastIsShowingAppendConfirm) {
            collapseToDockedPill()
            return true
        }
        return false
    }

    private fun enableOverlayKeyboardFocus(context: Context, editText: EditText) {
        val host = attachedWindowView
        val wm = windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        val lp = activePillWindowParams ?: lastCreatedWindowLayoutParams
        if (host != null && wm != null && lp != null) {
            val updatedFlags = lp.flags and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
            if (lp.flags != updatedFlags) {
                lp.flags = updatedFlags
                lp.windowAnimations = 0
                lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                try {
                    wm.updateViewLayout(host, lp)
                } catch (_: Exception) {}
            }
        }
        editText.requestFocus()
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Exception) {}
    }

    private fun hideOverlayKeyboard(context: Context, view: View?) {
        if (view == null) return
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        } catch (_: Exception) {}
    }

    /**
     * Builds the special-state cards for the Greek Save Confirmation (`lastIsShowingAppendConfirm`)
     * and the Finished Recording completion capsule (`lastIsShowingFinishedCompletion`).
     */
    private fun buildSpecialStateCardView(context: Context): View {
        val inflater = LayoutInflater.from(context)
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f

        val outerWrapper = object : FrameLayout(context) {
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                val action = event?.actionMasked ?: event?.action
                if (action == MotionEvent.ACTION_OUTSIDE) {
                    handleOutsideTouchEvent(context)
                    return false
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    if (handleOutsideTouchEvent(context)) {
                        return true
                    }
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = false
            setOnClickListener {
                handleOutsideTouchEvent(context)
            }
        }

        if (lastIsShowingFinishedCompletion) {
            val finishedCard = inflater.inflate(R.layout.notification_finished_compact, outerWrapper, false)
            finishedCard.isClickable = true
            finishedCard.isFocusable = true
            finishedCard.findViewById<ImageView>(R.id.notif_finished_badge)?.setImageResource(R.drawable.ic_notif_finished_check)
            finishedCard.findViewById<TextView>(R.id.notif_finished_title)?.text =
                buildGradientBlendedTitle(lastFinishedTitle.ifBlank { context.getString(R.string.notification_finished_recording) })
            finishedCard.setOnClickListener {
                handleFinishedCapsuleTapOpenSavedRecording(context)
            }
            attachFinishedCardSwipeAndTapListener(context, finishedCard)
            val cardLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
            outerWrapper.addView(finishedCard, cardLp)
            return outerWrapper
        }

        // Greek Save / Cancel confirmation card (`notification_append_confirm_compact`)
        val confirmCard = inflater.inflate(R.layout.notification_append_confirm_compact, outerWrapper, false)
        confirmCard.isClickable = true
        confirmCard.findViewById<TextView>(R.id.notif_append_dont_text)?.text = buildGradientBlendedTitle("Don't")
        confirmCard.findViewById<TextView>(R.id.notif_append_save_text)?.text = buildGradientBlendedTitle("Save")

        val initialTitle = currentEditedRecordingTitle.ifBlank {
            suggestedRecordingTitle.ifBlank {
                generateDefaultTimestampedTitle(lastStateTitle)
            }
        }
        if (suggestedRecordingTitle.isBlank()) {
            suggestedRecordingTitle = initialTitle
        }
        currentEditedRecordingTitle = initialTitle

        val inputContainer = confirmCard.findViewById<FrameLayout>(R.id.notif_append_title_input_container)
        confirmCard.findViewById<View>(R.id.notif_append_title_display)?.visibility = View.GONE

        val titleInputField = EditText(context).apply {
            id = R.id.notif_append_title_input
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (48f * density).toInt(),
                Gravity.CENTER_VERTICAL
            )
            minHeight = (48f * density).toInt()
            setBackgroundResource(R.drawable.bg_notif_title_input)
            val padH = (14f * density).toInt()
            val padV = (10f * density).toInt()
            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
            isSingleLine = true
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(0xFFF4D7C7.toInt())
            setHintTextColor(0xFF9E8C82.toInt())
            hint = "Recording title"
            setText(initialTitle)
            try {
                setSelection(initialTitle.length)
            } catch (_: Exception) {}
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setOnClickListener {
                enableOverlayKeyboardFocus(context, this)
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    enableOverlayKeyboardFocus(context, this)
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentEditedRecordingTitle = s?.toString().orEmpty()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        inputContainer?.addView(titleInputField)

        confirmCard.findViewById<View>(R.id.notif_append_dont_button)?.setOnClickListener {
            hideOverlayKeyboard(context, titleInputField)
            dismissAndReset()
            onCancelAppendSaveCallback?.invoke()
        }
        confirmCard.findViewById<View>(R.id.notif_append_save_button)?.setOnClickListener {
            hideOverlayKeyboard(context, titleInputField)
            val enteredTitle = titleInputField.text?.toString()?.trim().orEmpty()
            val finalTitle = enteredTitle.ifBlank {
                suggestedRecordingTitle.ifBlank {
                    generateDefaultTimestampedTitle(lastStateTitle)
                }
            }
            currentEditedRecordingTitle = finalTitle

            val titleCallback = onConfirmAppendSaveWithTitleCallback
            val callback = onConfirmAppendSaveCallback
            if (titleCallback != null) {
                titleCallback.invoke(finalTitle)
            } else if (callback != null) {
                callback.invoke()
            } else {
                showFinishedCompletionOverlay(
                    context = context,
                    title = context.getString(R.string.notification_finished_recording)
                )
            }
        }
        val cardLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        outerWrapper.addView(confirmCard, cardLp)
        return outerWrapper
    }

    private fun buildGradientBlendedTitle(text: String): android.text.SpannableString {
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

    private fun bindExpandedRecordingCardViews(context: Context, root: View) {
        val titleView = root.findViewById<TextView>(R.id.notif_recording_title)
        val statusLabel = if (lastIsPaused) {
            context.getString(R.string.notification_status_paused)
        } else {
            context.getString(R.string.notification_status_recording)
        }
        titleView?.text = statusLabel

        val chronometer = root.findViewById<Chronometer>(R.id.notif_recording_chronometer)
        val pausedTimer = root.findViewById<TextView>(R.id.notif_recording_paused_timer)
        val formattedTimer = formatTimerMs(lastElapsedMs)

        if (lastIsPaused) {
            chronometer?.stop()
            chronometer?.visibility = View.GONE
            pausedTimer?.visibility = View.VISIBLE
            pausedTimer?.text = formattedTimer
        } else {
            pausedTimer?.visibility = View.GONE
            chronometer?.visibility = View.VISIBLE
            val baseRealtime = (SystemClock.elapsedRealtime() - lastElapsedMs)
                .coerceAtMost(SystemClock.elapsedRealtime())
            chronometer?.base = baseRealtime
            chronometer?.start()
        }

        val waveformView = root.findViewById<ImageView>(R.id.notif_recording_waveform)
        // Keep the waveform strictly visible in the Dynamic Island expanded card
        waveformView?.visibility = View.VISIBLE
        lastWaveformBitmap?.let { bmp ->
            waveformView?.setImageBitmap(bmp)
        }

        val pauseResumeIcon = root.findViewById<ImageView>(R.id.notif_action_pause_resume_icon)
        val pauseResumeText = root.findViewById<TextView>(R.id.notif_action_pause_resume_text)
        if (lastIsPaused) {
            pauseResumeIcon?.setImageResource(R.drawable.ic_notif_resume)
            pauseResumeText?.text = context.getString(R.string.notification_action_resume)
        } else {
            pauseResumeIcon?.setImageResource(R.drawable.ic_notif_pause)
            pauseResumeText?.text = context.getString(R.string.notification_action_pause)
        }
    }

    private fun updatePersistentRecordingLayersInPlace(context: Context) {
        persistentDockedPillLayer?.let { pill ->
            val miniWaveform = pill.findViewById<ImageView>(R.id.notif_recording_waveform)
            miniWaveform?.setImageBitmap(
                renderMiniOscillatingWaveformBitmap(
                    displacements = lastOscillatingDisplacements,
                    isPaused = lastIsPaused
                )
            )
            val timerText = pill.findViewById<TextView>(R.id.notif_recording_paused_timer)
            timerText?.setTextColor(if (lastIsPaused) 0x99F4D7C7.toInt() else 0xFFF08C4B.toInt())
            timerText?.text = formatTimerMs(lastElapsedMs)
        }
        persistentExpandedCardLayer?.let { card ->
            bindExpandedRecordingCardViews(context, card)
        }
    }

    internal fun createWindowLayoutParams(context: Context): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val (screenWidth, _) = resolveFullScreenDimensions(context)
        val horizontalMarginPx = (EXPANDED_HORIZONTAL_MARGIN_DP * density).toInt()
        val fixedExpandedWidthPx = (screenWidth - horizontalMarginPx * 2).coerceAtLeast((280f * density).toInt())

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val isPill = currentMode == OverlayMode.DOCKED_PILL && !lastIsShowingAppendConfirm && !lastIsShowingFinishedCompletion

        // Keep window width constant at fixedExpandedWidthPx across DOCKED_PILL <-> EXPANDED_CARD
        // so WindowManagerService never reallocates or shifts windowFrame.left during expansion/collapse.
        // Strictly WRAP_CONTENT height in all modes so the rest of the phone screen remains 100% interactive.
        val widthSpec = fixedExpandedWidthPx
        val heightSpec = WindowManager.LayoutParams.WRAP_CONTENT

        val defaultY = defaultTopY(context)
        if (pillDragOffsetY == 0) {
            pillDragOffsetY = defaultY
        }

        val focusFlag = if (lastIsShowingAppendConfirm) {
            0
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        // Keep flags consistent between DOCKED_PILL and EXPANDED_CARD so status-bar / cutout
        // coordinate origins never shift by 1 pixel when expanding or collapsing.
        val flags = focusFlag or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        return WindowManager.LayoutParams(
            widthSpec,
            heightSpec,
            overlayType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = if (isPill) pillDragOffsetX else 0
            y = if (isPill) pillDragOffsetY else defaultY
            // Strictly disable all system window enter/exit/resize transitions
            windowAnimations = 0
            if (lastIsShowingAppendConfirm) {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            }
            applyHighRefreshRateHint(this)
        }
    }

    private fun syncOverlayView(
        context: Context,
        forceRecreate: Boolean = false
    ) {
        val lp = createWindowLayoutParams(context)
        activePillWindowParams = lp
        lastCreatedWindowLayoutParams = lp

        if (!shouldDisplayOverlay() || !canDrawSystemOverlay(context)) {
            detachWindowOnly()
            currentOverlayRootView = buildOverlayContentView(context)
            return
        }

        val isSpecialCard = lastIsShowingAppendConfirm || lastIsShowingFinishedCompletion
        val existingHost = attachedWindowView

        // Fast path for normal active recording (DOCKED_PILL or EXPANDED_CARD):
        // Keep the single persistent morphCapsuleShell attached inside existingHost!
        if (!isSpecialCard) {
            val morphShell = ensurePersistentMorphShell(context)
            updatePersistentRecordingLayersInPlace(context)

            if (morphAnimator?.isRunning != true) {
                val targetP = if (currentMode == OverlayMode.EXPANDED_CARD) 1f else 0f
                applyMorphProgress(context, targetP)
            }
            currentOverlayRootView = if (currentMode == OverlayMode.DOCKED_PILL) {
                persistentDockedPillLayer
            } else {
                persistentExpandedCardLayer
            }

            try {
                val wm = (windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager))
                    ?.also { windowManager = it } ?: return

                if (isOverlayAttachedToWindow && existingHost != null) {
                    if (existingHost.childCount != 1 || existingHost.getChildAt(0) !== morphShell) {
                        existingHost.removeAllViews()
                        (morphShell.parent as? ViewGroup)?.removeView(morphShell)
                        existingHost.addView(morphShell)
                    }
                    existingHost.minimumHeight = if (currentMode == OverlayMode.EXPANDED_CARD) {
                        measuredExpandedHeightPx
                    } else {
                        measuredPillHeightPx
                    }
                    try {
                        wm.updateViewLayout(existingHost, lp)
                    } catch (_: Exception) {}
                    attachedConfirmState = false
                    attachedFinishedState = false
                    return
                }

                val hostContainer = createPersistentHostContainer(context).apply {
                    minimumHeight = if (currentMode == OverlayMode.EXPANDED_CARD) {
                        measuredExpandedHeightPx
                    } else {
                        measuredPillHeightPx
                    }
                    (morphShell.parent as? ViewGroup)?.removeView(morphShell)
                    addView(morphShell)
                }
                wm.addView(hostContainer, lp)
                attachedWindowView = hostContainer
                attachedConfirmState = false
                attachedFinishedState = false
                isOverlayAttachedToWindow = true
            } catch (_: Exception) {
                attachedWindowView = null
                isOverlayAttachedToWindow = false
            }
            return
        }

        // Special state path: Greek Save Confirmation card or Finished Recording completion card
        if (!forceRecreate &&
            isOverlayAttachedToWindow &&
            existingHost != null &&
            currentOverlayRootView != null &&
            attachedConfirmState == lastIsShowingAppendConfirm &&
            attachedFinishedState == lastIsShowingFinishedCompletion
        ) {
            if (lastIsShowingFinishedCompletion) {
                currentOverlayRootView?.findViewById<TextView>(R.id.notif_finished_title)?.text =
                    buildGradientBlendedTitle(lastFinishedTitle.ifBlank { context.getString(R.string.notification_finished_recording) })
            }
            return
        }

        val specialContent = buildSpecialStateCardView(context)
        currentOverlayRootView = specialContent

        try {
            val wm = (windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager))
                ?.also { windowManager = it } ?: return

            if (isOverlayAttachedToWindow && existingHost != null) {
                existingHost.minimumHeight = 0
                existingHost.removeAllViews()
                existingHost.addView(
                    specialContent,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    )
                )
                try {
                    wm.updateViewLayout(existingHost, lp)
                } catch (_: Exception) {}
                attachedConfirmState = lastIsShowingAppendConfirm
                attachedFinishedState = lastIsShowingFinishedCompletion
                return
            }

            val hostContainer = createPersistentHostContainer(context).apply {
                addView(
                    specialContent,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    )
                )
            }
            wm.addView(hostContainer, lp)
            attachedWindowView = hostContainer
            attachedConfirmState = lastIsShowingAppendConfirm
            attachedFinishedState = lastIsShowingFinishedCompletion
            isOverlayAttachedToWindow = true
        } catch (_: Exception) {
            attachedWindowView = null
            isOverlayAttachedToWindow = false
        }
    }

    private fun createPersistentHostContainer(context: Context): FrameLayout {
        return object : FrameLayout(context) {
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                val action = event?.actionMasked ?: event?.action
                if (action == MotionEvent.ACTION_OUTSIDE) {
                    handleOutsideTouchEvent(context)
                    return false
                }
                if (action == MotionEvent.ACTION_DOWN && currentMode == OverlayMode.EXPANDED_CARD && !lastIsShowingAppendConfirm) {
                    handleOutsideTouchEvent(context)
                    return true
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            clipChildren = false
            clipToPadding = false
            translationX = 0f
            translationY = 0f
            rotation = 0f
            scaleX = 1f
            scaleY = 1f
        }
    }

    private fun detachOverlayWindow() {
        detachWindowOnly()
        currentOverlayRootView = null
        currentMorphCapsuleShell = null
        persistentDockedPillLayer = null
        persistentExpandedCardLayer = null
        morphCapsuleBackground = null
    }

    private fun detachWindowOnly() {
        cancelMorphAnimator()
        val viewToRemove = attachedWindowView
        attachedWindowView = null
        activePillWindowParams = null
        attachedConfirmState = null
        attachedFinishedState = null
        isOverlayAttachedToWindow = false
        if (viewToRemove != null) {
            try {
                windowManager?.removeViewImmediate(viewToRemove)
            } catch (_: Exception) {
                try {
                    windowManager?.removeView(viewToRemove)
                } catch (_: Exception) {}
            }
        }
    }

    internal fun renderMiniOscillatingWaveformBitmap(
        displacements: FloatArray,
        isPaused: Boolean
    ): Bitmap {
        val width = MINI_WAVEFORM_WIDTH_PX
        val height = MINI_WAVEFORM_HEIGHT_PX
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centerY = height / 2f
        val opacity = if (isPaused) RecordingForegroundService.PAUSED_WAVEFORM_OPACITY else 1.0f
        val alpha = (255f * opacity).toInt().coerceIn(0, 255)

        val points = if (displacements.size >= 4) {
            displacements
        } else {
            floatArrayOf(-0.35f, 0.55f, -0.75f, 0.65f, -0.45f, 0.30f)
        }
        val count = points.size
        val padX = 2.5f
        val usableWidth = (width - padX * 2f).coerceAtLeast(1f)
        val maxHalfAmp = height * 0.42f

        val xs = FloatArray(count) { idx ->
            padX + (idx.toFloat() / (count - 1).coerceAtLeast(1).toFloat()) * usableWidth
        }
        val ys = FloatArray(count) { idx ->
            (centerY + points[idx].coerceIn(-1f, 1f) * maxHalfAmp).coerceIn(2f, height - 2f)
        }

        val path = Path()
        path.moveTo(xs[0], ys[0])
        val tension = 0.24f
        for (i in 0 until count - 1) {
            val x0 = if (i > 0) xs[i - 1] else xs[i]
            val y0 = if (i > 0) ys[i - 1] else ys[i]
            val x1 = xs[i]
            val y1 = ys[i]
            val x2 = xs[i + 1]
            val y2 = ys[i + 1]
            val x3 = if (i + 2 < count) xs[i + 2] else x2
            val y3 = if (i + 2 < count) ys[i + 2] else y2

            val cp1x = x1 + (x2 - x0) * tension
            val cp1y = (y1 + (y2 - y0) * tension).coerceIn(1f, height - 1f)
            val cp2x = x2 - (x3 - x1) * tension
            val cp2y = (y2 - (y3 - y1) * tension).coerceIn(1f, height - 1f)
            path.cubicTo(cp1x, cp1y, cp2x, cp2y, x2, y2)
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.75f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                0f,
                intArrayOf(
                    RecordingForegroundService.WAVEFORM_CORAL_START_COLOR,
                    RecordingForegroundService.WAVEFORM_CORAL_MID_COLOR,
                    RecordingForegroundService.WAVEFORM_CORAL_END_COLOR
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            this.alpha = alpha
        }
        canvas.drawPath(path, strokePaint)
        return bitmap
    }

    private fun formatTimerMs(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
