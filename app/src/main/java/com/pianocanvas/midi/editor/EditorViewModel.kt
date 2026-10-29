package com.pianocanvas.midi.editor

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pianocanvas.midi.audio.AudioEngine
import com.pianocanvas.midi.audio.AudioStatus
import com.pianocanvas.midi.audio.PlaybackEngine
import com.pianocanvas.midi.audio.SoundFontLoader
import com.pianocanvas.midi.data.AutosaveData
import com.pianocanvas.midi.data.EditorPrefs
import com.pianocanvas.midi.data.ProjectRepository
import com.pianocanvas.midi.data.ProjectSummary
import com.pianocanvas.midi.data.ViewportState
import com.pianocanvas.midi.data.WorkspaceState
import com.pianocanvas.midi.midi.MidiDeviceOption
import com.pianocanvas.midi.midi.MidiFile
import com.pianocanvas.midi.midi.MidiInputEvent
import com.pianocanvas.midi.midi.MidiInputManager
import com.pianocanvas.midi.model.GridUnit
import com.pianocanvas.midi.model.MidiMeta
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Only guards against runaway copies; the real ceiling for a bank is its
// decoded sample size, which SoundFontLoader derives from the app heap.
private const val MAX_SOUNDFONT_FILE_BYTES = 1L * 1024 * 1024 * 1024

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

/** One entry in the tab bar. */
data class TabInfo(
    val id: String,
    val title: String,
    val active: Boolean,
    val noteCount: Int,
)

/** USB / BLE MIDI keyboard status for the input dialog and top bar. */
data class MidiUiState(
    val supported: Boolean = true,
    val devices: List<MidiDeviceOption> = emptyList(),
    val connectedName: String? = null,
    val recording: Boolean = false,
) {
    val connected: Boolean get() = connectedName != null
}

/** Commands that need a host Activity (SAF pickers, share sheet). */
sealed interface UiCommand {
    data object ImportMidi : UiCommand
    data object ExportMidi : UiCommand
    data class Share(val uri: Uri, val mimeType: String, val title: String) : UiCommand
}

