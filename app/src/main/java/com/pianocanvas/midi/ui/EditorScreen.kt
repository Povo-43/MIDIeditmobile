package com.pianocanvas.midi.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianocanvas.midi.audio.AudioStatus
import com.pianocanvas.midi.editor.AutosaveState
import com.pianocanvas.midi.editor.BootState
import com.pianocanvas.midi.editor.EditorUiState
import com.pianocanvas.midi.editor.EditorViewModel
import com.pianocanvas.midi.editor.HoverInfo
import com.pianocanvas.midi.editor.MidiUiState
import com.pianocanvas.midi.editor.RollHost
import com.pianocanvas.midi.editor.TabInfo
import com.pianocanvas.midi.editor.UiCommand
import com.pianocanvas.midi.editor.ViewCommand
import com.pianocanvas.midi.model.GridUnit
import com.pianocanvas.midi.model.MeterMap
import com.pianocanvas.midi.model.Project
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MidiEditMobileApp(vm: EditorViewModel, initialUri: String? = null) {
    MidiEditMobileTheme {
        val state by vm.uiState.collectAsStateWithLifecycle()
        val hover by vm.hover.collectAsStateWithLifecycle()
        val inspectorId by vm.inspectorNoteId.collectAsStateWithLifecycle()
        val snackbarHost = remember { SnackbarHostState() }
        var pendingImport by rememberSaveable { mutableStateOf(initialUri) }

        LaunchedEffect(vm) {
            vm.messageFlow.collect { message -> snackbarHost.showSnackbar(message) }
        }

        LaunchedEffect(pendingImport, state.boot) {
            val raw = pendingImport
            if (!raw.isNullOrBlank() && state.boot !is BootState.Loading) {
                pendingImport = null
                runCatching { vm.importMidi(Uri.parse(raw)) }
            }
        }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let(vm::importMidi) }
        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("audio/midi")
        ) { uri -> uri?.let(vm::exportMidi) }
        val soundFontLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let(vm::loadExternalSoundFont) }

        val onImport: () -> Unit = {
            importLauncher.launch(
                arrayOf(
                    "audio/midi",
                    "audio/x-midi",
                    "application/x-midi",
                    "application/octet-stream",
                )
            )
        }
        val onExport: () -> Unit = { exportLauncher.launch(vm.suggestedFileName()) }
        val onLoadSoundFont: () -> Unit = {
            // "*/*" keeps .sf2 files visible on pickers that filter by MIME.
            soundFontLauncher.launch(arrayOf("*/*"))
        }

        val context = LocalContext.current
        val projectBrowserVisible by vm.projectBrowserVisible.collectAsStateWithLifecycle()
        LaunchedEffect(vm) {
            vm.uiCommands.collect { command ->
                when (command) {
                    UiCommand.ImportMidi -> onImport()
                    UiCommand.ExportMidi -> onExport()
                    is UiCommand.Share -> {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = command.mimeType
                            putExtra(Intent.EXTRA_STREAM, command.uri)
                            putExtra(Intent.EXTRA_SUBJECT, command.title)
                            putExtra(Intent.EXTRA_TITLE, command.title)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(send, "MIDIを共有"))
                        }
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
        ) {
            val project = state.project
            if (project != null) {
                EditorScreen(
                    ui = state,
                    project = project,
                    hover = hover,
                    inspectorId = inspectorId,
                    vm = vm,
                    onImport = onImport,
                    onExport = onExport,
                    onLoadSoundFont = onLoadSoundFont,
                    onShare = vm::shareMidi,
                )
            } else if (state.boot is BootState.Loading) {
                BootScreen()
            } else {
                WelcomeScreen(
                    onNewProject = vm::newProject,
                    onImport = onImport,
                    onOpenProjects = vm::openProjectBrowser,
                )
            }

            if (projectBrowserVisible) {
                ProjectBrowserDialog(
                    projects = state.projects,
                    loading = state.projectsLoading,
                    activeId = state.tabs.firstOrNull { it.active }?.id,
                    onDismiss = vm::dismissProjectBrowser,
                    onOpen = {
                        vm.openProject(it)
                        vm.dismissProjectBrowser()
                    },
                    onNew = {
                        vm.newProject()
                        vm.dismissProjectBrowser()
                    },
                    onImport = {
                        vm.dismissProjectBrowser()
                        onImport()
                    },
                    onRename = vm::renameStoredProject,
                    onDelete = vm::deleteStoredProject,
                )
            }

            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .padding(
                        // While a note is selected the dock can be two rows
                        // tall, so lift the snackbar clear of both rows.
                        bottom = when {
                            project == null -> 24.dp
                            state.selection.isNotEmpty() -> 168.dp
                            else -> 102.dp
                        }
                    ),
            )
        }
    }
}

