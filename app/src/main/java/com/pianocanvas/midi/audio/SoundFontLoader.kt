package com.pianocanvas.midi.audio

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min
import kotlin.math.pow

class SoundFontException(message: String) : Exception(message)

data class SfEnvelope(
    val delay: Float,
    val attack: Float,
    val hold: Float,
    val decay: Float,
    val sustain: Float,
    val release: Float,
)

data class SfRegion(
    val sampleStart: Int,
    val sampleEnd: Int,
    val loopStart: Int,
    val loopEnd: Int,
    val sampleRate: Int,
    val rootKey: Int,
    val tuneCents: Double,
    val keyLow: Int,
    val keyHigh: Int,
    val velocityLow: Int,
    val velocityHigh: Int,
    val attenuationCb: Double,
    val pan: Double,
    val loopMode: Int,
    val envelope: SfEnvelope,
    val exclusiveClass: Int,
)

/**
 * A parsed SoundFont 2.04 instrument.
 *
 * Supported: mono samples, key/velocity zones, root key, coarse/fine tuning,
 * volume envelope, attenuation, pan, sample loop modes, global preset /
 * instrument zones and generator precedence (instrument wins for non-additive
 * values). Modulators, filters, LFOs and stereo sample pairs are not applied;
 * they are ignored rather than silently mangled.
 */
class SoundFont(
    val name: String,
    val presetName: String,
    val sampleData: ShortArray,
    val regions: List<SfRegion>,
) {
    val regionCount: Int get() = regions.size
}

object SoundFontLoader {

    /**
     * The whole bank has to stay resident: the synth reaches into arbitrary
     * sample offsets while rendering, so samples cannot be streamed during
     * playback. Loaded samples live in a single [ShortArray] (2 bytes each),
     * which makes the usable size a function of the app heap rather than of
     * the file format. `android:largeHeap` is enabled, and the budget below is
     * half of whatever the process is actually allowed to allocate.
     */
    private const val HEAP_BUDGET_FRACTION = 0.45
    private const val ABSOLUTE_MAX_SAMPLE_BYTES = 512L * 1024 * 1024
    private const val ABSOLUTE_MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024

    internal fun maxSampleBytes(heapBytes: Long = Runtime.getRuntime().maxMemory()): Long {
        val budget = (heapBytes.toDouble() * HEAP_BUDGET_FRACTION).toLong()
        return budget.coerceIn(1L, ABSOLUTE_MAX_SAMPLE_BYTES)
    }

    private fun megabytes(bytes: Long): String =
        "%.0fMB".format(java.util.Locale.JAPAN, bytes / 1024.0 / 1024.0)

    internal fun requireSampleBudget(sampleCount: Int, budgetBytes: Long = maxSampleBytes()) {
        val needed = sampleCount.toLong() * 2L
        if (needed > budgetBytes) {
            throw SoundFontException(
                "サンプルが大きすぎます: 約${megabytes(needed)}必要ですが、" +
                    "この端末で読み込めるのは約${megabytes(budgetBytes)}までです"
            )
        }
    }

    /** Random access over either an in-memory file or a real file on disk. */
    private interface Source {
        val size: Long
        fun readFully(offset: Long, into: ByteArray, length: Int)
    }

    private class ByteArraySource(private val bytes: ByteArray) : Source {
        override val size: Long get() = bytes.size.toLong()
        override fun readFully(offset: Long, into: ByteArray, length: Int) {
            if (offset < 0 || offset + length > bytes.size) {
                throw SoundFontException("SoundFontデータが壊れています")
            }
            System.arraycopy(bytes, offset.toInt(), into, 0, length)
        }
    }

    private class FileSource(private val file: RandomAccessFile) : Source {
        override val size: Long get() = file.length()
        override fun readFully(offset: Long, into: ByteArray, length: Int) {
            if (offset < 0 || offset + length > size) {
                throw SoundFontException("SoundFontデータが壊れています")
            }
            file.seek(offset)
            file.readFully(into, 0, length)
        }
    }

    private class Parts {
        var infoName: String? = null
        var sampleOffset = -1L
        var sampleBytes = 0
        val hydra = HashMap<String, ByteArray>()
    }

    fun load(bytes: ByteArray): SoundFont = load(ByteArraySource(bytes))

    /**
     * Streams the bank from disk: only the small `pdta`/`INFO` chunks are held
     * in memory next to the decoded samples, so a large file no longer costs
     * its own size in heap while it is being parsed.
     */
    fun load(file: File): SoundFont = RandomAccessFile(file, "r").use { load(FileSource(it)) }

