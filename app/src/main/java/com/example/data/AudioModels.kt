package com.example.data

import androidx.compose.runtime.Immutable
import java.util.Locale

enum class AudioFormatOption(
    val id: String,
    val label: String,
    val extension: String,
    val description: String,
    val defaultBitrate: Int
) {
    M4A("M4A", "M4A", "m4a", "Balanced studio quality & compact file size", 192_000),
    WAV("WAV", "WAV", "wav", "Uncompressed studio lossless quality", 1_411_200),
    FLAC("FLAC", "FLAC", "flac", "Lossless compression for archiving", 760_000);

    companion object {
        fun fromId(id: String): AudioFormatOption =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: M4A
    }
}

enum class RecordingStatus(val label: String) {
    READY("Ready"),
    RECORDING("Recording"),
    PAUSED("Paused"),
    PROCESSING("Processing"),
    SAVING("Saving"),
    ERROR("Error")
}

enum class PlaybackSpeedOption(val multiplier: Float, val label: String) {
    SPEED_0_5(0.5f, "0.5×"),
    SPEED_0_75(0.75f, "0.75×"),
    SPEED_1_0(1.0f, "1×"),
    SPEED_1_25(1.25f, "1.25×"),
    SPEED_1_5(1.5f, "1.5×"),
    SPEED_2_0(2.0f, "2×"),
    SPEED_3_0(3.0f, "3×")
}

enum class StorageDestination(
    val id: String,
    val label: String,
    val shortTitle: String,
    val subtitle: String
) {
    LOCAL(
        id = "LOCAL",
        label = "Local Device Storage",
        shortTitle = "Local",
        subtitle = "Recordings are saved directly to internal device storage"
    ),
    CLOUD(
        id = "CLOUD",
        label = "Google Drive Cloud",
        shortTitle = "Cloud",
        subtitle = "Recordings are uploaded and stored in your selected Google Drive folder"
    ),
    TRASH(
        id = "TRASH",
        label = "Recycle Bin",
        shortTitle = "Trash",
        subtitle = "Deleted recordings waiting to be restored or permanently removed"
    );

    companion object {
        fun fromId(id: String): StorageDestination =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: LOCAL
    }
}

enum class LibraryFilterChip(val label: String) {
    ALL("All"),
    M4A("M4A"),
    WAV("WAV"),
    FLAC("FLAC")
}

enum class LibrarySortOption(val id: String, val label: String) {
    NAME("NAME", "By Name"),
    NUMBER_ID("NUMBER_ID", "By Number/ID"),
    DATE("DATE", "By Date"),
    FORMAT("FORMAT", "By Format (M4A, WAV, FLAC)");

    companion object {
        fun fromId(id: String): LibrarySortOption =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: DATE
    }
}

enum class LibrarySortDirection(val id: String, val label: String) {
    ASCENDING("ASCENDING", "Ascending (↑)"),
    DESCENDING("DESCENDING", "Descending (↓)");

    companion object {
        fun fromId(id: String): LibrarySortDirection =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: DESCENDING
    }
}

@Immutable
data class DriveFolderItem(
    val id: String,
    val name: String,
    val parentId: String = "root",
    val path: String = "/My Drive/$name",
    val modifiedTime: String = "",
    val isAppCreated: Boolean = true
)

@Immutable
data class DriveQuotaInfo(
    val usedBytes: Long = 0L,
    val limitBytes: Long = 0L,
    val userDisplayName: String = "",
    val userEmail: String = ""
)

@Immutable
data class SilenceInterval(
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)
}

@Immutable
data class SilenceHysteresisParams(
    val openThresholdDb: Float,
    val holdThresholdDb: Float,
    val noiseFloorDb: Float,
    val minSilenceDurationMs: Int,
    val resumePaddingMs: Int
)