@Composable
private fun BootScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Icon(
                Icons.Rounded.Piano,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                "自動保存を確認しています…",
                style = MaterialTheme.typography.bodyMedium,
                color = Mist,
            )
        }
    }
}

@Composable
private fun WelcomeScreen(
    onNewProject: () -> Unit,
    onImport: () -> Unit,
    onOpenProjects: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Rounded.Piano,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(76.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "MIDIeditmobile",
                style = MaterialTheme.typography.displaySmall,
                color = Paper,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "タブレットのための、ピアノだけのMIDIエディタ",
                style = MaterialTheme.typography.titleMedium,
                color = Mist,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))
            ButtonWide(
                label = "新しい曲を作成",
                icon = Icons.Rounded.Add,
                filled = true,
                onClick = onNewProject,
            )
            Spacer(Modifier.height(12.dp))
            ButtonWide(
                label = "MIDIファイルを読み込む",
                icon = Icons.Rounded.FolderOpen,
                filled = false,
                onClick = onImport,
            )
            Spacer(Modifier.height(12.dp))
            ButtonWide(
                label = "保存済みプロジェクトを開く",
                icon = Icons.Rounded.LibraryMusic,
                filled = false,
                onClick = onOpenProjects,
            )
            Spacer(Modifier.height(28.dp))
            FeatureLine("ペン先でなぞって長さを指定、指でスクロール")
            FeatureLine("2本指のピンチで時間・音高をズーム")
            FeatureLine("SoundFontのピアノ音源を内蔵。再生と書き出しに対応")
            FeatureLine("自動保存は元のMIDIファイルとは別に保存されます")
            Spacer(Modifier.height(18.dp))
            Text(
                "既存の .mid を読み込んだ場合、他のトラックのイベントは保持されます。",
                style = MaterialTheme.typography.labelSmall,
                color = Mist.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun FeatureLine(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ButtonWide(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        shape = shape,
        color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun EditorScreen(
    ui: EditorUiState,
    project: Project,
    hover: HoverInfo?,
    inspectorId: Long?,
    vm: EditorViewModel,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onLoadSoundFont: () -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    val rollView = remember { PianoRollView(context) }
    val rollHost = remember(vm) {
        object : RollHost {
            override fun project() = vm.project()
            override fun prefs() = vm.prefs()
            override fun selection() = vm.selection()
            override fun playheadTick() = vm.playheadTick()
            override fun isPlaying() = vm.isPlaying()
            override fun isAudioReady() = vm.isAudioReady()
            override fun noteById(id: Long) = vm.noteById(id)
            override fun previewNote(pitch: Int, velocity: Int) = vm.previewNote(pitch, velocity)
            override fun addNoteAt(pitch: Int, startTick: Int, durationTicks: Int) =
                vm.addNoteAt(pitch, startTick, durationTicks)

            override fun commitRollNotes(newNotes: List<com.pianocanvas.midi.model.Note>) =
                vm.commitRollNotes(newNotes)

            override fun setRollSelection(ids: Set<Long>) = vm.setRollSelection(ids)
            override fun requestNoteInspector(id: Long) = vm.requestNoteInspector(id)
            override fun deleteNoteByDoubleTap(id: Long) = vm.deleteNoteByDoubleTap(id)
            override fun deleteNotes(ids: Set<Long>) = vm.deleteNotes(ids)
            override fun seekTo(tick: Double) = vm.seekTo(tick)
            override fun setLoopRange(startTick: Int, endTick: Int) =
                vm.setLoopRange(startTick, endTick)

            override fun onViewportSettled(viewport: com.pianocanvas.midi.data.ViewportState) =
                vm.onViewportSettled(viewport)

            override fun onHoverInfo(info: HoverInfo?) = vm.onHoverInfo(info)
        }
    }

    DisposableEffect(rollView, rollHost) {
        rollView.host = rollHost
        onDispose { rollView.host = null }
    }

    // When the editor dismisses the hover state (toolbar action, setting change
    // or pointer leaving the roll), drop the local ghost immediately instead
    // of waiting for the hover-exit grace period.
    LaunchedEffect(hover) {
        if (hover == null) rollView.dismissHover()
    }

    // Keyboard shortcuts that need the roll view (zoom, fit, bar scrolling).
    LaunchedEffect(rollView, vm) {
        vm.viewCommands.collect { command ->
            when (command) {
                ViewCommand.ZoomIn -> {
                    rollView.zoomHorizontal(1.22f)
                    rollView.zoomVertical(1.12f)
                }
                ViewCommand.ZoomOut -> {
                    rollView.zoomHorizontal(0.82f)
                    rollView.zoomVertical(0.9f)
                }
                ViewCommand.Fit -> rollView.fitToSong()
                is ViewCommand.ScrollBars -> rollView.scrollByBars(command.delta)
            }
        }
    }

    var showMeta by remember { mutableStateOf(false) }
    var showMidiInput by remember { mutableStateOf(false) }
    var showBpm by remember { mutableStateOf(false) }
    var showMeter by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showVelocity by remember { mutableStateOf(false) }
    var showLength by remember { mutableStateOf(false) }
    var showInputVelocity by remember { mutableStateOf(false) }
    var showNewConfirm by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 960.dp
        Column(Modifier.fillMaxSize()) {
            TopBar(
                ui = ui,
                project = project,
                compact = compact,
                vm = vm,
                onBpm = { showBpm = true },
                onMeter = { showMeter = true },
                onNew = { showNewConfirm = true },
                onImport = onImport,
                onExport = onExport,
                onSettings = { showSettings = true },
                onHelp = { showHelp = true },
                onRename = { showRename = true },
                onLoadSoundFont = onLoadSoundFont,
                onProjects = vm::openProjectBrowser,
                onShare = onShare,
                onMeta = { showMeta = true },
                onMidiInput = { vm.refreshMidiDevices(); showMidiInput = true },
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    factory = { rollView },
                    update = { view ->
                        view.syncState(
                            project = ui.project,
                            prefs = ui.prefs,
                            selection = ui.selection,
                            viewport = ui.viewport,
                            epoch = ui.viewportEpoch,
                            playing = ui.isPlaying,
                        )
                    },
                    // AndroidView stopped clipping its child automatically, and
                    // the roll paints its background with Canvas.drawColor(). On
                    // an unclipped canvas that fills the whole window and covers
                    // every composable drawn before it — the title, transport and
                    // menu — leaving them touchable but invisible. Clip the
                    // interop view to its layout bounds so its drawing stays
                    // inside the roll area.
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                )
            }
            ContextDock(
                ui = ui,
                project = project,
                vm = vm,
                onSettings = { showSettings = true },
                onVelocity = { showVelocity = true },
                onLength = { showLength = true },
                onInputVelocity = { showInputVelocity = true },
                onZoomIn = {
                    rollView.zoomHorizontal(1.22f)
                    rollView.zoomVertical(1.12f)
                },
                onZoomOut = {
                    rollView.zoomHorizontal(0.82f)
                    rollView.zoomVertical(0.9f)
                },
                onFit = { rollView.fitToSong() },
                onScrollBars = { rollView.scrollByBars(it) },
                onInspector = {
                    ui.selection.firstOrNull()?.let(vm::requestNoteInspector)
                },
            )
        }
    }

    if (showBpm) {
        BpmDialog(
            initial = project.bpm,
            onDismiss = { showBpm = false },
            onApply = {
                vm.setBpm(it)
                showBpm = false
            },
        )
    }
    if (showMeter) {
        MeterDialog(
            initial = project.timeSignature,
            onDismiss = { showMeter = false },
            onApply = { numerator, denominator ->
                vm.setTimeSignature(numerator, denominator)
                showMeter = false
            },
        )
    }
    if (showSettings) {
        SettingsDialog(
            ui = ui,
            vm = vm,
            onDismiss = { showSettings = false },
            onLoadSoundFont = onLoadSoundFont,
            onHelp = {
                showSettings = false
                showHelp = true
            },
        )
    }
    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }
    if (showRename) {
        RenameDialog(
            initial = project.title,
            onDismiss = { showRename = false },
            onApply = {
                vm.renameProject(it)
                showRename = false
            },
        )
    }
    if (showVelocity) {
        SelectionVelocityDialog(
            ui = ui,
            onDismiss = { showVelocity = false },
            onApply = {
                vm.setSelectionVelocity(it)
                showVelocity = false
            },
        )
    }
    if (showLength) {
        SelectionLengthDialog(
            ui = ui,
            onDismiss = { showLength = false },
            onApplyTicks = {
                vm.setSelectionDuration(it)
                showLength = false
            },
            onApplyUnit = {
                vm.setSelectionDuration(it.ticks(project.ppq))
                showLength = false
            },
        )
    }
    if (showInputVelocity) {
        InputVelocityDialog(
            initial = ui.prefs.inputVelocity,
            onDismiss = { showInputVelocity = false },
            onApply = {
                vm.setInputVelocity(it)
                showInputVelocity = false
            },
        )
    }
    if (showNewConfirm) {
        ConfirmNewDialog(
            onDismiss = { showNewConfirm = false },
            onConfirm = {
                showNewConfirm = false
                vm.newProject()
            },
        )
    }

    if (ui.trackPickerVisible) {
        TrackPickerDialog(
            project = project,
            onDismiss = vm::dismissTrackPicker,
            onSelect = vm::selectTrack,
        )
    }

    if (inspectorId != null) {
        val note = project.noteById(inspectorId)
        if (note != null) {
            NoteInspectorDialog(
                note = note,
                project = project,
                onDismiss = vm::dismissNoteInspector,
                onUpdate = { pitch, start, duration, velocity ->
                    vm.updateNote(
                        id = note.id,
                        pitch = pitch,
                        startTick = start,
                        durationTicks = duration,
                        velocity = velocity,
                    )
                },
                onDelete = {
                    vm.deleteNotes(setOf(note.id))
                    vm.dismissNoteInspector()
                },
                onDuplicate = {
                    vm.duplicateNote(note.id)
                    vm.dismissNoteInspector()
                },
                onPreview = { vm.previewNote(note.pitch, note.velocity) },
            )
        } else {
            LaunchedEffect(inspectorId) { vm.dismissNoteInspector() }
        }
    }

    if (showMeta) {
        MidiMetaDialog(
            initial = project.meta,
            onDismiss = { showMeta = false },
            onApply = {
                vm.setMidiMeta(it)
                showMeta = false
            },
        )
    }

    if (showMidiInput) {
        MidiInputDialog(
            midi = ui.midi,
            onDismiss = { showMidiInput = false },
            onSelectDevice = vm::selectMidiDevice,
            onToggleRecording = vm::toggleMidiRecording,
            onRefresh = vm::refreshMidiDevices,
        )
    }

}

