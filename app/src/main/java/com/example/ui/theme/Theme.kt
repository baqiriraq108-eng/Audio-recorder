package com.example.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

object M3MotionTokens {
    val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EmphasizedDecelerateEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerateEasing: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    const val DurationTabSwitchMs = 300
    const val DurationDrillDownMs = 350
}

private val WarmAudioDarkColorScheme = darkColorScheme(
    primary = PeachAccent,
    onPrimary = OnPeachAccent,
    primaryContainer = PeachAccentContainer,
    onPrimaryContainer = PrimaryText,
    secondary = PeachAccent,
    onSecondary = OnPeachAccent,
    secondaryContainer = ElevatedSurface,
    onSecondaryContainer = PrimaryText,
    tertiary = SuccessSage,
    onTertiary = ObsidianBackground,
    tertiaryContainer = SuccessContainer,
    onTertiaryContainer = SuccessSage,
    background = ObsidianBackground,
    onBackground = PrimaryText,
    surface = WarmSecondarySurface,
    onSurface = PrimaryText,
    surfaceVariant = ElevatedSurface,
    onSurfaceVariant = SecondaryText,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = WarmSecondarySurface,
    surfaceContainer = ElevatedSurface,
    surfaceContainerHigh = ElevatedSurfaceHigh,
    surfaceContainerHighest = ElevatedSurfaceHigh,
    outline = OutlineWarm,
    outlineVariant = OutlineWarm,
    error = DestructiveCrimson,
    onError = ObsidianBackground,
    errorContainer = DestructiveContainer,
    onErrorContainer = DestructiveCrimson
)

/**
 * Press interaction modifier featuring subtle scale depression (0.97f on press) with instant spring recovery,
 * bounded strictly to the component's rounded shape.
 */
fun Modifier.m3PressScale(
    shape: Shape = RoundedCornerShape(12.dp),
    enabled: Boolean = true,
    pressScale: Float = 0.97f,
    role: Role? = Role.Button,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "m3_press_scale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .clickable(
            interactionSource = interactionSource,
            indication = androidx.compose.material3.ripple(),
            enabled = enabled,
            role = role,
            onClick = onClick
        )
}

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Ltr
    ) {
        MaterialTheme(
            colorScheme = WarmAudioDarkColorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
