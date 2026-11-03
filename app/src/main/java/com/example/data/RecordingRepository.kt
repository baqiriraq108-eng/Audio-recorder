package com.example.data

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.audio.WavFileTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.Locale
import javax.crypto.KeyGenerator

class RecordingRepository(
    private val context: Context,
    private val dao: RecordingDao
) {
    private val prefs = context.getSharedPreferences("audio_studio_prefs", Context.MODE_PRIVATE)
    val allRecordings: Flow<List<RecordingEntity>> = dao.getAllRecordings()
    val localRecordings: Flow<List<RecordingEntity>> = dao.getLocalRecordings()
    val cloudRecordings: Flow<List<RecordingEntity>> = dao.getCloudRecordings()
    val trashRecordings: Flow<List<RecordingEntity>> = dao.getTrashRecordings()
    val trashCount: Flow<Int> = dao.getTrashCount()

    /**
     * Safely moves [source] file to [target] even across different filesystems or storage partitions,
     * falling back to bit-exact stream copy and source deletion if [File.renameTo] returns false.
     */
    fun moveFileSafely(source: File, target: File): Boolean {
        if (!source.exists()) return false
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return true
        return try {
            source.copyTo(target, overwrite = true)
            source.delete()
            target.exists() && target.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolves the actual audio file on device storage for [recording], auto-healing stale,
     * renamed, or relocated file paths (e.g. extension changes between M4A/WAV, companion .bak files,
     * or files saved into subfolders). Updates Room DB if a new valid path is located.
     */
    suspend fun resolveAudioFile(recording: RecordingEntity): File? = withContext(Dispatchers.IO) {
        if (recording.filePath.isNotBlank()) {
            val direct = File(recording.filePath)
            if (direct.exists() && direct.length() > 0L) {
                return@withContext direct
            }
        }

        val directFile = if (recording.filePath.isNotBlank()) File(recording.filePath) else null
        val parentDir = directFile?.parentFile ?: getRecordingsDir()
        val baseName = directFile?.nameWithoutExtension
            ?: recording.title.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(36)

        // Candidates to search for the physical audio file
        val candidates = mutableListOf<File>()

        // 1. Same directory with alternative extensions
        listOf("wav", "m4a", "aac", "flac", "mp3").forEach { ext ->
            candidates.add(File(parentDir, "$baseName.$ext"))
            candidates.add(File(getRecordingsDir(), "$baseName.$ext"))
        }

        // 2. Companion backup or temporary merged files
        if (directFile != null) {
            val pDir = directFile.parentFile ?: getRecordingsDir()
            candidates.add(File(pDir, "${directFile.name}.bak"))
            candidates.add(File(pDir, "${directFile.nameWithoutExtension}.bak"))
            candidates.add(File(pDir, "${directFile.name}.merged.tmp"))
            candidates.add(File(pDir, "${directFile.nameWithoutExtension}.merged.tmp"))
            candidates.add(File(pDir, "${directFile.name}.repaired.wav.tmp"))
            candidates.add(File(pDir, "${directFile.nameWithoutExtension}.repaired.wav.tmp"))
        }

        // 3. Cache directory files matching baseName
        val cacheFiles = context.cacheDir.listFiles() ?: emptyArray()
        for (f in cacheFiles) {
            if (f.isFile && f.name.contains(baseName, ignoreCase = true)) {
                if (f.length() > 44L) {
                    candidates.add(f)
                }
            }
        }

        val resolved = candidates.firstOrNull { it.exists() && it.length() > 32L }
        if (resolved != null) {
            // Self-heal database entry so subsequent playback, repair, and cloud uploads succeed seamlessly
            val updated = recording.copy(
                filePath = resolved.absolutePath,
                sizeBytes = resolved.length(),
                format = if (WavFileTools.isRiffWaveFile(resolved)) "WAV" else recording.format
            )
            dao.updateRecording(updated)
            return@withContext resolved
        }

        null
    }

    fun getRecordingsDir(subFolderName: String = "", folderPath: String = ""): File {
        val baseInternal = File(context.filesDir, "internal_storage")
        if (!baseInternal.exists()) baseInternal.mkdirs()

        val target = when {
            folderPath.isNotBlank() && folderPath.startsWith("/Internal Storage") -> {
                val rel = folderPath.removePrefix("/Internal Storage").trim('/')
                if (rel.isBlank()) {
                    File(baseInternal, "Recordings")
                } else {
                    File(baseInternal, rel)
                }
            }
            subFolderName.isNotBlank() && !subFolderName.equals("Recordings", ignoreCase = true) -> {
                val cleanSub = subFolderName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val recFolder = File(baseInternal, "Recordings")
                File(recFolder, cleanSub)
            }
            else -> {
                File(baseInternal, "Recordings")
            }
        }
        if (!target.exists()) target.mkdirs()
        return target
    }


    /**
     * Removes any previously pre-installed sample music tracks from database and disk.
     */
    suspend fun removePreinstalledSamplesIfPresent() = withContext(Dispatchers.IO) {
        val preinstalledTitles = setOf(
            "Keynote Architecture Review",
            "Acoustic Lecture Notes Ch.4",
            "Interview — M3 Motion Systems"
        )
        val preinstalledFiles = setOf(
            "Keynote_Architecture_Review.m4a",
            "Acoustic_Lecture_Notes_Ch4.wav",
            "Interview_Design_Systems.flac"
        )
        try {
            val currentList = dao.getAllRecordings().first()
            val toRemove = currentList.filter { rec ->
                rec.title in preinstalledTitles ||
                    preinstalledFiles.any { rec.filePath.endsWith(it) }
            }
            if (toRemove.isNotEmpty()) {
                toRemove.forEach { rec ->
                    try {
                        File(rec.filePath).delete()
                    } catch (_: Exception) {}
                }
                dao.deleteRecordingsByIds(toRemove.map { it.id })
            }
        } catch (_: Exception) {}
    }

    /**
     * Cleans up any legacy mock/dummy cloud records (e.g., fake "drive_..." IDs or local-only recordings
     * placed in the CLOUD tab before upload) and resets unverified local recordings to isCloudSynced = false
     * so unuploaded recordings always display the orange upload icon and never pollute the Cloud tab.
     */
    suspend fun purgeMockCloudDataAndResetFalseSyncStates() = withContext(Dispatchers.IO) {
        try {
            val all = dao.getAllRecordings().first()
            val localList = all.filter { it.storageLocation.equals(StorageDestination.LOCAL.id, ignoreCase = true) }
            val cloudList = all.filter { it.storageLocation.equals(StorageDestination.CLOUD.id, ignoreCase = true) }

            val cloudIdsToDelete = mutableListOf<Long>()
            cloudList.forEach { cloudRec ->
                // The Cloud tab must ONLY show recordings downloaded or imported from Google Drive onto the device.
                // Remove any remote-only placeholder or missing-file record.
                val hasOnDeviceFile = cloudRec.filePath.isNotBlank() && File(cloudRec.filePath).exists()
                val isImportedOrDownloadedFromDrive = cloudRec.notes.startsWith("Imported from", ignoreCase = true) ||
                    cloudRec.filePath.contains("/drive_cloud") ||
                    (!cloudRec.cloudFileId.isNullOrBlank() && !cloudRec.cloudFileId.startsWith("drive_"))

                if (!hasOnDeviceFile) {
                    cloudIdsToDelete.add(cloudRec.id)
                } else if (isImportedOrDownloadedFromDrive) {
                    // Only initialize cloudFileId/isCloudSynced if this recording has never been modified/appended
                    // and doesn't already have a cloudFileId. If appendedCount > 0 and isCloudSynced == false,
                    // preserve isCloudSynced = false so it stays marked as needing re-upload!
                    if (cloudRec.appendedCount == 0 && cloudRec.cloudFileId.isNullOrBlank()) {
                        dao.updateRecording(
                            cloudRec.copy(
                                isCloudSynced = true,
                                cloudFileId = "cloud_imported_${cloudRec.id}",
                                cloudSyncedAt = cloudRec.cloudSyncedAt ?: cloudRec.createdAt
                            )
                        )
                    }
                } else {
                    val hasMatchingLocal = localList.any { localRec ->
                        localRec.filePath == cloudRec.filePath ||
                            (!cloudRec.cloudFileId.isNullOrBlank() && localRec.cloudFileId == cloudRec.cloudFileId)
                    }
                    if (hasMatchingLocal) {
                        cloudIdsToDelete.add(cloudRec.id)
                    } else {
                        // Recorded directly on phone: keep in Local tab only
                        dao.updateRecording(
                            cloudRec.copy(
                                storageLocation = StorageDestination.LOCAL.id,
                                isCloudSynced = cloudRec.hasVerifiedCloudUpload,
                                cloudFileId = if (cloudRec.hasVerifiedCloudUpload) cloudRec.cloudFileId else null,
                                cloudSyncedAt = if (cloudRec.hasVerifiedCloudUpload) cloudRec.cloudSyncedAt else null,
                                driveWebViewLink = if (cloudRec.hasVerifiedCloudUpload) cloudRec.driveWebViewLink else null
                            )
                        )
                    }
                }
            }
            if (cloudIdsToDelete.isNotEmpty()) {
                dao.deleteRecordingsByIds(cloudIdsToDelete)
            }

            localList.forEach { localRec ->
                if ((localRec.isCloudSynced || !localRec.cloudFileId.isNullOrBlank()) && !localRec.hasVerifiedCloudUpload) {
                    dao.updateRecording(
                        localRec.copy(
                            isCloudSynced = false,
                            cloudFileId = null,
                            cloudSyncedAt = null,
                            driveWebViewLink = null
                        )
                    )
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Inspects all stored recordings on disk and repairs broken/truncated audio headers or
     * mislabeled container formats (e.g. .m4a files containing RIFF/WAVE data).
     * Synchronizes database entities with the verified file path, format, and size.
     */
    suspend fun autoRepairCorruptedRecordings() = withContext(Dispatchers.IO) {
        try {
            val all = dao.getAllRecordings().first()
            for (rec in all) {
                if (rec.filePath.isBlank()) continue
                val file = File(rec.filePath)
                if (file.exists() && file.length() > 0L) {
                    val repaired = WavFileTools.repairCorruptedAudioFileIfNeeded(file)
                    val isWav = WavFileTools.isRiffWaveFile(repaired)
                    val newFormat = if (isWav) "WAV" else rec.format
                    val pathChanged = repaired.absolutePath != rec.filePath
                    val formatChanged = !newFormat.equals(rec.format, ignoreCase = true)
                    val sizeChanged = repaired.length() != rec.sizeBytes && repaired.length() > 0L

                    if (pathChanged || formatChanged || sizeChanged) {
                        val updated = rec.copy(
                            filePath = repaired.absolutePath,
                            format = newFormat,
                            sizeBytes = repaired.length()
                        )
                        dao.updateRecording(updated)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun getRecordingById(id: Long): RecordingEntity? =
        dao.getRecordingById(id)

    suspend fun insertRecording(recording: RecordingEntity): Long =
        dao.insertRecording(recording)

    suspend fun updateRecording(recording: RecordingEntity) =
        dao.updateRecording(recording)

    /**
     * Updates [recording] and any linked record in the other tab that references the same underlying file,
     * ensuring both Local and Cloud lists immediately reflect updated duration, size, and waveform peaks
     * while preserving each row's storageLocation.
     */
    suspend fun updateRecordingAndLinkedPaths(
        recording: RecordingEntity,
        previousFilePath: String? = null
    ) = withContext(Dispatchers.IO) {
        dao.updateRecording(recording)
        val pathsToMatch = listOfNotNull(
            recording.filePath.takeIf { it.isNotBlank() },
            previousFilePath?.takeIf { it.isNotBlank() }
        ).distinct()

        for (path in pathsToMatch) {
            val matches = dao.getRecordingsByFilePath(path)
            for (other in matches) {
                if (other.id != recording.id) {
                    dao.updateRecording(
                        other.copy(
                            filePath = recording.filePath,
                            format = recording.format,
                            durationMs = recording.durationMs,
                            sizeBytes = recording.sizeBytes,
                            waveformPeaks = recording.waveformPeaks,
                            bookmarks = recording.bookmarks,
                            silenceMap = recording.silenceMap,
                            appendedCount = recording.appendedCount,
                            isCloudSynced = recording.isCloudSynced,
                            cloudFileId = recording.cloudFileId,
                            cloudSyncedAt = recording.cloudSyncedAt
                        )
                    )
                }
            }
        }
    }

    suspend fun moveToTrash(recording: RecordingEntity) = withContext(Dispatchers.IO) {
        dao.moveToTrash(recording.id, System.currentTimeMillis())
    }

    suspend fun moveToTrash(recordings: List<RecordingEntity>) = withContext(Dispatchers.IO) {
        if (recordings.isEmpty()) return@withContext
        dao.moveMultipleToTrash(recordings.map { it.id }, System.currentTimeMillis())
    }

    suspend fun restoreFromTrash(recording: RecordingEntity) = withContext(Dispatchers.IO) {
        dao.restoreFromTrash(recording.id)
    }

    suspend fun restoreFromTrash(recordings: List<RecordingEntity>) = withContext(Dispatchers.IO) {
        if (recordings.isEmpty()) return@withContext
        dao.restoreMultipleFromTrash(recordings.map { it.id })
    }

    suspend fun deletePermanently(recording: RecordingEntity) = withContext(Dispatchers.IO) {
        try {
            val file = resolveAudioFile(recording) ?: File(recording.filePath)
            if (file.exists()) file.delete()
            val bak = File(file.parentFile ?: getRecordingsDir(), "${file.name}.bak")
            if (bak.exists()) bak.delete()
        } catch (_: Exception) {}
        dao.deleteRecording(recording)
    }

    suspend fun deletePermanently(recordings: List<RecordingEntity>) = withContext(Dispatchers.IO) {
        recordings.forEach { rec ->
            try {
                val file = resolveAudioFile(rec) ?: File(rec.filePath)
                if (file.exists()) file.delete()
                val bak = File(file.parentFile ?: getRecordingsDir(), "${file.name}.bak")
                if (bak.exists()) bak.delete()
            } catch (_: Exception) {}
        }
        dao.deleteRecordingsByIds(recordings.map { it.id })
    }

    suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) {
        val trashedItems = dao.getAllTrashRecordsSync()
        trashedItems.forEach { rec ->
            try {
                val file = resolveAudioFile(rec) ?: File(rec.filePath)
                if (file.exists()) file.delete()
                val bak = File(file.parentFile ?: getRecordingsDir(), "${file.name}.bak")
                if (bak.exists()) bak.delete()
            } catch (_: Exception) {}
        }
        dao.emptyTrashRecords()
    }

    /**
     * Imports an audio file from Google Drive or Android Storage Access Framework (SAF) [uri],
     * copying the bit-exact audio stream (zero gain/normalization alteration), extracting accurate
     * metadata (duration, format, file size, sample rate, channels, bitrate), and generating its waveform.
     */
    suspend fun importAudioFromUri(
        uri: Uri,
        targetStorageLocation: StorageDestination = StorageDestination.CLOUD,
        driveFolderId: String? = "root",
        driveFolderName: String? = "My Drive",
        onProgress: (Float) -> Unit
    ): Result<RecordingEntity> = withContext(Dispatchers.IO) {
        try {
            onProgress(0.06f)
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            var displayName = ""
            var declaredSize = 0L

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let {
                            displayName = it.trim()
                        }
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        declaredSize = cursor.getLong(sizeIdx)
                    }
                }
            }

            val mimeType = context.contentResolver.getType(uri)?.lowercase(Locale.US).orEmpty()
            val rawExt = displayName.substringAfterLast('.', "").lowercase(Locale.US)
            val resolvedExt = when {
                rawExt in setOf("wav", "mp3", "m4a", "aac", "flac", "ogg", "opus", "mp4", "3gp", "amr") -> {
                    if (rawExt == "mp4") "m4a" else rawExt
                }
                mimeType.contains("wav") -> "wav"
                mimeType.contains("flac") -> "flac"
                mimeType.contains("mpeg") || mimeType.contains("mp3") -> "mp3"
                mimeType.contains("ogg") || mimeType.contains("opus") -> "ogg"
                mimeType.contains("aac") -> "aac"
                mimeType.contains("mp4") || mimeType.contains("m4a") || mimeType.startsWith("audio/") -> "m4a"
                else -> "m4a"
            }

            val baseTitle = if (displayName.contains('.')) {
                displayName.substringBeforeLast('.')
            } else {
                displayName
            }
            val cleanTitle = baseTitle
                .replace('_', ' ')
                .trim()
                .ifBlank { "Imported Drive Audio ${System.currentTimeMillis() % 10000}" }

            val targetDir = if (targetStorageLocation == StorageDestination.CLOUD) {
                val cloudDir = File(context.filesDir, "drive_cloud")
                if (!cloudDir.exists()) cloudDir.mkdirs()
                cloudDir
            } else {
                getRecordingsDir()
            }

            val destFile = File(
                targetDir,
                "import_${System.currentTimeMillis()}_${(100..999).random()}.$resolvedExt"
            )

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Could not open selected audio file from Google Drive."))

            var totalCopied = 0L
            inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        // Bit-exact copy: zero gain or sample modification
                        output.write(buffer, 0, read)
                        totalCopied += read
                        if (declaredSize > 0L) {
                            val ratio = (totalCopied.toFloat() / declaredSize.toFloat()).coerceIn(0.1f, 0.86f)
                            onProgress(ratio)
                        } else {
                            onProgress(0.55f)
                        }
                    }
                    output.flush()
                }
            }

            if (totalCopied < 16L) {
                destFile.delete()
                return@withContext Result.failure(IllegalStateException("Selected audio file appears empty."))
            }

            onProgress(0.92f)

            // Extract real audio metadata (duration, sample rate, channels, bitrate)
            var durationMs = 0L
            var sampleRate = 44100
            var channels = 1
            var bitDepth = 16
            var bitrate = 192_000

            if (resolvedExt == "wav") {
                val wavHeader = WavFileTools.readWavHeaderInfo(destFile, 44100, 1, 16)
                sampleRate = wavHeader.sampleRate
                channels = wavHeader.channels
                bitDepth = wavHeader.bitsPerSample
                val bytesPerSec = (sampleRate.toLong() * wavHeader.blockAlign.toLong()).coerceAtLeast(1L)
                bitrate = (bytesPerSec * 8L).toInt()
                durationMs = (wavHeader.pcmDataBytes * 1000L) / bytesPerSec
            }

            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(destFile.absolutePath)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val parsedDur = durStr?.toLongOrNull() ?: 0L
                    if (parsedDur > 0L) durationMs = parsedDur

                    val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    val parsedBr = brStr?.toIntOrNull() ?: 0
                    if (parsedBr > 0) bitrate = parsedBr
                } finally {
                    retriever.release()
                }
            } catch (_: Exception) {}

            try {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(destFile.absolutePath)
                    if (extractor.trackCount > 0) {
                        val format = extractor.getTrackFormat(0)
                        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(8000, 192000)
                        }
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                        }
                        if (durationMs <= 0L && format.containsKey(MediaFormat.KEY_DURATION)) {
                            durationMs = (format.getLong(MediaFormat.KEY_DURATION) / 1000L).coerceAtLeast(500L)
                        }
                    }
                } finally {
                    extractor.release()
                }
            } catch (_: Exception) {}

            if (durationMs <= 0L) {
                val fallbackBytesPerSec = (bitrate / 8).coerceAtLeast(16_000)
                durationMs = ((destFile.length() * 1000L) / fallbackBytesPerSec).coerceIn(1_000L, 7_200_000L)
            }

            val formatLabel = when (resolvedExt) {
                "wav" -> "WAV"
                "flac" -> "FLAC"
                "mp3" -> "MP3"
                "aac" -> "AAC"
                "ogg", "opus" -> "OGG"
                else -> "M4A"
            }

            val peaks = WavFileTools.extractWaveformFromFile(destFile)
            val silenceIntervals = WavFileTools.generateSilenceMapIndex(
                sourceFile = destFile,
                durationMs = durationMs,
                peaks = peaks,
                config = loadSettings().silenceConfig,
                fallbackSampleRate = sampleRate,
                fallbackChannels = channels,
                fallbackBitsPerSample = bitDepth
            )
            val isCloud = targetStorageLocation == StorageDestination.CLOUD
            val isFromGoogleDriveProvider = isCloud ||
                uri.authority?.contains("com.google.android.apps.docs", ignoreCase = true) == true
            val entity = RecordingEntity(
                title = cleanTitle,
                filePath = destFile.absolutePath,
                durationMs = durationMs,
                format = formatLabel,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                channels = channels,
                bitrate = bitrate,
                sizeBytes = destFile.length(),
                waveformPeaks = AudioFormatUtils.serializePeaks(peaks),
                silenceMap = AudioFormatUtils.serializeSilenceMap(silenceIntervals),
                storageLocation = targetStorageLocation.id,
                isCloudSynced = isFromGoogleDriveProvider,
                cloudFileId = if (isFromGoogleDriveProvider) uri.toString() else null,
                cloudSyncedAt = if (isFromGoogleDriveProvider) System.currentTimeMillis() else null,
                driveFolderId = if (isFromGoogleDriveProvider) (driveFolderId ?: "root") else null,
                driveFolderName = if (isFromGoogleDriveProvider) (driveFolderName ?: "My Drive") else null,
                tags = if (isFromGoogleDriveProvider) "Google Drive, $formatLabel" else "Imported, $formatLabel",
                notes = if (isFromGoogleDriveProvider) "Imported from Google Drive ($displayName)" else "Imported from device ($displayName)"
            )
            val newId = dao.insertRecording(entity)
            onProgress(1.0f)
            Result.success(entity.copy(id = newId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * The Cloud tab must only show recordings that have been downloaded or imported from Google Drive
     * to the device. Remote-only Google Drive files are never inserted into the Cloud tab.
     */
    suspend fun syncRemoteCloudMetadataOnly(
        remoteFiles: List<RemoteDriveAudioFile>
    ) = withContext(Dispatchers.IO) {
        // No-op by design: do not insert files that exist only on Google Drive.
    }

    /**
     * Resolves a collision-free local filename inside [targetDir] using standard `(1)`, `(2)` suffixes
     * so manual cloud downloads never overwrite existing local recordings.
     */
    fun resolveUniqueLocalFile(
        targetDir: File,
        baseTitle: String,
        extension: String
    ): File {
        if (!targetDir.exists()) targetDir.mkdirs()
        val cleanExt = extension.trim().trimStart('.').lowercase(Locale.US).ifBlank { "m4a" }
        val sanitizedBase = baseTitle.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(64)
            .ifBlank { "Recording" }

        var candidate = File(targetDir, "$sanitizedBase.$cleanExt")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(targetDir, "$sanitizedBase ($counter).$cleanExt")
            counter++
        }
        return candidate
    }

    /**
     * Explicitly downloads a single user-selected Cloud recording to the configured local storage
     * directory (`getRecordingsDir`) and inserts it into the Local tab ONLY after the file download
     * completes. Never runs automatically.
     */
    suspend fun downloadCloudRecordingToLocal(
        cloudRecording: RecordingEntity,
        driveClient: GoogleDriveRestClient,
        settings: RecorderSettings,
        onProgress: (Float) -> Unit
    ): Result<RecordingEntity> = withContext(Dispatchers.IO) {
        try {
            val targetLocalDir = getRecordingsDir(
                subFolderName = settings.localFolderName,
                folderPath = settings.localFolderPath
            )
            val ext = AudioFormatOption.fromId(cloudRecording.format).extension.ifBlank { "m4a" }
            val destinationFile = resolveUniqueLocalFile(
                targetDir = targetLocalDir,
                baseTitle = cloudRecording.title,
                extension = ext
            )

            val downloadRes = driveClient.downloadRemoteAudioToFile(
                accessToken = settings.driveAccessToken,
                accountEmail = settings.driveAccountEmail,
                cloudFileId = cloudRecording.cloudFileId.orEmpty(),
                fallbackCloudMirrorPath = cloudRecording.filePath,
                destinationFile = destinationFile,
                expectedSizeBytes = cloudRecording.sizeBytes,
                onProgress = { ratio ->
                    onProgress((ratio * 0.90f).coerceIn(0.05f, 0.90f))
                }
            )

            val savedFile = downloadRes.getOrElse { err ->
                return@withContext Result.failure(err)
            }

            if (!savedFile.exists() || savedFile.length() <= 0L) {
                savedFile.delete()
                return@withContext Result.failure(
                    IllegalStateException("Downloaded audio file is empty.")
                )
            }

            onProgress(0.94f)

            var durationMs = cloudRecording.durationMs
            var sampleRate = cloudRecording.sampleRate.takeIf { it > 0 } ?: 48000
            var channels = cloudRecording.channels.takeIf { it > 0 } ?: 1
            var bitDepth = cloudRecording.bitDepth.takeIf { it > 0 } ?: 16
            var bitrate = cloudRecording.bitrate.takeIf { it > 0 } ?: 192000

            if (ext.equals("wav", ignoreCase = true)) {
                val wavHeader = WavFileTools.readWavHeaderInfo(savedFile, sampleRate, channels, bitDepth)
                sampleRate = wavHeader.sampleRate
                channels = wavHeader.channels
                bitDepth = wavHeader.bitsPerSample
                val bytesPerSec = (sampleRate.toLong() * wavHeader.blockAlign.toLong()).coerceAtLeast(1L)
                bitrate = (bytesPerSec * 8L).toInt()
                val calcDur = (wavHeader.pcmDataBytes * 1000L) / bytesPerSec
                if (calcDur > 0L) durationMs = calcDur
            }

            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(savedFile.absolutePath)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val parsedDur = durStr?.toLongOrNull() ?: 0L
                    if (parsedDur > 0L) durationMs = parsedDur
                    val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    val parsedBr = brStr?.toIntOrNull() ?: 0
                    if (parsedBr > 0) bitrate = parsedBr
                } finally {
                    retriever.release()
                }
            } catch (_: Exception) {}

            if (durationMs <= 0L) {
                val fallbackBytesPerSec = (bitrate / 8).coerceAtLeast(16_000)
                durationMs = ((savedFile.length() * 1000L) / fallbackBytesPerSec).coerceIn(1_000L, 7_200_000L)
            }

            val peaks = cloudRecording.peakList.ifEmpty {
                WavFileTools.extractWaveformFromFile(savedFile)
            }
            val silenceIntervals = cloudRecording.silenceIntervals.ifEmpty {
                WavFileTools.generateSilenceMapIndex(
                    sourceFile = savedFile,
                    durationMs = durationMs,
                    peaks = peaks,
                    config = settings.silenceConfig,
                    fallbackSampleRate = sampleRate,
                    fallbackChannels = channels,
                    fallbackBitsPerSample = bitDepth
                )
            }

            val serializedPeaks = AudioFormatUtils.serializePeaks(peaks)
            val serializedSilence = AudioFormatUtils.serializeSilenceMap(silenceIntervals)

            // Update the Cloud entry so its local file reference is now available for immediate offline playback
            val updatedCloud = cloudRecording.copy(
                filePath = savedFile.absolutePath,
                durationMs = durationMs,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                channels = channels,
                bitrate = bitrate,
                sizeBytes = savedFile.length(),
                waveformPeaks = serializedPeaks,
                silenceMap = serializedSilence
            )
            dao.updateRecording(updatedCloud)

            // Insert or update the Local tab entry ONLY after the file has been fully downloaded to local storage
            val existingLocalList = dao.getLocalRecordings().first()
            val existingLocal = existingLocalList.firstOrNull { localRec ->
                (!cloudRecording.cloudFileId.isNullOrBlank() && localRec.cloudFileId == cloudRecording.cloudFileId) ||
                    localRec.filePath == savedFile.absolutePath
            }

            val localResultEntity = if (existingLocal != null) {
                val updatedLocal = existingLocal.copy(
                    title = cloudRecording.title,
                    filePath = savedFile.absolutePath,
                    durationMs = durationMs,
                    sampleRate = sampleRate,
                    bitDepth = bitDepth,
                    channels = channels,
                    bitrate = bitrate,
                    sizeBytes = savedFile.length(),
                    waveformPeaks = serializedPeaks,
                    bookmarks = cloudRecording.bookmarks,
                    silenceMap = serializedSilence,
                    storageLocation = StorageDestination.LOCAL.id,
                    isCloudSynced = true,
                    cloudFileId = cloudRecording.cloudFileId,
                    cloudSyncedAt = cloudRecording.cloudSyncedAt ?: System.currentTimeMillis(),
                    driveFolderId = cloudRecording.driveFolderId,
                    driveFolderName = cloudRecording.driveFolderName,
                    driveWebViewLink = cloudRecording.driveWebViewLink
                )
                dao.updateRecording(updatedLocal)
                updatedLocal
            } else {
                val newLocalEntity = cloudRecording.copy(
                    id = 0,
                    filePath = savedFile.absolutePath,
                    durationMs = durationMs,
                    sampleRate = sampleRate,
                    bitDepth = bitDepth,
                    channels = channels,
                    bitrate = bitrate,
                    sizeBytes = savedFile.length(),
                    waveformPeaks = serializedPeaks,
                    silenceMap = serializedSilence,
                    storageLocation = StorageDestination.LOCAL.id,
                    isCloudSynced = true,
                    cloudFileId = cloudRecording.cloudFileId,
                    cloudSyncedAt = cloudRecording.cloudSyncedAt ?: System.currentTimeMillis()
                )
                val localId = dao.insertRecording(newLocalEntity)
                newLocalEntity.copy(id = localId)
            }

            onProgress(1.0f)
            Result.success(localResultEntity)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Copies an existing recording file so it exists in both Local and Cloud libraries.
     */
    suspend fun copyRecordingFileToTarget(
        source: RecordingEntity,
        targetStorageLocation: StorageDestination,
        driveFolderId: String?,
        driveFolderName: String?,
        cloudFileId: String? = null
    ): Result<RecordingEntity> = withContext(Dispatchers.IO) {
        try {
            val srcFile = File(source.filePath)
            val ext = srcFile.extension.ifBlank {
                AudioFormatOption.fromId(source.format).extension
            }
            val destDir = if (targetStorageLocation == StorageDestination.CLOUD) {
                val sub = driveFolderName?.trim()?.replace(Regex("[\\\\/:*?\"<>|]"), "_").orEmpty()
                val baseCloud = File(context.filesDir, "drive_cloud").apply { if (!exists()) mkdirs() }
                if (sub.isBlank() || sub.equals("My Drive", ignoreCase = true)) {
                    baseCloud
                } else {
                    File(baseCloud, sub).apply { if (!exists()) mkdirs() }
                }
            } else {
                getRecordingsDir()
            }
            val copyFile = File(
                destDir,
                "${srcFile.nameWithoutExtension}_copy_${System.currentTimeMillis() % 10000}.$ext"
            )
            if (srcFile.exists()) {
                srcFile.copyTo(copyFile, overwrite = true)
            }
            val newEntity = source.copy(
                id = 0,
                filePath = if (copyFile.exists()) copyFile.absolutePath else source.filePath,
                createdAt = System.currentTimeMillis(),
                storageLocation = targetStorageLocation.id,
                isCloudSynced = true,
                cloudFileId = cloudFileId ?: source.cloudFileId ?: "drive_${System.currentTimeMillis() % 100000}",
                cloudSyncedAt = System.currentTimeMillis(),
                driveFolderId = driveFolderId ?: source.driveFolderId,
                driveFolderName = driveFolderName ?: source.driveFolderName
            )
            val newId = dao.insertRecording(newEntity)
            Result.success(newEntity.copy(id = newId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Converts an existing recording to a target format, sample rate, bit depth, channels, and bitrate.
     */
    suspend fun convertRecordingFormat(
        source: RecordingEntity,
        targetFormat: AudioFormatOption,
        targetSampleRate: Int,
        targetBitDepth: Int,
        targetChannels: Int,
        targetBitrate: Int,
        exportTreeUri: String? = null,
        onProgress: (Float) -> Unit
    ): Result<RecordingEntity> = withContext(Dispatchers.IO) {
        try {
            val srcFile = File(source.filePath)
            val sanitizedTitle = source.title.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            val outFile = File(
                getRecordingsDir(),
                "${sanitizedTitle}_${targetSampleRate}Hz_${System.currentTimeMillis() % 10000}.${targetFormat.extension}"
            )

            onProgress(0.10f)

            var conversionSuccess = false
            if (srcFile.exists() && srcFile.length() > 0L) {
                val isSrcWav = WavFileTools.isRiffWaveFile(srcFile)
                val srcHeader = if (isSrcWav) WavFileTools.readWavHeaderInfo(srcFile) else null

                val isMatchingWav = isSrcWav &&
                    srcHeader?.sampleRate == targetSampleRate &&
                    srcHeader?.channels == targetChannels &&
                    srcHeader?.bitsPerSample == targetBitDepth

                if (isMatchingWav) {
                    srcFile.copyTo(outFile, overwrite = true)
                    conversionSuccess = outFile.exists() && outFile.length() > 44L
                } else {
                    conversionSuccess = WavFileTools.decodeAudioToWav(
                        sourceFile = srcFile,
                        outputWavFile = outFile,
                        targetSampleRate = targetSampleRate,
                        targetChannels = targetChannels,
                        bitsPerSample = targetBitDepth,
                        onProgress = { ratio -> onProgress(0.10f + (ratio * 0.75f)) }
                    )
                }
            }

            if (!conversionSuccess || !outFile.exists() || outFile.length() <= 44L) {
                if (srcFile.exists()) {
                    srcFile.copyTo(outFile, overwrite = true)
                }
            }

            val peaks = if (outFile.exists() && outFile.length() > 44L) {
                source.peakList.ifEmpty { WavFileTools.extractWaveformFromFile(outFile) }
            } else {
                emptyList()
            }

            if (!exportTreeUri.isNullOrBlank()) {
                try {
                    val treeUri = Uri.parse(exportTreeUri)
                    val treeDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                    if (treeDoc != null && treeDoc.exists() && treeDoc.canWrite()) {
                        val mimeType = when (targetFormat.extension.lowercase(Locale.US)) {
                            "wav" -> "audio/x-wav"
                            "flac" -> "audio/flac"
                            else -> "audio/m4a"
                        }
                        val exportDoc = treeDoc.createFile(mimeType, outFile.name)
                        if (exportDoc != null) {
                            context.contentResolver.openOutputStream(exportDoc.uri)?.use { os ->
                                outFile.inputStream().use { isStream ->
                                    isStream.copyTo(os)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            onProgress(0.95f)
            val convertedEntity = RecordingEntity(
                title = "${source.title} (${targetFormat.id})",
                filePath = outFile.absolutePath,
                durationMs = source.durationMs,
                format = targetFormat.id,
                sampleRate = targetSampleRate,
                bitDepth = targetBitDepth,
                channels = targetChannels,
                bitrate = targetBitrate,
                sizeBytes = outFile.length(),
                waveformPeaks = AudioFormatUtils.serializePeaks(peaks),
                silenceMap = source.silenceMap,
                tags = "${source.tags}, Converted".trim(',', ' '),
                notes = "Converted from ${source.format} to ${targetFormat.label} (${targetSampleRate}Hz, ${targetBitDepth}-bit, ${if (targetChannels == 2) "Stereo" else "Mono"})."
            )
            val id = dao.insertRecording(convertedEntity)
            onProgress(1.0f)
            Result.success(convertedEntity.copy(id = id))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "google_drive_studio_token_key"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }

    private fun getOrCreateSecretKey(): javax.crypto.SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                existing.secretKey
            } else {
                val keyGen = KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                    KEYSTORE_PROVIDER
                )
                val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun encryptAccessToken(plainToken: String): Pair<String, String>? {
        if (plainToken.isBlank()) return null
        return try {
            val secretKey = getOrCreateSecretKey() ?: return null
            val cipher = javax.crypto.Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
            val ivBase64 = android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP)
            val encryptedBytes = cipher.doFinal(plainToken.toByteArray(Charsets.UTF_8))
            val cipherBase64 = android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP)
            cipherBase64 to ivBase64
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptAccessToken(): String {
        val cipherBase64 = prefs.getString("drive_keystore_token_cipher", null)
        val ivBase64 = prefs.getString("drive_keystore_token_iv", null)
        if (!cipherBase64.isNullOrBlank() && !ivBase64.isNullOrBlank()) {
            try {
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
                val secretKey = entry?.secretKey
                if (secretKey != null) {
                    val cipher = javax.crypto.Cipher.getInstance(AES_TRANSFORMATION)
                    val ivBytes = android.util.Base64.decode(ivBase64, android.util.Base64.NO_WRAP)
                    val cipherBytes = android.util.Base64.decode(cipherBase64, android.util.Base64.NO_WRAP)
                    val spec = javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, ivBytes)
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec)
                    return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
                }
            } catch (_: Exception) {
                return ""
            }
        }
        return prefs.getString("drive_keystore_token_fallback", "") ?: ""
    }

    /**
     * Stores the user's selected Google account session in SharedPreferences and AndroidKeyStore.
     */
    fun storeAuthenticatedDriveSession(profile: AuthenticatedGoogleProfile) {
        val cleanEmail = profile.email.trim()
        if (cleanEmail.isBlank()) return
        val cleanToken = profile.accessToken.trim()

        val encrypted = encryptAccessToken(cleanToken)
        val editor = prefs.edit()
            .remove("drive_keystore_token_ref")
            .putString("drive_account_email", cleanEmail)
            .putString("drive_account_name", profile.displayName.trim().ifBlank { cleanEmail.substringBefore('@') })
            .putString("drive_account_photo_url", profile.photoUrl.trim())
            .putBoolean("drive_connected", true)

        if (encrypted != null) {
            editor
                .putString("drive_keystore_token_cipher", encrypted.first)
                .putString("drive_keystore_token_iv", encrypted.second)
                .putString("drive_keystore_token_fallback", cleanToken)
        } else {
            editor
                .remove("drive_keystore_token_cipher")
                .remove("drive_keystore_token_iv")
                .putString("drive_keystore_token_fallback", cleanToken)
        }
        editor.apply()
    }

    /**
     * Deletes the AndroidKeyStore encryption key and removes all Google Drive session tokens
     * and account details from local storage.
     */
    fun disconnectCloudAccount() {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                keyStore.deleteEntry(KEYSTORE_ALIAS)
            }
        } catch (_: Exception) {}

        prefs.edit()
            .remove("drive_keystore_token_ref")
            .remove("drive_keystore_token_cipher")
            .remove("drive_keystore_token_iv")
            .remove("drive_keystore_token_fallback")
            .remove("drive_account_email")
            .remove("drive_account_name")
            .remove("drive_account_photo_url")
            .putBoolean("drive_connected", false)
            .putBoolean("drive_auto_sync", false)
            .putLong("last_cloud_sync_ms", 0L)
            .putString("default_storage_destination", StorageDestination.LOCAL.id)
            .apply()
    }

    fun clearLocalTempCache(): Long {
        var bytesFreed = 0L
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                bytesFreed += file.length()
                file.delete()
            }
        }
        getRecordingsDir().listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp") || file.name.endsWith(".bak")) {
                bytesFreed += file.length()
                file.delete()
            }
        }
        return bytesFreed
    }

    fun loadSettings(): RecorderSettings {
        val formatId = prefs.getString("default_format", AudioFormatOption.M4A.id) ?: AudioFormatOption.M4A.id
        val rawConnected = prefs.getBoolean("drive_connected", false)
        val rawEmail = (prefs.getString("drive_account_email", "") ?: "").trim()
        val rawName = (prefs.getString("drive_account_name", "") ?: "").trim()
        val rawPhoto = (prefs.getString("drive_account_photo_url", "") ?: "").trim()
        val rawDecrypt = decryptAccessToken().trim()
        val decryptedToken = if (rawDecrypt == "native_google_account_connected") "" else rawDecrypt

        // Ensure no legacy mock tokens or unauthenticated sessions are ever loaded
        val hasValidLiveSession = rawConnected &&
            rawEmail.isNotBlank() &&
            !rawEmail.equals("baqir.iraq108@gmail.com", ignoreCase = true) &&
            !decryptedToken.startsWith("ya29.studio_")

        if (!hasValidLiveSession && (rawConnected || prefs.contains("drive_keystore_token_ref") || rawEmail.isNotBlank())) {
            disconnectCloudAccount()
        }

        val destId = if (hasValidLiveSession) {
            prefs.getString("default_storage_destination", StorageDestination.LOCAL.id) ?: StorageDestination.LOCAL.id
        } else {
            StorageDestination.LOCAL.id
        }

        val localFolderName = prefs.getString("local_folder_name", "Recordings") ?: "Recordings"
        val localFolderPath = prefs.getString("local_folder_path", "Default App Storage")
            ?: "Default App Storage"
        val customStorageTreeUri = prefs.getString("custom_storage_tree_uri", "") ?: ""

        return RecorderSettings(
            defaultFormat = AudioFormatOption.fromId(formatId),
            sampleRate = prefs.getInt("sample_rate", 44100),
            bitDepth = prefs.getInt("bit_depth", 16),
            channels = prefs.getInt("channels", 1),
            bitrate = prefs.getInt("bitrate", 192000),
            storageDirectoryLabel = localFolderPath,
            localFolderName = localFolderName,
            localFolderPath = localFolderPath,
            customStorageTreeUri = customStorageTreeUri,
            defaultStorageDestination = StorageDestination.fromId(destId),
            defaultPlaybackSpeed = prefs.getFloat("default_playback_speed", 1.0f),
            silenceConfig = run {
                val smart = prefs.getBoolean("silence_smart_auto_mode", true)
                val manual = prefs.getBoolean("silence_manual_mode_enabled", false)
                val rawEnabled = prefs.getBoolean("silence_skip_enabled", false)
                SilenceSkipConfig(
                    enabled = rawEnabled && (smart || manual),
                    smartAutoMode = smart,
                    manualModeEnabled = if (smart) false else manual,
                    thresholdDb = prefs.getFloat("silence_threshold_db", -35f),
                    minSilenceDurationMs = prefs.getInt("silence_min_duration_ms", 400),
                    resumePaddingMs = prefs.getInt("silence_resume_padding_ms", 140)
                )
            },
            driveConnected = hasValidLiveSession,
            driveAccountEmail = if (hasValidLiveSession) rawEmail else "",
            driveAccountName = if (hasValidLiveSession) rawName.ifBlank { rawEmail.substringBefore('@') } else "",
            driveAccountPhotoUrl = if (hasValidLiveSession) rawPhoto else "",
            driveAccessToken = if (hasValidLiveSession) decryptedToken else "",
            driveFolderId = prefs.getString("drive_folder_id", "root") ?: "root",
            driveFolderName = prefs.getString("drive_folder_name", "My Drive") ?: "My Drive",
            driveFolderPath = prefs.getString("drive_folder_path", "/My Drive") ?: "/My Drive",
            driveFolderUri = prefs.getString("drive_folder_uri", "") ?: "",
            driveAutoSync = prefs.getBoolean("drive_auto_sync", false),
            lastCloudSyncMs = prefs.getLong("last_cloud_sync_ms", 0L),
            echoCancellationEnabled = prefs.getBoolean("echo_cancellation_enabled", true),
            noiseSuppressionEnabled = prefs.getBoolean("noise_suppression_enabled", true),
            pauseExternalMediaOnRecord = prefs.getBoolean("pause_external_media_on_record", true)
        )
    }

    fun saveSettings(settings: RecorderSettings) {
        val isAuthenticated = settings.driveConnected &&
            settings.driveAccountEmail.isNotBlank()

        prefs.edit()
            .putString("default_format", settings.defaultFormat.id)
            .putInt("sample_rate", settings.sampleRate)
            .putInt("bit_depth", settings.bitDepth)
            .putInt("channels", settings.channels)
            .putInt("bitrate", settings.bitrate)
            .putString("local_folder_name", settings.localFolderName)
            .putString("local_folder_path", settings.localFolderPath)
            .putString("custom_storage_tree_uri", settings.customStorageTreeUri)
            .putString(
                "default_storage_destination",
                settings.defaultStorageDestination.id
            )
            .putFloat("default_playback_speed", settings.defaultPlaybackSpeed)
            .putBoolean("silence_skip_enabled", settings.silenceConfig.isEffectivelyEnabled)
            .putBoolean("silence_smart_auto_mode", settings.silenceConfig.smartAutoMode)
            .putBoolean("silence_manual_mode_enabled", settings.silenceConfig.manualModeEnabled)
            .putFloat("silence_threshold_db", settings.silenceConfig.thresholdDb)
            .putInt("silence_min_duration_ms", settings.silenceConfig.minSilenceDurationMs)
            .putInt("silence_resume_padding_ms", settings.silenceConfig.resumePaddingMs)
            .putBoolean("drive_connected", isAuthenticated)
            .putString("drive_account_email", if (isAuthenticated) settings.driveAccountEmail else "")
            .putString("drive_account_name", if (isAuthenticated) settings.driveAccountName else "")
            .putString("drive_account_photo_url", if (isAuthenticated) settings.driveAccountPhotoUrl else "")
            .putString("drive_folder_id", settings.driveFolderId)
            .putString("drive_folder_name", settings.driveFolderName)
            .putString("drive_folder_path", settings.driveFolderPath)
            .putString("drive_folder_uri", settings.driveFolderUri)
            .putBoolean("drive_auto_sync", settings.driveAutoSync)
            .putLong("last_cloud_sync_ms", settings.lastCloudSyncMs)
            .putBoolean("echo_cancellation_enabled", settings.echoCancellationEnabled)
            .putBoolean("noise_suppression_enabled", settings.noiseSuppressionEnabled)
            .putBoolean("pause_external_media_on_record", settings.pauseExternalMediaOnRecord)
            .apply()
    }
}