// ----------------------------------------------------------------------
// Top bar
// ----------------------------------------------------------------------

@Composable
private fun TopBar(
    ui: EditorUiState,
    project: Project,
    compact: Boolean,
    vm: EditorViewModel,
    onBpm: () -> Unit,
    onMeter: () -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onRename: () -> Unit,
    onLoadSoundFont: () -> Unit,
    onProjects: () -> Unit,
    onShare: () -> Unit,
    onMeta: () -> Unit,
    onMidiInput: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            if (compact) {
                CompactTopRows(
                    ui = ui,
                    project = project,
                    vm = vm,
                    onRename = onRename,
                    onBpm = onBpm,
                    onMeter = onMeter,
                    onNew = onNew,
                    onImport = onImport,
                    onExport = onExport,
                    onSettings = onSettings,
                    onHelp = onHelp,
                    onLoadSoundFont = onLoadSoundFont,
                    onProjects = onProjects,
                    onShare = onShare,
                    onMeta = onMeta,
                    onMidiInput = onMidiInput,
                )
            } else {
                WideTopRow(
                    ui = ui,
                    project = project,
                    vm = vm,
                    onRename = onRename,
                    onBpm = onBpm,
                    onMeter = onMeter,
                    onNew = onNew,
                    onImport = onImport,
                    onExport = onExport,
                    onSettings = onSettings,
                    onHelp = onHelp,
                    onLoadSoundFont = onLoadSoundFont,
                    onProjects = onProjects,
                    onShare = onShare,
                    onMeta = onMeta,
                    onMidiInput = onMidiInput,
                )
            }
            if (ui.tabs.size > 1) {
                TabStrip(tabs = ui.tabs, vm = vm)
            }
            // A bright accent line separates the bar from the dark piano roll.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
            )
        }
    }
}

