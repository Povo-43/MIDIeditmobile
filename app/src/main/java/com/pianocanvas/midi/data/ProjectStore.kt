package com.pianocanvas.midi.data

import android.content.Context
import com.pianocanvas.midi.model.GridUnit
import com.pianocanvas.midi.model.MidiMeta
import com.pianocanvas.midi.model.Note
import com.pianocanvas.midi.model.Project
import com.pianocanvas.midi.model.RawEvent
import com.pianocanvas.midi.model.TimeSignature
import com.pianocanvas.midi.model.TimeSignaturePoint
import com.pianocanvas.midi.model.Track
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

data class ViewportState(
    val startTick: Double = 0.0,
    /** Horizontal zoom, in dp per MIDI tick. */
    val dpPerTick: Float = 0.12f,
    /** Lowest MIDI pitch visible at the bottom edge. */
    val lowestPitch: Float = 36f,
    /** Vertical zoom, in dp per semitone. */
    val rowHeightDp: Float = 22f,
)

data class EditorPrefs(
    val grid: GridUnit = GridUnit.EIGHTH,
    val inputDuration: GridUnit = GridUnit.QUARTER,
    val snapEnabled: Boolean = true,
    val inputVelocity: Int = 96,
    val autoScroll: Boolean = true,
    val hoverToolbar: Boolean = true,
    val fingerEditing: Boolean = true,
    val penOnlySelection: Boolean = false,
    val showSustainLane: Boolean = false,
)

data class AutosaveData(
    val project: Project,
    val viewport: ViewportState,
    val prefs: EditorPrefs,
    val savedAtMillis: Long,
)

/**
 * Atomic JSON autosave in app-private storage. This is deliberately separate
 * from SAF import/export: editing never writes to the user's .mid file.
 */
object ProjectStore {
    private const val VERSION = 2
    private const val MAX_FILE = 24 * 1024 * 1024

    private fun dir(context: Context): File = File(context.filesDir, "autosave").apply { mkdirs() }
    private fun file(context: Context): File = File(dir(context), "session.json")
    private fun backup(context: Context): File = File(dir(context), "session.bak.json")
    private fun temp(context: Context): File = File(dir(context), "session.tmp.json")

    fun hasSession(context: Context): Boolean =
        file(context).isFile || backup(context).isFile

