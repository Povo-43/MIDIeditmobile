package com.midiedit.midi.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * JVM tests for the offline synth core: stereo sample pairs must reach their
 * own channels (the "external SoundFonts only played in the left ear" bug)
 * and the user volume multiplier must scale the output.
 */
class PianoSynthOfflineTest {

    private fun bundled(): File {
        val candidates = listOf(
            File("src/main/assets/piano.sf2"),
            File("app/src/main/assets/piano.sf2"),
        )
        return candidates.firstOrNull { it.isFile } ?: error("bundled piano.sf2 not found")
    }

    /** Positive DC on the left sample, negative DC on the right one. */
    private fun stereoPairFont(): SoundFont {
        val left = TestSf2.Sample("Tone L", ShortArray(1000) { 24000 }, sampleType = 2, sampleLink = 1)
        val right = TestSf2.Sample("Tone R", ShortArray(1000) { -24000 }, sampleType = 1, sampleLink = 0)
        val zones = listOf(
            TestSf2.Zone(0, mapOf(17 to -500)),
            TestSf2.Zone(1, mapOf(17 to 500)),
        )
        return SoundFontLoader.load(TestSf2.build("stereo-bank", listOf(left, right), zones))
    }

    private fun renderNote(font: SoundFont, userGain: Float = 1f, frames: Int = 400): ShortArray {
        val synth = PianoSynth.Offline(font, 44100, userGain)
        synth.noteOn(60, 100, 0)
        val pcm = ShortArray(frames * 2)
        synth.renderPcm16(pcm, 0, frames)
        synth.close()
        return pcm
    }

    @Test
    fun stereoHalvesReachTheirOwnChannels() {
        // The sign of each channel tells exactly which sample fed it.
        val pcm = renderNote(stereoPairFont())
        // Frame 200 is well past the ~1 ms attack ramp.
        val left = pcm[200 * 2] / 32768f
        val right = pcm[200 * 2 + 1] / 32768f
        assertTrue("left channel should carry the left sample: $left", left > 0.1f)
        assertTrue("right channel should carry the right sample: $right", right < -0.1f)
    }

    @Test
    fun orphanHalfPlaysCentredInsteadOfOneEared() {
        val orphan = TestSf2.Sample("Tone L", ShortArray(1000) { 24000 }, sampleType = 2)
        val font = SoundFontLoader.load(
            TestSf2.build("orphan-bank", listOf(orphan), listOf(TestSf2.Zone(0, mapOf(17 to -500))))
        )
        val pcm = renderNote(font)
        val left = pcm[200 * 2] / 32768f
        val right = pcm[200 * 2 + 1] / 32768f
        assertTrue("left channel should be audible: $left", left > 0.1f)
        assertTrue("right channel should be audible too: $right", right > 0.1f)
    }

    @Test
    fun userGainScalesTheOutput() {
        val font = SoundFontLoader.load(bundled())
        fun peak(pcm: ShortArray): Float {
            var peak = 0f
            for (s in pcm) peak = maxOf(peak, abs(s / 32768f))
            return peak
        }
        val neutral = peak(renderNote(font, 1f))
        val half = peak(renderNote(font, 0.5f))
        assertTrue("neutral output should be audible: $neutral", neutral > 0.01f)
        val ratio = half / neutral
        assertTrue("half gain should roughly halve the peak: $ratio", ratio in 0.4f..0.6f)
    }
}
