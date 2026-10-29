package com.pianocanvas.midi.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Lightweight header used by the project browser. */
data class ProjectSummary(
    val id: String,
    val title: String,
    val savedAtMillis: Long,
    val noteCount: Int,
)

/** Which projects are open in tabs, and the shared input preferences. */
data class WorkspaceState(
    val openIds: List<String> = emptyList(),
    val activeIndex: Int = 0,
    val prefs: EditorPrefs = EditorPrefs(),
)

/**
 * Named, multi-project storage. Each project lives in its own JSON file so
 * projects can be listed, opened, renamed and deleted independently. This is a
 * superset of the legacy single-session autosave in [ProjectStore]; the old
 * session is migrated into a project on first launch.
 */
object ProjectRepository {
    private const val DIR = "projects"
    private const val MAX_FILE = 24 * 1024 * 1024
    private const val WORKSPACE = "workspace.json"

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
    private fun file(context: Context, id: String): File = File(dir(context), "$id.json")
    private fun workspaceFile(context: Context): File = File(context.filesDir, WORKSPACE)

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    fun exists(context: Context, id: String): Boolean = file(context, id).isFile

    fun list(context: Context): List<ProjectSummary> {
        val files = dir(context).listFiles { f: File ->
            f.isFile && f.name.endsWith(".json")
        } ?: return emptyList()
        return files.mapNotNull { f ->
            val id = f.name.removeSuffix(".json")
            readSummary(context, id)
        }.sortedByDescending { it.savedAtMillis }
    }

    private fun readSummary(context: Context, id: String): ProjectSummary? {
        val f = file(context, id)
        if (!f.isFile) return null
        return runCatching {
            if (f.length() > MAX_FILE) return null
            val root = JSONObject(f.readText(StandardCharsets.UTF_8))
            val summary = root.optJSONObject("summary")
            val project = root.optJSONObject("project")
            ProjectSummary(
                id = id,
                title = summary?.optString("title", "").orEmpty().ifBlank {
                    project?.optString("title", "無題") ?: "無題"
                },
                savedAtMillis = summary?.optLong("savedAt", 0L)
                    ?.takeIf { it > 0 } ?: f.lastModified(),
                noteCount = summary?.optInt("noteCount", 0) ?: 0,
            )
        }.getOrNull()
    }

    fun save(context: Context, id: String, data: AutosaveData): Result<Unit> {
        return try {
            val json = ProjectStore.encode(data).toString()
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_FILE) throw IOException("プロジェクトが大きすぎます")
            val tmp = File(dir(context), "$id.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            val target = file(context, id)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun load(context: Context, id: String): Result<AutosaveData?> {
        val f = file(context, id)
        if (!f.isFile) return Result.success(null)
        return try {
            if (f.length() > MAX_FILE) throw IOException("プロジェクトが大きすぎます")
            Result.success(ProjectStore.decode(f.readText(StandardCharsets.UTF_8)))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun rename(context: Context, id: String, title: String): Result<Unit> {
        val data = load(context, id).getOrNull() ?: return Result.failure(IOException("プロジェクトが見つかりません"))
        val clean = title.trim().take(80).ifBlank { "無題" }
        return save(
            context,
            id,
            data.copy(
                project = data.project.copy(title = clean),
                savedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    fun delete(context: Context, id: String) {
        file(context, id).delete()
        File(dir(context), "$id.tmp").delete()
    }

    /**
     * Moves the pre-multi-project autosave into a named project. Returns the new
     * id, or null when there is nothing to migrate.
     */
    fun migrateLegacy(context: Context): String? {
        if (list(context).isNotEmpty()) return null
        if (!ProjectStore.hasSession(context)) return null
        val data = ProjectStore.load(context).getOrNull() ?: return null
        val id = newId()
        save(context, id, data).getOrElse { return null }
        ProjectStore.clear(context)
        return id
    }

    // ---------------------------------------------------------------------
    // Workspace (open tabs + shared preferences)
    // ---------------------------------------------------------------------

    fun loadWorkspace(context: Context): WorkspaceState {
        val f = workspaceFile(context)
        if (!f.isFile) return WorkspaceState()
        return runCatching {
            val root = JSONObject(f.readText(StandardCharsets.UTF_8))
            val ids = ArrayList<String>()
            root.optJSONArray("open")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "")
                    if (v.isNotBlank()) ids += v
                }
            }
            WorkspaceState(
                openIds = ids,
                activeIndex = root.optInt("active", 0),
                prefs = ProjectStore.decodePrefs(root.optJSONObject("prefs")),
            )
        }.getOrElse { WorkspaceState() }
    }

    fun saveWorkspace(context: Context, state: WorkspaceState): Result<Unit> {
        return try {
            val root = JSONObject()
            val arr = JSONArray()
            state.openIds.forEach { arr.put(it) }
            root.put("open", arr)
            root.put("active", state.activeIndex)
            root.put("prefs", ProjectStore.encodePrefs(state.prefs))
            val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
            val tmp = File(workspaceFile(context).parentFile, "$WORKSPACE.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            val target = workspaceFile(context)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