    fun save(context: Context, data: AutosaveData): Result<Unit> {
        return try {
            val json = encode(data).toString()
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_FILE) throw IOException("自動保存データが大きすぎます")
            val tmp = temp(context)
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            val target = file(context)
            if (target.exists()) {
                val bak = backup(context)
                if (bak.exists()) bak.delete()
                if (!target.renameTo(bak)) {
                    target.copyTo(bak, overwrite = true)
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun load(context: Context): Result<AutosaveData?> {
        val primary = file(context)
        val bak = backup(context)
        val candidate = when {
            primary.isFile -> primary
            bak.isFile -> bak
            else -> return Result.success(null)
        }
        return try {
            if (candidate.length() > MAX_FILE) throw IOException("自動保存データが大きすぎます")
            val text = candidate.readText(StandardCharsets.UTF_8)
            Result.success(decode(text))
        } catch (t: Throwable) {
            if (candidate == primary && bak.isFile) {
                runCatching { Result.success(decode(bak.readText(StandardCharsets.UTF_8))) }
                    .getOrElse { Result.failure(t) }
            } else {
                Result.failure(t)
            }
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        backup(context).delete()
        temp(context).delete()
    }

    fun lastSavedAt(context: Context): Long? =
        maxOf(file(context).lastModified(), backup(context).lastModified()).takeIf { it > 0 }

    internal fun encode(data: AutosaveData): JSONObject {
        val p = data.project
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("savedAt", data.savedAtMillis)
        // Cheap header so the project browser can list titles without decoding
        // every track of every stored project.
        root.put(
            "summary",
            JSONObject()
                .put("title", p.title)
                .put("noteCount", p.tracks.sumOf { it.notes.size })
                .put("savedAt", data.savedAtMillis),
        )
        val project = JSONObject()
        project.put("title", p.title)
        project.put("ppq", p.ppq)
        project.put("activeTrack", p.activeTrack)
        project.put("bpm", p.bpm)
        project.put("timeSignature", JSONArray().put(p.timeSignature.numerator).put(p.timeSignature.denominator))
        val sigs = JSONArray()
        p.timeSignatures.forEach { s ->
            sigs.put(JSONObject().put("tick", s.tick).put("n", s.signature.numerator).put("d", s.signature.denominator))
        }
        project.put("timeSignatures", sigs)
        project.put("loopStart", p.loopStartTick)
        project.put("loopEnd", p.loopEndTick)
        project.put("loopEnabled", p.loopEnabled)
        project.put("nextNoteId", p.nextNoteId)
        p.importedPathName?.let { project.put("importedPathName", it) }
        project.put("meta", encodeMeta(p.meta))

        val tracks = JSONArray()
        p.tracks.forEach { t ->
            val track = JSONObject()
            track.put("name", t.name)
            track.put("channel", t.channel)
            track.put("isDrum", t.isDrum)
            val notes = JSONArray()
            t.notes.forEach { n ->
                notes.put(
                    JSONObject()
                        .put("id", n.id)
                        .put("p", n.pitch)
                        .put("s", n.startTick)
                        .put("d", n.durationTicks)
                        .put("v", n.velocity)
                        .put("c", n.channel)
                )
            }
            track.put("notes", notes)
            val events = JSONArray()
            t.rawEvents.forEach { e ->
                val dataArr = JSONArray()
                e.data.forEach { dataArr.put(it) }
                events.put(JSONObject().put("t", e.tick).put("s", e.status).put("d", dataArr))
            }
            track.put("events", events)
            tracks.put(track)
        }
        project.put("tracks", tracks)
        root.put("project", project)

        val vp = JSONObject()
        vp.put("startTick", data.viewport.startTick)
        vp.put("dpPerTick", data.viewport.dpPerTick)
        vp.put("lowPitch", data.viewport.lowestPitch)
        vp.put("rowHeightDp", data.viewport.rowHeightDp)
        root.put("viewport", vp)

        root.put("prefs", encodePrefs(data.prefs))
        return root
    }

    internal fun encodePrefs(prefs: EditorPrefs): JSONObject {
        val json = JSONObject()
        json.put("grid", prefs.grid.name)
        json.put("input", prefs.inputDuration.name)
        json.put("snap", prefs.snapEnabled)
        json.put("velocity", prefs.inputVelocity)
        json.put("autoScroll", prefs.autoScroll)
        json.put("hoverToolbar", prefs.hoverToolbar)
        json.put("fingerEditing", prefs.fingerEditing)
        json.put("penOnlySelection", prefs.penOnlySelection)
        json.put("showSustainLane", prefs.showSustainLane)
        return json
    }

    private fun encodeMeta(meta: MidiMeta): JSONObject {
        val json = JSONObject()
        json.put("copyright", meta.copyright)
        json.put("composer", meta.composer)
        json.put("lyricist", meta.lyricist)
        json.put("comment", meta.comment)
        meta.keySignature?.let {
            json.put("keySignature", it.coerceIn(-7, 7))
            json.put("minor", meta.minor)
        }
        return json
    }

    private fun decodeMeta(json: JSONObject?): MidiMeta {
        if (json == null) return MidiMeta()
        return MidiMeta(
            copyright = json.optString("copyright", ""),
            composer = json.optString("composer", ""),
            lyricist = json.optString("lyricist", ""),
            comment = json.optString("comment", ""),
            keySignature = if (json.has("keySignature") && !json.isNull("keySignature")) {
                json.optInt("keySignature", 0).coerceIn(-7, 7)
            } else null,
            minor = json.optBoolean("minor", false),
        )
    }

    internal fun decode(text: String): AutosaveData {
        val root = JSONObject(text)
        val version = root.optInt("version", 0)
        if (version > VERSION) throw JSONException("新しい形式の自動保存データです (version $version)")
        val p = root.getJSONObject("project")
        val ppq = p.optInt("ppq", 480).let { if (it in 1..32767) it else 480 }
        val tracks = ArrayList<Track>()
        val trackArray = p.optJSONArray("tracks") ?: JSONArray()
        var maxId = 0L
        for (i in 0 until trackArray.length()) {
            val tj = trackArray.optJSONObject(i) ?: continue
            val notes = ArrayList<Note>()
            val noteArray = tj.optJSONArray("notes") ?: JSONArray()
            for (n in 0 until noteArray.length()) {
                val nj = noteArray.optJSONObject(n) ?: continue
                val id = nj.optLong("id", 0)
                val pitch = nj.optInt("p", -1)
                val start = nj.optInt("s", -1)
                val duration = nj.optInt("d", 0)
                val velocity = nj.optInt("v", 96)
                val channel = nj.optInt("c", 0)
                if (pitch !in 0..127 || start < 0 || duration <= 0) continue
                val newId = if (id > 0) id else (maxId + 1)
                maxId = maxOf(maxId, newId)
                notes += Note(
                    id = newId,
                    pitch = pitch,
                    startTick = start,
                    durationTicks = duration,
                    velocity = velocity.coerceIn(1, 127),
                    channel = channel.coerceIn(0, 15),
                )
            }
            val events = ArrayList<RawEvent>()
            val eventArray = tj.optJSONArray("events") ?: JSONArray()
            for (e in 0 until eventArray.length()) {
                val ej = eventArray.optJSONObject(e) ?: continue
                val tick = ej.optInt("t", -1)
                val status = ej.optInt("s", -1)
                val arr = ej.optJSONArray("d") ?: continue
                if (tick < 0 || status < 0) continue
                val data = IntArray(arr.length()) { arr.optInt(it, 0) }
                events += RawEvent(tick, status, data)
            }
            tracks += Track(
                name = tj.optString("name", "Piano"),
                channel = tj.optInt("channel", 0).coerceIn(0, 15),
                notes = notes.sortedWith(compareBy({ it.startTick }, { it.pitch })),
                rawEvents = events.sortedBy { it.tick },
                isDrum = tj.optBoolean("isDrum", false),
            )
        }
        if (tracks.isEmpty()) {
            tracks += Track("Piano", 0)
        }
        val ts = p.optJSONArray("timeSignature")
        val numerator = ts?.optInt(0, 4) ?: 4
        val denominator = ts?.optInt(1, 4) ?: 4
        val timeSig = runCatching { TimeSignature(numerator, denominator) }
            .getOrElse { TimeSignature(4, 4) }
        val sigs = ArrayList<TimeSignaturePoint>()
        p.optJSONArray("timeSignatures")?.let { arr ->
            for (i in 0 until arr.length()) {
                val sj = arr.optJSONObject(i) ?: continue
                val n = sj.optInt("n", 4)
                val d = sj.optInt("d", 4)
                val sig = runCatching { TimeSignature(n, d) }.getOrNull() ?: continue
                sigs += TimeSignaturePoint(sj.optInt("tick", 0), sig)
            }
        }
        if (sigs.isEmpty()) sigs += TimeSignaturePoint(0, timeSig)
        val notes = tracks.flatMap { it.notes }
        val project = Project(
            title = p.optString("title", "無題"),
            ppq = ppq,
            tracks = tracks,
            activeTrack = p.optInt("activeTrack", 0).coerceIn(0, tracks.size - 1),
            bpm = p.optDouble("bpm", 120.0).takeIf { it in 10.0..1000.0 } ?: 120.0,
            timeSignature = timeSig,
            timeSignatures = sigs.sortedBy { it.tick },
            loopStartTick = p.optInt("loopStart", 0).coerceAtLeast(0),
            loopEndTick = p.optInt("loopEnd", ppq * 16).coerceAtLeast(1),
            loopEnabled = p.optBoolean("loopEnabled", false),
            nextNoteId = maxOf(p.optLong("nextNoteId", 1), (notes.maxOfOrNull { it.id } ?: 0) + 1),
            importedPathName = p.optString("importedPathName", "").takeIf { it.isNotBlank() },
            meta = decodeMeta(p.optJSONObject("meta")),
        ).let { pr ->
            if (pr.loopEndTick <= pr.loopStartTick) pr.copy(loopEndTick = pr.loopStartTick + ppq * 4) else pr
        }

        val vpJson = root.optJSONObject("viewport")
        val vp = if (vpJson != null) {
            ViewportState(
                startTick = vpJson.optDouble("startTick", 0.0).coerceAtLeast(0.0),
                dpPerTick = vpJson.optDouble("dpPerTick", 0.12).toFloat().coerceIn(0.01f, 3f),
                lowestPitch = vpJson.optDouble("lowPitch", 36.0).toFloat().coerceIn(0f, 115f),
                rowHeightDp = vpJson.optDouble("rowHeightDp", 22.0).toFloat().coerceIn(8f, 80f),
            )
        } else ViewportState()

        val prefs = decodePrefs(root.optJSONObject("prefs"))
        return AutosaveData(project, vp, prefs, root.optLong("savedAt", 0L))
    }

    internal fun decodePrefs(json: JSONObject?): EditorPrefs {
        if (json == null) return EditorPrefs()
        fun grid(name: String?, fallback: GridUnit): GridUnit =
            GridUnit.entries.firstOrNull { it.name == name } ?: fallback
        return EditorPrefs(
            grid = grid(json.optString("grid"), GridUnit.EIGHTH),
            inputDuration = grid(json.optString("input"), GridUnit.QUARTER),
            snapEnabled = json.optBoolean("snap", true),
            inputVelocity = json.optInt("velocity", 96).coerceIn(1, 127),
            autoScroll = json.optBoolean("autoScroll", true),
            hoverToolbar = json.optBoolean("hoverToolbar", true),
            fingerEditing = json.optBoolean("fingerEditing", true),
            penOnlySelection = json.optBoolean("penOnlySelection", false),
            showSustainLane = json.optBoolean("showSustainLane", false),
        )
    }
}
