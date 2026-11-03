package com.example.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Represents parsed audio codec configuration and sample table metadata from an M4A (ISO BMFF) file.
 */
data class Mp4AudioMetadata(
    val sampleRate: Int,
    val channelCount: Int,
    val aacObjectType: Int, // 2 = AAC-LC
    val audioSpecificConfig: ByteArray,
    val mdhdTimescale: Int,
    val mdhdDuration: Long,
    val mvhdTimescale: Int,
    val mvhdDuration: Long,
    val tkhdDuration: Long,
    val sttsEntries: List<SttsEntry>,
    val stscEntries: List<StscEntry>,
    val sampleSizes: IntArray,
    val chunkOffsets: LongArray,
    val usesCo64: Boolean,
    val ftypOffset: Long,
    val ftypSize: Long,
    val mdatOffset: Long,
    val mdatHeaderSize: Int,
    val mdatSize: Long,
    val moovOffset: Long,
    val moovSize: Long,
    val isMoovBeforeMdat: Boolean,
    val mdatBoxCount: Int = 1
) {
    val totalSamplesFromStts: Long
        get() = sttsEntries.sumOf { it.sampleCount.toLong() }

    val totalDurationSamples: Long
        get() = sttsEntries.sumOf { it.sampleCount.toLong() * it.sampleDelta.toLong() }

    val mdatDataOffset: Long
        get() = mdatOffset + mdatHeaderSize

    val mdatDataSize: Long
        get() = (mdatSize - mdatHeaderSize).coerceAtLeast(0L)

    val durationMs: Long
        get() = if (mdhdTimescale > 0) {
            (totalDurationSamples * 1000L) / mdhdTimescale.toLong()
        } else if (sampleRate > 0) {
            (totalDurationSamples * 1000L) / sampleRate.toLong()
        } else {
            0L
        }

    fun isSampleTableConsistent(): Boolean {
        return sampleSizes.isNotEmpty() &&
            totalSamplesFromStts == sampleSizes.size.toLong() &&
            chunkOffsets.isNotEmpty() &&
            stscEntries.isNotEmpty()
    }

    fun areAllChunksInsideMdat(): Boolean {
        if (mdatBoxCount != 1 || chunkOffsets.isEmpty()) return false
        val start = mdatDataOffset
        val end = mdatOffset + mdatSize
        return chunkOffsets.all { it in start until end }
    }
}

data class SttsEntry(
    val sampleCount: Int,
    val sampleDelta: Int
)

data class StscEntry(
    val firstChunk: Int,
    val samplesPerChunk: Int,
    val sampleDescriptionIndex: Int
)

data class Mp4BoxHeader(
    val offset: Long,
    val size: Long,
    val headerSize: Int,
    val type: String
) {
    val payloadOffset: Long get() = offset + headerSize
    val payloadSize: Long get() = (size - headerSize).coerceAtLeast(0L)
    val endOffset: Long get() = offset + size
}

/**
 * Lightweight ISO Base Media File Format (MP4 / M4A) parser and box builder.
 * Supports 32-bit and 64-bit (`largesize` / `co64`) box layouts, `esds` AudioSpecificConfig
 * extraction, and `moov` sample table patching without re-encoding media streams.
 */
object Mp4BoxParser {