/** Commands handled inside the piano-roll composable. */
sealed interface ViewCommand {
    data object ZoomIn : ViewCommand
    data object ZoomOut : ViewCommand
    data object Fit : ViewCommand
    data class ScrollBars(val delta: Int) : ViewCommand
}

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
    val soundFontCustom: Boolean,
    val soundFontFileName: String?,
    val tabs: List<TabInfo>,
    val midi: MidiUiState,
    val projects: List<ProjectSummary>,
    val projectsLoading: Boolean,
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
    private val _soundFontCustom = MutableStateFlow(false)
    private val _soundFontFileName = MutableStateFlow<String?>(null)
    private val _inspectorNoteId = MutableStateFlow<Long?>(null)
    private val _tabs = MutableStateFlow<List<TabState>>(emptyList())
    private val _activeTabIndex = MutableStateFlow(0)
    private val _midiRecording = MutableStateFlow(false)
    private val _projectList = MutableStateFlow<List<ProjectSummary>>(emptyList())
    private val _projectsLoading = MutableStateFlow(false)
    private val _projectBrowserVisible = MutableStateFlow(false)
    private val messages = Channel<String>(Channel.BUFFERED)

    private val _uiCommands = MutableSharedFlow<UiCommand>(extraBufferCapacity = 8)
    private val _viewCommands = MutableSharedFlow<ViewCommand>(extraBufferCapacity = 16)

    val messageFlow = messages.receiveAsFlow()
    val hover: StateFlow<HoverInfo?> = _hover.asStateFlow()
    val inspectorNoteId: StateFlow<Long?> = _inspectorNoteId.asStateFlow()
    val audioStatus: StateFlow<AudioStatus> = audio.status
    val uiCommands: SharedFlow<UiCommand> = _uiCommands.asSharedFlow()
    val viewCommands: SharedFlow<ViewCommand> = _viewCommands.asSharedFlow()
    val projectBrowserVisible: StateFlow<Boolean> = _projectBrowserVisible.asStateFlow()

    private val midiInput = MidiInputManager(application)

    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()
    private var clipboard: List<Note> = emptyList()
    private var clipboardBaseTick = 0
    private var autosaveJob: Job? = null
    private var autosaveEnabled = false
    private var pendingImport: Uri? = null
    private val previewJobs = HashMap<Int, Job>()

    /** Pitches currently held down by an external keyboard while recording. */
    private val midiPendingNotes = HashMap<Int, MidiNoteStart>()
    private var midiRecordUndoPushed = false

    private data class MidiNoteStart(val startTick: Int, val velocity: Int)

    private data class EditSnapshot(
        val notes: List<Note>,
        val bpm: Double,
        val timeSignature: TimeSignature,
        val timeSignatures: List<TimeSignaturePoint>,
        val meta: MidiMeta,
    )

    /** A project open in a tab, together with its own editor history. */
    private data class TabState(
        val id: String,
        val project: Project,
        val selection: Set<Long>,
        val viewport: ViewportState,
        val undo: List<EditSnapshot>,
        val redo: List<EditSnapshot>,
        val clipboard: List<Note>,
        val clipboardBaseTick: Int,
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
        val soundFontCustom: Boolean,
        val soundFontFileName: String?,
    )

    private data class TabsState(
        val tabs: List<TabInfo>,
        val projects: List<ProjectSummary>,
        val loading: Boolean,
    )

    private data class MidiState(
        val supported: Boolean,
        val devices: List<MidiDeviceOption>,
        val connectedName: String?,
        val recording: Boolean,
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
        audio.status,
        playback.isPlaying,
        _trackPickerVisible,
        _soundFontCustom,
        _soundFontFileName,
    ) { audioStatus, playing, picker, custom, fileName ->
        RuntimeState(audioStatus, playing, picker, custom, fileName)
    }

    // The active tab's live project overrides the stale snapshot so titles and
    // note counts in the tab bar update as the user edits.
    private val tabsFlow = combine(
        _tabs, _activeTabIndex, _project, _projectList, _projectsLoading
    ) { tabs, active, live, projects, loading ->
        TabsState(
            tabs = tabs.mapIndexed { i, tab ->
                val isActive = i == active
                TabInfo(
                    id = tab.id,
                    title = if (isActive && live != null) live.title else tab.project.title,
                    active = isActive,
                    noteCount = if (isActive && live != null) live.notes.size else tab.project.notes.size,
                )
            },
            projects = projects,
            loading = loading,
        )
    }

    private val midiFlow = combine(
        midiInput.devices, midiInput.connectedDeviceName, _midiRecording
    ) { devices, connected, recording ->
        MidiState(midiInput.supported, devices, connected, recording)
    }

    val uiState: StateFlow<EditorUiState> =
        combine(coreFlow, metaFlow, runtimeFlow, tabsFlow, midiFlow) { c, m, r, t, midi ->
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
                soundFontCustom = r.soundFontCustom,
                soundFontFileName = r.soundFontFileName,
                tabs = t.tabs,
                midi = MidiUiState(midi.supported, midi.devices, midi.connectedName, midi.recording),
                projects = t.projects,
                projectsLoading = t.loading,
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
                soundFontCustom = false,
                soundFontFileName = null,
                tabs = emptyList(),
                midi = MidiUiState(),
                projects = emptyList(),
                projectsLoading = false,
            )
        )

    init {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { loadStoredSoundFont() }
            if (stored != null) {
                _soundFontCustom.value = true
                _soundFontFileName.value = stored.second
                audio.loadExternal(stored.first)
            } else {
                audio.loadBundled()
            }
        }
        midiInput.start()
        viewModelScope.launch {
            midiInput.events.collect { handleMidiInput(it) }
        }
        viewModelScope.launch { restoreWorkspace() }
        viewModelScope.launch {
            combine(_project, _prefs, _viewport) { p, pr, v -> Triple(p, pr, v) }
                .drop(1)
                .collect {
                    if (autosaveEnabled && _project.value != null) scheduleAutosave()
                }
        }
        viewModelScope.launch {
            combine(_prefs, _tabs, _activeTabIndex) { pr, tabs, active -> Triple(pr, tabs, active) }
                .drop(1)
                .collect { persistWorkspace() }
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
        _hover.value = info
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
        val id = ProjectRepository.newId()
        val project = Project.empty()
        openTab(TabState(id, project, emptySet(), ViewportState(), emptyList(), emptyList(), emptyList(), 0))
        saveNow()
        postMessage("新しいプロジェクトを作成しました")
    }

    fun renameProject(title: String) {
        val p = _project.value ?: return
        val clean = title.trim().take(80).ifBlank { "無題" }
        if (clean == p.title) return
        _project.value = p.copy(title = clean)
        scheduleAutosave()
    }

    // ---------------------------------------------------------------------
    // Tabs
    // ---------------------------------------------------------------------

    /** Snapshot of the currently live tab. */
    private fun captureActiveTab(): TabState? {
        val project = _project.value ?: return null
        val id = _tabs.value.getOrNull(_activeTabIndex.value)?.id ?: return null
        return TabState(
            id = id,
            project = project,
            selection = _selection.value,
            viewport = _viewport.value,
            undo = undoStack.toList(),
            redo = redoStack.toList(),
            clipboard = clipboard,
            clipboardBaseTick = clipboardBaseTick,
        )
    }

    /** Stores the live editor state back into the tab list. */
    private fun commitActiveTab() {
        val index = _activeTabIndex.value
        val snapshot = captureActiveTab() ?: return
        val tabs = _tabs.value.toMutableList()
        if (index in tabs.indices) {
            tabs[index] = snapshot
            _tabs.value = tabs
        }
    }

    private fun openTab(tab: TabState) {
        commitActiveTab()
        val tabs = _tabs.value + tab
        _tabs.value = tabs
        activateTab(tabs.lastIndex)
    }

    fun switchTab(index: Int) {
        if (index == _activeTabIndex.value) return
        commitActiveTab()
        val outgoing = _tabs.value.getOrNull(_activeTabIndex.value)
        if (outgoing != null) persistTab(outgoing)
        activateTab(index)
    }

    fun closeTab(index: Int) {
        val tabs = _tabs.value.toMutableList()
        if (index !in tabs.indices) return
        val closingActive = index == _activeTabIndex.value
        // Capture the active tab first so a non-active close cannot persist a
        // stale copy over edits that autosave has already written.
        val liveSnapshot = captureActiveTab()
        val closing = if (closingActive && liveSnapshot != null) liveSnapshot else tabs[index]
        tabs.removeAt(index)
        _tabs.value = tabs
        viewModelScope.launch { persistTabNow(closing) }

        if (tabs.isEmpty()) {
            playback.stop()
            audio.allNotesOff()
            undoStack.clear()
            redoStack.clear()
            updateHistoryCounts()
            clipboard = emptyList()
            _selection.value = emptySet()
            _hover.value = null
            _inspectorNoteId.value = null
            _project.value = null
            _trackPickerVisible.value = false
            _activeTabIndex.value = 0
            return
        }
        if (closingActive) {
            activateTab(index.coerceAtMost(tabs.lastIndex))
        } else {
            val adjusted = if (_activeTabIndex.value > index) _activeTabIndex.value - 1 else _activeTabIndex.value
            _activeTabIndex.value = adjusted
        }
    }

    fun closeActiveTab() = closeTab(_activeTabIndex.value)

    private fun activateTab(index: Int) {
        val tabs = _tabs.value
        if (index !in tabs.indices) return
        val tab = tabs[index]
        playback.stop()
        audio.allNotesOff()
        _hover.value = null
        _inspectorNoteId.value = null
        _activeTabIndex.value = index
        _project.value = tab.project
        _selection.value = tab.selection
        _viewport.value = tab.viewport
        _viewportEpoch.value++
        undoStack.clear()
        undoStack.addAll(tab.undo)
        redoStack.clear()
        redoStack.addAll(tab.redo)
        clipboard = tab.clipboard
        clipboardBaseTick = tab.clipboardBaseTick
        updateHistoryCounts()
        playback.setProject(tab.project)
        autosaveEnabled = true
        _autosave.value = AutosaveState.Idle
    }

    // ---------------------------------------------------------------------
    // Project browser
    // ---------------------------------------------------------------------

    fun refreshProjects() {
        _projectsLoading.value = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { ProjectRepository.list(app) }
            _projectList.value = list
            _projectsLoading.value = false
        }
    }

    fun openProject(id: String) {
        val existing = _tabs.value.indexOfFirst { it.id == id }
        if (existing >= 0) {
            switchTab(existing)
            return
        }
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) { ProjectRepository.load(app, id) }
                .getOrNull() ?: run {
                    postMessage("プロジェクトを開けませんでした")
                    return@launch
                }
            openTab(
                TabState(
                    id = id,
                    project = data.project,
                    selection = emptySet(),
                    viewport = data.viewport,
                    undo = emptyList(),
                    redo = emptyList(),
                    clipboard = emptyList(),
                    clipboardBaseTick = 0,
                )
            )
            _trackPickerVisible.value = data.project.tracks.size > 1
            postMessage("「${data.project.title}」を開きました")
        }
    }

    fun renameStoredProject(id: String, title: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { ProjectRepository.rename(app, id, title) }
            result.fold(
                onSuccess = {
                    val clean = title.trim().take(80).ifBlank { "無題" }
                    if (_tabs.value.getOrNull(_activeTabIndex.value)?.id == id) {
                        _project.value = _project.value?.copy(title = clean)
                    }
                    refreshProjects()
                    postMessage("名前を変更しました")
                },
                onFailure = { postMessage("名前を変更できませんでした") },
            )
        }
    }

    fun deleteStoredProject(id: String) {
        val openIndex = _tabs.value.indexOfFirst { it.id == id }
        if (openIndex >= 0) closeTab(openIndex)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ProjectRepository.delete(app, id) }
            refreshProjects()
            postMessage("プロジェクトを削除しました")
        }
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
                    val project = parsed.project
                    val notes = project.track.notes
                    val lowest = if (notes.isEmpty()) 36f else {
                        (notes.minOf { it.pitch } - 4).toFloat().coerceIn(0f, 108f)
                    }
                    openTab(
                        TabState(
                            id = ProjectRepository.newId(),
                            project = project,
                            selection = emptySet(),
                            viewport = ViewportState(startTick = 0.0, lowestPitch = lowest),
                            undo = emptyList(),
                            redo = emptyList(),
                            clipboard = emptyList(),
                            clipboardBaseTick = 0,
                        )
                    )
                    _trackPickerVisible.value = project.tracks.size > 1
                    saveNow()
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
                    val (name, _) = queryMeta(app.contentResolver, uri)
                    val displayName = name?.takeIf { it.isNotBlank() } ?: "SoundFont.sf2"
                    val stream = app.contentResolver.openInputStream(uri)
                        ?: throw IOException("ファイルを開けません")
                    // Streamed to disk and validated there: the bank does not
                    // have to fit in heap just to be installed.
                    stream.use { storeSoundFont(it, displayName) }
                    displayName
                }
            }
            result.fold(
                onSuccess = { name ->
                    _soundFontCustom.value = true
                    _soundFontFileName.value = name
                    audio.loadExternal(soundFontFile())
                    postMessage("SoundFont「$name」を読み込み中です")
                },
                onFailure = { t ->
                    postMessage("SoundFontを読み込めません: ${t.message ?: t.javaClass.simpleName}")
                }
            )
            _busy.value = _busy.value.copy(soundFont = false)
        }
    }

    fun reloadBundledSoundFont() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { soundFontDir().deleteRecursively() }
            _soundFontCustom.value = false
            _soundFontFileName.value = null
            audio.loadBundled()
            postMessage("内蔵ピアノ音源に戻しました")
        }
    }

    // ---------------------------------------------------------------------
    // MIDI meta
    // ---------------------------------------------------------------------

    fun setMidiMeta(meta: MidiMeta) {
        val p = _project.value ?: return
        if (p.meta == meta) return
        pushUndo()
        _project.value = p.copy(meta = meta)
    }

    // ---------------------------------------------------------------------
    // External MIDI keyboard (USB / BLE)
    // ---------------------------------------------------------------------

    fun selectMidiDevice(id: Int?) = midiInput.connect(id)

    fun refreshMidiDevices() = midiInput.refreshDevices()

    fun toggleMidiRecording() {
        val on = !_midiRecording.value
        if (on) {
            midiRecordUndoPushed = false
            midiPendingNotes.clear()
        } else {
            flushPendingMidiNotes()
            audio.setSustain(false)
        }
        _midiRecording.value = on
        postMessage(if (on) "MIDI入力を録音します" else "MIDI入力の録音を停止しました")
    }

    private fun handleMidiInput(event: MidiInputEvent) {
        when (event) {
            is MidiInputEvent.NoteOn -> onHardwareNoteOn(event.pitch, event.velocity)
            is MidiInputEvent.NoteOff -> onHardwareNoteOff(event.pitch)
            is MidiInputEvent.Sustain -> audio.setSustain(event.on)
            is MidiInputEvent.ControlChange -> Unit
            is MidiInputEvent.PitchBend -> Unit
        }
    }

    private fun trackChannel(): Int = _project.value?.track?.channel ?: 0

    /**
     * Live-auditions the incoming note and, while recording, remembers where it
     * started so the matching note-off can be turned into an edit.
     */
    private fun onHardwareNoteOn(pitch: Int, velocity: Int) {
        if (audio.isReady()) audio.noteOn(pitch, velocity.coerceIn(1, 127), trackChannel())
        if (!_midiRecording.value) return
        if (midiPendingNotes.size < 128) {
            midiPendingNotes[pitch] = MidiNoteStart(
                startTick = playheadTick().toInt().coerceAtLeast(0),
                velocity = velocity,
            )
        }
    }

    private fun onHardwareNoteOff(pitch: Int) {
        if (audio.isReady()) audio.noteOff(pitch, trackChannel())
        if (!_midiRecording.value) return
        val pending = midiPendingNotes.remove(pitch) ?: return
        commitRecordedNote(pitch, pending)
    }

    private fun commitRecordedNote(pitch: Int, start: MidiNoteStart) {
        val p = _project.value ?: return
        val snappedStart = snapIfNeeded(start.startTick).coerceAtLeast(0)
        val rawDuration = playheadTick().toInt() - snappedStart
        val duration = if (rawDuration >= 8) rawDuration else _prefs.value.inputDuration.ticks(p.ppq)
        val id = p.nextNoteId
        val note = Note(
            id = id,
            pitch = pitch.coerceIn(0, 127),
            startTick = snappedStart,
            durationTicks = duration.coerceAtLeast(1),
            velocity = start.velocity.coerceIn(1, 127),
            channel = p.track.channel,
        )
        if (!midiRecordUndoPushed) {
            pushUndo()
            midiRecordUndoPushed = true
        }
        applyNotes(p.notes + note, p.copy(nextNoteId = id + 1))
        _selection.value = setOf(id)
    }

    /** Closes notes that never received a note-off, e.g. when disarming. */
    private fun flushPendingMidiNotes() {
        val pending = midiPendingNotes.toList()
        midiPendingNotes.clear()
        pending.forEach { (pitch, start) -> commitRecordedNote(pitch, start) }
    }

    // ---------------------------------------------------------------------
    // Share
    // ---------------------------------------------------------------------

    fun shareMidi() {
        val p = _project.value ?: return
        if (_busy.value.exporting) return
        _busy.value = _busy.value.copy(exporting = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = MidiFile.write(p)
                    val dir = File(app.cacheDir, "shared").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val name = suggestedFileName()
                    val file = File(dir, name)
                    file.writeBytes(bytes)
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                    uri to name
                }
            }
            result.fold(
                onSuccess = { (uri, name) ->
                    _uiCommands.tryEmit(UiCommand.Share(uri, "audio/midi", name))
                },
                onFailure = { t -> postMessage("共有に失敗しました: ${t.message ?: t.javaClass.simpleName}") },
            )
            _busy.value = _busy.value.copy(exporting = false)
        }
    }

    fun requestImport() {
        _uiCommands.tryEmit(UiCommand.ImportMidi)
    }

    fun requestExport() {
        _uiCommands.tryEmit(UiCommand.ExportMidi)
    }

    // ---------------------------------------------------------------------
    // Project browser visibility
    // ---------------------------------------------------------------------

    fun openProjectBrowser() {
        refreshProjects()
        _projectBrowserVisible.value = true
    }

    fun dismissProjectBrowser() {
        _projectBrowserVisible.value = false
    }

    // ---------------------------------------------------------------------
    // External keyboard shortcuts
    // ---------------------------------------------------------------------

    /**
     * Handles a physical keyboard event. Returns true when the shortcut was
     * consumed. Plain typing is never intercepted because a focused text field
     * consumes its keys before the Activity sees them.
     */
    fun onShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val shift = event.isShiftPressed
        if (ctrl) {
            return when (keyCode) {
                KeyEvent.KEYCODE_Z -> { if (shift) redo() else undo(); true }
                KeyEvent.KEYCODE_Y -> { redo(); true }
                KeyEvent.KEYCODE_C -> { copySelection(); true }
                KeyEvent.KEYCODE_X -> { cutSelection(); true }
                KeyEvent.KEYCODE_V -> { pasteAtPlayhead(); true }
                KeyEvent.KEYCODE_A -> { selectAll(); true }
                KeyEvent.KEYCODE_D -> { duplicateSelection(); true }
                KeyEvent.KEYCODE_S -> { if (shift) shareMidi() else saveNow(); true }
                KeyEvent.KEYCODE_N -> { newProject(); true }
                KeyEvent.KEYCODE_O -> { requestImport(); true }
                KeyEvent.KEYCODE_E -> { requestExport(); true }
                KeyEvent.KEYCODE_P -> { openProjectBrowser(); true }
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> {
                    _viewCommands.tryEmit(ViewCommand.ZoomIn); true
                }
                KeyEvent.KEYCODE_MINUS -> { _viewCommands.tryEmit(ViewCommand.ZoomOut); true }
                KeyEvent.KEYCODE_0 -> { _viewCommands.tryEmit(ViewCommand.Fit); true }
                KeyEvent.KEYCODE_TAB -> { cycleTab(if (shift) -1 else 1); true }
                KeyEvent.KEYCODE_PAGE_DOWN -> { cycleTab(1); true }
                KeyEvent.KEYCODE_PAGE_UP -> { cycleTab(-1); true }
                else -> false
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> {
                if (event.repeatCount == 0) togglePlayback()
                true
            }
            KeyEvent.KEYCODE_R -> { toggleMidiRecording(); true }
            KeyEvent.KEYCODE_L -> { toggleLoop(); true }
            KeyEvent.KEYCODE_ESCAPE -> { escapeAction(); true }
            KeyEvent.KEYCODE_HOME -> { seekTo(0.0); true }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL ->
                _selection.value.isNotEmpty().also { if (it) deleteSelection() }
            KeyEvent.KEYCODE_DPAD_LEFT -> nudgeSelection(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> nudgeSelection(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> nudgeSelection(0, if (shift) 12 else 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> nudgeSelection(0, if (shift) -12 else -1)
            else -> false
        }
    }

    fun cycleTab(delta: Int) {
        val size = _tabs.value.size
        if (size <= 1) return
        val next = ((_activeTabIndex.value + delta) % size + size) % size
        switchTab(next)
    }

    private fun escapeAction() {
        if (playback.isPlaying.value) {
            stopPlayback()
        } else if (_trackPickerVisible.value) {
            _trackPickerVisible.value = false
        } else if (_inspectorNoteId.value != null) {
            _inspectorNoteId.value = null
        } else if (_selection.value.isNotEmpty()) {
            clearSelection()
        }
    }

    private fun nudgeSelection(barDirection: Int, semitones: Int): Boolean {
        val p = _project.value ?: return false
        val ids = _selection.value
        if (ids.isEmpty()) return false
        if (semitones != 0) {
            transposeSelection(semitones)
            return true
        }
        val step = duplicateStepTicks(p)
        val moved = p.notes.map { n ->
            if (n.id in ids) n.shifted(barDirection * step, 0).clampInsideSong() else n
        }
        if (moved == p.notes) return true
        pushUndo()
        applyNotes(moved, p)
        return true
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
        midiInput.sendAllNotesOff()
        audio.allNotesOff()
        saveNow()
    }

    /**
     * Reopens the workspace that was open last time. On first launch (or after
     * an upgrade) the legacy single-session autosave is migrated into a project.
     */
    private suspend fun restoreWorkspace() {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val migrated = ProjectRepository.migrateLegacy(app)
                val workspace = ProjectRepository.loadWorkspace(app)
                var ids = workspace.openIds.filter { ProjectRepository.exists(app, it) }
                if (ids.isEmpty() && migrated != null) ids = listOf(migrated)
                val tabs = ids.mapNotNull { id ->
                    ProjectRepository.load(app, id).getOrNull()?.let { data ->
                        TabState(
                            id = id,
                            project = data.project,
                            selection = emptySet(),
                            viewport = data.viewport,
                            undo = emptyList(),
                            redo = emptyList(),
                            clipboard = emptyList(),
                            clipboardBaseTick = 0,
                        )
                    }
                }
                Triple(tabs, workspace.prefs, workspace.activeIndex)
            }
        }
        result.fold(
            onSuccess = { (tabs, prefs, active) ->
                _prefs.value = prefs
                _tabs.value = tabs
                _boot.value = BootState.Ready(tabs.isNotEmpty())
                autosaveEnabled = true
                if (tabs.isNotEmpty()) {
                    activateTab(active.coerceIn(0, tabs.size - 1))
                    _autosave.value = AutosaveState.Saved(System.currentTimeMillis())
                    postMessage("前回のプロジェクトを復元しました")
                }
                runPendingImport()
            },
            onFailure = { t ->
                _boot.value = BootState.Ready(false)
                autosaveEnabled = true
                postMessage("ワークスペースを復元できませんでした: ${t.message ?: t.javaClass.simpleName}")
                runPendingImport()
            },
        )
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

    /** Fire-and-forget save of a specific (possibly inactive) tab. */
    private fun persistTab(tab: TabState) {
        viewModelScope.launch { persistTabNow(tab) }
    }

    private suspend fun persistTabNow(tab: TabState) {
        val snapshot = AutosaveData(
            project = tab.project,
            viewport = tab.viewport,
            prefs = _prefs.value,
            savedAtMillis = System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) { ProjectRepository.save(app, tab.id, snapshot) }
    }

    private suspend fun saveInternal() {
        if (!autosaveEnabled) return
        val project = _project.value ?: return
        val id = _tabs.value.getOrNull(_activeTabIndex.value)?.id ?: return
        val snapshot = AutosaveData(
            project = project,
            viewport = _viewport.value,
            prefs = _prefs.value,
            savedAtMillis = System.currentTimeMillis(),
        )
        _autosave.value = AutosaveState.Saving
        val result = withContext(Dispatchers.IO) { ProjectRepository.save(app, id, snapshot) }
        _autosave.value = result.fold(
            onSuccess = { AutosaveState.Saved(snapshot.savedAtMillis) },
            onFailure = { AutosaveState.Failed(it.message ?: "自動保存に失敗") }
        )
        persistWorkspace()
    }

    private fun persistWorkspace() {
        val state = WorkspaceState(
            openIds = _tabs.value.map { it.id },
            activeIndex = _activeTabIndex.value,
            prefs = _prefs.value,
        )
        viewModelScope.launch { withContext(Dispatchers.IO) { ProjectRepository.saveWorkspace(app, state) } }
    }

    override fun onCleared() {
        autosaveJob?.cancel()
        previewJobs.values.forEach { it.cancel() }
        midiInput.shutdown()
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
        meta = p.meta,
    )

    private fun restoreSnapshot(snapshot: EditSnapshot) {
        val p = _project.value ?: return
        val updated = p.copy(
            bpm = snapshot.bpm,
            timeSignature = snapshot.timeSignature,
            timeSignatures = snapshot.timeSignatures,
            meta = snapshot.meta,
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

    // ---------------------------------------------------------------------
    // SoundFont storage
    // ---------------------------------------------------------------------

    private fun soundFontDir(): File = File(app.filesDir, "soundfonts").apply { mkdirs() }

    private fun soundFontFile(): File = File(soundFontDir(), "custom.sf2")

    private fun soundFontNameFile(): File = File(soundFontDir(), "custom.name")

    /**
     * Streams the chosen SF2 into app-private storage so it survives restarts.
     * The original file on the device is never touched.
     *
     * The copy is written to `custom.tmp`, parsed there and only then swapped
     * in, so a broken or oversized bank leaves the working font untouched and
     * a large file never has to fit in memory twice.
     */
    private fun storeSoundFont(input: InputStream, displayName: String): File {
        val dir = soundFontDir()
        val temp = File(dir, "custom.tmp")
        try {
            temp.outputStream().use { out ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_SOUNDFONT_FILE_BYTES) {
                        throw IOException("ファイルが大きすぎます")
                    }
                    out.write(buffer, 0, n)
                }
            }
            if (temp.length() == 0L) throw IOException("ファイルが空です")
            // Validate the copy before replacing a working font.
            SoundFontLoader.load(temp)
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
        val target = File(dir, "custom.sf2")
        if (target.exists() && !target.delete()) {
            temp.delete()
            throw IOException("既存のSoundFontを置き換えられません")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        File(dir, "custom.name").writeText(displayName)
        return target
    }

    /** Returns the stored font only when it still parses, otherwise cleans it up. */
    private fun loadStoredSoundFont(): Pair<File, String>? {
        val file = soundFontFile()
        if (!file.isFile || file.length() == 0L) return null
        if (runCatching { SoundFontLoader.load(file) }.isFailure) {
            soundFontDir().deleteRecursively()
            return null
        }
        val name = runCatching { soundFontNameFile().readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "SoundFont.sf2"
        return file to name
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
