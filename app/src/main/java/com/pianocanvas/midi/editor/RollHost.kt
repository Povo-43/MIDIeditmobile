package com.pianocanvas.midi.editor

import com.pianocanvas.midi.data.EditorPrefs
import com.pianocanvas.midi.data.ViewportState
import com.pianocanvas.midi.model.Note
import com.pianocanvas.midi.model.Project

/**
 * Everything the custom piano roll needs from the editor. Keeping this narrow
 * (instead of passing the ViewModel around) makes the view testable and stops
 * it from reaching into editor internals.
 */
interface RollHost {
    fun project(): Project?
    fun prefs(): EditorPrefs
    fun selection(): Set<Long>
    fun playheadTick(): Double
    fun isPlaying(): Boolean
    fun isAudioReady(): Boolean
    fun noteById(id: Long): Note?
    fun previewNote(pitch: Int, velocity: Int)
    fun addNoteAt(pitch: Int, startTick: Int, durationTicks: Int): Long?
    fun commitRollNotes(newNotes: List<Note>)
    fun setRollSelection(ids: Set<Long>)
    fun requestNoteInspector(id: Long)
    fun deleteNoteByDoubleTap(id: Long)
    fun deleteNotes(ids: Set<Long>)
    fun seekTo(tick: Double)
    fun setLoopRange(startTick: Int, endTick: Int)
    fun onViewportSettled(viewport: ViewportState)
    fun onHoverInfo(info: HoverInfo?)
}
