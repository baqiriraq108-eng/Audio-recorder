package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

// Primary typeface: Roboto Flex with local bundled font weights (Roboto / SansSerif fallback)
val RobotoFlexFontFamily = FontFamily(
    Font(R.font.roboto_flex, FontWeight.Light),
    Font(R.font.roboto_flex, FontWeight.Normal),
    Font(R.font.roboto_flex, FontWeight.Medium),
    Font(R.font.roboto_flex, FontWeight.SemiBold),
    Font(R.font.roboto_flex, FontWeight.Bold)
)

// Secondary fallback reference to Android system Roboto
val RobotoFallbackFontFamily: FontFamily = FontFamily.SansSerif

val GoogleSansFontFamily = FontFamily(
    Font(R.font.google_sans, FontWeight.Normal),
    Font(R.font.google_sans, FontWeight.Medium),
    Font(R.font.google_sans, FontWeight.SemiBold),
    Font(R.font.google_sans, FontWeight.Bold)
)

val GoogleSansTextFontFamily = FontFamily(
    Font(R.font.google_sans_text, FontWeight.Normal),
    Font(R.font.google_sans_text, FontWeight.Medium),
    Font(R.font.google_sans_text, FontWeight.SemiBold),
    Font(R.font.google_sans_text, FontWeight.Bold)
)

// Digits & Timers: Roboto Flex with tabular figures ("tnum")
val TabularMonoStyle = TextStyle(
    fontFamily = RobotoFlexFontFamily,
    fontWeight = FontWeight.Normal,
    fontFeatureSettings = "tnum"
)

// Centralized Audio Recording Playback Screen Typography Scale (Material 3 "sp" scale)
val PlayerRecordingTitleStyle = TextStyle(
    fontFamily = RobotoFlexFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 30.sp,
    lineHeight = 36.sp
)

val PlayerMainTimerStyle = TextStyle(
    fontFamily = RobotoFlexFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 60.sp,
    lineHeight = 68.sp,
    letterSpacing = (-0.5).sp,
    fontFeatureSettings = "tnum"
)

val PlayerSecondaryInfoStyle = TextStyle(
    fontFamily = RobotoFlexFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp
)

val PlayerSecondaryTimerStyle = TextStyle(
    fontFamily = RobotoFlexFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    fontFeatureSettings = "tnum"
)

val PlayerButtonLabelStyle = TextStyle(
    fontFamily = RobotoFlexFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp
)

val PlayerSpeedLabelStyle = TextStyle(
    fontFamily = RobotoFlexFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    fontFeatureSettings = "tnum"
)

val PlayerSkipSilentLabelStyle = TextStyle(
    fontFamily = RobotoFlexFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp
)

val PlayerCompactSecondaryStyle = TextStyle(
    fontFamily = RobotoFlexFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontFeatureSettings = "tnum"
)

// Storage & Drive Save Location Option Label Style ("Device" & "Google Drive"): 13 sp, Medium (500), Roboto Flex
val StorageLocationOptionLabelStyle = TextStyle(
    fontFamily = RobotoFlexFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 16.sp
)

// Navigation Bar Active & Inactive Label Styles
val NavBarActiveLabelStyle = TextStyle(
    fontFamily = RobotoFlexFontFamily,
    fontWeight = FontWeight.W500,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.5.sp
)

val NavBarInactiveLabelStyle = TextStyle(
    fontFamily = RobotoFlexFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp
)

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum"
    ),
    displayMedium = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontFeatureSettings = "tnum"
    ),
    headlineLarge = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    )
)
