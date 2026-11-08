package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Centralized Warm Semantic Color Token Hierarchy
// 1. Base Background & Surface Containers
val ObsidianBackground = Color(0xFF14100D)       // Base App Background / Inset Containers
val WarmSecondarySurface = Color(0xFF221B17)     // Primary Containers / Cards / Procedural Dialogs
val ElevatedSurface = Color(0xFF2C221D)          // Elevated Surfaces / Top App Bar Buttons / Popup Menus
val ElevatedSurfaceHigh = Color(0xFF382B24)      // Surface Container Highest / Muted Slot
val TonalDarkBrown = Color(0xFF33251E)           // Tonal Action Pill Button & Chip Container

// 2. Navigation Bar Tokens
val NavPillIndicator = Color(0xFF543D31)         // Navigation Bar Active Pill Indicator
val NavActiveContent = Color(0xFFF4D7C7)         // Navigation Active Icon & Label
val NavInactiveContent = Color(0xFFA79589)       // Navigation Inactive Icon & Label

// 3. Primary Action & Active/Selected Accents
val DriveRecordFab = Color(0xFF8A4A18)           // Primary Action / Record FAB (Warm Burnt Terracotta)
val PeachAccent = Color(0xFFE38851)              // Active / Selected Accent & Playhead
val PeachAccentSoft = Color(0xFFF4D7C7)          // Soft Warm Peach Ivory
val PeachAccentContainer = Color(0xFF543D31)     // Warm Terracotta Container
val CloudArrowBrown = ObsidianBackground          // Two-tone arrow cutout matching base background
val CloudArrowColor = ObsidianBackground          // Two-tone arrow color for cloud icons
val Mp4BadgeContainerColor = ObsidianBackground   // Format badge container token
val OnPeachAccent = Color(0xFF14100D)            // High-contrast dark on PeachAccent
val OnDriveRecordFab = Color(0xFFF4D7C7)         // Warm ivory icon on DriveRecordFab

// 4. Typography & Borders
val PrimaryText = Color(0xFFF4D7C7)              // Primary Typography (Soft Warm Peach Ivory)
val SecondaryText = Color(0xFFA79589)            // Secondary / Muted Typography (Warm Grey-Brown)
val OutlineWarm = Color(0xFF382B24)              // Centralized Border & Divider Token

// 5. Waveform Tokens
val WaveformActiveBar = Color(0xFFF4D7C7)        // Waveform Active Bars
val WaveformInactiveBar = Color(0xFF382B24)      // Waveform Inactive/Muted Bars
val WaveformPlayhead = Color(0xFFE38851)         // Playhead / Scrubbing Cursor

// 6. Semantic Status Tokens & State Aliases
val DestructiveCrimson = PeachAccent             // Mapped to Active Warm Accent (no rogue red)
val DestructiveContainer = TonalDarkBrown        // Mapped to Tonal Dark Container
val SuccessSage = Color(0xFF81C784)              // Designated Semantic Green for Cloud Synced & Downloaded Checkmark Status
val SuccessContainer = TonalDarkBrown            // Mapped to Tonal Dark Container
val WarningAmber = SecondaryText                 // Mapped to Muted Warm Typography (no rogue yellow)
