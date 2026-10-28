package com.pianocanvas.midi.midi

import com.pianocanvas.midi.model.Note
import com.pianocanvas.midi.model.Project
import com.pianocanvas.midi.model.RawEvent
import com.pianocanvas.midi.model.TimeSignature
import com.pianocanvas.midi.model.TimeSignaturePoint
import com.pianocanvas.midi.model.Track
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Small, dependency-free Standard MIDI File reader/writer.
 *
 * Unknown meta, sysex and controller events are kept verbatim so that opening
 * and saving a file does not silently discard information this app does not
 * understand. Note-on/off pairs are extracted as editable [Note]s and re-emitted
 * from the note list on save (for every track, so no track is damaged).
 */
data class MidiParseResult(
    val project: Project,
    val warnings: List<String>,
)

class MidiFormatException(message: String) : Exception(message)

object MidiFile {

    const val MAX_EVENTS = 2_000_000

    private const val PRIORITY_SYSEX = 4
    private const val PRIORITY_NOTE_OFF = 2
    private const val PRIORITY_NOTE_ON = 3

    fun parse(bytes: ByteArray, title: String): MidiParseResult {
        if (bytes.size < 14) throw MidiFormatException("MIDIファイルが短すぎます")
        val reader = ByteReader(bytes)
        val headerId = reader.readAscii(4)
        if (headerId != "MThd") throw MidiFormatException("MThd ヘッダが見つかりません")
        val headerLen = reader.readIntBE()
        if (headerLen < 6) throw MidiFormatException("MIDIヘッダが壊れています")
        val format = reader.readShortBE()
        val declaredTracks = reader.readShortBE()
        val division = reader.readShortBE()
        if (headerLen > 6) reader.skip(headerLen - 6)

        if (format !in 0..1) {
            throw MidiFormatException("MIDIフォーマット $format には対応していません (0/1のみ)")
        }
        if (division and 0x8000 != 0) {
            throw MidiFormatException("SMPTE時間軸のMIDIには対応していません。PPQ形式で書き出してください。")
        }
        val ppq = division
        if (ppq < 1 || ppq > 32767) throw MidiFormatException("不正なPPQです: $ppq")

        val warnings = mutableListOf<String>()
        val parsedTracks = mutableListOf<RawTrack>()
        var guard = 0
        while (reader.remaining() >= 8 && parsedTracks.size < declaredTracks) {
            if (++guard > declaredTracks + 8) break
            val id = reader.readAscii(4)
            val len = reader.readIntBE()
            if (len < 0 || len > reader.remaining()) {
                warnings += "壊れたチャンクを読み飛ばしました: $id"
                break
            }
            if (id != "MTrk") {
                reader.skip(len)
                warnings += "未知のチャンク $id を保持できませんでした"
                continue
            }
            parsedTracks += parseTrack(bytes, reader.position, len, warnings)
            reader.skip(len)
        }
        if (parsedTracks.isEmpty()) throw MidiFormatException("MIDIトラックがありません")
        if (parsedTracks.size < declaredTracks) {
            warnings += "ヘッダのトラック数 (${declaredTracks}) より実際のトラック数が少ありません"
        }

        var noteId = 1L
        val trackModels = mutableListOf<Track>()
        parsedTracks.forEachIndexed { index, rawTrack ->
            val events = rawTrack.events
            val extracted = extractNotes(events, rawTrack.endTick, noteId, warnings, index)
            noteId = extracted.second
            val name = trackName(events) ?: "Track ${index + 1}"
            val primaryChannel = primaryChannel(events, extracted.first)
            trackModels += Track(
                name = name,
                channel = primaryChannel,
                notes = extracted.first,
                rawEvents = events.filterNot { it.isEndOfTrack },
                isDrum = primaryChannel == 9,
            )
        }

        val allEvents = trackModels.flatMap { t -> t.rawEvents }
        val tempoEvent = allEvents
            .filter { it.isMeta && it.metaType == 0x51 && it.tick >= 0 && it.data.size >= 4 }
            .minWithOrNull(compareBy({ it.tick }))
        val bpm = tempoEvent?.let {
            val micros = (it.data[1] shl 16) or (it.data[2] shl 8) or it.data[3]
            if (micros > 0) 60_000_000.0 / micros else 120.0
        } ?: 120.0

        val sigEvents = allEvents
            .filter { it.isMeta && it.metaType == 0x58 && it.data.size >= 3 }
            .sortedBy { it.tick }
        val firstSig = sigEvents.firstOrNull()?.let { e ->
            runCatching {
                TimeSignature(e.data[1], 1 shl e.data[2].coerceIn(0, 8))
            }.getOrNull()
        } ?: TimeSignature(4, 4)
        val sigPoints = sigEvents.mapNotNull { e ->
            runCatching {
                TimeSignaturePoint(e.tick, TimeSignature(e.data[1], 1 shl e.data[2].coerceIn(0, 8)))
            }.getOrNull()
        }.ifEmpty { listOf(TimeSignaturePoint(0, firstSig)) }

        val active = trackModels.indexOfFirst { it.notes.isNotEmpty() }
            .let { if (it >= 0) it else 0 }
        val songEnd = trackModels.flatMap { it.notes }.maxOfOrNull { it.endTick } ?: 0
        val meterSpan = max(songEnd, ppq * 4 * firstSig.numerator)
        val loopEnd = ((meterSpan + ppq * 4 - 1) / (ppq * 4)) * ppq * 4

        val project = Project(
            title = title.ifBlank { "読み込んだ曲" },
            ppq = ppq,
            tracks = trackModels,
            activeTrack = active,
            bpm = bpm,
            timeSignature = firstSig,
            timeSignatures = sigPoints,
            loopStartTick = 0,
            loopEndTick = max(loopEnd, ppq * 4),
            loopEnabled = false,
            nextNoteId = noteId,
            importedPathName = title.takeIf { it.isNotBlank() },
        )
        if (trackModels.all { it.notes.isEmpty() }) {
            warnings += "ノートが見つかりませんでした"
        }
        return MidiParseResult(project, warnings)
    }

