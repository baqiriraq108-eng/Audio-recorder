package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Transport seek button (Rewind / Fast-Forward) supporting both:
 * 1. Quick tap: single discrete seek step (±3s)
 * 2. Continuous press-and-hold: "Cue & Review" audible scrubbing that plays intelligible audio
 *    snippets every ~220ms across progressively accelerating jumps until the finger is released.
 */
@Composable
fun HoldToSeekTransportButton(
    icon: ImageVector,
    contentDescription: String,
    directionSign: Int,
    containerColor: Color,
    iconTint: Color,
    buttonSize: Dp,
    cornerRadius: Dp,
    iconSize: Dp,
    testTagName: String,
    getCurrentPositionMs: () -> Long,
    minBoundMs: Long,
    maxBoundMs: Long,
    onSingleTapSeek: (deltaMs: Long) -> Unit,
    onScrubHoldStart: () -> Unit,
    onScrubCueSeekToMs: (targetPositionMs: Long) -> Unit,
    onScrubHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    singleTapStepMs: Long = 3_000L
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val coroutineScope = rememberCoroutineScope()
    var isHoldActive by remember { mutableStateOf(false) }

    val currentGetPositionMs by rememberUpdatedState(getCurrentPositionMs)
    val currentMinBoundMs by rememberUpdatedState(minBoundMs)
    val currentMaxBoundMs by rememberUpdatedState(maxBoundMs)
    val currentOnSingleTapSeek by rememberUpdatedState(onSingleTapSeek)
    val currentOnScrubHoldStart by rememberUpdatedState(onScrubHoldStart)
    val currentOnScrubCueSeekToMs by rememberUpdatedState(onScrubCueSeekToMs)
    val currentOnScrubHoldEnd by rememberUpdatedState(onScrubHoldEnd)

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "hold_seek_press_scale"
    )

    val sign = if (directionSign < 0) -1L else 1L

    DisposableEffect(Unit) {
        onDispose {
            if (isHoldActive) {
                isHoldActive = false
                currentOnScrubHoldEnd()
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(buttonSize)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(containerColor)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, borderColor, shape)
                } else {
                    Modifier
                }
            )
            .indication(interactionSource, ripple())
            .semantics {
                role = Role.Button
                onClick {
                    currentOnSingleTapSeek(sign * singleTapStepMs)
                    true
                }
            }
            .pointerInput(sign, singleTapStepMs) {
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interactionSource.emit(press)

                        var didContinuousSeek = false
                        val holdJob = coroutineScope.launch {
                            // Brief threshold distinguishing crisp quick taps from audible hold-scrubbing
                            delay(200L)
                            didContinuousSeek = true
                            isHoldActive = true

                            var scrubAnchorMs = currentGetPositionMs()
                            currentOnScrubHoldStart()

                            var heldMs = 0L
                            var isFirstHop = true
                            while (isActive) {
                                val hopStepMs = when {
                                    isFirstHop -> 1_000L
                                    heldMs < 1_320L -> 1_200L
                                    heldMs < 3_080L -> 2_200L
                                    else -> 3_500L
                                }
                                isFirstHop = false

                                val lower = currentMinBoundMs.coerceAtLeast(0L)
                                val upper = currentMaxBoundMs.coerceAtLeast(lower)
                                val safeForwardUpper = if (upper > lower + 250L) upper - 150L else upper
                                val maxAllowed = if (sign > 0L) safeForwardUpper else upper

                                scrubAnchorMs = (scrubAnchorMs + sign * hopStepMs)
                                    .coerceIn(lower, maxAllowed)
                                currentOnScrubCueSeekToMs(scrubAnchorMs)

                                delay(220L)
                                heldMs += 220L
                            }
                        }

                        var released = false
                        try {
                            released = tryAwaitRelease()
                        } finally {
                            holdJob.cancel()
                            if (didContinuousSeek) {
                                isHoldActive = false
                                currentOnScrubHoldEnd()
                            }
                            interactionSource.emit(
                                if (released) PressInteraction.Release(press)
                                else PressInteraction.Cancel(press)
                            )
                        }
                        if (released && !didContinuousSeek) {
                            currentOnSingleTapSeek(sign * singleTapStepMs)
                        }
                    }
                )
            }
            .testTag(testTagName)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(iconSize)
        )
    }
}
