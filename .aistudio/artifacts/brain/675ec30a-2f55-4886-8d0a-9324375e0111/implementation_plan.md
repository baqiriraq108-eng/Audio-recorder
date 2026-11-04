# Implementation Plan: Unified Service with Custom Recording Notification, Dynamic Island Overlay Capsule & Centered Oscillating Waveform

## 1. Architectural Overview & Root-Cause Resolution

### A. Unified `RecordingForegroundService` with Strict State Separation
We will keep the single `RecordingForegroundService` to manage both **Recording (`isPlaybackMode == false`)** and **Playback (`isPlaybackMode == true`)**, while eliminating every trigger that caused Android / HyperOS SystemUI to hijack the Recording notification into the OS Media Player carousel:

1. **Playback State (`isPlaybackMode == true`) — 100% Untouched**:
   - Retains `Notification.MediaStyle().setMediaSession(token)` with `setShowActionsInCompactView(0, 1, 2)`.
   - Activates `mediaSession.isActive = true` and updates `PlaybackState` (`STATE_PLAYING` / `STATE_PAUSED`) and `MediaMetadata` (`METADATA_KEY_DURATION`) so the standard playback seekbar and 5 media controls (`Previous`, `Play/Pause`, `Next`, `Bookmark`, `Continue Recording`) function identically.
   - Starts foreground with `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`.

2. **Recording State (`isPlaybackMode == false`) — Complete Removal of `MediaStyle` & Media Carousel Hijacking**:
   - **No `MediaStyle` / No `DecoratedCustomViewStyle`**: Never call `.setStyle(...)` on the Recording notification builder.
   - **No `MediaSession` Attachment or Active PlaybackState**: Do not set `EXTRA_MEDIA_SESSION`, do not call `mediaSession.setPlaybackState(STATE_PLAYING)` when `isPlaybackMode == false`, and keep `mediaSession.isActive = false` during Recording so Android 13–16 and HyperOS `MiuiMediaControlPanel` never promote the recording notification into a media player card with a `00:00 / 00:00` seekbar or Google Cast output switcher.
   - **Microphone-Only Foreground Service Type During Recording**: Call `startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)` during Recording (instead of `MICROPHONE or MEDIA_PLAYBACK`) so the OS does not classify the active recording notification as a media-playback session.
   - **Strictly Non-Expandable Custom `RemoteViews` Card (CRITICAL FIX FOR CHEVRON ARROW)**:
     * Bind the fixed-height dark card (`#1A1614`, `24dp` corner radius) ONLY to `setCustomContentView(remoteViews)` and `setCustomHeadsUpContentView(remoteViews)`.
     * **DO NOT call `setCustomBigContentView(...)`** (keep `bigContentView = null`). In Android/HyperOS, setting `bigContentView`—even with the exact same layout—forces the OS to render the expand/collapse chevron arrow (`^`). Leaving `bigContentView` null guarantees zero expansion arrows across the notification shade and lock screen.
     * Do not attach standard `builder.addAction(...)` footer rows.

---

## 2. Visual Design & Component Specifications

### A. Recording Card Layout (`#1A1614` Dark Surface, `24dp` Rounded Corners)
Used identically by both the **Notification Shade Custom `RemoteViews`** and the **Expanded System Overlay Card** (`RecordingIslandOverlayManager`):

1. **Top Row (Edge-to-Edge Header — Zero Cast Button)**:
   - **Far-Left**: Studio microphone icon (`@drawable/ic_notif_mic` tinted `#F08C4B`) + status label (`Recording` when active, `Paused` when paused).
   - **Far-Right**: Live real-time chronometer/timer (`00:14`) accurately offset by `priorAccumulatedMs` / `recordingElapsedMs` when appending or continuing a recording, and freezing cleanly at the exact elapsed timestamp (`setTextViewText` on `@+id/notif_recording_paused_timer`) the instant recording is paused.
   - **Strict Cast Removal**: Zero Cast or media-routing icons in the top-right corner; only the live/frozen timer is present.