@Composable
private fun WideTopRow(
    ui: EditorUiState,
    project: Project,
    vm: EditorViewModel,
    onRename: () -> Unit,
    onBpm: () -> Unit,
    onMeter: () -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onLoadSoundFont: () -> Unit,
    onProjects: () -> Unit,
    onShare: () -> Unit,
    onMeta: () -> Unit,
    onMidiInput: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TitleBlock(ui = ui, project = project, onRename = onRename, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(14.dp))
        TransportControls(ui = ui, project = project, vm = vm)
        Spacer(Modifier.width(14.dp))
        MetaChip(
            text = "♩ ${formatBpm(project.bpm)}",
            icon = Icons.Rounded.Speed,
            onClick = onBpm,
        )
        Spacer(Modifier.width(6.dp))
        MetaChip(
            text = project.timeSignature.label(),
            icon = Icons.Rounded.MusicNote,
            onClick = onMeter,
        )
        if (project.tracks.size > 1) {
            Spacer(Modifier.width(6.dp))
            MetaChip(
                text = project.track.name,
                icon = Icons.Rounded.Piano,
                onClick = vm::openTrackPicker,
            )
        }
        Spacer(Modifier.width(6.dp))
        MidiChip(midi = ui.midi, onClick = onMidiInput)
        Spacer(Modifier.width(6.dp))
        HistoryButtons(ui = ui, vm = vm)
        Spacer(Modifier.width(4.dp))
        OverflowMenu(
            onNew = onNew,
            onImport = onImport,
            onExport = onExport,
            onSoundFont = onLoadSoundFont,
            onSettings = onSettings,
            onHelp = onHelp,
            onProjects = onProjects,
            onShare = onShare,
            onMeta = onMeta,
            onMidiInput = onMidiInput,
        )
    }
}

