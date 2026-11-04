package com.medit.midi.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SoundFontLoaderTest {

    private fun bundledFile(): File {
        val candidates = listOf(
            File("src/main/assets/piano.sf2"),
            File("app/src/main/assets/piano.sf2"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("bundled piano.sf2 not found")
    }

    private fun bundledFont(): SoundFont = SoundFontLoader.load(bundledFile())

    @Test
    fun bundledPianoParsesIntoPlayableRegions() {
        val font = bundledFont()
        assertTrue(font.name.isNotBlank())
        assertTrue(font.presetName.isNotBlank())
        assertTrue(font.sampleData.isNotEmpty())
        assertTrue(font.regions.isNotEmpty())

        for (region in font.regions) {
            assertTrue("keyLow ${region.keyLow}", region.keyLow in 0..127)
            assertTrue("keyHigh ${region.keyHigh}", region.keyHigh in region.keyLow..127)
            assertTrue("velocityLow", region.velocityLow in 0..127)
            assertTrue("velocityHigh", region.velocityHigh in region.velocityLow..127)
            assertTrue("rootKey", region.rootKey in 0..127)
            assertTrue("sampleRate ${region.sampleRate}", region.sampleRate in 8_000..192_000)
            assertTrue("sample range", region.sampleStart in 0 until region.sampleEnd)
            assertTrue("sample end", region.sampleEnd <= font.sampleData.size)
            assertTrue("loop mode", region.loopMode in 0..3)
            assertTrue("attack", region.envelope.attack >= 0f)
            assertTrue("release", region.envelope.release >= 0f)
        }
    }

    @Test
    fun bundledPianoCoversTheKeyboard() {
        val font = bundledFont()
        val covered = BooleanArray(128)
        for (region in font.regions) {
            if (region.velocityLow <= 100 && region.velocityHigh >= 100) {
                for (pitch in region.keyLow..region.keyHigh) covered[pitch] = true
            }
        }
        // The bundled font is a full piano; at least A0..C8 should be playable.
        assertTrue(covered[21])
        assertTrue(covered[60])
        assertTrue(covered[96])
        assertFalse(font.regions.any { it.sampleEnd - it.sampleStart <= 0 })
    }

    @Test
    fun invalidFilesAreRejectedWithReadableErrors() {
        val cases = listOf(
            ByteArray(0),
            "not a soundfont".toByteArray(),
            ByteArray(16) { 1 },
        )
        for (bytes in cases) {
            var threw = false
            try {
                SoundFontLoader.load(bytes)
            } catch (_: SoundFontException) {
                threw = true
            }
            assertTrue(threw)
        }
    }

    @Test
    fun streamingFromDiskMatchesInMemoryLoad() {
        val file = bundledFile()
        val fromFile = SoundFontLoader.load(file)
        val fromBytes = SoundFontLoader.load(file.readBytes())

        assertEquals(fromBytes.name, fromFile.name)
        assertEquals(fromBytes.presetName, fromFile.presetName)
        assertEquals(fromBytes.sampleData.size, fromFile.sampleData.size)
        assertEquals(fromBytes.regions.size, fromFile.regions.size)
        assertTrue(fromFile.sampleData.contentEquals(fromBytes.sampleData))
    }

    @Test
    fun sampleBudgetGrowsWithTheHeapAndStaysCapped() {
        val small = SoundFontLoader.maxSampleBytes(128L * 1024 * 1024)
        val large = SoundFontLoader.maxSampleBytes(2L * 1024 * 1024 * 1024)
        val huge = SoundFontLoader.maxSampleBytes(64L * 1024 * 1024 * 1024)

        assertTrue("budget must not be tiny", small >= 40L * 1024 * 1024)
        assertTrue("budget must grow with the heap", large > small)
        assertTrue("budget must be capped", huge <= 512L * 1024 * 1024)
        assertTrue("budget must stay below the heap", large < 2L * 1024 * 1024 * 1024)
    }

    @Test
    fun oversizedBanksAreRejectedWithAReadableMessage() {
        // 60M samples = 120MB, which must not pass a 64MB budget.
        var message: String? = null
        try {
            SoundFontLoader.requireSampleBudget(60_000_000, 64L * 1024 * 1024)
        } catch (e: SoundFontException) {
            message = e.message
        }
        assertTrue("expected a budget failure", message != null)
        assertTrue("message should mention the samples: $message", message!!.contains("サンプル"))
        assertTrue("message should mention the limit: $message", message!!.contains("MB"))

        // Within budget must pass without throwing.
        SoundFontLoader.requireSampleBudget(1_000, 64L * 1024 * 1024)
    }

    @Test
    fun regionCountMatchesReportedMetadata() {
        val font = bundledFont()
        assertEquals(font.regions.size, font.regionCount)
        assertEquals(font.regions.map { it.keyLow }.minOrNull(), font.regions.minOf { it.keyLow })
    }

    // ----------------------------------------------------------------------
    // Stereo sample pairs
    // ----------------------------------------------------------------------

    private fun stereoPairFont(): SoundFont {
        val left = TestSf2.Sample("Tone L", ShortArray(1000) { 24000 }, sampleType = 2, sampleLink = 1)
        val right = TestSf2.Sample("Tone R", ShortArray(1000) { -24000 }, sampleType = 1, sampleLink = 0)
        val zones = listOf(
            TestSf2.Zone(0, mapOf(17 to -500)),
            TestSf2.Zone(1, mapOf(17 to 500)),
        )
        return SoundFontLoader.load(TestSf2.build("stereo-bank", listOf(left, right), zones))
    }

    @Test
    fun stereoPairLoadsAsTwoSidedRegions() {
        val font = stereoPairFont()
        assertEquals(2, font.regions.size)
        assertEquals(-1, font.regions.first { it.stereoSide == -1 }.stereoSide)
        assertEquals(1, font.regions.first { it.stereoSide == 1 }.stereoSide)
        // Both halves stay playable across the whole keyboard.
        for (r in font.regions) {
            assertEquals(0, r.keyLow)
            assertEquals(127, r.keyHigh)
        }
    }

    @Test
    fun linkedPairSidesFollowThePartnerType() {
        // Linked samples (type 3) have no side of their own; the partner's
        // sampleType decides which channel they feed.
        val left = TestSf2.Sample("Tone L", ShortArray(1000) { 24000 }, sampleType = 3, sampleLink = 1)
        val right = TestSf2.Sample("Tone R", ShortArray(1000) { -24000 }, sampleType = 2, sampleLink = 0)
        val zones = listOf(
            TestSf2.Zone(0),
            TestSf2.Zone(1),
        )
        val font = SoundFontLoader.load(TestSf2.build("linked-bank", listOf(left, right), zones))
        assertEquals(2, font.regions.size)
        assertEquals(1, font.regions.count { it.stereoSide == 1 })
        assertEquals(1, font.regions.count { it.stereoSide == -1 })
    }

    @Test
    fun unpairedStereoHalfFallsBackToCentredMono() {
        // A bank referencing only the left half of a pair used to play in one
        // ear; the orphan must be demoted to a centred mono region instead.
        val left = TestSf2.Sample("Tone L", ShortArray(1000) { 24000 }, sampleType = 2)
        val font = SoundFontLoader.load(
            TestSf2.build("orphan-bank", listOf(left), listOf(TestSf2.Zone(0, mapOf(17 to -500))))
        )
        assertEquals(1, font.regions.size)
        assertEquals(0, font.regions[0].stereoSide)
    }

    @Test
    fun bundledPianoStaysCentredMono() {
        val font = bundledFont()
        assertTrue(font.regions.isNotEmpty())
        assertTrue(font.regions.all { it.stereoSide == 0 })
    }
}
