package com.example.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.data.AudioFormatUtils
import com.example.data.SilenceInterval
import com.example.data.SilenceSkipConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos

class AudioStudioEngine(private val context: Context) {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Recording state flows
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _liveDb = MutableStateFlow(-60f)
    val liveDb: StateFlow<Float> = _liveDb.asStateFlow()

    private val _livePeaks = MutableStateFlow<List<Float>>(emptyList())
    val livePeaks: StateFlow<List<Float>> = _livePeaks.asStateFlow()

    // Active microphone / headset input hardware label
    private val _activeAudioDeviceName = MutableStateFlow("Phone Microphone")
    val activeAudioDeviceName: StateFlow<String> = _activeAudioDeviceName.asStateFlow()

    private var isBluetoothScoActive = false
    private val needsAudioRecordRebind = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var headsetBroadcastReceiver: BroadcastReceiver? = null
    private var isDeviceMonitorRegistered = false
    private var activeEchoCancellationEnabled: Boolean = true
    private var activeNoiseSuppressionEnabled: Boolean = true

    // Playback state flows
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _playbackDurationMs = MutableStateFlow(0L)
    val playbackDurationMs: StateFlow<Long> = _playbackDurationMs.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _silenceSkippedSavedMs = MutableStateFlow(0L)
    val silenceSkippedSavedMs: StateFlow<Long> = _silenceSkippedSavedMs.asStateFlow()

    private val _activeSilenceJumping = MutableStateFlow(false)
    val activeSilenceJumping: StateFlow<Boolean> = _activeSilenceJumping.asStateFlow()

    // Dynamic Silence Map Index for the currently loaded playback file
    private val _activeSilenceMap = MutableStateFlow<List<SilenceInterval>>(emptyList())
    val activeSilenceMap: StateFlow<List<SilenceInterval>> = _activeSilenceMap.asStateFlow()

    private var recordingJob: Job? = null
    private var pcmPlaybackJob: Job? = null
    private var playbackTickerJob: Job? = null
    @Volatile
    private var activeAudioRecord: AudioRecord? = null
    private var activeAudioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var activeAec: AcousticEchoCanceler? = null
    private var activeNs: NoiseSuppressor? = null
    private var activeFocusRequest: Any? = null
    private var currentSessionFile: File? = null
    private var currentSampleRate: Int = 44100
    private var currentChannels: Int = 1

    @Volatile
    private var activePlaybackPeaks: List<Float> = emptyList()

    @Volatile
    private var preindexedSilenceIntervals: List<SilenceInterval> = emptyList()

    @Volatile
    private var silenceSkipConfig: SilenceSkipConfig = SilenceSkipConfig()

    @Volatile
    private var isUsingPcmEngine: Boolean = false

    @Volatile
    private var hasAppliedCustomTrackSpeed: Boolean = false

    @Volatile
    private var hasAppliedCustomMediaPlayerSpeed: Boolean = false

    private val isRobolectricRuntime: Boolean =
        Build.FINGERPRINT.equals("robolectric", ignoreCase = true)

    private val pendingSeekMs = AtomicLong(-1L)
    private var lastMediaPlayerSeekUptimeMs: Long = 0L

    init {
        registerAudioDeviceMonitor()
        refreshConnectedAudioDevices()
    }

    /**
     * Real-time transport switch for "Skip Silence":
     * - Bound directly to the Voice Activity Detection (VAD) / Silence Trimming filter inside
     *   the active audio playback loop.
     * - Responds instantaneously in real time when toggled ON or OFF without pausing or reloading
     *   the audio track.
     * - Executes silence detection strictly according to the active Settings mode:
     *   Smart Adaptive VAD (`smartAutoMode = true`) vs. Manual fixed threshold (`manualModeEnabled = true`).
     * - When toggled ON: jumps over silent intervals at zero-crossing points with bit-exact 0 dB unity gain.
     * - When toggled OFF (or disabled in Settings): plays the complete chronological sequence
     *   preserving all original silent intervals.
     */
    fun updateSilenceSkipConfig(config: SilenceSkipConfig) {
        val previousConfig = silenceSkipConfig
        silenceSkipConfig = config

        val dur = _playbackDurationMs.value
        val file = currentSessionFile
        val parametersChanged =
            previousConfig.smartAutoMode != config.smartAutoMode ||
                previousConfig.manualModeEnabled != config.manualModeEnabled ||
                previousConfig.thresholdDb != config.thresholdDb ||
                previousConfig.minSilenceDurationMs != config.minSilenceDurationMs ||
                previousConfig.resumePaddingMs != config.resumePaddingMs

        if (dur > 0L) {
            if (parametersChanged || _activeSilenceMap.value.isEmpty()) {
                val quickMap = if (!parametersChanged && preindexedSilenceIntervals.isNotEmpty() && config.smartAutoMode) {
                    preindexedSilenceIntervals
                } else {
                    AudioFormatUtils.computeSilenceMapFromPeaks(activePlaybackPeaks, dur, config)
                }
                _activeSilenceMap.value = quickMap

                if (file != null && file.exists()) {
                    engineScope.launch(Dispatchers.IO) {
                        val refined = WavFileTools.generateSilenceMapIndex(
                            sourceFile = file,
                            durationMs = dur,
                            peaks = activePlaybackPeaks,
                            config = config,
                            fallbackSampleRate = currentSampleRate,
                            fallbackChannels = currentChannels
                        )
                        _activeSilenceMap.value = refined
                    }
                }
            }
        }

        if (!config.isEffectivelyEnabled) {
            _activeSilenceJumping.value = false
            _silenceSkippedSavedMs.value = 0L
        } else if (_isPlaying.value && !isUsingPcmEngine) {
            // For the fallback MediaPlayer path, trigger an immediate seek if inside a silent interval.
            val mp = mediaPlayer
            if (mp != null) {
                try {
                    if (mp.isPlaying) {
                        val pos = mp.currentPosition.toLong()
                        val totalDur = mp.duration.toLong().coerceAtLeast(1L)
                        val jumpTarget = resolveRealtimeSilenceJump(
                            currentPosMs = pos,
                            totalDurationMs = totalDur
                        )
                        if (jumpTarget != null && jumpTarget > pos + 60L) {
                            _activeSilenceJumping.value = true
                            lastMediaPlayerSeekUptimeMs = SystemClock.uptimeMillis()
                            mp.seekTo(jumpTarget.toInt())
                            _playbackPositionMs.value = jumpTarget
                            _silenceSkippedSavedMs.value += (jumpTarget - pos)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun requestRecordingAudioFocus(pauseExternalMedia: Boolean) {
        if (!pauseExternalMedia) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* Focus change listener */ }
                    .build()
                activeFocusRequest = focusRequest
                am.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        } catch (_: Exception) {}
    }

    private fun abandonRecordingAudioFocus() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = activeFocusRequest as? AudioFocusRequest
                if (request != null) {
                    am.abandonAudioFocusRequest(request)
                }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
        activeFocusRequest = null
    }

    private fun releaseAudioEffects() {
        try {
            activeAec?.enabled = false
            activeAec?.release()
        } catch (_: Exception) {}
        activeAec = null

        try {
            activeNs?.enabled = false
            activeNs?.release()
        } catch (_: Exception) {}
        activeNs = null
    }

    fun updateAudioIsolationConfig(
        echoCancellationEnabled: Boolean,
        noiseSuppressionEnabled: Boolean,
        pauseExternalMediaOnRecord: Boolean
    ) {
        if (!echoCancellationEnabled) {
            try {
                activeAec?.enabled = false
                activeAec?.release()
            } catch (_: Exception) {}
            activeAec = null
        }
        if (!noiseSuppressionEnabled) {
            try {
                activeNs?.enabled = false
                activeNs?.release()
            } catch (_: Exception) {}
            activeNs = null
        }
        if (!pauseExternalMediaOnRecord) {
            abandonRecordingAudioFocus()
        }
    }

    /**
     * Finds the highest-priority connected external or headset input device (Bluetooth SCO/BLE,
     * wired 3.5mm headset, or USB headset) to capture audio instead of the phone's built-in microphone.
     */
    @SuppressLint("MissingPermission")
    fun findPreferredHeadsetInputDevice(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            // Priority 1: Bluetooth SCO / BLE Headset from active input devices
            val bluetoothInput = inputDevices.firstOrNull { dev ->
                isBluetoothHeadsetDevice(dev)
            }
            if (bluetoothInput != null) return bluetoothInput

            // On Android 12+ (API 31+), a paired & connected Bluetooth HFP/BLE headset may appear in
            // availableCommunicationDevices before MODE_IN_COMMUNICATION activates its SCO input node.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val btCommDevice = try {
                    audioManager.availableCommunicationDevices.firstOrNull { dev ->
                        isBluetoothHeadsetDevice(dev)
                    }
                } catch (_: Exception) {
                    null
                }
                if (btCommDevice != null) return btCommDevice
            }

            // Priority 2: Wired 3.5mm Headset with microphone
            val wiredHeadset = inputDevices.firstOrNull { dev ->
                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
            if (wiredHeadset != null) return wiredHeadset

            // Priority 3: USB Headset / USB Audio Device
            val usbDevice = inputDevices.firstOrNull { dev ->
                dev.type == AudioDeviceInfo.TYPE_USB_HEADSET || dev.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }
            usbDevice
        } catch (_: Exception) {
            null
        }
    }

    private fun isBluetoothHeadsetDevice(device: AudioDeviceInfo?): Boolean {
        if (device == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun findBuiltInMicInputDevice(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatInputDeviceLabel(device: AudioDeviceInfo?): String {
        if (device == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "Phone Microphone"
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Headset"
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                "BLE Headset"
            } else {
                "External Headset"
            }
        }
    }

    /**
     * Refreshes the currently connected audio input devices and updates [activeAudioDeviceName].
     * If a recording session is active and the target hardware route changed, triggers a seamless
     * hardware rebind so recording continues on the newly connected headset or falls back to the
     * phone's built-in microphone on disconnect.
     */
    fun refreshConnectedAudioDevices() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val preferred = findPreferredHeadsetInputDevice(am)
        val newLabel = formatInputDeviceLabel(preferred)
        val previousLabel = _activeAudioDeviceName.value
        _activeAudioDeviceName.value = newLabel

        if (_isRecording.value) {
            val currentRecord = activeAudioRecord
            val routedId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try { currentRecord?.routedDevice?.id } catch (_: Exception) { null }
            } else null
            val targetId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                preferred?.id ?: findBuiltInMicInputDevice(am)?.id
            } else null

            if (previousLabel != newLabel || (targetId != null && routedId != null && targetId != routedId)) {
                applyHardwareInputRouting(am, preferred, currentRecord)
                needsAudioRecordRebind.set(true)
            }
        }
    }

