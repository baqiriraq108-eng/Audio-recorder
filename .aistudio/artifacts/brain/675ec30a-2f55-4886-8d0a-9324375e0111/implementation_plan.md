# In-Place Geometric Dynamic Island Morphing (Zero Jump / Twist / Slide Expansion)

This update eliminates the jumpy, twisting, and side-sliding artifacts during recording capsule expansion and collapse by replacing the view teardown and scale-matrix approach with a **pure in-place geometric morph** (matching YouTube Music and iOS Dynamic Island physics) that smoothly animates width, height, and corner radius from a single persistent view anchored strictly at top-center.

### User Review & Critical Decisions

> [!IMPORTANT]
> This plan directly addresses all three critical behavioral fixes requested, while preserving all previously confirmed behaviors (physical screen-right timer, 1:1 drag-to-discard with unclipped bottom trash target, and the Greek save confirmation card with pre-filled title input):

- **Strict Fix 1 — Pure In-Place Geometric Expansion (`windowAnimations = 0`)**: The overlay keeps the exact same `WindowManager` host view and persistent capsule container attached across `DOCKED_PILL` $\leftrightarrow$ `EXPANDED_CARD` transitions without calling `removeAllViews()` or re-inflating views, and explicitly sets `windowAnimations = 0` on `WindowManager.LayoutParams` so Android never triggers default window enter/exit/resize slides.
- **Strict Fix 2 — Zero Twisting, Sliding, or Bouncy Overshoot**: All `scaleX`/`scaleY` matrix distortions, mid-animation `pivotX` mutations, horizontal X-axis translations, and bouncy overshoot interpolators are removed. The capsule's horizontal center remains locked to the screen's top-center axis (`Gravity.TOP or Gravity.CENTER_HORIZONTAL`, `translationX = 0f`, `rotation = 0f`) on every frame.
- **Strict Fix 3 — Single Synchronized Width, Height, Corner Radius & Crossfade Animator**: A single monotonic `ValueAnimator` (`0f` $\leftrightarrow$ `1f`, using non-overshooting `PathInterpolator(0.2f, 0f, 0f, 1f)`) simultaneously interpolates the capsule's pixel width (`pillWidthPx` $\leftrightarrow$ `expandedWidthPx`), pixel height (`pillHeightPx` $\leftrightarrow$ `expandedHeightPx`), and corner radius (`pillRadiusPx` $\leftrightarrow$ `24dp`), while crossfading the pre-sized compact pill layer and expanded controls layer inside a rounded-outline-clipped container so internal views never snap or reflow.

---

### 1. Overview & Core Concept

- **What It Does**:
  1. **Root Cause Elimination**: Previously, expanding the capsule swapped `WindowManager.LayoutParams.width` between `WRAP_CONTENT` and `MATCH_PARENT` while destroying and recreating the child view tree and mutating `pivotX` alongside `scaleX`/`scaleY`. Because `WindowManager.updateViewLayout` resizes asynchronously and triggers system window animations when `windowAnimations` is unset, the surface jumped horizontally from the screen edge and fought with the view's scale matrix.
  2. **In-Place Geometric Shell Morph**: A single persistent capsule shell (`morphCapsuleShell`) holds both the compact pill layer and the expanded recording controls layer simultaneously. Tapping the pill drives a single synchronized progress value $p \in [0, 1]$ that interpolates the shell's exact `width`, `height`, `cornerRadius`, and surface color outward from top-center and downward from the top edge.
  3. **Fixed-Coordinate Internal Crossfade**: Inside the clipping capsule shell (`clipToOutline = true`), both the compact pill content and the expanded recording controls maintain fixed internal dimensions centered at top-center. As the shell expands or collapses, the two layers smoothly crossfade in opacity without any internal layout reflow, text jumping, or icon stretching.