@Composable
private fun CompactTopRows(
    ui: EditorUiState,
    project: Project,
    vm: EditorViewModel,
    onRename: () -> Unit,
    onBpm: () -> Unit,
    onMeter: () -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onLoadSoundFont: () -> Unit,
    onProjects: () -> Unit,
    onShare: () -> Unit,
    onMeta: () -> Unit,
    onMidiInput: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitleBlock(ui = ui, project = project, onRename = onRename, modifier = Modifier.weight(1f))
            if (project.tracks.size > 1) {
                MetaChip(
                    text = project.track.name,
                    icon = Icons.Rounded.MusicNote,
                    onClick = vm::openTrackPicker,
                )
                Spacer(Modifier.width(4.dp))
            }
            MidiChip(midi = ui.midi, onClick = onMidiInput, compact = true)
            Spacer(Modifier.width(4.dp))
            OverflowMenu(
                onNew = onNew,
                onImport = onImport,
                onExport = onExport,
                onSoundFont = onLoadSoundFont,
                onSettings = onSettings,
                onHelp = onHelp,
                onProjects = onProjects,
                onShare = onShare,
                onMeta = onMeta,
                onMidiInput = onMidiInput,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportControls(ui = ui, project = project, vm = vm, compact = true, showHistory = true)
            Spacer(Modifier.weight(1f))
            MetaChip(
                text = "♩ ${formatBpm(project.bpm)}",
                icon = Icons.Rounded.Speed,
                onClick = onBpm,
            )
            Spacer(Modifier.width(6.dp))
            MetaChip(
                text = project.timeSignature.label(),
                icon = Icons.Rounded.MusicNote,
                onClick = onMeter,
            )
        }
    }
}

@Composable
private fun TitleBlock(
    ui: EditorUiState,
    project: Project,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val autosaveText = remember(ui.autosave) { autosaveLabel(ui.autosave) }
    val autosaveIcon = when (ui.autosave) {
        is AutosaveState.Saved -> Icons.Rounded.CheckCircle
        is AutosaveState.Failed -> Icons.Rounded.Warning
        else -> Icons.Rounded.Save
    }
    val autosaveTint = if (ui.autosave is AutosaveState.Failed) Coral
    else MaterialTheme.colorScheme.onSurfaceVariant
    val audioText = remember(ui.audio) {
        when (val audio = ui.audio) {
            is AudioStatus.Loading -> "音源を準備中…"
            is AudioStatus.Ready -> "音源: ${audio.presetName}"
            is AudioStatus.Failed -> "音源エラー"
        }
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onRename)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            project.title,
            style = MaterialTheme.typography.titleMedium,
            color = Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                autosaveIcon,
                contentDescription = null,
                tint = autosaveTint,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                autosaveText,
                style = MaterialTheme.typography.labelSmall,
                color = autosaveTint,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "·",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = if (ui.audio is AudioStatus.Failed) Coral
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                audioText,
                style = MaterialTheme.typography.labelSmall,
                color = if (ui.audio is AudioStatus.Failed) Coral
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TransportControls(
    ui: EditorUiState,
    project: Project,
    vm: EditorViewModel,
    compact: Boolean = false,
    showHistory: Boolean = false,
) {
    val meter = remember(project.ppq, project.timeSignature, project.timeSignatures) {
        MeterMap(project.ppq, project.timeSignatures, project.timeSignature)
    }
    val positionText by produceState(
        initialValue = meter.positionAt(vm.playheadTick().toInt()).label(),
        meter,
        ui.isPlaying,
    ) {
        while (true) {
            value = meter.positionAt(vm.playheadTick().toInt().coerceAtLeast(0)).label()
            delay(if (ui.isPlaying) 90 else 450)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showHistory) {
            IconButton(onClick = vm::undo, enabled = ui.canUndo, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.Undo,
                    contentDescription = "元に戻す",
                    tint = if (ui.canUndo) Paper else Mist.copy(alpha = 0.35f),
                )
            }
        }
        FilledIconButton(
            onClick = vm::togglePlayback,
            modifier = Modifier.size(if (compact) 44.dp else 46.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (ui.isPlaying) "一時停止" else "再生",
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(onClick = vm::stopPlayback, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Stop, contentDescription = "停止", tint = Paper)
        }
        IconButton(onClick = vm::toggleLoop, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Rounded.Loop,
                contentDescription = "ループ",
                tint = if (project.loopEnabled) MaterialTheme.colorScheme.secondary else Mist,
            )
        }
        Text(
            positionText,
            style = MaterialTheme.typography.labelMedium,
            color = Paper,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 54.dp),
            maxLines = 1,
        )
        if (showHistory) {
            IconButton(onClick = vm::redo, enabled = ui.canRedo, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.Redo,
                    contentDescription = "やり直す",
                    tint = if (ui.canRedo) Paper else Mist.copy(alpha = 0.35f),
                )
            }
        }
    }
}