@Immutable
data class SilenceSkipConfig(
    val enabled: Boolean = false,
    val smartAutoMode: Boolean = true,      // Smart Silence Skip (Adaptive VAD Mode - Pure Timeline Seeking)
    val manualModeEnabled: Boolean = false, // Manual Silence Skip toggle in Settings
    val thresholdDb: Float = -35f,          // -60 dB to -10 dB
    val minSilenceDurationMs: Int = 400,    // 100 ms to 1200 ms
    val resumePaddingMs: Int = 140          // 0 ms to 300 ms (pre/post skip safety buffer)
) {
    /**
     * Master Settings gate: true when either Smart Silence Skip or Manual Silence Skip is turned ON in Settings.
     * When both are OFF in Settings, the Silence Skip action button is disabled and grayed out in Recorder and Player.
     */
    val isConfiguredInSettings: Boolean
        get() = smartAutoMode || manualModeEnabled

    /**
     * Effective runtime state: Silence Skip only operates when enabled on the transport AND configured in Settings.
     */
    val isEffectivelyEnabled: Boolean
        get() = enabled && isConfiguredInSettings

    /**
     * Resolves Dual-Threshold Hysteresis & Adaptive Noise Floor parameters:
     * - Estimates the recording's baseline ambient noise floor (`noiseFloorDb`).
     * - Computes a primary **Open (Onset) Threshold** to enter active speech and a secondary
     *   **Hold (Sustain) Threshold** 10–12 dB lower (down to just above the noise floor) to stay
     *   in active speech when a speaker lowers their voice mid-sentence.
     * - In **Smart Silence Skip**, adapts thresholds automatically to the recording's noise floor
     *   and vocal peak profile.
     * - In **Manual Silence Skip**, uses the user's exact `thresholdDb`, `minSilenceDurationMs`,
     *   and `resumePaddingMs` slider values while applying the 11 dB sustain hysteresis band.
     */
    fun resolveHysteresisParameters(
        peaks: List<Float> = emptyList(),
        estimatedNoiseFloorDb: Float? = null
    ): SilenceHysteresisParams {
        val sorted = if (peaks.isNotEmpty()) peaks.sorted() else emptyList()
        val noiseFloorNorm = if (sorted.isNotEmpty()) {
            sorted[(sorted.size * 0.18f).toInt().coerceIn(0, sorted.lastIndex)]
        } else {
            0.05f
        }
        val speechPeakNorm = if (sorted.isNotEmpty()) {
            sorted[(sorted.size * 0.82f).toInt().coerceIn(0, sorted.lastIndex)]
        } else {
            0.55f
        }
        val dynamicRangeNorm = (speechPeakNorm - noiseFloorNorm).coerceAtLeast(0.05f)

        val baselineNoiseFloorDb = (estimatedNoiseFloorDb ?: AudioFormatUtils.normalizedPeakToDb(noiseFloorNorm))
            .coerceIn(-58f, -24f)
        val speechPeakDb = AudioFormatUtils.normalizedPeakToDb(speechPeakNorm).coerceAtLeast(baselineNoiseFloorDb + 8f)
        val dbSpan = (speechPeakDb - baselineNoiseFloorDb).coerceAtLeast(8f)

        if (!smartAutoMode) {
            val manualOpenDb = thresholdDb.coerceIn(-60f, -10f)
            // Sustain (Hold) threshold is 11 dB lower than Open threshold, bounded just above the ambient noise floor
            val manualHoldDb = (manualOpenDb - 11f)
                .coerceAtLeast(baselineNoiseFloorDb + 2.0f)
                .coerceAtMost(manualOpenDb - 3.5f)
            return SilenceHysteresisParams(
                openThresholdDb = manualOpenDb,
                holdThresholdDb = manualHoldDb,
                noiseFloorDb = baselineNoiseFloorDb,
                minSilenceDurationMs = minSilenceDurationMs.coerceIn(100, 1200),
                resumePaddingMs = resumePaddingMs.coerceIn(0, 300)
            )
        }

        // Smart Silence Skip: Adaptive onset threshold above noise floor + 11 dB lower sustain threshold
        val adaptiveOpenDb = (baselineNoiseFloorDb + (dbSpan * 0.34f).coerceIn(5.0f, 14.0f))
            .coerceIn(-44f, -24f)
        val adaptiveHoldDb = (adaptiveOpenDb - 11f)
            .coerceAtLeast(baselineNoiseFloorDb + 2.2f)
            .coerceAtMost(adaptiveOpenDb - 4.0f)
        val adaptiveSafetyBufferMs = (130 + (dynamicRangeNorm * 30f).toInt()).coerceIn(120, 160)

        return SilenceHysteresisParams(
            openThresholdDb = adaptiveOpenDb,
            holdThresholdDb = adaptiveHoldDb,
            noiseFloorDb = baselineNoiseFloorDb,
            minSilenceDurationMs = 340,
            resumePaddingMs = adaptiveSafetyBufferMs
        )
    }

    /**
     * Pure Timeline-Seeking VAD Resolver (ZERO Audio Gain / DSP Alteration):
     * Returns (openThresholdDb, minSilenceDurationMs, resumePaddingMs) for callers needing a triple.
     */
    fun resolveEffectiveParameters(peaks: List<Float> = emptyList()): Triple<Float, Int, Int> {
        val params = resolveHysteresisParameters(peaks)
        return Triple(params.openThresholdDb, params.minSilenceDurationMs, params.resumePaddingMs)
    }
}

