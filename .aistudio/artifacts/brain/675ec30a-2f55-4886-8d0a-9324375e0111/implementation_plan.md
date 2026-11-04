# Refine Recording Notification Card & Preserve Dynamic Island Architecture

## Summary
Refine the background recording notification card shown in the Android/HyperOS notification shade to display exactly the recording status icon, "Recording" status text, live chronometer, and the three control buttons (Bookmark, Save, Pause/Resume) without an audio waveform. Completely preserve the system overlay capsule (Dynamic Island) without touching its live waveform, keeping all capsule behavior intact (compact pill expanding on first tap, second tap opening the active recording window in the app, and outside taps collapsing it back). When tapping Save from either the notification shade or the capsule, the active recording state disappears and transitions cleanly into the Save/Cancel confirmation message card ("Don't" & "Save").

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The user confirmed the following decisions:
> 1. **Notification Status Word**: Keep English text **"Recording"** (and "Paused" when paused) to remain 100% consistent with the studio English interface.
> 2. **Waveform in Notification vs. Capsule**: The notification in the notification shade **will NOT have a waveform**, whereas the floating system capsule **keeps its complete oscillating waveform intact** without any modification.
> 3. **Save Action Flow**: Pressing Save from either the capsule or the notification dismisses the recording state and presents the compact Save/Cancel confirmation message card featuring **Θ Don't** and **Ω Save** buttons.
> 4. **Capsule Touch Hierarchy**: First tap on the contracted docked pill expands it into the control card; tapping anywhere on the expanded card (including the header) opens the app directly to the active recording window; tapping outside collapses the capsule back to the docked pill.

---

## 1. Overview & Core Concept
- **Recording Notification Card (Notification Shade)**: A clean, sleek, non-expandable card with zero MediaStyle seekbar, zero cast icon, and no waveform. It contains:
  1. Header with orange mic icon, "Recording" status word, and monospace live chronometer (frozen when paused).
  2. Bottom row with 3 identical-sized rounded buttons: Far-left Bookmark, Center Save, Far-right Pause/Resume toggle.
- **Dynamic Island Overlay (Floating Capsule)**: Completely preserved and untouched in its contracted docked pill state (miniature live oscillating coral waveform + monospace timer).
  - Tap 1 (when contracted): Expands into the floating card.
  - Tap 2 (when expanded): Clicking the expanded card opens `MainActivity` to the active recording view.
  - Tap outside: Collapses back to the contracted docked pill.
- **Save State Handling**:
  - When Save is tapped from the capsule or notification, the recording card disappears immediately from both the overlay and the notification shade, replaced seamlessly by the Save/Cancel confirmation card.
  - Selecting "Save" saves the recording permanently and shows the "Finished recording" confirmation notification.
  - Selecting "Don't" cancels the save action and stops/discards cleanly.

---

## 2. Technical Architecture & System Flow

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Recording State Sources                         │
│   (AudioStudioEngine -> RecordingForegroundService -> View / Overlays) │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │
           ┌─────────────────────────┴──────────────────────────┐
           ▼                                                     ▼
