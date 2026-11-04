package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.AudioFormatOption
import com.example.data.PlaybackSpeedOption
import com.example.data.RecorderSettings
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.OnPeachAccent
import com.example.ui.theme.PeachAccent
import com.example.ui.theme.PrimaryText
import com.example.ui.theme.SecondaryText
import com.example.ui.theme.SuccessSage
import com.example.ui.theme.TabularMonoStyle
import com.example.ui.theme.TonalDarkBrown
import com.example.ui.theme.WarmSecondarySurface
import com.example.ui.theme.m3PressScale

@Composable
fun SettingsScreen(
    settings: RecorderSettings,
    onUpdateSettings: ((RecorderSettings) -> RecorderSettings) -> Unit,
    onToggleSmartSilenceMode: (Boolean) -> Unit,
    onToggleManualSilenceMode: (Boolean) -> Unit,
    onUpdateSilenceParams: (Float, Int, Int) -> Unit,
    onResetSilenceDefaults: () -> Unit,
    onCleanLocalCache: () -> Unit = {}
) {
    var showEncoderModal by remember { mutableStateOf(false) }
    var isManualAccordionExpanded by remember { mutableStateOf(false) }

    val chevronRotation by animateFloatAsState(
        targetValue = if (isManualAccordionExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 240),
        label = "manual_accordion_chevron_rotation"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .testTag("settings_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Header
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Studio Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Audio & Silence Settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 1. Recording & Encoder Compact Summary Card (Opens Modal Popup Dialog on Tap)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(WarmSecondarySurface)
                    .m3PressScale(
                        shape = RoundedCornerShape(16.dp),
                        onClick = { showEncoderModal = true }
                    )
                    .padding(horizontal = 16.dp, vertical = 18.dp)
                    .testTag("settings_recording_encoder_card")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(ElevatedSurface)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Recording & Encoder",
                            tint = PeachAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Recording & Encoder",
                            style = MaterialTheme.typography.titleMedium,
                            color = PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val channelLabel = when {
                            settings.bitDepth == 24 -> "24-bit"
                            settings.channels == 2 -> "Stereo"
                            else -> "Mono"
                        }
                        Text(
                            text = "${settings.defaultFormat.id} • ${settings.sampleRate / 1000.0} kHz • $channelLabel",
                            style = TabularMonoStyle.copy(fontSize = 11.sp),
                            color = PeachAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ElevatedSurface)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Open Recording & Encoder Popup",
                        tint = PrimaryText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 2. Playback & Silence Skipping Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(WarmSecondarySurface)
                    .padding(18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Playback & Silence Skipping",
                        tint = PeachAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Playback & Silence Skipping",
                        style = MaterialTheme.typography.titleMedium,
                        color = PrimaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                val smartAutoMode = settings.silenceConfig.smartAutoMode
                val manualModeEnabled = settings.silenceConfig.manualModeEnabled && !smartAutoMode
                val isManualLockedBySmart = smartAutoMode

                // Smart Silence Skip Toggle Row (Fixed height 64.dp and fixed padding to prevent layout shifts when toggling)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (smartAutoMode) PeachAccent.copy(alpha = 0.14f) else ObsidianBackground)
                        .padding(horizontal = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (smartAutoMode) PeachAccent else ElevatedSurface)
                        ) {
                            SkipSilenceCustomIcon(
                                tint = if (smartAutoMode) OnPeachAccent else SecondaryText,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Smart Silence Skip",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = PrimaryText,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Auto Skip Pauses",
                                style = MaterialTheme.typography.bodySmall,
                                color = SecondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Switch(
                        checked = smartAutoMode,
                        onCheckedChange = { onToggleSmartSilenceMode(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnPeachAccent,
                            checkedTrackColor = PeachAccent,
                            uncheckedThumbColor = SecondaryText,
                            uncheckedTrackColor = ElevatedSurface
                        ),
                        modifier = Modifier.testTag("toggle_smart_silence_mode")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Manual Silence Skipping Accordion Dropdown (Slide-down panel with rotating chevron)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (isManualLockedBySmart) 0.45f else 1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (manualModeEnabled) PeachAccent.copy(alpha = 0.12f) else ObsidianBackground
                        )
                ) {
                    // Accordion Header (Tappable to expand/collapse downward)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .m3PressScale(
                                shape = RoundedCornerShape(14.dp),
                                onClick = { isManualAccordionExpanded = !isManualAccordionExpanded }
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("manual_silence_accordion_header")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (manualModeEnabled) PeachAccent else ElevatedSurface)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Manual Silence Skip",
                                    tint = if (manualModeEnabled) OnPeachAccent else SecondaryText,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Manual Silence Skip",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = PrimaryText,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${settings.silenceConfig.thresholdDb.toInt()} dB • ${settings.silenceConfig.minSilenceDurationMs}ms • ${settings.silenceConfig.resumePaddingMs}ms",
                                    style = TabularMonoStyle.copy(fontSize = 11.sp),
                                    color = if (manualModeEnabled) PeachAccent else SecondaryText,
                                    maxLines = 1
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isManualAccordionExpanded) "Collapse manual silence settings" else "Expand manual silence settings",
                            tint = PrimaryText,
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(chevronRotation)
                        )
                    }

                    // Smooth Slide-Down Accordion Content
                    AnimatedVisibility(
                        visible = isManualAccordionExpanded,
                        enter = expandVertically(animationSpec = tween(240)) + fadeIn(animationSpec = tween(200)),
                        exit = shrinkVertically(animationSpec = tween(220)) + fadeOut(animationSpec = tween(160))
                    ) {
                        val areManualSlidersInteractive = !smartAutoMode
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                                .testTag("manual_silence_accordion_content")
                        ) {
                            // 1. Silence Threshold Slider (-60 dB to -15 dB)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(WarmSecondarySurface)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Silence Threshold",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = PrimaryText,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${settings.silenceConfig.thresholdDb.toInt()} dB",
                                        style = TabularMonoStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                        color = if (areManualSlidersInteractive) PeachAccent else SecondaryText,
                                        maxLines = 1
                                    )
                                }
                                Slider(
                                    value = settings.silenceConfig.thresholdDb,
                                    onValueChange = { newDb ->
                                        onUpdateSettings { current ->
                                            current.copy(
                                                silenceConfig = current.silenceConfig.copy(thresholdDb = newDb)
                                            )
                                        }
                                    },
                                    enabled = areManualSlidersInteractive,
                                    valueRange = -60f..-15f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = PeachAccent,
                                        activeTrackColor = PeachAccent,
                                        inactiveTrackColor = ElevatedSurface,
                                        disabledThumbColor = SecondaryText,
                                        disabledActiveTrackColor = SecondaryText.copy(alpha = 0.5f),
                                        disabledInactiveTrackColor = ElevatedSurface
                                    ),
                                    modifier = Modifier.testTag("slider_silence_threshold")
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 2. Min Silence Duration Slider (200 ms to 2000 ms)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(WarmSecondarySurface)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Min Silence Duration",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = PrimaryText,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${settings.silenceConfig.minSilenceDurationMs} ms",
                                        style = TabularMonoStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                        color = if (areManualSlidersInteractive) PeachAccent else SecondaryText,
                                        maxLines = 1
                                    )
                                }
                                Slider(
                                    value = settings.silenceConfig.minSilenceDurationMs.toFloat(),
                                    onValueChange = { newMs ->
                                        onUpdateSettings { current ->
                                            current.copy(
                                                silenceConfig = current.silenceConfig.copy(
                                                    minSilenceDurationMs = newMs.toInt()
                                                )
                                            )
                                        }
                                    },
                                    enabled = areManualSlidersInteractive,
                                    valueRange = 200f..2000f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = PeachAccent,
                                        activeTrackColor = PeachAccent,
                                        inactiveTrackColor = ElevatedSurface,
                                        disabledThumbColor = SecondaryText,
                                        disabledActiveTrackColor = SecondaryText.copy(alpha = 0.5f),
                                        disabledInactiveTrackColor = ElevatedSurface
                                    ),
                                    modifier = Modifier.testTag("slider_min_silence_duration")
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 3. Resume Buffer Slider (50 ms to 400 ms)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(WarmSecondarySurface)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Resume Buffer",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = PrimaryText,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${settings.silenceConfig.resumePaddingMs} ms",
                                        style = TabularMonoStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                        color = if (areManualSlidersInteractive) PeachAccent else SecondaryText,
                                        maxLines = 1
                                    )
                                }
                                Slider(
                                    value = settings.silenceConfig.resumePaddingMs.toFloat(),
                                    onValueChange = { newPad ->
                                        onUpdateSettings { current ->
                                            current.copy(
                                                silenceConfig = current.silenceConfig.copy(
                                                    resumePaddingMs = newPad.toInt()
                                                )
                                            )
                                        }
                                    },
                                    enabled = areManualSlidersInteractive,
                                    valueRange = 50f..400f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = PeachAccent,
                                        activeTrackColor = PeachAccent,
                                        inactiveTrackColor = ElevatedSurface,
                                        disabledThumbColor = SecondaryText,
                                        disabledActiveTrackColor = SecondaryText.copy(alpha = 0.5f),
                                        disabledInactiveTrackColor = ElevatedSurface
                                    ),
                                    modifier = Modifier.testTag("slider_resume_padding")
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Single-word Reset button on the left and single-word Enable/Disable button on the right
                            // Matching size, shape, and color styling
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = onResetSilenceDefaults,
                                    enabled = !isManualLockedBySmart,
                                    shape = CircleShape,
                                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TonalDarkBrown,
                                        contentColor = PrimaryText,
                                        disabledContainerColor = TonalDarkBrown.copy(alpha = 0.5f),
                                        disabledContentColor = PrimaryText.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                        .testTag("reset_silence_params_button")
                                ) {
                                    Text(
                                        text = "Reset",
                                        maxLines = 1
                                    )
                                }

                                Button(
                                    onClick = { onToggleManualSilenceMode(!manualModeEnabled) },
                                    enabled = !isManualLockedBySmart,
                                    shape = CircleShape,
                                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TonalDarkBrown,
                                        contentColor = PrimaryText,
                                        disabledContainerColor = TonalDarkBrown.copy(alpha = 0.5f),
                                        disabledContentColor = PrimaryText.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                        .testTag("toggle_manual_silence_mode")
                                ) {
                                    Text(
                                        text = if (manualModeEnabled) "Disable" else "Enable",
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Audio Isolation & Noise Control Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(WarmSecondarySurface)
                    .padding(18.dp)
                    .testTag("audio_isolation_card")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Audio Isolation & Noise Control",
                        tint = PeachAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Audio Isolation & Noise Control",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = PrimaryText,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Item 1: Acoustic Echo Cancellation
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (settings.echoCancellationEnabled) PeachAccent.copy(alpha = 0.14f) else ObsidianBackground)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Acoustic Echo Cancellation",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Speaker & mic echo",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Switch(
                        checked = settings.echoCancellationEnabled,
                        onCheckedChange = { isEnabled ->
                            onUpdateSettings { it.copy(echoCancellationEnabled = isEnabled) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnPeachAccent,
                            checkedTrackColor = PeachAccent,
                            uncheckedThumbColor = SecondaryText,
                            uncheckedTrackColor = ElevatedSurface
                        ),
                        modifier = Modifier.testTag("toggle_echo_cancellation")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Item 2: Noise Suppression (NS)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (settings.noiseSuppressionEnabled) PeachAccent.copy(alpha = 0.14f) else ObsidianBackground)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Noise Suppression (NS)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Fans & room noise",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Switch(
                        checked = settings.noiseSuppressionEnabled,
                        onCheckedChange = { isEnabled ->
                            onUpdateSettings { it.copy(noiseSuppressionEnabled = isEnabled) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnPeachAccent,
                            checkedTrackColor = PeachAccent,
                            uncheckedThumbColor = SecondaryText,
                            uncheckedTrackColor = ElevatedSurface
                        ),
                        modifier = Modifier.testTag("toggle_noise_suppression")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Item 3: Pause Media & Mute
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (settings.pauseExternalMediaOnRecord) PeachAccent.copy(alpha = 0.14f) else ObsidianBackground)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Pause Media & Mute",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Music & alert sounds",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Switch(
                        checked = settings.pauseExternalMediaOnRecord,
                        onCheckedChange = { isEnabled ->
                            onUpdateSettings { it.copy(pauseExternalMediaOnRecord = isEnabled) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnPeachAccent,
                            checkedTrackColor = PeachAccent,
                            uncheckedThumbColor = SecondaryText,
                            uncheckedTrackColor = ElevatedSurface
                        ),
                        modifier = Modifier.testTag("toggle_pause_external_media")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Item 4: Dynamic Island Capsule Overlay (System Overlay Permission Toggle)
                val settingsContext = androidx.compose.ui.platform.LocalContext.current
                var canDrawOverlayState by remember {
                    mutableStateOf(com.example.service.RecordingIslandOverlayManager.canDrawSystemOverlay(settingsContext))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (canDrawOverlayState) PeachAccent.copy(alpha = 0.14f) else ObsidianBackground)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dynamic Island Capsule",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (canDrawOverlayState) "Overlay active on Home Screen" else "Tap to grant overlay permission",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Switch(
                        checked = canDrawOverlayState,
                        onCheckedChange = {
                            com.example.service.RecordingIslandOverlayManager.launchOverlayPermissionSettings(settingsContext)
                            canDrawOverlayState = com.example.service.RecordingIslandOverlayManager.canDrawSystemOverlay(settingsContext)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnPeachAccent,
                            checkedTrackColor = PeachAccent,
                            uncheckedThumbColor = SecondaryText,
                            uncheckedTrackColor = ElevatedSurface
                        ),
                        modifier = Modifier.testTag("toggle_dynamic_island_overlay")
                    )
                }
            }
        }
    }

    // Independent Modal Popup / Dialog for "Recording & Encoder"
    if (showEncoderModal) {
        RecordingEncoderModalDialog(
            settings = settings,
            onUpdateSettings = onUpdateSettings,
            onDismiss = { showEncoderModal = false }
        )
    }
}

