package com.pianocanvas.midi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pianocanvas.midi.BuildConfig
import com.pianocanvas.midi.audio.AudioStatus
import com.pianocanvas.midi.editor.EditorUiState
import com.pianocanvas.midi.editor.EditorViewModel
import com.pianocanvas.midi.model.GridUnit
import com.pianocanvas.midi.model.MeterMap
import com.pianocanvas.midi.model.Note
import com.pianocanvas.midi.model.Project
import com.pianocanvas.midi.model.TimeSignature
import com.pianocanvas.midi.model.noteName
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
private fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val scrimInteraction = remember { MutableInteractionSource() }
        val surfaceInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.58f))
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                val maxDialogHeight = maxHeight.coerceAtMost(700.dp)
                val maxScrollHeight = (maxDialogHeight - 150.dp).coerceAtLeast(140.dp)
                Surface(
                    modifier = Modifier
                        .widthIn(max = 620.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxDialogHeight)
                        .clickable(
                            interactionSource = surfaceInteraction,
                            indication = null,
                            onClick = {},
                        ),
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 24.dp,
                ) {
                    Column(Modifier.padding(22.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (icon != null) {
                                Surface(
                                    modifier = Modifier.size(44.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleLarge)
                                if (subtitle != null) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Mist,
                                    )
                                }
                            }
                            IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = "閉じる")
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Column(
                            Modifier
                                .heightIn(max = maxScrollHeight)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            content()
                        }
                        if (actions != null) {
                            Spacer(Modifier.height(14.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                                content = actions,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Tempo and meter
// ----------------------------------------------------------------------

@Composable
fun BpmDialog(
    initial: Double,
    onDismiss: () -> Unit,
    onApply: (Double) -> Unit,
) {
    var text by remember {
        mutableStateOf(
            if (abs(initial - initial.roundToInt()) < 0.05) initial.roundToInt().toString()
            else String.format(java.util.Locale.JAPAN, "%.1f", initial)
        )
    }
    val parsed = text.toDoubleOrNull()
    val valid = parsed != null && parsed in 20.0..400.0
    AppDialog(
        title = "テンポ",
        subtitle = "20〜400 BPM",
        icon = Icons.Rounded.Speed,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("キャンセル", Icons.Rounded.Close, onDismiss)
            Spacer(Modifier.size(6.dp))
            DialogPrimaryButton(
                text = "適用",
                icon = Icons.Rounded.Check,
                enabled = valid,
                onClick = { onApply(parsed!!.coerceIn(20.0, 400.0)) },
            )
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value.filter { it.isDigit() || it == '.' }.take(6)
            },
            label = { Text("BPM") },
            singleLine = true,
            isError = !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(60.0, 80.0, 100.0, 120.0, 140.0, 160.0, 200.0).forEach { bpm ->
                FilterChip(
                    selected = abs((parsed ?: -1.0) - bpm) < 0.05,
                    onClick = { text = bpm.roundToInt().toString() },
                    label = { Text(bpm.roundToInt().toString()) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "テンポは曲の先頭に書き出されます。",
            style = MaterialTheme.typography.labelSmall,
            color = Mist,
        )
    }
}

@Composable
fun MeterDialog(
    initial: TimeSignature,
    onDismiss: () -> Unit,
    onApply: (numerator: Int, denominator: Int) -> Unit,
) {
    var numerator by remember { mutableIntStateOf(initial.numerator) }
    var denominator by remember { mutableIntStateOf(initial.denominator) }
    val valid = numerator in 1..32 && denominator in listOf(1, 2, 4, 8, 16, 32)
    AppDialog(
        title = "拍子",
        subtitle = "曲の先頭の拍子を変更します",
        icon = Icons.Rounded.Timer,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("キャンセル", Icons.Rounded.Close, onDismiss)
            Spacer(Modifier.size(6.dp))
            DialogPrimaryButton(
                text = "適用",
                icon = Icons.Rounded.Check,
                enabled = valid,
                onClick = { onApply(numerator, denominator) },
            )
        },
    ) {
        Text("分子", style = MaterialTheme.typography.labelMedium, color = Mist)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepSquare("−") { numerator = (numerator - 1).coerceIn(1, 32) }
            Text(
                numerator.toString(),
                modifier = Modifier.widthIn(min = 74.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
            )
            StepSquare("+") { numerator = (numerator + 1).coerceIn(1, 32) }
        }
        Spacer(Modifier.height(16.dp))
        Text("分母", style = MaterialTheme.typography.labelMedium, color = Mist)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(1, 2, 4, 8, 16, 32).forEach { d ->
                FilterChip(
                    selected = denominator == d,
                    onClick = { denominator = d },
                    label = { Text(d.toString()) },
                    modifier = Modifier.heightIn(min = 42.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                "$numerator / $denominator 拍子",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Paper,
            )
        }
    }
}

// ----------------------------------------------------------------------
// Track picker
// ----------------------------------------------------------------------

@Composable
fun TrackPickerDialog(
    project: Project,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AppDialog(
        title = "編集するトラック",
        subtitle = "他のトラックはそのまま保持されます",
        icon = Icons.Rounded.LibraryMusic,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("閉じる", Icons.Rounded.Close, onDismiss)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            project.tracks.forEachIndexed { index, track ->
                val active = index == project.activeTrack
                Surface(
                    onClick = { onSelect(index) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 62.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHighest
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) MaterialTheme.colorScheme.onPrimary else Paper,
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${track.noteCount} ノート ・ CH ${track.channel + 1}" +
                                    if (track.isDrum) " ・ ドラム" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = Mist,
                            )
                        }
                        if (active) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = "選択中",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Note inspector
// ----------------------------------------------------------------------

@Composable
fun NoteInspectorDialog(
    note: Note,
    project: Project,
    onDismiss: () -> Unit,
    onUpdate: (pitch: Int?, startTick: Int?, durationTicks: Int?, velocity: Int?) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onPreview: () -> Unit,
) {
    val meter = remember(project.ppq, project.timeSignature, project.timeSignatures) {
        MeterMap(project.ppq, project.timeSignatures, project.timeSignature)
    }
    val step = remember(project.ppq, project.track.channel) {
        val grid = GridUnit.entries.firstOrNull { it != GridUnit.OFF } ?: GridUnit.SIXTEENTH
        grid.ticks(project.ppq).coerceAtLeast(1)
    }
    var velocity by remember(note.id, note.velocity) {
        mutableFloatStateOf(note.velocity.toFloat())
    }
    val barLabel = meter.positionAt(note.startTick).label()

    AppDialog(
        title = "ノートを編集",
        subtitle = "ダブルタップしたノートの数値編集",
        icon = Icons.Rounded.Edit,
        onDismiss = onDismiss,
        actions = {
            TextButton(
                onClick = onDelete,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.size(5.dp))
                Text("削除")
            }
            TextButton(onClick = onDuplicate) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.size(5.dp))
                Text("複製")
            }
            Spacer(Modifier.size(6.dp))
            DialogPrimaryButton("完了", Icons.Rounded.Check, onClick = onDismiss)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    noteName(note.pitch),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column {
                Text("位置 $barLabel", style = MaterialTheme.typography.labelMedium, color = Mist)
                Text(
                    "tick ${note.startTick} ・ 長さ ${note.durationTicks}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Mist,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onPreview, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = "試聴",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))

        Text("音高", style = MaterialTheme.typography.labelMedium, color = Mist)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepSquare("−12") { onUpdate((note.pitch - 12).coerceIn(0, 127), null, null, null) }
            Spacer(Modifier.size(5.dp))
            StepSquare("−1") { onUpdate((note.pitch - 1).coerceIn(0, 127), null, null, null) }
            Text(
                noteName(note.pitch),
                modifier = Modifier.widthIn(min = 86.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            StepSquare("+1") { onUpdate((note.pitch + 1).coerceIn(0, 127), null, null, null) }
            Spacer(Modifier.size(5.dp))
            StepSquare("+12") { onUpdate((note.pitch + 12).coerceIn(0, 127), null, null, null) }
        }

        Spacer(Modifier.height(10.dp))
        Text("開始位置", style = MaterialTheme.typography.labelMedium, color = Mist)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepSquare("−") {
                onUpdate(null, (note.startTick - step).coerceAtLeast(0), null, null)
            }
            Text(
                "$barLabel",
                modifier = Modifier.widthIn(min = 96.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            StepSquare("+") { onUpdate(null, note.startTick + step, null, null) }
            Spacer(Modifier.weight(1f))
            Text(
                "${note.startTick} tick",
                style = MaterialTheme.typography.labelSmall,
                color = Mist,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("長さ", style = MaterialTheme.typography.labelMedium, color = Mist)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepSquare("−") {
                onUpdate(null, null, (note.durationTicks - step).coerceAtLeast(1), null)
            }
            Text(
                "${note.durationTicks} tick",
                modifier = Modifier.widthIn(min = 96.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            StepSquare("+") { onUpdate(null, null, note.durationTicks + step, null) }
            Spacer(Modifier.size(8.dp))
            SmallAction("½") {
                onUpdate(null, null, (note.durationTicks / 2).coerceAtLeast(1), null)
            }
            Spacer(Modifier.size(5.dp))
            SmallAction("×2") { onUpdate(null, null, note.durationTicks * 2, null) }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ベロシティ", style = MaterialTheme.typography.labelMedium, color = Mist)
            Spacer(Modifier.weight(1f))
            Text(
                velocity.roundToInt().toString(),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = velocity,
            onValueChange = { velocity = it },
            valueRange = 1f..127f,
            onValueChangeFinished = {
                onUpdate(null, null, null, velocity.roundToInt().coerceIn(1, 127))
            },
        )
        Text(
            "変更は自動保存されます。元に戻すは上部の矢印から。",
            style = MaterialTheme.typography.labelSmall,
            color = Mist,
        )
    }
}

// ----------------------------------------------------------------------
// Selection value dialogs
// ----------------------------------------------------------------------

@Composable
fun SelectionVelocityDialog(
    ui: EditorUiState,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    val notes = ui.project?.notes?.filter { it.id in ui.selection } ?: emptyList()
    val initial = if (notes.isEmpty()) ui.prefs.inputVelocity
    else notes.sumOf { it.velocity } / notes.size
    var value by remember { mutableFloatStateOf(initial.toFloat()) }
    AppDialog(
        title = "ベロシティ",
        subtitle = "${notes.size} 個のノート",
        icon = Icons.AutoMirrored.Rounded.VolumeUp,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("キャンセル", Icons.Rounded.Close, onDismiss)
            Spacer(Modifier.size(6.dp))
            DialogPrimaryButton(
                "適用",
                Icons.Rounded.Check,
                onClick = { onApply(value.roundToInt()) },
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("強さ", style = MaterialTheme.typography.labelMedium, color = Mist)
            Spacer(Modifier.weight(1f))
            Text(
                value.roundToInt().toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 1f..127f,
        )
        Text(
            "選択中のすべてのノートに同じベロシティを設定します。",
            style = MaterialTheme.typography.labelSmall,
            color = Mist,
        )
    }
}

@Composable
fun SelectionLengthDialog(
    ui: EditorUiState,
    onDismiss: () -> Unit,
    onApplyTicks: (Int) -> Unit,
    onApplyUnit: (GridUnit) -> Unit,
) {
    val project = ui.project
    val notes = project?.notes?.filter { it.id in ui.selection } ?: emptyList()
    val initial = notes.firstOrNull()?.durationTicks ?: (project?.ppq ?: 480)
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = text.toIntOrNull()
    AppDialog(
        title = "長さ",
        subtitle = "${notes.size} 個のノートを揃えます",
        icon = Icons.Rounded.Straighten,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("キャンセル", Icons.Rounded.Close, onDismiss)
            Spacer(Modifier.size(6.dp))
            DialogPrimaryButton(
                text = "tickで適用",
                icon = Icons.Rounded.Check,
                enabled = parsed != null && parsed > 0,
                onClick = { parsed?.let { onApplyTicks(it.coerceAtLeast(1)) } },
            )
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { value -> text = value.filter { it.isDigit() }.take(7) },
            label = { Text("長さ (tick)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "音符の長さをプリセットから選ぶ",
            style = MaterialTheme.typography.labelMedium,
            color = Mist,
        )
        Spacer(Modifier.height(6.dp))
        GridUnitChips(
            selected = null,
            includeOff = false,
            onSelect = onApplyUnit,
        )
    }
}

@Composable
fun InputVelocityDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    var value by remember { mutableFloatStateOf(initial.toFloat()) }
    AppDialog(
        title = "入力ベロシティ",
        subtitle = "新しく追加する音符の強さ",
        icon = Icons.AutoMirrored.Rounded.VolumeUp,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("キャンセル", Icons.Rounded.Close, onDismiss)
            Spacer(Modifier.size(6.dp))
            DialogPrimaryButton(
                "適用",
                Icons.Rounded.Check,
                onClick = { onApply(value.roundToInt()) },
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(8.dp))
            Text("v${value.roundToInt()}", style = MaterialTheme.typography.headlineSmall)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 1f..127f,
        )
        Text(
            "ペンで描く音符やタップで追加する音符に使われます。",
            style = MaterialTheme.typography.labelSmall,
            color = Mist,
        )
    }
}

// ----------------------------------------------------------------------
// Settings and help
// ----------------------------------------------------------------------

@Composable
fun SettingsDialog(
    ui: EditorUiState,
    vm: EditorViewModel,
    onDismiss: () -> Unit,
    onLoadSoundFont: () -> Unit,
    onHelp: () -> Unit,
) {
    AppDialog(
        title = "設定",
        icon = Icons.Rounded.Settings,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("操作ガイド", Icons.AutoMirrored.Rounded.HelpOutline, onHelp)
            Spacer(Modifier.size(6.dp))
            DialogPrimaryButton("閉じる", Icons.Rounded.Check, onClick = onDismiss)
        },
    ) {
        SectionTitle("入力", Icons.Rounded.Tune)
        SettingLabel("グリッド / スナップ")
        GridUnitChips(selected = ui.prefs.grid, includeOff = true, onSelect = vm::setGrid)
        Spacer(Modifier.height(10.dp))
        SettingLabel("新規音符の長さ")
        GridUnitChips(selected = ui.prefs.inputDuration, includeOff = false, onSelect = vm::setInputDuration)
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            title = "スナップ",
            description = "音符の開始・終了をグリッドに合わせる",
            icon = Icons.Rounded.GridOn,
            checked = ui.prefs.snapEnabled,
            onCheckedChange = vm::setSnapEnabled,
        )
        ToggleRow(
            title = "指で音符を編集",
            description = "オフにすると指はスクロールと選択だけに使います",
            icon = Icons.Rounded.TouchApp,
            checked = ui.prefs.fingerEditing,
            onCheckedChange = vm::setFingerEditing,
        )
        ToggleRow(
            title = "ペンのホバーツールバー",
            description = "ペンを浮かせたときに文脈メニューを表示",
            icon = Icons.Rounded.PanTool,
            checked = ui.prefs.hoverToolbar,
            onCheckedChange = vm::setHoverToolbar,
        )
        ToggleRow(
            title = "再生中の自動スクロール",
            description = "再生位置が画面外に出たら追従します",
            icon = Icons.Rounded.Swipe,
            checked = ui.prefs.autoScroll,
            onCheckedChange = vm::setAutoScroll,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("入力ベロシティ", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                ui.prefs.inputVelocity.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = ui.prefs.inputVelocity.toFloat(),
            onValueChange = { vm.setInputVelocity(it.roundToInt()) },
            valueRange = 1f..127f,
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        SectionTitle("音源", Icons.Rounded.GraphicEq)
        AudioStatusCard(ui, vm, onLoadSoundFont)

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        SectionTitle("保存について", Icons.Rounded.Save)
        Text(
            "編集内容は約0.7秒後にアプリ内へ自動保存されます。MIDIの書き出し先とは別なので、元のファイルが勝手に上書きされることはありません。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Piano Canvas ${BuildConfig.VERSION_NAME} ・ ピアノ専用MIDIエディタ",
            style = MaterialTheme.typography.labelSmall,
            color = Mist,
        )
    }
}

@Composable
private fun AudioStatusCard(
    ui: EditorUiState,
    vm: EditorViewModel,
    onLoadSoundFont: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Audiotrack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                when (val audio = ui.audio) {
                    is AudioStatus.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("音源を読み込み中…", style = MaterialTheme.typography.bodyMedium)
                    }
                    is AudioStatus.Ready -> {
                        Column {
                            Text(
                                audio.presetName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${audio.fontName} ・ ${audio.regionCount} ゾーン",
                                style = MaterialTheme.typography.labelSmall,
                                color = Mist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    is AudioStatus.Failed -> {
                        Text(
                            audio.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::reloadBundledSoundFont) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("内蔵ピアノ")
                }
                OutlinedButton(onClick = onLoadSoundFont, enabled = !ui.busy.soundFont) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (ui.busy.soundFont) "読込中…" else "SF2を開く")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "SF2 (SoundFont 2.04) に対応。SF3/Ogg圧縮には対応していません。",
                style = MaterialTheme.typography.labelSmall,
                color = Mist,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(7.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Mist)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun GridUnitChips(
    selected: GridUnit?,
    includeOff: Boolean,
    onSelect: (GridUnit) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        GridUnit.entries
            .filter { includeOff || it != GridUnit.OFF }
            .forEach { unit ->
                FilterChip(
                    selected = selected == unit,
                    onClick = { onSelect(unit) },
                    label = { Text(unit.label) },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(10.dp),
            color = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (checked) MaterialTheme.colorScheme.primary else Mist,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.labelSmall, color = Mist)
        }
        Spacer(Modifier.size(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AppDialog(
        title = "操作ガイド",
        subtitle = "指とスタイラスの操作",
        icon = Icons.AutoMirrored.Rounded.HelpOutline,
        onDismiss = onDismiss,
        actions = {
            DialogPrimaryButton("閉じる", Icons.Rounded.Check, onClick = onDismiss)
        },
    ) {
        HelpSection(
            "ペン（スタイラス）",
            Icons.Rounded.Edit,
            listOf(
                "ペンを浮かせる : ゴースト音符と対象ノートがハイライトされます。位置に応じたツールバーが出ます。",
                "空欄をドラッグ : 音符の長さを描いて追加します。離した時点で確定します。",
                "音符の中央をドラッグ : 選択中の音符をまとめて移動します。",
                "音符の左右の端をドラッグ : 長さを変更します。端の判定にはヒステリシスがあります。",
                "音符をダブルタップ : 音高・位置・長さ・ベロシティの数値編集を開きます。",
                "ペンの消しゴム : 触れた音符を削除します。",
            ),
        )
        Spacer(Modifier.height(12.dp))
        HelpSection(
            "指",
            Icons.Rounded.TouchApp,
            listOf(
                "空欄をタップ : 現在の長さで音符を追加します。",
                "空欄をドラッグ : 最初に動いた方向へスクロールします（軸固定）。",
                "2本指 : スクロールと、時間・音高のピンチズーム。",
                "空欄を長押し : 矩形で複数ノートを選択します。",
                "音符を長押し : 選択に追加 / 選択から除外します。",
                "音符をドラッグ : 移動。端をドラッグすると長さ変更。",
                "ルーラーをタップ / ドラッグ : 再生位置を移動。",
                "ルーラーの下のループ帯 : ドラッグでループ範囲を指定。",
            ),
        )
        Spacer(Modifier.height(12.dp))
        HelpSection(
            "下部ツールバー",
            Icons.Rounded.Tune,
            listOf(
                "選択中は、長さ・ベロシティ・移調・複製・コピー・カット・削除・クオンタイズ・ループ範囲が使えます。",
                "未選択時は、グリッド・入力長・スナップ・入力ベロシティ・全クオンタイズ・全選択が使えます。",
                "拡大 / 縮小 / 全体表示は時間軸と音高軸の両方に効きます。",
            ),
        )
        Spacer(Modifier.height(12.dp))
        HelpSection(
            "保存とMIDI",
            Icons.Rounded.Save,
            listOf(
                "編集は自動保存され、次回起動時に復元されます。",
                "MIDI書き出しは Storage Access Framework の保存先へ行います。",
                "読み込んだMIDIの他トラックは、編集していないイベントも含めて書き出し時に保持されます。",
                "テンポと拍子は曲の先頭イベントとして更新されます。",
            ),
        )
    }
}

@Composable
private fun HelpSection(title: String, icon: ImageVector, lines: List<String>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(7.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(5.dp))
    lines.forEach { line ->
        Row(Modifier.padding(vertical = 3.dp)) {
            Text(
                "•",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ----------------------------------------------------------------------
// Other dialogs
// ----------------------------------------------------------------------

@Composable
fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AppDialog(
        title = "曲名",
        icon = Icons.Rounded.DriveFileRenameOutline,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("キャンセル", Icons.Rounded.Close, onDismiss)
            Spacer(Modifier.size(6.dp))
            DialogPrimaryButton(
                text = "変更",
                icon = Icons.Rounded.Check,
                onClick = { onApply(text.trim().ifBlank { "無題" }) },
            )
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(80) },
            label = { Text("タイトル") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "曲名は画面表示と書き出すファイル名の初期値に使われます。",
            style = MaterialTheme.typography.labelSmall,
            color = Mist,
        )
    }
}

@Composable
fun ConfirmNewDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AppDialog(
        title = "新しいプロジェクト",
        icon = Icons.AutoMirrored.Rounded.NoteAdd,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("キャンセル", Icons.Rounded.Close, onDismiss)
            Spacer(Modifier.size(6.dp))
            DialogPrimaryButton("作成", Icons.Rounded.Add, onClick = onConfirm)
        },
    ) {
        Text(
            "現在の編集内容は自動保存に上書きされます。必要な場合は先にMIDIを書き出してください。",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ----------------------------------------------------------------------
// Small shared controls
// ----------------------------------------------------------------------

@Composable
private fun DialogPrimaryButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(text)
    }
}

@Composable
private fun DialogSecondaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.size(5.dp))
        Text(text)
    }
}

@Composable
private fun StepSquare(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = Paper,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = if (label.length > 1) 13.sp else 21.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = Paper,
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
