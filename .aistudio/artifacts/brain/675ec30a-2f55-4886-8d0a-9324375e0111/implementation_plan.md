# Unified Dark Dialog Styling, Coral-Orange Audio Chips & In-Place Capsule Completion Flow

This update unifies the surface color, corner radius, and button aesthetic across all procedural dialogs to match the deep dark surface of **Edit Recording Details**, styles selected chips in the **Recording & Encoder** modal with a tonal dark fill and studio coral-orange border and text, and refines the recording completion flow so only the Dynamic Island capsule transitions in-place on the home screen without spawning a duplicate heads-up notification banner.

### User Review & Critical Decisions

> [!IMPORTANT]
> The following design and behavioral choices were confirmed during clarification and govern this implementation:

- **Confirmed Decision 1 — Coral-Orange Accent on Selected Audio Setting Chips**: Selected chips in the **Recording & Encoder** modal (`Default Audio Format`, `Sample Rate`, and `Channels & Bit Depth`) will use a **tonal dark fill with coral-orange border and coral-orange text** (`#E38851` / `#F08C4B`), while unselected chips retain the muted dark surface with light beige text.
- **Confirmed Decision 2 — Zero-Elevation Deep Dark Surface Across All Dialogs**: Every procedural modal (**Move to Trash?**, **Delete Permanently?**, **Recording & Encoder**, **Save Recording**, and **Discard Recording**) will use the exact zero-elevation deep dark surface (`#1E1916` with `0.dp` tonal elevation) matching **Edit Recording Details**, eliminating the lighter reddish-brown surface tint.
- **Confirmed Decision 3 — In-Place Capsule Transition Without Duplicate Heads-Up Pop-Up**: Confirming a saved recording transitions the floating Dynamic Island capsule in-place to the completion state while updating the system notification silently in the notification shade (no `CustomHeadsUpContentView` or peeking alert banner).

---

### 1. Overview & Core Concept

- **What It Does**:
  1. **Unified Deep Dark Dialog Surface**: Removes Material 3 tonal surface tinting across all confirmation and settings dialogs so their container background renders as the exact deep espresso-obsidian surface (`#1E1916`) of **Edit Recording Details**, allowing the tonal dark brown (`#33261E`) **Cancel** and **Confirm** buttons to stand out with crisp contrast.
  2. **Studio Coral-Orange Selected Audio Chips**: Highlights the active **Default Audio Format**, **Sample Rate**, and **Channels & Bit Depth** chips inside **Recording & Encoder** using a tonal dark fill paired with a crisp coral-orange border and coral-orange label text.
  3. **Single In-Place Capsule Completion Transition**: Ensures that when a recording is saved, only the floating Dynamic Island overlay capsule transitions in-place on the home screen, while the system notification updates quietly inside the notification shade without triggering a second floating Heads-Up Notification (HUN) banner.
