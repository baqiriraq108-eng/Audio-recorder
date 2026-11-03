package com.example.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val durationMs: Long,
    val format: String, // "M4A", "WAV", "FLAC"
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val channels: Int = 1,
    val bitrate: Int = 192000,
    val sizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val waveformPeaks: String = "",
    val isCloudSynced: Boolean = false,
    val cloudFileId: String? = null,
    val cloudSyncedAt: Long? = null,
    val storageLocation: String = "LOCAL", // "LOCAL" or "CLOUD"
    val driveFolderId: String? = null,
    val driveFolderName: String? = null,
    val driveWebViewLink: String? = null,
    val tags: String = "Studio",
    val notes: String = "",
    val appendedCount: Int = 0,
    val bookmarks: String = "",
    val silenceMap: String = "",
    val isInTrash: Boolean = false,
    val trashedAt: Long? = null
) {
    val peakList: List<Float>
        get() = AudioFormatUtils.deserializePeaks(waveformPeaks)

    val bookmarkList: List<Long>
        get() = AudioFormatUtils.deserializeBookmarks(bookmarks)

    val silenceIntervals: List<SilenceInterval>
        get() = AudioFormatUtils.deserializeSilenceMap(silenceMap)

    /**
     * Returns true ONLY when this recording has a verified Google Drive upload or cloud import ID,
     * rejecting any legacy mock/dummy "drive_..." IDs or local filesystem paths.
     */
    val hasVerifiedCloudUpload: Boolean
        get() = isCloudSynced &&
            !cloudFileId.isNullOrBlank() &&
            !cloudFileId.startsWith("drive_") &&
            !cloudFileId.startsWith("/")
}
