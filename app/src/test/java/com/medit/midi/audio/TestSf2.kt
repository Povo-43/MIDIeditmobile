package com.medit.midi.audio

/**
 * Builds minimal but structurally valid SoundFont 2.04 files for tests.
 * Only the chunks [SoundFontLoader] reads are emitted (INFO/sdta/pdta).
 */
object TestSf2 {

    /** One sample of the bank. */
    data class Sample(
        val name: String,
        val pcm: ShortArray,
        val sampleRate: Int = 44100,
        val originalPitch: Int = 60,
        val sampleLink: Int = -1,
        val sampleType: Int = 0, // 0 mono, 1 right, 2 left, 3 linked
    )

    /** One instrument zone: which sample it plays plus raw generator ops. */
    data class Zone(
        val sampleIndex: Int,
        /** Generator operator -> amount, e.g. 17 (pan) to -500..500. */
        val gens: Map<Int, Int> = emptyMap(),
    )

    private fun chunk(id: String, body: ByteArray): ByteArray {
        require(id.length == 4)
        val size = body.size
        val pad = size and 1
        val out = ByteArray(8 + size + pad)
        id.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        out[4] = (size and 0xFF).toByte()
        out[5] = ((size shr 8) and 0xFF).toByte()
        out[6] = ((size shr 16) and 0xFF).toByte()
        out[7] = ((size shr 24) and 0xFF).toByte()
        body.copyInto(out, 8)
        return out
    }

    private fun list(type: String, body: ByteArray): ByteArray {
        val inner = ByteArray(4 + body.size)
        type.toByteArray(Charsets.US_ASCII).copyInto(inner, 0)
        body.copyInto(inner, 4)
        return chunk("LIST", inner)
    }

    private fun fixedString(value: String, length: Int): ByteArray {
        val out = ByteArray(length)
        value.toByteArray(Charsets.ISO_8859_1).take(length).forEachIndexed { i, b -> out[i] = b }
        return out
    }

    private fun u16(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun i32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    /**
     * Builds a one-preset, one-instrument bank whose instrument zones are
     * given by [zones]. Sample data is concatenated in [samples] order.
     */
    fun build(name: String, samples: List<Sample>, zones: List<Zone>): ByteArray {
        val out = java.io.ByteArrayOutputStream()

        // ---- INFO ----
        val info = chunk("INAM", fixedString(name, name.length + 1))
        out.write(list("INFO", info))

        // ---- sdta ----
        val pcm = ByteArray(samples.sumOf { it.pcm.size } * 2)
        var pcmPos = 0
        var sampleStart = 0
        val sampleRanges = ArrayList<Pair<Int, Int>>()
        samples.forEach { s ->
            s.pcm.forEach { v ->
                pcm[pcmPos] = (v.toInt() and 0xFF).toByte()
                pcm[pcmPos + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
                pcmPos += 2
            }
            sampleRanges += sampleStart to (sampleStart + s.pcm.size)
            sampleStart += s.pcm.size
        }
        out.write(list("sdta", chunk("smpl", pcm)))

        // ---- pdta ----
        val pdta = java.io.ByteArrayOutputStream()

        // phdr: terminal + one preset (bank 0, program 0).
        fun phdrEntry(presetName: String, program: Int, bank: Int, bagIndex: Int): ByteArray =
            fixedString(presetName, 20) + u16(program) + u16(bank) + u16(bagIndex) +
                i32(0) + i32(0) + i32(0)
        pdta.write(chunk("phdr", phdrEntry("Test preset", 0, 0, 0) + phdrEntry("EOI", 0, 0, 1)))

        // pbag: preset zone 0 -> pgen 0; terminal bag -> pgen end.
        pdta.write(chunk("pbag", u16(0) + u16(0) + u16(1) + u16(0)))
        // pgen: single instrument generator pointing at instrument 0 (op 41).
        pdta.write(chunk("pgen", u16(41) + u16(0)))
        pdta.write(chunk("pmod", ByteArray(10)))

        // inst: one instrument + terminal.
        val inst = fixedString("Test inst", 20) + u16(0) + fixedString("EOI", 20) + u16(zones.size)
        pdta.write(chunk("inst", inst))

        // ibag/igen: one bag per zone, each zone emits its generators plus the
        // sampleID (op 53) last, matching how the loader reads them.
        val ibag = java.io.ByteArrayOutputStream()
        val igen = java.io.ByteArrayOutputStream()
        var genIndex = 0
        zones.forEach { zone ->
            ibag.write(u16(genIndex))
            ibag.write(u16(0))
            zone.gens.forEach { (oper, amount) ->
                igen.write(u16(oper))
                igen.write(u16(amount))
                genIndex++
            }
            igen.write(u16(53))
            igen.write(u16(zone.sampleIndex))
            genIndex++
        }
        ibag.write(u16(genIndex))
        ibag.write(u16(0))
        pdta.write(chunk("ibag", ibag.toByteArray()))
        pdta.write(chunk("imod", ByteArray(10)))
        pdta.write(chunk("igen", igen.toByteArray()))

        // shdr: one header per sample, then the terminal entry LAST (the same
        // layout the bundled piano.sf2 uses, and the one the loader indexes).
        val shdr = java.io.ByteArrayOutputStream()
        samples.forEachIndexed { i, s ->
            val (start, end) = sampleRanges[i]
            shdr.write(fixedString(s.name, 20))
            shdr.write(i32(start))
            shdr.write(i32(end))
            shdr.write(i32(end)) // loopStart
            shdr.write(i32(end)) // loopEnd
            shdr.write(i32(s.sampleRate))
            shdr.write(byteArrayOf(s.originalPitch.toByte(), 0))
            shdr.write(u16(if (s.sampleLink >= 0) s.sampleLink else 0))
            shdr.write(u16(s.sampleType))
        }
        shdr.write(fixedString("EOS", 20) + i32(0) + i32(0) + i32(0) + i32(0) +
            i32(0) + byteArrayOf(0, 0) + u16(0) + u16(0))
        pdta.write(chunk("shdr", shdr.toByteArray()))

        out.write(list("pdta", pdta.toByteArray()))

        val body = out.toByteArray()
        val riff = java.io.ByteArrayOutputStream()
        riff.write("RIFF".toByteArray(Charsets.US_ASCII))
        riff.write(i32(body.size))
        riff.write("sfbk".toByteArray(Charsets.US_ASCII))
        riff.write(body)
        return riff.toByteArray()
    }
}
