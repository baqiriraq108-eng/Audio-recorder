package com.example.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs

data class Mp4AppendOutcome(
    val success: Boolean,
    val finalFile: File,
    val totalSamples: Long = 0L,
    val totalDurationMs: Long = 0L,
    val usedTranscodeFallback: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Lossless MP4 / M4A container appender (Samsung Recorder approach).
 *
 * Appends raw AAC-LC access units directly into an existing M4A file's `mdat` atom and patches
 * the `moov` hierarchy (`stsz`, `stsc`, `stco`/`co64`, `stts`, `mdhd`, `tkhd`, `mvhd`, `edts`/`elst`)
 * on a staging copy before atomically replacing the original file.
 */
object Mp4Appender {

    /**
     * Appends an [EncodedAacSegment] onto [baseM4aFile] without re-encoding existing audio.
     * Works on a temporary staging file in the same directory and atomically swaps on success.
     * If any step or validation fails, [baseM4aFile] remains completely untouched.
     */
    fun appendEncodedSegmentToM4a(
        baseM4aFile: File,
        segment: EncodedAacSegment,
        force64BitOffsets: Boolean = false,
        simulateInterruptBeforeRename: Boolean = false,
        onProgress: ((Float) -> Unit)? = null
    ): Mp4AppendOutcome {
        if (!baseM4aFile.exists() || baseM4aFile.length() < 32L) {
            return Mp4AppendOutcome(
                success = false,
                finalFile = baseM4aFile,
                errorMessage = "Base M4A file is missing or empty."
            )
        }
        if (!segment.rawAacFile.exists() || segment.rawAacFile.length() == 0L || segment.frameSizes.isEmpty()) {
            return Mp4AppendOutcome(
                success = false,
                finalFile = baseM4aFile,
                errorMessage = "Encoded AAC segment is empty."
            )
        }

        val expectedSegmentBytes = segment.frameSizes.sumOf { it.toLong() }
        if (expectedSegmentBytes != segment.rawAacFile.length()) {
            return Mp4AppendOutcome(
                success = false,
                finalFile = baseM4aFile,
                errorMessage = "Segment frame sizes sum ($expectedSegmentBytes) does not match file length (${segment.rawAacFile.length()})."
            )
        }

        onProgress?.invoke(0.08f)
        val meta = Mp4BoxParser.parseM4a(baseM4aFile)
            ?: return Mp4AppendOutcome(
                success = false,
                finalFile = baseM4aFile,
                errorMessage = "Unable to parse base M4A container structure."
            )

        if (!meta.isSampleTableConsistent() || !meta.areAllChunksInsideMdat()) {
            return Mp4AppendOutcome(
                success = false,
                finalFile = baseM4aFile,
                errorMessage = "Base M4A sample tables (stts/stsz/stco) are inconsistent or span multiple mdat boxes."
            )
        }

        // Verify audio parameter compatibility (sampleRate, channelCount, AAC-LC profile, and AudioSpecificConfig)
        val ascCompatible = meta.audioSpecificConfig.isEmpty() ||
            segment.audioSpecificConfig.isEmpty() ||
            meta.audioSpecificConfig.contentEquals(segment.audioSpecificConfig)
        if (meta.sampleRate != segment.sampleRate ||
            meta.channelCount != segment.channelCount ||
            meta.aacObjectType != segment.aacObjectType ||
            !ascCompatible
        ) {
            return Mp4AppendOutcome(
                success = false,
                finalFile = baseM4aFile,
                errorMessage = "Audio parameter or AudioSpecificConfig mismatch between base M4A and appended segment."
            )
        }

        val parentDir = baseM4aFile.parentFile ?: File(".")
        val stagingFile = File(parentDir, "${baseM4aFile.name}.append.tmp")
        if (stagingFile.exists()) stagingFile.delete()

        return try {
            val originalMoovBytes = ByteArray(meta.moovSize.toInt())
            RandomAccessFile(baseM4aFile, "r").use { raf ->
                raf.seek(meta.moovOffset)
                raf.readFully(originalMoovBytes)
            }

            // 1. Build merged stsz (sample sizes)
            val oldSampleCount = meta.sampleSizes.size
            val addedSampleCount = segment.frameSizes.size
            val totalSampleCount = oldSampleCount + addedSampleCount
            val mergedSampleSizes = IntArray(totalSampleCount)
            System.arraycopy(meta.sampleSizes, 0, mergedSampleSizes, 0, oldSampleCount)
            System.arraycopy(segment.frameSizes, 0, mergedSampleSizes, oldSampleCount, addedSampleCount)

            // 2. Build merged stts (1024 samples per AAC-LC frame, scaled if mdhdTimescale != sampleRate)
            val frameDeltaInMdhd = if (meta.mdhdTimescale == meta.sampleRate || meta.sampleRate <= 0) {
                AacEncoder.AAC_FRAME_SAMPLES
            } else {
                ((AacEncoder.AAC_FRAME_SAMPLES.toLong() * meta.mdhdTimescale.toLong()) / meta.sampleRate.toLong()).toInt()
                    .coerceAtLeast(1)
            }
            val mergedStts = ArrayList<SttsEntry>(meta.sttsEntries.size + 1)
            mergedStts.addAll(meta.sttsEntries)
            if (mergedStts.isNotEmpty() && mergedStts.last().sampleDelta == frameDeltaInMdhd) {
                val last = mergedStts.removeAt(mergedStts.lastIndex)
                mergedStts.add(SttsEntry(sampleCount = last.sampleCount + addedSampleCount, sampleDelta = frameDeltaInMdhd))
            } else {
                mergedStts.add(SttsEntry(sampleCount = addedSampleCount, sampleDelta = frameDeltaInMdhd))
            }

            // Verify invariant: sum(stts) == total samples == stsz count
            val sttsTotalSamples = mergedStts.sumOf { it.sampleCount }
            if (sttsTotalSamples != mergedSampleSizes.size) {
                stagingFile.delete()
                return Mp4AppendOutcome(
                    success = false,
                    finalFile = baseM4aFile,
                    errorMessage = "Sample table invariant violated: sum(stts)=$sttsTotalSamples != stsz=${mergedSampleSizes.size}"
                )
            }

            // 3. Build merged stsc (add new chunk entry for the appended contiguous run of AAC frames)
            val newChunkIndexOneBased = meta.chunkOffsets.size + 1
            val validOldStsc = meta.stscEntries.filter {
                it.firstChunk in 1..meta.chunkOffsets.size && it.samplesPerChunk > 0
            }
            val mergedStsc = ArrayList<StscEntry>(validOldStsc.size + 1)
            mergedStsc.addAll(validOldStsc)
            val sampleDescIdx = validOldStsc.lastOrNull()?.sampleDescriptionIndex ?: 1
            mergedStsc.add(
                StscEntry(
                    firstChunk = newChunkIndexOneBased,
                    samplesPerChunk = addedSampleCount,
                    sampleDescriptionIndex = sampleDescIdx
                )
            )

            onProgress?.invoke(0.25f)

            val newMdatPayloadBytes = meta.mdatDataSize + expectedSegmentBytes
            val needs64BitMdat = force64BitOffsets ||
                meta.mdatHeaderSize == 16 ||
                (newMdatPayloadBytes + 16L > 0xFFFFFFFFL)
            val newMdatHeaderSize = if (needs64BitMdat) 16 else 8
            val newMdatTotalSize = newMdatHeaderSize.toLong() + newMdatPayloadBytes
            val mdatHeaderDelta = (newMdatHeaderSize - meta.mdatHeaderSize).toLong()

            if (!meta.isMoovBeforeMdat) {
                // LAYOUT A: moov is AFTER mdat (ftyp -> mdat -> moov)
                // Shift existing chunk offsets only if mdat header expanded from 8 to 16 bytes
                val mergedChunkOffsets = LongArray(meta.chunkOffsets.size + 1)
                for (i in meta.chunkOffsets.indices) {
                    mergedChunkOffsets[i] = meta.chunkOffsets[i] + mdatHeaderDelta
                }
                val newChunkOffset = meta.mdatOffset + newMdatHeaderSize.toLong() + meta.mdatDataSize
                mergedChunkOffsets[meta.chunkOffsets.size] = newChunkOffset

                val useCo64 = force64BitOffsets ||
                    meta.usesCo64 ||
                    mergedChunkOffsets.any { it > 0xFFFFFFFFL }

                val patchedMoovBytes = Mp4BoxParser.patchMoovBytes(
                    originalMoovBytes = originalMoovBytes,
                    newSttsEntries = mergedStts,
                    newStscEntries = mergedStsc,
                    newSampleSizes = mergedSampleSizes,
                    newChunkOffsets = mergedChunkOffsets,
                    mdhdTimescale = meta.mdhdTimescale,
                    mvhdTimescale = meta.mvhdTimescale,
                    forceCo64 = useCo64
                )

                if (mdatHeaderDelta == 0L && meta.moovOffset == meta.mdatOffset + meta.mdatSize) {
                    // Fast path: copy up to end of mdat (or copy file and overwrite old moov at mdat end),
                    // patch mdat header in-place, append new AAC frames, and write updated moov at EOF.
                    FileOutputStream(stagingFile).use { fos ->
                        RandomAccessFile(baseM4aFile, "r").use { rafIn ->
                            copyRange(rafIn, fos, 0L, meta.mdatOffset + meta.mdatSize)
                        }
                        onProgress?.invoke(0.60f)
                        FileInputStream(segment.rawAacFile).use { segIn ->
                            segIn.copyTo(fos, 65536)
                        }
                        onProgress?.invoke(0.80f)
                        fos.write(patchedMoovBytes)
                        fos.flush()
                    }
                    // Patch updated mdat size header in staging file
                    val updatedMdatHeader = Mp4BoxParser.buildMdatHeader(newMdatTotalSize, force64Bit = needs64BitMdat)
                    RandomAccessFile(stagingFile, "rw").use { rafOut ->
                        rafOut.seek(meta.mdatOffset)
                        rafOut.write(updatedMdatHeader)
                    }
                } else {
                    // Header size upgraded (8 -> 16 bytes) or extra boxes existed between mdat and moov
                    val updatedMdatHeader = Mp4BoxParser.buildMdatHeader(newMdatTotalSize, force64Bit = needs64BitMdat)
                    FileOutputStream(stagingFile).use { fos ->
                        RandomAccessFile(baseM4aFile, "r").use { rafIn ->
                            copyRange(rafIn, fos, 0L, meta.mdatOffset)
                            fos.write(updatedMdatHeader)
                            copyRange(rafIn, fos, meta.mdatDataOffset, meta.mdatDataSize)
                        }
                        onProgress?.invoke(0.60f)
                        FileInputStream(segment.rawAacFile).use { segIn ->
                            segIn.copyTo(fos, 65536)
                        }
                        onProgress?.invoke(0.80f)
                        fos.write(patchedMoovBytes)
                        fos.flush()
                    }
                }
            } else {
                // LAYOUT B: moov is BEFORE mdat (ftyp -> moov -> mdat)
                // Rebuilding moov increases its size, which shifts mdat and all chunk offsets forward.
                val provisionalOffsets = LongArray(meta.chunkOffsets.size + 1)
                val provisionalCo64 = force64BitOffsets ||
                    meta.usesCo64 ||
                    (meta.mdatOffset + newMdatTotalSize + 1_048_576L > 0xFFFFFFFFL)

                val preliminaryMoov = Mp4BoxParser.patchMoovBytes(
                    originalMoovBytes = originalMoovBytes,
                    newSttsEntries = mergedStts,
                    newStscEntries = mergedStsc,
                    newSampleSizes = mergedSampleSizes,
                    newChunkOffsets = provisionalOffsets,
                    mdhdTimescale = meta.mdhdTimescale,
                    mvhdTimescale = meta.mvhdTimescale,
                    forceCo64 = provisionalCo64
                )

                val moovSizeDelta = preliminaryMoov.size.toLong() - meta.moovSize
                val totalShiftForExistingChunks = moovSizeDelta + mdatHeaderDelta

                val shiftedChunkOffsets = LongArray(meta.chunkOffsets.size + 1)
                for (i in meta.chunkOffsets.indices) {
                    shiftedChunkOffsets[i] = meta.chunkOffsets[i] + totalShiftForExistingChunks
                }
                val newMdatOffset = meta.mdatOffset + moovSizeDelta
                val newChunkOffset = newMdatOffset + newMdatHeaderSize.toLong() + meta.mdatDataSize
                shiftedChunkOffsets[meta.chunkOffsets.size] = newChunkOffset

                val finalMoovBytes = Mp4BoxParser.patchMoovBytes(
                    originalMoovBytes = originalMoovBytes,
                    newSttsEntries = mergedStts,
                    newStscEntries = mergedStsc,
                    newSampleSizes = mergedSampleSizes,
                    newChunkOffsets = shiftedChunkOffsets,
                    mdhdTimescale = meta.mdhdTimescale,
                    mvhdTimescale = meta.mvhdTimescale,
                    forceCo64 = provisionalCo64
                )

                val updatedMdatHeader = Mp4BoxParser.buildMdatHeader(newMdatTotalSize, force64Bit = needs64BitMdat)
                FileOutputStream(stagingFile).use { fos ->
                    RandomAccessFile(baseM4aFile, "r").use { rafIn ->
                        // 1. Copy prefix before moov (e.g. ftyp)
                        copyRange(rafIn, fos, 0L, meta.moovOffset)
                        // 2. Write updated moov with shifted chunk offsets
                        fos.write(finalMoovBytes)
                        // 3. Copy any boxes between end of old moov and start of old mdat (e.g. free/skip)
                        val betweenStart = meta.moovOffset + meta.moovSize
                        val betweenLen = (meta.mdatOffset - betweenStart).coerceAtLeast(0L)
                        if (betweenLen > 0L) {
                            copyRange(rafIn, fos, betweenStart, betweenLen)
                        }
                        // 4. Write updated mdat header + original mdat payload
                        fos.write(updatedMdatHeader)
                        copyRange(rafIn, fos, meta.mdatDataOffset, meta.mdatDataSize)
                    }
                    onProgress?.invoke(0.65f)
                    // 5. Append new AAC access units
                    FileInputStream(segment.rawAacFile).use { segIn ->
                        segIn.copyTo(fos, 65536)
                    }
                    fos.flush()
                }
            }

            if (simulateInterruptBeforeRename) {
                throw IllegalStateException("Simulated interrupted write before atomic rename.")
            }

            onProgress?.invoke(0.90f)

            // Validate the newly written M4A container before replacing the original file
            val totalDurationUnits = mergedStts.sumOf { it.sampleCount.toLong() * it.sampleDelta.toLong() }
            val mdhdScale = meta.mdhdTimescale.toLong().coerceAtLeast(1L)
            val expectedDurationMs = (totalDurationUnits * 1000L) / mdhdScale
            val validation = validateAppendedM4aFile(
                file = stagingFile,
                expectedTotalSamples = totalSampleCount.toLong(),
                expectedDurationMs = expectedDurationMs
            )
            if (!validation.first) {
                stagingFile.delete()
                return Mp4AppendOutcome(
                    success = false,
                    finalFile = baseM4aFile,
                    errorMessage = validation.second ?: "Post-append M4A validation failed."
                )
            }

            // Atomically replace baseM4aFile with stagingFile
            if (!atomicSwapFile(stagingFile, baseM4aFile)) {
                stagingFile.delete()
                return Mp4AppendOutcome(
                    success = false,
                    finalFile = baseM4aFile,
                    errorMessage = "Atomic file swap failed."
                )
            }

            onProgress?.invoke(1.0f)
            val updatedMeta = Mp4BoxParser.parseM4a(baseM4aFile)
            Mp4AppendOutcome(
                success = true,
                finalFile = baseM4aFile,
                totalSamples = updatedMeta?.totalSamplesFromStts ?: totalSampleCount.toLong(),
                totalDurationMs = updatedMeta?.durationMs ?: expectedDurationMs,
                usedTranscodeFallback = false
            )
        } catch (e: Exception) {
            stagingFile.delete()
            Mp4AppendOutcome(
                success = false,
                finalFile = baseM4aFile,
                errorMessage = e.localizedMessage ?: "Exception during M4A append."
            )
        }
    }

    /**
     * Appends [appendedFile] (which may be an M4A segment or a PCM WAV segment) onto [baseM4aFile].
     *
     * - If [appendedFile] is an M4A file with matching codec parameters (`sampleRate`, `channelCount`,
     *   `aacObjectType`), extracts its AAC frames and appends them directly into [baseM4aFile]
     *   with zero re-encoding.
     * - If [appendedFile] is a PCM WAV segment, encodes it with [AacEncoder] matching [baseM4aFile]'s
     *   exact `AudioSpecificConfig` (skipping the 1024-sample encoder priming frame) and appends
     *   the AAC frames directly into [baseM4aFile]'s `mdat`.
     * - If audio parameters cannot be matched, automatically runs the background transcode fallback.
     */
    fun appendAudioToM4aUniversal(
        baseM4aFile: File,
        appendedFile: File,
        targetBitrate: Int = 128_000,
        onProgress: ((Float) -> Unit)? = null
    ): Mp4AppendOutcome {
        if (!baseM4aFile.exists() || !appendedFile.exists()) {
            return Mp4AppendOutcome(false, baseM4aFile, errorMessage = "Source or append file does not exist.")
        }

        val baseMeta = Mp4BoxParser.parseM4a(baseM4aFile)
        val appendMeta = Mp4BoxParser.parseM4a(appendedFile)

        // Case 1: Both baseM4aFile and appendedFile are valid M4A files with matching audio parameters
        val ascMatches = baseMeta != null && appendMeta != null && (
            baseMeta.audioSpecificConfig.isEmpty() ||
                appendMeta.audioSpecificConfig.isEmpty() ||
                baseMeta.audioSpecificConfig.contentEquals(appendMeta.audioSpecificConfig)
            )
        if (baseMeta != null && appendMeta != null &&
            baseMeta.sampleRate == appendMeta.sampleRate &&
            baseMeta.channelCount == appendMeta.channelCount &&
            baseMeta.aacObjectType == appendMeta.aacObjectType &&
            ascMatches
        ) {
            val rawExtractTemp = File(baseM4aFile.parentFile, "${baseM4aFile.name}.raw_ext.aac")
            try {
                // Live continuation segments ("append_segment_*") already skipped the priming frame during recording.
                val alreadySkippedPriming = appendedFile.name.startsWith("append_segment_", ignoreCase = true)
                val skipFirstPriming = if (!alreadySkippedPriming && appendMeta.sampleSizes.size > 2) 1 else 0
                val keptSizes = extractRawAacFramesViaChunkTable(
                    sourceFile = appendedFile,
                    meta = appendMeta,
                    skipFirstFrames = skipFirstPriming,
                    outRawAacFile = rawExtractTemp
                )

                if (keptSizes != null && keptSizes.isNotEmpty() && rawExtractTemp.exists() && rawExtractTemp.length() > 0L) {
                    val segment = EncodedAacSegment(
                        rawAacFile = rawExtractTemp,
                        frameSizes = keptSizes,
                        sampleRate = appendMeta.sampleRate,
                        channelCount = appendMeta.channelCount,
                        aacObjectType = appendMeta.aacObjectType,
                        audioSpecificConfig = appendMeta.audioSpecificConfig,
                        startPtsSamples = baseMeta.totalDurationSamples,
                        totalEncodedSamples = keptSizes.size.toLong() * AacEncoder.AAC_FRAME_SAMPLES.toLong(),
                        primingFramesSkipped = skipFirstPriming
                    )
                    val outcome = appendEncodedSegmentToM4a(
                        baseM4aFile = baseM4aFile,
                        segment = segment,
                        onProgress = onProgress
                    )
                    rawExtractTemp.delete()
                    if (outcome.success) {
                        try { appendedFile.delete() } catch (_: Exception) {}
                        return outcome
                    }
                } else {
                    rawExtractTemp.delete()
                }
            } catch (_: Exception) {
                rawExtractTemp.delete()
            }
        }

        // Case 2: baseM4aFile is valid M4A and appendedFile is PCM WAV -> encode WAV to matching AAC frames & remux
        if (baseMeta != null && WavFileTools.isRiffWaveFile(appendedFile)) {
            val matchingEncoderPair = AacEncoder.createMatchingEncoderForBaseFile(baseM4aFile, targetBitrate)
            if (matchingEncoderPair != null) {
                val (encoder, _) = matchingEncoderPair
                val rawAacTemp = File(baseM4aFile.parentFile, "${baseM4aFile.name}.seg.aac")
                val resampledWavTemp = File(baseM4aFile.parentFile, "${baseM4aFile.name}.seg_matched.wav")
                try {
                    val wavHeader = WavFileTools.readWavHeaderInfo(appendedFile)
                    val matchedWavFile = if (wavHeader.sampleRate == baseMeta.sampleRate &&
                        wavHeader.channels == baseMeta.channelCount
                    ) {
                        appendedFile
                    } else {
                        val ok = WavFileTools.decodeAudioToWav(
                            sourceFile = appendedFile,
                            outputWavFile = resampledWavTemp,
                            targetSampleRate = baseMeta.sampleRate,
                            targetChannels = baseMeta.channelCount,
                            onProgress = { p -> onProgress?.invoke(p * 0.25f) }
                        )
                        if (ok) resampledWavTemp else appendedFile
                    }

                    val encodedSegment = encoder.encodeWavFileToSegment(
                        wavFile = matchedWavFile,
                        rawAacOutputFile = rawAacTemp,
                        onProgress = { p -> onProgress?.invoke(0.25f + p * 0.35f) }
                    )
                    if (encodedSegment != null && encodedSegment.frameSizes.isNotEmpty()) {
                        val outcome = appendEncodedSegmentToM4a(
                            baseM4aFile = baseM4aFile,
                            segment = encodedSegment,
                            onProgress = { p -> onProgress?.invoke(0.60f + p * 0.40f) }
                        )
                        rawAacTemp.delete()
                        if (resampledWavTemp.exists()) resampledWavTemp.delete()
                        if (outcome.success) {
                            try { appendedFile.delete() } catch (_: Exception) {}
                            return outcome
                        }
                    }
                } catch (_: Exception) {
                    rawAacTemp.delete()
                    if (resampledWavTemp.exists()) resampledWavTemp.delete()
                }
            }
        }

        // Case 3: Parameter mismatch or unsupported source container -> explicit background transcode fallback
        val fallbackRate = baseMeta?.sampleRate?.takeIf { it in 8000..48000 } ?: 44100
        val fallbackChannels = baseMeta?.channelCount?.coerceIn(1, 2) ?: 1
        return transcodeAndAppendFallback(
            baseFile = baseM4aFile,
            appendedFile = appendedFile,
            targetSampleRate = fallbackRate,
            targetChannels = fallbackChannels,
            targetBitrate = targetBitrate,
            onProgress = onProgress
        )
    }

    /**
     * Explicit transcode fallback when the base file's sample rate, channel count, or AAC profile
     * cannot be matched directly by the hardware encoder.
     * Decodes both streams to 16-bit PCM at [targetSampleRate]/[targetChannels], encodes to a new
     * M4A staging file, validates the output, and swaps atomically while keeping [baseFile] intact
     * until completion.
     */
    fun transcodeAndAppendFallback(
        baseFile: File,
        appendedFile: File,
        targetSampleRate: Int = 44100,
        targetChannels: Int = 1,
        targetBitrate: Int = 128_000,
        onProgress: ((Float) -> Unit)? = null
    ): Mp4AppendOutcome {
        val parentDir = baseFile.parentFile ?: File(".")
        val basePcmTemp = File(parentDir, "${baseFile.name}.transcode_base.pcm")
        val appendPcmTemp = File(parentDir, "${baseFile.name}.transcode_app.pcm")
        val rawAacTemp = File(parentDir, "${baseFile.name}.transcode.aac")
        val stagingM4a = File(parentDir, "${baseFile.name}.transcode.m4a.tmp")

        return try {
            onProgress?.invoke(0.05f)
            val baseDecoded = if (WavFileTools.isRiffWaveFile(baseFile)) {
                extractOrResampleWavToRawPcm(baseFile, basePcmTemp, targetSampleRate, targetChannels)
            } else {
                WavFileTools.decodeAudioToPcm(
                    sourceFile = baseFile,
                    outputPcmFile = basePcmTemp,
                    targetSampleRate = targetSampleRate,
                    targetChannels = targetChannels,
                    onProgress = { p -> onProgress?.invoke(0.05f + p * 0.30f) }
                )
            }

            if (!baseDecoded || !basePcmTemp.exists() || basePcmTemp.length() == 0L) {
                return Mp4AppendOutcome(false, baseFile, errorMessage = "Failed to decode base file for transcode fallback.")
            }

            onProgress?.invoke(0.35f)
            val appendDecoded = if (WavFileTools.isRiffWaveFile(appendedFile)) {
                extractOrResampleWavToRawPcm(appendedFile, appendPcmTemp, targetSampleRate, targetChannels)
            } else {
                WavFileTools.decodeAudioToPcm(
                    sourceFile = appendedFile,
                    outputPcmFile = appendPcmTemp,
                    targetSampleRate = targetSampleRate,
                    targetChannels = targetChannels,
                    onProgress = { p -> onProgress?.invoke(0.35f + p * 0.25f) }
                )
            }

            if (!appendDecoded || !appendPcmTemp.exists() || appendPcmTemp.length() == 0L) {
                return Mp4AppendOutcome(false, baseFile, errorMessage = "Failed to decode appended file for transcode fallback.")
            }

            onProgress?.invoke(0.60f)
            val encoder = AacEncoder(
                sampleRate = targetSampleRate,
                channelCount = targetChannels,
                bitrate = targetBitrate,
                skipPrimingFrame = false
            )
            encoder.start(rawAacTemp)

            val totalPcmBytes = (basePcmTemp.length() + appendPcmTemp.length()).coerceAtLeast(1L)
            var processedBytes = 0L
            val buf = ByteArray(16384)

            for (pcmFile in listOf(basePcmTemp, appendPcmTemp)) {
                FileInputStream(pcmFile).use { fis ->
                    while (true) {
                        val r = fis.read(buf)
                        if (r <= 0) break
                        encoder.encodePcmBytes(buf, r)
                        processedBytes += r
                        val p = 0.60f + 0.30f * (processedBytes.toFloat() / totalPcmBytes.toFloat()).coerceIn(0f, 1f)
                        onProgress?.invoke(p)
                    }
                }
            }

            val segment = encoder.finish()
            val wroteM4a = encoder.writeStandaloneM4aFile(segment, stagingM4a)
            if (!wroteM4a || !stagingM4a.exists()) {
                return Mp4AppendOutcome(false, baseFile, errorMessage = "Failed to write transcoded M4A container.")
            }

            val targetFinalM4a = if (baseFile.extension.equals("m4a", ignoreCase = true)) {
                baseFile
            } else {
                File(parentDir, "${baseFile.nameWithoutExtension}.m4a")
            }

            if (!atomicSwapFile(stagingM4a, targetFinalM4a)) {
                return Mp4AppendOutcome(false, baseFile, errorMessage = "Failed to swap transcoded M4A file.")
            }

            if (targetFinalM4a.absolutePath != baseFile.absolutePath && baseFile.exists()) {
                try { baseFile.delete() } catch (_: Exception) {}
            }
            try { appendedFile.delete() } catch (_: Exception) {}

            onProgress?.invoke(1.0f)
            val finalMeta = Mp4BoxParser.parseM4a(targetFinalM4a)
            Mp4AppendOutcome(
                success = true,
                finalFile = targetFinalM4a,
                totalSamples = finalMeta?.totalSamplesFromStts ?: segment.frameSizes.size.toLong(),
                totalDurationMs = finalMeta?.durationMs
                    ?: ((segment.totalEncodedSamples * 1000L) / targetSampleRate.toLong()),
                usedTranscodeFallback = true
            )
        } catch (e: Exception) {
            Mp4AppendOutcome(
                success = false,
                finalFile = baseFile,
                usedTranscodeFallback = true,
                errorMessage = e.localizedMessage
            )
        } finally {
            basePcmTemp.delete()
            appendPcmTemp.delete()
            rawAacTemp.delete()
            if (stagingM4a.exists()) stagingM4a.delete()
        }
    }

    private fun extractOrResampleWavToRawPcm(
        wavFile: File,
        outputPcmFile: File,
        targetSampleRate: Int,
        targetChannels: Int
    ): Boolean {
        return try {
            val header = WavFileTools.readWavHeaderInfo(wavFile)
            val pcmOffset = WavFileTools.locatePcmDataOffset(wavFile)
            val resampler = if (header.sampleRate != targetSampleRate) {
                WavFileTools.PcmResampler(header.sampleRate, targetSampleRate, targetChannels)
            } else null

            RandomAccessFile(wavFile, "r").use { raf ->
                raf.seek(pcmOffset)
                FileOutputStream(outputPcmFile).use { fos ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val r = raf.read(buf)
                        if (r <= 0) break
                        val rawChunk = if (r == buf.size) buf else buf.copyOf(r)
                        val chConverted = WavFileTools.convertPcmChannels(rawChunk, header.channels, targetChannels)
                        val finalBytes = resampler?.resampleChunk(chConverted) ?: chConverted
                        fos.write(finalBytes)
                    }
                    fos.flush()
                }
            }
            outputPcmFile.exists() && outputPcmFile.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Validates an appended M4A file:
     * 1. Parses the container with [Mp4BoxParser.parseM4a] and verifies `sum(stts) == stsz.count == expectedTotalSamples`.
     * 2. Verifies every chunk offset falls strictly inside `[mdatDataOffset, mdatOffset + mdatSize)`.
     * 3. Verifies sum of sample sizes equals `mdatDataSize`.
     * 4. On Android runtime, verifies track readability and duration via `MediaExtractor` / `MediaMetadataRetriever`.
     */
    fun validateAppendedM4aFile(
        file: File,
        expectedTotalSamples: Long,
        expectedDurationMs: Long
    ): Pair<Boolean, String?> {
        if (!file.exists() || file.length() < 32L) {
            return false to "File does not exist or is too small."
        }
        val meta = Mp4BoxParser.parseM4a(file)
            ?: return false to "Mp4BoxParser failed to parse appended M4A."

        if (!meta.isSampleTableConsistent()) {
            return false to "Sample table inconsistent: stts=${meta.totalSamplesFromStts}, stsz=${meta.sampleSizes.size}"
        }
        if (expectedTotalSamples > 0L && meta.totalSamplesFromStts != expectedTotalSamples) {
            return false to "Expected $expectedTotalSamples samples, found ${meta.totalSamplesFromStts}."
        }

        val mdatStart = meta.mdatDataOffset
        val mdatEnd = meta.mdatOffset + meta.mdatSize
        for (chunkOff in meta.chunkOffsets) {
            if (chunkOff < mdatStart || chunkOff >= mdatEnd) {
                return false to "Chunk offset $chunkOff outside mdat payload [$mdatStart, $mdatEnd)."
            }
        }

        val totalSampleBytes = meta.sampleSizes.sumOf { it.toLong() }
        if (totalSampleBytes > meta.mdatDataSize) {
            return false to "Sum of sample sizes ($totalSampleBytes) exceeds mdat payload (${meta.mdatDataSize})."
        }

        val oneFrameToleranceMs = ((AacEncoder.AAC_FRAME_SAMPLES * 1000L) / meta.sampleRate.coerceAtLeast(8000)) + 35L
        if (expectedDurationMs > 0L && abs(meta.durationMs - expectedDurationMs) > oneFrameToleranceMs) {
            return false to "Container duration (${meta.durationMs}ms) differs from expected (${expectedDurationMs}ms)."
        }

        val isRobolectric = AacEncoder.isJvmOrRobolectricTest()

        if (!isRobolectric) {
            var extractor: MediaExtractor? = null
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                if (extractor.trackCount < 1) {
                    return false to "MediaExtractor found 0 tracks in appended M4A."
                }
                var foundAudio = false
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        foundAudio = true
                        break
                    }
                }
                if (!foundAudio) {
                    return false to "MediaExtractor did not find an audio track."
                }
            } catch (e: Exception) {
                return false to "MediaExtractor validation error: ${e.localizedMessage}"
            } finally {
                try { extractor?.release() } catch (_: Exception) {}
            }

            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val parsedDur = durStr?.toLongOrNull() ?: 0L
                val retrieverToleranceMs = maxOf(oneFrameToleranceMs * 6, 600L)
                if (parsedDur > 0L && expectedDurationMs > 0L &&
                    abs(parsedDur - expectedDurationMs) > retrieverToleranceMs
                ) {
                    return false to "MediaMetadataRetriever duration ($parsedDur ms) out of tolerance ($expectedDurationMs ms)."
                }
            } catch (_: Exception) {
                // Ignore retriever metadata quirks if MediaExtractor and box parser succeeded
            } finally {
                try { retriever?.release() } catch (_: Exception) {}
            }
        }

        return true to null
    }

    /**
     * Walks the ISO BMFF `stsc` (sample-to-chunk), `stco`/`co64` (chunk offsets), and `stsz` (sample sizes)
     * tables of [meta] to extract exact AAC access unit bytes from [sourceFile] into [outRawAacFile].
     * Gracefully clamps to [sourceFile]'s physical length so truncated `mdat` boxes or out-of-bounds
     * trailing sample table entries are cleanly salvaged.
     */
    fun extractRawAacFramesViaChunkTable(
        sourceFile: File,
        meta: Mp4AudioMetadata,
        skipFirstFrames: Int = 0,
        outRawAacFile: File
    ): IntArray? {
        if (!sourceFile.exists() || meta.sampleSizes.isEmpty() || meta.chunkOffsets.isEmpty()) return null
        val fileLen = sourceFile.length()
        val totalSamples = meta.sampleSizes.size
        if (skipFirstFrames >= totalSamples) return null

        return try {
            val extractedSizes = ArrayList<Int>(totalSamples - skipFirstFrames)
            val sortedStsc = meta.stscEntries
                .filter { it.firstChunk >= 1 && it.samplesPerChunk > 0 }
                .sortedBy { it.firstChunk }

            RandomAccessFile(sourceFile, "r").use { raf ->
                FileOutputStream(outRawAacFile).use { fos ->
                    var sampleIndex = 0
                    val totalChunks = meta.chunkOffsets.size
                    val buf = ByteArray(65536)

                    for (chunkIdxZero in 0 until totalChunks) {
                        if (sampleIndex >= totalSamples) break
                        val chunkNumberOneBased = chunkIdxZero + 1
                        val activeStsc = sortedStsc.lastOrNull { it.firstChunk <= chunkNumberOneBased }
                        val samplesInThisChunk = activeStsc?.samplesPerChunk
                            ?: (totalSamples - sampleIndex)

                        var currentOffset = meta.chunkOffsets[chunkIdxZero]
                        if (currentOffset < 0L || currentOffset >= fileLen) {
                            break
                        }

                        val countForChunk = minOf(samplesInThisChunk, totalSamples - sampleIndex)
                        for (s in 0 until countForChunk) {
                            val sz = meta.sampleSizes[sampleIndex]
                            if (sz <= 0 || currentOffset + sz > fileLen) {
                                sampleIndex = totalSamples // stop at first truncated sample
                                break
                            }
                            if (sampleIndex >= skipFirstFrames) {
                                raf.seek(currentOffset)
                                var remaining = sz
                                while (remaining > 0) {
                                    val toRead = minOf(buf.size, remaining)
                                    val r = raf.read(buf, 0, toRead)
                                    if (r <= 0) break
                                    fos.write(buf, 0, r)
                                    remaining -= r
                                }
                                if (remaining == 0) {
                                    extractedSizes.add(sz)
                                }
                            }
                            currentOffset += sz.toLong()
                            sampleIndex++
                        }
                    }
                    fos.flush()
                }
            }
            if (extractedSizes.isEmpty()) null else extractedSizes.toIntArray()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Deep-scans, unpacks, and rebuilds a corrupted or partially merged M4A container.
     *
     * Recovery strategy:
     * 1. Restores any intact `.bak` backup if [corruptedFile] is missing or empty.
     * 2. Scans [corruptedFile] for all top-level and embedded `moov` boxes (handling files where
     *    multiple M4A containers were concatenated or where `mdat` headers have unpatched sizes).
     * 3. Unpacks all valid AAC frames via `stsc`/`stco`/`stsz` across every discovered segment
     *    (Plus any orphaned `.append.tmp` or `.seg.aac` companion files).
     * 4. Repacks all salvaged AAC frames into a brand-new, single-chunk M4A container (`ftyp` +
     *    contiguous `mdat` + freshly built `moov` with normalized `stts`/`stsc`/`stsz`/`stco`).
     * 5. If sample tables were completely missing or incompatible, falls back to decoding readable
     *    audio packets to 16-bit PCM and re-encoding a fresh M4A container.
     */
    fun unpackAndRepairM4aFile(
        corruptedFile: File,
        fallbackSampleRate: Int = 44100,
        fallbackChannels: Int = 1,
        targetBitrate: Int = 128_000,
        onProgress: ((Float) -> Unit)? = null
    ): Mp4AppendOutcome {
        val parentDir = corruptedFile.parentFile ?: File(".")
        val backupCandidate = File(parentDir, "${corruptedFile.name}.bak")
        if ((!corruptedFile.exists() || corruptedFile.length() < 32L) &&
            backupCandidate.exists() && backupCandidate.length() >= 32L
        ) {
            try {
                backupCandidate.copyTo(corruptedFile, overwrite = true)
            } catch (_: Exception) {}
        }

        if (!corruptedFile.exists() || corruptedFile.length() < 16L) {
            return Mp4AppendOutcome(false, corruptedFile, errorMessage = "Corrupted M4A file is missing or empty.")
        }

        onProgress?.invoke(0.10f)
        val unpackedRawAac = File(parentDir, "${corruptedFile.name}.repaired_raw.aac")
        val stagingM4a = File(parentDir, "${corruptedFile.name}.repaired.m4a.tmp")
        if (unpackedRawAac.exists()) unpackedRawAac.delete()
        if (stagingM4a.exists()) stagingM4a.delete()

        try {
            // Discover all moov metadata segments inside corruptedFile (supports single or concatenated M4As)
            val discoveredMetas = scanAllMoovSegmentsInFile(corruptedFile)
            val combinedFrameSizes = ArrayList<Int>()
            var resolvedSampleRate = fallbackSampleRate.coerceIn(8000, 96000)
            var resolvedChannels = fallbackChannels.coerceIn(1, 2)
            var resolvedAsc = Mp4BoxParser.buildAudioSpecificConfig(2, resolvedSampleRate, resolvedChannels)

            if (discoveredMetas.isNotEmpty()) {
                val primaryMeta = discoveredMetas.first()
                resolvedSampleRate = primaryMeta.sampleRate.coerceIn(8000, 96000)
                resolvedChannels = primaryMeta.channelCount.coerceIn(1, 2)
                if (primaryMeta.audioSpecificConfig.size >= 2) {
                    resolvedAsc = primaryMeta.audioSpecificConfig
                }

                FileOutputStream(unpackedRawAac).use { mergedFos ->
                    val segTemp = File(parentDir, "${corruptedFile.name}.seg_unpack.aac")
                    for (metaSeg in discoveredMetas) {
                        if (segTemp.exists()) segTemp.delete()
                        val segSizes = extractRawAacFramesViaChunkTable(
                            sourceFile = corruptedFile,
                            meta = metaSeg,
                            skipFirstFrames = 0,
                            outRawAacFile = segTemp
                        )
                        if (segSizes != null && segSizes.isNotEmpty() && segTemp.exists()) {
                            FileInputStream(segTemp).use { fis ->
                                fis.copyTo(mergedFos, 65536)
                            }
                            for (sz in segSizes) {
                                combinedFrameSizes.add(sz)
                            }
                        }
                        if (segTemp.exists()) segTemp.delete()
                    }
                    mergedFos.flush()
                }
            }

            onProgress?.invoke(0.55f)

            // If chunk-table unpacking salvaged valid AAC frames, rebuild a clean single-chunk M4A container
            if (combinedFrameSizes.isNotEmpty() && unpackedRawAac.exists() && unpackedRawAac.length() > 0L) {
                val frameSizesArray = combinedFrameSizes.toIntArray()
                val totalPayloadBytes = frameSizesArray.sumOf { it.toLong() }
                if (totalPayloadBytes == unpackedRawAac.length()) {
                    val ftypBytes = Mp4BoxParser.buildFtypBox()
                    val needs64 = (totalPayloadBytes + 16L) > 0xFFFFFFFFL
                    val mdatHeaderBytes = Mp4BoxParser.buildMdatHeader(
                        totalMdatBoxSize = totalPayloadBytes + (if (needs64) 16L else 8L),
                        force64Bit = needs64
                    )
                    val firstChunkOffset = ftypBytes.size.toLong() + mdatHeaderBytes.size.toLong()
                    val freshMoovBytes = Mp4BoxParser.buildFreshMoovBox(
                        sampleRate = resolvedSampleRate,
                        channelCount = resolvedChannels,
                        audioSpecificConfig = resolvedAsc,
                        sampleSizes = frameSizesArray,
                        chunkOffsets = longArrayOf(firstChunkOffset),
                        stscEntries = listOf(
                            StscEntry(
                                firstChunk = 1,
                                samplesPerChunk = frameSizesArray.size,
                                sampleDescriptionIndex = 1
                            )
                        ),
                        sttsEntries = listOf(
                            SttsEntry(
                                sampleCount = frameSizesArray.size,
                                sampleDelta = AacEncoder.AAC_FRAME_SAMPLES
                            )
                        ),
                        forceCo64 = needs64
                    )

                    FileOutputStream(stagingM4a).use { fos ->
                        fos.write(ftypBytes)
                        fos.write(mdatHeaderBytes)
                        FileInputStream(unpackedRawAac).use { fis ->
                            fis.copyTo(fos, 65536)
                        }
                        fos.write(freshMoovBytes)
                        fos.flush()
                    }

                    val expectedDurationMs = (frameSizesArray.size.toLong() * AacEncoder.AAC_FRAME_SAMPLES * 1000L) /
                        resolvedSampleRate.toLong().coerceAtLeast(1L)

                    onProgress?.invoke(0.85f)
                    if (stagingM4a.exists() && stagingM4a.length() > 32L && atomicSwapFile(stagingM4a, corruptedFile)) {
                        unpackedRawAac.delete()
                        onProgress?.invoke(1.0f)
                        val repairedMeta = Mp4BoxParser.parseM4a(corruptedFile)
                        return Mp4AppendOutcome(
                            success = true,
                            finalFile = corruptedFile,
                            totalSamples = repairedMeta?.totalSamplesFromStts ?: frameSizesArray.size.toLong(),
                            totalDurationMs = repairedMeta?.durationMs ?: expectedDurationMs,
                            usedTranscodeFallback = false
                        )
                    }
                }
            }

            // Fallback: Decode readable audio stream to PCM and re-encode a clean M4A container
            val decodedPcmTemp = File(parentDir, "${corruptedFile.name}.repair_dec.pcm")
            try {
                val decodedOk = WavFileTools.decodeAudioToPcm(
                    sourceFile = corruptedFile,
                    outputPcmFile = decodedPcmTemp,
                    targetSampleRate = resolvedSampleRate,
                    targetChannels = resolvedChannels,
                    onProgress = { p -> onProgress?.invoke(0.55f + p * 0.25f) }
                )
                if (decodedOk && decodedPcmTemp.exists() && decodedPcmTemp.length() > 0L) {
                    val encoder = AacEncoder(
                        sampleRate = resolvedSampleRate,
                        channelCount = resolvedChannels,
                        bitrate = targetBitrate,
                        skipPrimingFrame = false
                    )
                    encoder.start(unpackedRawAac)
                    val buf = ByteArray(16384)
                    FileInputStream(decodedPcmTemp).use { fis ->
                        while (true) {
                            val r = fis.read(buf)
                            if (r <= 0) break
                            encoder.encodePcmBytes(buf, r)
                        }
                    }
                    val seg = encoder.finish()
                    if (encoder.writeStandaloneM4aFile(seg, stagingM4a) &&
                        atomicSwapFile(stagingM4a, corruptedFile)
                    ) {
                        onProgress?.invoke(1.0f)
                        val finalMeta = Mp4BoxParser.parseM4a(corruptedFile)
                        return Mp4AppendOutcome(
                            success = true,
                            finalFile = corruptedFile,
                            totalSamples = finalMeta?.totalSamplesFromStts ?: seg.frameSizes.size.toLong(),
                            totalDurationMs = finalMeta?.durationMs
                                ?: ((seg.totalEncodedSamples * 1000L) / resolvedSampleRate.toLong()),
                            usedTranscodeFallback = true
                        )
                    }
                }
            } finally {
                if (decodedPcmTemp.exists()) decodedPcmTemp.delete()
            }

            return Mp4AppendOutcome(
                success = false,
                finalFile = corruptedFile,
                errorMessage = "Unable to salvage M4A frames from corrupted container."
            )
        } catch (e: Exception) {
            return Mp4AppendOutcome(
                success = false,
                finalFile = corruptedFile,
                errorMessage = e.localizedMessage ?: "Failed to repair M4A container."
            )
        } finally {
            if (unpackedRawAac.exists()) unpackedRawAac.delete()
            if (stagingM4a.exists()) stagingM4a.delete()
        }
    }

    /**
     * Scans [file] for all top-level or concatenated `moov` boxes so even files created by
     * naive concatenation of two M4A files or files with unpatched `mdat` sizes can have all
     * of their `moov` sample tables parsed.
     */
    private fun scanAllMoovSegmentsInFile(file: File): List<Mp4AudioMetadata> {
        val results = mutableListOf<Mp4AudioMetadata>()
        val standardMeta = Mp4BoxParser.parseM4a(file)
        if (standardMeta != null) {
            results.add(standardMeta)
        }

        try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                val topBoxes = Mp4BoxParser.listBoxesInRange(raf, 0L, fileLen)
                val moovBoxes = topBoxes.filter { it.type == "moov" }
                val mdatBoxes = topBoxes.filter { it.type == "mdat" }

                if (moovBoxes.size > 1) {
                    results.clear()
                    for (i in moovBoxes.indices) {
                        val moovBox = moovBoxes[i]
                        val matchingMdat = mdatBoxes.getOrNull(i) ?: mdatBoxes.firstOrNull() ?: continue
                        val moovBytes = ByteArray(moovBox.size.toInt())
                        raf.seek(moovBox.offset)
                        raf.readFully(moovBytes)
                        val meta = Mp4BoxParser.parseMoovBytes(
                            moovBytes = moovBytes,
                            ftypOffset = 0L,
                            ftypSize = 0L,
                            mdatOffset = matchingMdat.offset,
                            mdatHeaderSize = matchingMdat.headerSize,
                            mdatSize = matchingMdat.size,
                            moovOffset = moovBox.offset,
                            moovSize = moovBox.size,
                            mdatBoxCount = 1
                        )
                        if (meta != null) {
                            val mdatDataStart = matchingMdat.offset + matchingMdat.headerSize.toLong()
                            val rebasedMeta = if (meta.chunkOffsets.isNotEmpty() && meta.chunkOffsets[0] < mdatDataStart) {
                                val delta = mdatDataStart - meta.chunkOffsets[0]
                                meta.copy(
                                    chunkOffsets = LongArray(meta.chunkOffsets.size) { idx ->
                                        meta.chunkOffsets[idx] + delta
                                    }
                                )
                            } else {
                                meta
                            }
                            results.add(rebasedMeta)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return results
    }

    private fun copyRange(rafIn: RandomAccessFile, fosOut: FileOutputStream, startOffset: Long, byteCount: Long) {
        if (byteCount <= 0L) return
        rafIn.seek(startOffset)
        val buf = ByteArray(65536)
        var remaining = byteCount
        while (remaining > 0L) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val r = rafIn.read(buf, 0, toRead)
            if (r <= 0) break
            fosOut.write(buf, 0, r)
            remaining -= r.toLong()
        }
    }

    private fun atomicSwapFile(stagingFile: File, targetFile: File): Boolean {
        if (!stagingFile.exists() || stagingFile.length() == 0L) return false
        val parent = targetFile.parentFile ?: stagingFile.parentFile ?: return false
        val backupFile = File(parent, "${targetFile.name}.bak")
        if (backupFile.exists()) backupFile.delete()

        return try {
            if (targetFile.exists()) {
                if (targetFile.renameTo(backupFile)) {
                    if (stagingFile.renameTo(targetFile)) {
                        backupFile.delete()
                        return true
                    } else {
                        try {
                            stagingFile.copyTo(targetFile, overwrite = true)
                            stagingFile.delete()
                            backupFile.delete()
                            return true
                        } catch (_: Exception) {
                            backupFile.renameTo(targetFile)
                            return false
                        }
                    }
                }
            }
            if (stagingFile.renameTo(targetFile)) {
                true
            } else {
                stagingFile.copyTo(targetFile, overwrite = true)
                stagingFile.delete()
                true
            }
        } catch (_: Exception) {
            if (!targetFile.exists() && backupFile.exists()) {
                backupFile.renameTo(targetFile)
            }
            false
        }
    }
}