@Composable
private fun HistoryButtons(ui: EditorUiState, vm: EditorViewModel) {
    IconButton(onClick = vm::undo, enabled = ui.canUndo, modifier = Modifier.size(42.dp)) {
        Icon(
            Icons.AutoMirrored.Rounded.Undo,
            contentDescription = "元に戻す",
            tint = if (ui.canUndo) Paper else Mist.copy(alpha = 0.35f),
        )
    }
    IconButton(onClick = vm::redo, enabled = ui.canRedo, modifier = Modifier.size(42.dp)) {
        Icon(
            Icons.AutoMirrored.Rounded.Redo,
            contentDescription = "やり直す",
            tint = if (ui.canRedo) Paper else Mist.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun MetaChip(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.heightIn(min = 38.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MidiChip(
    midi: MidiUiState,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    val recording = midi.recording
    val label = when {
        recording -> "REC"
        midi.connectedName != null -> midi.connectedName
        midi.devices.any { it.canReceive } -> "MIDI入力"
        else -> "MIDI"
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        color = if (recording) MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (recording) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.heightIn(min = 38.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (recording) Icons.Rounded.FiberManualRecord else Icons.Rounded.Usb,
                contentDescription = null,
                tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(if (recording) 13.dp else 15.dp),
            )
            if (!compact || recording) {
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (recording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 132.dp),
                )
            }
        }
    }
}

@Composable
private fun TabStrip(tabs: List<TabInfo>, vm: EditorViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            TabChip(
                tab = tab,
                onSelect = { vm.switchTab(index) },
                onClose = { vm.closeTab(index) },
            )
        }
        IconButton(onClick = vm::newProject, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Add, contentDescription = "新しいタブ", tint = Paper)
        }
    }
}

@Composable
private fun TabChip(tab: TabInfo, onSelect: () -> Unit, onClose: () -> Unit) {
    val accent = if (tab.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(10.dp),
        color = if (tab.active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (tab.active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accent),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tab.title,
                style = MaterialTheme.typography.labelMedium,
                color = if (tab.active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "タブを閉じる",
                    tint = Mist,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    onNew: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSoundFont: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onProjects: () -> Unit,
    onShare: () -> Unit,
    onMeta: () -> Unit,
    onMidiInput: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "メニュー", tint = Paper)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("プロジェクトを開く") },
                leadingIcon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                onClick = {
                    expanded = false
                    onProjects()
                },
            )
            DropdownMenuItem(
                text = { Text("共有") },
                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                onClick = {
                    expanded = false
                    onShare()
                },
            )
            DropdownMenuItem(
                text = { Text("MIDI META 設定") },
                leadingIcon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
                onClick = {
                    expanded = false
                    onMeta()
                },
            )
            DropdownMenuItem(
                text = { Text("MIDIキーボード入力") },
                leadingIcon = { Icon(Icons.Rounded.Usb, contentDescription = null) },
                onClick = {
                    expanded = false
                    onMidiInput()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("MIDIを読み込む") },
                leadingIcon = { Icon(Icons.Rounded.FileUpload, contentDescription = null) },
                onClick = {
                    expanded = false
                    onImport()
                },
            )
            DropdownMenuItem(
                text = { Text("MIDIを書き出す") },
                leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
                onClick = {
                    expanded = false
                    onExport()
                },
            )
            DropdownMenuItem(
                text = { Text("SoundFontを読み込む") },
                leadingIcon = { Icon(Icons.Rounded.Audiotrack, contentDescription = null) },
                onClick = {
                    expanded = false
                    onSoundFont()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("新しいプロジェクト") },
                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                onClick = {
                    expanded = false
                    onNew()
                },
            )
            DropdownMenuItem(
                text = { Text("設定") },
                leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                onClick = {
                    expanded = false
                    onSettings()
                },
            )
            DropdownMenuItem(
                text = { Text("操作ガイド") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null) },
                onClick = {
                    expanded = false
                    onHelp()
                },
            )
        }
    }
}

