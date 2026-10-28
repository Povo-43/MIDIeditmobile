package com.pianocanvas.midi.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    @Test
    fun gridUnitsMatchMusicalDurations() {
        assertEquals(1920, GridUnit.WHOLE.ticks(480))
        assertEquals(960, GridUnit.HALF.ticks(480))
        assertEquals(480, GridUnit.QUARTER.ticks(480))
        assertEquals(240, GridUnit.EIGHTH.ticks(480))
        assertEquals(120, GridUnit.SIXTEENTH.ticks(480))
        assertEquals(720, GridUnit.QUARTER_DOTTED.ticks(480))
        assertEquals(160, GridUnit.EIGHTH_TRIPLET.ticks(480))
        assertEquals(80, GridUnit.SIXTEENTH_TRIPLET.ticks(480))
        assertEquals(1, GridUnit.OFF.ticks(480))
    }

    @Test
    fun snappingRoundsToNearestGridAndSupportsTriplets() {
        assertEquals(0, snapTick(50, GridUnit.SIXTEENTH, 480))
        assertEquals(120, snapTick(70, GridUnit.SIXTEENTH, 480))
        assertEquals(240, snapTick(190, GridUnit.SIXTEENTH, 480))
        assertEquals(160, snapTick(150, GridUnit.EIGHTH_TRIPLET, 480))
        assertEquals(320, snapTick(300, GridUnit.EIGHTH_TRIPLET, 480))
        assertEquals(777, snapTick(777, GridUnit.OFF, 480))
    }

    @Test
    fun tempoMapConvertsTicksAndSeconds() {
        val map = TempoMap(480, listOf(TempoPoint(0, 120.0)))
        assertEquals(120.0, map.bpmAt(0), 0.001)
        assertEquals(0.5, map.secondsAt(480), 0.0001)
        assertEquals(1.0, map.secondsAt(960), 0.0001)
        assertEquals(960.0, map.tickAtSeconds(1.0), 0.0001)
        assertEquals(500L, map.msAt(480))
    }

    @Test
    fun tempoMapHonoursChanges() {
        val map = TempoMap(
            ppq = 480,
            points = listOf(TempoPoint(0, 120.0), TempoPoint(960, 60.0)),
        )
        // 960 ticks at 120 BPM = 1 second, next 480 ticks at 60 BPM = 1 second.
        assertEquals(2.0, map.secondsAt(1440), 0.0001)
        assertEquals(60.0, map.bpmAt(1200), 0.001)
        assertEquals(120.0, map.bpmAt(959), 0.001)
    }

    @Test
    fun meterMapLabelsBarsAndBeats() {
        val map = MeterMap(480, emptyList(), TimeSignature(4, 4))
        assertEquals(0, map.barAt(0))
        assertEquals(0, map.barStartTick(0))
        assertEquals(1920, map.barStartTick(1))
        assertEquals("1.1", map.positionAt(0).label())
        assertEquals("1.2", map.positionAt(480).label())
        assertEquals("2.1", map.positionAt(1920).label())
    }

    @Test
    fun meterMapSupportsSignatureChanges() {
        val map = MeterMap(
            ppq = 480,
            points = listOf(
                TimeSignaturePoint(0, TimeSignature(4, 4)),
                TimeSignaturePoint(3840, TimeSignature(3, 4)),
            ),
            fallback = TimeSignature(4, 4),
        )
        assertEquals(3840, map.barStartTick(2))
        assertEquals(TimeSignature(3, 4), map.signatureAt(3840))
        assertEquals(1440, map.barTickLength(2))
        assertEquals(5280, map.barStartTick(3))
    }

    @Test
    fun notesReportEndAndClampSafely() {
        val note = Note(id = 1, pitch = 60, startTick = 120, durationTicks = 240, velocity = 90)
        assertEquals(360, note.endTick)
        val clamped = Note(id = 2, pitch = 200, startTick = -20, durationTicks = 0, velocity = 0)
            .clampInsideSong()
        assertEquals(127, clamped.pitch)
        assertEquals(0, clamped.startTick)
        assertEquals(1, clamped.durationTicks)
        assertEquals(1, clamped.velocity)
    }

    @Test
    fun noteNamesFollowScientificPitch() {
        assertEquals("C4", noteName(60))
        assertEquals("A4", noteName(69))
        assertEquals("C-1", noteName(0))
        assertEquals("G9", noteName(127))
    }

    @Test
    fun blackKeysMatchPianoLayout() {
        assertFalse(isBlackKey(60))
        assertTrue(isBlackKey(61))
        assertTrue(isBlackKey(63))
        assertFalse(isBlackKey(64))
        assertTrue(isBlackKey(66))
        assertFalse(isBlackKey(67))
    }

    @Test
    fun songEndCoversNotesAndRawEvents() {
        val project = Project.empty().copy(
            tracks = listOf(
                Track(
                    name = "Piano",
                    channel = 0,
                    notes = listOf(
                        Note(id = 1, pitch = 60, startTick = 100, durationTicks = 500),
                    ),
                    rawEvents = listOf(
                        RawEvent(900, 0xB0, intArrayOf(64, 0)),
                    ),
                )
            )
        )
        assertEquals(900, project.songEndTick())
    }

    @Test
    fun rawEventClassification() {
        val noteOn = RawEvent(0, 0x90, intArrayOf(60, 100))
        val noteOff = RawEvent(10, 0x80, intArrayOf(60, 0))
        val zeroVelocity = RawEvent(10, 0x90, intArrayOf(60, 0))
        val sustain = RawEvent(0, 0xB0, intArrayOf(64, 127))
        val meta = RawEvent(0, 0xFF, intArrayOf(0x2F))
        assertTrue(noteOn.isNoteOn)
        assertTrue(noteOff.isNoteOff)
        assertTrue(zeroVelocity.isNoteOff)
        assertTrue(sustain.isSustain)
        assertTrue(meta.isEndOfTrack)
        assertEquals(0, noteOn.channel)
        assertFalse(meta.isNoteOn)
    }
}
