package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.R

/**
 * Xiaomi-Style System Overlay Dynamic Capsule (`RecordingIslandOverlayManager`) for active recording.
 *
 * - Appears ONLY when the app moves to the background / Home Screen (`!isAppInForeground`) during
 *   an active or paused recording session (`isRecordingSessionActive`).
 * - Automatically hides whenever `MainActivity` is visible in the foreground.
 * - Supports interactive modes:
 *   1. [OverlayMode.DOCKED_PILL]: Compact black capsule at the top-center status bar / camera cutout
 *      with a live mini oscillating coral waveform on the left and live recording timer on the right.
 *      Tapping anywhere on the capsule expands it to [OverlayMode.EXPANDED_CARD].
 *   2. [OverlayMode.EXPANDED_CARD]: Fixed-height non-expandable dark card (`#1A1614`, `24dp` radius,
 *      zero chevron arrows, zero Cast icon, zero seekbar) with:
 *      - Top Row: Mic icon + `Recording`/`Paused` (Far-Left), live/frozen timer (Far-Right).
 *        Tapping the header opens `MainActivity` and hides the overlay.
 *      - Middle Row: Full-width horizontally centered oscillating cubic-Bezier coral waveform.
 *      - Bottom Row: 3 equal-sized icon-only buttons: Far-Left `Bookmark`, Center `Save`, Far-Right `Pause/Resume`.
 *      - Tapping anywhere outside the expanded card returns it to [OverlayMode.DOCKED_PILL].
 *   3. When the Save button is pressed (`lastIsShowingAppendConfirm == true`), the recording capsule
 *      disappears completely and displays the Save / Cancel (`Θ Don't` / `Σ Save`) confirmation message.
 */
object RecordingIslandOverlayManager {

    enum class OverlayMode {
        DOCKED_PILL,
        EXPANDED_CARD
    }

    private const val PREFS_NAME = "dynamic_island_overlay_prefs"
    private const val KEY_PROMPTED_ONCE = "overlay_permission_prompted_once"

    private const val MINI_WAVEFORM_WIDTH_PX = 96
    private const val MINI_WAVEFORM_HEIGHT_PX = 36

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

    internal var currentOverlayRootView: View? = null
        private set

    private var attachedWindowView: View? = null
    private var attachedMode: OverlayMode? = null
    private var attachedConfirmState: Boolean? = null

    private var windowManager: WindowManager? = null
    private var appContext: Context? = null
    private var lastWaveformBitmap: Bitmap? = null
    private var lastOscillatingDisplacements: FloatArray = FloatArray(0)