2. **Middle Row (Full-Width Horizontally Centered Oscillating Audio Waveform)**:
   - Spans the full horizontal width previously occupied by the seekbar (`match_parent` with `16dp` start/end padding, height `46dp`).
   - **Single Smooth Continuous Oscillating Wave (`Canvas.drawPath`)**:
     - Centered vertically along the horizontal midline (`centerY = heightPx / 2f`).
     - Uses smooth cubic-Bezier curve segments (`wavePath.cubicTo(...)`) that alternate crests above `centerY` and troughs below `centerY` (`sign = if (i % 2 == 0) -1f else 1f`) across the full horizontal span—never vertical bars/columns and never climbing diagonally upward.
     - Fluctuates dynamically in amplitude and frequency with live microphone voice peaks (`advanceRecordingWaveformBars`).
   - **Studio Coral-Orange Linear Gradient & Center Glow**:
     - Primary oscillating wave stroke rendered with a horizontal linear gradient (`#E38851` → `#F08C4B` → `#FFB485`).
     - Closed glow path between the oscillating wave curve and `centerY` filled with a vertical shader that has **35% opacity (`alpha = 89`)** at the wave crests/troughs and tapers smoothly to **0% opacity (`alpha = 0`)** at the horizontal center baseline (`centerY`).
   - **Paused State Behavior**:
     - The instant recording is paused (`isPaused == true`), `advanceRecordingWaveformBars` becomes a strict no-op, freezing the exact oscillating wave crests and troughs in place and dimming the rendered waveform opacity to **40% (`alpha = 102`, `PAUSED_WAVEFORM_OPACITY = 0.40f`)**.

3. **Bottom Row (Strict 3-Button Layout Directly Beneath the Waveform)**:
   - Positioned inside a full-width `RelativeLayout` directly below `@+id/notif_recording_waveform`:
     - **Far-Left Edge (`ALIGN_PARENT_START` / `ALIGN_PARENT_LEFT`)**: **Bookmark** button (`@+id/notif_recording_btn_bookmark` → `ACTION_NOTIFICATION_BOOKMARK`).
     - **Center (`CENTER_IN_PARENT`)**: **Save / Stop & Save** button (`@+id/notif_recording_btn_stop_save` → `ACTION_NOTIFICATION_STOP_SAVE`), which transitions to the inline save confirmation (`Θ Don't` / `Ω Save`).
     - **Far-Right Edge (`ALIGN_PARENT_END` / `ALIGN_PARENT_RIGHT`)**: **Pause / Resume** toggle button (`@+id/notif_recording_btn_pause_resume` → `ACTION_NOTIFICATION_PAUSE` / `ACTION_NOTIFICATION_RESUME`), displaying the **Pause icon (`⏸`, `@drawable/ic_notif_pause`)** while recording and swapping immediately to the **Play/Resume icon (`▶`, `@drawable/ic_notif_resume`)** when paused.

---

### B. Xiaomi-Style System Overlay Dynamic Capsule (`RecordingIslandOverlayManager`)
- **Visibility & Lifecycle Synchronization (CRITICAL)**:
  - The overlay capsule MUST only appear when the app moves to the **background / Home Screen** during an active or paused recording session.
  - When `MainActivity` is in the foreground (visible on screen), automatically hide the overlay capsule so it does not overlap the in-app UI. When the user presses Home or switches apps, smoothly animate and dock the capsule at the top-center of the screen.
- **Permission Handling (Proactive First-Time Prompt + Fallback)**:
  - Uses `WindowManager` with `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` (`FLAG_NOT_FOCUSABLE`, `FLAG_LAYOUT_IN_SCREEN`) whenever `Settings.canDrawOverlays(context)` is granted.
  - If `Settings.canDrawOverlays(context)` is NOT granted when starting a recording or backgrounding the app for the first time, proactively prompt the user once to grant the overlay permission (`Settings.ACTION_MANAGE_OVERLAY_PERMISSION` for `package:$packageName`), while also keeping a toggle in Settings and falling back gracefully without crashing if declined.
