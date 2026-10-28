package com.pianocanvas.midi.editor

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pianocanvas.midi.audio.AudioEngine
import com.pianocanvas.midi.audio.AudioStatus
import com.pianocanvas.midi.audio.PlaybackEngine
import com.pianocanvas.midi.data.AutosaveData
import com.pianocanvas.midi.data.EditorPrefs
import com.pianocanvas.midi.data.ProjectStore
import com.pianocanvas.midi.data.ViewportState
import com.pianocanvas.midi.midi.MidiFile
import com.pianocanvas.midi.model.GridUnit
import com.pianocanvas.midi.model.Note
import com.pianocanvas.midi.model.Project
import com.pianocanvas.midi.model.TimeSignature
import com.pianocanvas.midi.model.TimeSignaturePoint
import com.pianocanvas.midi.model.noteName
import com.pianocanvas.midi.model.snapTick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class HoverZone { EMPTY, NOTE_BODY, NOTE_START, NOTE_END }

data class HoverInfo(
    val xPx: Float,
    val yPx: Float,
    val pitch: Int,
    val tick: Int,
    val snappedTick: Int,
    val barBeatLabel: String,
    val zone: HoverZone,
    val noteId: Long?,
    val notePitch: Int?,
    val noteStart: Int?,
    val noteDuration: Int?,
    val ghostStart: Int,
    val ghostPitch: Int,
    val ghostDurationTicks: Int,
    val stylus: Boolean,
)

sealed interface AutosaveState {
    data object Idle : AutosaveState
    data object Saving : AutosaveState
    data class Saved(val atMillis: Long) : AutosaveState
    data class Failed(val message: String) : AutosaveState
}

sealed interface BootState {
    data object Loading : BootState
    data class Ready(val restored: Boolean) : BootState
    data class Failed(val message: String) : BootState
}

data class BusyState(
    val importing: Boolean = false,
    val exporting: Boolean = false,
    val soundFont: Boolean = false,
)

