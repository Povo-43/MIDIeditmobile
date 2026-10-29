package com.pianocanvas.midi.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pianocanvas.midi.data.ProjectSummary
import com.pianocanvas.midi.editor.MidiUiState
import com.pianocanvas.midi.midi.MidiDeviceOption
import com.pianocanvas.midi.model.MidiMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ----------------------------------------------------------------------
// Project browser
// ----------------------------------------------------------------------

@Composable
fun ProjectBrowserDialog(
    projects: List<ProjectSummary>,
    loading: Boolean,
    activeId: String?,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }

    AppDialog(
        title = "プロジェクト",
        subtitle = "保存済みのプロジェクトを開く / 管理する",
        icon = Icons.Rounded.LibraryMusic,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("閉じる", Icons.Rounded.Close, onDismiss)
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onNew, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.size(6.dp))
                Text("新規")
            }
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.size(6.dp))
                Text("読み込む")
            }
        }
        Spacer(Modifier.height(12.dp))
        if (loading && projects.isEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(10.dp))
                Text("読み込み中…", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (projects.isEmpty()) {
            Text(
                "保存されたプロジェクトはまだありません。",
                style = MaterialTheme.typography.bodyMedium,
                color = Mist,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                projects.forEach { summary ->
                    ProjectBrowserRow(
                        summary = summary,
                        active = summary.id == activeId,
                        onOpen = { onOpen(summary.id) },
                        onRename = { renameTarget = summary },
                        onDelete = { deleteTarget = summary },
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameProjectDialog(
            initial = target.title,
            onDismiss = { renameTarget = null },
            onApply = { title ->
                onRename(target.id, title)
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { target ->
        AppDialog(
            title = "プロジェクトを削除",
            subtitle = target.title,
            icon = Icons.Rounded.Delete,
            onDismiss = { deleteTarget = null },
            actions = {
                DialogSecondaryButton("キャンセル", Icons.Rounded.Close) { deleteTarget = null }
                Spacer(Modifier.size(6.dp))
                DialogPrimaryButton("削除", Icons.Rounded.Delete) {
                    onDelete(target.id)
                    deleteTarget = null
                }
            },
        ) {
            Text(
                "アプリ内に保存されたこのプロジェクトを削除します。書き出した .mid ファイルは残ります。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ProjectBrowserRow(
    summary: ProjectSummary,
    active: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.onPrimary else Paper,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    summary.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${summary.noteCount} ノート ・ ${formatSavedAt(summary.savedAtMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Mist,
                )
            }
            IconButton(onClick = onRename, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.DriveFileRenameOutline,
                    contentDescription = "名前を変更",
                    tint = Paper,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "削除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RenameProjectDialog(
    initial: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AppDialog(
        title = "名前を変更",
        icon = Icons.Rounded.DriveFileRenameOutline,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("キャンセル", Icons.Rounded.Close, onDismiss)
            Spacer(Modifier.size(6.dp))
            DialogPrimaryButton("変更", Icons.Rounded.Check) {
                onApply(text.trim().ifBlank { "無題" })
            }
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(80) },
            label = { Text("タイトル") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatSavedAt(millis: Long): String {
    if (millis <= 0L) return "保存日時不明"
    val format = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)
    return format.format(Date(millis))
}

// ----------------------------------------------------------------------
// MIDI META editor
// ----------------------------------------------------------------------

@Composable
fun MidiMetaDialog(
    initial: MidiMeta,
    onDismiss: () -> Unit,
    onApply: (MidiMeta) -> Unit,
) {
    var copyright by remember { mutableStateOf(initial.copyright) }
    var composer by remember { mutableStateOf(initial.composer) }
    var lyricist by remember { mutableStateOf(initial.lyricist) }
    var comment by remember { mutableStateOf(initial.comment) }
    var keySignature by remember { mutableStateOf(initial.keySignature) }
    var minor by remember { mutableStateOf(initial.minor) }

    AppDialog(
        title = "MIDI META 設定",
        subtitle = "曲の著作権・キー・テキスト情報",
        icon = Icons.Rounded.GraphicEq,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("キャンセル", Icons.Rounded.Close, onDismiss)
            Spacer(Modifier.size(6.dp))
            DialogPrimaryButton("適用", Icons.Rounded.Check) {
                onApply(
                    MidiMeta(
                        copyright = copyright.trim(),
                        composer = composer.trim(),
                        lyricist = lyricist.trim(),
                        comment = comment.trim(),
                        keySignature = keySignature,
                        minor = minor,
                    )
                )
            }
        },
    ) {
        MetaField("作曲者 (Text)", composer) { composer = it }
        Spacer(Modifier.height(8.dp))
        MetaField("作詞者 (Text)", lyricist) { lyricist = it }
        Spacer(Modifier.height(8.dp))
        MetaField("コメント (Text)", comment) { comment = it }
        Spacer(Modifier.height(8.dp))
        MetaField("著作権 (Copyright)", copyright) { copyright = it }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("キー (Key Signature)", style = MaterialTheme.typography.labelMedium, color = Mist)
            Spacer(Modifier.weight(1f))
            Text(
                if (keySignature == null) "未設定"
                else keySignatureName(keySignature!!, minor),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            FilterChip(
                selected = keySignature == null,
                onClick = { keySignature = null },
                label = { Text("なし") },
                modifier = Modifier.heightIn(min = 40.dp),
            )
            for (sf in -7..7) {
                FilterChip(
                    selected = keySignature == sf,
                    onClick = { keySignature = sf },
                    label = { Text(keySignatureName(sf, minor)) },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("短調 (minor)", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = minor,
                onCheckedChange = { minor = it },
                enabled = keySignature != null,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "著作権・キー・テキストは書き出し時に曲の先頭 (トラック1) のメタイベントとして反映されます。",
            style = MaterialTheme.typography.labelSmall,
            color = Mist,
        )
    }
}

@Composable
private fun MetaField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(240)) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Human-readable key-signature label for -7..7 sharps/flats. */
private fun keySignatureName(sf: Int, minor: Boolean): String {
    val sharps = listOf("C", "G", "D", "A", "E", "B", "F#", "C#")
    val flats = listOf("C", "F", "B♭", "E♭", "A♭", "D♭", "G♭", "C♭")
    val root = if (sf >= 0) sharps[sf.coerceIn(0, 7)] else flats[(-sf).coerceIn(0, 7)]
    return root + if (minor) "m" else ""
}

// ----------------------------------------------------------------------
// External MIDI keyboard
// ----------------------------------------------------------------------

@Composable
fun MidiInputDialog(
    midi: MidiUiState,
    onDismiss: () -> Unit,
    onSelectDevice: (Int?) -> Unit,
    onToggleRecording: () -> Unit,
    onRefresh: () -> Unit,
) {
    AppDialog(
        title = "MIDIキーボード入力",
        subtitle = "USB / Bluetooth LE MIDI から録音",
        icon = Icons.Rounded.Usb,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("閉じる", Icons.Rounded.Close, onDismiss)
        },
    ) {
        if (!midi.supported) {
            Text(
                "この端末は Android MIDI API に対応していません。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            return@AppDialog
        }

        // Recording arm switch.
        Surface(
            color = if (midi.recording) MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(
                1.dp,
                if (midi.recording) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.outline,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = if (midi.recording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (midi.recording) "録音中" else "録音待機",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (midi.recording) MaterialTheme.colorScheme.error else Paper,
                    )
                    Text(
                        if (midi.recording) "鍵盤を弾くと再生位置に記録されます"
                        else "録音をオンにすると入力した音符が記録されます",
                        style = MaterialTheme.typography.labelSmall,
                        color = Mist,
                    )
                }
                Switch(checked = midi.recording, onCheckedChange = { onToggleRecording() })
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("入力デバイス", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Refresh, contentDescription = "再検索", tint = Paper)
            }
        }
        Spacer(Modifier.height(4.dp))

        val receiveDevices = midi.devices.filter { it.canReceive }
        if (receiveDevices.isEmpty()) {
            Text(
                "MIDIキーボードが見つかりません。USB接続、またはBluetooth MIDIでペアリングしてください。",
                style = MaterialTheme.typography.bodyMedium,
                color = Mist,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                receiveDevices.forEach { device ->
                    MidiDeviceRow(
                        device = device,
                        selected = device.name == midi.connectedName,
                        onClick = { onSelectDevice(device.id) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onSelectDevice(null) }, enabled = midi.connected) {
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(5.dp))
                Text("切断")
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "録音をオンにして再生しながら弾くと、入力した音符が再生位置に追加されます。" +
                "停止中は入力長ぶんの長さで記録されます。サステインペダル (CC64) は試聴に反映されます。",
            style = MaterialTheme.typography.labelSmall,
            color = Mist,
        )
    }
}

@Composable
private fun MidiDeviceRow(
    device: MidiDeviceOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Usb,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Paper,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append("入力")
                        if (device.canSend) append(" / 出力")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Mist,
                )
            }
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "接続中",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
