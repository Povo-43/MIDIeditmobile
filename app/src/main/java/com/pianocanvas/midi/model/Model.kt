package com.pianocanvas.midi.model

import kotlin.math.roundToInt

/**
 * A note on the edited piano track. All times are expressed in MIDI ticks so
 * that imported files keep their original resolution.
 */
data class Note(
    val id: Long,
    val pitch: Int,
    val startTick: Int,
    val durationTicks: Int,
    val velocity: Int = 96,
    val channel: Int = 0,
) {
    val endTick: Int get() = startTick + durationTicks

    fun shifted(deltaTicks: Int, deltaPitch: Int): Note = copy(
        startTick = (startTick + deltaTicks).coerceAtLeast(0),
        pitch = (pitch + deltaPitch).coerceIn(0, 127),
    )

    fun clampInsideSong(): Note = copy(
        startTick = startTick.coerceAtLeast(0),
        durationTicks = durationTicks.coerceAtLeast(1),
        pitch = pitch.coerceIn(0, 127),
        velocity = velocity.coerceIn(1, 127),
    )
}

data class TimeSignature(val numerator: Int, val denominator: Int) {
    init {
        require(numerator in 1..32)
        require(denominator in 1..32 && (denominator and (denominator - 1)) == 0)
    }

    /** Length of one bar in ticks. */
    fun barTicks(ppq: Int): Int =
        (numerator.toLong() * ppq * 4L / denominator).toInt().coerceAtLeast(1)

    fun label(): String = "$numerator/$denominator"
}

/** One raw (non-note) MIDI event kept for lossless round-tripping. */
class RawEvent(
    val tick: Int,
    /** 0xFF for meta, 0xF0 for sysex, otherwise a channel status byte. */
    val status: Int,
    /** Meta: [type, payload...]. Sysex: payload bytes (status kept separately). MIDI: data bytes. */
    val data: IntArray,
) {
    val isMeta: Boolean get() = status == 0xFF
    val isSysex: Boolean get() = status == 0xF0 || status == 0xF7
    val metaType: Int get() = if (isMeta && data.isNotEmpty()) data[0] else -1
    val isEndOfTrack: Boolean get() = isMeta && metaType == 0x2F

    val isNoteOn: Boolean
        get() = !isMeta && !isSysex && (status and 0xF0) == 0x90 &&
            data.size >= 2 && data[1] > 0

    val isNoteOff: Boolean
        get() = !isMeta && !isSysex && ((status and 0xF0) == 0x80 ||
            ((status and 0xF0) == 0x90 && data.size >= 2 && data[1] == 0))

    val isSustain: Boolean
        get() = !isMeta && !isSysex && (status and 0xF0) == 0xB0 &&
            data.size >= 2 && data[0] == 64

    val channel: Int get() = if (isMeta || isSysex) -1 else status and 0x0F

    val note: Int get() = if (data.isNotEmpty()) data[0] else 0
    val velocity: Int get() = if (data.size >= 2) data[1] else 0

    fun withTick(newTick: Int): RawEvent = RawEvent(newTick, status, data)

    override fun toString(): String =
        "RawEvent(tick=$tick, status=${status.toString(16)}, data=${data.joinToString()})"
}

data class Track(
    val name: String,
    val channel: Int,
    val notes: List<Note> = emptyList(),
    /** Original non-note and note events as imported. Note events are rebuilt on export. */
    val rawEvents: List<RawEvent> = emptyList(),
    val isDrum: Boolean = false,
) {
    val noteCount: Int get() = notes.size
    fun sortedNotes(): List<Note> = notes.sortedWith(compareBy({ it.startTick }, { it.pitch }))
}

data class TempoPoint(val tick: Int, val bpm: Double)

class TempoMap(
    val ppq: Int,
    val points: List<TempoPoint>,
    val fallbackBpm: Double = 120.0,
) {
    /** Sorted, de-duplicated points. */
    val resolved: List<TempoPoint> = buildList {
        val cleaned = points
            .filter { it.tick >= 0 && it.bpm > 0 }
            .groupBy { it.tick }
            .map { (tick, list) -> list.last().copy(tick = tick) }
            .sortedBy { it.tick }
        if (cleaned.isEmpty() || cleaned.first().tick != 0) {
            add(TempoPoint(0, cleaned.firstOrNull()?.bpm ?: fallbackBpm))
        }
        addAll(cleaned)
    }

    private val secondsAtPoint = DoubleArray(resolved.size)

    init {
        var seconds = 0.0
        for (i in resolved.indices) {
            secondsAtPoint[i] = seconds
            if (i < resolved.size - 1) {
                val ticks = (resolved[i + 1].tick - resolved[i].tick).coerceAtLeast(0)
                seconds += ticks.toDouble() / ppq * 60.0 / resolved[i].bpm
            }
        }
    }

    fun bpmAt(tick: Int): Double {
        var result = resolved.first().bpm
        for (p in resolved) {
            if (p.tick <= tick) result = p.bpm else break
        }
        return result
    }

    fun secondsAt(tick: Int): Double {
        if (tick <= 0) return 0.0
        var idx = 0
        for (i in resolved.indices) {
            if (resolved[i].tick <= tick) idx = i else break
        }
        val point = resolved[idx]
        return secondsAtPoint[idx] +
            (tick - point.tick).toDouble() / ppq * 60.0 / point.bpm
    }

    fun tickAtSeconds(seconds: Double): Double {
        if (seconds <= 0) return 0.0
        var idx = 0
        for (i in resolved.indices) {
            if (secondsAtPoint[i] <= seconds) idx = i else break
        }
        val point = resolved[idx]
        return point.tick + (seconds - secondsAtPoint[idx]) * ppq * point.bpm / 60.0
    }

    fun msAt(tick: Int): Long = (secondsAt(tick) * 1000.0).roundToInt().toLong()
}

