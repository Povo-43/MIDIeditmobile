package com.pianocanvas.midi.audio

import android.os.Process
import com.pianocanvas.midi.model.Project
import com.pianocanvas.midi.model.TempoMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Tick-accurate-enough transport running on its own thread. The UI only reads
 * an atomic playhead position; no Compose state is updated at audio rate.
 */
class PlaybackEngine(
    private val audio: AudioEngine,
) {
    private enum class Kind { SUSTAIN, NOTE_OFF, NOTE_ON }

    private data class PlayEvent(
        val tick: Int,
        val kind: Kind,
        val pitch: Int = 0,
        val velocity: Int = 0,
        val channel: Int = 0,
    )

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val position = AtomicReference(0.0)
    private val generation = AtomicInteger(0)
    private val eventIndex = AtomicInteger(0)
    private val threadId = AtomicLong(0)

    @Volatile private var playing = false
    @Volatile private var events: List<PlayEvent> = emptyList()
    @Volatile private var eventsDirty = false
    @Volatile private var tempoMap: TempoMap? = null
    @Volatile private var ppq: Int = 480
    @Volatile private var loopEnabled = false
    @Volatile private var loopStart = 0
    @Volatile private var loopEnd = 0
    @Volatile private var stopAtTick = 0
    @Volatile private var sustainAtStart = false
    @Volatile private var activeTrackIndex = 0

    private var lastProject: Project? = null

    fun currentTick(): Double = position.get()

    fun setProject(project: Project) {
        lastProject = project
        val track = project.track
        val map = project.tempoMap()
        tempoMap = map
        ppq = project.ppq
        loopEnabled = project.loopEnabled && project.loopEndTick > project.loopStartTick
        loopStart = project.loopStartTick.coerceAtLeast(0)
        loopEnd = project.loopEndTick.coerceAtLeast(loopStart + 1)
        activeTrackIndex = project.activeTrack
        val end = maxOf(project.songEndTick(), project.ppq * 4)
        stopAtTick = end + project.ppq / 2
        val newEvents = buildEvents(project)
        if (playing) {
            eventsDirty = true
        } else {
            events = newEvents
            eventsDirty = false
            eventIndex.set(lowerBound(newEvents, position.get().toInt()))
        }
    }

    fun play(project: Project? = null) {
        if (project != null) setProject(project)
        if (playing) return
        if (eventsDirty) {
            val p = lastProject
            if (p != null) events = buildEvents(p)
            eventsDirty = false
        }
        var tick = position.get()
        val end = stopAtTick
        if (tick >= end - 1.0) tick = if (loopEnabled) loopStart.toDouble() else 0.0
        if (loopEnabled && (tick < loopStart || tick >= loopEnd)) tick = loopStart.toDouble()
        position.set(tick)
        eventIndex.set(lowerBound(events, tick.toInt()))
        sustainAtStart = sustainAt(tick.toInt(), lastProject)
        audio.allNotesOff(fast = true)
        audio.setSustain(sustainAtStart)
        retriggerSoundingNotes(tick)
        playing = true
        _isPlaying.value = true
        val myGen = generation.incrementAndGet()
        val t = Thread({ runPlayer(myGen) }, "PianoPlayback-${threadId.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun pause() {
        if (!playing) return
        playing = false
        generation.incrementAndGet()
        _isPlaying.value = false
        audio.setSustain(false)
        audio.allNotesOff()
    }

    fun stop() {
        val wasPlaying = playing
        if (wasPlaying) pause()
        position.set(0.0)
        eventIndex.set(0)
        if (!wasPlaying) {
            audio.setSustain(false)
            audio.allNotesOff()
        }
    }

    fun toggle(project: Project) {
        if (playing) pause() else play(project)
    }

    fun seek(tick: Double) {
        val target = max(0.0, tick)
        val wasPlaying = playing
        if (wasPlaying) {
            playing = false
            generation.incrementAndGet()
            _isPlaying.value = false
            audio.setSustain(false)
            audio.allNotesOff()
        }
        position.set(target)
        eventIndex.set(lowerBound(events, target.toInt()))
        if (wasPlaying) {
            val p = lastProject
            if (p != null) play(p) else {
                position.set(target)
            }
        }
    }

    fun shutdown() {
        playing = false
        generation.incrementAndGet()
        _isPlaying.value = false
        audio.setSustain(false)
        audio.allNotesOff(fast = true)
    }

    private fun runPlayer(myGeneration: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var lastNanos = System.nanoTime()
        var sustain = sustainAtStart
        while (playing && generation.get() == myGeneration) {
            val now = System.nanoTime()
            val dt = ((now - lastNanos).coerceAtLeast(0L)) / 1_000_000_000.0
            lastNanos = now
            val map = tempoMap ?: break
            val current = position.get()
            val bpm = map.bpmAt(current.toInt())
            var next = current + dt * ppq.toDouble() * bpm / 60.0
            val loopOn = loopEnabled && loopEnd > loopStart
            if (loopOn && next >= loopEnd) {
                dispatchUntil(loopEnd - 0.001) { on, off ->
                    if (on) sustain = true
                    if (off) sustain = false
                }
                val carry = (next - loopEnd).coerceAtMost((loopEnd - loopStart).toDouble())
                audio.setSustain(false)
                audio.allNotesOff(fast = true)
                if (eventsDirty) {
                    lastProject?.let { events = buildEvents(it) }
                    eventsDirty = false
                }
                val newPos = loopStart + carry
                position.set(newPos)
                eventIndex.set(lowerBound(events, loopStart))
                sustain = sustainAt(loopStart, lastProject)
                audio.setSustain(sustain)
                retriggerSoundingNotes(newPos)
            } else {
                dispatchUntil(next) { on, off ->
                    if (on) sustain = true
                    if (off) sustain = false
                }
                position.set(next)
                if (!loopOn && next >= stopAtTick) {
                    playing = false
                    _isPlaying.value = false
                    audio.setSustain(false)
                    audio.allNotesOff()
                    break
                }
            }
            try {
                Thread.sleep(4)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun dispatchUntil(targetTick: Double, sustainCallback: (on: Boolean, off: Boolean) -> Unit) {
        val evs = events
        var idx = eventIndex.get()
        while (idx < evs.size && evs[idx].tick <= targetTick) {
            val e = evs[idx]
            when (e.kind) {
                Kind.SUSTAIN -> {
                    val isOn = e.velocity >= 64
                    audio.setSustain(isOn)
                    sustainCallback(isOn, !isOn)
                }
                Kind.NOTE_ON -> audio.noteOn(e.pitch, e.velocity, e.channel)
                Kind.NOTE_OFF -> audio.noteOff(e.pitch, e.channel)
            }
            idx++
        }
        eventIndex.set(idx)
    }

    private fun retriggerSoundingNotes(tick: Double) {
        val project = lastProject ?: return
        val t = tick.toInt()
        for (note in project.track.notes) {
            if (note.startTick < t && note.endTick > t) {
                audio.noteOn(note.pitch, note.velocity, note.channel)
            }
        }
    }

    private fun sustainAt(tick: Int, project: Project?): Boolean {
        val p = project ?: return false
        var on = false
        for (e in p.track.rawEvents) {
            if (!e.isSustain || e.tick > tick) continue
            on = e.velocity >= 64
        }
        return on
    }

    private fun buildEvents(project: Project): List<PlayEvent> {
        val list = ArrayList<PlayEvent>(project.track.notes.size * 2 + 8)
        for (n in project.track.notes) {
            if (n.durationTicks <= 0) continue
            list += PlayEvent(n.startTick, Kind.NOTE_ON, n.pitch, n.velocity, n.channel)
            list += PlayEvent(n.endTick, Kind.NOTE_OFF, n.pitch, 0, n.channel)
        }
        for (e in project.track.rawEvents) {
            if (e.isSustain && e.data.size >= 2) {
                list += PlayEvent(e.tick, Kind.SUSTAIN, velocity = e.data[1])
            }
        }
        list.sortWith(compareBy({ it.tick }, { it.kind.ordinal }))
        return list
    }

    private fun lowerBound(list: List<PlayEvent>, tick: Int): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].tick < tick) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