┌────────────────────────────────────┐       ┌────────────────────────────────────┐
│      Notification Shade Card       │       │    System Overlay Island Capsule   │
│   (notification_recording_live)    │       │  (RecordingIslandOverlayManager)   │
├────────────────────────────────────┤       ├────────────────────────────────────┤
│ • Header: Mic + "Recording" + Time │       │ • Contracted: [ Waveform | Timer ] │
│ • Waveform: GONE (Zero waveform)   │       │ • Expanded:   [ Header + Waveform  │
│ • Actions: [Bookmk | Save | Pause] │       │                 + 3 Action Buttons]│
└──────────────────┬─────────────────┘       └──────────────────┬─────────────────┘
                   │                                            │
                   │             User Taps "Save"               │
                   └─────────────────────┬──────────────────────┘
                                         ▼
                 ┌───────────────────────────────────────────────┐
                 │       Save Confirmation Card & Message        │
                 │    notification_append_confirm_compact        │
                 ├───────────────────────────────────────────────┤
                 │ • Recording notification state dismissed      │
                 │ • Displays: [ Θ Don't ]       [ Ω Save ]      │
                 └───────────────────────┬───────────────────────┘
                                         │
                    ┌────────────────────┴────────────────────┐
                    ▼                                         ▼
             User Taps "Don't"                         User Taps "Save"
     ┌──────────────────────────────┐          ┌──────────────────────────────┐
     │ Discard / Cancel save        │          │ Insert DB entity             │
     │ Dismiss confirmation & cancel│          │ Show "Finished recording"    │
     │ notification completely      │          │ notification with Omega icon │
     └──────────────────────────────┘          └──────────────────────────────┘
```

---

## 3. Detailed Component Specifications

### A. Dedicated Layout Strategy (`notification_recording_live.xml`)
- To prevent removing the waveform from the floating capsule, we cleanly separate the view logic:
  - In `notification_recording_live.xml`: The middle `ImageView` (`notif_recording_waveform`) has `android:visibility="gone"` or its height collapsed in the notification shade, while `RecordingIslandOverlayManager` either sets its visibility to `View.VISIBLE` when inflating for the expanded capsule overlay or retains full waveform display.
  - In `RecordingForegroundService.buildRecordingNotification(...)`:
    - `recordingCardView.setViewVisibility(R.id.notif_recording_waveform, android.view.View.GONE)`
    - The top header holds `notif_recording_icon`, `notif_recording_title` ("Recording"), and `notif_recording_chronometer` (or `notif_recording_paused_timer`).
    - The bottom row holds the 3 equal-width rounded buttons: Bookmark, Stop & Save, Pause/Resume.
    - Zero waveform is drawn or shown in the notification shade.

### B. Dynamic Island Overlay Preservation (`RecordingIslandOverlayManager.kt`)
- **No changes to contracted pill**: Keeps its mini live oscillating coral waveform and monospace timer.
- **Contracted-to-Expanded & Expanded-to-App Touch Handling**:
  - In `buildDockedPillView`: First tap calls `expandFromDockedPill()`.
  - In `buildExpandedCardView`: The card view is clickable (`cardView.setOnClickListener { handleHeaderTapOpenApp(context) }`) so clicking anywhere on the expanded card (outside of the 3 action buttons) opens `MainActivity` to the active recording window.
  - Tapping outside on `outerWrapper` continues to call `collapseToDockedPill()`.
  - In `bindExpandedRecordingCardViews`: `waveformView?.visibility = View.VISIBLE` ensures the expanded capsule continues displaying the live waveform as before.

### C. Save and Confirmation Lifecycle
- In `RecordingForegroundService.handleRecordingStopSaveFromNotification()` and `onStopSaveCallback`:
  - Active recording notification is replaced by the confirmation notification (`showAppendSaveConfirmation()`).
  - Active recording capsule is replaced by the confirmation card (`isShowingAppendConfirmation = true`), which renders the "Don't" and "Save" buttons.
  - On confirming save: `handleConfirmAppendSaveFromNotification()` dismisses the confirmation and posts `showFinishedSavedConfirmation()`.
  - On canceling save: `handleCancelAppendSaveFromNotification()` dismisses the notification and tears down the service.

---

## 4. Verification Plan
1. **Compilation**: Run `compile_applet` to verify clean compilation.
2. **Automated Tests**: Execute `gradle :app:testDebugUnitTest` using Robolectric to ensure:
   - Notification card has `notif_recording_waveform` set to `GONE` in the notification shade.
   - 3 buttons (Bookmark, Save, Pause/Resume) remain intact and clickable.
   - Header contains the mic icon, "Recording" title, and chronometer.
   - Dynamic Island overlay maintains its waveform in both contracted and expanded states.
   - Tapping the expanded card opens `MainActivity`.
   - Pressing Save transitions to the confirmation state with "Don't" and "Save" buttons.