    private fun load(source: Source): SoundFont {
        if (source.size < 12) throw SoundFontException("SoundFontファイルが短すぎます")
        if (source.size > ABSOLUTE_MAX_FILE_BYTES) throw SoundFontException("SoundFontファイルが大きすぎます")

        val head = ByteArray(12)
        source.readFully(0, head, 12)
        if (readAscii(head, 0, 4) != "RIFF") throw SoundFontException("RIFFヘッダがありません")
        if (readAscii(head, 8, 4) != "sfbk") throw SoundFontException("SoundFont (sfbk) ではありません")

        val parts = Parts()
        var p = 12L
        while (p + 8 <= source.size) {
            val chunkHead = ByteArray(8)
            source.readFully(p, chunkHead, 8)
            val id = readAscii(chunkHead, 0, 4)
            val size = readIntLE(chunkHead, 4)
            if (size < 0 || p + 8 + size > source.size) throw SoundFontException("壊れたRIFFチャンク")
            if (id == "LIST") parseList(source, p + 8, p + 8 + size, parts)
            p += 8 + size + (size and 1)
        }

        if (parts.sampleOffset < 0) throw SoundFontException(
            "SF2のサンプルデータがありません (SF3/Ogg形式には対応していません)"
        )
        if (parts.sampleBytes % 2 != 0) throw SoundFontException("サンプルデータが壊れています")
        val sampleCount = parts.sampleBytes / 2
        // Reject oversized banks before allocating anything for them.
        requireSampleBudget(sampleCount)
        val samples = readSamples(source, parts.sampleOffset, sampleCount)

        val hydra = parts.hydra
        val infoName = parts.infoName

        val phdr = hydra["phdr"] ?: throw SoundFontException("phdrがありません")
        val pbag = hydra["pbag"] ?: throw SoundFontException("pbagがありません")
        val pgen = hydra["pgen"] ?: throw SoundFontException("pgenがありません")
        val insts = hydra["inst"] ?: throw SoundFontException("instがありません")
        val ibag = hydra["ibag"] ?: throw SoundFontException("ibagがありません")
        val igen = hydra["igen"] ?: throw SoundFontException("igenがありません")
        val shdr = hydra["shdr"] ?: throw SoundFontException("shdrがありません")

        val phdrCount = phdr.size / 38
        val pbagCount = pbag.size / 4
        val pgenCount = pgen.size / 4
        val instCount = insts.size / 22
        val ibagCount = ibag.size / 4
        val igenCount = igen.size / 4
        val shdrCount = shdr.size / 46
        if (phdrCount < 2 || instCount < 2 || shdrCount < 2 || pbagCount < 2 || ibagCount < 2) {
            throw SoundFontException("SoundFontのヘッダが不足しています")
        }

        val sampleHeaders = List(shdrCount - 1) { i ->
            val o = i * 46
            SampleHeader(
                name = readFixedString(shdr, o, 20),
                start = readIntLE(shdr, o + 20),
                end = readIntLE(shdr, o + 24),
                loopStart = readIntLE(shdr, o + 28),
                loopEnd = readIntLE(shdr, o + 32),
                sampleRate = readIntLE(shdr, o + 36),
                originalPitch = shdr[o + 40].toInt() and 0xFF,
                pitchCorrection = shdr[o + 41].toInt(),
                sampleType = readShortLE(shdr, o + 44) and 0xFFFF,
            )
        }
        if (sampleHeaders.isEmpty()) throw SoundFontException("サンプルヘッダがありません")

        data class PresetHeader(
            val name: String,
            val program: Int,
            val bank: Int,
            val bagIndex: Int,
        )

        val presets = List(phdrCount - 1) { i ->
            val o = i * 38
            PresetHeader(
                name = readFixedString(phdr, o, 20),
                program = readShortLE(phdr, o + 20) and 0xFFFF,
                bank = readShortLE(phdr, o + 22) and 0xFFFF,
                bagIndex = readShortLE(phdr, o + 24) and 0xFFFF,
            )
        }
        val chosen = presets.firstOrNull { it.bank == 0 && it.program == 0 }
            ?: presets.firstOrNull()
            ?: throw SoundFontException("プリセットがありません")
        val chosenIndex = presets.indexOf(chosen)

        fun pbagGen(index: Int): Pair<Int, Int> {
            val o = index * 4
            return readShortLE(pbag, o) to readShortLE(pbag, o + 2)
        }

        fun zoneGenerators(
            startBag: Int,
            endBag: Int,
            bags: ByteArray,
            gens: ByteArray,
            genCount: Int,
            bagCount: Int,
        ): List<Map<Int, Int>> {
            val result = ArrayList<Map<Int, Int>>()
            val limit = endBag.coerceAtMost(bagCount - 1)
            for (b in startBag until limit) {
                if (b < 0 || b >= bagCount) break
                val bo = b * 4
                val genStart = readShortLE(bags, bo) and 0xFFFF
                val nextBo = (b + 1) * 4
                if (nextBo + 4 > bags.size) break
                val genEnd = readShortLE(bags, nextBo) and 0xFFFF
                val map = HashMap<Int, Int>()
                var g = genStart
                val ge = minOf(genEnd, genCount)
                while (g < ge) {
                    val go = g * 4
                    val oper = readShortLE(gens, go) and 0xFFFF
                    val amount = readShortLE(gens, go + 2)
                    map[oper] = amount
                    g++
                }
                result += map
            }
            return result
        }

        val presetZoneEnd = presets.getOrNull(chosenIndex + 1)?.bagIndex ?: pbagCount
        val presetZones = zoneGenerators(
            chosen.bagIndex, presetZoneEnd, pbag, pgen, pgenCount, pbagCount
        )
        if (presetZones.isEmpty()) throw SoundFontException("プリセットにゾーンがありません")

        val instHeaders = List(instCount - 1) { i ->
            val o = i * 22
            readShortLE(insts, o + 20) and 0xFFFF
        }

        val regions = ArrayList<SfRegion>()
        var presetGlobal = emptyMap<Int, Int>()

        for (pz in presetZones) {
            val instId = pz[41]
            if (instId == null) {
                presetGlobal = presetGlobal + pz
                continue
            }
            if (instId < 0 || instId >= instHeaders.size) continue
            val mergedPreset = presetGlobal + pz
            val pKey = rangeOf(mergedPreset[43])
            val pVel = rangeOf(mergedPreset[44])

            val bagStart = instHeaders[instId]
            val bagEnd = instHeaders.getOrNull(instId + 1) ?: ibagCount
            val instZones = zoneGenerators(bagStart, bagEnd, ibag, igen, igenCount, ibagCount)
            var instGlobal = emptyMap<Int, Int>()

            for (iz in instZones) {
                val sampleId = iz[53]
                if (sampleId == null) {
                    instGlobal = instGlobal + iz
                    continue
                }
                if (sampleId < 0 || sampleId >= sampleHeaders.size) continue
                val sh = sampleHeaders[sampleId]
                if (sh.sampleType != 1 && sh.sampleType != 0) {
                    // Stereo / linked samples are not supported; skipping them is
                    // safer than playing both channels out of phase.
                    continue
                }
                val mergedInst = instGlobal + iz
                val iKey = rangeOf(mergedInst[43])
                val iVel = rangeOf(mergedInst[44])
                val keyLow = maxOf(pKey.first, iKey.first)
                val keyHigh = minOf(pKey.second, iKey.second)
                val velLow = maxOf(pVel.first, iVel.first)
                val velHigh = minOf(pVel.second, iVel.second)
                if (keyLow > keyHigh || velLow > velHigh) continue

                val gens = HashMap<Int, Int>()
                gens.putAll(mergedPreset)
                gens.putAll(mergedInst)

                fun sampleOffset(oper: Int): Int {
                    val coarse = if (oper == 2) gens[45] ?: 0 else if (oper == 3) gens[50] ?: 0 else 0
                    return (gens[oper] ?: 0) + coarse * 32768 +
                        if (oper == 0 || oper == 1) (gens[if (oper == 0) 4 else 12] ?: 0) * 32768 else 0
                }

                var start = sh.start + sampleOffset(0)
                var end = sh.end + sampleOffset(1)
                var loopStart = sh.loopStart + sampleOffset(2)
                var loopEnd = sh.loopEnd + sampleOffset(3)
                if (start < 0) start = 0
                if (end > samples.size) end = samples.size
                if (end <= start + 1) continue
                if (loopStart < start) loopStart = start
                if (loopEnd > end) loopEnd = end
                val loopMode = (gens[54] ?: 0) and 0xFFFF
                val hasLoop = loopMode in 1..3 && loopEnd > loopStart + 1

                val sampleRate = sh.sampleRate.takeIf { it in 4000..192000 } ?: 32000
                val root = (gens[58]?.takeIf { it in 0..127 })
                    ?: sh.originalPitch.takeIf { it in 0..127 }
                    ?: 60
                val tune = (gens[52] ?: 0).toDouble() + sh.pitchCorrection + (gens[51] ?: 0) * 100.0
                val attenuation = (gens[48] ?: 0).toDouble()
                val pan = ((gens[17] ?: 0).coerceIn(-500, 500)) / 1000.0
                val env = SfEnvelope(
                    delay = timecents(gens[33] ?: -12000),
                    attack = timecents(gens[34] ?: -12000),
                    hold = timecents(gens[35] ?: -12000),
                    decay = timecents(gens[36] ?: -12000),
                    sustain = centibelsToGain((gens[37] ?: 0).coerceIn(0, 1440)),
                    release = timecents(gens[38] ?: -12000),
                )
                regions += SfRegion(
                    sampleStart = start,
                    sampleEnd = end,
                    loopStart = if (hasLoop) loopStart else 0,
                    loopEnd = if (hasLoop) loopEnd else 0,
                    sampleRate = sampleRate,
                    rootKey = root,
                    tuneCents = tune,
                    keyLow = keyLow,
                    keyHigh = keyHigh,
                    velocityLow = velLow,
                    velocityHigh = velHigh,
                    attenuationCb = attenuation,
                    pan = pan,
                    loopMode = if (hasLoop) loopMode else 0,
                    envelope = env,
                    exclusiveClass = gens[57] ?: 0,
                )
            }
        }

        if (regions.isEmpty()) throw SoundFontException("再生可能なゾーンがありません")
        return SoundFont(
            name = infoName ?: "Piano SoundFont",
            presetName = chosen.name,
            sampleData = samples,
            regions = regions.sortedWith(compareBy({ it.keyLow }, { it.velocityLow })),
        )
    }

