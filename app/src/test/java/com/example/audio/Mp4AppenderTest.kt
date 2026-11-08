package com.example.audio

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.sin

class Mp4AppenderTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("mp4_appender_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun generateSinePcmShorts(sampleCount: Int, sampleRate: Int, freqHz: Double = 440.0): ShortArray {
        return ShortArray(sampleCount) { idx ->
            val t = idx.toDouble() / sampleRate.toDouble()
            (sin(2.0 * Math.PI * freqHz * t) * 14000.0).toInt().toShort()
        }
    }

    private fun createSampleM4aFile(
        file: File,
        sampleRate: Int = 44100,
        channels: Int = 1,
        frameCount: Int = 10,
        moovBeforeMdat: Boolean = false
    ): EncodedAacSegment {
        val rawAac = File(tempDir, "${file.name}.raw.aac")
        val encoder = AacEncoder(
            sampleRate = sampleRate,
            channelCount = channels,
            bitrate = 128_000,
            skipPrimingFrame = false
        )
        encoder.start(rawAac)
        val totalPcmShorts = frameCount * AacEncoder.AAC_FRAME_SAMPLES * channels
        val pcm = generateSinePcmShorts(totalPcmShorts, sampleRate)
        encoder.encodePcmSamples(pcm, pcm.size)
        val segment = encoder.finish()
        val ok = encoder.writeStandaloneM4aFile(
            segment = segment,
            outputM4aFile = file,
            writeMoovBeforeMdat = moovBeforeMdat
        )
        assertTrue("Standalone M4A creation must succeed", ok)
        return segment
    }

    @Test
    fun `moov after mdat lossless append updates stsz stsc stco stts and durations`() {
        val baseFile = File(tempDir, "base_after.m4a")
        createSampleM4aFile(baseFile, sampleRate = 44100, channels = 1, frameCount = 12, moovBeforeMdat = false)

        val baseMeta = Mp4BoxParser.parseM4a(baseFile)
        assertNotNull(baseMeta)
        baseMeta!!
        assertFalse("Expected moov after mdat", baseMeta.isMoovBeforeMdat)
        assertEquals(44100, baseMeta.sampleRate)
        assertEquals(1, baseMeta.channelCount)
        assertEquals(2, baseMeta.aacObjectType)
        assertEquals(12, baseMeta.sampleSizes.size)
        assertEquals(12L, baseMeta.totalSamplesFromStts)

        // Encode an append segment matching base file parameters, with priming skip enabled
        val matchPair = AacEncoder.createMatchingEncoderForBaseFile(baseFile)
        assertNotNull(matchPair)
        val (appendEncoder, _) = matchPair!!
        val rawAppendFile = File(tempDir, "append_seg.aac")
        appendEncoder.start(rawAppendFile)
        // Feed 9 frames (1 priming frame skipped -> 8 frames kept)
        val appendPcm = generateSinePcmShorts(9 * AacEncoder.AAC_FRAME_SAMPLES, 44100, 660.0)
        appendEncoder.encodePcmSamples(appendPcm, appendPcm.size)
        val appendSegment = appendEncoder.finish()

        assertEquals("Should skip 1 priming frame (1024 samples)", 1, appendSegment.primingFramesSkipped)
        assertEquals(8, appendSegment.frameSizes.size)

        val outcome = Mp4Appender.appendEncodedSegmentToM4a(
            baseM4aFile = baseFile,
            segment = appendSegment
        )
        assertTrue("Append should succeed: ${outcome.errorMessage}", outcome.success)

        val mergedMeta = Mp4BoxParser.parseM4a(baseFile)
        assertNotNull(mergedMeta)
        mergedMeta!!

        // Verify sample table consistency: sum(stts) == total samples == stsz count
        assertEquals(20, mergedMeta.sampleSizes.size)
        assertEquals(20L, mergedMeta.totalSamplesFromStts)
        assertTrue(mergedMeta.isSampleTableConsistent())
        assertEquals(2, mergedMeta.chunkOffsets.size)
        assertEquals(2, mergedMeta.stscEntries.size)
        assertEquals(20L * 1024L, mergedMeta.mdhdDuration)

        // Second chunk offset must point immediately after original mdat payload
        val expectedSecondChunkOffset = baseMeta.mdatDataOffset + baseMeta.mdatDataSize
        assertEquals(expectedSecondChunkOffset, mergedMeta.chunkOffsets[1])
    }

    @Test
    fun `moov before mdat append shifts chunk offsets accurately and preserves consistency`() {
        val baseFile = File(tempDir, "base_before.m4a")
        createSampleM4aFile(baseFile, sampleRate = 48000, channels = 2, frameCount = 10, moovBeforeMdat = true)

        val baseMeta = Mp4BoxParser.parseM4a(baseFile)
        assertNotNull(baseMeta)
        baseMeta!!
        assertTrue("Expected moov before mdat", baseMeta.isMoovBeforeMdat)
        assertEquals(48000, baseMeta.sampleRate)
        assertEquals(2, baseMeta.channelCount)
        val originalFirstChunkOffset = baseMeta.chunkOffsets[0]
        val originalMoovSize = baseMeta.moovSize

        // Create 6-frame append segment (with 1 priming frame skipped -> 5 frames added)
        val (appendEncoder, _) = AacEncoder.createMatchingEncoderForBaseFile(baseFile)!!
        val rawAppendFile = File(tempDir, "append_before.aac")
        appendEncoder.start(rawAppendFile)
        val appendPcm = generateSinePcmShorts(6 * AacEncoder.AAC_FRAME_SAMPLES * 2, 48000)
        appendEncoder.encodePcmSamples(appendPcm, appendPcm.size)
        val appendSegment = appendEncoder.finish()
        assertEquals(5, appendSegment.frameSizes.size)

        val outcome = Mp4Appender.appendEncodedSegmentToM4a(
            baseM4aFile = baseFile,
            segment = appendSegment
        )
        assertTrue("moov-before-mdat append should succeed: ${outcome.errorMessage}", outcome.success)

        val mergedMeta = Mp4BoxParser.parseM4a(baseFile)!!
        assertTrue(mergedMeta.isMoovBeforeMdat)
        assertEquals(15, mergedMeta.sampleSizes.size)
        assertEquals(15L, mergedMeta.totalSamplesFromStts)

        val moovDelta = mergedMeta.moovSize - originalMoovSize
        assertTrue("Expanded sample tables must increase moov size", moovDelta > 0L)
        assertEquals(
            "Existing chunk offset must be shifted by exact moov size delta",
            originalFirstChunkOffset + moovDelta,
            mergedMeta.chunkOffsets[0]
        )
        assertEquals(
            "New chunk offset must start immediately after shifted original mdat payload",
            mergedMeta.chunkOffsets[0] + baseMeta.mdatDataSize,
            mergedMeta.chunkOffsets[1]
        )
    }

    @Test
    fun `64-bit co64 and largesize mdat headers serialize and parse accurately`() {
        val baseFile = File(tempDir, "base_64bit.m4a")
        createSampleM4aFile(baseFile, sampleRate = 44100, channels = 1, frameCount = 6, moovBeforeMdat = false)

        val (appendEncoder, _) = AacEncoder.createMatchingEncoderForBaseFile(baseFile)!!
        val rawAppendFile = File(tempDir, "append_64.aac")
        appendEncoder.start(rawAppendFile)
        val appendPcm = generateSinePcmShorts(5 * AacEncoder.AAC_FRAME_SAMPLES, 44100)
        appendEncoder.encodePcmSamples(appendPcm, appendPcm.size)
        val appendSegment = appendEncoder.finish()

        val outcome = Mp4Appender.appendEncodedSegmentToM4a(
            baseM4aFile = baseFile,
            segment = appendSegment,
            force64BitOffsets = true
        )
        assertTrue("64-bit append must succeed: ${outcome.errorMessage}", outcome.success)

        val mergedMeta = Mp4BoxParser.parseM4a(baseFile)!!
        assertTrue("Must upgrade chunk offsets box to co64", mergedMeta.usesCo64)
        assertEquals("Must upgrade mdat box header to 16-byte largesize", 16, mergedMeta.mdatHeaderSize)
        assertEquals(10L, mergedMeta.totalSamplesFromStts)
        assertEquals(10, mergedMeta.sampleSizes.size)

        // Also test > 4GB offset serialization directly in buildChunkOffsetBox
        val hugeOffset = 5_000_000_000L // > 4 GB
        val co64Box = Mp4BoxParser.buildChunkOffsetBox(longArrayOf(hugeOffset))
        val header = Mp4BoxParser.readBufferBoxHeader(co64Box, 0, co64Box.size)!!
        assertEquals("co64", header.type)
    }

    @Test
    fun `empty or corrupt file is rejected safely`() {
        val emptyFile = File(tempDir, "empty.m4a")
        emptyFile.writeBytes(ByteArray(0))

        val dummyRaw = File(tempDir, "dummy.aac")
        dummyRaw.writeBytes(ByteArray(180) { 1 })
        val dummySeg = EncodedAacSegment(
            rawAacFile = dummyRaw,
            frameSizes = intArrayOf(180),
            sampleRate = 44100,
            channelCount = 1,
            aacObjectType = 2,
            audioSpecificConfig = Mp4BoxParser.buildAudioSpecificConfig(2, 44100, 1),
            startPtsSamples = 0L,
            totalEncodedSamples = 1024L,
            primingFramesSkipped = 0
        )

        val outcome = Mp4Appender.appendEncodedSegmentToM4a(emptyFile, dummySeg)
        assertFalse("Empty file must fail cleanly", outcome.success)
        assertEquals(0L, emptyFile.length())
    }

    @Test
    fun `interrupted write rolls back cleanly and preserves original M4A byte for byte`() {
        val baseFile = File(tempDir, "rollback_test.m4a")
        createSampleM4aFile(baseFile, sampleRate = 44100, channels = 1, frameCount = 8, moovBeforeMdat = false)
        val originalBytes = baseFile.readBytes()

        val (appendEncoder, _) = AacEncoder.createMatchingEncoderForBaseFile(baseFile)!!
        val rawAppendFile = File(tempDir, "append_interrupt.aac")
        appendEncoder.start(rawAppendFile)
        val appendPcm = generateSinePcmShorts(5 * AacEncoder.AAC_FRAME_SAMPLES, 44100)
        appendEncoder.encodePcmSamples(appendPcm, appendPcm.size)
        val appendSegment = appendEncoder.finish()

        val outcome = Mp4Appender.appendEncodedSegmentToM4a(
            baseM4aFile = baseFile,
            segment = appendSegment,
            simulateInterruptBeforeRename = true
        )
        assertFalse("Interrupted write must report failure", outcome.success)
        assertArrayEquals(
            "Original M4A file must remain 100% untouched on interrupted write",
            originalBytes,
            baseFile.readBytes()
        )
        val leftoverTemp = File(tempDir, "${baseFile.name}.append.tmp")
        assertFalse("Temporary staging file must be cleaned up on failure", leftoverTemp.exists())
    }

    @Test
    fun `unpackAndRepairM4aFile recovers all frames from concatenated multi-segment M4A file`() {
        val seg1 = File(tempDir, "part1.m4a")
        val seg2 = File(tempDir, "part2.m4a")
        createSampleM4aFile(seg1, sampleRate = 44100, channels = 1, frameCount = 8, moovBeforeMdat = false)
        createSampleM4aFile(seg2, sampleRate = 44100, channels = 1, frameCount = 6, moovBeforeMdat = false)

        // Simulate a corrupted merge where two M4A containers were concatenated or had unmerged moov/mdat boxes
        val corruptedM4a = File(tempDir, "corrupted_concat.m4a")
        java.io.FileOutputStream(corruptedM4a).use { out ->
            out.write(seg1.readBytes())
            out.write(seg2.readBytes())
        }

        val outcome = Mp4Appender.unpackAndRepairM4aFile(
            corruptedFile = corruptedM4a,
            fallbackSampleRate = 44100,
            fallbackChannels = 1
        )
        assertTrue("M4A unpack and repair must succeed: ${outcome.errorMessage}", outcome.success)

        val repairedMeta = Mp4BoxParser.parseM4a(corruptedM4a)
        assertNotNull(repairedMeta)
        repairedMeta!!
        assertEquals("All 14 AAC frames across both segments must be unpacked and merged", 14, repairedMeta.sampleSizes.size)
        assertEquals(14L, repairedMeta.totalSamplesFromStts)
        assertEquals(1, repairedMeta.mdatBoxCount)
        assertTrue(repairedMeta.areAllChunksInsideMdat())
    }

    @Test
    fun `repairAndRebuildCorruptedRecording strips embedded RIFF headers and fixes odd byte alignment shifts in WAV`() {
        val seg1 = File(tempDir, "wav_seg1.wav")
        val seg2 = File(tempDir, "wav_seg2.wav")
        WavFileTools.createStudioSampleAudioFile(outputFile = seg1, durationSeconds = 2, sampleRate = 44100)
        WavFileTools.createStudioSampleAudioFile(outputFile = seg2, durationSeconds = 2, sampleRate = 44100)

        val seg1PcmBytes = seg1.length() - 44L
        val seg2PcmBytes = seg2.length() - 44L

        val corruptedWav = File(tempDir, "corrupted_merged.wav")
        java.io.FileOutputStream(corruptedWav).use { out ->
            out.write(seg1.readBytes())
            out.write(byteArrayOf(0x00)) // 1-byte alignment shift
            out.write(seg2.readBytes())  // Embedded 44-byte RIFF/WAVE header + second PCM stream
        }

        val repairResult = WavFileTools.repairAndRebuildCorruptedRecording(
            file = corruptedWav,
            fallbackSampleRate = 44100,
            fallbackChannels = 1
        )
        assertTrue("WAV deep repair must succeed", repairResult.success)
        assertTrue(WavFileTools.isRiffWaveFile(repairResult.finalFile))

        val repairedHeader = WavFileTools.readWavHeaderInfo(repairResult.finalFile)
        assertEquals(44100, repairedHeader.sampleRate)
        assertEquals(1, repairedHeader.channels)
        assertEquals(seg1PcmBytes + seg2PcmBytes, repairedHeader.pcmDataBytes)
        assertEquals(0L, repairedHeader.pcmDataBytes % 2L)
    }

    @Test
    fun `mergeMultipleAudioFilesNonDestructive merges multiple M4A recordings natively into M4A without re-encoding`() {
        val m4aTrack1 = File(tempDir, "m4a_track1.m4a")
        val m4aTrack2 = File(tempDir, "m4a_track2.m4a")
        val m4aTrack3 = File(tempDir, "m4a_track3.m4a")
        createSampleM4aFile(m4aTrack1, sampleRate = 48000, channels = 2, frameCount = 10, moovBeforeMdat = false)
        createSampleM4aFile(m4aTrack2, sampleRate = 48000, channels = 2, frameCount = 8, moovBeforeMdat = false)
        createSampleM4aFile(m4aTrack3, sampleRate = 48000, channels = 2, frameCount = 6, moovBeforeMdat = false)

        val origBytes1 = m4aTrack1.readBytes()
        val origBytes2 = m4aTrack2.readBytes()
        val origBytes3 = m4aTrack3.readBytes()

        val outputMergedM4a = File(tempDir, "merged_tracks.m4a")
        val mergeResult = WavFileTools.mergeMultipleAudioFilesNonDestructive(
            sourceFiles = listOf(m4aTrack1, m4aTrack2, m4aTrack3),
            outputFile = outputMergedM4a,
            sampleRate = 48000,
            channels = 2,
            targetBitrate = 192_000
        )

        assertTrue("Native M4A multi-track merge must succeed", mergeResult.success)
        assertEquals("M4A", mergeResult.format)
        assertEquals("audio/mp4", mergeResult.mimeType)
        assertEquals("m4a", mergeResult.finalFile.extension.lowercase())
        assertFalse("Merged M4A file must not be a RIFF WAV container", WavFileTools.isRiffWaveFile(mergeResult.finalFile))
        assertTrue("Merged M4A file must be a valid ISO BMFF / M4A container", Mp4BoxParser.isMp4File(mergeResult.finalFile))

        val mergedMeta = Mp4BoxParser.parseM4a(mergeResult.finalFile)
        assertNotNull(mergedMeta)
        mergedMeta!!
        assertEquals("Sample rate must be preserved from source M4A", 48000, mergedMeta.sampleRate)
        assertEquals("Channel configuration must be preserved from source M4A", 2, mergedMeta.channelCount)
        assertTrue("Merged M4A sample table must be consistent", mergedMeta.isSampleTableConsistent())
        // 10 frames (track 1) + (8 - 1 priming skipped = 7 frames) + (6 - 1 priming skipped = 5 frames) = 22 frames
        assertEquals(22, mergedMeta.sampleSizes.size)
        assertEquals(22L, mergedMeta.totalSamplesFromStts)

        // Verify original source M4A files remain 100% untouched byte-for-byte
        assertArrayEquals(origBytes1, m4aTrack1.readBytes())
        assertArrayEquals(origBytes2, m4aTrack2.readBytes())
        assertArrayEquals(origBytes3, m4aTrack3.readBytes())
    }

    @Test
    fun `concatenateAudioFilesPhysicallyToM4a processes all selected tracks in order with cumulative PTS offsetting and single final EOS`() {
        val trackA = File(tempDir, "track_a_1s.wav")
        val trackB = File(tempDir, "track_b_2s.wav")
        val trackC = File(tempDir, "track_c_1s.wav")
        WavFileTools.createStudioSampleAudioFile(outputFile = trackA, durationSeconds = 1, sampleRate = 22050)
        WavFileTools.createStudioSampleAudioFile(outputFile = trackB, durationSeconds = 2, sampleRate = 44100)
        WavFileTools.createStudioSampleAudioFile(outputFile = trackC, durationSeconds = 1, sampleRate = 22050)

        // Merge in explicit user-selected order: trackB (2s) -> trackC (1s) -> trackA (1s) = 4s (4000ms)
        val mergedM4a = File(tempDir, "merged_multi_order.m4a")
        val result = WavFileTools.concatenateAudioFilesPhysicallyToM4a(
            sourceFiles = listOf(trackB, trackC, trackA),
            outputM4aFile = mergedM4a,
            targetSampleRate = 44100,
            targetChannels = 1,
            targetBitrate = 128_000
        )

        assertTrue("Multi-track physical M4A concatenation must succeed", result.success)
        assertEquals("M4A", result.format)
        assertTrue("Merged M4A file must exist on disk", mergedM4a.exists())

        val meta = Mp4BoxParser.parseM4a(mergedM4a)
        assertNotNull(meta)
        meta!!
        assertTrue("Sample table must be consistent", meta.isSampleTableConsistent())
        // 4 seconds at 44100Hz = 176,400 samples -> ~172-173 AAC frames (not just Track 1's ~86 frames!)
        assertTrue(
            "Merged M4A must contain frames from all 3 tracks (expected >= 165 frames, got ${meta.sampleSizes.size})",
            meta.sampleSizes.size >= 165
        )
        assertTrue(
            "Merged M4A duration (${meta.durationMs}ms) must equal sum of all 3 tracks (~4000ms), not just Track 1",
            meta.durationMs in 3900L..4100L
        )
    }
}