private fun formatBpm(bpm: Double): String =
    if (kotlin.math.abs(bpm - Math.round(bpm).toDouble()) < 0.05) {
        Math.round(bpm).toString()
    } else {
        String.format(Locale.JAPAN, "%.1f", bpm)
    }

private fun autosaveLabel(state: AutosaveState): String = when (state) {
    AutosaveState.Idle -> "未保存"
    AutosaveState.Saving -> "自動保存中…"
    is AutosaveState.Saved -> {
        val format = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)
        "自動保存 ${format.format(Date(state.atMillis))}"
    }
    is AutosaveState.Failed -> "自動保存エラー"
}

// ----------------------------------------------------------------------
// Contextual dock
// ----------------------------------------------------------------------

private data class DockAction(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun ContextDock(
    ui: EditorUiState,
    project: Project,
    vm: EditorViewModel,
    onSettings: () -> Unit,
    onVelocity: () -> Unit,
    onLength: () -> Unit,
    onInputVelocity: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onScrollBars: (Int) -> Unit,
    onInspector: () -> Unit,
) {
    val selectionCount = ui.selection.size
    // The unselected bar: input controls shown when nothing is selected. It
    // stays as the bottom row even while notes are selected.
    val unselectedActions = buildList {
            if (selectionCount == 0) {
                add(
                    DockAction(
                        id = "grid",
                        icon = if (ui.prefs.grid == GridUnit.OFF) Icons.Rounded.GridOff else Icons.Rounded.GridOn,
                        label = "グリッド ${ui.prefs.grid.label}",
                        active = ui.prefs.grid != GridUnit.OFF,
                        onClick = vm::cycleGrid,
                    )
                )
                add(
                    DockAction(
                        id = "length",
                        icon = Icons.Rounded.Straighten,
                        label = "長さ ${ui.prefs.inputDuration.label}",
                        onClick = vm::cycleInputDuration,
                    )
                )
                add(
                    DockAction(
                        id = "snap",
                        icon = if (ui.prefs.snapEnabled) Icons.Rounded.Check else Icons.Rounded.Close,
                        label = "スナップ ${if (ui.prefs.snapEnabled) "ON" else "OFF"}",
                        active = ui.prefs.snapEnabled,
                        onClick = { vm.setSnapEnabled(!ui.prefs.snapEnabled) },
                    )
                )
                add(
                    DockAction(
                        id = "velocity",
                        icon = Icons.AutoMirrored.Rounded.VolumeUp,
                        label = "入力 v${ui.prefs.inputVelocity}",
                        onClick = onInputVelocity,
                    )
                )
                add(
                    DockAction(
                        id = "quantizeAll",
                        icon = Icons.Rounded.AutoFixHigh,
                        label = "全クオンタイズ",
                        enabled = project.notes.isNotEmpty(),
                        onClick = vm::quantizeAll,
                    )
                )
                add(
                    DockAction(
                        id = "selectAll",
                        icon = Icons.Rounded.SelectAll,
                        label = "全選択",
                        enabled = project.notes.isNotEmpty(),
                        onClick = vm::selectAll,
                    )
                )
                add(
                    DockAction(
                        id = "paste",
                        icon = Icons.Rounded.ContentPaste,
                        label = "貼り付け",
                        onClick = vm::pasteAtPlayhead,
                    )
                )
            }
    }
    // Note editing actions: an extra bar layered on top of the unselected bar
    // while notes are selected.
    val selectionActions = buildList {
            if (selectionCount > 0) {
                add(
                    DockAction(
                        id = "clear",
                        icon = Icons.Rounded.Close,
                        label = "選択解除",
                        onClick = vm::clearSelection,
                    )
                )
                add(
                    DockAction(
                        id = "length",
                        icon = Icons.Rounded.Straighten,
                        label = "長さ",
                        onClick = onLength,
                    )
                )
                add(
                    DockAction(
                        id = "velocity",
                        icon = Icons.Rounded.GraphicEq,
                        label = "ベロシティ",
                        onClick = onVelocity,
                    )
                )
                add(
                    DockAction(
                        id = "down12",
                        icon = Icons.Rounded.ArrowDownward,
                        label = "−12",
                        onClick = { vm.transposeSelection(-12) },
                    )
                )
                add(
                    DockAction(
                        id = "down1",
                        icon = Icons.Rounded.ArrowDownward,
                        label = "−1",
                        onClick = { vm.transposeSelection(-1) },
                    )
                )
                add(
                    DockAction(
                        id = "up1",
                        icon = Icons.Rounded.ArrowUpward,
                        label = "+1",
                        onClick = { vm.transposeSelection(1) },
                    )
                )
                add(
                    DockAction(
                        id = "up12",
                        icon = Icons.Rounded.ArrowUpward,
                        label = "+12",
                        onClick = { vm.transposeSelection(12) },
                    )
                )
                add(
                    DockAction(
                        id = "duplicate",
                        icon = Icons.Rounded.FileCopy,
                        label = "複製",
                        onClick = vm::duplicateSelection,
                    )
                )
                add(
                    DockAction(
                        id = "copy",
                        icon = Icons.Rounded.ContentCopy,
                        label = "コピー",
                        onClick = vm::copySelection,
                    )
                )
                add(
                    DockAction(
                        id = "cut",
                        icon = Icons.Rounded.ContentCut,
                        label = "カット",
                        onClick = vm::cutSelection,
                    )
                )
                add(
                    DockAction(
                        id = "paste",
                        icon = Icons.Rounded.ContentPaste,
                        label = "貼り付け",
                        onClick = vm::pasteAtPlayhead,
                    )
                )
                add(
                    DockAction(
                        id = "delete",
                        icon = Icons.Rounded.Delete,
                        label = "削除",
                        onClick = vm::deleteSelection,
                    )
                )
                add(
                    DockAction(
                        id = "quantize",
                        icon = Icons.Rounded.AutoFixHigh,
                        label = "クオンタイズ",
                        onClick = vm::quantizeSelection,
                    )
                )
                add(
                    DockAction(
                        id = "loop",
                        icon = Icons.Rounded.Loop,
                        label = "ループ範囲",
                        onClick = vm::loopFromSelection,
                    )
                )
                add(
                    DockAction(
                        id = "selectAll",
                        icon = Icons.Rounded.SelectAll,
                        label = "全選択",
                        onClick = vm::selectAll,
                    )
                )
                if (selectionCount == 1) {
                    add(
                        DockAction(
                            id = "inspector",
                            icon = Icons.Rounded.Edit,
                            label = "数値編集",
                            onClick = onInspector,
                        )
                    )
                }
            }
    }
        // The always-available actions that share the bottom (unselected) bar:
        // bar navigation, zoom, loop and settings.
        val alwaysActions = buildList {
            add(
                DockAction(
                    id = "barPrev",
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    label = "前の小節",
                    onClick = { onScrollBars(-1) },
                )
            )
            add(
                DockAction(
                    id = "barNext",
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    label = "次の小節",
                    onClick = { onScrollBars(1) },
                )
            )
            add(
                DockAction(
                    id = "zoomOut",
                    icon = Icons.Rounded.ZoomOut,
                    label = "縮小",
                    onClick = onZoomOut,
                )
            )
            add(
                DockAction(
                    id = "zoomIn",
                    icon = Icons.Rounded.ZoomIn,
                    label = "拡大",
                    onClick = onZoomIn,
                )
            )
            add(
                DockAction(
                    id = "fit",
                    icon = Icons.Rounded.Tune,
                    label = "全体表示",
                    onClick = onFit,
                )
            )
            add(
                DockAction(
                    id = "loopToggle",
                    icon = Icons.Rounded.Loop,
                    label = "ループ ${if (project.loopEnabled) "ON" else "OFF"}",
                    active = project.loopEnabled,
                    onClick = vm::toggleLoop,
                )
            )
            add(
                DockAction(
                    id = "settings",
                    icon = Icons.Rounded.Settings,
                    label = "設定",
                    onClick = onSettings,
                )
            )
        }
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (selectionCount == 0) {
                        "入力 ${ui.prefs.inputDuration.label} ・ グリッド ${ui.prefs.grid.label} ・ スナップ ${if (ui.prefs.snapEnabled) "ON" else "OFF"}"
                    } else {
                        "$selectionCount / ${project.notes.size} ノートを選択"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selectionCount == 0) Mist else MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Mist.copy(alpha = 0.7f),
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    "${project.notes.size} notes",
                    style = MaterialTheme.typography.labelSmall,
                    color = Mist.copy(alpha = 0.7f),
                )
            }
            // While notes are selected the note edit menu becomes an extra
            // bar above the unselected bar; otherwise a single row is used.
            if (selectionCount > 0) {
                DockActionRow(selectionActions)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            DockActionRow(unselectedActions + alwaysActions)
        }
    }
}

@Composable
private fun DockActionRow(actions: List<DockAction>) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(actions, key = { it.id }) { action ->
            DockButton(action)
        }
    }
}

@Composable
private fun DockButton(action: DockAction) {
    val tint = when {
        !action.enabled -> Mist.copy(alpha = 0.3f)
        action.active -> MaterialTheme.colorScheme.primary
        else -> Paper
    }
    Column(
        modifier = Modifier
            .width(64.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (action.active) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                else Color.Transparent
            )
            .clickable(enabled = action.enabled, onClick = action.onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(action.icon, contentDescription = action.label, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            action.label,
            color = tint,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
