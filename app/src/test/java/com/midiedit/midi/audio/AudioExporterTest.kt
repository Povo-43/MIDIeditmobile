package com.midiedit.midi.audio

import com.midiedit.midi.model.Note
import com.midiedit.midi.model.Project
import com.midiedit.midi.model.TimeSignature
import com.midiedit.midi.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * JVM-level smoke test for the offline export pipeline. AAC uses the platform
 * MediaCodec, so it is exercised only by the real encoder on devices, but the
 * offline render and the pure-Java MP3 path run here.
 */
class AudioExporterTest {

    private fun soundFontFile(): File {
        val candidates = listOf(
            File("src/main/assets/piano.sf2"),
            File("app/src/main/assets/piano.sf2"),
        )
        return candidates.first { it.exists() }
    }

    private fun project(): Project = Project(
        title = "export-test",
        ppq = 480,
        tracks = listOf(
            Track(
                name = "Track 1",
                channel = 0,
                notes = listOf(
                    Note(id = 1, pitch = 60, startTick = 0, durationTicks = 480, velocity = 96),
                    Note(id = 2, pitch = 64, startTick = 480, durationTicks = 480, velocity = 96),
                ),
            )
        ),
        activeTrack = 0,
        bpm = 120.0,
        timeSignature = TimeSignature(4, 4),
    )

    @Test
    fun offlineRenderProducesNonSilentPcm() {
        val font = SoundFontLoader.load(soundFontFile())
        val pcm = AudioExporter.renderPcm(project(), font)
        val frames = pcm.size / 2
        // 120 BPM: one bar is 2 s. Expect the song end plus the release tail.
        assertTrue("unexpected frame count: $frames", frames > 44100 * 2)
        var peak = 0f
        var hasSignal = false
        for (s in pcm) {
            val v = s / 32768f
            if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
            if (s != 0.toShort()) hasSignal = true
        }
        assertTrue("PCM render produced silence", hasSignal)
        assertTrue("peak too low: $peak", peak > 0.001f)
    }

    @Test
    fun isEmptyWhenNoNotes() {
        val font = SoundFontLoader.load(soundFontFile())
        val empty = project().copy(tracks = listOf(Track(name = "Track 1", channel = 0)))
        assertEquals(0, AudioExporter.renderPcm(empty, font).size)
    }

    @Test
    fun mp3EncodeStartsWithFrameSync() {
        val font = SoundFontLoader.load(soundFontFile())
        val pcm = AudioExporter.renderPcm(project(), font)
        val out = ByteArrayOutputStream()
        AudioExporter.encodeMp3(pcm, out)
        val bytes = out.toByteArray()
        assertTrue("mp3 too small: ${bytes.size}", bytes.size > 1000)
        // MPEG frame sync: 11 bits all ones.
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertTrue("no frame sync in first bytes", (bytes[1].toInt() and 0xE0) == 0xE0)
    }
}