    private class RawTrack(val events: List<RawEvent>, val endTick: Int)

    private fun parseTrack(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        warnings: MutableList<String>,
    ): RawTrack {
        val end = offset + length
        var pos = offset
        var absTick = 0
        var runningStatus = -1
        val events = mutableListOf<RawEvent>()
        var endTick = 0

        fun readByte(): Int {
            if (pos >= end) throw MidiFormatException("トラックデータが途中で終了しています")
            return bytes[pos++].toInt() and 0xFF
        }

        fun readVarLen(): Int {
            var value = 0
            var count = 0
            while (true) {
                val b = readByte()
                value = (value shl 7) or (b and 0x7F)
                count++
                if (b and 0x80 == 0) return value
                if (count > 4) throw MidiFormatException("可変長数値が不正です")
            }
        }

        while (pos < end) {
            val delta = readVarLen()
            absTick += delta
            if (events.size > MAX_EVENTS) throw MidiFormatException("イベント数が多すぎます")
            if (absTick < 0) throw MidiFormatException("時間情報が不正です")
            var status = readByte()
            if (status < 0x80) {
                if (runningStatus < 0) throw MidiFormatException("ランニングステータスが不正です")
                status = runningStatus
                pos-- // first data byte belongs to the event
            } else if (status < 0xF0) {
                runningStatus = status
            }

            when {
                status == 0xFF -> {
                    val type = readByte()
                    val len = readVarLen()
                    if (pos + len > end) throw MidiFormatException("メタイベントが壊れています")
                    val payload = IntArray(len) { readByte() }
                    events += RawEvent(absTick, 0xFF, intArrayOf(type) + payload)
                    if (type == 0x2F) {
                        endTick = absTick
                        break
                    }
                }
                status == 0xF0 || status == 0xF7 -> {
                    val len = readVarLen()
                    if (pos + len > end) throw MidiFormatException("SysExが壊れています")
                    val payload = IntArray(len) { readByte() }
                    // Keep the original F0/F7 status; escape (F7) messages are
                    // valid and must not be rewritten as F0.
                    events += RawEvent(absTick, status, payload)
                    runningStatus = -1
                }
                status in 0xF1..0xF6 || status in 0xF8..0xFE -> {
                    // System common / realtime messages. Keep minimal support so
                    // parsing can continue without pretending to understand them.
                    val dataLen = when (status) {
                        0xF1 -> 1
                        0xF2 -> 2
                        0xF3 -> 1
                        else -> 0
                    }
                    val data = IntArray(dataLen) { readByte() }
                    warnings += "システムメッセージ ${status.toString(16)} を読み飛ばしました"
                    events += RawEvent(absTick, status, data)
                    runningStatus = -1
                }
                else -> {
                    val high = status and 0xF0
                    val dataLen = if (high == 0xC0 || high == 0xD0) 1 else 2
                    if (pos + dataLen > end) throw MidiFormatException("MIDIイベントが壊れています")
                    val data = IntArray(dataLen) { readByte() }
                    events += RawEvent(absTick, status, data)
                }
            }
            endTick = absTick
        }
        if (pos > end) throw MidiFormatException("トラック長が不正です")
        return RawTrack(events, endTick)
    }