@Immutable
data class RecorderSettings(
    val defaultFormat: AudioFormatOption = AudioFormatOption.M4A,
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val channels: Int = 1,
    val bitrate: Int = 192_000,
    val storageDirectoryLabel: String = "Internal Studio Vault (/files/recordings)",
    val localFolderName: String = "Recordings",
    val localFolderPath: String = "Default App Storage",
    val customStorageTreeUri: String = "",
    val defaultStorageDestination: StorageDestination = StorageDestination.LOCAL,
    val defaultPlaybackSpeed: Float = 1.0f,
    val silenceConfig: SilenceSkipConfig = SilenceSkipConfig(),
    val driveConnected: Boolean = false,
    val driveAccountEmail: String = "",
    val driveAccountName: String = "",
    val driveAccountPhotoUrl: String = "",
    val driveAccessToken: String = "",
    val driveFolderId: String = "root",
    val driveFolderName: String = "My Drive",
    val driveFolderPath: String = "/My Drive",
    val driveFolderUri: String = "",
    val driveAutoSync: Boolean = false,
    val lastCloudSyncMs: Long = 0L,
    val echoCancellationEnabled: Boolean = true,
    val noiseSuppressionEnabled: Boolean = true,
    val pauseExternalMediaOnRecord: Boolean = true
)

object AudioFormatUtils {
    fun formatDurationHMS(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun formatDurationPrecise(durationMs: Long): String {
        val clamped = durationMs.coerceAtLeast(0L)
        val totalSeconds = clamped / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val tenths = (clamped % 1000L) / 100L
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d.%d", hours, minutes, seconds, tenths)
        } else {
            String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
        }
    }

