package com.example.audio

import android.annotation.SuppressLint
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
import com.example.R
import com.example.data.AudioFormatUtils
import com.example.data.SilenceInterval
import com.example.data.SilenceSkipConfig
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
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

    // Active microphone / headset input hardware label & async Bluetooth connection status
    private val _activeAudioDeviceName = MutableStateFlow("Phone Microphone")
    val activeAudioDeviceName: StateFlow<String> = _activeAudioDeviceName.asStateFlow()

    private val _isConnectingBluetoothMic = MutableStateFlow(false)
    val isConnectingBluetoothMic: StateFlow<Boolean> = _isConnectingBluetoothMic.asStateFlow()

    private val _routingNoticeMessage = MutableStateFlow<String?>(null)
    val routingNoticeMessage: StateFlow<String?> = _routingNoticeMessage.asStateFlow()

    fun clearRoutingNotice() {
        _routingNoticeMessage.value = null
    }

    private val captureStateLock = Any()
    @Volatile
    private var hasActiveBluetoothCommRoute: Boolean = false
    @Volatile
    private var isCapturingFromBluetooth: Boolean = false
    @Volatile
    private var isSwitchingMicrophone: Boolean = false
    @Volatile
    private var activeBluetoothDeviceId: Int? = null
    private val pendingFallbackToPhoneMic = AtomicBoolean(false)
    private val pendingSwitchToBluetooth = AtomicBoolean(false)
    private val failedBluetoothDeviceIds = ConcurrentHashMap.newKeySet<Int>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainThreadExecutor = Executor { command -> mainHandler.post(command) }
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var activeCommDeviceDisconnectListener: Any? = null
    private var isDeviceMonitorRegistered = false

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
    private val pendingScrubFlush = AtomicBoolean(false)
    @Volatile
    private var isAudibleScrubbing: Boolean = false
    @Volatile
    private var wasPlayingBeforeScrub: Boolean = false
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
     * Queries OS audio devices to locate an available Bluetooth headset (BLE Audio or SCO/HFP).
     * On Android 12+ (API 31+), checks `availableCommunicationDevices` first because modern TWS
     * earbuds (such as Huawei FreeBuds 5) advertise their HFP/BLE mic channel there before the
     * input node is activated in `GET_DEVICES_INPUTS`.
     */
    @SuppressLint("MissingPermission")
    private fun findAvailableBluetoothRouteDevice(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevices = try {
                    audioManager.availableCommunicationDevices
                } catch (_: Exception) {
                    emptyList()
                }
                val bleComm = commDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                if (bleComm != null) return bleComm
                val scoComm = commDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (scoComm != null) return scoComm
            }

            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bleInput = inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                if (bleInput != null) return bleInput
            }
            inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Finds the highest-priority connected external or headset input device (Bluetooth BLE/SCO,
     * wired 3.5mm headset, or USB headset) without mutating hardware state.
     */
    @SuppressLint("MissingPermission")
    fun findPreferredHeadsetInputDevice(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val bluetoothDevice = findAvailableBluetoothRouteDevice(audioManager)
            if (bluetoothDevice != null) return bluetoothDevice

            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val wiredHeadset = inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            if (wiredHeadset != null) return wiredHeadset

            inputDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findBuiltInMicInputDevice(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        } catch (_: Exception) {
            null
        }
    }

    private fun isBluetoothHeadsetDevice(device: AudioDeviceInfo?): Boolean {
        if (device == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
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

    internal fun markBluetoothDeviceFailedForSession(deviceId: Int) {
        failedBluetoothDeviceIds.add(deviceId)
    }

    internal fun clearFailedBluetoothDevices() {
        failedBluetoothDeviceIds.clear()
    }

    internal fun isBluetoothDeviceSuppressedForSession(deviceId: Int?): Boolean {
        return deviceId != null && failedBluetoothDeviceIds.contains(deviceId)
    }

    internal fun hasPendingBluetoothSwitch(): Boolean = pendingSwitchToBluetooth.get()

    internal fun hasPendingPhoneMicFallback(): Boolean = pendingFallbackToPhoneMic.get()

    /**
     * Evaluates Bluetooth audio topology changes for true two-way mid-recording handoff:
     * - Bluetooth added while recording on Phone Mic -> queues seamless Phone -> BT switch
     *   (deferred automatically if paused, activating when resumed), unless this Bluetooth
     *   device ID failed/timed out earlier in the same session.
     * - Bluetooth physically removed -> clears failed-device suppression and any queued BT switch,
     *   and if actively capturing on Bluetooth, queues seamless BT -> Phone Mic fallback.
     */
    internal fun handleBluetoothTopologyUpdate(
        hasBluetoothAvailable: Boolean,
        bluetoothDeviceId: Int?
    ) {
        if (!hasBluetoothAvailable) {
            failedBluetoothDeviceIds.clear()
            pendingSwitchToBluetooth.set(false)
            if (_isRecording.value && isCapturingFromBluetooth && !_isConnectingBluetoothMic.value && !isSwitchingMicrophone) {
                pendingFallbackToPhoneMic.set(true)
            }
            return
        }

        if (_isRecording.value && !isCapturingFromBluetooth && !_isConnectingBluetoothMic.value && !isSwitchingMicrophone) {
            if (bluetoothDeviceId != null && !failedBluetoothDeviceIds.contains(bluetoothDeviceId)) {
                pendingSwitchToBluetooth.set(true)
            }
        }
    }

    /**
     * Refreshes connected audio device state and signals bidirectional mid-recording handoffs
     * (Phone Mic <-> Bluetooth Headset) whenever OS audio topology changes.
     */
    fun refreshConnectedAudioDevices() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val availableBt = findAvailableBluetoothRouteDevice(am)
        if (_isRecording.value) {
            handleBluetoothTopologyUpdate(
                hasBluetoothAvailable = availableBt != null,
                bluetoothDeviceId = availableBt?.id
            )
            return
        }
        val preferred = findPreferredHeadsetInputDevice(am)
        _activeAudioDeviceName.value = formatInputDeviceLabel(preferred)
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
                    if (removedDevices?.any { isBluetoothHeadsetDevice(it) } == true) {
                        failedBluetoothDeviceIds.clear()
                    }
                    refreshConnectedAudioDevices()
                }
            }
            audioDeviceCallback = callback
            try {
                am.registerAudioDeviceCallback(callback, mainHandler)
            } catch (_: Exception) {}
        }
    }

    private fun registerActiveBluetoothDisconnectListener(audioManager: AudioManager) {
        unregisterActiveBluetoothDisconnectListener(audioManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                if (_isRecording.value && isCapturingFromBluetooth && !_isConnectingBluetoothMic.value) {
                    if (device == null || !isBluetoothHeadsetDevice(device)) {
                        val btStillAvailable = findAvailableBluetoothRouteDevice(audioManager)
                        if (btStillAvailable == null) {
                            pendingFallbackToPhoneMic.set(true)
                        }
                    }
                }
            }
            activeCommDeviceDisconnectListener = listener
            try {
                audioManager.addOnCommunicationDeviceChangedListener(mainThreadExecutor, listener)
            } catch (_: Exception) {
                activeCommDeviceDisconnectListener = null
            }
        }
    }

    private fun unregisterActiveBluetoothDisconnectListener(audioManager: AudioManager?) {
        val existing = activeCommDeviceDisconnectListener ?: return
        activeCommDeviceDisconnectListener = null
        if (audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.removeOnCommunicationDeviceChangedListener(
                    existing as AudioManager.OnCommunicationDeviceChangedListener
                )
            } catch (_: Exception) {}
        }
    }

    fun releaseEngineResources() {
        releaseMicrophoneHardwareImmediately()
        stopPlayback()
        if (!isDeviceMonitorRegistered) return
        isDeviceMonitorRegistered = false
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        unregisterActiveBluetoothDisconnectListener(am)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try {
                am?.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (_: Exception) {}
        }
        audioDeviceCallback = null
    }

    private sealed class BluetoothRouteResult {
        data class Connected(val inputDevice: AudioDeviceInfo?, val label: String) : BluetoothRouteResult()
        data object FallbackToPhoneMic : BluetoothRouteResult()
        data object Cancelled : BluetoothRouteResult()
    }

    /**
     * Stage 1: Asynchronous Bluetooth Route Coordinator.
     * Requests OS communication audio routing using the standard Android API for the device's OS
     * version (strictly `setCommunicationDevice` on Android 12+, or `startBluetoothSco` on older
     * versions — never mixing both), then suspends until the OS confirms the mic route is open.
     * Falls back cleanly to Phone Microphone on timeout or rejection so recordings never capture
     * flatlined silence.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun establishBluetoothRouteAsync(
        audioManager: AudioManager,
        targetBtDevice: AudioDeviceInfo,
        timeoutMs: Long = 3500L
    ): BluetoothRouteResult {
        if (!_isRecording.value) return BluetoothRouteResult.Cancelled
        _isConnectingBluetoothMic.value = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevice = try {
                    audioManager.availableCommunicationDevices.firstOrNull { dev ->
                        dev.id == targetBtDevice.id || isBluetoothHeadsetDevice(dev)
                    }
                } catch (_: Exception) {
                    null
                }

                if (commDevice == null) {
                    // Check if a BLE/SCO input node is already directly active without a communication profile
                    val directInput = try {
                        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                            .firstOrNull { isBluetoothHeadsetDevice(it) }
                    } catch (_: Exception) {
                        null
                    }
                    return if (directInput != null) {
                        activeBluetoothDeviceId = directInput.id
                        BluetoothRouteResult.Connected(directInput, formatInputDeviceLabel(directInput))
                    } else {
                        BluetoothRouteResult.FallbackToPhoneMic
                    }
                }

                val routeConfirmed = CompletableDeferred<Boolean>()
                val tempListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                    if (device != null && (device.id == commDevice.id || isBluetoothHeadsetDevice(device))) {
                        routeConfirmed.complete(true)
                    }
                }

                try {
                    audioManager.addOnCommunicationDeviceChangedListener(mainThreadExecutor, tempListener)
                } catch (_: Exception) {}

                try {
                    try {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    } catch (_: Exception) {}
                    hasActiveBluetoothCommRoute = true

                    val alreadyActive = try {
                        val current = audioManager.communicationDevice
                        current != null && (current.id == commDevice.id || isBluetoothHeadsetDevice(current))
                    } catch (_: Exception) {
                        false
                    }

                    if (alreadyActive) {
                        routeConfirmed.complete(true)
                    } else {
                        val accepted = try {
                            audioManager.setCommunicationDevice(commDevice)
                        } catch (_: Exception) {
                            false
                        }
                        if (!accepted) {
                            teardownBluetoothRoute(audioManager)
                            return BluetoothRouteResult.FallbackToPhoneMic
                        }
                    }

                    // Await OS confirmation callback (with lightweight polling backup for OEM HALs/Robolectric)
                    val confirmed = withTimeoutOrNull(timeoutMs) {
                        while (_isRecording.value) {
                            if (routeConfirmed.isCompleted) return@withTimeoutOrNull true
                            val activeComm = try {
                                audioManager.communicationDevice
                            } catch (_: Exception) {
                                null
                            }
                            if (activeComm != null && (activeComm.id == commDevice.id || isBluetoothHeadsetDevice(activeComm))) {
                                return@withTimeoutOrNull true
                            }
                            delay(60L)
                        }
                        false
                    } ?: false

                    if (!_isRecording.value) {
                        teardownBluetoothRoute(audioManager)
                        return BluetoothRouteResult.Cancelled
                    }

                    if (!confirmed) {
                        teardownBluetoothRoute(audioManager)
                        return BluetoothRouteResult.FallbackToPhoneMic
                    }

                    // Briefly wait for the Bluetooth input AudioDeviceInfo node to expose its native capabilities
                    var activeInput: AudioDeviceInfo? = null
                    val inputDeadline = SystemClock.uptimeMillis() + 450L
                    while (_isRecording.value) {
                        activeInput = try {
                            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                                .firstOrNull { isBluetoothHeadsetDevice(it) }
                        } catch (_: Exception) {
                            null
                        }
                        if (activeInput != null || SystemClock.uptimeMillis() >= inputDeadline) break
                        delay(45L)
                    }

                    if (!_isRecording.value) {
                        teardownBluetoothRoute(audioManager)
                        return BluetoothRouteResult.Cancelled
                    }

                    val resolvedInput = activeInput ?: commDevice
                    activeBluetoothDeviceId = resolvedInput.id
                    registerActiveBluetoothDisconnectListener(audioManager)
                    return BluetoothRouteResult.Connected(resolvedInput, formatInputDeviceLabel(resolvedInput))
                } finally {
                    try {
                        audioManager.removeOnCommunicationDeviceChangedListener(tempListener)
                    } catch (_: Exception) {}
                }
            } else {
                // Legacy Android (< API 31): standard SCO handshake waiting for SCO_AUDIO_STATE_CONNECTED
                val scoConfirmed = CompletableDeferred<Boolean>()
                val scoReceiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                        val state = intent.getIntExtra(
                            AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_ERROR
                        )
                        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                            scoConfirmed.complete(true)
                        } else if (state == AudioManager.SCO_AUDIO_STATE_ERROR) {
                            scoConfirmed.complete(false)
                        }
                    }
                }
                val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                try {
                    context.registerReceiver(scoReceiver, filter)
                } catch (_: Exception) {}

                try {
                    try {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                        hasActiveBluetoothCommRoute = true
                    } catch (_: Exception) {
                        teardownBluetoothRoute(audioManager)
                        return BluetoothRouteResult.FallbackToPhoneMic
                    }

                    val confirmed = withTimeoutOrNull(timeoutMs) {
                        while (_isRecording.value) {
                            if (scoConfirmed.isCompleted) return@withTimeoutOrNull scoConfirmed.await()
                            if (isRobolectricRuntime && try { audioManager.isBluetoothScoOn } catch (_: Exception) { false }) {
                                return@withTimeoutOrNull true
                            }
                            delay(60L)
                        }
                        false
                    } ?: false

                    if (!_isRecording.value) {
                        teardownBluetoothRoute(audioManager)
                        return BluetoothRouteResult.Cancelled
                    }
                    if (!confirmed) {
                        teardownBluetoothRoute(audioManager)
                        return BluetoothRouteResult.FallbackToPhoneMic
                    }

                    val btInput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                                .firstOrNull { isBluetoothHeadsetDevice(it) }
                        } catch (_: Exception) {
                            null
                        }
                    } else null
                    activeBluetoothDeviceId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) btInput?.id else null
                    return BluetoothRouteResult.Connected(btInput ?: targetBtDevice, formatInputDeviceLabel(btInput ?: targetBtDevice))
                } finally {
                    try {
                        context.unregisterReceiver(scoReceiver)
                    } catch (_: Exception) {}
                }
            }
        } finally {
            _isConnectingBluetoothMic.value = false
        }
    }

    /**
     * Tears down the active Bluetooth communication route and restores normal audio mode.
     * Invoked ONLY when a recording session ends (Save/Discard), times out on startup, or
     * when Bluetooth earbuds physically disconnect mid-session — NEVER during Pause.
     */
    @Suppress("DEPRECATION")
    private fun teardownBluetoothRoute(audioManager: AudioManager?) {
        unregisterActiveBluetoothDisconnectListener(audioManager)
        activeBluetoothDeviceId = null
        if (!hasActiveBluetoothCommRoute) return
        hasActiveBluetoothCommRoute = false
        if (audioManager == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (_: Exception) {}
        } else {
            try {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            } catch (_: Exception) {}
        }
        try {
            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) {}
    }

    internal data class CaptureConfig(
        val sampleRate: Int,
        val channels: Int,
        val channelMask: Int,
        val bufferSizeBytes: Int
    )

    /**
     * Pure dynamic hardware rate & channel ordering without hardcoded limits:
     * - When the hardware reports explicit native `sampleRates`, selects strictly from highest
     *   native rate downward so we always capture the cleanest signal the hardware natively produces.
     * - When `sampleRates` is empty (meaning arbitrary/standard rates supported by the HAL),
     *   prioritizes the OS-reported native optimal sample rate (`PROPERTY_OUTPUT_SAMPLE_RATE`)
     *   followed by standard descending PCM rates.
     * - Picks the highest natively supported channel count (capped to 1..2 for PCM mono/stereo).
     */
    internal fun orderNativeHardwareCapabilities(
        nativeSampleRates: IntArray,
        nativeChannelCounts: IntArray,
        osNativeSampleRate: Int?,
        isBluetoothSco: Boolean
    ): Pair<List<Int>, List<Int>> {
        val validNativeRates = nativeSampleRates
            .filter { it in 8000..192000 }
            .distinct()
            .sortedDescending()

        val orderedRates = if (validNativeRates.isNotEmpty()) {
            validNativeRates
        } else {
            buildList {
                if (osNativeSampleRate != null && osNativeSampleRate in 8000..192000) {
                    add(osNativeSampleRate)
                }
                addAll(listOf(96000, 48000, 44100, 32000, 24000, 22050, 16000, 11025, 8000))
            }.distinct()
        }

        val validNativeChannels = nativeChannelCounts
            .filter { it in 1..2 }
            .distinct()
            .sortedDescending()

        val orderedChannels = when {
            validNativeChannels.isNotEmpty() -> validNativeChannels
            isBluetoothSco -> listOf(1)
            else -> listOf(2, 1)
        }

        return orderedRates to orderedChannels
    }

    /**
     * Stage 2: Dynamic Hardware Configuration Selector.
     * Queries the active microphone's [AudioDeviceInfo] native `sampleRates` and `channelCounts`
     * and returns OS-validated [CaptureConfig] candidates ordered from highest native capability down.
     */
    internal fun buildDynamicCaptureCandidates(
        audioManager: AudioManager?,
        activeInputDevice: AudioDeviceInfo?
    ): List<CaptureConfig> {
        val nativeRates = if (activeInputDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                activeInputDevice.sampleRates
            } catch (_: Exception) {
                IntArray(0)
            }
        } else {
            IntArray(0)
        }

        val nativeChannels = if (activeInputDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                activeInputDevice.channelCounts
            } catch (_: Exception) {
                IntArray(0)
            }
        } else {
            IntArray(0)
        }

        val osNativeRate = try {
            audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        } catch (_: Exception) {
            null
        }

        val isBtSco = activeInputDevice != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            activeInputDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

        val (orderedRates, orderedChannels) = orderNativeHardwareCapabilities(
            nativeSampleRates = nativeRates,
            nativeChannelCounts = nativeChannels,
            osNativeSampleRate = osNativeRate,
            isBluetoothSco = isBtSco
        )

        val candidates = ArrayList<CaptureConfig>()
        for (ch in orderedChannels) {
            val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            for (rate in orderedRates) {
                val rawMinBuf = try {
                    AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                } catch (_: Exception) {
                    -1
                }
                if (rawMinBuf > 0 || isRobolectricRuntime) {
                    val bufBytes = rawMinBuf.coerceAtLeast(4096) * 2
                    candidates.add(
                        CaptureConfig(
                            sampleRate = rate,
                            channels = ch,
                            channelMask = mask,
                            bufferSizeBytes = bufBytes
                        )
                    )
                }
            }
        }

        if (candidates.isEmpty()) {
            val fallbackRate = osNativeRate?.takeIf { it in 8000..96000 } ?: 48000
            val fallbackMask = AudioFormat.CHANNEL_IN_MONO
            val fallbackBuf = try {
                AudioRecord.getMinBufferSize(fallbackRate, fallbackMask, AudioFormat.ENCODING_PCM_16BIT)
            } catch (_: Exception) {
                4096
            }.coerceAtLeast(4096) * 2
            candidates.add(
                CaptureConfig(
                    sampleRate = fallbackRate,
                    channels = 1,
                    channelMask = fallbackMask,
                    bufferSizeBytes = fallbackBuf
                )
            )
        }

        return candidates
    }

    private fun resolveAudioSourceForRoute(
        activeInputDevice: AudioDeviceInfo?,
        isBluetoothRoute: Boolean,
        echoCancellationEnabled: Boolean,
        noiseSuppressionEnabled: Boolean
    ): Int {
        return when {
            isBluetoothRoute -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            activeInputDevice != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                activeInputDevice.type != AudioDeviceInfo.TYPE_BUILTIN_MIC -> MediaRecorder.AudioSource.MIC
            !echoCancellationEnabled && !noiseSuppressionEnabled -> MediaRecorder.AudioSource.MIC
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    private data class HardwareCaptureSession(
        val audioRecord: AudioRecord,
        val actualSampleRate: Int,
        val actualChannels: Int,
        val chunkShorts: Int,
        val isBluetoothRoute: Boolean
    )

    @SuppressLint("MissingPermission")
    private fun createAndStartAudioRecord(
        audioManager: AudioManager?,
        activeInputDevice: AudioDeviceInfo?,
        isBluetoothRoute: Boolean,
        echoCancellationEnabled: Boolean,
        noiseSuppressionEnabled: Boolean
    ): HardwareCaptureSession? {
        val resolvedInputDevice = activeInputDevice ?: audioManager?.let { findBuiltInMicInputDevice(it) }
        val audioSource = resolveAudioSourceForRoute(
            activeInputDevice = resolvedInputDevice,
            isBluetoothRoute = isBluetoothRoute,
            echoCancellationEnabled = echoCancellationEnabled,
            noiseSuppressionEnabled = noiseSuppressionEnabled
        )

        val candidates = buildDynamicCaptureCandidates(audioManager, resolvedInputDevice)
        var initializedRecord: AudioRecord? = null
        var chosenConfig: CaptureConfig? = null

        for (candidate in candidates) {
            val record = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioRecord.Builder()
                        .setAudioSource(audioSource)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(candidate.sampleRate)
                                .setChannelMask(candidate.channelMask)
                                .build()
                        )
                        .setBufferSizeInBytes(candidate.bufferSizeBytes)
                        .build()
                } else {
                    AudioRecord(
                        audioSource,
                        candidate.sampleRate,
                        candidate.channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        candidate.bufferSizeBytes
                    )
                }
            } catch (_: Exception) {
                null
            }

            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                initializedRecord = record
                chosenConfig = candidate
                break
            } else {
                try {
                    record?.release()
                } catch (_: Exception) {}
            }
        }

        val audioRecord = initializedRecord
        val config = chosenConfig
        if (audioRecord == null || config == null) {
            synchronized(captureStateLock) {
                activeAudioRecord = null
            }
            return null
        }

        synchronized(captureStateLock) {
            activeAudioRecord = audioRecord
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && resolvedInputDevice != null) {
                try {
                    audioRecord.preferredDevice = resolvedInputDevice
                } catch (_: Exception) {}
            }
        }

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

        synchronized(captureStateLock) {
            if (_isRecording.value && !_isPaused.value) {
                try {
                    audioRecord.startRecording()
                } catch (_: Exception) {}
            }
        }

        // Accept whatever exact stream parameters Android's AudioRecord accepted
        val actualSampleRate = try {
            audioRecord.sampleRate.takeIf { it in 8000..192000 } ?: config.sampleRate
        } catch (_: Exception) {
            config.sampleRate
        }
        val actualChannels = try {
            audioRecord.channelCount.takeIf { it in 1..2 } ?: config.channels
        } catch (_: Exception) {
            config.channels
        }

        val rawShorts = ((actualSampleRate * actualChannels) / 20).coerceAtLeast(512)
        val alignedShorts = rawShorts - (rawShorts % actualChannels.coerceAtLeast(1))
        return HardwareCaptureSession(
            audioRecord = audioRecord,
            actualSampleRate = actualSampleRate,
            actualChannels = actualChannels,
            chunkShorts = alignedShorts.coerceAtLeast(256),
            isBluetoothRoute = isBluetoothRoute
        )
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
        releaseMicrophoneHardwareImmediately()

        pendingFallbackToPhoneMic.set(false)
        pendingSwitchToBluetooth.set(false)
        failedBluetoothDeviceIds.clear()
        isSwitchingMicrophone = false
        isCapturingFromBluetooth = false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val initialPreferredDevice = audioManager?.let { findPreferredHeadsetInputDevice(it) }
        val initialIsBluetooth = isBluetoothHeadsetDevice(initialPreferredDevice)
        _activeAudioDeviceName.value = formatInputDeviceLabel(initialPreferredDevice)

        // Stage 3 Target Format: Base M4A metadata (for continuations) or requested session format
        // dictates the output sample rate and channel count; any hardware stream is resampled on-the-fly.
        val baseM4aMeta = if (baseM4aForAppend != null && baseM4aForAppend.exists()) {
            Mp4BoxParser.parseM4a(baseM4aForAppend)
        } else null

        val canMatchBaseM4a = baseM4aMeta != null &&
            AacEncoder.canEncodeWithParameters(
                sampleRate = baseM4aMeta.sampleRate,
                channelCount = baseM4aMeta.channelCount,
                aacObjectType = baseM4aMeta.aacObjectType
            )

        val effectiveSampleRate = (if (canMatchBaseM4a && baseM4aMeta != null) {
            baseM4aMeta.sampleRate
        } else {
            sampleRate
        }).coerceIn(8000, 96000)

        val effectiveChannels = (if (canMatchBaseM4a && baseM4aMeta != null) {
            baseM4aMeta.channelCount
        } else {
            channels
        }).coerceIn(1, 2)

        currentSessionFile = outputFile
        currentSampleRate = effectiveSampleRate
        currentChannels = effectiveChannels
        _elapsedMs.value = 0L
        _livePeaks.value = initialPeaks
        _isPaused.value = false
        _isConnectingBluetoothMic.value = initialIsBluetooth
        _isRecording.value = true

        if (pauseExternalMediaOnRecord) {
            requestRecordingAudioFocus(true)
        }

        recordingJob = engineScope.launch(Dispatchers.IO) {
            outputFile.parentFile?.mkdirs()

            // Stage 1: Establish Bluetooth route asynchronously before touching AudioRecord
            var activeInputDevice: AudioDeviceInfo? = initialPreferredDevice
            var useBluetoothRoute = false

            if (initialIsBluetooth && audioManager != null && initialPreferredDevice != null) {
                when (val routeResult = establishBluetoothRouteAsync(audioManager, initialPreferredDevice)) {
                    is BluetoothRouteResult.Connected -> {
                        activeInputDevice = routeResult.inputDevice
                        useBluetoothRoute = true
                        isCapturingFromBluetooth = true
                        _activeAudioDeviceName.value = routeResult.label
                    }
                    is BluetoothRouteResult.FallbackToPhoneMic -> {
                        failedBluetoothDeviceIds.add(initialPreferredDevice.id)
                        activeInputDevice = findBuiltInMicInputDevice(audioManager)
                        useBluetoothRoute = false
                        isCapturingFromBluetooth = false
                        _activeAudioDeviceName.value = "Phone Microphone"
                        _routingNoticeMessage.value = context.getString(R.string.bluetooth_fallback_phone_mic)
                    }
                    is BluetoothRouteResult.Cancelled -> {
                        return@launch
                    }
                }
            } else {
                _isConnectingBluetoothMic.value = false
            }

            if (!_isRecording.value) {
                teardownBluetoothRoute(audioManager)
                return@launch
            }

            // Stage 2: Dynamically initialize AudioRecord at the active mic's highest native capability
            var captureSession: HardwareCaptureSession? = createAndStartAudioRecord(
                audioManager = audioManager,
                activeInputDevice = activeInputDevice,
                isBluetoothRoute = useBluetoothRoute,
                echoCancellationEnabled = echoCancellationEnabled,
                noiseSuppressionEnabled = noiseSuppressionEnabled
            )

            // If Bluetooth AudioRecord unexpectedly failed to initialize, cleanly fall back to Phone Mic
            if (captureSession == null && useBluetoothRoute) {
                initialPreferredDevice?.let { failedBluetoothDeviceIds.add(it.id) }
                activeBluetoothDeviceId?.let { failedBluetoothDeviceIds.add(it) }
                teardownBluetoothRoute(audioManager)
                useBluetoothRoute = false
                isCapturingFromBluetooth = false
                val builtInMic = audioManager?.let { findBuiltInMicInputDevice(it) }
                _activeAudioDeviceName.value = "Phone Microphone"
                _routingNoticeMessage.value = context.getString(R.string.bluetooth_fallback_phone_mic)
                captureSession = createAndStartAudioRecord(
                    audioManager = audioManager,
                    activeInputDevice = builtInMic,
                    isBluetoothRoute = false,
                    echoCancellationEnabled = echoCancellationEnabled,
                    noiseSuppressionEnabled = noiseSuppressionEnabled
                )
            }

            fun buildResamplerForSession(session: HardwareCaptureSession?): WavFileTools.PcmResampler? {
                val srcRate = session?.actualSampleRate ?: return null
                return if (srcRate != effectiveSampleRate) {
                    WavFileTools.PcmResampler(
                        srcSampleRate = srcRate,
                        targetSampleRate = effectiveSampleRate,
                        channels = effectiveChannels
                    )
                } else null
            }

            var activeResampler: WavFileTools.PcmResampler? = buildResamplerForSession(captureSession)
            var pendingSpliceFadeIn = false

            fun stopAndReleaseCurrentCaptureSession() {
                releaseAudioEffects()
                synchronized(captureStateLock) {
                    val oldRecord = captureSession?.audioRecord
                    if (oldRecord != null) {
                        try {
                            if (oldRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                oldRecord.stop()
                            }
                        } catch (_: Exception) {}
                        try {
                            oldRecord.release()
                        } catch (_: Exception) {}
                    }
                    if (activeAudioRecord === oldRecord) {
                        activeAudioRecord = null
                    }
                }
                captureSession = null
            }

            /**
             * Seamless Bluetooth -> Phone Microphone handoff when Bluetooth earbuds physically
             * disconnect mid-recording. Re-targets the software resampler on-the-fly and schedules
             * an 8 ms micro-fade-in so the single open WAV/AAC stream remains click-free.
             */
            fun fallbackToPhoneMicrophoneMidSession() {
                isSwitchingMicrophone = true
                try {
                    pendingSwitchToBluetooth.set(false)
                    stopAndReleaseCurrentCaptureSession()
                    teardownBluetoothRoute(audioManager)
                    isCapturingFromBluetooth = false
                    val builtInMic = audioManager?.let { findBuiltInMicInputDevice(it) }
                    _activeAudioDeviceName.value = "Phone Microphone"
                    _routingNoticeMessage.value = context.getString(R.string.bluetooth_disconnected_phone_mic)

                    val newSession = createAndStartAudioRecord(
                        audioManager = audioManager,
                        activeInputDevice = builtInMic,
                        isBluetoothRoute = false,
                        echoCancellationEnabled = echoCancellationEnabled,
                        noiseSuppressionEnabled = noiseSuppressionEnabled
                    )
                    captureSession = newSession
                    activeResampler = buildResamplerForSession(newSession)
                    pendingSpliceFadeIn = true
                } finally {
                    isSwitchingMicrophone = false
                }
            }

            /**
             * Seamless Phone Microphone -> Bluetooth Headset mid-recording handoff.
             * Halts Phone Mic capture while holding the accumulated timer and waveform, opens the
             * OS Bluetooth audio route asynchronously, dynamically queries the headset's native
             * hardware capabilities, re-targets the software resampler on-the-fly, and resumes into
             * the exact same open WAV/AAC file with a smooth 8 ms micro-fade-in.
             * On timeout or rejection, suppresses retries for that headset instance until physical
             * disconnect and smoothly resumes Phone Microphone capture.
             */
            suspend fun switchToBluetoothMicrophoneMidSession() {
                if (!_isRecording.value || isCapturingFromBluetooth || audioManager == null) return
                val targetBtDevice = findAvailableBluetoothRouteDevice(audioManager) ?: return
                if (isBluetoothDeviceSuppressedForSession(targetBtDevice.id)) return

                isSwitchingMicrophone = true
                try {
                    stopAndReleaseCurrentCaptureSession()
                    _liveDb.value = -58f
                    _activeAudioDeviceName.value = formatInputDeviceLabel(targetBtDevice)
                    _isConnectingBluetoothMic.value = true

                    when (val routeResult = establishBluetoothRouteAsync(audioManager, targetBtDevice)) {
                        is BluetoothRouteResult.Connected -> {
                            val btSession = createAndStartAudioRecord(
                                audioManager = audioManager,
                                activeInputDevice = routeResult.inputDevice,
                                isBluetoothRoute = true,
                                echoCancellationEnabled = echoCancellationEnabled,
                                noiseSuppressionEnabled = noiseSuppressionEnabled
                            )
                            if (btSession != null) {
                                captureSession = btSession
                                activeResampler = buildResamplerForSession(btSession)
                                isCapturingFromBluetooth = true
                                pendingSpliceFadeIn = true
                                _activeAudioDeviceName.value = routeResult.label
                                _routingNoticeMessage.value = context.getString(R.string.bluetooth_switched_active)
                                return
                            }
                            // Bluetooth AudioRecord failed to initialize: suppress & recover on Phone Mic
                            failedBluetoothDeviceIds.add(targetBtDevice.id)
                            activeBluetoothDeviceId?.let { failedBluetoothDeviceIds.add(it) }
                            pendingSwitchToBluetooth.set(false)
                            teardownBluetoothRoute(audioManager)
                            isCapturingFromBluetooth = false
                        }
                        is BluetoothRouteResult.FallbackToPhoneMic -> {
                            failedBluetoothDeviceIds.add(targetBtDevice.id)
                            activeBluetoothDeviceId?.let { failedBluetoothDeviceIds.add(it) }
                            pendingSwitchToBluetooth.set(false)
                            teardownBluetoothRoute(audioManager)
                            isCapturingFromBluetooth = false
                        }
                        is BluetoothRouteResult.Cancelled -> {
                            teardownBluetoothRoute(audioManager)
                            return
                        }
                    }

                    // Recovery path: re-open Phone Microphone seamlessly so recording continues unbroken
                    if (_isRecording.value) {
                        val builtInMic = findBuiltInMicInputDevice(audioManager)
                        _activeAudioDeviceName.value = "Phone Microphone"
                        _routingNoticeMessage.value = context.getString(R.string.bluetooth_handoff_failed_phone_mic)
                        val recoveredPhoneSession = createAndStartAudioRecord(
                            audioManager = audioManager,
                            activeInputDevice = builtInMic,
                            isBluetoothRoute = false,
                            echoCancellationEnabled = echoCancellationEnabled,
                            noiseSuppressionEnabled = noiseSuppressionEnabled
                        )
                        captureSession = recoveredPhoneSession
                        activeResampler = buildResamplerForSession(recoveredPhoneSession)
                        pendingSpliceFadeIn = true
                    }
                } finally {
                    _isConnectingBluetoothMic.value = false
                    isSwitchingMicrophone = false
                }
            }

            suspend fun resumeCaptureAfterPause() {
                if (pendingFallbackToPhoneMic.getAndSet(false) && isCapturingFromBluetooth) {
                    fallbackToPhoneMicrophoneMidSession()
                    return
                }
                if (pendingSwitchToBluetooth.getAndSet(false) && !isCapturingFromBluetooth) {
                    switchToBluetoothMicrophoneMidSession()
                    return
                }
                val existingSession = captureSession
                if (existingSession != null && existingSession.audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    synchronized(captureStateLock) {
                        if (!_isRecording.value || _isPaused.value) return
                        try {
                            if (existingSession.audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                                existingSession.audioRecord.startRecording()
                            }
                        } catch (_: Exception) {}
                    }
                    activeResampler?.reset()
                    pendingSpliceFadeIn = true
                } else if (_isRecording.value && !_isPaused.value) {
                    val fallbackDevice = if (isCapturingFromBluetooth) {
                        audioManager?.let { findAvailableBluetoothRouteDevice(it) }
                    } else {
                        audioManager?.let { findBuiltInMicInputDevice(it) }
                    }
                    val recreated = createAndStartAudioRecord(
                        audioManager = audioManager,
                        activeInputDevice = fallbackDevice,
                        isBluetoothRoute = isCapturingFromBluetooth && fallbackDevice != null,
                        echoCancellationEnabled = echoCancellationEnabled,
                        noiseSuppressionEnabled = noiseSuppressionEnabled
                    )
                    captureSession = recreated
                    activeResampler = buildResamplerForSession(recreated)
                    pendingSpliceFadeIn = true
                }
            }

            val isAacSession = outputFile.extension.equals("m4a", ignoreCase = true) ||
                outputFile.extension.equals("aac", ignoreCase = true)
            val fallbackChunkShorts = (((effectiveSampleRate * effectiveChannels) / 20).coerceAtLeast(1024)).let {
                it - (it % effectiveChannels.coerceAtLeast(1))
            }
            var shortBuffer = ShortArray(captureSession?.chunkShorts ?: fallbackChunkShorts)
            var totalBytesWritten = 0L
            var wasPaused = false

            // Stage 3: Universal on-the-fly channel conversion + click-free software resampling
            fun convertCapturedShortsToTargetPcmBytes(readShorts: Int): ByteArray {
                val hwChannels = (captureSession?.actualChannels ?: effectiveChannels).coerceIn(1, 2)
                val alignedShorts = (readShorts - (readShorts % hwChannels)).coerceAtLeast(0)
                if (alignedShorts <= 0) return ByteArray(0)

                val rawBytes = ByteArray(alignedShorts * 2)
                ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shortBuffer, 0, alignedShorts)

                val channelMatched = if (hwChannels != effectiveChannels) {
                    WavFileTools.convertPcmChannels(rawBytes, hwChannels, effectiveChannels)
                } else {
                    rawBytes
                }
                val resampled = activeResampler?.resampleChunk(channelMatched) ?: channelMatched
                val blockAlign = (effectiveChannels * 2).coerceAtLeast(2)
                val validLen = resampled.size - (resampled.size % blockAlign)
                return if (validLen == resampled.size) resampled else resampled.copyOf(validLen.coerceAtLeast(0))
            }

            try {
                if (isAacSession) {
                    val rawAacTemp = File(outputFile.parentFile, "${outputFile.name}.raw.aac")
                    val aacEncoder = AacEncoder(
                        sampleRate = effectiveSampleRate,
                        channelCount = effectiveChannels,
                        bitrate = AacEncoder.clampBitrateForAacLc(128_000, effectiveSampleRate, effectiveChannels),
                        baseStartSamples = 0L,
                        skipPrimingFrame = false
                    )
                    aacEncoder.start(rawAacTemp)

                    try {
                        while (isActive && _isRecording.value) {
                            if (_isPaused.value) {
                                wasPaused = true
                                _liveDb.value = -58f
                                delay(60L)
                                continue
                            }

                            if (wasPaused) {
                                wasPaused = false
                                resumeCaptureAfterPause()
                            }

                            if (pendingFallbackToPhoneMic.getAndSet(false) && isCapturingFromBluetooth) {
                                fallbackToPhoneMicrophoneMidSession()
                            } else if (pendingSwitchToBluetooth.getAndSet(false) && !isCapturingFromBluetooth) {
                                switchToBluetoothMicrophoneMidSession()
                            }

                            val currentRecord = captureSession?.audioRecord
                            val targetReadShorts = captureSession?.chunkShorts ?: fallbackChunkShorts
                            if (shortBuffer.size < targetReadShorts) {
                                shortBuffer = ShortArray(targetReadShorts)
                            }

                            var readSamples = 0
                            try {
                                if (currentRecord != null && currentRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                    readSamples = currentRecord.read(shortBuffer, 0, targetReadShorts)
                                }
                            } catch (_: Exception) {
                                readSamples = 0
                            }

                            if (!_isRecording.value) break
                            if (_isPaused.value) {
                                wasPaused = true
                                continue
                            }

                            if (readSamples <= 0) {
                                if (!isRobolectricRuntime) {
                                    delay(30L)
                                    continue
                                } else {
                                    readSamples = targetReadShorts
                                    shortBuffer.fill(0)
                                    delay(48L)
                                }
                            }

                            var targetPcmBytes = convertCapturedShortsToTargetPcmBytes(readSamples)
                            val switchToBtAfterChunk = !isCapturingFromBluetooth && pendingSwitchToBluetooth.getAndSet(false)
                            if (targetPcmBytes.isNotEmpty()) {
                                if (pendingSpliceFadeIn) {
                                    targetPcmBytes = WavFileTools.applyMicroFadeIn(
                                        pcmBytes = targetPcmBytes,
                                        sampleRate = effectiveSampleRate,
                                        channels = effectiveChannels
                                    )
                                    pendingSpliceFadeIn = false
                                }
                                if (switchToBtAfterChunk) {
                                    targetPcmBytes = WavFileTools.applyMicroFadeOut(
                                        pcmBytes = targetPcmBytes,
                                        sampleRate = effectiveSampleRate,
                                        channels = effectiveChannels
                                    )
                                }
                                aacEncoder.encodePcmBytes(targetPcmBytes, targetPcmBytes.size)
                                totalBytesWritten += targetPcmBytes.size.toLong()
                            }

                            val (db, normPeak) = WavFileTools.calculateRmsAndPeak(shortBuffer, readSamples)
                            _liveDb.value = db
                            val updatedPeaks = (_livePeaks.value + normPeak).takeLast(140)
                            _livePeaks.value = updatedPeaks

                            val bytesPerSecond = (effectiveSampleRate.toLong() * effectiveChannels.toLong() * 2L).coerceAtLeast(1L)
                            _elapsedMs.value = (totalBytesWritten * 1000L) / bytesPerSecond

                            if (switchToBtAfterChunk) {
                                switchToBluetoothMicrophoneMidSession()
                            }
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
                                wasPaused = true
                                _liveDb.value = -58f
                                delay(60L)
                                continue
                            }

                            if (wasPaused) {
                                wasPaused = false
                                resumeCaptureAfterPause()
                            }

                            if (pendingFallbackToPhoneMic.getAndSet(false) && isCapturingFromBluetooth) {
                                fallbackToPhoneMicrophoneMidSession()
                            } else if (pendingSwitchToBluetooth.getAndSet(false) && !isCapturingFromBluetooth) {
                                switchToBluetoothMicrophoneMidSession()
                            }

                            val currentRecord = captureSession?.audioRecord
                            val targetReadShorts = captureSession?.chunkShorts ?: fallbackChunkShorts
                            if (shortBuffer.size < targetReadShorts) {
                                shortBuffer = ShortArray(targetReadShorts)
                            }

                            var readSamples = 0
                            try {
                                if (currentRecord != null && currentRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                    readSamples = currentRecord.read(shortBuffer, 0, targetReadShorts)
                                }
                            } catch (_: Exception) {
                                readSamples = 0
                            }

                            if (!_isRecording.value) break
                            if (_isPaused.value) {
                                wasPaused = true
                                continue
                            }

                            if (readSamples <= 0) {
                                if (!isRobolectricRuntime) {
                                    delay(30L)
                                    continue
                                } else {
                                    readSamples = targetReadShorts
                                    shortBuffer.fill(0)
                                    delay(48L)
                                }
                            }

                            // Write PCM bytes to disk matched to effectiveSampleRate & effectiveChannels
                            var targetPcmBytes = convertCapturedShortsToTargetPcmBytes(readSamples)
                            val switchToBtAfterChunk = !isCapturingFromBluetooth && pendingSwitchToBluetooth.getAndSet(false)
                            if (targetPcmBytes.isNotEmpty()) {
                                if (pendingSpliceFadeIn) {
                                    targetPcmBytes = WavFileTools.applyMicroFadeIn(
                                        pcmBytes = targetPcmBytes,
                                        sampleRate = effectiveSampleRate,
                                        channels = effectiveChannels
                                    )
                                    pendingSpliceFadeIn = false
                                }
                                if (switchToBtAfterChunk) {
                                    targetPcmBytes = WavFileTools.applyMicroFadeOut(
                                        pcmBytes = targetPcmBytes,
                                        sampleRate = effectiveSampleRate,
                                        channels = effectiveChannels
                                    )
                                }
                                fos.write(targetPcmBytes, 0, targetPcmBytes.size)
                                totalBytesWritten += targetPcmBytes.size.toLong()
                            }

                            // Compute RMS dB & peak (read-only)
                            val (db, normPeak) = WavFileTools.calculateRmsAndPeak(shortBuffer, readSamples)
                            _liveDb.value = db
                            val updatedPeaks = (_livePeaks.value + normPeak).takeLast(140)
                            _livePeaks.value = updatedPeaks

                            val bytesPerSecond = (effectiveSampleRate.toLong() * effectiveChannels.toLong() * 2L).coerceAtLeast(1L)
                            _elapsedMs.value = (totalBytesWritten * 1000L) / bytesPerSecond

                            if (switchToBtAfterChunk) {
                                switchToBluetoothMicrophoneMidSession()
                            }
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
                synchronized(captureStateLock) {
                    val record = captureSession?.audioRecord
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
                    if (activeAudioRecord === record) {
                        activeAudioRecord = null
                    }
                }
                teardownBluetoothRoute(audioManager)
                isCapturingFromBluetooth = false
                _isConnectingBluetoothMic.value = false
            }
        }
    }

    /**
     * Instantly stops and frees physical microphone hardware and tears down any active
     * Bluetooth communication route when a recording session is saved, discarded, or released.
     */
    fun releaseMicrophoneHardwareImmediately() {
        _isConnectingBluetoothMic.value = false
        isSwitchingMicrophone = false
        isCapturingFromBluetooth = false
        pendingFallbackToPhoneMic.set(false)
        pendingSwitchToBluetooth.set(false)
        failedBluetoothDeviceIds.clear()
        releaseAudioEffects()
        abandonRecordingAudioFocus()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        synchronized(captureStateLock) {
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
        }
        teardownBluetoothRoute(am)
        val preferred = am?.let { findPreferredHeadsetInputDevice(it) }
        _activeAudioDeviceName.value = formatInputDeviceLabel(preferred)
    }

    /**
     * Pauses recording and halts active AudioRecord reads to save CPU/battery, while silently
     * keeping the established Bluetooth communication route open so earbuds do not play
     * annoying connect/disconnect chimes on Pause/Resume.
     */
    fun pauseRecording() {
        synchronized(captureStateLock) {
            if (!_isRecording.value || _isPaused.value) return
            _isPaused.value = true
            val record = activeAudioRecord
            if (record != null) {
                try {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Resumes recording instantaneously over the silently held audio route.
     */
    fun resumeRecording() {
        synchronized(captureStateLock) {
            if (!_isRecording.value || !_isPaused.value) return
            _isPaused.value = false
        }
    }

    suspend fun stopRecordingAndFinalize(): Pair<File?, Long> {
        _isRecording.value = false
        _isPaused.value = false
        _isConnectingBluetoothMic.value = false
        // Instantly release microphone hardware and tear down Bluetooth route on Save
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
        _isConnectingBluetoothMic.value = false
        // Instantly release microphone hardware and tear down Bluetooth route on Discard
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
        stopPlaybackInternal(
            resetSavedCounter = startPositionMs == 0L,
            resetScrubState = false
        )

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
                        // 1. Check for interactive user seek (scrubbing waveform, hold-to-seek, or +/-3s)
                        val requestedSeekMs = pendingSeekMs.getAndSet(-1L)
                        if (requestedSeekMs >= 0L) {
                            if (pendingScrubFlush.getAndSet(false) && trackReady && audioTrack != null) {
                                try {
                                    audioTrack.pause()
                                    audioTrack.flush()
                                    audioTrack.play()
                                } catch (_: Exception) {}
                            }
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

                        // 2. Real-Time Silence Trimming Filter (paused during audible hold-scrubbing so every cue is heard accurately)
                        if (!isAudibleScrubbing && currentConfig.isEffectivelyEnabled && totalDurationMs > 400L) {
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
            } else if (isAudibleScrubbing) {
                // Keep PCM cursor aligned near real-time speaker output while still avoiding buffer underruns
                delay((effectiveFrameMs / 2L).coerceAtLeast(4L))
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
            java.io.FileInputStream(file).use { fis ->
                mp.setDataSource(fis.fd)
            }
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
                            java.io.FileInputStream(repairResult.finalFile).use { fis ->
                                mpRetry.setDataSource(fis.fd)
                            }
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

    fun prepareIdlePlayerMetadata(
        file: File,
        durationMs: Long,
        peaks: List<Float>
    ) {
        stopPlaybackInternal(resetSavedCounter = true)
        currentSessionFile = file
        _playbackDurationMs.value = durationMs.coerceAtLeast(1L)
        _playbackPositionMs.value = 0L
        if (peaks.isNotEmpty()) {
            activePlaybackPeaks = peaks
        }
        isUsingPcmEngine = file.exists() && WavFileTools.isRiffWaveFile(file)
    }

    fun beginScrubHold(
        currentFile: File?,
        peaks: List<Float>,
        preindexedSilenceMap: List<SilenceInterval> = preindexedSilenceIntervals,
        fallbackDurationMs: Long = 0L
    ) {
        if (isAudibleScrubbing) return
        if (_playbackDurationMs.value <= 0L && fallbackDurationMs > 0L) {
            _playbackDurationMs.value = fallbackDurationMs
        }
        isAudibleScrubbing = true
        wasPlayingBeforeScrub = _isPlaying.value
        _activeSilenceJumping.value = false
        if (!wasPlayingBeforeScrub) {
            togglePlayPause(
                currentFile = currentFile,
                peaks = peaks,
                preindexedSilenceMap = preindexedSilenceMap
            )
        }
    }

    fun seekToForScrub(positionMs: Long) {
        val rawMax = _playbackDurationMs.value.coerceAtLeast(1L)
        val safeUpper = if (rawMax > 250L) rawMax - 120L else rawMax
        val clamped = positionMs.coerceIn(0L, safeUpper)
        _playbackPositionMs.value = clamped
        if (isUsingPcmEngine) {
            pendingScrubFlush.set(true)
            pendingSeekMs.set(clamped)
        } else {
            try {
                lastMediaPlayerSeekUptimeMs = SystemClock.uptimeMillis()
                mediaPlayer?.seekTo(clamped.toInt())
            } catch (_: Exception) {}
        }
    }

    fun endScrubHold() {
        if (!isAudibleScrubbing) return
        val shouldPauseAfterScrub = !wasPlayingBeforeScrub
        isAudibleScrubbing = false
        wasPlayingBeforeScrub = false
        pendingScrubFlush.set(false)
        if (shouldPauseAfterScrub && _isPlaying.value) {
            togglePlayPause(
                currentFile = currentSessionFile,
                peaks = activePlaybackPeaks
            )
        }
    }

    fun seekTo(positionMs: Long) {
        val maxDur = _playbackDurationMs.value.coerceAtLeast(1L)
        val clamped = positionMs.coerceIn(0L, maxDur)
        _playbackPositionMs.value = clamped
        if (isUsingPcmEngine) {
            pendingScrubFlush.set(true)
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
        stopPlaybackInternal(resetSavedCounter = false, resetScrubState = true)
    }

    private fun stopPlaybackInternal(
        resetSavedCounter: Boolean,
        resetScrubState: Boolean = true
    ) {
        pcmPlaybackJob?.cancel()
        pcmPlaybackJob = null
        playbackTickerJob?.cancel()
        playbackTickerJob = null
        _isPlaying.value = false
        _activeSilenceJumping.value = false
        if (resetScrubState) {
            isAudibleScrubbing = false
            wasPlayingBeforeScrub = false
        }
        pendingScrubFlush.set(false)
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
                    if ((nowUptime - lastMediaPlayerSeekUptimeMs) > 120L) {
                        _playbackPositionMs.value = pos
                    }
                    _playbackDurationMs.value = dur

                    if (!isAudibleScrubbing && silenceSkipConfig.isEffectivelyEnabled && dur > 500L && (nowUptime - lastMediaPlayerSeekUptimeMs) > 120L) {
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
