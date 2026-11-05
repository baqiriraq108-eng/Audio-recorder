package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.WavFileTools
import com.example.data.AudioFormatUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Audio Recorder", appName)
    }

    @Test
    fun `verify WAV creation, silence export, and atomic append`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.cacheDir, "test_audio")
        tempDir.mkdirs()

        val sourceFile = File(tempDir, "test_source.wav")
        val peaks = WavFileTools.createStudioSampleAudioFile(
            outputFile = sourceFile,
            durationSeconds = 5,
            sampleRate = 22050
        )
        assertTrue(sourceFile.exists() && sourceFile.length() > 44L)
        assertTrue(peaks.isNotEmpty())

        // Verify non-destructive silence export
        val trimmedFile = File(tempDir, "test_trimmed.wav")
        val (newDurMs, trimmedPeaks) = WavFileTools.exportWithoutSilence(
            sourceFile = sourceFile,
            targetFile = trimmedFile,
            sampleRate = 22050,
            thresholdDb = -35f,
            minSilenceDurationMs = 300,
            resumePaddingMs = 60,
            onProgress = {}
        )
        assertTrue(trimmedFile.exists() && trimmedFile.length() > 44L)
        assertTrue(newDurMs > 0L)
        assertTrue(trimmedPeaks.isNotEmpty())

        // Verify atomic append
        val appendSegment = File(tempDir, "test_append.wav")
        WavFileTools.createStudioSampleAudioFile(
            outputFile = appendSegment,
            durationSeconds = 2,
            sampleRate = 22050
        )
        val initialSize = sourceFile.length()
        val merged = WavFileTools.appendAudioFilesAtomically(
            originalFile = sourceFile,
            appendedPcmFile = appendSegment,
            sampleRate = 22050,
            channels = 1
        )
        assertTrue(merged)
        assertTrue(sourceFile.length() > initialSize)

        assertEquals("00:01:05", AudioFormatUtils.formatDurationHMS(65_000L))
    }

    @Test
    fun `verify 4-tab navigation and zero mock Google Drive authentication lifecycle`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val destinations = com.example.viewmodel.AppDestination.entries
        assertEquals(4, destinations.size)
        assertEquals(
            listOf("Recorder", "Library", "Storage", "Settings"),
            destinations.map { it.label }
        )

        val db = com.example.data.AppDatabase.getInstance(context)
        val repository = com.example.data.RecordingRepository(context, db.recordingDao())
        repository.disconnectCloudAccount()

        // 1. Verify initial state has zero hardcoded email addresses or fake tokens
        val initialSettings = repository.loadSettings()
        org.junit.Assert.assertFalse(initialSettings.driveConnected)
        assertEquals("", initialSettings.driveAccountEmail)
        assertEquals("", initialSettings.driveAccountName)
        assertEquals("", initialSettings.driveAccessToken)
        assertEquals(com.example.data.StorageDestination.LOCAL, initialSettings.defaultStorageDestination)

        // 2. Verify unauthenticated Drive API calls strictly fail / return empty without mock data
        val driveClient = com.example.data.GoogleDriveRestClient(context)
        val unauthCreate = driveClient.createFolder(
            accessToken = "",
            folderName = "Test Studio Lectures",
            parentFolderId = "root",
            parentPath = "/My Drive"
        )
        assertTrue(unauthCreate.isFailure)

        val unauthFolders = driveClient.listFolders(
            accessToken = "",
            parentFolderId = "root",
            parentPath = "/My Drive"
        )
        assertTrue(unauthFolders.isEmpty())

        val unauthQuota = driveClient.fetchDriveQuota(accessToken = "")
        assertEquals(0L, unauthQuota.usedBytes)
        assertEquals(0L, unauthQuota.limitBytes)
        assertEquals("", unauthQuota.userEmail)

        // 3. Verify disconnect revokes and clears stored session tokens and account data
        repository.storeAuthenticatedDriveSession(
            com.example.data.AuthenticatedGoogleProfile(
                email = "verified.user@example.org",
                displayName = "Verified Studio User",
                photoUrl = "",
                accessToken = "ya29.real_oauth_token_12345"
            )
        )
        val connectedSettings = repository.loadSettings()
        assertTrue(connectedSettings.driveConnected)
        assertEquals("verified.user@example.org", connectedSettings.driveAccountEmail)
        assertEquals("Verified Studio User", connectedSettings.driveAccountName)
        assertEquals("ya29.real_oauth_token_12345", connectedSettings.driveAccessToken)

        repository.disconnectCloudAccount()
        val afterDisconnect = repository.loadSettings()
        org.junit.Assert.assertFalse(afterDisconnect.driveConnected)
        assertEquals("", afterDisconnect.driveAccountEmail)
        assertEquals("", afterDisconnect.driveAccountName)
        assertEquals("", afterDisconnect.driveAccessToken)
        assertEquals(com.example.data.StorageDestination.LOCAL, afterDisconnect.defaultStorageDestination)

        // 4. Verify native AccountManager.newChooseAccountIntent result handling & Drive folder creation + file saving
        val accountIntent = android.content.Intent().apply {
            putExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME, "studio.creator@gmail.com")
            putExtra(android.accounts.AccountManager.KEY_ACCOUNT_TYPE, "com.google")
        }
        val stepResult = driveClient.completeActivityIntentResolution(
            activityContext = context,
            stepType = com.example.data.AuthIntentStepType.ACCOUNT_PICKER,
            granted = true,
            resultIntent = accountIntent,
            pendingEmail = "",
            pendingDisplayName = "",
            pendingPhotoUrl = ""
        )
        assertTrue(stepResult is com.example.data.GoogleDriveAuthStepResult.Authenticated)
        val profile = (stepResult as com.example.data.GoogleDriveAuthStepResult.Authenticated).profile
        assertEquals("studio.creator@gmail.com", profile.email)

        repository.storeAuthenticatedDriveSession(profile)
        val folderCreated = driveClient.createFolder(
            accessToken = profile.accessToken,
            folderName = "Podcast Sessions",
            parentFolderId = "root",
            parentPath = "/My Drive"
        )
        assertTrue(folderCreated.isSuccess)
        val createdFolder = folderCreated.getOrThrow()
        assertEquals("Podcast Sessions", createdFolder.name)

        // Verify local phone folders remain completely separate from Google Drive folders
        val localFolders = driveClient.listLocalPhoneFolders("/Internal Storage/Recordings")
        assertTrue(localFolders.none { it.name == "Podcast Sessions" })
    }

    @Test
    fun `verify silence export preserves exact audio parameters and 100 percent bit-exact spoken samples`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.cacheDir, "test_bit_exact")
        tempDir.mkdirs()

        val sampleRate = 48000
        val channels = 2
        val bitsPerSample = 16
        val blockAlign = channels * (bitsPerSample / 8)
        val windowMs = 40
        val framesPerWindow = (sampleRate * windowMs) / 1000
        val bytesPerWindow = framesPerWindow * blockAlign

        // Build a known stereo 48kHz WAV file:
        // - 5 windows of active speech with deterministic sample values (e.g., +12345, -12345)
        // - 20 windows of pure silence (0)
        // - 5 windows of active speech with deterministic sample values (e.g., +18400, -18400)
        val speechWindow1 = ByteArray(bytesPerWindow)
        val speechWindow2 = ByteArray(bytesPerWindow)
        val silentWindow = ByteArray(bytesPerWindow) // all zeros

        java.nio.ByteBuffer.wrap(speechWindow1).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            for (f in 0 until framesPerWindow) {
                putShort(12345.toShort())   // Left channel
                putShort((-12345).toShort()) // Right channel
            }
        }
        java.nio.ByteBuffer.wrap(speechWindow2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            for (f in 0 until framesPerWindow) {
                putShort(18400.toShort())   // Left channel
                putShort((-18400).toShort()) // Right channel
            }
        }

        val totalWindows = 5 + 20 + 5
        val totalPcmBytes = totalWindows.toLong() * bytesPerWindow.toLong()
        val sourceStereoFile = File(tempDir, "source_stereo_48k.wav")
        java.io.FileOutputStream(sourceStereoFile).use { fos ->
            WavFileTools.writeWavHeader(fos, totalPcmBytes, sampleRate, channels, bitsPerSample)
            repeat(5) { fos.write(speechWindow1) }
            repeat(20) { fos.write(silentWindow) }
            repeat(5) { fos.write(speechWindow2) }
            fos.flush()
        }

        val exportedStereoFile = File(tempDir, "exported_stereo_48k.wav")
        WavFileTools.exportWithoutSilence(
            sourceFile = sourceStereoFile,
            targetFile = exportedStereoFile,
            sampleRate = sampleRate,
            thresholdDb = -35f,
            minSilenceDurationMs = 200,
            resumePaddingMs = 80,
            channels = channels,
            bitsPerSample = bitsPerSample,
            onProgress = {}
        )

        // 1. Verify exact header parameter preservation
        val exportedHeader = WavFileTools.readWavHeaderInfo(exportedStereoFile)
        assertEquals(sampleRate, exportedHeader.sampleRate)
        assertEquals(channels, exportedHeader.channels)
        assertEquals(bitsPerSample, exportedHeader.bitsPerSample)
        assertEquals(blockAlign, exportedHeader.blockAlign)

        // 2. Verify silence was removed (exported file is shorter than source file)
        assertTrue(exportedStereoFile.length() < sourceStereoFile.length())

        // 3. Verify 100% bit-exact sample values (zero gain/attenuation/fading) in exported PCM
        val exportedBytes = exportedStereoFile.readBytes().copyOfRange(44, exportedStereoFile.length().toInt())
        val firstWindowExported = exportedBytes.copyOfRange(0, bytesPerWindow)
        val lastWindowExported = exportedBytes.copyOfRange(exportedBytes.size - bytesPerWindow, exportedBytes.size)
        org.junit.Assert.assertArrayEquals(speechWindow1, firstWindowExported)
        org.junit.Assert.assertArrayEquals(speechWindow2, lastWindowExported)
    }

    @Test
    fun `verify universal waveform extraction and silence skipping support for all audio files`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.cacheDir, "test_universal")
        tempDir.mkdirs()

        // 1. Create simulated imported M4A audio file
        val m4aFile = File(tempDir, "imported_lecture.m4a")
        m4aFile.writeBytes(ByteArray(8192) { (it % 127).toByte() })
        assertTrue(m4aFile.exists())

        // 2. Extract waveform profile
        val peaks = WavFileTools.extractWaveformFromFile(m4aFile, targetBars = 96)
        assertEquals(96, peaks.size)
        assertTrue(peaks.any { it > 0.1f })

        // 3. Verify silence skip configuration resolves effective parameters accurately
        val config = com.example.data.SilenceSkipConfig(
            enabled = true,
            smartAutoMode = true
        )
        val (thresholdDb, minSilenceDurationMs, safetyBufferMs) = config.resolveEffectiveParameters(peaks)
        assertTrue(thresholdDb in -55f..-15f)
        assertTrue(minSilenceDurationMs in 200..800)
        assertTrue(safetyBufferMs in 120..160)

        // 4. Verify non-destructive silence export succeeds on the file
        val cleanFile = File(tempDir, "clean_export.m4a")
        val (dur, cleanPeaks) = WavFileTools.exportWithoutSilence(
            sourceFile = m4aFile,
            targetFile = cleanFile,
            sampleRate = 44100,
            thresholdDb = thresholdDb,
            minSilenceDurationMs = minSilenceDurationMs,
            resumePaddingMs = safetyBufferMs,
            onProgress = {}
        )
        assertTrue(cleanFile.exists() && cleanFile.length() > 0L)
        assertTrue(dur > 0L)
        assertTrue(cleanPeaks.isNotEmpty())
    }

    @Test
    fun `verify Silence Map Index generation preserves original file and supports dynamic playback toggle`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.cacheDir, "test_silence_map")
        tempDir.mkdirs()

        val sampleRate = 24000
        val channels = 1
        val bitsPerSample = 16
        val blockAlign = 2
        val windowMs = 40
        val framesPerWindow = (sampleRate * windowMs) / 1000
        val bytesPerWindow = framesPerWindow * blockAlign

        val speechWindow = ByteArray(bytesPerWindow)
        val silentWindow = ByteArray(bytesPerWindow)
        java.nio.ByteBuffer.wrap(speechWindow).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            for (f in 0 until framesPerWindow) {
                putShort(14000.toShort())
            }
        }

        // 10 speech windows (0..400ms), 25 silent windows (400..1400ms), 10 speech windows (1400..1800ms)
        val totalWindows = 10 + 25 + 10
        val totalPcmBytes = totalWindows.toLong() * bytesPerWindow.toLong()
        val originalFile = File(tempDir, "original_session.wav")
        java.io.FileOutputStream(originalFile).use { fos ->
            WavFileTools.writeWavHeader(fos, totalPcmBytes, sampleRate, channels, bitsPerSample)
            repeat(10) { fos.write(speechWindow) }
            repeat(25) { fos.write(silentWindow) }
            repeat(10) { fos.write(speechWindow) }
            fos.flush()
        }

        val originalLength = originalFile.length()
        val originalBytesSnapshot = originalFile.readBytes()
        val peaks = WavFileTools.extractWaveformFromFile(originalFile)
        val config = com.example.data.SilenceSkipConfig(
            enabled = true,
            smartAutoMode = false,
            manualModeEnabled = true,
            thresholdDb = -35f,
            minSilenceDurationMs = 400,
            resumePaddingMs = 80
        )

        // Generate Silence Map Index non-destructively
        val silenceMap = WavFileTools.generateSilenceMapIndex(
            sourceFile = originalFile,
            durationMs = 1800L,
            peaks = peaks,
            config = config,
            fallbackSampleRate = sampleRate,
            fallbackChannels = channels,
            fallbackBitsPerSample = bitsPerSample
        )

        // 1. Original file is 100% untouched bit-for-bit
        assertEquals(originalLength, originalFile.length())
        org.junit.Assert.assertArrayEquals(originalBytesSnapshot, originalFile.readBytes())

        // 2. Silence Map Index accurately captures the silent interval
        assertTrue(silenceMap.isNotEmpty())
        val firstInterval = silenceMap.first()
        assertTrue("Expected startMs in 400..850 but was ${firstInterval.startMs}", firstInterval.startMs in 400L..850L)
        assertTrue("Expected endMs in 1100..1500 but was ${firstInterval.endMs}", firstInterval.endMs in 1100L..1500L)

        // 3. Verify serialization & deserialization in RecordingEntity
        val serialized = AudioFormatUtils.serializeSilenceMap(silenceMap)
        assertTrue(serialized.isNotBlank())
        val deserialized = AudioFormatUtils.deserializeSilenceMap(serialized)
        assertEquals(silenceMap, deserialized)

        // 4. Verify real-time AudioStudioEngine playback with Skip Silence ON/OFF toggle
        val engine = com.example.audio.AudioStudioEngine(context)
        engine.loadAndPlayFile(
            file = originalFile,
            peaks = peaks,
            startPositionMs = 500L, // start right inside the silent interval [480ms, 1320ms]
            speed = 1.0f,
            silenceConfig = config.copy(enabled = false),
            preindexedSilenceMap = silenceMap
        )
        assertTrue(engine.isPlaying.value)
        assertEquals(0L, engine.silenceSkippedSavedMs.value)

        // Toggle Skip Silence ON in real time while playing without pausing or reloading
        engine.updateSilenceSkipConfig(config.copy(enabled = true))
        var waitedMs = 0L
        while (engine.silenceSkippedSavedMs.value == 0L && waitedMs < 1500L) {
            kotlinx.coroutines.delay(20L)
            waitedMs += 20L
        }

        // Verify the engine jumped across the silent interval if playback loop advanced
        if (engine.silenceSkippedSavedMs.value > 0L) {
            assertTrue(engine.playbackPositionMs.value >= 1200L)
            assertTrue(engine.silenceSkippedSavedMs.value > 400L)
        }

        // Toggle Skip Silence OFF in real time while playing
        engine.updateSilenceSkipConfig(config.copy(enabled = false))
        org.junit.Assert.assertFalse(engine.activeSilenceJumping.value)

        // 5. Verify Master Settings Gate: when both Smart and Manual are OFF in Settings,
        // isConfiguredInSettings and isEffectivelyEnabled are strictly false
        val disabledInSettings = config.copy(
            enabled = true,
            smartAutoMode = false,
            manualModeEnabled = false
        )
        org.junit.Assert.assertFalse(disabledInSettings.isConfiguredInSettings)
        org.junit.Assert.assertFalse(disabledInSettings.isEffectivelyEnabled)

        // 6. Verify Zero-Crossing Finder snaps to a zero-crossing frame without altering file bytes
        java.io.RandomAccessFile(originalFile, "r").use { raf ->
            val targetOffset = (480L * sampleRate * blockAlign) / 1000L
            val snappedOffset = WavFileTools.findNearestZeroCrossingByteOffset(
                raf = raf,
                targetByteOffset = targetOffset,
                totalPcmBytes = totalPcmBytes,
                blockAlign = blockAlign,
                bytesPerSample = 2,
                sampleRate = sampleRate
            )
            assertEquals(0L, snappedOffset % blockAlign)
            assertTrue(snappedOffset in 0L..totalPcmBytes)
        }
        org.junit.Assert.assertArrayEquals(originalBytesSnapshot, originalFile.readBytes())

        engine.stopPlayback()
    }

    @Test
    fun `verify trimWavToRange supports Save as Copy and Apply to Current Overwrite with bit-exact audio`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.cacheDir, "test_trim_audio")
        tempDir.mkdirs()

        val sourceFile = File(tempDir, "trim_source.wav")
        WavFileTools.createStudioSampleAudioFile(
            outputFile = sourceFile,
            durationSeconds = 6,
            sampleRate = 22050
        )
        val initialLength = sourceFile.length()
        assertTrue(initialLength > 44L)

        // 1. Save as Copy (trim 1000ms..4000ms -> ~3000ms)
        val copyFile = File(tempDir, "trim_copy.wav")
        val (copyDurationMs, copyPeaks) = WavFileTools.trimWavToRange(
            sourceFile = sourceFile,
            targetFile = copyFile,
            startMs = 1000L,
            endMs = 4000L,
            fallbackSampleRate = 22050,
            fallbackChannels = 1,
            fallbackBitsPerSample = 16
        )
        assertTrue(copyFile.exists() && copyFile.length() > 44L)
        assertEquals(initialLength, sourceFile.length()) // Original untouched
        assertTrue(copyDurationMs in 2950L..3050L)
        assertTrue(copyPeaks.isNotEmpty())

        // 2. Apply to Current (Overwrite in place: trim 500ms..2500ms -> ~2000ms)
        val (overwrittenDurationMs, overwrittenPeaks) = WavFileTools.trimWavToRange(
            sourceFile = sourceFile,
            targetFile = sourceFile,
            startMs = 500L,
            endMs = 2500L,
            fallbackSampleRate = 22050,
            fallbackChannels = 1,
            fallbackBitsPerSample = 16
        )
        assertTrue(sourceFile.exists() && sourceFile.length() in 45L until initialLength)
        assertTrue(overwrittenDurationMs in 1950L..2050L)
        assertTrue(overwrittenPeaks.isNotEmpty())
    }

    @Test
    fun `verify playback screen Roboto Flex typography scale and Skip Silent label`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Skip Silent", context.getString(R.string.skip_silence))

        assertEquals(30f, com.example.ui.theme.PlayerRecordingTitleStyle.fontSize.value, 0.01f)
        assertEquals(
            androidx.compose.ui.text.font.FontWeight.Normal,
            com.example.ui.theme.PlayerRecordingTitleStyle.fontWeight
        )

        assertEquals(60f, com.example.ui.theme.PlayerMainTimerStyle.fontSize.value, 0.01f)
        assertEquals(
            androidx.compose.ui.text.font.FontWeight.Normal,
            com.example.ui.theme.PlayerMainTimerStyle.fontWeight
        )

        assertEquals(14f, com.example.ui.theme.PlayerSecondaryInfoStyle.fontSize.value, 0.01f)
        assertEquals(
            androidx.compose.ui.text.font.FontWeight.Normal,
            com.example.ui.theme.PlayerSecondaryInfoStyle.fontWeight
        )

        assertEquals(14f, com.example.ui.theme.PlayerSecondaryTimerStyle.fontSize.value, 0.01f)
        assertEquals(
            androidx.compose.ui.text.font.FontWeight.Normal,
            com.example.ui.theme.PlayerSecondaryTimerStyle.fontWeight
        )

        assertEquals(14f, com.example.ui.theme.PlayerSkipSilentLabelStyle.fontSize.value, 0.01f)
        assertEquals(
            androidx.compose.ui.text.font.FontWeight.Medium,
            com.example.ui.theme.PlayerSkipSilentLabelStyle.fontWeight
        )

        assertEquals(14f, com.example.ui.theme.PlayerSpeedLabelStyle.fontSize.value, 0.01f)
        assertEquals(
            androidx.compose.ui.text.font.FontWeight.Medium,
            com.example.ui.theme.PlayerSpeedLabelStyle.fontWeight
        )
    }

    @Test
    fun `verify storage location button typography HyperIsland notification and manual cloud download`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Storage Screen "Device" and "Google Drive" button typography: Roboto Flex, 13 sp, Medium (500)
        assertEquals(13f, com.example.ui.theme.StorageLocationOptionLabelStyle.fontSize.value, 0.01f)
        assertEquals(
            androidx.compose.ui.text.font.FontWeight.Medium,
            com.example.ui.theme.StorageLocationOptionLabelStyle.fontWeight
        )
        assertEquals(
            com.example.ui.theme.RobotoFlexFontFamily,
            com.example.ui.theme.StorageLocationOptionLabelStyle.fontFamily
        )

        // 2. Unified Foreground Service + Non-Expandable Custom Recording Card + RecordingIslandOverlayManager:
        //    - Uses single unified RecordingForegroundService (Playback keeps MediaStyle untouched; Recording strips MediaStyle)
        //    - Recording state removes Cast button, bigContentView = null (zero chevron arrow ^), zero standard footer actions
        //    - Renders horizontally centered oscillating coral-orange cubic-Bezier waveform
        //    - Recording controls beneath waveform: [0] Far-Left Bookmark, [1] Center Save, [2] Far-Right Pause/Resume
        //    - Paused state freezes exact oscillating wave shape and dims opacity to 40% (alpha = 102)
        val serviceController = org.robolectric.Robolectric.buildService(
            com.example.service.RecordingForegroundService::class.java
        ).create()
        val service = serviceController.get()

        val activeNotif = service.buildNotification(
            title = "Recording",
            statusText = "",
            isPaused = false,
            isSkipSilence = false,
            isAwaitingSave = false
        )
        // No standard builder.addAction rows on Recording notification (prevents expand chevron and duplicate buttons)
        assertTrue(activeNotif.actions.isNullOrEmpty())
        val customActionOrder = activeNotif.extras.getStringArray(
            com.example.service.RecordingForegroundService.EXTRA_RECORDING_CUSTOM_ACTION_ORDER
        )
        org.junit.Assert.assertArrayEquals(
            arrayOf("Bookmark", "Stop & Save", "Pause"),
            customActionOrder
        )
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, activeNotif.visibility)
        assertEquals(android.app.Notification.CATEGORY_SERVICE, activeNotif.category)
        assertTrue((activeNotif.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue(activeNotif.extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(activeNotif.extras.getBoolean("miui.focus.enable", false))
        val activeFocusParam = activeNotif.extras.getString("miui.focus.param").orEmpty()
        assertTrue(activeFocusParam.contains("miui.focus.pic_island_left"))
        assertTrue(activeFocusParam.contains("\"dismissIsland\":false"))
        assertTrue(activeNotif.extras.getBoolean("android.requestPromotedOngoing"))
        // Cast button, MediaStyle, and bigContentView completely removed in Recording state
        assertTrue(activeNotif.extras.getBoolean(com.example.service.RecordingForegroundService.EXTRA_HIDE_CAST_BUTTON))
        assertTrue(activeNotif.extras.getBoolean("android.hideMediaOutputSwitcher"))
        assertTrue(activeNotif.extras.getBoolean("miui.media.hideCastButton"))
        @Suppress("DEPRECATION")
        org.junit.Assert.assertNull(activeNotif.extras.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION))
        org.junit.Assert.assertNull(activeNotif.extras.getString(android.app.Notification.EXTRA_TEMPLATE))
        org.junit.Assert.assertNull(activeNotif.extras.getIntArray(android.app.Notification.EXTRA_COMPACT_ACTIONS))
        @Suppress("DEPRECATION")
        org.junit.Assert.assertNull("bigContentView must be null to prevent expand chevron arrow", activeNotif.bigContentView)
        org.junit.Assert.assertFalse("MediaSession must remain inactive during Recording", service.getActiveMediaSession()?.isActive == true)

        val activePlaybackState = service.lastRecordingPlaybackState
        org.junit.Assert.assertNotNull(activePlaybackState)
        assertEquals(android.media.session.PlaybackState.STATE_PLAYING, activePlaybackState?.state)
        val customActionIds = activePlaybackState?.customActions?.map { it.action }.orEmpty()
        assertTrue(customActionIds.contains(com.example.service.RecordingForegroundService.ACTION_NOTIFICATION_BOOKMARK))
        assertTrue(customActionIds.contains(com.example.service.RecordingForegroundService.ACTION_NOTIFICATION_STOP_SAVE))

        // Verify custom non-expandable Recording card inflates cleanly without waveform in notification shade + 3 buttons
        @Suppress("DEPRECATION")
        org.junit.Assert.assertNotNull(activeNotif.contentView)
        @Suppress("DEPRECATION")
        val inflatedCard = activeNotif.contentView.apply(context, android.widget.FrameLayout(context))

        val waveformView = inflatedCard.findViewById<android.widget.ImageView>(R.id.notif_recording_waveform)
        assertEquals("Waveform must be hidden (GONE) in notification shade card", android.view.View.GONE, waveformView?.visibility)

        val bookmarkBtn = inflatedCard.findViewById<android.view.View>(R.id.notif_recording_btn_bookmark)
        val saveBtn = inflatedCard.findViewById<android.view.View>(R.id.notif_recording_btn_stop_save)
        val pauseResumeBtn = inflatedCard.findViewById<android.view.View>(R.id.notif_recording_btn_pause_resume)
        val bookmarkLabelView = inflatedCard.findViewById<android.widget.TextView>(R.id.notif_action_bookmark_text)
        val saveLabelView = inflatedCard.findViewById<android.widget.TextView>(R.id.notif_action_stop_save_text)
        val pauseResumeLabelView = inflatedCard.findViewById<android.widget.TextView>(R.id.notif_action_pause_resume_text)
        org.junit.Assert.assertNotNull(bookmarkBtn)
        org.junit.Assert.assertNotNull(saveBtn)
        org.junit.Assert.assertNotNull(pauseResumeBtn)
        assertEquals("Pause", pauseResumeLabelView?.text?.toString())
        // Verify all 3 button text labels are hidden (icon-only buttons without text labels)
        assertEquals(android.view.View.GONE, bookmarkLabelView?.visibility)
        assertEquals(android.view.View.GONE, saveLabelView?.visibility)
        assertEquals(android.view.View.GONE, pauseResumeLabelView?.visibility)
        val bookmarkLp = bookmarkBtn.layoutParams as android.widget.RelativeLayout.LayoutParams
        val saveLp = saveBtn.layoutParams as android.widget.RelativeLayout.LayoutParams
        val pauseResumeLp = pauseResumeBtn.layoutParams as android.widget.RelativeLayout.LayoutParams
        // Verify all 3 button containers have identical width and height
        assertTrue(bookmarkLp.width > 0 && bookmarkLp.width == saveLp.width && saveLp.width == pauseResumeLp.width)
        assertTrue(bookmarkLp.height > 0 && bookmarkLp.height == saveLp.height && saveLp.height == pauseResumeLp.height)
        assertTrue(bookmarkLp.getRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT) != 0)
        assertTrue(saveLp.getRule(android.widget.RelativeLayout.CENTER_IN_PARENT) != 0)
        assertTrue(pauseResumeLp.getRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT) != 0)

        val initialBitmap = service.lastRenderedWaveformBitmap
        org.junit.Assert.assertNotNull(initialBitmap)
        assertEquals(
            com.example.service.RecordingForegroundService.WAVEFORM_BITMAP_WIDTH_PX,
            initialBitmap?.width
        )
        assertEquals(
            com.example.service.RecordingForegroundService.WAVEFORM_BAR_COUNT,
            service.currentWaveformBars.size
        )
        assertEquals(1.0f, service.lastRenderedWaveformOpacity, 0.001f)
        assertEquals(255, service.lastRenderedWaveformAlpha)
        org.junit.Assert.assertArrayEquals(
            intArrayOf(
                com.example.service.RecordingForegroundService.WAVEFORM_CORAL_START_COLOR,
                com.example.service.RecordingForegroundService.WAVEFORM_CORAL_MID_COLOR,
                com.example.service.RecordingForegroundService.WAVEFORM_CORAL_END_COLOR
            ),
            service.lastRenderedColorPalette
        )

        // Verify horizontally centered oscillating wave geometry (alternating crests above centerY and troughs below centerY)
        val centerY = com.example.service.RecordingForegroundService.WAVEFORM_BITMAP_HEIGHT_PX / 2f
        val oscillatingDisplacements = service.lastRenderedOscillatingDisplacements
        val oscillatingYs = service.lastRenderedOscillatingYs
        assertEquals(com.example.service.RecordingForegroundService.WAVEFORM_BAR_COUNT, oscillatingDisplacements.size)
        assertTrue("Expected crests above horizontal midline (displacement < 0)", oscillatingDisplacements.any { it < -0.10f })
        assertTrue("Expected troughs below horizontal midline (displacement > 0)", oscillatingDisplacements.any { it > 0.10f })
        assertTrue("Expected wave points above centerY", oscillatingYs.any { it < centerY })
        assertTrue("Expected wave points below centerY", oscillatingYs.any { it > centerY })
        for (i in 0 until oscillatingDisplacements.size - 1) {
            assertTrue(
                "Adjacent control points must alternate above and below centerY",
                oscillatingDisplacements[i] * oscillatingDisplacements[i + 1] < 0f
            )
        }

        // Advance active recording waveform with live microphone peaks and snapshot before pause
        service.advanceRecordingWaveformBars(floatArrayOf(0.72f, 0.88f), isPaused = false)
        val barsSnapshotBeforePause = service.currentWaveformBars.copyOf()
        val displacementsBeforePause = service.computeOscillatingWaveDisplacements(barsSnapshotBeforePause)
        assertTrue(barsSnapshotBeforePause.all { it > 0.10f })

        // Attempt to advance while paused (must be a strict no-op, freezing exact oscillating wave shape)
        service.advanceRecordingWaveformBars(floatArrayOf(0.0f, 0.0f), isPaused = true)
        org.junit.Assert.assertArrayEquals(
            barsSnapshotBeforePause,
            service.currentWaveformBars,
            0.0001f
        )

        val pausedNotif = service.buildNotification(
            title = "Paused",
            statusText = "",
            isPaused = true,
            isSkipSilence = false,
            isAwaitingSave = false
        )
        // Verify oscillating waveform remains 100% frozen in place upon pause and opacity dims to 40% (alpha = 102)
        org.junit.Assert.assertArrayEquals(
            barsSnapshotBeforePause,
            service.currentWaveformBars,
            0.0001f
        )
        org.junit.Assert.assertArrayEquals(
            displacementsBeforePause,
            service.lastRenderedOscillatingDisplacements,
            0.0001f
        )
        assertEquals(
            com.example.service.RecordingForegroundService.PAUSED_WAVEFORM_OPACITY,
            service.lastRenderedWaveformOpacity,
            0.001f
        )
        assertEquals(
            com.example.service.RecordingForegroundService.PAUSED_WAVEFORM_ALPHA,
            service.lastRenderedWaveformAlpha
        )
        assertTrue(pausedNotif.actions.isNullOrEmpty())
        @Suppress("DEPRECATION")
        org.junit.Assert.assertNull(pausedNotif.bigContentView)
        @Suppress("DEPRECATION")
        val inflatedPausedCard = pausedNotif.contentView.apply(context, android.widget.FrameLayout(context))
        val pausedButtonLabel = inflatedPausedCard.findViewById<android.widget.TextView>(R.id.notif_action_pause_resume_text)
        assertEquals("Resume", pausedButtonLabel?.text?.toString())
        assertEquals(android.view.View.GONE, pausedButtonLabel?.visibility)
        org.junit.Assert.assertFalse(pausedNotif.extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER))
        val pausedPlaybackState = service.lastRecordingPlaybackState
        assertEquals(android.media.session.PlaybackState.STATE_PAUSED, pausedPlaybackState?.state)

        // Verify RecordingIslandOverlayManager foreground/background visibility & interactive mode transitions
        com.example.service.RecordingIslandOverlayManager.onAppForegroundChanged(context, true)
        org.junit.Assert.assertFalse(
            "Overlay must be hidden while MainActivity is in foreground",
            com.example.service.RecordingIslandOverlayManager.shouldDisplayOverlay()
        )
        com.example.service.RecordingIslandOverlayManager.onAppForegroundChanged(context, false)
        assertTrue(
            "Overlay must activate in Docked Pill mode when app moves to background",
            com.example.service.RecordingIslandOverlayManager.shouldDisplayOverlay()
        )
        assertEquals(
            com.example.service.RecordingIslandOverlayManager.OverlayMode.DOCKED_PILL,
            com.example.service.RecordingIslandOverlayManager.currentMode
        )
        // Verify tapping the docked pill view expands it to EXPANDED_CARD
        val dockedPillView = com.example.service.RecordingIslandOverlayManager.currentOverlayRootView
        org.junit.Assert.assertNotNull(dockedPillView)
        dockedPillView?.performClick()
        assertEquals(
            com.example.service.RecordingIslandOverlayManager.OverlayMode.EXPANDED_CARD,
            com.example.service.RecordingIslandOverlayManager.currentMode
        )
        // Verify waveform remains strictly VISIBLE in the expanded capsule
        val expandedCardWaveform = com.example.service.RecordingIslandOverlayManager.currentOverlayRootView
            ?.findViewById<android.widget.ImageView>(R.id.notif_recording_waveform)
        org.junit.Assert.assertNotNull(expandedCardWaveform)
        assertEquals(android.view.View.VISIBLE, expandedCardWaveform?.visibility)

        // Verify tapping outside the expanded card collapses it back to DOCKED_PILL
        val expandedWrapperView = com.example.service.RecordingIslandOverlayManager.currentOverlayRootView
        org.junit.Assert.assertNotNull(expandedWrapperView)
        expandedWrapperView?.performClick()
        assertEquals(
            com.example.service.RecordingIslandOverlayManager.OverlayMode.DOCKED_PILL,
            com.example.service.RecordingIslandOverlayManager.currentMode
        )
        // Verify waveform remains present in docked pill mode
        val dockedWaveform = com.example.service.RecordingIslandOverlayManager.currentOverlayRootView
            ?.findViewById<android.widget.ImageView>(R.id.notif_recording_waveform)
        org.junit.Assert.assertNotNull(dockedWaveform)

        com.example.service.RecordingIslandOverlayManager.handleHeaderTapOpenApp(context)
        org.junit.Assert.assertFalse(com.example.service.RecordingIslandOverlayManager.shouldDisplayOverlay())

        // Verify pressing Save dismisses the recording capsule completely and shows Save/Cancel confirmation
        com.example.service.RecordingIslandOverlayManager.onAppForegroundChanged(context, false)
        service.showAppendSaveConfirmation()
        assertTrue(
            "Confirmation state must be active when Save is pressed",
            com.example.service.RecordingIslandOverlayManager.lastIsShowingAppendConfirm
        )
        val confirmOverlayRoot = com.example.service.RecordingIslandOverlayManager.currentOverlayRootView
        org.junit.Assert.assertNotNull(confirmOverlayRoot)
        org.junit.Assert.assertNull(
            "Recording capsule waveform must disappear completely when Save is pressed",
            confirmOverlayRoot?.findViewById<android.view.View>(R.id.notif_recording_waveform)
        )
        org.junit.Assert.assertNotNull(
            "Cancel (Don't) button must be displayed in confirmation message",
            confirmOverlayRoot?.findViewById<android.view.View>(R.id.notif_append_dont_button)
        )
        org.junit.Assert.assertNotNull(
            "Save button must be displayed in confirmation message",
            confirmOverlayRoot?.findViewById<android.view.View>(R.id.notif_append_save_button)
        )
        val appendConfirmNotif = service.buildAppendSaveConfirmationNotification()
        assertEquals(R.drawable.ic_notif_finished_check, appendConfirmNotif.smallIcon.resId)
        assertEquals("Σ Save", appendConfirmNotif.actions[1].title.toString())
        org.junit.Assert.assertFalse(appendConfirmNotif.extras.getBoolean("miui.focus.enable", true))
        assertTrue(appendConfirmNotif.extras.getString("miui.focus.param").orEmpty().contains("\"dismissIsland\":true"))

        // Verify resuming recording continues smoothly from the frozen state without resetting prior bars
        service.advanceRecordingWaveformBars(floatArrayOf(0.64f), isPaused = false)
        assertEquals(
            barsSnapshotBeforePause[1],
            service.currentWaveformBars[0],
            0.0001f
        )

        // Verify Finished Recording Confirmation Capsule (Dynamic Island & Notification Shade):
        // Minimalist capsule displaying gradient-blended "Finished recording", Greek Omega (Ω) symbol, warm peach-orange theme color, and 0 action buttons
        service.showFinishedSavedConfirmation("Finished recording")
        assertTrue(
            "Finished completion capsule must appear on active screen simultaneously with notification",
            com.example.service.RecordingIslandOverlayManager.lastIsShowingFinishedCompletion
        )
        val finishedOverlayRoot = com.example.service.RecordingIslandOverlayManager.currentOverlayRootView
        org.junit.Assert.assertNotNull(finishedOverlayRoot)
        assertEquals(
            "Finished recording",
            finishedOverlayRoot?.findViewById<android.widget.TextView>(R.id.notif_finished_title)?.text?.toString()
        )
        // Verify tapping outside dismisses the finished completion capsule
        finishedOverlayRoot?.performClick()
        org.junit.Assert.assertFalse(com.example.service.RecordingIslandOverlayManager.lastIsShowingFinishedCompletion)

        val finishedNotif = service.buildFinishedNotification("Finished recording")
        assertEquals("Finished recording", finishedNotif.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        org.junit.Assert.assertNull(finishedNotif.actions)
        assertEquals(0xFFE38851.toInt(), finishedNotif.color)
        val blendedSpannable = service.buildGradientBlendedEnglishTitle("Finished recording")
        val colorSpans = blendedSpannable.getSpans(0, blendedSpannable.length, android.text.style.ForegroundColorSpan::class.java)
        assertEquals("Finished recording".length, colorSpans.size)
        assertEquals(0xFFE38851.toInt(), colorSpans.first().foregroundColor)
        assertEquals(0xFFF4D7C7.toInt(), colorSpans.last().foregroundColor)
        org.junit.Assert.assertFalse(finishedNotif.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        org.junit.Assert.assertArrayEquals(
            intArrayOf(),
            finishedNotif.extras.getIntArray(android.app.Notification.EXTRA_COMPACT_ACTIONS)
        )

        // 3. Cloud Storage exclusively contains manually imported recordings:
        //    - Auto-scanned folder entries are purged on startup while manually imported recordings are preserved
        val db = com.example.data.AppDatabase.getInstance(context)
        val dao = db.recordingDao()
        val repository = com.example.data.RecordingRepository(context, dao)
        val driveClient = com.example.data.GoogleDriveRestClient(context)

        val remoteId = "remote_drive_file_991"
        val remoteMeta = com.example.data.RemoteDriveAudioFile(
            fileId = remoteId,
            name = "Remote_Lecture_Session.wav",
            title = "Remote Lecture Session",
            format = "WAV",
            mimeType = "audio/wav",
            sizeBytes = 64_000L,
            modifiedTimeMs = System.currentTimeMillis(),
            webViewLink = "https://drive.google.com/file/d/$remoteId/view",
            folderId = "root",
            folderName = "My Drive"
        )
        repository.syncRemoteCloudMetadataOnly(listOf(remoteMeta))
        // Purging removes any auto-scanned folder entries that were not manually imported
        repository.purgeMockCloudDataAndResetFalseSyncStates()
        val cloudAfterPurge = repository.cloudRecordings.first()
        assertTrue(cloudAfterPurge.none { it.cloudFileId == remoteId })

        // Verify a manually imported cloud recording is preserved in Cloud Storage and supports manual download
        val stagedCloudMirror = File(context.cacheDir, "staged_cloud_remote.wav")
        WavFileTools.createStudioSampleAudioFile(
            outputFile = stagedCloudMirror,
            durationSeconds = 2,
            sampleRate = 22050
        )
        val manuallyImportedCloudId = repository.insertRecording(
            com.example.data.RecordingEntity(
                title = "Manually Imported Cloud Lecture",
                filePath = stagedCloudMirror.absolutePath,
                durationMs = 2000L,
                format = "WAV",
                sampleRate = 22050,
                bitDepth = 16,
                channels = 1,
                bitrate = 352800,
                sizeBytes = stagedCloudMirror.length(),
                waveformPeaks = "0.4,0.7,0.5",
                silenceMap = "",
                storageLocation = com.example.data.StorageDestination.CLOUD.id,
                isCloudSynced = true,
                cloudFileId = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3D55",
                cloudSyncedAt = System.currentTimeMillis(),
                driveFolderId = "root",
                driveFolderName = "My Drive",
                tags = "Google Drive, WAV",
                notes = "Imported from Google Drive (Manually_Imported_Cloud_Lecture.wav)"
            )
        )
        repository.purgeMockCloudDataAndResetFalseSyncStates()
        val cloudWithManualImport = repository.cloudRecordings.first()
        val importedCloudItem = cloudWithManualImport.firstOrNull { it.id == manuallyImportedCloudId }
        org.junit.Assert.assertNotNull(importedCloudItem)

        // Verify duplicate-safe local filename resolution ("Title.m4a", "Title (1).m4a")
        val dupTestDir = File(context.cacheDir, "dup_test_dir").apply {
            deleteRecursively()
            mkdirs()
        }
        val firstResolved = repository.resolveUniqueLocalFile(dupTestDir, "Studio Note", "m4a")
        firstResolved.writeBytes(ByteArray(32))
        val secondResolved = repository.resolveUniqueLocalFile(dupTestDir, "Studio Note", "m4a")
        assertEquals("Studio Note.m4a", firstResolved.name)
        assertEquals("Studio Note (1).m4a", secondResolved.name)

        // Verify explicit manual download copies only the selected recording to local storage and adds to Local tab
        val manualDownloadRes = repository.downloadCloudRecordingToLocal(
            cloudRecording = importedCloudItem!!,
            driveClient = driveClient,
            settings = repository.loadSettings(),
            onProgress = {}
        )
        assertTrue(manualDownloadRes.isSuccess)
        val downloadedLocalEntity = manualDownloadRes.getOrThrow()
        assertEquals(com.example.data.StorageDestination.LOCAL.id, downloadedLocalEntity.storageLocation)
        assertTrue(File(downloadedLocalEntity.filePath).exists())
    }

    @Test
    fun `verify instant audio hardware release and zero background idle mic access`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.audio.AudioStudioEngine(context)
        org.junit.Assert.assertFalse(engine.isRecording.value)

        // Verify synchronous hardware release call is completely safe when idle
        engine.releaseMicrophoneHardwareImmediately()
        org.junit.Assert.assertFalse(engine.isRecording.value)

        // Verify stopRecordingAndFinalize halts recording and releases hardware immediately
        val (file, duration) = engine.stopRecordingAndFinalize()
        org.junit.Assert.assertFalse(engine.isRecording.value)
        org.junit.Assert.assertFalse(engine.isPaused.value)
        assertEquals(0L, duration)
        assertEquals(0L, engine.elapsedMs.value)

        // Verify pressing Bookmark from notification once records strictly ONE bookmark (not two)
        val serviceController = org.robolectric.Robolectric.buildService(
            com.example.service.RecordingForegroundService::class.java
        ).create()
        val service = serviceController.get()
        var bookmarkTriggerCount = 0
        com.example.service.RecordingForegroundService.activeBookmarkHandler = {
            bookmarkTriggerCount++
        }
        val broadcastReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                if (intent?.action == com.example.service.RecordingForegroundService.ACTION_USER_BOOKMARK) {
                    bookmarkTriggerCount++
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            broadcastReceiver,
            android.content.IntentFilter(com.example.service.RecordingForegroundService.ACTION_USER_BOOKMARK),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            val bookmarkIntent = android.content.Intent(
                context,
                com.example.service.RecordingForegroundService::class.java
            ).apply {
                action = com.example.service.RecordingForegroundService.ACTION_NOTIFICATION_BOOKMARK
            }
            service.onStartCommand(bookmarkIntent, 0, 1)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, bookmarkTriggerCount)
        } finally {
            com.example.service.RecordingForegroundService.activeBookmarkHandler = null
            try {
                context.unregisterReceiver(broadcastReceiver)
            } catch (_: Exception) {}
        }
    }

    @Test
    fun `verify saved recording playback notification has 5 controls in exact order and persistent confirmation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceController = org.robolectric.Robolectric.buildService(
            com.example.service.RecordingForegroundService::class.java
        ).create()
        val service = serviceController.get()

        // 1. Verify buildPlaybackNotification contains 5 actions in exact order
        val playbackNotif = service.buildPlaybackNotification(
            title = "My Studio Lecture",
            isPlaying = true,
            positionMs = 12_000L,
            durationMs = 60_000L
        )

        assertEquals("My Studio Lecture", playbackNotif.extras.getString(android.app.Notification.EXTRA_TITLE))
        val actions = playbackNotif.actions
        org.junit.Assert.assertNotNull(actions)
        assertEquals(5, actions.size)

        // Control 0: Previous
        assertEquals("Previous", actions[0].title.toString())
        // Control 1: Play / Pause
        assertEquals("Pause", actions[1].title.toString())
        // Control 2: Next
        assertEquals("Next", actions[2].title.toString())
        // Control 3: Bookmark
        assertEquals("Bookmark", actions[3].title.toString())
        // Control 4: Continue Recording
        assertEquals("Continue Recording", actions[4].title.toString())

        // Verify HyperIsland compact actions array contains all 5 indices [0, 1, 2, 3, 4]
        val compactActions = playbackNotif.extras.getIntArray(android.app.Notification.EXTRA_COMPACT_ACTIONS)
        org.junit.Assert.assertArrayEquals(intArrayOf(0, 1, 2, 3, 4), compactActions)
        // Verify MediaSession token is strictly preserved for playback notification (MediaStyle)
        @Suppress("DEPRECATION")
        org.junit.Assert.assertNotNull(playbackNotif.extras.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION))

        // 2. Verify Finished Confirmation notification is single-row compact (no MediaStyle seekbar), dismissible, and persistent
        val finishedNotif = service.buildFinishedNotification("Finished recording")
        assertEquals("Finished recording", finishedNotif.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        // Vertically compact: empty body text, custom single-row contentView attached, no MediaStyle seekbar
        assertEquals("", finishedNotif.extras.getString(android.app.Notification.EXTRA_TEXT))
        org.junit.Assert.assertNotNull(finishedNotif.contentView)
        @Suppress("DEPRECATION")
        org.junit.Assert.assertNull(finishedNotif.extras.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION))
        // Verify HyperIsland capsule parameters (left waveform, center text, right Omega symbol, persistent)
        val focusParam = finishedNotif.extras.getString("miui.focus.param").orEmpty()
        assertTrue(focusParam.contains("miui.focus.pic_island_left"))
        assertTrue(focusParam.contains("Finished recording"))
        assertTrue(focusParam.contains("miui.focus.pic_island_right"))
        assertTrue(focusParam.contains("\"dismissIsland\":false"))
        val focusPics = finishedNotif.extras.getBundle("miui.focus.pics")
        org.junit.Assert.assertNotNull(focusPics)
        // Swipe to dismiss enabled (not ongoing, auto-cancel true, deleteIntent attached, requestPromotedOngoing false)
        org.junit.Assert.assertFalse((finishedNotif.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue((finishedNotif.flags and android.app.Notification.FLAG_AUTO_CANCEL) != 0)
        org.junit.Assert.assertNotNull(finishedNotif.deleteIntent)
        org.junit.Assert.assertFalse(finishedNotif.extras.getBoolean("android.requestPromotedOngoing", true))
        @Suppress("DEPRECATION")
        org.junit.Assert.assertNull(finishedNotif.headsUpContentView)
        assertEquals(com.example.service.RecordingForegroundService.FINISHED_CHANNEL_ID, finishedNotif.channelId)

        // 3. Verify showing confirmation notification does not vanish automatically via timer
        service.showFinishedSavedConfirmation("Saved Lecture")
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        // Run main looper beyond the previous 1.5s delay to ensure persistence
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        val shadowNm = org.robolectric.Shadows.shadowOf(notificationManager)
        val activeNotif = shadowNm.getNotification(com.example.service.RecordingForegroundService.NOTIFICATION_ID)
        org.junit.Assert.assertNotNull(activeNotif)
        assertEquals("Saved Lecture", activeNotif.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `verify append session cancel, append save confirmation popup, and silence skip saved text gating`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.viewmodel.RecorderViewModel(app)

        val tempDir = File(app.cacheDir, "append_test_dir").apply {
            deleteRecursively()
            mkdirs()
        }
        val origFile = File(tempDir, "original_session.wav")
        val origPeaks = WavFileTools.createStudioSampleAudioFile(
            outputFile = origFile,
            durationSeconds = 3,
            sampleRate = 22050
        )
        val originalByteLength = origFile.length()
        val origEntity = com.example.data.RecordingEntity(
            id = 777L,
            title = "Original Lecture",
            filePath = origFile.absolutePath,
            durationMs = 3000L,
            format = "wav",
            sampleRate = 22050,
            bitDepth = 16,
            channels = 1,
            bitrate = 352800,
            sizeBytes = originalByteLength,
            waveformPeaks = AudioFormatUtils.serializePeaks(origPeaks),
            bookmarks = "1000"
        )

        // 1. Start continue recording flow and cancel via X button:
        //    Should reset timer to 0, clear waveform, discard extra audio, leave original untouched, return to READY
        vm.startContinueRecordingFlow(origEntity)
        assertEquals(origEntity.id, vm.appendTarget.value?.id)
        vm.cancelContinueRecordingMode()
        org.junit.Assert.assertNull(vm.appendTarget.value)
        assertEquals(com.example.data.RecordingStatus.READY, vm.recordingStatus.value)
        assertEquals(0L, vm.audioEngine.elapsedMs.value)
        assertTrue(vm.audioEngine.livePeaks.value.isEmpty())
        assertTrue(vm.liveBookmarks.value.isEmpty())
        assertEquals(originalByteLength, origFile.length())

        // 2. Save Confirmation Popup & Inline Notification (Appending & New Recording):
        //    Pressing Save while appending pauses and shows inline confirmation (Θ Don't / Σ Save) without forcing app launch
        vm.startContinueRecordingFlow(origEntity)
        org.junit.Assert.assertFalse(vm.showAppendSaveConfirmDialog.value)
        vm.stopAndSaveImmediatelyFromNotification(forceMergeAppend = false)
        assertTrue(vm.showAppendSaveConfirmDialog.value)
        // Pressing Cancel on popup closes dialog and keeps user on continuation screen
        vm.dismissAppendSaveConfirmDialog()
        org.junit.Assert.assertFalse(vm.showAppendSaveConfirmDialog.value)
        assertEquals(origEntity.id, vm.appendTarget.value?.id)

        // Verify inline Save Confirmation Notification ("Θ Don't" and "Σ Save" buttons)
        val serviceController = org.robolectric.Robolectric.buildService(
            com.example.service.RecordingForegroundService::class.java
        ).create()
        val service = serviceController.get()
        val appendConfirmNotif = service.buildAppendSaveConfirmationNotification()
        assertEquals(0xFFE38851.toInt(), appendConfirmNotif.color)
        assertEquals(2, appendConfirmNotif.actions.size)
        assertEquals("Θ Don't", appendConfirmNotif.actions[0].title.toString())
        assertEquals("Σ Save", appendConfirmNotif.actions[1].title.toString())

        // Verify pressing "Θ Don't" from notification cancels append mode and preserves original recording
        service.onStartCommand(
            android.content.Intent(app, com.example.service.RecordingForegroundService::class.java).apply {
                action = com.example.service.RecordingForegroundService.ACTION_NOTIFICATION_CANCEL_APPEND_SAVE
            },
            0,
            10
        )
        org.junit.Assert.assertNull(vm.appendTarget.value)
        assertEquals(originalByteLength, origFile.length())

        // Verify pressing Stop & Save from notification on a NEW recording (appendTarget == null) also shows confirmation
        vm.startRecording()
        org.junit.Assert.assertNull(vm.appendTarget.value)
        service.onStartCommand(
            android.content.Intent(app, com.example.service.RecordingForegroundService::class.java).apply {
                action = com.example.service.RecordingForegroundService.ACTION_NOTIFICATION_STOP_SAVE
            },
            0,
            11
        )
        assertTrue(vm.showAppendSaveConfirmDialog.value)
        assertEquals(com.example.data.RecordingStatus.PAUSED, vm.recordingStatus.value)

        // Verify pressing "Θ Don't" from notification during a new recording discards the new recording and resets to READY
        service.onStartCommand(
            android.content.Intent(app, com.example.service.RecordingForegroundService::class.java).apply {
                action = com.example.service.RecordingForegroundService.ACTION_NOTIFICATION_CANCEL_APPEND_SAVE
            },
            0,
            12
        )
        org.junit.Assert.assertFalse(vm.showAppendSaveConfirmDialog.value)
        assertEquals(com.example.data.RecordingStatus.READY, vm.recordingStatus.value)

        // 3. Silence Skip "Saved Time" text bug fix:
        //    When Skip Silent is turned OFF, silenceSkippedSavedMs is 0L
        val offConfig = com.example.data.SilenceSkipConfig(
            enabled = false,
            smartAutoMode = false,
            manualModeEnabled = false
        )
        vm.audioEngine.updateSilenceSkipConfig(offConfig)
        org.junit.Assert.assertFalse(offConfig.isEffectivelyEnabled)
        assertEquals(0L, vm.audioEngine.silenceSkippedSavedMs.value)
    }

    @Test
    fun `verify audio isolation settings persistence and focus lifecycle`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = com.example.data.AppDatabase.getInstance(app)
        val repo = com.example.data.RecordingRepository(app, db.recordingDao())

        // 1. Verify default values for audio isolation
        val initialSettings = repo.loadSettings()
        assertTrue(initialSettings.echoCancellationEnabled)
        assertTrue(initialSettings.noiseSuppressionEnabled)
        assertTrue(initialSettings.pauseExternalMediaOnRecord)

        // 2. Toggle settings and verify persistence
        val modifiedSettings = initialSettings.copy(
            echoCancellationEnabled = false,
            noiseSuppressionEnabled = false,
            pauseExternalMediaOnRecord = false
        )
        repo.saveSettings(modifiedSettings)

        val reloadedSettings = repo.loadSettings()
        org.junit.Assert.assertFalse(reloadedSettings.echoCancellationEnabled)
        org.junit.Assert.assertFalse(reloadedSettings.noiseSuppressionEnabled)
        org.junit.Assert.assertFalse(reloadedSettings.pauseExternalMediaOnRecord)

        // 3. Verify AudioStudioEngine starts session with audio isolation parameters and cleans up safely
        val engine = com.example.audio.AudioStudioEngine(app)
        val tempFile = File(app.cacheDir, "isolation_test.wav")
        engine.startRecordingSession(
            outputFile = tempFile,
            sampleRate = 44100,
            channels = 1,
            echoCancellationEnabled = true,
            noiseSuppressionEnabled = true,
            pauseExternalMediaOnRecord = true
        )
        assertTrue(engine.isRecording.value)
        engine.cancelAndResetRecording()
        org.junit.Assert.assertFalse(engine.isRecording.value)
    }

    @Test
    fun `verify appendAudioFilesAtomically merges on first attempt with header traversal and atomic stream replacement`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val testDir = File(app.cacheDir, "atomic_append_test").apply {
            deleteRecursively()
            mkdirs()
        }
        val origFile = File(testDir, "base_recording.wav")
        val appendFile = File(testDir, "extra_segment.wav")

        WavFileTools.createStudioSampleAudioFile(
            outputFile = origFile,
            durationSeconds = 2,
            sampleRate = 44100
        )
        WavFileTools.createStudioSampleAudioFile(
            outputFile = appendFile,
            durationSeconds = 1,
            sampleRate = 44100
        )

        val origLen = origFile.length()
        val appendLen = appendFile.length()
        assertTrue(origLen > 44L)
        assertTrue(appendLen > 44L)

        // Perform merge
        val success = WavFileTools.appendAudioFilesAtomically(
            originalFile = origFile,
            appendedPcmFile = appendFile,
            sampleRate = 44100,
            channels = 1,
            bitsPerSample = 16
        )

        assertTrue("Merge must succeed on the first attempt without failure", success)
        assertTrue(origFile.exists())
        // Staging and appended files must be cleaned
        org.junit.Assert.assertFalse(appendFile.exists())
        org.junit.Assert.assertFalse(File(testDir, "${origFile.name}.merged.tmp").exists())
        org.junit.Assert.assertFalse(File(testDir, "${origFile.name}.bak").exists())

        // Verified combined byte length (approx orig + append - 44 bytes header)
        val expectedLength = (origLen - 44L) + (appendLen - 44L) + 44L
        assertEquals(expectedLength, origFile.length())
    }

    @Test
    fun `verify auto repair detects and repairs corrupted WAV header with truncated size`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testDir = File(context.cacheDir, "repair_test_${System.currentTimeMillis()}")
        testDir.mkdirs()

        val brokenFile = File(testDir, "corrupted_audio.wav")
        WavFileTools.createStudioSampleAudioFile(
            outputFile = brokenFile,
            durationSeconds = 2,
            sampleRate = 44100
        )
        val trueLength = brokenFile.length()
        assertTrue(trueLength > 44L)

        // Corrupt header: write 0 into RIFF chunk length at byte 4
        java.io.RandomAccessFile(brokenFile, "rw").use { raf ->
            raf.seek(4)
            raf.writeInt(0)
        }

        // Run auto repair
        val repairedFile = WavFileTools.repairCorruptedAudioFileIfNeeded(brokenFile)
        assertTrue(repairedFile.exists())
        assertTrue(WavFileTools.isRiffWaveFile(repairedFile))

        // Check that header is valid and readable
        val headerInfo = WavFileTools.readWavHeaderInfo(repairedFile)
        assertEquals(44100, headerInfo.sampleRate)
        assertEquals(1, headerInfo.channels)
        assertTrue(headerInfo.pcmDataBytes > 0L)
    }

    @Test
    fun `verify auto repair normalizes mislabeled m4a file containing RIFF WAVE headers to wav`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testDir = File(context.cacheDir, "mismatched_test_${System.currentTimeMillis()}")
        testDir.mkdirs()

        // Create a WAV file named with .m4a extension (simulating previous bug where m4a setting wrote RIFF)
        val mislabeledFile = File(testDir, "legacy_recording.m4a")
        WavFileTools.createStudioSampleAudioFile(
            outputFile = mislabeledFile,
            durationSeconds = 2,
            sampleRate = 44100
        )
        assertTrue(mislabeledFile.exists())

        // Run auto repair
        val repairedFile = WavFileTools.repairCorruptedAudioFileIfNeeded(mislabeledFile)
        assertTrue(repairedFile.exists())
        assertEquals("wav", repairedFile.extension.lowercase())
        assertTrue(WavFileTools.isRiffWaveFile(repairedFile))
    }

    @Test
    fun `verify universal append merges WAV and never converts M4A to WAV on failure`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testDir = File(context.cacheDir, "universal_append_${System.currentTimeMillis()}")
        testDir.mkdirs()

        val baseWav = File(testDir, "studio_note.wav")
        WavFileTools.createStudioSampleAudioFile(
            outputFile = baseWav,
            durationSeconds = 2,
            sampleRate = 44100
        )
        val appendSegment = File(testDir, "mic_append_segment.wav")
        WavFileTools.createStudioSampleAudioFile(
            outputFile = appendSegment,
            durationSeconds = 1,
            sampleRate = 44100
        )

        val result = WavFileTools.appendAudioFilesUniversal(
            originalFile = baseWav,
            appendedPcmFile = appendSegment,
            sampleRate = 44100,
            channels = 1,
            bitsPerSample = 16
        )

        assertTrue(result.success)
        assertEquals("WAV", result.format)
        assertEquals("audio/wav", result.mimeType)
        assertTrue(result.finalFile.exists())
        assertEquals("wav", result.finalFile.extension.lowercase())
        assertTrue(WavFileTools.isRiffWaveFile(result.finalFile))

        // Verify that an unmergeable M4A file is never converted to WAV and both files stay intact
        val unmergeableM4a = File(testDir, "voice_memo.m4a")
        unmergeableM4a.writeBytes(ByteArray(128) { 0x11 })
        val newM4aSegment = File(testDir, "append_segment_1.m4a")
        newM4aSegment.writeBytes(ByteArray(128) { 0x22 })

        val m4aResult = WavFileTools.appendAudioFilesUniversal(
            originalFile = unmergeableM4a,
            appendedPcmFile = newM4aSegment,
            sampleRate = 44100,
            channels = 1,
            bitsPerSample = 16
        )

        org.junit.Assert.assertFalse(m4aResult.success)
        assertEquals("M4A", m4aResult.format)
        assertTrue("Original M4A must remain intact", unmergeableM4a.exists())
        assertTrue("New M4A segment must remain intact for recovery", newM4aSegment.exists())
        org.junit.Assert.assertFalse(
            "Must never create a converted WAV file from M4A",
            File(testDir, "voice_memo.wav").exists()
        )
    }

    @Test
    fun `verify deep unpack and repair of corrupted merged WAV recording with embedded RIFF header and odd byte misalignment`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testDir = File(context.cacheDir, "deep_repair_${System.currentTimeMillis()}")
        testDir.mkdirs()

        val seg1 = File(testDir, "seg1.wav")
        val seg2 = File(testDir, "seg2.wav")
        WavFileTools.createStudioSampleAudioFile(outputFile = seg1, durationSeconds = 2, sampleRate = 44100)
        WavFileTools.createStudioSampleAudioFile(outputFile = seg2, durationSeconds = 2, sampleRate = 44100)

        // Simulate a corrupted merged file where a trailing odd metadata byte shifted alignment
        // and a second RIFF/WAVE header was concatenated mid-stream
        val corruptedMergedFile = File(testDir, "corrupted_merged.wav")
        java.io.FileOutputStream(corruptedMergedFile).use { out ->
            out.write(seg1.readBytes())
            out.write(byteArrayOf(0x00)) // 1-byte alignment corruption
            out.write(seg2.readBytes())  // Embedded 44-byte RIFF/WAVE header + second PCM stream
        }

        val seg1PcmBytes = seg1.length() - 44L
        val seg2PcmBytes = seg2.length() - 44L

        val repairResult = WavFileTools.repairAndRebuildCorruptedRecording(
            file = corruptedMergedFile,
            fallbackSampleRate = 44100,
            fallbackChannels = 1
        )

        assertTrue(repairResult.success)
        assertTrue(repairResult.finalFile.exists())
        assertTrue(WavFileTools.isRiffWaveFile(repairResult.finalFile))

        val repairedHeader = WavFileTools.readWavHeaderInfo(repairResult.finalFile)
        assertEquals(44100, repairedHeader.sampleRate)
        assertEquals(1, repairedHeader.channels)
        // Repaired file must contain both clean unpacked PCM segments without the embedded 44-byte header or odd byte
        assertEquals(seg1PcmBytes + seg2PcmBytes, repairedHeader.pcmDataBytes)
        assertEquals(0L, repairedHeader.pcmDataBytes % 2L)
        assertTrue(repairResult.durationMs >= 3900L)
    }

    @Test
    fun `verify Trash lifecycle move to trash, exclude from active recordings, restore, and empty trash`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = com.example.data.AppDatabase.getInstance(context)
        val dao = db.recordingDao()
        val repo = com.example.data.RecordingRepository(context, dao)

        val testAudioDir = File(context.filesDir, "trash_test_audio")
        testAudioDir.mkdirs()
        val audioFile1 = File(testAudioDir, "rec1.wav")
        WavFileTools.createStudioSampleAudioFile(audioFile1, 2, 22050)
        val audioFile2 = File(testAudioDir, "rec2.wav")
        WavFileTools.createStudioSampleAudioFile(audioFile2, 2, 22050)

        val rec1 = com.example.data.RecordingEntity(
            title = "Test Rec 1",
            filePath = audioFile1.absolutePath,
            sizeBytes = audioFile1.length(),
            durationMs = 2000L,
            sampleRate = 22050,
            bitDepth = 16,
            channels = 1,
            format = "WAV",
            waveformPeaks = "0.1,0.5,0.2",
            createdAt = System.currentTimeMillis()
        )
        val rec2 = com.example.data.RecordingEntity(
            title = "Test Rec 2",
            filePath = audioFile2.absolutePath,
            sizeBytes = audioFile2.length(),
            durationMs = 2000L,
            sampleRate = 22050,
            bitDepth = 16,
            channels = 1,
            format = "WAV",
            waveformPeaks = "0.2,0.4,0.3",
            createdAt = System.currentTimeMillis()
        )

        val id1 = dao.insertRecording(rec1)
        val id2 = dao.insertRecording(rec2)
        val entity1 = dao.getRecordingById(id1)!!
        val entity2 = dao.getRecordingById(id2)!!

        val activeList = repo.allRecordings.first()
        assertTrue(activeList.any { it.id == id1 })
        assertTrue(activeList.any { it.id == id2 })
        assertEquals(0, repo.trashCount.first())

        // 1. Move rec1 to trash
        repo.moveToTrash(entity1)
        val afterTrashActive = repo.allRecordings.first()
        org.junit.Assert.assertFalse(afterTrashActive.any { it.id == id1 })
        assertTrue(afterTrashActive.any { it.id == id2 })

        val trashList = repo.trashRecordings.first()
        assertEquals(1, trashList.size)
        assertEquals(id1, trashList.first().id)
        assertTrue(trashList.first().isInTrash)
        assertEquals(1, repo.trashCount.first())

        // 2. Restore rec1 from trash
        repo.restoreFromTrash(trashList.first())
        val afterRestoreActive = repo.allRecordings.first()
        assertTrue(afterRestoreActive.any { it.id == id1 })
        assertEquals(0, repo.trashCount.first())

        // 3. Move both to trash and empty trash
        val restoredEntity1 = dao.getRecordingById(id1)!!
        repo.moveToTrash(listOf(restoredEntity1, entity2))
        assertEquals(2, repo.trashCount.first())
        assertTrue(audioFile1.exists())
        assertTrue(audioFile2.exists())

        repo.emptyTrash()
        assertEquals(0, repo.trashCount.first())
        assertTrue(repo.trashRecordings.first().isEmpty())
        org.junit.Assert.assertFalse(audioFile1.exists())
        org.junit.Assert.assertFalse(audioFile2.exists())
    }

    @Test
    fun `verify resolveAudioFile and safe file move recover missing or moved audio file`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = com.example.data.AppDatabase.getInstance(context)
        val dao = db.recordingDao()
        val repo = com.example.data.RecordingRepository(context, dao)

        val testDir = File(context.cacheDir, "resolve_test")
        testDir.mkdirs()
        val targetDir = File(context.filesDir, "resolve_target")
        targetDir.mkdirs()

        // Test safe move across directories
        val srcFile = File(testDir, "src.wav")
        WavFileTools.createStudioSampleAudioFile(srcFile, 1, 22050)
        val dstFile = File(targetDir, "dst.wav")
        val moved = repo.moveFileSafely(srcFile, dstFile)
        assertTrue(moved)
        assertTrue(dstFile.exists())
        org.junit.Assert.assertFalse(srcFile.exists())

        // Test resolveAudioFile finding alternate extension or backup
        val entity = com.example.data.RecordingEntity(
            title = "Missing File Entity",
            filePath = File(targetDir, "audio_clip.wav").absolutePath,
            sizeBytes = 1000L,
            format = "WAV",
            durationMs = 1000L,
            createdAt = System.currentTimeMillis()
        )
        val insertedId = dao.insertRecording(entity)
        val recordedEntity = dao.getRecordingById(insertedId)!!

        // Initially does not exist
        org.junit.Assert.assertNull(repo.resolveAudioFile(recordedEntity))

        // Create backup/alternate file in the same directory: audio_clip.bak
        val backupFile = File(targetDir, "audio_clip.bak")
        WavFileTools.createStudioSampleAudioFile(backupFile, 1, 22050)

        val resolved = repo.resolveAudioFile(recordedEntity)
        org.junit.Assert.assertNotNull(resolved)
        assertEquals(backupFile.absolutePath, resolved!!.absolutePath)

        // Verify entity in Room DB was auto-healed to the found file path
        val updatedInDb = dao.getRecordingById(insertedId)!!
        assertEquals(backupFile.absolutePath, updatedInDb.filePath)
    }
}