    fun load(input: ByteArrayInputStream): SoundFont = load(input.readBytes())

    /** Records the sample chunk and loads the small hydra chunks; never the samples. */
    private fun parseList(source: Source, start: Long, end: Long, parts: Parts) {
        if (start + 4 > end) return
        val typeBytes = ByteArray(4)
        source.readFully(start, typeBytes, 4)
        val type = readAscii(typeBytes, 0, 4)
        var p = start + 4
        while (p + 8 <= end) {
            val chunkHead = ByteArray(8)
            source.readFully(p, chunkHead, 8)
            val id = readAscii(chunkHead, 0, 4)
            val size = readIntLE(chunkHead, 4)
            if (size < 0 || p + 8 + size > end) throw SoundFontException("壊れたチャンク: $id")
            val dataStart = p + 8
            when {
                type == "INFO" && id == "INAM" -> {
                    val data = ByteArray(size)
                    source.readFully(dataStart, data, size)
                    parts.infoName = readFixedString(data, 0, size)
                }
                // The sample chunk can be hundreds of megabytes: remember where
                // it is instead of copying it.
                type == "sdta" && id == "smpl" -> {
                    parts.sampleOffset = dataStart
                    parts.sampleBytes = size
                }
                type == "pdta" -> {
                    val data = ByteArray(size)
                    source.readFully(dataStart, data, size)
                    parts.hydra[id] = data
                }
            }
            p += 8 + size + (size and 1)
        }
    }