    private fun registerAudioDeviceMonitor() {
        if (isDeviceMonitorRegistered) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        isDeviceMonitorRegistered = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    refreshConnectedAudioDevices()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    refreshConnectedAudioDevices()
                }
            }
            audioDeviceCallback = callback
            try {
                am.registerAudioDeviceCallback(callback, mainHandler)
            } catch (_: Exception) {}
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                    val state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR
                    )
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED && _isRecording.value) {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        if (audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val btInput = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                                .firstOrNull { isBluetoothHeadsetDevice(it) }
                            if (btInput != null) {
                                try {
                                    activeAudioRecord?.preferredDevice = btInput
                                } catch (_: Exception) {}
                            }
                        }
                    } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && _isRecording.value) {
                        refreshConnectedAudioDevices()
                    }
                } else {
                    refreshConnectedAudioDevices()
                }
            }
        }
        headsetBroadcastReceiver = receiver
        try {
            val filter = IntentFilter().apply {
                addAction(AudioManager.ACTION_HEADSET_PLUG)
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {}
    }

    fun releaseEngineResources() {
        releaseMicrophoneHardwareImmediately()
        stopPlayback()
        if (!isDeviceMonitorRegistered) return
        isDeviceMonitorRegistered = false
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try {
                am?.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (_: Exception) {}
        }
        audioDeviceCallback = null
        headsetBroadcastReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
        headsetBroadcastReceiver = null
    }

    /**
     * Explicitly configures OS audio routing and AudioRecord preferred input device:
     * - For Bluetooth Headsets (SCO / BLE): switches AudioManager to MODE_IN_COMMUNICATION,
     *   calls setCommunicationDevice on Android 12+ (API 31+) and starts Bluetooth SCO link,
     *   then binds AudioRecord.preferredDevice to the Bluetooth headset input node.
     * - For Wired / USB Headsets: clears any stale Bluetooth SCO state, uses MODE_NORMAL,
     *   and binds AudioRecord.preferredDevice directly to the wired/USB headset input node.
     * - For Phone Microphone fallback (on headset disconnect or when no headset is present):
     *   clears communication device/SCO, restores MODE_NORMAL, and binds AudioRecord.preferredDevice
     *   to TYPE_BUILTIN_MIC.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun applyHardwareInputRouting(
        audioManager: AudioManager?,
        preferredHeadset: AudioDeviceInfo?,
        audioRecord: AudioRecord?
    ) {
        if (audioManager == null) return
        try {
            if (preferredHeadset != null && isBluetoothHeadsetDevice(preferredHeadset)) {
                try {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                } catch (_: Exception) {}

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        val commDevice = audioManager.availableCommunicationDevices.firstOrNull { dev ->
                            dev.id == preferredHeadset.id || isBluetoothHeadsetDevice(dev)
                        }
                        if (commDevice != null) {
                            audioManager.setCommunicationDevice(commDevice)
                        }
                    } catch (_: Exception) {}
                }

                if (preferredHeadset.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    try {
                        if (!audioManager.isBluetoothScoOn) {
                            audioManager.startBluetoothSco()
                            audioManager.isBluetoothScoOn = true
                        }
                        isBluetoothScoActive = true
                    } catch (_: Exception) {}
                }

                if (audioRecord != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val activeBtInput = try {
                        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                            .firstOrNull { isBluetoothHeadsetDevice(it) }
                    } catch (_: Exception) {
                        null
                    } ?: preferredHeadset
                    try {
                        audioRecord.preferredDevice = activeBtInput
                    } catch (_: Exception) {}
                }
            } else if (preferredHeadset != null) {
                // Wired 3.5mm headset or USB headset
                clearBluetoothCommunicationRouting(audioManager)
                if (audioRecord != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        audioRecord.preferredDevice = preferredHeadset
                    } catch (_: Exception) {}
                }
            } else {
                // Fallback to built-in phone microphone
                clearBluetoothCommunicationRouting(audioManager)
                if (audioRecord != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val builtInMic = findBuiltInMicInputDevice(audioManager)
                    try {
                        audioRecord.preferredDevice = builtInMic
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun clearBluetoothCommunicationRouting(audioManager: AudioManager?) {
        if (audioManager == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (_: Exception) {}
        }
        if (isBluetoothScoActive || try { audioManager.isBluetoothScoOn } catch (_: Exception) { false }) {
            try {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            } catch (_: Exception) {}
            isBluetoothScoActive = false
        }
        try {
            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) {}
    }

    /**
     * Selects the optimal AudioSource list for the target input hardware:
     * - Bluetooth Headset (SCO / BLE): VOICE_COMMUNICATION is required by Android Audio HALs
     *   to route capture through the Bluetooth HFP/BLE voice uplink instead of the internal mic.
     * - Wired / USB Headset: MIC followed by VOICE_COMMUNICATION avoids far-field internal mic overrides.
     * - Built-in Phone Microphone: uses VOICE_RECOGNITION when AEC/NS are enabled, or raw MIC when off.
     */
    private fun resolvePrioritizedAudioSources(
        preferredHeadset: AudioDeviceInfo?,
        echoCancellationEnabled: Boolean,
        noiseSuppressionEnabled: Boolean
    ): List<Int> {
        return when {
            isBluetoothHeadsetDevice(preferredHeadset) -> listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )
            preferredHeadset != null -> listOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.DEFAULT
            )
            !echoCancellationEnabled && !noiseSuppressionEnabled -> listOf(
                MediaRecorder.AudioSource.MIC
            )
            else -> listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAndStartAudioRecord(
        audioManager: AudioManager?,
        preferredHeadset: AudioDeviceInfo?,
        effectiveSampleRate: Int,
        channelConfig: Int,
        minBufSize: Int,
        echoCancellationEnabled: Boolean,
        noiseSuppressionEnabled: Boolean
    ): AudioRecord? {
        applyHardwareInputRouting(audioManager, preferredHeadset, null)

        val audioSources = resolvePrioritizedAudioSources(
            preferredHeadset = preferredHeadset,
            echoCancellationEnabled = echoCancellationEnabled,
            noiseSuppressionEnabled = noiseSuppressionEnabled
        )

        var audioRecord: AudioRecord? = null
        for (source in audioSources) {
            try {
                val candidate = AudioRecord(
                    source,
                    effectiveSampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufSize * 2
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = candidate
                    break
                } else {
                    candidate.release()
                }
            } catch (_: Exception) {}
        }

        if (audioRecord != null && audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            activeAudioRecord = audioRecord
            applyHardwareInputRouting(audioManager, preferredHeadset, audioRecord)

            // Acoustic Echo Cancellation (AEC): enable only when toggle is ON; explicitly disable if OFF
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    val aec = AcousticEchoCanceler.create(audioRecord.audioSessionId)
                    if (echoCancellationEnabled) {
                        aec?.enabled = true
                        activeAec = aec
                    } else {
                        aec?.enabled = false
                        aec?.release()
                        activeAec = null
                    }
                } catch (_: Exception) {}
            }
            // Noise Suppression (NS): enable only when toggle is ON; explicitly disable if OFF
            if (NoiseSuppressor.isAvailable()) {
                try {
                    val ns = NoiseSuppressor.create(audioRecord.audioSessionId)
                    if (noiseSuppressionEnabled) {
                        ns?.enabled = true
                        activeNs = ns
                    } else {
                        ns?.enabled = false
                        ns?.release()
                        activeNs = null
                    }
                } catch (_: Exception) {}
            }

            try {
                audioRecord.startRecording()
            } catch (_: Exception) {}

            // Re-assert preferredDevice immediately after startRecording() so OEM HALs lock onto the headset
            applyHardwareInputRouting(audioManager, preferredHeadset, audioRecord)
            return audioRecord
        } else {
            activeAudioRecord = null
            return null
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecordingSession(
        outputFile: File,
        sampleRate: Int = 44100,
        channels: Int = 1,
        initialPeaks: List<Float> = emptyList(),
        echoCancellationEnabled: Boolean = true,
        noiseSuppressionEnabled: Boolean = true,
        pauseExternalMediaOnRecord: Boolean = true,
        baseM4aForAppend: File? = null
    ) {
        stopPlayback()
        recordingJob?.cancel()
        releaseAudioEffects()
        abandonRecordingAudioFocus()

        activeEchoCancellationEnabled = echoCancellationEnabled
        activeNoiseSuppressionEnabled = noiseSuppressionEnabled
        needsAudioRecordRebind.set(false)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val initialPreferredHeadset = audioManager?.let { findPreferredHeadsetInputDevice(it) }
        _activeAudioDeviceName.value = formatInputDeviceLabel(initialPreferredHeadset)

        // If appending to an existing M4A file, parse its esds/mdhd/stsd before encoding
        // so AudioRecord and MediaCodec match its exact sampleRate, channelCount, and PTS timeline.
        val baseM4aMeta = if (baseM4aForAppend != null && baseM4aForAppend.exists()) {
            Mp4BoxParser.parseM4a(baseM4aForAppend)
        } else null

        val canMatchBaseM4a = baseM4aMeta != null &&
            AacEncoder.canEncodeWithParameters(
                sampleRate = baseM4aMeta.sampleRate,
                channelCount = baseM4aMeta.channelCount,
                aacObjectType = baseM4aMeta.aacObjectType
            )

        val effectiveSampleRate = if (canMatchBaseM4a && baseM4aMeta != null) {
            baseM4aMeta.sampleRate
        } else {
            sampleRate
        }
        val effectiveChannels = if (canMatchBaseM4a && baseM4aMeta != null) {
            baseM4aMeta.channelCount
        } else {
            channels
        }

        currentSessionFile = outputFile
        currentSampleRate = effectiveSampleRate
        currentChannels = effectiveChannels
        _elapsedMs.value = 0L
        _livePeaks.value = initialPeaks
        _isPaused.value = false
        _isRecording.value = true

        if (pauseExternalMediaOnRecord) {
            requestRecordingAudioFocus(true)
        }

        recordingJob = engineScope.launch(Dispatchers.IO) {
            outputFile.parentFile?.mkdirs()
            val channelConfig = if (effectiveChannels == 2) {
                AudioFormat.CHANNEL_IN_STEREO
            } else {
                AudioFormat.CHANNEL_IN_MONO
            }
            val minBufSize = AudioRecord.getMinBufferSize(
                effectiveSampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            var audioRecord: AudioRecord? = createAndStartAudioRecord(
                audioManager = audioManager,
                preferredHeadset = initialPreferredHeadset,
                effectiveSampleRate = effectiveSampleRate,
                channelConfig = channelConfig,
                minBufSize = minBufSize,
                echoCancellationEnabled = echoCancellationEnabled,
                noiseSuppressionEnabled = noiseSuppressionEnabled
            )

            suspend fun rebindAudioRecordSeamlessly(): AudioRecord? {
                releaseAudioEffects()
                try {
                    audioRecord?.stop()
                } catch (_: Exception) {}
                try {
                    audioRecord?.release()
                } catch (_: Exception) {}
                activeAudioRecord = null
                val latestPreferred = audioManager?.let { findPreferredHeadsetInputDevice(it) }
                _activeAudioDeviceName.value = formatInputDeviceLabel(latestPreferred)
                return createAndStartAudioRecord(
                    audioManager = audioManager,
                    preferredHeadset = latestPreferred,
                    effectiveSampleRate = effectiveSampleRate,
                    channelConfig = channelConfig,
                    minBufSize = minBufSize,
                    echoCancellationEnabled = activeEchoCancellationEnabled,
                    noiseSuppressionEnabled = activeNoiseSuppressionEnabled
                )
            }

            val isAacSession = outputFile.extension.equals("m4a", ignoreCase = true) ||
                outputFile.extension.equals("aac", ignoreCase = true)
            val rawShorts = ((effectiveSampleRate * effectiveChannels) / 20).coerceAtLeast(1024) // ~50ms frame
            val chunkShorts = rawShorts - (rawShorts % effectiveChannels.coerceAtLeast(1))
            val shortBuffer = ShortArray(chunkShorts)
            val byteBuffer = ByteBuffer.allocate(chunkShorts * 2).order(ByteOrder.LITTLE_ENDIAN)
            var totalBytesWritten = 0L
            var consecutiveReadFailures = 0

            try {
                if (isAacSession) {
                    val rawAacTemp = File(outputFile.parentFile, "${outputFile.name}.raw.aac")
                    val aacEncoder = AacEncoder(
                        sampleRate = effectiveSampleRate,
                        channelCount = effectiveChannels,
                        bitrate = 128_000,
                        baseStartSamples = baseM4aMeta?.totalDurationSamples ?: 0L,
                        skipPrimingFrame = baseM4aForAppend != null
                    )
                    aacEncoder.start(rawAacTemp)

                    try {
                        while (isActive && _isRecording.value) {
                            if (_isPaused.value) {
                                _liveDb.value = -58f
                                delay(60L)
                                continue
                            }

                            if (needsAudioRecordRebind.getAndSet(false)) {
                                audioRecord = rebindAudioRecordSeamlessly()
                                consecutiveReadFailures = 0
                            }

                            var readSamples = 0
                            try {
                                if (audioRecord != null && audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                    readSamples = audioRecord.read(shortBuffer, 0, chunkShorts)
                                }
                            } catch (_: Exception) {
                                readSamples = -1
                            }

                            if (readSamples <= 0) {
                                if (!_isRecording.value && totalBytesWritten > 0L) break
                                consecutiveReadFailures++
                                if (!isRobolectricRuntime && (readSamples < 0 || consecutiveReadFailures >= 6)) {
                                    audioRecord = rebindAudioRecordSeamlessly()
                                    consecutiveReadFailures = 0
                                }
                                readSamples = chunkShorts
                                shortBuffer.fill(0)
                                delay(48L)
                            } else {
                                consecutiveReadFailures = 0
                            }

                            aacEncoder.encodePcmSamples(shortBuffer, readSamples)
                            totalBytesWritten += (readSamples * 2)

                            val (db, normPeak) = WavFileTools.calculateRmsAndPeak(shortBuffer, readSamples)
                            _liveDb.value = db
                            val updatedPeaks = (_livePeaks.value + normPeak).takeLast(140)
                            _livePeaks.value = updatedPeaks

                            val bytesPerSecond = (effectiveSampleRate.toLong() * effectiveChannels.toLong() * 2L).coerceAtLeast(1L)
                            _elapsedMs.value = (totalBytesWritten * 1000L) / bytesPerSecond
                        }
                    } finally {
                        val segment = aacEncoder.finish()
                        aacEncoder.writeStandaloneM4aFile(segment, outputFile)
                        if (rawAacTemp.exists()) rawAacTemp.delete()
                    }
                } else {
                    FileOutputStream(outputFile).use { fos ->
                        // Placeholder 44-byte WAV header, updated on completion
                        WavFileTools.writeWavHeader(fos, 0L, effectiveSampleRate, effectiveChannels, 16)

                        while (isActive && _isRecording.value) {
                            if (_isPaused.value) {
                                _liveDb.value = -58f
                                delay(60L)
                                continue
                            }

                            if (needsAudioRecordRebind.getAndSet(false)) {
                                audioRecord = rebindAudioRecordSeamlessly()
                                consecutiveReadFailures = 0
                            }

                            var readSamples = 0
                            try {
                                if (audioRecord != null && audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                    readSamples = audioRecord.read(shortBuffer, 0, chunkShorts)
                                }
                            } catch (_: Exception) {
                                readSamples = -1
                            }

                            if (readSamples <= 0) {
                                if (!_isRecording.value && totalBytesWritten > 0L) break
                                consecutiveReadFailures++
                                if (!isRobolectricRuntime && (readSamples < 0 || consecutiveReadFailures >= 6)) {
                                    audioRecord = rebindAudioRecordSeamlessly()
                                    consecutiveReadFailures = 0
                                }
                                readSamples = chunkShorts
                                shortBuffer.fill(0)
                                delay(48L)
                            } else {
                                consecutiveReadFailures = 0
                            }

                            // Write PCM bytes to disk untouched (100% original audio preserved — never cut)
                            byteBuffer.clear()
                            for (i in 0 until readSamples) {
                                byteBuffer.putShort(shortBuffer[i])
                            }
                            val bytesCount = readSamples * 2
                            fos.write(byteBuffer.array(), 0, bytesCount)
                            totalBytesWritten += bytesCount

                            // Compute RMS dB & peak (read-only)
                            val (db, normPeak) = WavFileTools.calculateRmsAndPeak(shortBuffer, readSamples)
                            _liveDb.value = db
                            val updatedPeaks = (_livePeaks.value + normPeak).takeLast(140)
                            _livePeaks.value = updatedPeaks

                            val bytesPerSecond = (effectiveSampleRate.toLong() * effectiveChannels.toLong() * 2L).coerceAtLeast(1L)
                            _elapsedMs.value = (totalBytesWritten * 1000L) / bytesPerSecond
                        }
                        fos.flush()
                    }
                    WavFileTools.updateWavHeader(outputFile, effectiveSampleRate, effectiveChannels, 16)
                }
            } catch (_: Exception) {
                if (!isAacSession) {
                    WavFileTools.updateWavHeader(outputFile, effectiveSampleRate, effectiveChannels, 16)
                }
            } finally {
                try {
                    audioRecord?.stop()
                } catch (_: Exception) {}
                try {
                    audioRecord?.release()
                } catch (_: Exception) {}
                activeAudioRecord = null
            }
        }
    }

    /**
     * Instantly stops and frees physical microphone hardware (AudioRecord.release())
     * to immediately drop Android OS microphone indicators with zero delay.
     */
    fun releaseMicrophoneHardwareImmediately() {
        releaseAudioEffects()
        abandonRecordingAudioFocus()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        clearBluetoothCommunicationRouting(am)
        val preferred = am?.let { findPreferredHeadsetInputDevice(it) }
        _activeAudioDeviceName.value = formatInputDeviceLabel(preferred)
        try {
            val record = activeAudioRecord
            activeAudioRecord = null
            if (record != null) {
                try {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                } catch (_: Exception) {}
                try {
                    record.release()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun pauseRecording() {
        if (_isRecording.value) {
            _isPaused.value = true
        }
    }

    fun resumeRecording() {
        if (_isRecording.value) {
            _isPaused.value = false
        }
    }

    suspend fun stopRecordingAndFinalize(): Pair<File?, Long> {
        _isRecording.value = false
        _isPaused.value = false
        // Instantly release microphone hardware so the OS microphone indicator drops with 0ms delay
        releaseMicrophoneHardwareImmediately()
        recordingJob?.join()
        _liveDb.value = -60f
        val file = currentSessionFile
        val duration = _elapsedMs.value
        _elapsedMs.value = 0L
        return file to duration
    }

    fun resetSessionDisplayState() {
        currentSessionFile = null
        _elapsedMs.value = 0L
        _liveDb.value = -60f
        _livePeaks.value = emptyList()
    }

    fun cancelAndResetRecording() {
        _isRecording.value = false
        _isPaused.value = false
        // Instantly release microphone hardware so the OS microphone indicator drops with 0ms delay
        releaseMicrophoneHardwareImmediately()
        recordingJob?.cancel()
        currentSessionFile?.delete()
        resetSessionDisplayState()
    }

    // --- Playback & Real-Time VAD Silence Trimming Transport ---

    fun loadAndPlayFile(
        file: File,
        peaks: List<Float>,
        startPositionMs: Long = 0L,
        speed: Float = _playbackSpeed.value,
        silenceConfig: SilenceSkipConfig = this.silenceSkipConfig,
        preindexedSilenceMap: List<SilenceInterval> = emptyList()
    ) {
        if (!file.exists()) return
        stopPlaybackInternal(resetSavedCounter = startPositionMs == 0L)

        val repairedFile = WavFileTools.repairCorruptedAudioFileIfNeeded(file)
        currentSessionFile = repairedFile
        this.silenceSkipConfig = silenceConfig
        this._playbackSpeed.value = speed
        this.activePlaybackPeaks = if (peaks.size >= 8) peaks else WavFileTools.extractWaveformFromFile(repairedFile)
        this.preindexedSilenceIntervals = preindexedSilenceMap

        if (WavFileTools.isRiffWaveFile(repairedFile)) {
            isUsingPcmEngine = true
            startPcmPlaybackLoop(
                file = repairedFile,
                startPositionMs = startPositionMs,
                preindexedSilenceMap = preindexedSilenceMap
            )
        } else {
            isUsingPcmEngine = false
            startMediaPlayerFallback(
                file = repairedFile,
                startPositionMs = startPositionMs,
                speed = speed,
                silenceConfig = silenceConfig,
                preindexedSilenceMap = preindexedSilenceMap
            )
        }
    }

    private fun startPcmPlaybackLoop(
        file: File,
        startPositionMs: Long,
        preindexedSilenceMap: List<SilenceInterval>
    ) {
        val header = WavFileTools.readWavHeaderInfo(
            file = file,
            fallbackSampleRate = currentSampleRate,
            fallbackChannels = currentChannels,
            fallbackBitsPerSample = 16
        )
        val sampleRate = header.sampleRate.coerceIn(8000, 96000)
        val channels = header.channels.coerceIn(1, 2)
        val bitsPerSample = header.bitsPerSample
        val bytesPerSample = header.bytesPerSample
        val blockAlign = header.blockAlign.coerceAtLeast(2)
        val bytesPerSecond = (sampleRate.toLong() * blockAlign.toLong()).coerceAtLeast(1L)
        val totalPcmBytes = header.pcmDataBytes.coerceAtLeast(0L)
        val totalDurationMs = ((totalPcmBytes * 1000L) / bytesPerSecond).coerceAtLeast(200L)

        _playbackDurationMs.value = totalDurationMs
        val clampedStartMs = if (startPositionMs in 1 until totalDurationMs) startPositionMs else 0L
        _playbackPositionMs.value = clampedStartMs
        pendingSeekMs.set(-1L)
        _isPlaying.value = true

        // Seed initial Silence Map using the vocal-aware dual-threshold hysteresis peak analyzer
        // while the 20ms PCM vocal-band indexer runs in the background.
        val initialMap = AudioFormatUtils.computeSilenceMapFromPeaks(
            peaks = activePlaybackPeaks,
            durationMs = totalDurationMs,
            config = silenceSkipConfig
        ).ifEmpty {
            if (silenceSkipConfig.smartAutoMode) preindexedSilenceMap else emptyList()
        }
        _activeSilenceMap.value = initialMap

        pcmPlaybackJob = engineScope.launch(Dispatchers.IO) {
            if (totalDurationMs <= 15_000L) {
                val refinedMap = WavFileTools.generateSilenceMapIndex(
                    sourceFile = file,
                    durationMs = totalDurationMs,
                    peaks = activePlaybackPeaks,
                    config = silenceSkipConfig,
                    fallbackSampleRate = sampleRate,
                    fallbackChannels = channels,
                    fallbackBitsPerSample = bitsPerSample
                )
                _activeSilenceMap.value = refinedMap
            } else {
                engineScope.launch(Dispatchers.IO) {
                    val refinedMap = WavFileTools.generateSilenceMapIndex(
                        sourceFile = file,
                        durationMs = totalDurationMs,
                        peaks = activePlaybackPeaks,
                        config = silenceSkipConfig,
                        fallbackSampleRate = sampleRate,
                        fallbackChannels = channels,
                        fallbackBitsPerSample = bitsPerSample
                    )
                    _activeSilenceMap.value = refinedMap
                }
            }

            val channelOutConfig = if (channels == 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }
            val minTrackBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                channelOutConfig,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val targetTrackBufferBytes = maxOf(minTrackBuf * 4, ((sampleRate * blockAlign) / 4).coerceAtLeast(8192))

            var audioTrack: AudioTrack? = null
            var trackReady = false
            hasAppliedCustomTrackSpeed = false
            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelOutConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(targetTrackBufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    // Enforce strict flat unity gain (0 dB / 1.0f) — zero volume boost or compression
                    audioTrack.setVolume(1.0f)
                    val initialSpeed = _playbackSpeed.value
                    if (kotlin.math.abs(initialSpeed - 1.0f) > 0.01f) {
                        applyAudioTrackSpeed(audioTrack, initialSpeed)
                    }
                    audioTrack.play()
                    trackReady = true
                }
            } catch (_: Exception) {
                audioTrack = null
                trackReady = false
            }
            activeAudioTrack = audioTrack

            if (!trackReady || audioTrack == null) {
                withContext(Dispatchers.Main) {
                    isUsingPcmEngine = false
                    startMediaPlayerFallback(
                        file = file,
                        startPositionMs = clampedStartMs,
                        speed = _playbackSpeed.value,
                        silenceConfig = silenceSkipConfig,
                        preindexedSilenceMap = preindexedSilenceMap
                    )
                }
                return@launch
            }

            val pcmStartOffset = WavFileTools.locatePcmDataOffset(file)
            val frameMs = 20L
            val framesPerChunk = ((sampleRate * frameMs) / 1000L).toInt().coerceAtLeast(64)
            val chunkBytes = (framesPerChunk * blockAlign).coerceAtLeast(blockAlign)
            val chunkBuffer = ByteArray(chunkBytes)

            var jumpingBadgeHoldFrames = 0

            try {
                RandomAccessFile(file, "r").use { raf ->
                    var currentByteOffset = alignToBlock(
                        rawOffset = (clampedStartMs * bytesPerSecond) / 1000L,
                        blockAlign = blockAlign,
                        maxBytes = totalPcmBytes
                    )

                    while (isActive && _isPlaying.value) {
                        // 1. Check for interactive user seek (scrubbing waveform or +/-3s)
                        val requestedSeekMs = pendingSeekMs.getAndSet(-1L)
                        if (requestedSeekMs >= 0L) {
                            val rawSeekOffset = alignToBlock(
                                rawOffset = (requestedSeekMs.coerceIn(0L, totalDurationMs) * bytesPerSecond) / 1000L,
                                blockAlign = blockAlign,
                                maxBytes = totalPcmBytes
                            )
                            currentByteOffset = WavFileTools.findNearestZeroCrossingByteOffset(
                                raf = raf,
                                targetByteOffset = rawSeekOffset,
                                totalPcmBytes = totalPcmBytes,
                                blockAlign = blockAlign,
                                bytesPerSample = bytesPerSample,
                                sampleRate = sampleRate,
                                pcmStartOffset = pcmStartOffset
                            )
                            _playbackPositionMs.value = requestedSeekMs.coerceIn(0L, totalDurationMs)
                        }

                        if (currentByteOffset >= totalPcmBytes) {
                            _playbackPositionMs.value = totalDurationMs
                            _isPlaying.value = false
                            _activeSilenceJumping.value = false
                            break
                        }

                        val currentPosMs = ((currentByteOffset * 1000L) / bytesPerSecond)
                            .coerceIn(0L, totalDurationMs)
                        _playbackPositionMs.value = currentPosMs

                        val currentConfig = silenceSkipConfig
                        var jumpedThisIteration = false

                        // 2. Real-Time Silence Trimming Filter (when Skip Silence is ON and enabled in Settings)
                        if (currentConfig.isEffectivelyEnabled && totalDurationMs > 400L) {
                            val jumpTargetMs = resolveRealtimeSilenceJump(
                                currentPosMs = currentPosMs,
                                totalDurationMs = totalDurationMs
                            )

                            if (jumpTargetMs != null && jumpTargetMs > currentPosMs + 60L) {
                                val rawTargetByteOffset = alignToBlock(
                                    rawOffset = (jumpTargetMs * bytesPerSecond) / 1000L,
                                    blockAlign = blockAlign,
                                    maxBytes = totalPcmBytes
                                )
                                if (rawTargetByteOffset > currentByteOffset + blockAlign) {
                                    // Snap outgoing boundary to nearest forward zero-crossing (if within current chunk)
                                    // and play those bit-exact unity samples so the waveform exits at 0 amplitude
                                    val outgoingZeroOffset = WavFileTools.findNearestZeroCrossingByteOffset(
                                        raf = raf,
                                        targetByteOffset = currentByteOffset,
                                        totalPcmBytes = totalPcmBytes,
                                        blockAlign = blockAlign,
                                        bytesPerSample = bytesPerSample,
                                        sampleRate = sampleRate,
                                        pcmStartOffset = pcmStartOffset
                                    )
                                    val preJumpLeadBytes = (outgoingZeroOffset - currentByteOffset).toInt()
                                    if (preJumpLeadBytes in blockAlign..chunkBytes) {
                                        raf.seek(pcmStartOffset + currentByteOffset)
                                        val leadRead = readAlignedBytes(raf, chunkBuffer, preJumpLeadBytes, blockAlign)
                                        if (leadRead > 0) {
                                            val leadPcm16 = if (bytesPerSample == 2) {
                                                chunkBuffer
                                            } else {
                                                convertPcmWindowTo16Bit(chunkBuffer, leadRead, bytesPerSample)
                                            }
                                            val leadCount = if (bytesPerSample == 2) leadRead else leadPcm16.size
                                            writePcmToTrackOrPace(
                                                audioTrack = audioTrack,
                                                trackReady = trackReady,
                                                buffer = leadPcm16,
                                                byteCount = leadCount,
                                                bytesPerSecond = (sampleRate.toLong() * channels.toLong() * 2L).coerceAtLeast(1L)
                                            )
                                        }
                                    }

                                    // Snap incoming target strictly to a zero-crossing point with 100% bit-exact 0 dB unity samples
                                    val snappedTargetByteOffset = WavFileTools.findNearestZeroCrossingByteOffset(
                                        raf = raf,
                                        targetByteOffset = rawTargetByteOffset,
                                        totalPcmBytes = totalPcmBytes,
                                        blockAlign = blockAlign,
                                        bytesPerSample = bytesPerSample,
                                        sampleRate = sampleRate,
                                        pcmStartOffset = pcmStartOffset
                                    ).coerceIn(currentByteOffset + blockAlign, totalPcmBytes)

                                    currentByteOffset = snappedTargetByteOffset
                                    val actualLandedMs = ((currentByteOffset * 1000L) / bytesPerSecond)
                                        .coerceIn(0L, totalDurationMs)
                                    val savedDeltaMs = (actualLandedMs - currentPosMs).coerceAtLeast(0L)
                                    _silenceSkippedSavedMs.value += savedDeltaMs
                                    _playbackPositionMs.value = actualLandedMs
                                    _activeSilenceJumping.value = true
                                    jumpingBadgeHoldFrames = 6
                                    jumpedThisIteration = true
                                }
                            }
                        } else {
                            _activeSilenceJumping.value = false
                            jumpingBadgeHoldFrames = 0
                        }

                        if (jumpedThisIteration) {
                            continue
                        }

                        if (jumpingBadgeHoldFrames > 0) {
                            jumpingBadgeHoldFrames--
                            if (jumpingBadgeHoldFrames == 0) {
                                _activeSilenceJumping.value = false
                            }
                        }

                        // 3. Bit-exact sequential frame playback at flat 0 dB (1.0x) unity gain
                        raf.seek(pcmStartOffset + currentByteOffset)
                        val bytesToRead = minOf(chunkBytes.toLong(), totalPcmBytes - currentByteOffset).toInt()
                        val alignedRead = readAlignedBytes(raf, chunkBuffer, bytesToRead, blockAlign)
                        if (alignedRead <= 0) {
                            _playbackPositionMs.value = totalDurationMs
                            _isPlaying.value = false
                            _activeSilenceJumping.value = false
                            break
                        }

                        val pcm16Buffer = if (bytesPerSample == 2) {
                            chunkBuffer
                        } else {
                            convertPcmWindowTo16Bit(chunkBuffer, alignedRead, bytesPerSample)
                        }
                        val pcm16Count = if (bytesPerSample == 2) alignedRead else pcm16Buffer.size

                        writePcmToTrackOrPace(
                            audioTrack = audioTrack,
                            trackReady = trackReady,
                            buffer = pcm16Buffer,
                            byteCount = pcm16Count,
                            bytesPerSecond = (sampleRate.toLong() * channels.toLong() * 2L).coerceAtLeast(1L)
                        )

                        currentByteOffset += alignedRead
                        _playbackPositionMs.value = ((currentByteOffset * 1000L) / bytesPerSecond)
                            .coerceIn(0L, totalDurationMs)
                    }
                }
            } catch (_: Exception) {
                _isPlaying.value = false
                _activeSilenceJumping.value = false
                withContext(Dispatchers.Main) {
                    try {
                        isUsingPcmEngine = false
                        startMediaPlayerFallback(
                            file = file,
                            startPositionMs = clampedStartMs,
                            speed = _playbackSpeed.value,
                            silenceConfig = silenceSkipConfig,
                            preindexedSilenceMap = preindexedSilenceMap
                        )
                    } catch (_: Exception) {}
                }
            } finally {
                try {
                    audioTrack?.stop()
                } catch (_: Exception) {}
                try {
                    audioTrack?.release()
                } catch (_: Exception) {}
                if (activeAudioTrack === audioTrack) {
                    activeAudioTrack = null
                }
            }
        }
    }

    private fun convertPcmWindowTo16Bit(
        sourceBuffer: ByteArray,
        byteCount: Int,
        bytesPerSample: Int
    ): ByteArray {
        if (bytesPerSample <= 0) return ByteArray(0)
        val sampleCount = byteCount / bytesPerSample
        val out = ByteArray(sampleCount * 2)
        var srcIdx = 0
        var dstIdx = 0
        while (srcIdx + bytesPerSample <= byteCount && dstIdx + 1 < out.size) {
            val sample16: Int = when (bytesPerSample) {
                1 -> ((sourceBuffer[srcIdx].toInt() and 0xFF) - 128) shl 8
                3 -> {
                    val b1 = sourceBuffer[srcIdx + 1].toInt() and 0xFF
                    val b2 = sourceBuffer[srcIdx + 2].toInt()
                    (b2 shl 8) or b1
                }
                4 -> {
                    val b2 = sourceBuffer[srcIdx + 2].toInt() and 0xFF
                    val b3 = sourceBuffer[srcIdx + 3].toInt()
                    (b3 shl 8) or b2
                }
                else -> {
                    val lo = sourceBuffer[srcIdx].toInt() and 0xFF
                    val hi = sourceBuffer[srcIdx + 1].toInt()
                    (hi shl 8) or lo
                }
            }
            out[dstIdx] = (sample16 and 0xFF).toByte()
            out[dstIdx + 1] = ((sample16 shr 8) and 0xFF).toByte()
            srcIdx += bytesPerSample
            dstIdx += 2
        }
        return out
    }

    /**
     * Streams bit-exact PCM frames to the hardware [AudioTrack] without artificial software sleep
     * delays on real Android devices (preventing buffer underruns, crackling, and time-stretching).
     * In headless Robolectric JVM tests, falls back to wall-clock coroutine pacing.
     */
    private suspend fun writePcmToTrackOrPace(
        audioTrack: AudioTrack?,
        trackReady: Boolean,
        buffer: ByteArray,
        byteCount: Int,
        bytesPerSecond: Long
    ) {
        if (byteCount <= 0) return
        val speed = _playbackSpeed.value.coerceIn(0.5f, 3.0f)
        val nominalFrameMs = ((byteCount * 1000L) / bytesPerSecond.coerceAtLeast(1L)).coerceAtLeast(5L)
        val effectiveFrameMs = (nominalFrameMs / speed).toLong().coerceAtLeast(4L)

        if (trackReady && audioTrack != null && !isRobolectricRuntime) {
            val written = try {
                audioTrack.write(buffer, 0, byteCount, AudioTrack.WRITE_BLOCKING)
            } catch (_: Exception) {
                -1
            }
            if (written <= 0) {
                delay(effectiveFrameMs)
            }
        } else {
            if (trackReady && audioTrack != null) {
                try {
                    audioTrack.write(buffer, 0, byteCount)
                } catch (_: Exception) {}
            }
            delay(effectiveFrameMs)
        }
    }

    private fun readAlignedBytes(
        raf: RandomAccessFile,
        buffer: ByteArray,
        maxBytes: Int,
        blockAlign: Int
    ): Int {
        val target = minOf(maxBytes, buffer.size)
        var total = 0
        while (total < target) {
            val r = raf.read(buffer, total, target - total)
            if (r <= 0) break
            total += r
        }
        return if (total >= blockAlign) {
            total - (total % blockAlign)
        } else {
            0
        }
    }

    private fun alignToBlock(rawOffset: Long, blockAlign: Int, maxBytes: Long): Long {
        val safeAlign = blockAlign.coerceAtLeast(1).toLong()
        val aligned = (rawOffset / safeAlign) * safeAlign
        val alignedMax = (maxBytes / safeAlign) * safeAlign
        return aligned.coerceIn(0L, alignedMax)
    }

    private fun startMediaPlayerFallback(
        file: File,
        startPositionMs: Long,
        speed: Float,
        silenceConfig: SilenceSkipConfig,
        preindexedSilenceMap: List<SilenceInterval>
    ) {
        try {
            hasAppliedCustomMediaPlayerSpeed = false
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            // Strict flat 0 dB (1.0f) unity gain
            mp.setVolume(1.0f, 1.0f)
            val totalDur = mp.duration.toLong().coerceAtLeast(500L)
            _playbackDurationMs.value = totalDur

            val initialMap = AudioFormatUtils.computeSilenceMapFromPeaks(
                peaks = activePlaybackPeaks,
                durationMs = totalDur,
                config = silenceConfig
            ).ifEmpty {
                if (silenceConfig.smartAutoMode) preindexedSilenceMap else emptyList()
            }
            _activeSilenceMap.value = initialMap

            if (startPositionMs > 0L && startPositionMs < totalDur) {
                mp.seekTo(startPositionMs.toInt())
                _playbackPositionMs.value = startPositionMs
            } else {
                _playbackPositionMs.value = 0L
            }

            if (kotlin.math.abs(speed - 1.0f) > 0.01f) {
                applyPlaybackSpeedInternal(mp, speed)
            }
            mp.setOnCompletionListener {
                _isPlaying.value = false
                _playbackPositionMs.value = _playbackDurationMs.value
                _activeSilenceJumping.value = false
                playbackTickerJob?.cancel()
            }
            mp.start()
            mediaPlayer = mp
            _isPlaying.value = true
            startMediaPlayerTicker()
        } catch (_: Exception) {
            // Attempt automatic deep container unpack & repair if MediaPlayer rejected the container
            engineScope.launch(Dispatchers.IO) {
                val repairResult = WavFileTools.repairAndRebuildCorruptedRecording(
                    file = file,
                    fallbackSampleRate = currentSampleRate,
                    fallbackChannels = currentChannels
                )
                if (repairResult.success && repairResult.finalFile.exists()) {
                    withContext(Dispatchers.Main) {
                        try {
                            val mpRetry = MediaPlayer()
                            mpRetry.setDataSource(repairResult.finalFile.absolutePath)
                            mpRetry.prepare()
                            mpRetry.setVolume(1.0f, 1.0f)
                            val totalDur = mpRetry.duration.toLong().coerceAtLeast(500L)
                            _playbackDurationMs.value = totalDur
                            if (startPositionMs > 0L && startPositionMs < totalDur) {
                                mpRetry.seekTo(startPositionMs.toInt())
                                _playbackPositionMs.value = startPositionMs
                            } else {
                                _playbackPositionMs.value = 0L
                            }
                            mpRetry.setOnCompletionListener {
                                _isPlaying.value = false
                                _playbackPositionMs.value = _playbackDurationMs.value
                                _activeSilenceJumping.value = false
                                playbackTickerJob?.cancel()
                            }
                            mpRetry.start()
                            mediaPlayer = mpRetry
                            _isPlaying.value = true
                            startMediaPlayerTicker()
                        } catch (_: Exception) {
                            _isPlaying.value = false
                        }
                    }
                } else {
                    _isPlaying.value = false
                }
            }
        }
    }

    fun togglePlayPause(
        currentFile: File?,
        peaks: List<Float>,
        preindexedSilenceMap: List<SilenceInterval> = preindexedSilenceIntervals
    ) {
        val targetFile = currentFile ?: currentSessionFile
        if (_isPlaying.value) {
            // Pause active playback while preserving exact position
            _isPlaying.value = false
            _activeSilenceJumping.value = false
            pcmPlaybackJob?.cancel()
            playbackTickerJob?.cancel()
            try {
                activeAudioTrack?.pause()
                activeAudioTrack?.flush()
            } catch (_: Exception) {}
            try {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.pause()
                }
            } catch (_: Exception) {}
            return
        }

        // Resume playback from current position (or 0L if at the end of the recording)
        if (!isUsingPcmEngine && mediaPlayer != null) {
            val mp = mediaPlayer!!
            try {
                if (mp.currentPosition >= mp.duration - 150) {
                    mp.seekTo(0)
                    _playbackPositionMs.value = 0L
                    _silenceSkippedSavedMs.value = 0L
                }
                mp.setVolume(1.0f, 1.0f)
                if (kotlin.math.abs(_playbackSpeed.value - 1.0f) > 0.01f || hasAppliedCustomMediaPlayerSpeed) {
                    applyPlaybackSpeedInternal(mp, _playbackSpeed.value)
                }
                mp.start()
                _isPlaying.value = true
                startMediaPlayerTicker()
                return
            } catch (_: Exception) {
                // Fall through to reload file
            }
        }

        if (targetFile != null && targetFile.exists()) {
            val resumeAtZero = _playbackDurationMs.value > 0L &&
                _playbackPositionMs.value >= _playbackDurationMs.value - 150L
            val startPos = if (resumeAtZero) 0L else _playbackPositionMs.value
            val effectivePeaks = if (peaks.isNotEmpty()) peaks else activePlaybackPeaks
            val effectiveMap = if (silenceSkipConfig.smartAutoMode && preindexedSilenceMap.isNotEmpty()) {
                preindexedSilenceMap
            } else {
                _activeSilenceMap.value
            }
            loadAndPlayFile(
                file = targetFile,
                peaks = effectivePeaks,
                startPositionMs = startPos,
                speed = _playbackSpeed.value,
                silenceConfig = silenceSkipConfig,
                preindexedSilenceMap = effectiveMap
            )
        }
    }

    fun seekTo(positionMs: Long) {
        val maxDur = _playbackDurationMs.value.coerceAtLeast(1L)
        val clamped = positionMs.coerceIn(0L, maxDur)
        _playbackPositionMs.value = clamped
        if (isUsingPcmEngine) {
            pendingSeekMs.set(clamped)
        } else {
            try {
                lastMediaPlayerSeekUptimeMs = SystemClock.uptimeMillis()
                mediaPlayer?.seekTo(clamped.toInt())
            } catch (_: Exception) {}
        }
    }

    fun seekRelative(deltaMs: Long) {
        val target = (_playbackPositionMs.value + deltaMs)
            .coerceIn(0L, _playbackDurationMs.value.coerceAtLeast(1L))
        seekTo(target)
    }

    fun setPlaybackSpeed(multiplier: Float) {
        val clampedSpeed = multiplier.coerceIn(0.5f, 3.0f)
        _playbackSpeed.value = clampedSpeed
        activeAudioTrack?.let { track ->
            applyAudioTrackSpeed(track, clampedSpeed)
        }
        mediaPlayer?.let { mp ->
            if (_isPlaying.value) {
                applyPlaybackSpeedInternal(mp, clampedSpeed)
            }
        }
    }

    private fun applyAudioTrackSpeed(track: AudioTrack, multiplier: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val clamped = multiplier.coerceIn(0.5f, 3.0f)
            if (kotlin.math.abs(clamped - 1.0f) <= 0.01f && !hasAppliedCustomTrackSpeed) {
                return
            }
            try {
                val params = PlaybackParams()
                    .setSpeed(clamped)
                    .setPitch(1.0f)
                track.playbackParams = params
                hasAppliedCustomTrackSpeed = kotlin.math.abs(clamped - 1.0f) > 0.01f
            } catch (_: Exception) {}
        }
    }

    private fun applyPlaybackSpeedInternal(mp: MediaPlayer, multiplier: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val clamped = multiplier.coerceIn(0.5f, 3.0f)
            if (kotlin.math.abs(clamped - 1.0f) <= 0.01f && !hasAppliedCustomMediaPlayerSpeed) {
                return
            }
            try {
                val params = PlaybackParams()
                    .setSpeed(clamped)
                    .setPitch(1.0f)
                mp.playbackParams = params
                hasAppliedCustomMediaPlayerSpeed = kotlin.math.abs(clamped - 1.0f) > 0.01f
            } catch (_: Exception) {}
        }
    }

    fun stopPlayback() {
        stopPlaybackInternal(resetSavedCounter = false)
    }

    private fun stopPlaybackInternal(resetSavedCounter: Boolean) {
        pcmPlaybackJob?.cancel()
        pcmPlaybackJob = null
        playbackTickerJob?.cancel()
        playbackTickerJob = null
        _isPlaying.value = false
        _activeSilenceJumping.value = false
        pendingSeekMs.set(-1L)
        if (resetSavedCounter) {
            _silenceSkippedSavedMs.value = 0L
        }
        try {
            activeAudioTrack?.stop()
        } catch (_: Exception) {}
        try {
            activeAudioTrack?.release()
        } catch (_: Exception) {}
        activeAudioTrack = null

        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun startMediaPlayerTicker() {
        playbackTickerJob?.cancel()
        playbackTickerJob = engineScope.launch(Dispatchers.Main) {
            while (isActive && _isPlaying.value && !isUsingPcmEngine) {
                val mp = mediaPlayer ?: break
                try {
                    if (!mp.isPlaying) break
                    val nowUptime = SystemClock.uptimeMillis()
                    val pos = mp.currentPosition.toLong()
                    val dur = mp.duration.toLong().coerceAtLeast(1L)
                    _playbackPositionMs.value = pos
                    _playbackDurationMs.value = dur

                    if (silenceSkipConfig.isEffectivelyEnabled && dur > 500L && (nowUptime - lastMediaPlayerSeekUptimeMs) > 120L) {
                        val jumpTarget = resolveRealtimeSilenceJump(
                            currentPosMs = pos,
                            totalDurationMs = dur
                        )
                        if (jumpTarget != null && jumpTarget > pos + 60L) {
                            _activeSilenceJumping.value = true
                            lastMediaPlayerSeekUptimeMs = nowUptime
                            mp.seekTo(jumpTarget.toInt())
                            _playbackPositionMs.value = jumpTarget
                            _silenceSkippedSavedMs.value += (jumpTarget - pos)
                        } else {
                            _activeSilenceJumping.value = false
                        }
                    } else if (!silenceSkipConfig.isEffectivelyEnabled) {
                        _activeSilenceJumping.value = false
                    }
                } catch (_: Exception) {
                    break
                }
                delay(25L)
            }
        }
    }

    /**
     * Resolves whether [currentPosMs] falls inside a silent interval in the Silence Map Index
     * (`_activeSilenceMap`), falling back to peak-based timeline calculation if the map is still
     * being indexed.
     */
    private fun resolveRealtimeSilenceJump(
        currentPosMs: Long,
        totalDurationMs: Long
    ): Long? {
        val map = _activeSilenceMap.value
        if (map.isNotEmpty()) {
            val matchingInterval = map.firstOrNull { interval ->
                currentPosMs >= interval.startMs && currentPosMs < (interval.endMs - 30L)
            }
            if (matchingInterval != null) {
                val target = matchingInterval.endMs.coerceIn(currentPosMs, totalDurationMs - 20L)
                if (target > currentPosMs + 60L) {
                    return target
                }
            }
            return null
        }

        val peaksToUse = if (activePlaybackPeaks.size >= 8) {
            activePlaybackPeaks
        } else {
            val extracted = currentSessionFile?.let { WavFileTools.extractWaveformFromFile(it) } ?: emptyList()
            activePlaybackPeaks = extracted
            extracted
        }
        if (peaksToUse.size >= 8) {
            return findSilenceJumpTarget(
                currentPosMs = currentPosMs,
                totalDurationMs = totalDurationMs,
                peaks = peaksToUse,
                config = silenceSkipConfig
            )
        }
        return null
    }

    /**
     * Hysteresis-Aware Timeline-Seeking Silence Detector (fallback when pre-indexed Silence Map is empty):
     * Uses the dual-threshold hysteresis and intra-phrase gap-bridging silence map computed from peaks
     * so playback never jumps from loud peak to loud peak over quiet speech.
     */
    private fun findSilenceJumpTarget(
        currentPosMs: Long,
        totalDurationMs: Long,
        peaks: List<Float>,
        config: SilenceSkipConfig
    ): Long? {
        if (peaks.isEmpty() || totalDurationMs <= 0L) return null
        val intervals = AudioFormatUtils.computeSilenceMapFromPeaks(
            peaks = peaks,
            durationMs = totalDurationMs,
            config = config
        )
        val matchingInterval = intervals.firstOrNull { interval ->
            currentPosMs >= interval.startMs && currentPosMs < (interval.endMs - 30L)
        } ?: return null

        val target = matchingInterval.endMs.coerceIn(currentPosMs, totalDurationMs - 20L)
        return if (target > currentPosMs + 60L) target else null
    }
}