    private var onBookmarkCallback: (() -> Unit)? = null
    private var onStopSaveCallback: (() -> Unit)? = null
    private var onPauseResumeCallback: (() -> Unit)? = null
    private var onConfirmAppendSaveCallback: (() -> Unit)? = null
    private var onCancelAppendSaveCallback: (() -> Unit)? = null

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
        return isRecordingSessionActive && !isAppInForeground
    }

    fun onAppForegroundChanged(context: Context, inForeground: Boolean) {
        appContext = context.applicationContext
        val previousInForeground = isAppInForeground
        isAppInForeground = inForeground
        if (inForeground) {
            // Hide overlay capsule immediately when MainActivity is in the foreground
            detachOverlayWindow()
        } else if (isRecordingSessionActive) {
            if (previousInForeground && !canDrawSystemOverlay(context)) {
                maybePromptOverlayPermissionOnce(context)
            }
            if (!lastIsShowingAppendConfirm) {
                currentMode = OverlayMode.DOCKED_PILL
            }
            syncOverlayView(context.applicationContext)
        }
    }

    fun bindCallbacks(
        onBookmark: () -> Unit,
        onStopSave: () -> Unit,
        onPauseResume: () -> Unit,
        onConfirmAppendSave: () -> Unit,
        onCancelAppendSave: () -> Unit
    ) {
        onBookmarkCallback = onBookmark
        onStopSaveCallback = onStopSave
        onPauseResumeCallback = onPauseResume
        onConfirmAppendSaveCallback = onConfirmAppendSave
        onCancelAppendSaveCallback = onCancelAppendSave
    }

    fun updateRecordingOverlayState(
        context: Context,
        title: String,
        isPaused: Boolean,
        elapsedMs: Long,
        waveformBitmap: Bitmap,
        oscillatingDisplacements: FloatArray,
        isShowingAppendConfirmation: Boolean = false
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx
        isRecordingSessionActive = true
        lastStateTitle = title
        lastIsPaused = isPaused
        lastElapsedMs = elapsedMs.coerceAtLeast(0L)
        lastWaveformBitmap = waveformBitmap
        lastOscillatingDisplacements = oscillatingDisplacements.copyOf()
        lastIsShowingAppendConfirm = isShowingAppendConfirmation
        if (isShowingAppendConfirmation) {
            // Recording capsule disappears completely; transition to Save/Cancel confirmation card
            currentMode = OverlayMode.EXPANDED_CARD
        }

        if (shouldDisplayOverlay()) {
            syncOverlayView(appCtx)
        } else {
            detachOverlayWindow()
        }
    }

    fun expandFromDockedPill() {
        val ctx = appContext ?: return
        if (lastIsShowingAppendConfirm) return
        currentMode = OverlayMode.EXPANDED_CARD
        syncOverlayView(ctx, forceRecreate = true)
    }

    fun collapseToDockedPill() {
        val ctx = appContext ?: return
        if (lastIsShowingAppendConfirm) return
        currentMode = OverlayMode.DOCKED_PILL
        syncOverlayView(ctx, forceRecreate = true)
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
        currentMode = OverlayMode.DOCKED_PILL
        detachOverlayWindow()
    }

    fun dismissAndReset() {
        isRecordingSessionActive = false
        lastIsShowingAppendConfirm = false
        currentMode = OverlayMode.DOCKED_PILL
        detachOverlayWindow()
    }

    internal fun buildOverlayContentView(context: Context): View {
        return if (currentMode == OverlayMode.DOCKED_PILL && !lastIsShowingAppendConfirm) {
            buildDockedPillView(context)
        } else {
            buildExpandedCardView(context)
        }
    }

    private fun buildDockedPillView(context: Context): View {
        val density = context.resources.displayMetrics.density
        val padH = (14f * density).toInt()
        val padV = (7f * density).toInt()

        val pillContainer = LinearLayout(context).apply {
            id = R.id.notif_recording_compact_root
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_island_docked_pill)
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                expandFromDockedPill()
            }
        }

        val miniWaveformImage = ImageView(context).apply {
            id = R.id.notif_recording_waveform
            layoutParams = LinearLayout.LayoutParams(
                (44f * density).toInt(),
                (18f * density).toInt()
            )
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
                marginStart = (10f * density).toInt()
            }
            isClickable = false
            isFocusable = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(if (lastIsPaused) 0x99F4D7C7.toInt() else 0xFFF08C4B.toInt())
            text = formatTimerMs(lastElapsedMs)
        }
        pillContainer.addView(timerText)

        return pillContainer
    }

    private fun buildExpandedCardView(context: Context): View {
        val inflater = LayoutInflater.from(context)
        val density = context.resources.displayMetrics.density
        val horizontalMargin = (12f * density).toInt()
        val topMargin = (8f * density).toInt()

        // Full-screen transparent backdrop when expanded so tapping ANYWHERE outside the card
        // reliably collapses the capsule back to its original DOCKED_PILL size.
        val outerWrapper = object : FrameLayout(context) {
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                if (!lastIsShowingAppendConfirm &&
                    (event?.action == MotionEvent.ACTION_OUTSIDE || event?.action == MotionEvent.ACTION_DOWN)
                ) {
                    collapseToDockedPill()
                    return true
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            setPadding(horizontalMargin, topMargin, horizontalMargin, topMargin)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                if (!lastIsShowingAppendConfirm) {
                    collapseToDockedPill()
                }
            }
        }

        if (lastIsShowingAppendConfirm) {
            // Recording capsule disappears completely; render only the Save / Cancel confirmation message card
            val confirmCard = inflater.inflate(R.layout.notification_append_confirm_compact, outerWrapper, false)
            confirmCard.isClickable = true
            confirmCard.findViewById<View>(R.id.notif_append_dont_button)?.setOnClickListener {
                dismissAndReset()
                onCancelAppendSaveCallback?.invoke()
            }
            confirmCard.findViewById<View>(R.id.notif_append_save_button)?.setOnClickListener {
                dismissAndReset()
                onConfirmAppendSaveCallback?.invoke()
            }
            val cardLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
            outerWrapper.addView(confirmCard, cardLp)
            return outerWrapper
        }

        val cardView = inflater.inflate(R.layout.notification_recording_live, outerWrapper, false)
        // Second tap on the expanded capsule opens the app to the currently active recording window
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
            onStopSaveCallback?.invoke()
        }
        pauseResumeBtn?.setOnClickListener {
            onPauseResumeCallback?.invoke()
        }

        val cardLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        outerWrapper.addView(cardView, cardLp)
        return outerWrapper
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

    private fun updateAttachedViewInPlace(context: Context, root: View) {
        if (currentMode == OverlayMode.DOCKED_PILL && !lastIsShowingAppendConfirm) {
            val miniWaveform = root.findViewById<ImageView>(R.id.notif_recording_waveform)
            miniWaveform?.setImageBitmap(
                renderMiniOscillatingWaveformBitmap(
                    displacements = lastOscillatingDisplacements,
                    isPaused = lastIsPaused
                )
            )
            val timerText = root.findViewById<TextView>(R.id.notif_recording_paused_timer)
            timerText?.setTextColor(if (lastIsPaused) 0x99F4D7C7.toInt() else 0xFFF08C4B.toInt())
            timerText?.text = formatTimerMs(lastElapsedMs)
        } else if (!lastIsShowingAppendConfirm) {
            bindExpandedRecordingCardViews(context, root)
        }
    }

    private fun syncOverlayView(context: Context, forceRecreate: Boolean = false) {
        if (!shouldDisplayOverlay() || !canDrawSystemOverlay(context)) {
            detachWindowOnly()
            currentOverlayRootView = buildOverlayContentView(context)
            return
        }

        val existingView = attachedWindowView
        if (!forceRecreate &&
            isOverlayAttachedToWindow &&
            existingView != null &&
            attachedMode == currentMode &&
            attachedConfirmState == lastIsShowingAppendConfirm
        ) {
            updateAttachedViewInPlace(context, existingView)
            currentOverlayRootView = existingView
            return
        }

        // Remove the previously attached view BEFORE creating and attaching the new view
        detachWindowOnly()

        val content = buildOverlayContentView(context)
        currentOverlayRootView = content

        try {
            val wm = (windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager))
                ?.also { windowManager = it } ?: return

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val isPill = currentMode == OverlayMode.DOCKED_PILL && !lastIsShowingAppendConfirm
            val widthSpec = if (isPill) {
                WindowManager.LayoutParams.WRAP_CONTENT
            } else {
                WindowManager.LayoutParams.MATCH_PARENT
            }
            val heightSpec = if (isPill || lastIsShowingAppendConfirm) {
                WindowManager.LayoutParams.WRAP_CONTENT
            } else {
                // Full-screen transparent touch catcher in EXPANDED_CARD mode so any outside tap collapses back to DOCKED_PILL
                WindowManager.LayoutParams.MATCH_PARENT
            }

            val lp = WindowManager.LayoutParams(
                widthSpec,
                heightSpec,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (6f * context.resources.displayMetrics.density).toInt()
            }

            wm.addView(content, lp)
            attachedWindowView = content
            attachedMode = currentMode
            attachedConfirmState = lastIsShowingAppendConfirm
            isOverlayAttachedToWindow = true
        } catch (_: Exception) {
            attachedWindowView = null
            isOverlayAttachedToWindow = false
        }
    }

    private fun detachOverlayWindow() {
        detachWindowOnly()
        currentOverlayRootView = null
    }

    private fun detachWindowOnly() {
        val viewToRemove = attachedWindowView
        attachedWindowView = null
        attachedMode = null
        attachedConfirmState = null
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
        val padX = 3f
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
            strokeWidth = 3f
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
            String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