    /** Decodes the sample chunk block by block so no full copy is needed. */
    private fun readSamples(source: Source, offset: Long, sampleCount: Int): ShortArray {
        val out = try {
            ShortArray(sampleCount)
        } catch (_: OutOfMemoryError) {
            throw SoundFontException(
                "メモリが足りずサンプルを読み込めません (約${megabytes(sampleCount.toLong() * 2)}必要)"
            )
        }
        val blockSamples = 1 shl 15
        val block = ByteArray(blockSamples * 2)
        var index = 0
        var pos = offset
        while (index < sampleCount) {
            val n = min(blockSamples, sampleCount - index)
            source.readFully(pos, block, n * 2)
            var b = 0
            for (i in 0 until n) {
                out[index + i] =
                    ((block[b].toInt() and 0xFF) or (block[b + 1].toInt() shl 8)).toShort()
                b += 2
            }
            index += n
            pos += n.toLong() * 2
        }
        return out
    }

    private data class SampleHeader(
        val name: String,
        val start: Int,
        val end: Int,
        val loopStart: Int,
        val loopEnd: Int,
        val sampleRate: Int,
        val originalPitch: Int,
        val pitchCorrection: Int,
        val sampleType: Int,
    )

    private fun rangeOf(amount: Int?): Pair<Int, Int> {
        if (amount == null) return 0 to 127
        val low = amount and 0xFF
        val high = (amount shr 8) and 0xFF
        return low.coerceIn(0, 127) to high.coerceIn(0, 127)
    }

    private fun timecents(value: Int): Float {
        if (value <= -11950) return 0f
        return 2.0.pow(value / 1200.0).toFloat().coerceIn(0f, 60f)
    }

    private fun centibelsToGain(cb: Int): Float =
        10.0.pow(-cb / 200.0).toFloat()

    private fun readShortLE(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) throw SoundFontException("SoundFontデータが壊れています")
        return (bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) throw SoundFontException("SoundFontデータが壊れています")
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset + length > bytes.size) throw SoundFontException("SoundFontデータが壊れています")
        return String(bytes, offset, length, Charsets.US_ASCII)
    }

    private fun readFixedString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val max = minOf(offset + length, bytes.size)
        while (end < max && bytes[end].toInt() != 0) end++
        return String(bytes, offset, end - offset, Charsets.ISO_8859_1).trim()
    }
}
