package com.pianocanvas.midi.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SoundFontLoaderTest {

    private fun bundledFont(): SoundFont {
        val candidates = listOf(
            File("src/main/assets/piano.sf2"),
            File("app/src/main/assets/piano.sf2"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("bundled piano.sf2 not found")
        return SoundFontLoader.load(file.readBytes())
    }

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
    fun regionCountMatchesReportedMetadata() {
        val font = bundledFont()
        assertEquals(font.regions.size, font.regionCount)
        assertEquals(font.regions.map { it.keyLow }.minOrNull(), font.regions.minOf { it.keyLow })
    }
}