- **Target Audience / Persona**: Users multitasking with the floating recording capsule who expect flagship, rock-solid Dynamic Island expansion and collapse physics (like YouTube Music) with zero visual glitches.
- **Key Value**: Delivers a completely stable, anchored top-center expansion and collapse transition with zero frame jumps, zero horizontal sliding, and zero view destruction overhead.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Docked Top-Center Compact Pill ($p = 0.0$)**:
     - The capsule rests at top-center (`y = 6dp`) at its exact compact dimensions (`pillWidthPx × pillHeightPx`, fully rounded pill corner radius `height / 2f`, `#0D0B0A` surface).
     - The compact pill layer (`alpha = 1.0f`) displays the mini oscillating coral waveform on the physical left and the monospace timer on the physical right (`LAYOUT_DIRECTION_LTR`). The expanded controls layer sits hidden (`alpha = 0.0f`, `INVISIBLE`) inside the clipped bounds.
  2. **Tap-to-Expand Geometric Morph ($p: 0.0 \rightarrow 1.0$)**:
     - Tapping the compact pill immediately starts the single synchronized `ValueAnimator` (`220ms`, `PathInterpolator(0.2f, 0f, 0f, 1f)`—smooth deceleration with zero bouncy overshoot).
     - **Width**: Symmetrically widens outward from top-center from `pillWidthPx` to `expandedWidthPx` (`screenWidth - 24dp`).
     - **Height**: Expands downward from the top edge from `pillHeightPx` to `expandedHeightPx`.
     - **Corner Radius**: Morphs continuously from `pillHeightPx / 2f` (full pill semicircle ends) to `24dp` (expanded card rounded corners) via both `GradientDrawable.cornerRadius` and a hardware `ViewOutlineProvider` (`clipToOutline = true`).
     - **Internal Crossfade**:
       - Compact pill content (`dockedPillLayer`) fades out cleanly over $p \in [0.0, 0.30]$ while remaining anchored at top-center without stretching.
       - Expanded card content (`expandedCardLayer`) fades in smoothly over $p \in [0.18, 1.0]$ at its full pre-measured width so the header, live waveform, and 3 icon buttons (`Bookmark`, `Save`, `Pause/Resume`) reveal in place without shifting or snapping.
  3. **Outside-Tap In-Place Geometric Collapse ($p: 1.0 \rightarrow 0.0$)**:
     - Tapping anywhere outside the expanded capsule reverses the exact same `ValueAnimator` from the current progress back to `0.0f` over `190ms`.
     - The expanded controls fade out over the first phase while the capsule shell smoothly narrows inward toward top-center and upward toward the top edge, fading the compact pill timer and mini waveform back in as the corner radius returns to a full pill.
     - Only after the collapse animation finishes ($p = 0.0$) does the outer `WindowManager.LayoutParams` tighten back to the compact pill touch bounds so the user never sees a mid-animation window jump.

- **Visual Identity & Theme**:
  - *Surface Color Interpolation*: Smoothly blends via `ArgbEvaluator` from `#0D0B0A` (Docked Pill) to `#1A1614` (Expanded Card) with a `1dp` subtle coral border (`#33F08C4B` $\rightarrow$ `#26F08C4B`).
  - *Motion Curve*: Monotonic `PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)` (standard Material 3 emphasized decelerate without spring overshoot) at `120 Hz` preferred refresh rate (`windowAnimations = 0`).

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Persistent Dual-Layer Morph Container vs. `removeAllViews()` View Swapping**
  - *Chosen Approach*: Keep a single persistent `morphCapsuleShell` (`FrameLayout`) attached inside the `WindowManager` host view containing both `dockedPillLayer` and `expandedCardLayer` pre-measured to their intrinsic dimensions, and animate `morphCapsuleShell.layoutParams.width`, `morphCapsuleShell.layoutParams.height`, and `morphCapsuleBackground.cornerRadius`.
  - *Why*: Destroying and inflating views on tap caused an unmeasured `width = 0` frame and forced WindowManager to relayout from scratch. Pre-hosting both layers inside a single outline-clipped shell guarantees 0ms instant response and rock-solid top-center symmetry on every frame.
- **Decision 2: True Geometric Bounds + Outline Clipping Instead of `scaleX` / `scaleY`**
  - *Chosen Approach*: Keep `scaleX = 1f`, `scaleY = 1f`, `rotation = 0f`, and `translationX = 0f` permanently on the capsule and its children, and animate the actual pixel `width`, `height`, and `cornerRadius` (with `clipToOutline = true`).
  - *Why*: Matrix scaling (`scaleX`/`scaleY`) stretches child text, distorts circular corners into ellipses when X and Y scale ratios differ, and causes horizontal jumping whenever `pivotX` updates after a layout pass. True geometric dimension animation with outline clipping is how YouTube Music and system Dynamic Island implementations achieve crisp, distortion-free morphing.
- **Decision 3: Disabling Default System Window Animations (`windowAnimations = 0`)**
  - *Chosen Approach*: Explicitly set `windowAnimations = 0` on all `WindowManager.LayoutParams` created by `RecordingIslandOverlayManager`.
  - *Why*: Prevents Android's WindowManager from applying default system enter/exit/resize transitions that slide or fade the overlay window from the side of the screen.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:
```
┌────────────────────────────────────────────────────────────────────────────┐
│            Persistent WindowManager Host (windowAnimations = 0)            │
│      Gravity.TOP | Gravity.CENTER_HORIZONTAL, FLAG_NOT_TOUCH_MODAL         │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│   ┌─────────────────────── morphCapsuleShell ──────────────────────────┐   │
│   │  Single Synchronized ValueAnimator (p: 0.0 <-> 1.0, 120 Hz)        │   │
│   │  • Width:        lerp(pillWidthPx, expandedWidthPx, p)             │   │
│   │  • Height:       lerp(pillHeightPx, expandedHeightPx, p)           │   │
│   │  • CornerRadius: lerp(pillHeightPx / 2f, 24dp, p) + clipToOutline  │   │
│   │  • SurfaceColor: lerpArgb(#0D0B0A, #1A1614, p)                     │   │
│   │  • Anchor:       Gravity.TOP | Gravity.CENTER_HORIZONTAL           │   │
│   │  • Transform:    translationX = 0, rotation = 0, scaleX/Y = 1.0    │   │
│   ├────────────────────────────────────────────────────────────────────┤   │
│   │  Layer A: dockedPillLayer (Fixed pillWidthPx × pillHeightPx)       │   │
│   │  • Gravity.TOP | Gravity.CENTER_HORIZONTAL                         │   │
│   │  • Alpha = clamp(1.0 - p / 0.30, 0.0, 1.0)                         │   │
│   │  • [Mini Waveform 34x15dp] ──14dp──> [Physical-Right Timer 00:12]  │   │
│   ├────────────────────────────────────────────────────────────────────┤   │
│   │  Layer B: expandedCardLayer (Fixed expandedWidthPx × expandedH)    │   │
│   │  • Gravity.TOP | Gravity.CENTER_HORIZONTAL                         │   │
│   │  • Alpha = clamp((p - 0.18) / 0.82, 0.0, 1.0)                      │   │
│   │  • Header (Mic + Status + Live Chronometer)                        │   │
│   │  • Full-Width Live Oscillating Waveform                            │   │
│   │  • Bottom Action Row ([Bookmark]   [Save]   [Pause/Resume])        │   │
│   └────────────────────────────────────────────────────────────────────┘   │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

- **Interactive Component & State Mapping**:
  - **Window Layout Configuration (`createWindowLayoutParams`)**:
    - Explicitly assigns `windowAnimations = 0` on both the main capsule `WindowManager.LayoutParams` and the bottom trash target `WindowManager.LayoutParams`.
    - Keeps `gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL` and `height = WindowManager.LayoutParams.WRAP_CONTENT` so the rest of the phone screen remains 100% interactive (`FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH`).
  - **Persistent Morphable Capsule Hierarchy (`RecordingIslandOverlayManager`)**:
    - Pre-measures `dockedPillLayer` (`pillWidthPx`, `pillHeightPx`) and `expandedCardLayer` (`expandedWidthPx = screenWidth - 24dp`, `expandedHeightPx`) using `View.MeasureSpec` so exact start and end pixel dimensions are always known deterministically—even before the first hardware frame is drawn.
    - Wraps both layers inside `morphCapsuleShell` (`FrameLayout`) with `clipChildren = true`, `clipToPadding = true`, `clipToOutline = true`, and a custom `ViewOutlineProvider` that clips to `(0, 0, view.width, view.height, currentCornerRadiusPx)`.
  - **Single Synchronized Morph Animator (`animateCapsuleGeometricMorph(targetProgress: Float)`)**:
    - Uses `ValueAnimator.ofFloat(currentMorphProgress, targetProgress)` with `PathInterpolator(0.2f, 0f, 0f, 1f)` (`220ms` expand, `190ms` collapse).
    - On each frame (`applyMorphProgress(p)`):
      1. Computes `w = lerp(pillWidthPx, expandedWidthPx, p)` and `h = lerp(pillHeightPx, expandedHeightPx, p)` and updates `morphCapsuleShell.layoutParams.width = w`, `morphCapsuleShell.layoutParams.height = h`, calling `morphCapsuleShell.requestLayout()`.
      2. Computes `radius = lerp(pillHeightPx / 2f, 24dp, p)` and updates `morphCapsuleBackground.cornerRadius = radius` and `morphCapsuleShell.invalidateOutline()`.
      3. Computes `dockedPillLayer.alpha = ((0.30f - p) / 0.30f).coerceIn(0f, 1f)` and `expandedCardLayer.alpha = ((p - 0.18f) / 0.82f).coerceIn(0f, 1f)`, toggling `visibility` (`VISIBLE` vs `INVISIBLE`) and touch clickability cleanly so only the active layer receives taps.
      4. Enforces `translationX = 0f`, `translationY = 0f`, `rotation = 0f`, `scaleX = 1f`, `scaleY = 1f` on the shell and both layers so twisting, sliding, or dancing is physically impossible.