- **Target Audience / Persona**: Audio creators and mobile studio users who expect cohesive dark-room visual hierarchy across modals and a clean, non-duplicative Dynamic Island completion transition.
- **Key Value**: Eliminates visual discrepancy between dialog backgrounds, makes active encoder settings immediately scannable, and prevents overlapping completion banners on the home screen.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Procedural Confirmation & Editing Modals (Move to Trash?, Delete Permanently?, Save Recording, Discard Recording, Edit Recording Details)**:
     - Opening any dialog displays a `24.dp` rounded container with a pure deep dark surface (`#1E1916`, zero tonal elevation) and generous `24.dp` horizontal / `22.dp` vertical interior padding.
     - At the bottom of every dialog, **Cancel** and **Confirm** sit side-by-side in equal-width `48.dp`-tall pill buttons with tonal dark brown (`#33261E`) backgrounds and light beige (`#F4D7C7`) text.
  2. **Recording & Encoder Modal**:
     - Shares the exact `#1E1916` zero-elevation container and bottom **Cancel** / **Confirm** button row.
     - Active chips in **Default Audio Format**, **Sample Rate**, and **Channels & Bit Depth** render with a tonal dark brown fill (`#33261E` / `#2A221E`), a `1.5.dp` studio coral-orange border (`#E38851`), and coral-orange label text (`#E38851`).
     - Inactive chips render with a recessed dark surface (`#120F0D`) and muted light beige text.
     - Tapping **Cancel** discards uncommitted chip changes and closes the modal; tapping **Confirm** persists the selected encoder configuration.
  3. **In-Place Recording Completion Flow**:
     - When the user confirms saving a recording (`Σ Save` in the capsule or **Confirm** in the Save dialog), the floating Dynamic Island capsule smoothly transitions its own view in-place to the `"Finished recording"` summary state.
     - Simultaneously, the notification shade entry updates silently in the background (without `CustomHeadsUpContentView` and without high-priority peeking), so SystemUI never pops up a second floating banner over the screen.
     - Pulling down the notification shade reveals the completion notification, which can be swiped away in any direction (clearing both shade and capsule) or tapped to open the app directly to the saved recording.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Warm Obsidian Studio — deep zero-glare dark surfaces with warm tonal brown interactive controls and precision coral-orange studio indicators.
  - *Color Palette & Mood*:
    - `--dialog-surface`: `#1E1916` (Warm Secondary Surface, `0.dp` tonal elevation)
    - `--field-unselected-surface`: `#120F0D` (Obsidian Recessed Surface)
    - `--button-tonal-fill`: `#33261E` (Tonal Dark Brown)
    - `--chip-selected-border-text`: `#E38851` (Studio Coral-Orange Accent)
    - `--text-primary`: `#F4D7C7` (Warm Light Beige)
    - `--text-secondary`: `#B89F91` (Muted Warm Taupe)
  - *Typography & Hierarchy*: Semi-bold `20.sp` dialog titles, `13.sp` medium section headers, `13.sp` semi-bold chip labels, and `15.sp` semi-bold pill button labels.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Zero Tonal Elevation (`0.dp`) on All Dialog Surfaces**
  - *Chosen Approach*: Explicitly set `tonalElevation = 0.dp` on all dialog `Surface` containers using `#1E1916`.
  - *Why*: Material 3 applies a `surfaceTint` overlay (configured as coral-orange `#E38851` in the app's color scheme) whenever `tonalElevation > 0.dp`. That tint shifted `6.dp`-elevated dialogs to a lighter reddish-brown that clashed with the `#33261E` action buttons. Setting `0.dp` matches **Edit Recording Details** pixel-for-pixel.
  - *Alternatives Considered*: Keeping `tonalElevation = 6.dp` and darkening the base color — rejected because custom tint math varies across screens, whereas `0.dp` directly uses the exact token already proven in **Edit Recording Details**.

- **Decision 2: Tonal Dark Fill with Coral-Orange Border & Text for Selected Audio Chips**
  - *Chosen Approach*: Render selected encoder chips with a tonal dark container fill (`#33261E`), a `1.5.dp` `#E38851` border, and `#E38851` bold text.
  - *Why*: Preserves the restrained dark studio aesthetic of the modal while giving active audio parameters unmistakable visual distinction.
  - *Alternatives Considered*: Solid orange fill — passed over per user preference for a tonal dark fill with coral-orange border and text.

- **Decision 3: Silent Notification Shade Update + In-Place Overlay Transition**
  - *Chosen Approach*: Remove `setCustomHeadsUpContentView` from the completion notification and post the completion update using a non-peeking channel/priority (`IMPORTANCE_DEFAULT` / `PRIORITY_DEFAULT` with `setOnlyAlertOnce(true)` and no sound/vibration), while `RecordingIslandOverlayManager` updates its existing window host in-place.
  - *Why*: Prevents Android SystemUI from displaying a duplicate floating Heads-Up Notification banner at the top of the screen while keeping the notification present, swipe-dismissible, and tap-navigable inside the notification shade.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:
```
┌──────────────────────────────────────────────────────────────────────────┐
│                     Unified Procedural Dialog System                     │
│  Surface(color = #1E1916, shape = 24.dp, tonalElevation = 0.dp)          │
├────────────────────────────────────┬─────────────────────────────────────┤
│  Confirmation & Edit Modals        │  Recording & Encoder Modal          │
│  • Edit Recording Details          │  • Draft State (Format/Rate/Depth)  │
│  • Move to Trash?                  │  • Selected Chip: Tonal Dark Fill   │
│  • Delete Permanently?             │    + #E38851 Border & Text          │
│  • Save / Discard Recording        │  • Unselected Chip: #120F0D + Beige │
├────────────────────────────────────┴─────────────────────────────────────┤
│  Unified Action Row: [ Cancel (#33261E) ]       [ Confirm (#33261E) ]    │
└──────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│                   Recording Completion Lifecycle                         │
│                                                                          │
│  [User Confirms Save]                                                    │
│          │                                                               │
│          ├──► Dynamic Island Overlay Host (In-Place View Transition)     │
│          │    • Reuses attached FrameLayout window container             │
│          │    • Transitions layout in-place to "Finished recording"      │
│          │    • Up / Left / Right swipe dismisses capsule + shade        │
│          │                                                               │
│          └──► System Notification Shade (Silent Shade-Only Update)       │
│               • No CustomHeadsUpContentView (prevents duplicate pop-up)  │
│               • Non-peeking priority / silent completion channel         │
│               • Swipeable in shade & tap opens saved recording in app    │
└──────────────────────────────────────────────────────────────────────────┘
```

- **Interactive Component & State Mapping**:
  - **Dialog Surface Standardization**: All modal composables bind to the shared `#1E1916` surface with `0.dp` tonal elevation, `RoundedCornerShape(24.dp)`, outer `24.dp` horizontal margin, and inner `24.dp` horizontal / `22.dp` vertical padding.
  - **Encoder Chip Selection State**: Selecting a format, sample rate, or channel/bit-depth updates local draft state inside the modal, immediately shifting the chip border and label to `#E38851` and fill to `#33261E`. Tapping **Cancel** discards draft edits; tapping **Confirm** commits them to `SettingsRepository`.
  - **Completion Overlay & Shade Synchronization**: Saving a recording updates `RecordingIslandOverlayManager`'s existing `FrameLayout` host in-place and posts the completion notification without `CustomHeadsUpContentView` on a silent shade channel so only the Dynamic Island capsule animates on screen.