    private val SAMPLE_RATE_TABLE = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000,
        22050, 16000, 12000, 11025, 8000, 7350
    )

    val CONTAINER_BOX_TYPES = setOf(
        "moov", "trak", "edts", "mdia", "minf", "dinf", "stbl", "udta"
    )

    /**
     * Checks whether [file] starts with an ISO BMFF `ftyp`, `moov`, `mdat`, `free`, or `skip` box.
     */
    fun isMp4File(file: File): Boolean {
        if (!file.exists() || file.length() < 16L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = readBoxHeader(raf, 0L, file.length()) ?: return false
                header.type in setOf("ftyp", "moov", "mdat", "free", "skip", "wide")
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads a box header at [offset] inside [raf]. Supports standard 8-byte headers,
     * 16-byte 64-bit `largesize` headers (`size == 1`), and EOF-sized boxes (`size == 0`).
     */
    fun readBoxHeader(raf: RandomAccessFile, offset: Long, fileLength: Long): Mp4BoxHeader? {
        if (offset < 0L || offset + 8L > fileLength) return null
        raf.seek(offset)
        val buf = ByteArray(16)
        val read = raf.read(buf, 0, minOf(16, (fileLength - offset).toInt()))
        if (read < 8) return null

        val bb = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.BIG_ENDIAN)
        val size32 = bb.int.toLong() and 0xFFFFFFFFL
        val typeBytes = ByteArray(4)
        bb.get(typeBytes)
        val type = String(typeBytes, StandardCharsets.US_ASCII)

        return when (size32) {
            1L -> {
                if (read < 16) return null
                val largeSize = bb.long
                if (largeSize < 16L || offset + largeSize > fileLength) return null
                Mp4BoxHeader(offset = offset, size = largeSize, headerSize = 16, type = type)
            }
            0L -> {
                val remaining = fileLength - offset
                Mp4BoxHeader(offset = offset, size = remaining, headerSize = 8, type = type)
            }
            else -> {
                if (size32 < 8L || offset + size32 > fileLength) return null
                Mp4BoxHeader(offset = offset, size = size32, headerSize = 8, type = type)
            }
        }
    }

    /**
     * Lists direct child box headers within [startOffset, endOffset).
     */
    fun listBoxesInRange(raf: RandomAccessFile, startOffset: Long, endOffset: Long): List<Mp4BoxHeader> {
        val boxes = mutableListOf<Mp4BoxHeader>()
        var pos = startOffset
        while (pos + 8L <= endOffset) {
            val header = readBoxHeader(raf, pos, endOffset) ?: break
            boxes.add(header)
            if (header.size <= 0L) break
            pos += header.size
        }
        return boxes
    }

    /**
     * Parses an M4A file's top-level boxes, `moov` hierarchy, `esds` AudioSpecificConfig,
     * `mdhd`/`mvhd`/`tkhd` durations, and `stbl` tables (`stts`, `stsc`, `stsz`, `stco`/`co64`).
     */
    fun parseM4a(file: File): Mp4AudioMetadata? {
        if (!file.exists() || file.length() < 32L) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                val topBoxes = listBoxesInRange(raf, 0L, fileLen)
                val ftypBox = topBoxes.firstOrNull { it.type == "ftyp" }
                val mdatBoxes = topBoxes.filter { it.type == "mdat" }
                val mdatBox = mdatBoxes.maxByOrNull { it.payloadSize } ?: return null
                val moovBox = topBoxes.lastOrNull { it.type == "moov" } ?: return null

                val moovBytes = ByteArray(moovBox.size.toInt())
                raf.seek(moovBox.offset)
                raf.readFully(moovBytes)

                parseMoovBytes(
                    moovBytes = moovBytes,
                    ftypOffset = ftypBox?.offset ?: 0L,
                    ftypSize = ftypBox?.size ?: 0L,
                    mdatOffset = mdatBox.offset,
                    mdatHeaderSize = mdatBox.headerSize,
                    mdatSize = mdatBox.size,
                    moovOffset = moovBox.offset,
                    moovSize = moovBox.size,
                    mdatBoxCount = mdatBoxes.size
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses the in-memory `moov` box bytes along with top-level layout positions.
     */
    fun parseMoovBytes(
        moovBytes: ByteArray,
        ftypOffset: Long,
        ftypSize: Long,
        mdatOffset: Long,
        mdatHeaderSize: Int,
        mdatSize: Long,
        moovOffset: Long,
        moovSize: Long,
        mdatBoxCount: Int = 1
    ): Mp4AudioMetadata? {
        if (moovBytes.size < 16) return null
        val moovHeaderSize = readBufferBoxHeader(moovBytes, 0, moovBytes.size)?.headerSize ?: 8
        val moovChildren = listBufferBoxes(moovBytes, moovHeaderSize, moovBytes.size)

        var mvhdTimescale = 1000
        var mvhdDuration = 0L
        val mvhdBox = moovChildren.firstOrNull { it.type == "mvhd" }
        if (mvhdBox != null) {
            val (ts, dur) = parseMvhd(moovBytes, mvhdBox.payloadOffset.toInt(), mvhdBox.payloadSize.toInt())
            mvhdTimescale = ts
            mvhdDuration = dur
        }

        // Locate audio trak (containing mdia -> minf -> smhd or hdlr 'soun')
        val trakBoxes = moovChildren.filter { it.type == "trak" }
        for (trak in trakBoxes) {
            val trakChildren = listBufferBoxes(moovBytes, trak.payloadOffset.toInt(), trak.endOffset.toInt())
            var tkhdDuration = 0L
            val tkhdBox = trakChildren.firstOrNull { it.type == "tkhd" }
            if (tkhdBox != null) {
                tkhdDuration = parseTkhdDuration(moovBytes, tkhdBox.payloadOffset.toInt(), tkhdBox.payloadSize.toInt())
            }

            val mdiaBox = trakChildren.firstOrNull { it.type == "mdia" } ?: continue
            val mdiaChildren = listBufferBoxes(moovBytes, mdiaBox.payloadOffset.toInt(), mdiaBox.endOffset.toInt())

            var mdhdTimescale = 44100
            var mdhdDuration = 0L
            val mdhdBox = mdiaChildren.firstOrNull { it.type == "mdhd" }
            if (mdhdBox != null) {
                val (ts, dur) = parseMdhd(moovBytes, mdhdBox.payloadOffset.toInt(), mdhdBox.payloadSize.toInt())
                mdhdTimescale = ts
                mdhdDuration = dur
            }

            val minfBox = mdiaChildren.firstOrNull { it.type == "minf" } ?: continue
            val minfChildren = listBufferBoxes(moovBytes, minfBox.payloadOffset.toInt(), minfBox.endOffset.toInt())
            val stblBox = minfChildren.firstOrNull { it.type == "stbl" } ?: continue
            val stblChildren = listBufferBoxes(moovBytes, stblBox.payloadOffset.toInt(), stblBox.endOffset.toInt())

            val stsdBox = stblChildren.firstOrNull { it.type == "stsd" } ?: continue
            val sttsBox = stblChildren.firstOrNull { it.type == "stts" } ?: continue
            val stscBox = stblChildren.firstOrNull { it.type == "stsc" } ?: continue
            val stszBox = stblChildren.firstOrNull { it.type == "stsz" } ?: continue
            val stcoBox = stblChildren.firstOrNull { it.type == "stco" }
            val co64Box = stblChildren.firstOrNull { it.type == "co64" }
            if (stcoBox == null && co64Box == null) continue

            val stsdInfo = parseStsdAudio(moovBytes, stsdBox.payloadOffset.toInt(), stsdBox.payloadSize.toInt())
                ?: continue
            val sttsEntries = parseStts(moovBytes, sttsBox.payloadOffset.toInt(), sttsBox.payloadSize.toInt())
            val stscEntries = parseStsc(moovBytes, stscBox.payloadOffset.toInt(), stscBox.payloadSize.toInt())
            val sampleSizes = parseStsz(moovBytes, stszBox.payloadOffset.toInt(), stszBox.payloadSize.toInt())
            val (chunkOffsets, usesCo64) = if (co64Box != null) {
                parseCo64(moovBytes, co64Box.payloadOffset.toInt(), co64Box.payloadSize.toInt()) to true
            } else {
                parseStco(moovBytes, stcoBox!!.payloadOffset.toInt(), stcoBox.payloadSize.toInt()) to false
            }

            return Mp4AudioMetadata(
                sampleRate = stsdInfo.sampleRate,
                channelCount = stsdInfo.channelCount,
                aacObjectType = stsdInfo.aacObjectType,
                audioSpecificConfig = stsdInfo.audioSpecificConfig,
                mdhdTimescale = if (mdhdTimescale > 0) mdhdTimescale else stsdInfo.sampleRate,
                mdhdDuration = mdhdDuration,
                mvhdTimescale = if (mvhdTimescale > 0) mvhdTimescale else 1000,
                mvhdDuration = mvhdDuration,
                tkhdDuration = tkhdDuration,
                sttsEntries = sttsEntries,
                stscEntries = stscEntries,
                sampleSizes = sampleSizes,
                chunkOffsets = chunkOffsets,
                usesCo64 = usesCo64,
                ftypOffset = ftypOffset,
                ftypSize = ftypSize,
                mdatOffset = mdatOffset,
                mdatHeaderSize = mdatHeaderSize,
                mdatSize = mdatSize,
                moovOffset = moovOffset,
                moovSize = moovSize,
                isMoovBeforeMdat = moovOffset < mdatOffset,
                mdatBoxCount = mdatBoxCount
            )
        }
        return null
    }

    internal fun readBufferBoxHeader(bytes: ByteArray, offset: Int, limit: Int): Mp4BoxHeader? {
        if (offset < 0 || offset + 8 > limit || limit > bytes.size) return null
        val bb = ByteBuffer.wrap(bytes, offset, limit - offset).order(ByteOrder.BIG_ENDIAN)
        val size32 = bb.int.toLong() and 0xFFFFFFFFL
        val typeBytes = ByteArray(4)
        bb.get(typeBytes)
        val type = String(typeBytes, StandardCharsets.US_ASCII)
        return when (size32) {
            1L -> {
                if (offset + 16 > limit) return null
                val largeSize = bb.long
                if (largeSize < 16L || offset + largeSize > limit) return null
                Mp4BoxHeader(offset.toLong(), largeSize, 16, type)
            }
            0L -> Mp4BoxHeader(offset.toLong(), (limit - offset).toLong(), 8, type)
            else -> {
                if (size32 < 8L || offset + size32 > limit) return null
                Mp4BoxHeader(offset.toLong(), size32, 8, type)
            }
        }
    }

    internal fun listBufferBoxes(bytes: ByteArray, startOffset: Int, endOffset: Int): List<Mp4BoxHeader> {
        val list = mutableListOf<Mp4BoxHeader>()
        var pos = startOffset
        while (pos + 8 <= endOffset) {
            val h = readBufferBoxHeader(bytes, pos, endOffset) ?: break
            list.add(h)
            if (h.size <= 0L) break
            pos += h.size.toInt()
        }
        return list
    }

    private fun parseMvhd(bytes: ByteArray, payloadOffset: Int, payloadSize: Int): Pair<Int, Long> {
        if (payloadSize < 20) return 1000 to 0L
        val bb = ByteBuffer.wrap(bytes, payloadOffset, payloadSize).order(ByteOrder.BIG_ENDIAN)
        val version = bb.get().toInt() and 0xFF
        bb.position(payloadOffset + 4) // skip version + 3-byte flags
        return if (version == 1 && payloadSize >= 32) {
            bb.long // creation_time
            bb.long // modification_time
            val timescale = bb.int
            val duration = bb.long
            timescale to duration
        } else {
            bb.int // creation_time
            bb.int // modification_time
            val timescale = bb.int
            val duration = bb.int.toLong() and 0xFFFFFFFFL
            timescale to duration
        }
    }

    private fun parseTkhdDuration(bytes: ByteArray, payloadOffset: Int, payloadSize: Int): Long {
        if (payloadSize < 24) return 0L
        val bb = ByteBuffer.wrap(bytes, payloadOffset, payloadSize).order(ByteOrder.BIG_ENDIAN)
        val version = bb.get().toInt() and 0xFF
        bb.position(payloadOffset + 4)
        return if (version == 1 && payloadSize >= 36) {
            bb.long // creation_time
            bb.long // modification_time
            bb.int // track_id
            bb.int // reserved
            bb.long // duration
        } else {
            bb.int // creation_time
            bb.int // modification_time
            bb.int // track_id
            bb.int // reserved
            bb.int.toLong() and 0xFFFFFFFFL
        }
    }

    private fun parseMdhd(bytes: ByteArray, payloadOffset: Int, payloadSize: Int): Pair<Int, Long> {
        if (payloadSize < 20) return 44100 to 0L
        val bb = ByteBuffer.wrap(bytes, payloadOffset, payloadSize).order(ByteOrder.BIG_ENDIAN)
        val version = bb.get().toInt() and 0xFF
        bb.position(payloadOffset + 4)
        return if (version == 1 && payloadSize >= 32) {
            bb.long
            bb.long
            val timescale = bb.int
            val duration = bb.long
            timescale to duration
        } else {
            bb.int
            bb.int
            val timescale = bb.int
            val duration = bb.int.toLong() and 0xFFFFFFFFL
            timescale to duration
        }
    }

    private class StsdAudioInfo(
        val sampleRate: Int,
        val channelCount: Int,
        val aacObjectType: Int,
        val audioSpecificConfig: ByteArray
    )

    private fun parseStsdAudio(bytes: ByteArray, payloadOffset: Int, payloadSize: Int): StsdAudioInfo? {
        if (payloadSize < 16) return null
        val bb = ByteBuffer.wrap(bytes, payloadOffset, payloadSize).order(ByteOrder.BIG_ENDIAN)
        bb.int // version + flags
        val entryCount = bb.int
        if (entryCount < 1) return null

        val entryStart = payloadOffset + 8
        val entryHeader = readBufferBoxHeader(bytes, entryStart, payloadOffset + payloadSize) ?: return null
        if (entryHeader.payloadSize < 28) return null

        val entryPayload = entryHeader.payloadOffset.toInt()
        val entryBb = ByteBuffer.wrap(bytes, entryPayload, entryHeader.payloadSize.toInt()).order(ByteOrder.BIG_ENDIAN)
        entryBb.position(entryPayload + 16) // 6 reserved + 2 data_ref_index + 8 reserved
        val stsdChannels = entryBb.short.toInt() and 0xFFFF
        entryBb.short // sampleSize (16)
        entryBb.int // pre_defined + reserved
        val fixedSampleRate = (entryBb.int ushr 16) and 0xFFFF

        // Search for child esds box starting at entryPayload + 28
        val childStart = entryPayload + 28
        val childEnd = entryHeader.endOffset.toInt()
        val children = listBufferBoxes(bytes, childStart, childEnd)
        val esdsBox = children.firstOrNull { it.type == "esds" }

        if (esdsBox != null) {
            val asc = extractAudioSpecificConfigFromEsds(
                bytes,
                esdsBox.payloadOffset.toInt(),
                esdsBox.payloadSize.toInt()
            )
            if (asc != null && asc.size >= 2) {
                val parsedAsc = parseAudioSpecificConfig(asc)
                return StsdAudioInfo(
                    sampleRate = if (parsedAsc.first > 0) parsedAsc.first else fixedSampleRate,
                    channelCount = if (parsedAsc.second > 0) parsedAsc.second else stsdChannels,
                    aacObjectType = parsedAsc.third,
                    audioSpecificConfig = asc
                )
            }
        }

        val fallbackRate = if (fixedSampleRate > 0) fixedSampleRate else 44100
        val fallbackCh = if (stsdChannels > 0) stsdChannels else 1
        return StsdAudioInfo(
            sampleRate = fallbackRate,
            channelCount = fallbackCh,
            aacObjectType = 2,
            audioSpecificConfig = buildAudioSpecificConfig(2, fallbackRate, fallbackCh)
        )
    }

    /**
     * Parses MPEG-4 ES_Descriptor (`esds` payload) to extract the `AudioSpecificConfig` bytes
     * from `DecoderSpecificInfo` (tag `0x05`).
     */
    fun extractAudioSpecificConfigFromEsds(
        bytes: ByteArray,
        payloadOffset: Int,
        payloadSize: Int
    ): ByteArray? {
        if (payloadSize < 6) return null
        var pos = payloadOffset + 4 // skip version + flags
        val end = payloadOffset + payloadSize

        while (pos < end) {
            val tag = bytes[pos].toInt() and 0xFF
            pos++
            var len = 0
            var count = 0
            while (pos < end && count < 4) {
                val b = bytes[pos].toInt() and 0xFF
                pos++
                len = (len shl 7) or (b and 0x7F)
                count++
                if ((b and 0x80) == 0) break
            }
            when (tag) {
                0x03 -> {
                    // ES_Descriptor: 2 bytes ES_ID + 1 byte flags
                    if (pos + 3 > end) return null
                    val flags = bytes[pos + 2].toInt() and 0xFF
                    pos += 3
                    if ((flags and 0x80) != 0) pos += 2 // streamDependenceFlag
                    if ((flags and 0x40) != 0 && pos < end) {
                        val urlLen = bytes[pos].toInt() and 0xFF
                        pos += 1 + urlLen
                    }
                    if ((flags and 0x20) != 0) pos += 2 // OCRstreamFlag
                }
                0x04 -> {
                    // DecoderConfigDescriptor: 13 bytes fixed header before DecoderSpecificInfo
                    if (pos + 13 > end) return null
                    pos += 13
                }
                0x05 -> {
                    // DecoderSpecificInfo: payload is AudioSpecificConfig
                    if (len <= 0 || pos + len > end) return null
                    return bytes.copyOfRange(pos, pos + len)
                }
                else -> {
                    pos += len
                }
            }
        }
        return null
    }

    /**
     * Parses `AudioSpecificConfig` bytes into `(sampleRate, channelCount, audioObjectType)`.
     */
    fun parseAudioSpecificConfig(asc: ByteArray): Triple<Int, Int, Int> {
        if (asc.size < 2) return Triple(44100, 1, 2)
        val b0 = asc[0].toInt() and 0xFF
        val b1 = asc[1].toInt() and 0xFF

        val audioObjectType = (b0 ushr 3) and 0x1F
        val freqIndex = ((b0 and 0x07) shl 1) or ((b1 ushr 7) and 0x01)

        return if (freqIndex == 0x0F && asc.size >= 5) {
            // Explicit 24-bit sample rate
            val b2 = asc[2].toInt() and 0xFF
            val b3 = asc[3].toInt() and 0xFF
            val b4 = asc[4].toInt() and 0xFF
            val explicitRate = ((b1 and 0x7F) shl 17) or (b2 shl 9) or (b3 shl 1) or ((b4 ushr 7) and 0x01)
            val ch = (b4 ushr 3) and 0x0F
            Triple(explicitRate, ch.coerceAtLeast(1), audioObjectType)
        } else {
            val sampleRate = if (freqIndex in SAMPLE_RATE_TABLE.indices) {
                SAMPLE_RATE_TABLE[freqIndex]
            } else {
                44100
            }
            val channelCount = ((b1 ushr 3) and 0x0F).coerceAtLeast(1)
            Triple(sampleRate, channelCount, audioObjectType)
        }
    }

    /**
     * Builds a 2-byte `AudioSpecificConfig` for standard AAC profiles (default AAC-LC = 2).
     */
    fun buildAudioSpecificConfig(
        audioObjectType: Int = 2,
        sampleRate: Int = 44100,
        channelCount: Int = 1
    ): ByteArray {
        val freqIdx = SAMPLE_RATE_TABLE.indexOfFirst { it == sampleRate }.let {
            if (it >= 0) it else 4 // default 44100 Hz index
        }
        val b0 = ((audioObjectType and 0x1F) shl 3) or ((freqIdx ushr 1) and 0x07)
        val b1 = ((freqIdx and 0x01) shl 7) or ((channelCount.coerceIn(1, 2) and 0x0F) shl 3)
        return byteArrayOf(b0.toByte(), b1.toByte())
    }

    private fun parseStts(bytes: ByteArray, payloadOffset: Int, payloadSize: Int): List<SttsEntry> {
        if (payloadSize < 8) return emptyList()
        val bb = ByteBuffer.wrap(bytes, payloadOffset, payloadSize).order(ByteOrder.BIG_ENDIAN)
        bb.int // version + flags
        val count = bb.int.coerceAtLeast(0)
        val entries = ArrayList<SttsEntry>(count)
        for (i in 0 until count) {
            if (bb.remaining() < 8) break
            val sampleCount = bb.int
            val sampleDelta = bb.int
            if (sampleCount > 0) {
                entries.add(SttsEntry(sampleCount = sampleCount, sampleDelta = sampleDelta))
            }
        }
        return entries
    }

    private fun parseStsc(bytes: ByteArray, payloadOffset: Int, payloadSize: Int): List<StscEntry> {
        if (payloadSize < 8) return emptyList()
        val bb = ByteBuffer.wrap(bytes, payloadOffset, payloadSize).order(ByteOrder.BIG_ENDIAN)
        bb.int // version + flags
        val count = bb.int.coerceAtLeast(0)
        val entries = ArrayList<StscEntry>(count)
        for (i in 0 until count) {
            if (bb.remaining() < 12) break
            entries.add(
                StscEntry(
                    firstChunk = bb.int,
                    samplesPerChunk = bb.int,
                    sampleDescriptionIndex = bb.int
                )
            )
        }
        return entries
    }

    private fun parseStsz(bytes: ByteArray, payloadOffset: Int, payloadSize: Int): IntArray {
        if (payloadSize < 12) return IntArray(0)
        val bb = ByteBuffer.wrap(bytes, payloadOffset, payloadSize).order(ByteOrder.BIG_ENDIAN)
        bb.int // version + flags
        val uniformSize = bb.int
        val sampleCount = bb.int.coerceAtLeast(0)
        if (sampleCount == 0) return IntArray(0)
        if (uniformSize > 0) {
            return IntArray(sampleCount) { uniformSize }
        }
        val actualCount = minOf(sampleCount, bb.remaining() / 4)
        val sizes = IntArray(actualCount)
        for (i in 0 until actualCount) {
            sizes[i] = bb.int
        }
        return sizes
    }

    private fun parseStco(bytes: ByteArray, payloadOffset: Int, payloadSize: Int): LongArray {
        if (payloadSize < 8) return LongArray(0)
        val bb = ByteBuffer.wrap(bytes, payloadOffset, payloadSize).order(ByteOrder.BIG_ENDIAN)
        bb.int // version + flags
        val count = minOf(bb.int.coerceAtLeast(0), bb.remaining() / 4)
        val offsets = LongArray(count)
        for (i in 0 until count) {
            offsets[i] = bb.int.toLong() and 0xFFFFFFFFL
        }
        return offsets
    }

    private fun parseCo64(bytes: ByteArray, payloadOffset: Int, payloadSize: Int): LongArray {
        if (payloadSize < 8) return LongArray(0)
        val bb = ByteBuffer.wrap(bytes, payloadOffset, payloadSize).order(ByteOrder.BIG_ENDIAN)
        bb.int // version + flags
        val count = minOf(bb.int.coerceAtLeast(0), bb.remaining() / 8)
        val offsets = LongArray(count)
        for (i in 0 until count) {
            offsets[i] = bb.long
        }
        return offsets
    }

    // --- MP4 Box Serialization & Patching Builders ---

    fun buildBox(type: String, payload: ByteArray): ByteArray {
        val totalSize = 8 + payload.size
        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        out.putInt(totalSize)
        out.put(type.toByteArray(StandardCharsets.US_ASCII), 0, 4)
        out.put(payload)
        return out.array()
    }

    fun buildContainerBox(type: String, children: List<ByteArray>): ByteArray {
        val childrenSize = children.sumOf { it.size }
        val totalSize = 8 + childrenSize
        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        out.putInt(totalSize)
        out.put(type.toByteArray(StandardCharsets.US_ASCII), 0, 4)
        for (child in children) {
            out.put(child)
        }
        return out.array()
    }

    fun buildFtypBox(): ByteArray {
        val bb = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        bb.put("M4A ".toByteArray(StandardCharsets.US_ASCII))
        bb.putInt(0) // minor_version
        bb.put("M4A ".toByteArray(StandardCharsets.US_ASCII))
        bb.put("mp42".toByteArray(StandardCharsets.US_ASCII))
        bb.put("isom".toByteArray(StandardCharsets.US_ASCII))
        bb.putInt(0)
        return buildBox("ftyp", bb.array())
    }

    /**
     * Builds an 8-byte standard `mdat` header (if totalMdatSize <= 0xFFFFFFFFL)
     * or a 16-byte 64-bit `largesize` `mdat` header (if totalMdatSize > 0xFFFFFFFFL).
     */
    fun buildMdatHeader(totalMdatBoxSize: Long, force64Bit: Boolean = false): ByteArray {
        return if (force64Bit || totalMdatBoxSize > 0xFFFFFFFFL) {
            val bb = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(1) // signals 64-bit largesize
            bb.put("mdat".toByteArray(StandardCharsets.US_ASCII))
            bb.putLong(totalMdatBoxSize)
            bb.array()
        } else {
            val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(totalMdatBoxSize.toInt())
            bb.put("mdat".toByteArray(StandardCharsets.US_ASCII))
            bb.array()
        }
    }

    fun buildSttsBox(entries: List<SttsEntry>): ByteArray {
        val bb = ByteBuffer.allocate(8 + entries.size * 8).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(0) // version + flags
        bb.putInt(entries.size)
        for (e in entries) {
            bb.putInt(e.sampleCount)
            bb.putInt(e.sampleDelta)
        }
        return buildBox("stts", bb.array())
    }

    fun buildStscBox(entries: List<StscEntry>): ByteArray {
        val bb = ByteBuffer.allocate(8 + entries.size * 12).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(0) // version + flags
        bb.putInt(entries.size)
        for (e in entries) {
            bb.putInt(e.firstChunk)
            bb.putInt(e.samplesPerChunk)
            bb.putInt(e.sampleDescriptionIndex)
        }
        return buildBox("stsc", bb.array())
    }

    fun buildStszBox(sampleSizes: IntArray): ByteArray {
        val bb = ByteBuffer.allocate(12 + sampleSizes.size * 4).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(0) // version + flags
        bb.putInt(0) // sample_size = 0 (variable per-sample sizes)
        bb.putInt(sampleSizes.size)
        for (sz in sampleSizes) {
            bb.putInt(sz)
        }
        return buildBox("stsz", bb.array())
    }

    /**
     * Automatically builds `co64` if [forceCo64] is true or any offset exceeds 32-bit unsigned max,
     * otherwise builds standard `stco`.
     */
    fun buildChunkOffsetBox(chunkOffsets: LongArray, forceCo64: Boolean = false): ByteArray {
        val needsCo64 = forceCo64 || chunkOffsets.any { it > 0xFFFFFFFFL || it < 0L }
        return if (needsCo64) {
            val bb = ByteBuffer.allocate(8 + chunkOffsets.size * 8).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(0) // version + flags
            bb.putInt(chunkOffsets.size)
            for (off in chunkOffsets) {
                bb.putLong(off)
            }
            buildBox("co64", bb.array())
        } else {
            val bb = ByteBuffer.allocate(8 + chunkOffsets.size * 4).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(0) // version + flags
            bb.putInt(chunkOffsets.size)
            for (off in chunkOffsets) {
                bb.putInt(off.toInt())
            }
            buildBox("stco", bb.array())
        }
    }

    /**
     * Builds a complete `moov` box from scratch for newly recorded M4A files.
     */
    fun buildFreshMoovBox(
        sampleRate: Int,
        channelCount: Int,
        audioSpecificConfig: ByteArray,
        sampleSizes: IntArray,
        chunkOffsets: LongArray,
        stscEntries: List<StscEntry>,
        sttsEntries: List<SttsEntry>,
        mvhdTimescale: Int = 1000,
        forceCo64: Boolean = false
    ): ByteArray {
        val mdhdTimescale = sampleRate.coerceAtLeast(8000)
        val totalMediaSamples = sttsEntries.sumOf { it.sampleCount.toLong() * it.sampleDelta.toLong() }
        val mvhdDuration = ((totalMediaSamples * mvhdTimescale.toLong()) / mdhdTimescale.toLong()).coerceAtLeast(1L)

        val mvhdBox = buildFreshMvhdBox(mvhdTimescale, mvhdDuration)
        val tkhdBox = buildFreshTkhdBox(mvhdDuration)
        val mdhdBox = buildFreshMdhdBox(mdhdTimescale, totalMediaSamples)
        val hdlrBox = buildFreshSounHdlrBox()
        val smhdBox = buildFreshSmhdBox()
        val dinfBox = buildFreshDinfBox()
        val stsdBox = buildFreshStsdMp4aBox(sampleRate, channelCount, audioSpecificConfig)
        val sttsBox = buildSttsBox(sttsEntries)
        val stscBox = buildStscBox(stscEntries)
        val stszBox = buildStszBox(sampleSizes)
        val stcoOrCo64Box = buildChunkOffsetBox(chunkOffsets, forceCo64)

        val stblBox = buildContainerBox("stbl", listOf(stsdBox, sttsBox, stscBox, stszBox, stcoOrCo64Box))
        val minfBox = buildContainerBox("minf", listOf(smhdBox, dinfBox, stblBox))
        val mdiaBox = buildContainerBox("mdia", listOf(mdhdBox, hdlrBox, minfBox))
        val trakBox = buildContainerBox("trak", listOf(tkhdBox, mdiaBox))
        return buildContainerBox("moov", listOf(mvhdBox, trakBox))
    }

    private fun buildFreshMvhdBox(timescale: Int, duration: Long): ByteArray {
        val useV1 = duration > 0xFFFFFFFFL
        val bb = ByteBuffer.allocate(if (useV1) 112 else 100).order(ByteOrder.BIG_ENDIAN)
        if (useV1) {
            bb.put(1) // version 1
            bb.put(byteArrayOf(0, 0, 0)) // flags
            bb.putLong(0L) // creation_time
            bb.putLong(0L) // modification_time
            bb.putInt(timescale)
            bb.putLong(duration)
        } else {
            bb.putInt(0) // version 0 + flags
            bb.putInt(0) // creation_time
            bb.putInt(0) // modification_time
            bb.putInt(timescale)
            bb.putInt(duration.toInt())
        }
        bb.putInt(0x00010000) // rate = 1.0
        bb.putShort(0x0100.toShort()) // volume = 1.0
        bb.putShort(0) // reserved
        bb.putInt(0)
        bb.putInt(0)
        // Unity matrix
        bb.putInt(0x00010000); bb.putInt(0); bb.putInt(0)
        bb.putInt(0); bb.putInt(0x00010000); bb.putInt(0)
        bb.putInt(0); bb.putInt(0); bb.putInt(0x40000000)
        for (i in 0 until 6) bb.putInt(0) // pre_defined
        bb.putInt(2) // next_track_ID
        return buildBox("mvhd", bb.array())
    }

    private fun buildFreshTkhdBox(durationInMvhdTimescale: Long): ByteArray {
        val useV1 = durationInMvhdTimescale > 0xFFFFFFFFL
        val bb = ByteBuffer.allocate(if (useV1) 96 else 84).order(ByteOrder.BIG_ENDIAN)
        if (useV1) {
            bb.put(1) // version 1
            bb.put(byteArrayOf(0, 0, 7)) // flags: track_enabled | track_in_movie | track_in_preview
            bb.putLong(0L)
            bb.putLong(0L)
            bb.putInt(1) // track_ID
            bb.putInt(0)
            bb.putLong(durationInMvhdTimescale)
        } else {
            bb.putInt(0x00000007) // version 0 + flags 7
            bb.putInt(0)
            bb.putInt(0)
            bb.putInt(1) // track_ID
            bb.putInt(0)
            bb.putInt(durationInMvhdTimescale.toInt())
        }
        bb.putInt(0); bb.putInt(0) // reserved
        bb.putShort(0); bb.putShort(0) // layer, alternate_group
        bb.putShort(0x0100.toShort()) // volume = 1.0 for audio track
        bb.putShort(0) // reserved
        // Unity matrix
        bb.putInt(0x00010000); bb.putInt(0); bb.putInt(0)
        bb.putInt(0); bb.putInt(0x00010000); bb.putInt(0)
        bb.putInt(0); bb.putInt(0); bb.putInt(0x40000000)
        bb.putInt(0) // width = 0
        bb.putInt(0) // height = 0
        return buildBox("tkhd", bb.array())
    }

    private fun buildFreshMdhdBox(timescale: Int, durationInMdhdTimescale: Long): ByteArray {
        val useV1 = durationInMdhdTimescale > 0xFFFFFFFFL
        val bb = ByteBuffer.allocate(if (useV1) 36 else 24).order(ByteOrder.BIG_ENDIAN)
        if (useV1) {
            bb.put(1)
            bb.put(byteArrayOf(0, 0, 0))
            bb.putLong(0L)
            bb.putLong(0L)
            bb.putInt(timescale)
            bb.putLong(durationInMdhdTimescale)
        } else {
            bb.putInt(0)
            bb.putInt(0)
            bb.putInt(0)
            bb.putInt(timescale)
            bb.putInt(durationInMdhdTimescale.toInt())
        }
        bb.putShort(0x55C4.toShort()) // ISO-639-2/T language "und"
        bb.putShort(0) // pre_defined
        return buildBox("mdhd", bb.array())
    }

    private fun buildFreshSounHdlrBox(): ByteArray {
        val nameBytes = "SoundHandler\u0000".toByteArray(StandardCharsets.US_ASCII)
        val bb = ByteBuffer.allocate(24 + nameBytes.size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(0) // version + flags
        bb.putInt(0) // pre_defined
        bb.put("soun".toByteArray(StandardCharsets.US_ASCII))
        bb.putInt(0); bb.putInt(0); bb.putInt(0) // reserved
        bb.put(nameBytes)
        return buildBox("hdlr", bb.array())
    }

    private fun buildFreshSmhdBox(): ByteArray {
        val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(0) // version + flags
        bb.putShort(0) // balance
        bb.putShort(0) // reserved
        return buildBox("smhd", bb.array())
    }

    private fun buildFreshDinfBox(): ByteArray {
        val urlBox = buildBox("url ", byteArrayOf(0, 0, 0, 1)) // self-contained flag = 1
        val drefPayload = ByteBuffer.allocate(8 + urlBox.size).order(ByteOrder.BIG_ENDIAN)
        drefPayload.putInt(0) // version + flags
        drefPayload.putInt(1) // entry_count = 1
        drefPayload.put(urlBox)
        val drefBox = buildBox("dref", drefPayload.array())
        return buildContainerBox("dinf", listOf(drefBox))
    }

    private fun buildFreshStsdMp4aBox(
        sampleRate: Int,
        channelCount: Int,
        audioSpecificConfig: ByteArray
    ): ByteArray {
        val asc = if (audioSpecificConfig.size >= 2) {
            audioSpecificConfig
        } else {
            buildAudioSpecificConfig(2, sampleRate, channelCount)
        }
        val esdsBox = buildEsdsBox(asc)

        val mp4aPayload = ByteBuffer.allocate(28 + esdsBox.size).order(ByteOrder.BIG_ENDIAN)
        mp4aPayload.put(ByteArray(6)) // reserved
        mp4aPayload.putShort(1) // data_reference_index = 1
        mp4aPayload.putInt(0) // reserved
        mp4aPayload.putInt(0) // reserved
        mp4aPayload.putShort(channelCount.coerceIn(1, 2).toShort())
        mp4aPayload.putShort(16) // sampleSize = 16
        mp4aPayload.putShort(0) // pre_defined
        mp4aPayload.putShort(0) // reserved
        mp4aPayload.putInt((sampleRate.coerceIn(8000, 65535) and 0xFFFF) shl 16)
        mp4aPayload.put(esdsBox)
        val mp4aBox = buildBox("mp4a", mp4aPayload.array())

        val stsdPayload = ByteBuffer.allocate(8 + mp4aBox.size).order(ByteOrder.BIG_ENDIAN)
        stsdPayload.putInt(0) // version + flags
        stsdPayload.putInt(1) // entry_count = 1
        stsdPayload.put(mp4aBox)
        return buildBox("stsd", stsdPayload.array())
    }

    fun buildEsdsBox(audioSpecificConfig: ByteArray): ByteArray {
        val ascLen = audioSpecificConfig.size
        // DecoderSpecificInfo (tag 0x05)
        val decSpecInfo = ByteBuffer.allocate(2 + ascLen)
        decSpecInfo.put(0x05.toByte())
        decSpecInfo.put(ascLen.toByte())
        decSpecInfo.put(audioSpecificConfig)

        // DecoderConfigDescriptor (tag 0x04): 13 bytes + decSpecInfo
        val decConfigLen = 13 + decSpecInfo.capacity()
        val decConfig = ByteBuffer.allocate(2 + decConfigLen).order(ByteOrder.BIG_ENDIAN)
        decConfig.put(0x04.toByte())
        decConfig.put(decConfigLen.toByte())
        decConfig.put(0x40.toByte()) // objectTypeIndication = 0x40 (MPEG-4 Audio)
        decConfig.put(0x15.toByte()) // streamType = AudioStream (5 << 2 | 1)
        decConfig.put(byteArrayOf(0x00, 0x18, 0x00)) // bufferSizeDB
        decConfig.putInt(192_000) // maxBitrate
        decConfig.putInt(128_000) // avgBitrate
        decConfig.put(decSpecInfo.array())

        // SLConfigDescriptor (tag 0x06)
        val slConfig = byteArrayOf(0x06, 0x01, 0x02)

        // ES_Descriptor (tag 0x03): 3 bytes + decConfig + slConfig
        val esDescLen = 3 + decConfig.capacity() + slConfig.size
        val esdsPayload = ByteBuffer.allocate(4 + 2 + esDescLen).order(ByteOrder.BIG_ENDIAN)
        esdsPayload.putInt(0) // version + flags
        esdsPayload.put(0x03.toByte())
        esdsPayload.put(esDescLen.toByte())
        esdsPayload.putShort(1) // ES_ID = 1
        esdsPayload.put(0x00.toByte()) // flags = 0
        esdsPayload.put(decConfig.array())
        esdsPayload.put(slConfig)
        return buildBox("esds", esdsPayload.array())
    }

    /**
     * Patches an existing `moov` byte array in-place (preserving `stsd`, `hdlr`, `dinf`, `udta`, etc.)
     * with updated `stts`, `stsc`, `stsz`, `stco`/`co64`, and updated durations in `mdhd`, `tkhd`,
     * `mvhd`, and `edts`/`elst`, recomputing all parent container box sizes.
     */
    fun patchMoovBytes(
        originalMoovBytes: ByteArray,
        newSttsEntries: List<SttsEntry>,
        newStscEntries: List<StscEntry>,
        newSampleSizes: IntArray,
        newChunkOffsets: LongArray,
        mdhdTimescale: Int,
        mvhdTimescale: Int,
        forceCo64: Boolean = false
    ): ByteArray {
        val totalSamplesDuration = newSttsEntries.sumOf { it.sampleCount.toLong() * it.sampleDelta.toLong() }
        val safeMdhdScale = mdhdTimescale.coerceAtLeast(1)
        val safeMvhdScale = mvhdTimescale.coerceAtLeast(1)
        val newMvhdDuration = ((totalSamplesDuration * safeMvhdScale.toLong()) / safeMdhdScale.toLong()).coerceAtLeast(1L)

        val moovHeader = readBufferBoxHeader(originalMoovBytes, 0, originalMoovBytes.size)
            ?: return originalMoovBytes
        val moovChildren = listBufferBoxes(originalMoovBytes, moovHeader.payloadOffset.toInt(), originalMoovBytes.size)

        val rebuiltMoovChildren = moovChildren.map { child ->
            when (child.type) {
                "mvhd" -> patchMvhdBox(originalMoovBytes, child, newMvhdDuration)
                "trak" -> patchTrakBox(
                    originalMoovBytes = originalMoovBytes,
                    trakHeader = child,
                    newSttsEntries = newSttsEntries,
                    newStscEntries = newStscEntries,
                    newSampleSizes = newSampleSizes,
                    newChunkOffsets = newChunkOffsets,
                    newMdhdDuration = totalSamplesDuration,
                    newMvhdDuration = newMvhdDuration,
                    forceCo64 = forceCo64
                )
                else -> originalMoovBytes.copyOfRange(child.offset.toInt(), child.endOffset.toInt())
            }
        }
        return buildContainerBox("moov", rebuiltMoovChildren)
    }

    private fun patchMvhdBox(bytes: ByteArray, mvhdHeader: Mp4BoxHeader, newDuration: Long): ByteArray {
        val raw = bytes.copyOfRange(mvhdHeader.offset.toInt(), mvhdHeader.endOffset.toInt())
        val payloadOff = mvhdHeader.headerSize
        if (raw.size < payloadOff + 20) return raw
        val version = raw[payloadOff].toInt() and 0xFF
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        if (version == 1 && raw.size >= payloadOff + 32) {
            bb.putLong(payloadOff + 24, newDuration)
        } else if (newDuration <= 0xFFFFFFFFL) {
            bb.putInt(payloadOff + 16, newDuration.toInt())
        } else {
            val ts = bb.getInt(payloadOff + 12)
            return buildFreshMvhdBox(ts, newDuration)
        }
        return raw
    }

    private fun patchTrakBox(
        originalMoovBytes: ByteArray,
        trakHeader: Mp4BoxHeader,
        newSttsEntries: List<SttsEntry>,
        newStscEntries: List<StscEntry>,
        newSampleSizes: IntArray,
        newChunkOffsets: LongArray,
        newMdhdDuration: Long,
        newMvhdDuration: Long,
        forceCo64: Boolean
    ): ByteArray {
        val children = listBufferBoxes(originalMoovBytes, trakHeader.payloadOffset.toInt(), trakHeader.endOffset.toInt())
        val rebuilt = children.map { child ->
            when (child.type) {
                "tkhd" -> patchTkhdBox(originalMoovBytes, child, newMvhdDuration)
                "edts" -> patchEdtsBox(originalMoovBytes, child, newMvhdDuration)
                "mdia" -> patchMdiaBox(
                    originalMoovBytes = originalMoovBytes,
                    mdiaHeader = child,
                    newSttsEntries = newSttsEntries,
                    newStscEntries = newStscEntries,
                    newSampleSizes = newSampleSizes,
                    newChunkOffsets = newChunkOffsets,
                    newMdhdDuration = newMdhdDuration,
                    forceCo64 = forceCo64
                )
                else -> originalMoovBytes.copyOfRange(child.offset.toInt(), child.endOffset.toInt())
            }
        }
        return buildContainerBox("trak", rebuilt)
    }

    private fun patchTkhdBox(bytes: ByteArray, tkhdHeader: Mp4BoxHeader, newDuration: Long): ByteArray {
        val raw = bytes.copyOfRange(tkhdHeader.offset.toInt(), tkhdHeader.endOffset.toInt())
        val payloadOff = tkhdHeader.headerSize
        if (raw.size < payloadOff + 24) return raw
        val version = raw[payloadOff].toInt() and 0xFF
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        if (version == 1 && raw.size >= payloadOff + 36) {
            bb.putLong(payloadOff + 28, newDuration)
        } else if (newDuration <= 0xFFFFFFFFL) {
            bb.putInt(payloadOff + 20, newDuration.toInt())
        } else {
            return buildFreshTkhdBox(newDuration)
        }
        return raw
    }

    private fun patchEdtsBox(bytes: ByteArray, edtsHeader: Mp4BoxHeader, newMvhdDuration: Long): ByteArray {
        val children = listBufferBoxes(bytes, edtsHeader.payloadOffset.toInt(), edtsHeader.endOffset.toInt())
        val rebuilt = children.map { child ->
            if (child.type == "elst") {
                patchElstBox(bytes, child, newMvhdDuration)
            } else {
                bytes.copyOfRange(child.offset.toInt(), child.endOffset.toInt())
            }
        }
        return buildContainerBox("edts", rebuilt)
    }

    private fun patchElstBox(bytes: ByteArray, elstHeader: Mp4BoxHeader, newMvhdDuration: Long): ByteArray {
        val raw = bytes.copyOfRange(elstHeader.offset.toInt(), elstHeader.endOffset.toInt())
        val payloadOff = elstHeader.headerSize
        if (raw.size < payloadOff + 16) return raw
        val version = raw[payloadOff].toInt() and 0xFF
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val entryCount = bb.getInt(payloadOff + 4)
        if (entryCount >= 1) {
            if (version == 1 && raw.size >= payloadOff + 8 + 20) {
                bb.putLong(payloadOff + 8, newMvhdDuration)
            } else if (version == 0 && raw.size >= payloadOff + 8 + 12 && newMvhdDuration <= 0xFFFFFFFFL) {
                bb.putInt(payloadOff + 8, newMvhdDuration.toInt())
            }
        }
        return raw
    }

    private fun patchMdiaBox(
        originalMoovBytes: ByteArray,
        mdiaHeader: Mp4BoxHeader,
        newSttsEntries: List<SttsEntry>,
        newStscEntries: List<StscEntry>,
        newSampleSizes: IntArray,
        newChunkOffsets: LongArray,
        newMdhdDuration: Long,
        forceCo64: Boolean
    ): ByteArray {
        val children = listBufferBoxes(originalMoovBytes, mdiaHeader.payloadOffset.toInt(), mdiaHeader.endOffset.toInt())
        val rebuilt = children.map { child ->
            when (child.type) {
                "mdhd" -> patchMdhdBox(originalMoovBytes, child, newMdhdDuration)
                "minf" -> patchMinfBox(
                    originalMoovBytes = originalMoovBytes,
                    minfHeader = child,
                    newSttsEntries = newSttsEntries,
                    newStscEntries = newStscEntries,
                    newSampleSizes = newSampleSizes,
                    newChunkOffsets = newChunkOffsets,
                    forceCo64 = forceCo64
                )
                else -> originalMoovBytes.copyOfRange(child.offset.toInt(), child.endOffset.toInt())
            }
        }
        return buildContainerBox("mdia", rebuilt)
    }

    private fun patchMdhdBox(bytes: ByteArray, mdhdHeader: Mp4BoxHeader, newDuration: Long): ByteArray {
        val raw = bytes.copyOfRange(mdhdHeader.offset.toInt(), mdhdHeader.endOffset.toInt())
        val payloadOff = mdhdHeader.headerSize
        if (raw.size < payloadOff + 20) return raw
        val version = raw[payloadOff].toInt() and 0xFF
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        if (version == 1 && raw.size >= payloadOff + 32) {
            bb.putLong(payloadOff + 24, newDuration)
        } else if (newDuration <= 0xFFFFFFFFL) {
            bb.putInt(payloadOff + 16, newDuration.toInt())
        } else {
            val ts = bb.getInt(payloadOff + 12)
            return buildFreshMdhdBox(ts, newDuration)
        }
        return raw
    }

    private fun patchMinfBox(
        originalMoovBytes: ByteArray,
        minfHeader: Mp4BoxHeader,
        newSttsEntries: List<SttsEntry>,
        newStscEntries: List<StscEntry>,
        newSampleSizes: IntArray,
        newChunkOffsets: LongArray,
        forceCo64: Boolean
    ): ByteArray {
        val children = listBufferBoxes(originalMoovBytes, minfHeader.payloadOffset.toInt(), minfHeader.endOffset.toInt())
        val rebuilt = children.map { child ->
            if (child.type == "stbl") {
                patchStblBox(
                    originalMoovBytes = originalMoovBytes,
                    stblHeader = child,
                    newSttsEntries = newSttsEntries,
                    newStscEntries = newStscEntries,
                    newSampleSizes = newSampleSizes,
                    newChunkOffsets = newChunkOffsets,
                    forceCo64 = forceCo64
                )
            } else {
                originalMoovBytes.copyOfRange(child.offset.toInt(), child.endOffset.toInt())
            }
        }
        return buildContainerBox("minf", rebuilt)
    }

    private fun patchStblBox(
        originalMoovBytes: ByteArray,
        stblHeader: Mp4BoxHeader,
        newSttsEntries: List<SttsEntry>,
        newStscEntries: List<StscEntry>,
        newSampleSizes: IntArray,
        newChunkOffsets: LongArray,
        forceCo64: Boolean
    ): ByteArray {
        val children = listBufferBoxes(originalMoovBytes, stblHeader.payloadOffset.toInt(), stblHeader.endOffset.toInt())
        val rebuilt = children.map { child ->
            when (child.type) {
                "stts" -> buildSttsBox(newSttsEntries)
                "stsc" -> buildStscBox(newStscEntries)
                "stsz" -> buildStszBox(newSampleSizes)
                "stco", "co64" -> buildChunkOffsetBox(newChunkOffsets, forceCo64)
                else -> originalMoovBytes.copyOfRange(child.offset.toInt(), child.endOffset.toInt())
            }
        }
        return buildContainerBox("stbl", rebuilt)
    }
}
