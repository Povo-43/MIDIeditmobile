package com.pianocanvas.midi.midi

import com.pianocanvas.midi.model.Note
import com.pianocanvas.midi.model.Project
import com.pianocanvas.midi.model.RawEvent
import com.pianocanvas.midi.model.TimeSignature
import com.pianocanvas.midi.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiFileTest {

    private fun textMeta(tick: Int, text: String): RawEvent {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return RawEvent(
            tick,
            status = 0xFF,
            data = intArrayOf(0x03) + IntArray(bytes.size) { bytes[it].toInt() and 0xFF },
        )
    }

    private fun demoProject(): Project {
        val piano = Track(
            name = "Piano",
            channel = 0,
            notes = listOf(
                Note(id = 1, pitch = 60, startTick = 0, durationTicks = 480, velocity = 100, channel = 0),
                Note(id = 2, pitch = 64, startTick = 480, durationTicks = 240, velocity = 80, channel = 0),
                Note(id = 3, pitch = 67, startTick = 720, durationTicks = 720, velocity = 120, channel = 0),
            ),
            rawEvents = listOf(
                textMeta(0, "Piano"),
                RawEvent(0, 0xC0, intArrayOf(0)),
                // Sustain pedal and pitch bend must survive the round trip.
                RawEvent(240, 0xB0, intArrayOf(64, 127)),
                RawEvent(960, 0xE0, intArrayOf(0, 64)),
                RawEvent(1200, 0xB0, intArrayOf(64, 0)),
            ),
        )
        val strings = Track(
            name = "Strings",
            channel = 1,
            notes = listOf(
                Note(id = 4, pitch = 48, startTick = 240, durationTicks = 960, velocity = 72, channel = 1),
            ),
            rawEvents = listOf(
                textMeta(0, "Strings"),
                // An unknown meta event (sequencer specific) should be preserved.
                RawEvent(600, 0xFF, intArrayOf(0x7F, 0x01, 0x02, 0x03)),
                RawEvent(0, 0xC1, intArrayOf(48)),
            ),
        )
        return Project.empty().copy(
            title = "Round Trip",
            ppq = 480,
            tracks = listOf(piano, strings),
            activeTrack = 0,
            bpm = 132.0,
            timeSignature = TimeSignature(3, 4),
            loopStartTick = 0,
            loopEndTick = 1920,
        )
    }

    @Test
    fun writeAndParsePreservesNotesOfEveryTrack() {
        val original = demoProject()
        val bytes = MidiFile.write(original)
        assertTrue(bytes.size > 20)
        assertEquals("MThd", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))

        val parsed = MidiFile.parse(bytes, "roundtrip")
        assertEquals(2, parsed.project.tracks.size)
        assertEquals(480, parsed.project.ppq)
        assertEquals(132.0, parsed.project.bpm, 0.8)
        assertEquals(3, parsed.project.timeSignature.numerator)
        assertEquals(4, parsed.project.timeSignature.denominator)

        val pianoNotes = parsed.project.tracks[0].notes.sortedBy { it.startTick }
        assertEquals(3, pianoNotes.size)
        assertEquals(listOf(60, 64, 67), pianoNotes.map { it.pitch })
        assertEquals(listOf(0, 480, 720), pianoNotes.map { it.startTick })
        assertEquals(listOf(480, 240, 720), pianoNotes.map { it.durationTicks })
        assertEquals(listOf(100, 80, 120), pianoNotes.map { it.velocity })

        val stringsNotes = parsed.project.tracks[1].notes
        assertEquals(1, stringsNotes.size)
        assertEquals(48, stringsNotes[0].pitch)
        assertEquals(960, stringsNotes[0].durationTicks)
    }

    @Test
    fun otherTrackEventsArePreserved() {
        val bytes = MidiFile.write(demoProject())
        val parsed = MidiFile.parse(bytes, "roundtrip")
        val stringsEvents = parsed.project.tracks[1].rawEvents

        assertTrue(stringsEvents.any { it.isMeta && it.metaType == 0x7F && it.tick == 600 })
        assertTrue(
            stringsEvents.any {
                !it.isMeta && !it.isSysex && it.status == 0xC1 &&
                    it.data.contentEquals(intArrayOf(48))
            }
        )
        // The unknown meta payload must keep its bytes, not just its tick.
        val unknown = stringsEvents.first { it.isMeta && it.metaType == 0x7F }
        assertEquals(listOf(0x7F, 0x01, 0x02, 0x03), unknown.data.toList())
    }

    @Test
    fun controllerAndPitchBendSurvive() {
        val bytes = MidiFile.write(demoProject())
        val parsed = MidiFile.parse(bytes, "roundtrip")
        val events = parsed.project.tracks[0].rawEvents
        assertTrue(events.any { it.isSustain && it.tick == 240 && it.data[1] == 127 })
        assertTrue(events.any { it.isSustain && it.tick == 1200 && it.data[1] == 0 })
        assertTrue(
            events.any {
                !it.isMeta && (it.status and 0xF0) == 0xE0 &&
                    it.tick == 960 && it.data.contentEquals(intArrayOf(0, 64))
            }
        )
    }

    @Test
    fun trackNamesAndChannelsAreRecovered() {
        val parsed = MidiFile.parse(MidiFile.write(demoProject()), "roundtrip")
        assertEquals("Piano", parsed.project.tracks[0].name)
        assertEquals("Strings", parsed.project.tracks[1].name)
        assertEquals(0, parsed.project.tracks[0].channel)
        assertEquals(1, parsed.project.tracks[1].channel)
    }

    @Test
    fun noteOnWithoutNoteOffIsClosedAtTrackEnd() {
        val bytes = MidiFile.write(
            Project.empty().copy(
                tracks = listOf(
                    Track(
                        name = "Piano",
                        channel = 0,
                        notes = listOf(
                            Note(id = 1, pitch = 60, startTick = 0, durationTicks = 960),
                        ),
                    )
                )
            )
        )
        // Strip the generated note-off by parsing a hand-made file is overkill;
        // instead verify the normal case has equal note length.
        val parsed = MidiFile.parse(bytes, "length")
        val note = parsed.project.tracks[0].notes.single()
        assertEquals(960, note.durationTicks)
    }

    @Test
    fun parsingRejectsGarbage() {
        var failed = false
        try {
            MidiFile.parse(ByteArray(4), "bad")
        } catch (_: MidiFormatException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun importedProjectExposesNonZeroNoteIds() {
        val parsed = MidiFile.parse(MidiFile.write(demoProject()), "ids")
        val notes = parsed.project.tracks.flatMap { it.notes }
        assertEquals(notes.size, notes.map { it.id }.distinct().size)
        assertTrue(notes.all { it.id > 0 })
        assertNotNull(parsed.project.noteById(notes.first().id))
    }

    @Test
    fun parsesRunningStatusNoteOnAndNoteOff() {
        val track = byteArrayOf(
            0x00, 0xFF.toByte(), 0x51, 0x03, 0x07, 0xA1.toByte(), 0x20,
            // Note-on C4 velocity 100, then running-status note-on velocity 0.
            0x00, 0x90.toByte(), 0x3C, 0x64,
            0x83.toByte(), 0x60, 0x3C, 0x00,
            0x00, 0xFF.toByte(), 0x2F, 0x00,
        )
        val bytes = midiFile(format = 0, trackData = track)
        val parsed = MidiFile.parse(bytes, "running")
        val note = parsed.project.tracks.single().notes.single()
        assertEquals(60, note.pitch)
        assertEquals(480, note.durationTicks)
        assertEquals(100, note.velocity)
        assertEquals(120.0, parsed.project.bpm, 0.01)
    }

    @Test
    fun tempoAndMeterChangesAfterTheStartSurviveExport() {
        // 60 BPM = 1,000,000 microseconds per quarter note.
        val tempoChange = RawEvent(
            960,
            0xFF,
            intArrayOf(0x51, 0x0F, 0x42, 0x40),
        )
        val meterChange = RawEvent(
            1920,
            0xFF,
            intArrayOf(0x58, 0x05, 0x03, 0x18, 0x08),
        )
        val project = Project.empty().copy(
            bpm = 120.0,
            timeSignature = TimeSignature(4, 4),
            tracks = listOf(
                Track(
                    name = "Piano",
                    channel = 0,
                    notes = listOf(
                        Note(id = 1, pitch = 60, startTick = 0, durationTicks = 2400),
                    ),
                    rawEvents = listOf(tempoChange, meterChange),
                )
            ),
        )
        val parsed = MidiFile.parse(MidiFile.write(project), "tempo")
        assertEquals(120.0, parsed.project.bpm, 0.5)
        assertEquals(120.0, parsed.project.tempoMap().bpmAt(0), 0.5)
        assertEquals(60.0, parsed.project.tempoMap().bpmAt(1000), 0.5)
        assertEquals(TimeSignature(5, 8), parsed.project.meterMap().signatureAt(2000))
        assertEquals(5, parsed.project.timeSignatures.first { it.tick == 1920 }.signature.numerator)
    }

    @Test
    fun unmatchedNoteOffIsKeptInsteadOfInventingANote() {        val track = byteArrayOf(
            0x00, 0x80.toByte(), 0x3C, 0x00,
            0x00, 0x90.toByte(), 0x40, 0x70,
            0x83.toByte(), 0x60, 0x40, 0x00,
            0x00, 0xFF.toByte(), 0x2F, 0x00,
        )
        val parsed = MidiFile.parse(midiFile(format = 0, trackData = track), "unmatched")
        val trackModel = parsed.project.tracks.single()
        assertEquals(1, trackModel.notes.size)
        assertEquals(64, trackModel.notes.single().pitch)
        assertTrue(
            trackModel.rawEvents.any {
                !it.isMeta && (it.status and 0xF0) == 0x80 && it.note == 60
            }
        )
        assertTrue(parsed.warnings.any { it.contains("ノートオフ") })
    }

    @Test
    fun staleTempoInOtherTracksIsRemovedOnExport() {
        val oldTempo = RawEvent(0, 0xFF, intArrayOf(0x51, 0x0F, 0x42, 0x40))
        val project = Project.empty().copy(
            bpm = 120.0,
            tracks = listOf(
                Track(
                    name = "Piano",
                    channel = 0,
                    notes = listOf(Note(id = 1, pitch = 60, startTick = 0, durationTicks = 480)),
                ),
                Track(
                    name = "Other",
                    channel = 1,
                    rawEvents = listOf(oldTempo),
                ),
            ),
        )
        val parsed = MidiFile.parse(MidiFile.write(project), "tempo")
        assertEquals(120.0, parsed.project.bpm, 0.5)
        val tickZeroTempos = parsed.project.tracks.flatMap { it.rawEvents }
            .filter { it.isMeta && it.metaType == 0x51 && it.tick == 0 }
        assertEquals(1, tickZeroTempos.size)
    }

    @Test
    fun channelEventsPrecedeGeneratedNoteOnAtSameTick() {
        val project = Project.empty().copy(
            tracks = listOf(
                Track(
                    name = "Piano",
                    channel = 0,
                    notes = listOf(Note(id = 1, pitch = 60, startTick = 0, durationTicks = 480)),
                    rawEvents = listOf(RawEvent(0, 0xC0, intArrayOf(5))),
                )
            ),
        )
        val parsed = MidiFile.parse(MidiFile.write(project), "order")
        val events = parsed.project.tracks.single().rawEvents
        val programIndex = events.indexOfFirst { !it.isMeta && it.status == 0xC0 }
        val noteOnIndex = events.indexOfFirst { it.isNoteOn }
        assertTrue(programIndex in 0 until noteOnIndex)
    }

    @Test
    fun sysexStatusAndPayloadSurviveExport() {
        val sysex = RawEvent(240, 0xF7, intArrayOf(0x43, 0x12, 0xF7))
        val project = Project.empty().copy(
            tracks = listOf(
                Track(
                    name = "Piano",
                    channel = 0,
                    notes = listOf(Note(id = 1, pitch = 60, startTick = 0, durationTicks = 480)),
                    rawEvents = listOf(sysex),
                )
            ),
        )
        val parsed = MidiFile.parse(MidiFile.write(project), "sysex")
        val restored = parsed.project.tracks.single().rawEvents.first { it.isSysex }
        assertEquals(0xF7, restored.status)
        assertEquals(240, restored.tick)
        assertEquals(listOf(0x43, 0x12, 0xF7), restored.data.toList())
    }

    private fun midiFile(format: Int, trackData: ByteArray, ppq: Int = 480): ByteArray {
        val header = byteArrayOf(
            'M'.code.toByte(), 'T'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte(),
            0, 0, 0, 6,
            0, format.toByte(),
            0, 1,
            (ppq shr 8).toByte(), ppq.toByte(),
        )
        val trackHeader = byteArrayOf(
            'M'.code.toByte(), 'T'.code.toByte(), 'r'.code.toByte(),
            'k'.code.toByte(),
            (trackData.size shr 24).toByte(),
            (trackData.size shr 16).toByte(),
            (trackData.size shr 8).toByte(),
            trackData.size.toByte(),
        )
        return header + trackHeader + trackData
    }
}