data class EditorUiState(
    val project: Project?,
    val selection: Set<Long>,
    val prefs: EditorPrefs,
    val viewport: ViewportState,
    val viewportEpoch: Int,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val autosave: AutosaveState,
    val boot: BootState,
    val audio: AudioStatus,
    val isPlaying: Boolean,
    val busy: BusyState,
    val trackPickerVisible: Boolean,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val audio = AudioEngine(application)
    private val playback = PlaybackEngine(audio)

    private val _project = MutableStateFlow<Project?>(null)
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    private val _prefs = MutableStateFlow(EditorPrefs())
    private val _viewport = MutableStateFlow(ViewportState())
    private val _viewportEpoch = MutableStateFlow(0)
    private val _hover = MutableStateFlow<HoverInfo?>(null)
    private val _undoCount = MutableStateFlow(0)
    private val _redoCount = MutableStateFlow(0)
    private val _autosave = MutableStateFlow<AutosaveState>(AutosaveState.Idle)
    private val _boot = MutableStateFlow<BootState>(BootState.Loading)
    private val _busy = MutableStateFlow(BusyState())
    private val _trackPickerVisible = MutableStateFlow(false)
    private val _inspectorNoteId = MutableStateFlow<Long?>(null)
    private val messages = Channel<String>(Channel.BUFFERED)

    val messageFlow = messages.receiveAsFlow()
    val hover: StateFlow<HoverInfo?> = _hover.asStateFlow()
    val inspectorNoteId: StateFlow<Long?> = _inspectorNoteId.asStateFlow()
    val audioStatus: StateFlow<AudioStatus> = audio.status

    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()
    private var clipboard: List<Note> = emptyList()
    private var clipboardBaseTick = 0
    private var autosaveJob: Job? = null
    private var autosaveEnabled = false
    private var pendingImport: Uri? = null
    private val previewJobs = HashMap<Int, Job>()

    private data class EditSnapshot(
        val notes: List<Note>,
        val bpm: Double,
        val timeSignature: TimeSignature,
        val timeSignatures: List<TimeSignaturePoint>,
    )

    private data class CoreState(
        val project: Project?,
        val selection: Set<Long>,
        val prefs: EditorPrefs,
        val viewport: ViewportState,
        val viewportEpoch: Int,
    )

    private data class MetaState(
        val canUndo: Boolean,
        val canRedo: Boolean,
        val autosave: AutosaveState,
        val boot: BootState,
        val busy: BusyState,
    )

    private data class RuntimeState(
        val audio: AudioStatus,
        val isPlaying: Boolean,
        val trackPickerVisible: Boolean,
    )

    private val coreFlow = combine(
        _project, _selection, _prefs, _viewport, _viewportEpoch
    ) { project, selection, prefs, viewport, epoch ->
        CoreState(project, selection, prefs, viewport, epoch)
    }

    private val metaFlow = combine(
        _undoCount, _redoCount, _autosave, _boot, _busy
    ) { undo, redo, autosave, boot, busy ->
        MetaState(undo > 0, redo > 0, autosave, boot, busy)
    }

    private val runtimeFlow = combine(
        audio.status, playback.isPlaying, _trackPickerVisible
    ) { audioStatus, playing, picker ->
        RuntimeState(audioStatus, playing, picker)
    }

    val uiState: StateFlow<EditorUiState> = combine(coreFlow, metaFlow, runtimeFlow) { c, m, r ->
        EditorUiState(
            project = c.project,
            selection = c.selection,
            prefs = c.prefs,
            viewport = c.viewport,
            viewportEpoch = c.viewportEpoch,
            canUndo = m.canUndo,
            canRedo = m.canRedo,
            autosave = m.autosave,
            boot = m.boot,
            audio = r.audio,
            isPlaying = r.isPlaying,
            busy = m.busy,
            trackPickerVisible = r.trackPickerVisible,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        EditorUiState(
            project = null,
            selection = emptySet(),
            prefs = EditorPrefs(),
            viewport = ViewportState(),
            viewportEpoch = 0,
            canUndo = false,
            canRedo = false,
            autosave = AutosaveState.Idle,
            boot = BootState.Loading,
            audio = AudioStatus.Loading,
            isPlaying = false,
            busy = BusyState(),
            trackPickerVisible = false,
        )
    )

    init {
        audio.loadBundled()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { ProjectStore.load(app) }
            result.fold(
                onSuccess = { data ->
                    if (data != null) {
                        restore(data)
                    } else {
                        _boot.value = BootState.Ready(false)
                        autosaveEnabled = true
                        runPendingImport()
                    }
                },
                onFailure = { t ->
                    _boot.value = BootState.Ready(false)
                    autosaveEnabled = true
                    postMessage("自動保存を復元できませんでした: ${t.message ?: t.javaClass.simpleName}")
                    runPendingImport()
                }
            )
        }
        viewModelScope.launch {
            combine(_project, _prefs, _viewport) { p, pr, v -> Triple(p, pr, v) }
                .drop(1)
                .collect {
                    if (autosaveEnabled && _project.value != null) scheduleAutosave()
                }
        }
    }

    // ---------------------------------------------------------------------
    // Host bridge used by the custom piano roll view
    // ---------------------------------------------------------------------

    fun project(): Project? = _project.value
    fun prefs(): EditorPrefs = _prefs.value
    fun selection(): Set<Long> = _selection.value
    fun playheadTick(): Double = playback.currentTick()
    fun isPlaying(): Boolean = playback.isPlaying.value
    fun isAudioReady(): Boolean = audio.isReady()

    fun noteById(id: Long): Note? = _project.value?.noteById(id)

    fun selectedNotesSnapshot(): List<Note> = selectedNotes()

    fun requestNoteInspector(id: Long) {
        if (_project.value?.noteById(id) == null) return
        _selection.value = setOf(id)
        _inspectorNoteId.value = id
    }

    fun dismissNoteInspector() {
        _inspectorNoteId.value = null
    }

    fun onViewportSettled(viewport: ViewportState) {
        if (_viewport.value != viewport) _viewport.value = viewport
    }

    fun onHoverInfo(info: HoverInfo?) {
        if (!_prefs.value.hoverToolbar) {
            _hover.value = null
        } else {
            _hover.value = info
        }
    }

    fun previewNote(pitch: Int, velocity: Int) {
        if (!audio.isReady()) return
        val channel = _project.value?.track?.channel ?: 0
        previewJobs[pitch]?.cancel()
        audio.noteOn(pitch, velocity.coerceIn(1, 127), channel)
        previewJobs[pitch] = viewModelScope.launch {
            delay(320)
            audio.noteOff(pitch, channel)
            previewJobs.remove(pitch)
        }
    }

    fun addNoteAt(pitch: Int, startTick: Int, durationTicks: Int): Long? {
        val p = _project.value ?: return null
        val start = startTick.coerceAtLeast(0)
        val dur = durationTicks.coerceAtLeast(1)
        val id = p.nextNoteId
        val note = Note(
            id = id,
            pitch = pitch.coerceIn(0, 127),
            startTick = start,
            durationTicks = dur,
            velocity = _prefs.value.inputVelocity,
            channel = p.track.channel,
        )
        pushUndo()
        val notes = p.notes + note
        applyNotes(notes, p.copy(nextNoteId = id + 1))
        _selection.value = setOf(id)
        previewNote(note.pitch, note.velocity)
        return id
    }

    /**
     * The roll previews drags locally and calls this once when the gesture ends.
     * One undo entry is created for the whole gesture.
     */
    fun commitRollNotes(newNotes: List<Note>) {
        val p = _project.value ?: return
        if (newNotes == p.notes) return
        pushUndo()
        applyNotes(newNotes, p)
    }

    fun setRollSelection(ids: Set<Long>) {
        val available = _project.value?.notes?.mapTo(HashSet()) { it.id } ?: emptySet()
        _selection.value = ids.filterTo(LinkedHashSet()) { it in available }
    }

    fun deleteSelection() {
        val p = _project.value ?: return
        val ids = _selection.value
        if (ids.isEmpty()) {
            postMessage("削除するノートを選択してください")
            return
        }
        pushUndo()
        applyNotes(p.notes.filterNot { it.id in ids }, p)
        _selection.value = emptySet()
    }

    fun deleteNotes(ids: Set<Long>) {
        val p = _project.value ?: return
        if (ids.isEmpty()) return
        pushUndo()
        applyNotes(p.notes.filterNot { it.id in ids }, p)
        _selection.value = _selection.value - ids
    }

    /**
     * Double-tapping a note is a quick delete gesture. It is still a normal
     * undoable edit, so the snackbar points at Undo for accidental taps.
     */
    fun deleteNoteByDoubleTap(id: Long) {
        val p = _project.value ?: return
        if (p.noteById(id) == null) return
        deleteNotes(setOf(id))
        postMessage("ノートを削除しました（元に戻すで復元できます）")
    }

    fun selectAll() {
        _selection.value = _project.value?.notes?.mapTo(LinkedHashSet()) { it.id } ?: emptySet()
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun toggleSelection(id: Long) {
        val current = _selection.value
        _selection.value = if (id in current) current - id else current + id
    }

    fun copySelection() {
        val selected = selectedNotes()
        if (selected.isEmpty()) {
            postMessage("コピーするノートを選択してください")
            return
        }
        clipboardBaseTick = selected.minOf { it.startTick }
        clipboard = selected.map { it.copy(startTick = it.startTick - clipboardBaseTick) }
        postMessage("${clipboard.size} 個のノートをコピーしました")
    }

    fun cutSelection() {
        if (_selection.value.isEmpty()) return
        copySelection()
        deleteSelection()
    }

    fun pasteAtPlayhead() {
        val p = _project.value ?: return
        if (clipboard.isEmpty()) {
            postMessage("コピーしたノートがありません")
            return
        }
        val at = snapIfNeeded(playback.currentTick().toInt().coerceAtLeast(0))
        var nextId = p.nextNoteId
        val pasted = clipboard.map { n ->
            n.copy(
                id = nextId++,
                startTick = (at + n.startTick).coerceAtLeast(0),
                channel = p.track.channel,
            )
        }
        pushUndo()
        applyNotes(p.notes + pasted, p.copy(nextNoteId = nextId))
        _selection.value = pasted.mapTo(LinkedHashSet()) { it.id }
        previewNote(pasted.first().pitch, pasted.first().velocity)
    }

    fun duplicateSelection() {
        val p = _project.value ?: return
        val selected = selectedNotes()
        if (selected.isEmpty()) {
            postMessage("複製するノートを選択してください")
            return
        }
        val step = duplicateStepTicks(p)
        var nextId = p.nextNoteId
        val copies = selected.map { n ->
            n.copy(id = nextId++, startTick = n.startTick + step)
        }
        pushUndo()
        applyNotes(p.notes + copies, p.copy(nextNoteId = nextId))
        _selection.value = copies.mapTo(LinkedHashSet()) { it.id }
        previewNote(copies.first().pitch, copies.first().velocity)
    }

    fun duplicateNote(id: Long) {
        val p = _project.value ?: return
        val note = p.noteById(id) ?: return
        val copy = note.copy(id = p.nextNoteId, startTick = note.startTick + duplicateStepTicks(p))
        pushUndo()
        applyNotes(p.notes + copy, p.copy(nextNoteId = p.nextNoteId + 1))
        _selection.value = setOf(copy.id)
        previewNote(copy.pitch, copy.velocity)
    }

    fun transposeSelection(semitones: Int) {
        val p = _project.value ?: return
        val selected = selectedNotes()
        if (selected.isEmpty()) {
            postMessage("移調するノートを選択してください")
            return
        }
        val ids = selected.mapTo(HashSet()) { it.id }
        var changed = false
        val notes = p.notes.map { n ->
            if (n.id in ids) {
                val pitch = (n.pitch + semitones).coerceIn(0, 127)
                if (pitch != n.pitch) changed = true
                n.copy(pitch = pitch)
            } else n
        }
        if (!changed) return
        pushUndo()
        applyNotes(notes, p)
        selected.firstOrNull()?.let { previewNote((it.pitch + semitones).coerceIn(0, 127), it.velocity) }
    }

    fun adjustSelectionLength(deltaTicks: Int) {
        val p = _project.value ?: return
        val selected = selectedNotes()
        if (selected.isEmpty()) return
        val ids = selected.mapTo(HashSet()) { it.id }
        val notes = p.notes.map { n ->
            if (n.id in ids) n.copy(durationTicks = (n.durationTicks + deltaTicks).coerceAtLeast(1)) else n
        }
        pushUndo()
        applyNotes(notes, p)
    }

    fun setSelectionDuration(durationTicks: Int) {
        val p = _project.value ?: return
        val selected = selectedNotes()
        if (selected.isEmpty()) return
        val ids = selected.mapTo(HashSet()) { it.id }
        val dur = durationTicks.coerceAtLeast(1)
        pushUndo()
        applyNotes(p.notes.map { if (it.id in ids) it.copy(durationTicks = dur) else it }, p)
    }

    fun setSelectionVelocity(velocity: Int) {
        val p = _project.value ?: return
        val selected = selectedNotes()
        if (selected.isEmpty()) return
        val ids = selected.mapTo(HashSet()) { it.id }
        val v = velocity.coerceIn(1, 127)
        pushUndo()
        applyNotes(p.notes.map { if (it.id in ids) it.copy(velocity = v) else it }, p)
        previewNote(selected.first().pitch, v)
    }

    fun updateNote(
        id: Long,
        pitch: Int? = null,
        startTick: Int? = null,
        durationTicks: Int? = null,
        velocity: Int? = null,
    ) {
        val p = _project.value ?: return
        val note = p.noteById(id) ?: return
        val updated = note.copy(
            pitch = (pitch ?: note.pitch).coerceIn(0, 127),
            startTick = (startTick ?: note.startTick).coerceAtLeast(0),
            durationTicks = (durationTicks ?: note.durationTicks).coerceAtLeast(1),
            velocity = (velocity ?: note.velocity).coerceIn(1, 127),
        )
        if (updated == note) return
        pushUndo()
        applyNotes(p.notes.map { if (it.id == id) updated else it }, p)
        previewNote(updated.pitch, updated.velocity)
    }

    fun nudgeNote(noteId: Long, deltaTicks: Int, deltaPitch: Int) {
        val p = _project.value ?: return
        val note = p.noteById(noteId) ?: return
        val moved = note.shifted(deltaTicks, deltaPitch).clampInsideSong()
        pushUndo()
        applyNotes(p.notes.map { if (it.id == noteId) moved else it }, p)
    }

    fun resizeNoteBy(noteId: Long, deltaTicks: Int) {
        val p = _project.value ?: return
        val note = p.noteById(noteId) ?: return
        val resized = note.copy(durationTicks = (note.durationTicks + deltaTicks).coerceAtLeast(1))
        if (resized == note) return
        pushUndo()
        applyNotes(p.notes.map { if (it.id == noteId) resized else it }, p)
    }

    fun quantizeSelection() {
        val ids = _selection.value
        if (ids.isEmpty()) {
            postMessage("クオンタイズするノートを選択してください")
            return
        }
        quantize(ids)
    }

    fun quantizeAll() {
        quantize(_project.value?.notes?.mapTo(HashSet()) { it.id } ?: emptySet())
    }

    private fun quantize(ids: Set<Long>) {
        val p = _project.value ?: return
        if (ids.isEmpty()) return
        val grid = _prefs.value.grid
        if (grid == GridUnit.OFF) {
            postMessage("クオンタイズにはスナップ間隔が必要です")
            return
        }
        val notes = p.notes.map { n ->
            if (n.id in ids) {
                val start = snapTick(n.startTick, grid, p.ppq).coerceAtLeast(0)
                n.copy(startTick = start)
            } else n
        }
        pushUndo()
        applyNotes(notes, p)
        postMessage("${ids.size} 個のノートをクオンタイズしました")
    }

    fun setBpm(bpm: Double) {
        val p = _project.value ?: return
        val value = bpm.coerceIn(20.0, 400.0)
        if (abs(p.bpm - value) < 0.0001) return
        pushUndo()
        _project.value = p.copy(bpm = value)
        playback.setProject(_project.value!!)
    }

    fun setTimeSignature(numerator: Int, denominator: Int) {
        val p = _project.value ?: return
        val sig = runCatching { TimeSignature(numerator, denominator) }.getOrNull() ?: return
        if (p.timeSignature == sig) return
        pushUndo()
        val sigs = p.timeSignatures.toMutableList()
        val idx = sigs.indexOfFirst { it.tick == 0 }
        val point = TimeSignaturePoint(0, sig)
        if (idx >= 0) sigs[idx] = point else sigs.add(0, point)
        val updated = p.copy(
            timeSignature = sig,
            timeSignatures = sigs.sortedBy { it.tick },
        )
        _project.value = updated
        playback.setProject(updated)
    }

    fun toggleLoop() {
        val p = _project.value ?: return
        if (!p.loopEnabled && p.loopEndTick <= p.loopStartTick) {
            val end = p.loopStartTick + p.ppq * 16
            _project.value = p.copy(loopEnabled = true, loopEndTick = end)
        } else {
            _project.value = p.copy(loopEnabled = !p.loopEnabled)
        }
        playback.setProject(_project.value!!)
    }

    fun setLoopRange(startTick: Int, endTick: Int) {
        val p = _project.value ?: return
        val a = min(startTick, endTick).coerceAtLeast(0)
        val b = max(startTick, endTick).coerceAtLeast(a + 1)
        _project.value = p.copy(loopStartTick = a, loopEndTick = b, loopEnabled = true)
        playback.setProject(_project.value!!)
    }

    fun loopFromSelection() {
        val selected = selectedNotes()
        if (selected.isEmpty()) {
            postMessage("ループ範囲にするノートを選択してください")
            return
        }
        val p = _project.value ?: return
        val start = snapIfNeeded(selected.minOf { it.startTick }.coerceAtLeast(0))
        val end = snapIfNeeded(selected.maxOf { it.endTick }.coerceAtLeast(start + 1))
        _project.value = p.copy(loopStartTick = start, loopEndTick = end, loopEnabled = true)
        playback.setProject(_project.value!!)
        postMessage("選択範囲をループに設定しました")
    }

    fun play() {
        val p = _project.value ?: return
        when (val status = audio.status.value) {
            is AudioStatus.Loading -> {
                postMessage("ピアノ音源を読み込み中です")
                return
            }
            is AudioStatus.Failed -> {
                postMessage("音源エラー: ${status.message}")
                return
            }
            is AudioStatus.Ready -> Unit
        }
        playback.play(p)
    }

    fun pause() = playback.pause()

    fun stopPlayback() {
        playback.stop()
    }

    fun togglePlayback() {
        if (playback.isPlaying.value) pause() else play()
    }

    fun seekTo(tick: Double) {
        playback.seek(tick)
    }

    fun setGrid(grid: GridUnit) {
        _prefs.value = _prefs.value.copy(grid = grid)
    }

    fun setInputDuration(duration: GridUnit) {
        _prefs.value = _prefs.value.copy(inputDuration = duration)
    }

    fun cycleInputDuration() {
        val values = GridUnit.entries.filter { it != GridUnit.OFF }
        val idx = values.indexOf(_prefs.value.inputDuration)
        _prefs.value = _prefs.value.copy(inputDuration = values[(idx + 1) % values.size])
    }

    fun cycleGrid() {
        val values = GridUnit.entries
        val idx = values.indexOf(_prefs.value.grid)
        _prefs.value = _prefs.value.copy(grid = values[(idx + 1) % values.size])
    }

    fun setSnapEnabled(on: Boolean) {
        _prefs.value = _prefs.value.copy(snapEnabled = on)
    }

    fun setAutoScroll(on: Boolean) {
        _prefs.value = _prefs.value.copy(autoScroll = on)
    }

    fun setHoverToolbar(on: Boolean) {
        _prefs.value = _prefs.value.copy(hoverToolbar = on)
        if (!on) _hover.value = null
    }

    fun setFingerEditing(on: Boolean) {
        _prefs.value = _prefs.value.copy(fingerEditing = on)
    }

    fun setInputVelocity(velocity: Int) {
        _prefs.value = _prefs.value.copy(inputVelocity = velocity.coerceIn(1, 127))
    }

    fun setShowSustainLane(on: Boolean) {
        _prefs.value = _prefs.value.copy(showSustainLane = on)
    }

    fun toggleFingerEditing() {
        _prefs.value = _prefs.value.copy(fingerEditing = !_prefs.value.fingerEditing)
    }

    fun undo() {
        val p = _project.value ?: return
        val snapshot = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshotOf(p))
        restoreSnapshot(snapshot)
        updateHistoryCounts()
        postMessage("元に戻しました")
    }

    fun redo() {
        val p = _project.value ?: return
        val snapshot = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshotOf(p))
        restoreSnapshot(snapshot)
        updateHistoryCounts()
        postMessage("やり直しました")
    }

    // ---------------------------------------------------------------------
    // Hover toolbar actions
    // ---------------------------------------------------------------------

    fun hoverAddAtCurrent() {
        val h = _hover.value ?: return
        addNoteAt(h.ghostPitch, h.ghostStart, h.ghostDurationTicks)
    }

    fun hoverDuplicate() {
        _hover.value?.noteId?.let { duplicateNote(it) }
    }

    fun hoverDelete() {
        _hover.value?.noteId?.let { deleteNotes(setOf(it)) }
    }

    fun hoverResize(direction: Int) {
        val h = _hover.value ?: return
        val id = h.noteId ?: return
        val p = project() ?: return
        val note = p.noteById(id) ?: return
        val step = _prefs.value.grid.let {
            if (it == GridUnit.OFF) _prefs.value.inputDuration.ticks(p.ppq)
            else it.ticks(p.ppq)
        }
        if (h.zone == HoverZone.NOTE_START) {
            // "Longer" at the start edge moves the start earlier while keeping
            // the end fixed; "shorter" moves it later.
            val newStart = (note.startTick - direction * step)
                .coerceIn(0, note.endTick - 1)
            val updated = note.copy(
                startTick = newStart,
                durationTicks = note.endTick - newStart,
            )
            if (updated == note) return
            pushUndo()
            applyNotes(p.notes.map { if (it.id == id) updated else it }, p)
            previewNote(updated.pitch, updated.velocity)
        } else {
            resizeNoteBy(id, direction * step)
        }
    }

    fun hoverCycleInputDuration() = cycleInputDuration()
    fun hoverCycleGrid() = cycleGrid()

    /** Cycles the input velocity through musically useful levels from the hover bar. */
    fun hoverCycleInputVelocity() {
        val values = listOf(32, 48, 64, 80, 96, 112, 127)
        val current = _prefs.value.inputVelocity
        val next = values.firstOrNull { it > current } ?: values.first()
        setInputVelocity(next)
    }

    // ---------------------------------------------------------------------
    // Tracks and project lifecycle
    // ---------------------------------------------------------------------

    fun selectTrack(index: Int) {
        val p = _project.value ?: return
        if (index !in p.tracks.indices || index == p.activeTrack) {
            _trackPickerVisible.value = false
            return
        }
        undoStack.clear()
        redoStack.clear()
        updateHistoryCounts()
        _selection.value = emptySet()
        val updated = p.copy(activeTrack = index)
        _project.value = updated
        playback.setProject(updated)
        _trackPickerVisible.value = false
        postMessage("${p.tracks[index].name} を編集します")
    }

    fun dismissTrackPicker() {
        _trackPickerVisible.value = false
    }

    fun openTrackPicker() {
        val p = _project.value ?: return
        if (p.tracks.size > 1) _trackPickerVisible.value = true
    }

    fun newProject() {
        playback.stop()
        undoStack.clear()
        redoStack.clear()
        updateHistoryCounts()
        _selection.value = emptySet()
        _hover.value = null
        _trackPickerVisible.value = false
        _project.value = Project.empty()
        _viewport.value = ViewportState()
        _viewportEpoch.value++
        autosaveEnabled = true
        playback.setProject(_project.value!!)
        postMessage("新しいプロジェクトを作成しました")
    }

    fun renameProject(title: String) {
        val p = _project.value ?: return
        val clean = title.trim().take(80).ifBlank { "無題" }
        if (clean == p.title) return
        _project.value = p.copy(title = clean)
    }

    fun suggestedFileName(): String {
        val title = _project.value?.title ?: "piano"
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "piano" }
        return "$safe.mid"
    }

    fun importMidi(uri: Uri) {
        if (_boot.value is BootState.Loading) {
            pendingImport = uri
            return
        }
        if (_busy.value.importing) return
        _busy.value = _busy.value.copy(importing = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = app.contentResolver
                    val meta = queryMeta(resolver, uri)
                    val knownSize = meta.second
                    if (knownSize != null && knownSize > 32L * 1024 * 1024) {
                        throw IOException("MIDIファイルが大きすぎます (最大32MB)")
                    }
                    val bytes = resolver.openInputStream(uri)?.use { readLimited(it, 32 * 1024 * 1024) }
                        ?: throw IOException("ファイルを開けません")
                    val display = meta.first ?: "import.mid"
                    val title = display.substringBeforeLast('.').ifBlank { "読み込んだ曲" }
                    MidiFile.parse(bytes, title)
                }
            }
            result.fold(
                onSuccess = { parsed ->
                    playback.stop()
                    undoStack.clear()
                    redoStack.clear()
                    updateHistoryCounts()
                    _selection.value = emptySet()
                    _hover.value = null
                    val project = parsed.project
                    _project.value = project
                    val notes = project.track.notes
                    val lowest = if (notes.isEmpty()) 36f else {
                        (notes.minOf { it.pitch } - 4).toFloat().coerceIn(0f, 108f)
                    }
                    _viewport.value = ViewportState(startTick = 0.0, lowestPitch = lowest)
                    _viewportEpoch.value++
                    playback.setProject(project)
                    _trackPickerVisible.value = project.tracks.size > 1
                    autosaveEnabled = true
                    val warn = parsed.warnings.take(3).joinToString(" / ")
                    postMessage(
                        if (warn.isBlank()) "MIDIを読み込みました (${project.tracks.size}トラック)"
                        else "MIDIを読み込みました: $warn"
                    )
                },
                onFailure = { t ->
                    postMessage("MIDIを読み込めません: ${t.message ?: t.javaClass.simpleName}")
                }
            )
            _busy.value = _busy.value.copy(importing = false)
        }
    }

    fun exportMidi(uri: Uri) {
        val p = _project.value ?: return
        if (_busy.value.exporting) return
        _busy.value = _busy.value.copy(exporting = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = MidiFile.write(p)
                    app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(bytes)
                        out.flush()
                    } ?: throw IOException("保存先を開けません")
                }
            }
            result.fold(
                onSuccess = { postMessage("MIDIファイルを書き出しました") },
                onFailure = { t -> postMessage("書き出しに失敗しました: ${t.message ?: t.javaClass.simpleName}") }
            )
            _busy.value = _busy.value.copy(exporting = false)
        }
    }

    fun loadExternalSoundFont(uri: Uri) {
        if (_busy.value.soundFont) return
        _busy.value = _busy.value.copy(soundFont = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = app.contentResolver.openInputStream(uri)?.use {
                        readLimited(it, 96 * 1024 * 1024)
                    } ?: throw IOException("ファイルを開けません")
                    bytes
                }
            }
            result.fold(
                onSuccess = { bytes ->
                    audio.loadExternal(bytes, "SoundFont")
                    postMessage("SoundFontを読み込み中です")
                },
                onFailure = { t -> postMessage("SoundFontを読み込めません: ${t.message}") }
            )
            _busy.value = _busy.value.copy(soundFont = false)
        }
    }

    fun reloadBundledSoundFont() {
        audio.loadBundled()
        postMessage("内蔵ピアノ音源を読み込み直しています")
    }

    // ---------------------------------------------------------------------
    // Persistence / lifecycle
    // ---------------------------------------------------------------------

    fun saveNow() {
        autosaveJob?.cancel()
        viewModelScope.launch { saveInternal() }
    }

    fun onBackground() {
        playback.pause()
        audio.allNotesOff()
        saveNow()
    }

    private fun restore(data: AutosaveData) {
        _prefs.value = data.prefs
        _viewport.value = data.viewport
        _viewportEpoch.value++
        _project.value = data.project
        _selection.value = emptySet()
        playback.setProject(data.project)
        _autosave.value = AutosaveState.Saved(
            if (data.savedAtMillis > 0) data.savedAtMillis else System.currentTimeMillis()
        )
        _boot.value = BootState.Ready(true)
        autosaveEnabled = true
        postMessage("前回の続きを復元しました")
        runPendingImport()
    }

    private fun runPendingImport() {
        val uri = pendingImport ?: return
        pendingImport = null
        importMidi(uri)
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(650)
            saveInternal()
        }
    }

    private suspend fun saveInternal() {
        if (!autosaveEnabled) return
        val project = _project.value ?: return
        val snapshot = AutosaveData(
            project = project,
            viewport = _viewport.value,
            prefs = _prefs.value,
            savedAtMillis = System.currentTimeMillis(),
        )
        _autosave.value = AutosaveState.Saving
        val result = withContext(Dispatchers.IO) { ProjectStore.save(app, snapshot) }
        _autosave.value = result.fold(
            onSuccess = { AutosaveState.Saved(snapshot.savedAtMillis) },
            onFailure = { AutosaveState.Failed(it.message ?: "自動保存に失敗") }
        )
    }

    override fun onCleared() {
        autosaveJob?.cancel()
        previewJobs.values.forEach { it.cancel() }
        playback.shutdown()
        audio.shutdown()
        super.onCleared()
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun applyNotes(newNotes: List<Note>, base: Project) {
        val maxId = newNotes.maxOfOrNull { it.id } ?: 0L
        val updated = base.withActiveNotes(newNotes).copy(
            nextNoteId = maxOf(base.nextNoteId, maxId + 1)
        )
        _project.value = updated
        val ids = updated.notes.mapTo(HashSet()) { it.id }
        _selection.value = _selection.value.filterTo(LinkedHashSet()) { it in ids }
        playback.setProject(updated)
    }

    private fun selectedNotes(): List<Note> {
        val p = _project.value ?: return emptyList()
        val ids = _selection.value
        return p.notes.filter { it.id in ids }
    }

    private fun pushUndo() {
        val p = _project.value ?: return
        undoStack.addLast(snapshotOf(p))
        while (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        updateHistoryCounts()
    }

    private fun snapshotOf(p: Project): EditSnapshot = EditSnapshot(
        notes = p.notes,
        bpm = p.bpm,
        timeSignature = p.timeSignature,
        timeSignatures = p.timeSignatures,
    )

    private fun restoreSnapshot(snapshot: EditSnapshot) {
        val p = _project.value ?: return
        val updated = p.copy(
            bpm = snapshot.bpm,
            timeSignature = snapshot.timeSignature,
            timeSignatures = snapshot.timeSignatures,
        ).withActiveNotes(snapshot.notes)
        _project.value = updated
        val ids = snapshot.notes.mapTo(HashSet()) { it.id }
        _selection.value = _selection.value.filterTo(LinkedHashSet()) { it in ids }
        playback.setProject(updated)
    }

    private fun updateHistoryCounts() {
        _undoCount.value = undoStack.size
        _redoCount.value = redoStack.size
    }

    private fun duplicateStepTicks(p: Project): Int {
        val grid = _prefs.value.grid
        return if (grid != GridUnit.OFF) grid.ticks(p.ppq)
        else _prefs.value.inputDuration.ticks(p.ppq)
    }

    private fun snapIfNeeded(tick: Int): Int {
        val p = _project.value ?: return tick
        return if (_prefs.value.snapEnabled) snapTick(tick, _prefs.value.grid, p.ppq) else tick
    }

    private fun postMessage(text: String) {
        messages.trySend(text)
    }

    private fun queryMeta(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        return name to size
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("ファイルが大きすぎます")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    fun autosaveLabel(): String = when (val s = _autosave.value) {
        AutosaveState.Idle -> "未保存"
        AutosaveState.Saving -> "自動保存中…"
        is AutosaveState.Saved -> {
            val f = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)
            "自動保存 ${f.format(Date(s.atMillis))}"
        }
        is AutosaveState.Failed -> "自動保存エラー"
    }

    fun trackLabel(index: Int): String {
        val p = _project.value ?: return "Track ${index + 1}"
        val t = p.tracks.getOrNull(index) ?: return "Track ${index + 1}"
        return "${index + 1}. ${t.name} (${t.noteCount})"
    }

    fun pitchLabel(pitch: Int): String = noteName(pitch)
}