    private fun extractNotes(
        events: List<RawEvent>,
        endTick: Int,
        firstId: Long,
        warnings: MutableList<String>,
        trackIndex: Int,
    ): Pair<List<Note>, Long> {
        var id = firstId
        val notes = mutableListOf<Note>()
        val pending = HashMap<Int, ArrayDeque<Triple<Int, Int, Int>>>()
        events.forEach { e ->
            if (e.isNoteOn) {
                val key = (e.channel shl 8) or e.note
                val queue = pending.getOrPut(key) { ArrayDeque() }
                queue.addLast(Triple(e.tick, e.velocity, e.channel))
            } else if (e.isNoteOff) {
                val key = (e.channel shl 8) or e.note
                val queue = pending[key]
                val start = queue?.removeFirstOrNull()
                if (start == null) {
                    // A note-off without a matching note-on is kept as a raw event
                    // rather than turned into an invented note.
                    warnings += "Track ${trackIndex + 1}: 対応するノートオンのないノートオフを保持しました"
                } else {
                    notes += Note(
                        id = id++,
                        pitch = e.note,
                        startTick = start.first,
                        durationTicks = max(1, e.tick - start.first),
                        velocity = start.second,
                        channel = start.third,
                    )
                }
            }
        }
        pending.forEach { (key, queue) ->
            val pitch = key and 0xFF
            for ((start, vel, ch) in queue) {
                warnings += "Track ${trackIndex + 1}: 終了のないノートを曲末まで伸ばしました"
                notes += Note(
                    id = id++,
                    pitch = pitch,
                    startTick = start,
                    durationTicks = max(1, endTick - start),
                    velocity = vel,
                    channel = ch,
                )
            }
        }
        return notes.sortedWith(compareBy({ it.startTick }, { it.pitch })) to id
    }

    private fun primaryChannel(events: List<RawEvent>, notes: List<Note>): Int {
        if (notes.isNotEmpty()) return notes.groupingBy { it.channel }.eachCount().maxByOrNull { it.value }!!.key
        return events.firstOrNull { !it.isMeta && !it.isSysex }?.channel?.coerceAtLeast(0) ?: 0
    }

    private fun trackName(events: List<RawEvent>): String? {
        val ev = events.firstOrNull { it.isMeta && it.metaType == 0x03 && it.data.size > 1 }
            ?: return null
        val payload = ev.data.copyOfRange(1, ev.data.size)
        val text = decodeText(payload)?.trim()?.trimEnd('\u0000')
        return text?.takeIf { it.isNotBlank() }
    }