    fun formatDurationCompact(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    fun serializePeaks(peaks: List<Float>): String {
        if (peaks.isEmpty()) return ""
        val downsampled = if (peaks.size > 120) {
            val step = peaks.size.toFloat() / 120f
            List(120) { idx ->
                val srcIdx = (idx * step).toInt().coerceIn(0, peaks.lastIndex)
                peaks[srcIdx]
            }
        } else {
            peaks
        }
        return downsampled.joinToString(",") {
            String.format(Locale.US, "%.3f", it.coerceIn(0.02f, 1.0f))
        }
    }

    fun deserializePeaks(serialized: String): List<Float> {
        if (serialized.isBlank()) return emptyList()
        return serialized.split(",").mapNotNull { it.toFloatOrNull()?.coerceIn(0.02f, 1.0f) }
    }

    fun serializeBookmarks(bookmarks: List<Long>): String {
        if (bookmarks.isEmpty()) return ""
        return bookmarks.distinct().sorted().joinToString(",")
    }

    fun deserializeBookmarks(serialized: String): List<Long> {
        if (serialized.isBlank()) return emptyList()
        return serialized.split(",").mapNotNull { it.trim().toLongOrNull() }.distinct().sorted()
    }

    fun serializeSilenceMap(intervals: List<SilenceInterval>): String {
        if (intervals.isEmpty()) return ""
        return intervals
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
            .joinToString(",") { "${it.startMs}:${it.endMs}" }
    }

    fun deserializeSilenceMap(serialized: String): List<SilenceInterval> {
        if (serialized.isBlank()) return emptyList()
        return serialized.split(",").mapNotNull { token ->
            val parts = token.trim().split(":")
            if (parts.size == 2) {
                val start = parts[0].toLongOrNull()
                val end = parts[1].toLongOrNull()
                if (start != null && end != null && end > start && start >= 0L) {
                    SilenceInterval(startMs = start, endMs = end)
                } else {
                    null
                }
            } else {
                null
            }
        }.sortedBy { it.startMs }
    }

    fun dbToNormalizedPeak(db: Float): Float {
        val clampedDb = db.coerceIn(-60f, 0f)
        return Math.pow(((clampedDb + 60f) / 60f).toDouble(), 1.3).toFloat().coerceIn(0.04f, 1.0f)
    }

    fun normalizedPeakToDb(peak: Float): Float {
        val clampedPeak = peak.coerceIn(0.04f, 1.0f)
        val linear = Math.pow(clampedPeak.toDouble(), 1.0 / 1.3).toFloat()
        return (-60f + linear * 60f).coerceIn(-60f, 0f)
    }

    /**
     * Generates a Vocal-Aware Silence Map Index (`List<SilenceInterval>`) from normalized waveform
     * peaks using Dual-Threshold Hysteresis, Transient Click Rejection (< 60 ms), Intra-Phrase
     * Valley Bridging, and Symmetric Pre/Post-Roll (`Resume Buffer`) padding.
     */
    fun computeSilenceMapFromPeaks(
        peaks: List<Float>,
        durationMs: Long,
        config: SilenceSkipConfig
    ): List<SilenceInterval> {
        if (peaks.isEmpty() || durationMs <= 300L) return emptyList()
        val numBars = peaks.size
        val msPerBar = (durationMs.toFloat() / numBars.toFloat()).coerceAtLeast(1f)

        val hysteresis = config.resolveHysteresisParameters(peaks)
        val openPeak = dbToNormalizedPeak(hysteresis.openThresholdDb).coerceIn(0.05f, 0.70f)
        val holdPeak = dbToNormalizedPeak(hysteresis.holdThresholdDb).coerceIn(0.045f, openPeak)
        val noiseFloorPeak = dbToNormalizedPeak(hysteresis.noiseFloorDb).coerceIn(0.04f, holdPeak)
        val softVocalFloorPeak = (noiseFloorPeak + (holdPeak - noiseFloorPeak) * 0.45f).coerceIn(noiseFloorPeak, holdPeak)

        val effectiveMinDurationMs = hysteresis.minSilenceDurationMs
        val effectiveSafetyBufferMs = hysteresis.resumePaddingMs

        // 1. Reject brief transient clicks/pops lasting < 60 ms surrounded by silence
        val maxTransientBars = (60f / msPerBar).toInt().coerceIn(1, 3)
        val filteredPeaks = FloatArray(numBars) { peaks[it] }
        var i = 0
        while (i < numBars) {
            if (filteredPeaks[i] >= holdPeak) {
                val start = i
                while (i < numBars && filteredPeaks[i] >= holdPeak) i++
                val span = i - start
                val prevSilent = start == 0 || filteredPeaks[start - 1] <= softVocalFloorPeak
                val nextSilent = i >= numBars || filteredPeaks[i] <= softVocalFloorPeak
                if (span <= maxTransientBars && (span * msPerBar) < 65f && prevSilent && nextSilent) {
                    for (k in start until i) {
                        filteredPeaks[k] = noiseFloorPeak
                    }
                }
            } else {
                i++
            }
        }

        // 2. Dual-Threshold Hysteresis state machine (Open vs. Hold threshold + sustained soft speech recognition)
        val isSpeechBar = BooleanArray(numBars)
        var inSpeech = false
        val minSoftSpeechBars = (120f / msPerBar).toInt().coerceAtLeast(2)
        for (idx in 0 until numBars) {
            val amp = filteredPeaks[idx]
            if (!inSpeech) {
                if (amp >= openPeak) {
                    inSpeech = true
                } else if (amp >= holdPeak) {
                    // Recognize sustained soft speech onset even if it starts below the loud onset peak
                    var sustainedCount = 0
                    var look = idx
                    while (look < numBars && filteredPeaks[look] >= holdPeak && sustainedCount < minSoftSpeechBars) {
                        sustainedCount++
                        look++
                    }
                    if (sustainedCount >= minSoftSpeechBars) {
                        inSpeech = true
                    }
                }
            } else {
                // Remain locked in active speech while above the Hold (Sustain) threshold
                // or if only a single-bar dip occurs above the soft vocal floor
                val nextAmp = if (idx + 1 < numBars) filteredPeaks[idx + 1] else 0f
                inSpeech = amp >= holdPeak || (amp >= softVocalFloorPeak && nextAmp >= holdPeak)
            }
            isSpeechBar[idx] = inSpeech
        }

        // 3. Intra-Phrase Valley Bridging:
        // Bridge valleys between two active speech segments if the valley is shorter than Min Silence Duration
        // OR if the valley maintains continuous low-level vocal energy above the noise floor.
        var barIdx = 0
        while (barIdx < numBars) {
            if (!isSpeechBar[barIdx]) {
                val gapStart = barIdx
                while (barIdx < numBars && !isSpeechBar[barIdx]) barIdx++
                val gapEnd = barIdx
                val hasLeftSpeech = gapStart > 0 && isSpeechBar[gapStart - 1]
                val hasRightSpeech = gapEnd < numBars && isSpeechBar[gapEnd]
                if (hasLeftSpeech && hasRightSpeech) {
                    val gapDurationMs = (gapEnd - gapStart) * msPerBar
                    var sumAmp = 0f
                    var aboveNoiseCount = 0
                    for (k in gapStart until gapEnd) {
                        val a = filteredPeaks[k]
                        sumAmp += a
                        if (a >= softVocalFloorPeak) aboveNoiseCount++
                    }
                    val gapLen = (gapEnd - gapStart).coerceAtLeast(1)
                    val meanAmp = sumAmp / gapLen
                    val continuousLowVocal = meanAmp >= softVocalFloorPeak &&
                        aboveNoiseCount >= (gapLen * 0.65f).toInt().coerceAtLeast(1) &&
                        gapDurationMs <= (effectiveMinDurationMs * 2.2f)

                    if (gapDurationMs < effectiveMinDurationMs || continuousLowVocal) {
                        for (k in gapStart until gapEnd) {
                            isSpeechBar[k] = true
                        }
                    }
                }
            } else {
                barIdx++
            }
        }

        // 4. Emit SilenceIntervals with symmetric pre-roll & post-roll (Resume Buffer) padding
        val intervals = mutableListOf<SilenceInterval>()
        var idx = 0
        var hasSeenSpeech = false
        while (idx < numBars) {
            if (!isSpeechBar[idx]) {
                val startBar = idx
                while (idx < numBars && !isSpeechBar[idx]) {
                    idx++
                }
                val endBar = idx
                val rawStartMs = (startBar * msPerBar).toLong()
                val rawEndMs = (endBar * msPerBar).toLong().coerceAtMost(durationMs)
                val rawSpanMs = rawEndMs - rawStartMs
                if (rawSpanMs >= effectiveMinDurationMs) {
                    val maxPadEachSide = ((rawSpanMs - 80L) / 2L).coerceAtLeast(0L)
                    val actualPadMs = minOf(effectiveSafetyBufferMs.toLong(), maxPadEachSide)
                    val safeStartMs = if (hasSeenSpeech) {
                        rawStartMs + actualPadMs
                    } else {
                        rawStartMs
                    }
                    val safeEndMs = if (endBar < numBars) {
                        (rawEndMs - actualPadMs).coerceAtMost(durationMs - 20L)
                    } else {
                        rawEndMs.coerceAtMost(durationMs - 10L)
                    }
                    if (safeEndMs - safeStartMs >= 60L) {
                        intervals.add(SilenceInterval(startMs = safeStartMs, endMs = safeEndMs))
                    }
                }
            } else {
                hasSeenSpeech = true
                idx++
            }
        }
        return intervals
    }

    private fun formatRank(format: String): Int = when (format.uppercase(Locale.US)) {
        "M4A" -> 0
        "WAV" -> 1
        "FLAC" -> 2
        else -> 3
    }

    fun filterAndSortRecordings(
        sourceList: List<RecordingEntity>,
        query: String,
        filter: LibraryFilterChip,
        sortOption: LibrarySortOption,
        sortDirection: LibrarySortDirection
    ): List<RecordingEntity> {
        if (sourceList.isEmpty()) return emptyList()
        val trimmedQuery = query.trim()
        val filtered = sourceList.filter { item ->
            val matchesQuery = trimmedQuery.isEmpty() ||
                item.title.contains(trimmedQuery, ignoreCase = true) ||
                item.tags.contains(trimmedQuery, ignoreCase = true) ||
                item.format.contains(trimmedQuery, ignoreCase = true) ||
                item.notes.contains(trimmedQuery, ignoreCase = true)

            val matchesFilter = when (filter) {
                LibraryFilterChip.ALL -> true
                LibraryFilterChip.M4A -> item.format.equals("M4A", ignoreCase = true)
                LibraryFilterChip.WAV -> item.format.equals("WAV", ignoreCase = true)
                LibraryFilterChip.FLAC -> item.format.equals("FLAC", ignoreCase = true)
            }
            matchesQuery && matchesFilter
        }

        val baseComparator: Comparator<RecordingEntity> = when (sortOption) {
            LibrarySortOption.NAME -> compareBy<RecordingEntity> { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.id }
            LibrarySortOption.NUMBER_ID -> compareBy<RecordingEntity> { it.id }
                .thenBy { it.createdAt }
            LibrarySortOption.DATE -> compareBy<RecordingEntity> { it.createdAt }
                .thenBy { it.id }
            LibrarySortOption.FORMAT -> compareBy<RecordingEntity> { formatRank(it.format) }
                .thenBy { it.format.uppercase(Locale.ROOT) }
                .thenBy { it.createdAt }
        }

        val effectiveComparator = if (sortDirection == LibrarySortDirection.ASCENDING) {
            baseComparator
        } else {
            baseComparator.reversed()
        }

        return filtered.sortedWith(effectiveComparator)
    }
}