@Composable
private fun RecordingEncoderModalDialog(
    settings: RecorderSettings,
    onUpdateSettings: ((RecorderSettings) -> RecorderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(WarmSecondarySurface)
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .testTag("recording_encoder_modal_dialog")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Recording & Encoder",
                    tint = PeachAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Recording & Encoder",
                    style = MaterialTheme.typography.titleLarge,
                    color = PrimaryText,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Default Format
            Text(
                text = "Default Audio Format",
                style = MaterialTheme.typography.labelLarge,
                color = SecondaryText,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AudioFormatOption.entries.forEach { fmt ->
                    val selected = settings.defaultFormat == fmt
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(CircleShape)
                            .background(if (selected) PeachAccent else ElevatedSurface)
                            .m3PressScale(
                                shape = CircleShape,
                                onClick = {
                                    onUpdateSettings {
                                        it.copy(
                                            defaultFormat = fmt,
                                            bitrate = fmt.defaultBitrate
                                        )
                                    }
                                }
                            )
                            .testTag("settings_format_${fmt.id}")
                    ) {
                        Text(
                            text = fmt.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) OnPeachAccent else PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Sample Rate
            Text(
                text = "Sample Rate",
                style = MaterialTheme.typography.labelLarge,
                color = SecondaryText,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(16000 to "16 kHz", 22050 to "22.05 kHz", 44100 to "44.1 kHz", 48000 to "48 kHz").forEach { (sr, label) ->
                    val selected = settings.sampleRate == sr
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(CircleShape)
                            .background(if (selected) PeachAccent else ElevatedSurface)
                            .m3PressScale(
                                shape = CircleShape,
                                onClick = {
                                    onUpdateSettings { it.copy(sampleRate = sr) }
                                }
                            )
                            .testTag("settings_sr_$sr")
                    ) {
                        Text(
                            text = label,
                            style = TabularMonoStyle.copy(
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (selected) OnPeachAccent else PrimaryText,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Channels & Bit Depth
            Text(
                text = "Channels & Bit Depth",
                style = MaterialTheme.typography.labelLarge,
                color = SecondaryText,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    Triple(1, 16, "Mono 16-bit"),
                    Triple(2, 16, "Stereo 16-bit"),
                    Triple(2, 24, "Studio 24-bit")
                ).forEach { (ch, bd, label) ->
                    val selected = settings.channels == ch && settings.bitDepth == bd
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(CircleShape)
                            .background(if (selected) PeachAccent else ElevatedSurface)
                            .m3PressScale(
                                shape = CircleShape,
                                onClick = {
                                    onUpdateSettings { it.copy(channels = ch, bitDepth = bd) }
                                }
                            )
                            .padding(horizontal = 2.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 11.5.sp,
                                letterSpacing = (-0.3).sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (selected) OnPeachAccent else PrimaryText,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Internal Reset & OK Action Buttons (Matched to Export & Convert Audio sheet buttons)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        onUpdateSettings {
                            it.copy(
                                defaultFormat = AudioFormatOption.M4A,
                                sampleRate = 44100,
                                bitDepth = 16,
                                channels = 1,
                                bitrate = AudioFormatOption.M4A.defaultBitrate
                            )
                        }
                    },
                    shape = CircleShape,
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TonalDarkBrown,
                        contentColor = PrimaryText
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("encoder_modal_reset_button")
                ) {
                    Text(
                        text = "Reset",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                }

                Button(
                    onClick = onDismiss,
                    shape = CircleShape,
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TonalDarkBrown,
                        contentColor = PrimaryText
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("encoder_modal_ok_button")
                ) {
                    Text(
                        text = "OK",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