data class TimeSignaturePoint(val tick: Int, val signature: TimeSignature)

/** Time-signature-aware conversion between ticks and bars/beats. */
class MeterMap(
    private val ppq: Int,
    points: List<TimeSignaturePoint>,
    fallback: TimeSignature,
) {
    val resolved: List<TimeSignaturePoint> = buildList {
        val cleaned = points
            .filter { it.tick >= 0 }
            .sortedBy { it.tick }
            .distinctBy { it.tick }
        if (cleaned.isEmpty() || cleaned.first().tick != 0) {
            add(TimeSignaturePoint(0, cleaned.firstOrNull()?.signature ?: fallback))
        }
        addAll(cleaned)
    }

    private val barStarts = IntArray(resolved.size)
    private val barCounts = IntArray(resolved.size)

    init {
        var bar = 0
        for (i in resolved.indices) {
            barStarts[i] = bar
            barCounts[i] = if (i < resolved.size - 1) {
                val span = (resolved[i + 1].tick - resolved[i].tick).coerceAtLeast(1)
                span / resolved[i].signature.barTicks(ppq)
            } else 0
            bar += barCounts[i]
        }
    }

    private fun indexAt(tick: Int): Int {
        var idx = 0
        for (i in resolved.indices) {
            if (resolved[i].tick <= tick) idx = i else break
        }
        return idx
    }

    /** 0-based bar index at a tick. */
    fun barAt(tick: Int): Int {
        val i = indexAt(tick)
        val sig = resolved[i].signature
        val barTicks = sig.barTicks(ppq)
        val local = ((tick - resolved[i].tick).coerceAtLeast(0)) / barTicks
        return barStarts[i] + local
    }

    fun signatureAt(tick: Int): TimeSignature = resolved[indexAt(tick)].signature

    fun barStartTick(bar: Int): Int {
        var i = 0
        for (idx in resolved.indices) {
            if (barStarts[idx] <= bar) i = idx else break
        }
        val sig = resolved[i].signature
        return resolved[i].tick + (bar - barStarts[i]) * sig.barTicks(ppq)
    }

    /** 0-based bar, 1-based beat (fractional within beat), and bar length. */
    fun positionAt(tick: Int): BarBeat {
        val i = indexAt(tick)
        val sig = resolved[i].signature
        val ppqBar = sig.barTicks(ppq)
        val localTick = (tick - resolved[i].tick).coerceAtLeast(0)
        val localBar = localTick / ppqBar
        val tickInBar = localTick % ppqBar
        val beatTicks = ppqBar.toDouble() / sig.numerator
        val beat = tickInBar / beatTicks
        return BarBeat(
            bar = barStarts[i] + localBar,
            beat = beat,
            signature = sig,
            ticksPerBeat = beatTicks.toInt().coerceAtLeast(1),
        )
    }

    fun barTickLength(bar: Int): Int = signatureAt(barStartTick(bar)).barTicks(ppq)
}

data class BarBeat(
    val bar: Int,
    /** 0-based fractional beat. Display as beat + 1. */
    val beat: Double,
    val signature: TimeSignature,
    val ticksPerBeat: Int,
) {
    fun label(): String {
        val whole = beat.toInt()
        val frac = beat - whole
        return if (frac < 0.02 || frac > 0.98) {
            "${bar + 1}.${whole + 1}"
        } else {
            "${bar + 1}.${whole + 1}${"%.2f".format(frac).substring(1)}"
        }
    }
}