- **Two Interactive Modes**:
  1. **Docked Pill Mode (When App Goes to Background / Home Screen)**:
     - Compact black capsule (`#0D0B0A` with subtle `#26F08C4B` border, `999dp` pill radius) docked at `Gravity.TOP or Gravity.CENTER_HORIZONTAL` near the status bar / camera cutout.
     - Left side displays a live mini oscillating coral waveform (`ImageView` driven by the same oscillating cubic-Bezier renderer); right side displays the live recording timer (`00:14`, offset by `priorAccumulatedMs` and frozen on pause).
     - Tapping the docked pill smoothly expands it into **Expanded Card Mode**.
  2. **Expanded Card Mode (When Docked Pill is Tapped)**:
     - Renders the fixed-height `#1A1614` (`24dp` radius) recording card with the Top Row (Mic + status left, live timer right), Middle Row (full-width centered oscillating coral waveform), and Bottom Row (Far-Left `Bookmark`, Center `Save`, Far-Right `Pause/Resume` or inline `Θ Don't` / `Ω Save` confirmation).
     - **Touch & Dismissal Behavior**:
       - Tapping the **card header** (Mic icon / title / timer row) opens `MainActivity` to the active recording screen and hides the overlay.
       - Tapping **outside the card** (via `FLAG_WATCH_OUTSIDE_TOUCH` / backdrop listener) collapses the card back to **Docked Pill Mode**.

---

## 3. Planned File Changes

1. **`/app/src/main/AndroidManifest.xml`**:
   - Declare `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />` for the Xiaomi-style Dynamic Island overlay capsule.
2. **`/app/src/main/res/drawable/bg_notif_recording_card.xml`**:
   - Update card surface color to `#FF1A1614` with `24dp` rounded corners (`android:radius="24dp"`).
3. **`/app/src/main/res/layout/notification_recording_live.xml` & `notification_recording_compact.xml`**:
   - Unify into a sleek, fixed-height non-expandable dark card (`#1A1614`, `24dp` radius) with `16dp` horizontal padding on the waveform row (`@+id/notif_recording_waveform`), top header (`Mic` + `Recording`/`Paused` on Far-Left, live/frozen timer on Far-Right, zero Cast icon), and bottom 3-button `RelativeLayout` (`Bookmark` Far-Left, `Save` Center, `Pause/Resume` Far-Right).
4. **`/app/src/main/java/com/example/service/RecordingIslandOverlayManager.kt`** *(New File)*:
   - Implements the Xiaomi-style Dynamic Island capsule controller with `DockedPill` and `ExpandedCard` states, foreground/background visibility synchronization, `WindowManager` attachment when `Settings.canDrawOverlays(context)` is granted, proactive one-time `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` prompt helper, outside-touch collapse to Docked Pill, header-tap launch of `MainActivity`, and direct action callbacks (`onBookmark`, `onStopSave`, `onPauseResume`, `onConfirmSave`, `onCancelSave`).
5. **`/app/src/main/java/com/example/service/RecordingForegroundService.kt`**:
   - Replace `computeAscendingSplineHeights` / ascending curve logic with `computeOscillatingWavePoints` and `renderRecordingWaveformBitmap` drawing a **single smooth continuous oscillating cubic-Bezier wave** centered along `centerY = heightPx / 2f` with alternating crests and troughs, studio coral-orange linear gradient (`#E38851` → `#F08C4B` → `#FFB485`), and a `35% → 0%` translucent coral glow tapering toward the center baseline.
   - Freeze oscillating wave heights and dim opacity to `40%` (`alpha = 102`) when `isPaused == true`.
   - Strip all `MediaStyle`, `EXTRA_MEDIA_SESSION`, `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` (during recording), `setCustomBigContentView`, and standard `Notification.Action` footer additions from `buildRecordingNotification()` while preserving `buildPlaybackNotification()` 100% untouched.
   - Integrate `RecordingIslandOverlayManager` lifecycle updates on recording start, app background/foreground transitions, tick, pause/resume, append save confirmation, and stop.
6. **`/app/src/test/java/com/example/ExampleRobolectricTest.kt`**:
   - Verify absence of `MediaStyle`, `DecoratedCustomViewStyle`, `bigContentView`, and `EXTRA_MEDIA_SESSION` in Recording state, verify centered oscillating wave geometry and 40% opacity freeze on pause, and verify strict 3-button ordering (`Bookmark` Far-Left, `Save` Center, `Pause/Resume` Far-Right) and `RecordingIslandOverlayManager` lifecycle behavior.
