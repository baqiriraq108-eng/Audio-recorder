# Implementation Plan: In-Place Overlay Transition (No Flicker) & Non-Overlapping Status Banner Hierarchy

## 1. Fix Completion Capsule Flickering on Home Screen (`RecordingIslandOverlayManager.kt`, `RecordingForegroundService.kt`, `RecorderViewModel.kt`)
- **Root Cause**:
  1. `syncOverlayView(..., forceRecreate = true)` previously called `detachWindowOnly()` (`windowManager.removeViewImmediate(...)`) followed by `windowManager.addView(...)` on every state transition, causing the system window to tear down and re-attach visibly.
  2. When confirming save, completion was triggered multiple times in rapid succession: first from the overlay button click listener, second from `RecordingForegroundService.handleConfirmAppendSaveFromNotification()`, and third from `RecorderViewModel.stopAndSaveImmediatelyFromNotification()` after file finalization.
- **Fix — Keep Window Attached & Transition In-Place**:
  - Maintain a single persistent root `FrameLayout` container (`attachedWindowView`) in `WindowManager` once attached.
  - When transitioning between `DOCKED_PILL`, `EXPANDED_CARD` (live recording), `EXPANDED_CARD` (inline `"Θ Don't"` / `"Σ Save"` confirmation), and `EXPANDED_CARD` (`"Finished recording"` completion capsule), **never** call `removeView` + `addView`.
  - Instead, update the window's `WindowManager.LayoutParams` in-place via `windowManager.updateViewLayout(existingView, lp)` and smoothly swap/crossfade the child content inside the existing root container (`animate().alpha(1f).setDuration(150L)`).
- **Fix — Single Deduplicated Completion Emission**:
  - Deduplicate completion triggers so `showFinishedCompletionOverlay` and `showFinishedSavedConfirmation` execute at most once per recording session (`hasEmittedCompletionForSession` guard).
  - When save is confirmed from the notification or overlay capsule, prevent `RecorderViewModel` from firing a second redundant `ACTION_SHOW_SAVED_CONFIRMATION` service intent that re-notifies and re-renders the capsule.

## 2. Fix Status Banner Positioning Above Controls Card (`SavedRecordingPlayerScreen.kt`, `RecorderScreen.kt`, `MainActivity.kt`)
- **Root Cause**:
  - The status banner (`SnackbarHost`) was rendered as a floating overlay in `MainActivity`'s `Scaffold` at a fixed offset from the bottom of the screen. On `SavedRecordingPlayerScreen` (where the bottom navigation bar is hidden and the player controls card is ~220dp–350dp tall), that fixed offset placed the floating banner directly over the central Play/Pause button.
- **Fix — Anchor Strictly Above Controls Card in Layout Hierarchy**:
  - Create a dedicated inline `StudioStatusBannerHost` composable anchored directly **above** the controls card container (`controls_card_container`) in the vertical layout hierarchy of both `SavedRecordingPlayerScreen` and `RecorderScreen`, with a `12.dp` bottom separation margin (`Modifier.padding(bottom = 12.dp)`).
  - Because the banner sits above `controls_card_container` in the layout tree rather than floating at a hardcoded Y coordinate, it automatically shifts upward whenever the controls card expands (such as when the Trim Selection panel opens) and **never** overlaps the Play/Pause button, trimming tools, speed selector, or recording controls.