data class Project(
    val title: String,
    val ppq: Int,
    val tracks: List<Track>,
    val activeTrack: Int,
    val bpm: Double,
    val timeSignature: TimeSignature,
    val timeSignatures: List<TimeSignaturePoint> = emptyList(),
    val loopStartTick: Int = 0,
    val loopEndTick: Int = 0,
    val loopEnabled: Boolean = false,
    val nextNoteId: Long = 1L,
    val importedPathName: String? = null,
) {
    val track: Track get() = tracks[activeTrack.coerceIn(0, tracks.size - 1)]
    val notes: List<Note> get() = track.notes

    fun tempoMap(): TempoMap {
        val points = mutableListOf(TempoPoint(0, bpm))
        for (t in tracks) {
            for (e in t.rawEvents) {
                if (e.isMeta && e.metaType == 0x51 && e.data.size >= 4 && e.tick > 0) {
                    val micros = (e.data[1] shl 16) or (e.data[2] shl 8) or e.data[3]
                    if (micros > 0) points.add(TempoPoint(e.tick, 60_000_000.0 / micros))
                }
            }
        }
        return TempoMap(ppq, points, bpm)
    }

    fun meterMap(): MeterMap {
        val points = mutableListOf(TimeSignaturePoint(0, timeSignature))
        for (t in tracks) {
            for (e in t.rawEvents) {
                if (e.isMeta && e.metaType == 0x58 && e.data.size >= 3 && e.tick > 0) {
                    val denom = 1 shl (e.data[2].coerceIn(0, 8))
                    val sig = runCatching { TimeSignature(e.data[1], denom) }.getOrNull()
                    if (sig != null) points.add(TimeSignaturePoint(e.tick, sig))
                }
            }
        }
        return MeterMap(ppq, points, timeSignature)
    }

    fun withActiveNotes(newNotes: List<Note>): Project {
        val sorted = newNotes.sortedWith(compareBy({ it.startTick }, { it.pitch }))
        val newTracks = tracks.toMutableList()
        newTracks[activeTrack] = newTracks[activeTrack].copy(notes = sorted)
        return copy(tracks = newTracks)
    }

    fun songEndTick(): Int {
        var end = 0
        for (t in tracks) {
            t.notes.forEach { end = maxOf(end, it.endTick) }
            t.rawEvents.forEach { end = maxOf(end, it.tick) }
        }
        return end
    }

    fun noteById(id: Long): Note? = notes.firstOrNull { it.id == id }

    companion object {
        fun empty(): Project = Project(
            title = "新しい曲",
            ppq = 480,
            tracks = listOf(
                Track(
                    name = "Piano",
                    channel = 0,
                    notes = emptyList(),
                    rawEvents = emptyList(),
                )
            ),
            activeTrack = 0,
            bpm = 120.0,
            timeSignature = TimeSignature(4, 4),
            timeSignatures = listOf(TimeSignaturePoint(0, TimeSignature(4, 4))),
            loopStartTick = 0,
            loopEndTick = 480 * 16,
            loopEnabled = false,
        )
    }
}

enum class GridUnit(
    val label: String,
    val beats: Double,
    val dotted: Boolean = false,
    val triplet: Boolean = false,
) {
    WHOLE("1/1", 4.0),
    HALF("1/2", 2.0),
    QUARTER("1/4", 1.0),
    EIGHTH("1/8", 0.5),
    SIXTEENTH("1/16", 0.25),
    THIRTY_SECOND("1/32", 0.125),
    WHOLE_DOTTED("1/1.", 6.0, dotted = true),
    HALF_DOTTED("1/2.", 3.0, dotted = true),
    QUARTER_DOTTED("1/4.", 1.5, dotted = true),
    HALF_TRIPLET("1/2T", 4.0 / 3.0, triplet = true),
    QUARTER_TRIPLET("1/4T", 2.0 / 3.0, triplet = true),
    EIGHTH_TRIPLET("1/8T", 1.0 / 3.0, triplet = true),
    SIXTEENTH_TRIPLET("1/16T", 1.0 / 6.0, triplet = true),
    THIRTY_SECOND_TRIPLET("1/32T", 1.0 / 12.0, triplet = true),
    OFF("OFF", 0.0);

    fun ticks(ppq: Int): Int =
        if (this == OFF) 1 else (ppq * beats).roundToInt().coerceAtLeast(1)

    val isGrid: Boolean get() = this != OFF
}

fun snapTick(tick: Int, grid: GridUnit, ppq: Int): Int {
    if (!grid.isGrid) return tick
    val step = grid.ticks(ppq)
    return ((tick + step / 2) / step) * step
}

fun snapTick(tick: Double, grid: GridUnit, ppq: Int): Int =
    snapTick(tick.roundToInt(), grid, ppq)

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val BLACK_KEYS = setOf(1, 3, 6, 8, 10)

fun noteName(pitch: Int): String {
    val p = pitch.coerceIn(0, 127)
    return NOTE_NAMES[p % 12] + (p / 12 - 1)
}

fun isBlackKey(pitch: Int): Boolean = BLACK_KEYS.contains(pitch.mod(12))