    private fun decodeText(bytes: IntArray): String? {
        if (bytes.isEmpty()) return null
        val array = ByteArray(bytes.size) { bytes[it].toByte() }
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(array)).toString()
        } catch (_: CharacterCodingException) {
            runCatching { String(array, charset("Shift_JIS")) }.getOrNull()
        }
    }

    // ---------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------

    /**
     * Serialises a project as a format-1 MIDI file. Note events are regenerated
     * from the editor state for every track; all other events are preserved.
     */
    fun write(project: Project): ByteArray {
        val tracks = project.tracks
        if (tracks.isEmpty()) throw MidiFormatException("書き出すトラックがありません")

        val trackBytes = ArrayList<ByteArray>(tracks.size)
        tracks.forEachIndexed { index, track ->
            val events = mutableListOf<OutputEvent>()
            var seq = 0
            // Current project tempo / meter is authoritative at tick 0. Remove
            // stale tick-0 values from every track so a format-1 file cannot
            // carry two conflicting tempos; only track 0 receives the new one.
            val addTempoAndMeter = index == 0

            for (raw in track.rawEvents) {
                if (raw.isEndOfTrack) continue
                if (raw.isNoteOn || raw.isNoteOff) continue // regenerated below
                if (raw.isMeta && raw.metaType == 0x51 && raw.tick == 0) continue
                if (raw.isMeta && raw.metaType == 0x58 && raw.tick == 0) continue
                events += OutputEvent(raw.tick, priorityFor(raw), seq++, raw)
            }

            if (addTempoAndMeter) {
                val micros = (60_000_000.0 / project.bpm.coerceIn(1.0, 1000.0)).roundToInt()
                val payload = intArrayOf(
                    0x51,
                    (micros shr 16) and 0xFF,
                    (micros shr 8) and 0xFF,
                    micros and 0xFF,
                )
                events += OutputEvent(0, 0, seq++, RawEvent(0, 0xFF, payload))
            }
            if (addTempoAndMeter) {
                val sig = project.timeSignature
                var denom = 0
                var d = sig.denominator
                while (d > 1) {
                    d = d shr 1
                    denom++
                }
                val payload = intArrayOf(0x58, sig.numerator, denom, 24, 8)
                events += OutputEvent(0, 0, seq++, RawEvent(0, 0xFF, payload))
            }

            for (note in track.notes) {
                val n = note.clampInsideSong()
                val ch = n.channel and 0x0F
                events += OutputEvent(
                    n.startTick,
                    PRIORITY_NOTE_ON,
                    seq++,
                    RawEvent(n.startTick, 0x90 or ch, intArrayOf(n.pitch, n.velocity.coerceIn(1, 127))),
                )
                events += OutputEvent(
                    n.endTick,
                    PRIORITY_NOTE_OFF,
                    seq++,
                    RawEvent(n.endTick, 0x80 or ch, intArrayOf(n.pitch, 0)),
                )
            }

            events.sortWith(
                compareBy<OutputEvent>({ it.tick }, { it.priority }, { it.seq })
            )
            val endTick = events.maxOfOrNull { it.tick } ?: 0
            val out = ByteArrayOutputStream()
            var lastTick = 0
            for (e in events) {
                writeVarLen(out, e.tick - lastTick)
                lastTick = e.tick
                writeEvent(out, e.raw)
            }
            writeVarLen(out, 0)
            out.write(0xFF)
            out.write(0x2F)
            out.write(0x00)
            trackBytes += out.toByteArray()
        }

        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        writeIntBE(out, 6)
        writeShortBE(out, 1)
        writeShortBE(out, trackBytes.size)
        writeShortBE(out, project.ppq.coerceIn(1, 32767))
        trackBytes.forEach {
            out.write("MTrk".toByteArray(Charsets.US_ASCII))
            writeIntBE(out, it.size)
            out.write(it)
        }
        return out.toByteArray()
    }

    private data class OutputEvent(val tick: Int, val priority: Int, val seq: Int, val raw: RawEvent)

    private fun priorityFor(raw: RawEvent): Int = when {
        raw.isMeta && (raw.metaType == 0x51 || raw.metaType == 0x58) -> 0
        raw.isSysex -> PRIORITY_SYSEX
        raw.isMeta -> 1
        else -> 1
    }

    private fun writeEvent(out: ByteArrayOutputStream, raw: RawEvent) {
        when {
            raw.isMeta -> {
                out.write(0xFF)
                out.write(raw.metaType.coerceIn(0, 0x7F))
                val payload = raw.data.copyOfRange(1, raw.data.size)
                writeVarLen(out, payload.size)
                payload.forEach { out.write(it and 0xFF) }
            }
            raw.isSysex -> {
                out.write(raw.status and 0xFF)
                writeVarLen(out, raw.data.size)
                raw.data.forEach { out.write(it and 0xFF) }
            }
            else -> {
                out.write(raw.status and 0xFF)
                raw.data.forEach { out.write(it and 0x7F) }
            }
        }
    }

    private fun writeVarLen(out: ByteArrayOutputStream, value: Int) {
        var v = value.coerceAtLeast(0)
        var buffer = v and 0x7F
        v = v shr 7
        while (v > 0) {
            buffer = (buffer shl 8) or ((v and 0x7F) or 0x80)
            v = v shr 7
        }
        while (true) {
            out.write(buffer and 0xFF)
            if (buffer and 0x80 != 0) buffer = buffer shr 8 else break
        }
    }

    private fun writeIntBE(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeShortBE(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private class ByteReader(private val bytes: ByteArray) {
        var position: Int = 0
            private set

        fun remaining(): Int = bytes.size - position

        fun readByte(): Int {
            if (position >= bytes.size) throw MidiFormatException("予期しないファイル末尾")
            return bytes[position++].toInt() and 0xFF
        }

        fun readAscii(n: Int): String {
            if (remaining() < n) throw MidiFormatException("予期しないファイル末尾")
            val s = String(bytes, position, n, Charsets.US_ASCII)
            position += n
            return s
        }

        fun readShortBE(): Int = (readByte() shl 8) or readByte()

        fun readIntBE(): Int {
            var v = 0
            repeat(4) { v = (v shl 8) or readByte() }
            return v
        }

        fun skip(n: Int) {
            if (n < 0 || remaining() < n) throw MidiFormatException("予期しないファイル末尾")
            position += n
        }
    }
}
