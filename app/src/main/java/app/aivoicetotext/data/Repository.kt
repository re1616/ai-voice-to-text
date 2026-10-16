package app.aivoicetotext.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.aivoicetotext.util.TextNorm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

enum class NoteFilter { ALL, PINNED, TRASH }

/**
 * Single access point to the SQLite database. Every write bumps [changes]; observable
 * queries re-run on each bump (simple and good enough for a personal notes database).
 */
class Repository(private val helper: DbHelper) {

    private val changes = MutableStateFlow(0L)
    private val db: SQLiteDatabase get() = helper.writableDatabase

    private fun bump() { changes.value = changes.value + 1 }

    private fun <T> observe(query: () -> T): Flow<T> =
        changes.map { query() }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun close() = helper.close()

    // ------------------------------------------------------------------ notes

    fun createNote(title: String, language: String, status: String, audioPath: String?, pcmPath: String?, folderId: Long? = null): Long {
        val now = System.currentTimeMillis()
        val id = db.insert("notes", null, ContentValues().apply {
            put("title", title); put("created_at", now); put("updated_at", now)
            put("language", language); put("status", status)
            put("audio_path", audioPath); put("pcm_path", pcmPath)
            if (folderId != null) put("folder_id", folderId)
        })
        bump()
        return id
    }

    fun updateNote(id: Long, values: ContentValues, notify: Boolean = true) {
        values.put("updated_at", System.currentTimeMillis())
        db.update("notes", values, "id=?", arrayOf(id.toString()))
        if (notify) bump()
    }

    fun setStatus(id: Long, status: String, error: String? = null) =
        updateNote(id, ContentValues().apply { put("status", status); put("error", error) })

    fun rename(id: Long, title: String) {
        updateNote(id, ContentValues().apply { put("title", title) })
        reindex(id)
    }

    fun setPinned(id: Long, pinned: Boolean) = updateNote(id, ContentValues().apply { put("pinned", if (pinned) 1 else 0) })
    fun setFolder(id: Long, folderId: Long?) = updateNote(id, ContentValues().apply { if (folderId == null) putNull("folder_id") else put("folder_id", folderId) })
    fun setTags(id: Long, tags: List<String>) = updateNote(id, ContentValues().apply { put("tags", tags.joinToString(",") { it.trim() }.trim(',')) })
    fun moveToTrash(id: Long) = updateNote(id, ContentValues().apply { put("deleted_at", System.currentTimeMillis()) })
    fun restore(id: Long) = updateNote(id, ContentValues().apply { putNull("deleted_at") })

    fun deleteForever(id: Long): Note? {
        val n = note(id)
        db.delete("notes", "id=?", arrayOf(id.toString()))
        db.delete("notes_fts", "docid=?", arrayOf(id.toString()))
        bump()
        return n
    }

    fun note(id: Long): Note? =
        db.rawQuery("SELECT * FROM notes WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.toNote() else null }

    fun observeNote(id: Long): Flow<Note?> = observe { note(id) }

    fun notesWithStatus(vararg statuses: String): List<Note> {
        val q = statuses.joinToString(",") { "?" }
        return db.rawQuery("SELECT * FROM notes WHERE status IN ($q)", statuses).use { c -> c.list { it.toNote() } }
    }

    fun trashOlderThan(time: Long): List<Note> =
        db.rawQuery("SELECT * FROM notes WHERE deleted_at IS NOT NULL AND deleted_at < ?", arrayOf(time.toString())).use { c -> c.list { it.toNote() } }

    fun allNotes(): List<Note> = db.rawQuery("SELECT * FROM notes ORDER BY created_at DESC", null).use { c -> c.list { it.toNote() } }

    fun observeNotes(filter: NoteFilter, folderId: Long?, tag: String?, query: String): Flow<List<Note>> = observe {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        when (filter) {
            NoteFilter.TRASH -> where += "deleted_at IS NOT NULL"
            NoteFilter.PINNED -> { where += "deleted_at IS NULL"; where += "pinned=1" }
            NoteFilter.ALL -> where += "deleted_at IS NULL"
        }
        if (folderId != null) { where += "folder_id=?"; args += folderId.toString() }
        if (!tag.isNullOrBlank()) { where += "(',' || tags || ',') LIKE ?"; args += "%,$tag,%" }
        val ftsQuery = TextNorm.ftsQuery(query)
        if (ftsQuery != null) { where += "id IN (SELECT docid FROM notes_fts WHERE notes_fts MATCH ?)"; args += ftsQuery }
        val sql = "SELECT * FROM notes WHERE ${where.joinToString(" AND ")} ORDER BY pinned DESC, created_at DESC"
        try {
            db.rawQuery(sql, args.toTypedArray()).use { c -> c.list { it.toNote() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun allTags(): Flow<List<String>> = observe {
        db.rawQuery("SELECT tags FROM notes WHERE deleted_at IS NULL AND tags != ''", null).use { c ->
            c.list { it.getString(0) }.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        }
    }

    // ------------------------------------------------------------------ folders

    fun observeFolders(): Flow<List<Folder>> = observe {
        db.rawQuery("SELECT id, name FROM folders ORDER BY name", null).use { c -> c.list { Folder(it.getLong(0), it.getString(1)) } }
    }

    fun createFolder(name: String): Long = db.insert("folders", null, ContentValues().apply { put("name", name) }).also { bump() }
    fun deleteFolder(id: Long) { db.delete("folders", "id=?", arrayOf(id.toString())); bump() }

    // ------------------------------------------------------------------ segments

    fun segments(noteId: Long): List<Segment> =
        db.rawQuery("SELECT * FROM segments WHERE note_id=? ORDER BY start_ms, id", arrayOf(noteId.toString())).use { c -> c.list { it.toSegment() } }

    fun observeSegments(noteId: Long): Flow<List<Segment>> = observe { segments(noteId) }

    fun insertSegments(noteId: Long, items: List<Segment>, draft: Boolean) {
        db.beginTransaction()
        try {
            for (s in items) {
                db.insert("segments", null, ContentValues().apply {
                    put("note_id", noteId); put("start_ms", s.startMs); put("end_ms", s.endMs)
                    put("text", s.text); put("draft", if (draft) 1 else 0)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshSnippet(noteId)
        bump()
    }

    /** Removes draft (live) segments that are now covered by the final transcription. */
    fun deleteDraftsBefore(noteId: Long, timeMs: Long) {
        db.delete("segments", "note_id=? AND draft=1 AND start_ms<?", arrayOf(noteId.toString(), timeMs.toString()))
    }

    fun deleteDrafts(noteId: Long) {
        db.delete("segments", "note_id=? AND draft=1", arrayOf(noteId.toString()))
        bump()
    }

    /** Called when a (re)transcription restarts: removes unedited final segments from [fromMs]. */
    fun deleteFinalSegmentsFrom(noteId: Long, fromMs: Long) {
        db.delete("segments", "note_id=? AND draft=0 AND start_ms>=?", arrayOf(noteId.toString(), fromMs.toString()))
        bump()
    }

    fun deleteAllSegments(noteId: Long) {
        db.delete("segments", "note_id=?", arrayOf(noteId.toString()))
        refreshSnippet(noteId)
        bump()
    }

    fun updateSegmentText(id: Long, noteId: Long, text: String) {
        db.update("segments", ContentValues().apply { put("text", text); put("edited", 1) }, "id=?", arrayOf(id.toString()))
        refreshSnippet(noteId)
        reindex(noteId)
        bump()
    }

    fun deleteSegment(id: Long, noteId: Long) {
        db.delete("segments", "id=?", arrayOf(id.toString()))
        refreshSnippet(noteId)
        reindex(noteId)
        bump()
    }

    fun mergeWithNext(seg: Segment) {
        val all = segments(seg.noteId)
        val i = all.indexOfFirst { it.id == seg.id }
        val next = all.getOrNull(i + 1) ?: return
        if (i < 0) return
        db.beginTransaction()
        try {
            db.update("segments", ContentValues().apply {
                put("text", (seg.text.trim() + " " + next.text.trim()).trim())
                put("end_ms", maxOf(seg.endMs, next.endMs)); put("edited", 1)
            }, "id=?", arrayOf(seg.id.toString()))
            db.delete("segments", "id=?", arrayOf(next.id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        reindex(seg.noteId)
        bump()
    }

    private fun refreshSnippet(noteId: Long) {
        val text = db.rawQuery("SELECT text FROM segments WHERE note_id=? ORDER BY start_ms LIMIT 6", arrayOf(noteId.toString()))
            .use { c -> c.list { it.getString(0).trim() } }.joinToString(" ").take(240)
        db.update("notes", ContentValues().apply { put("snippet", text) }, "id=?", arrayOf(noteId.toString()))
    }

    // ------------------------------------------------------------------ markers

    fun addMarker(noteId: Long, timeMs: Long) {
        db.insert("markers", null, ContentValues().apply { put("note_id", noteId); put("time_ms", timeMs) })
        bump()
    }

    fun observeMarkers(noteId: Long): Flow<List<Marker>> = observe {
        db.rawQuery("SELECT id, note_id, time_ms FROM markers WHERE note_id=? ORDER BY time_ms", arrayOf(noteId.toString()))
            .use { c -> c.list { Marker(it.getLong(0), it.getLong(1), it.getLong(2)) } }
    }

    // ------------------------------------------------------------------ templates

    fun templates(): List<PromptTemplate> =
        db.rawQuery("SELECT * FROM templates ORDER BY sort, id", null).use { c -> c.list { it.toTemplate() } }

    fun observeTemplates(): Flow<List<PromptTemplate>> = observe { templates() }
    fun template(id: Long): PromptTemplate? = templates().firstOrNull { it.id == id }

    fun saveTemplate(t: PromptTemplate): Long {
        val v = ContentValues().apply {
            put("name", t.name); put("emoji", t.emoji); put("system_prompt", t.systemPrompt)
            put("kind", t.kind); put("mode", t.mode); put("temperature", t.temperature)
            put("builtin", if (t.builtin) 1 else 0); put("sort", t.sort)
        }
        val id = if (t.id == 0L) db.insert("templates", null, v) else {
            db.update("templates", v, "id=?", arrayOf(t.id.toString())); t.id
        }
        bump()
        return id
    }

    fun deleteTemplate(id: Long) { db.delete("templates", "id=?", arrayOf(id.toString())); bump() }

    // ------------------------------------------------------------------ AI results

    fun createAiResult(noteId: Long, t: PromptTemplate, modelId: String): Long {
        val id = db.insert("ai_results", null, ContentValues().apply {
            put("note_id", noteId); put("template_id", t.id); put("title", "${t.emoji} ${t.name}")
            put("kind", t.kind); put("model_id", modelId); put("status", AiStatus.QUEUED)
            put("created_at", System.currentTimeMillis())
        })
        bump()
        return id
    }

    fun aiResult(id: Long): AiResult? =
        db.rawQuery("SELECT * FROM ai_results WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.toAi() else null }

    fun aiResults(noteId: Long): List<AiResult> =
        db.rawQuery("SELECT * FROM ai_results WHERE note_id=? ORDER BY created_at DESC", arrayOf(noteId.toString())).use { c -> c.list { it.toAi() } }

    fun observeAiResults(noteId: Long): Flow<List<AiResult>> = observe { aiResults(noteId) }

    fun aiResultsWithStatus(vararg statuses: String): List<AiResult> {
        val q = statuses.joinToString(",") { "?" }
        return db.rawQuery("SELECT * FROM ai_results WHERE status IN ($q)", statuses).use { c -> c.list { it.toAi() } }
    }

    fun updateAi(id: Long, status: String? = null, content: String? = null, progress: Int? = null, total: Int? = null, error: String? = null) {
        val v = ContentValues()
        status?.let { v.put("status", it) }
        content?.let { v.put("content", it) }
        progress?.let { v.put("progress", it) }
        total?.let { v.put("total", it) }
        if (error != null || status == AiStatus.DONE) v.put("error", error)
        if (v.size() == 0) return
        db.update("ai_results", v, "id=?", arrayOf(id.toString()))
        bump()
    }

    fun deleteAi(id: Long, noteId: Long) {
        db.delete("ai_results", "id=?", arrayOf(id.toString()))
        reindex(noteId)
        bump()
    }

    fun chunkOutput(resultId: Long, level: Int, idx: Int): String? =
        db.rawQuery("SELECT output FROM ai_chunks WHERE result_id=? AND level=? AND idx=?", arrayOf(resultId.toString(), level.toString(), idx.toString()))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun saveChunkOutput(resultId: Long, level: Int, idx: Int, output: String) {
        db.insertWithOnConflict("ai_chunks", null, ContentValues().apply {
            put("result_id", resultId); put("level", level); put("idx", idx); put("output", output)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearChunks(resultId: Long) = db.delete("ai_chunks", "result_id=?", arrayOf(resultId.toString()))

    // ------------------------------------------------------------------ search index

    fun reindex(noteId: Long) {
        val n = note(noteId) ?: return
        val body = buildString {
            segments(noteId).forEach { append(it.text).append(' ') }
            aiResults(noteId).forEach { append(it.content).append(' ') }
        }
        db.delete("notes_fts", "docid=?", arrayOf(noteId.toString()))
        db.insert("notes_fts", null, ContentValues().apply {
            put("docid", noteId)
            put("title", TextNorm.normalize(n.title))
            put("body", TextNorm.normalize(body))
        })
    }

    // ------------------------------------------------------------------ mapping

    private inline fun <T> Cursor.list(map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count)
        while (moveToNext()) out += map(this)
        return out
    }

    private fun Cursor.str(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.longOrNull(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.toNote() = Note(
        id = long("id"),
        title = str("title") ?: "",
        createdAt = long("created_at"),
        durationMs = long("duration_ms"),
        audioPath = str("audio_path"),
        pcmPath = str("pcm_path"),
        language = str("language") ?: "auto",
        detectedLanguage = str("detected_lang"),
        folderId = longOrNull("folder_id"),
        tags = (str("tags") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() },
        pinned = long("pinned") == 1L,
        deletedAt = longOrNull("deleted_at"),
        status = str("status") ?: NoteStatus.DONE,
        progressMs = long("progress_ms"),
        error = str("error"),
        modelId = str("model_id"),
        speed = getDouble(getColumnIndexOrThrow("speed")),
        snippet = str("snippet") ?: "",
        updatedAt = long("updated_at"),
    )

    private fun Cursor.toSegment() = Segment(
        id = long("id"), noteId = long("note_id"), startMs = long("start_ms"), endMs = long("end_ms"),
        text = str("text") ?: "", draft = long("draft") == 1L, edited = long("edited") == 1L,
    )

    private fun Cursor.toTemplate() = PromptTemplate(
        id = long("id"), name = str("name") ?: "", emoji = str("emoji") ?: "", systemPrompt = str("system_prompt") ?: "",
        kind = str("kind") ?: TemplateKind.TEXT, mode = str("mode") ?: TemplateMode.AGGREGATE,
        temperature = getFloat(getColumnIndexOrThrow("temperature")), builtin = long("builtin") == 1L, sort = long("sort").toInt(),
    )

    private fun Cursor.toAi() = AiResult(
        id = long("id"), noteId = long("note_id"), templateId = long("template_id"), title = str("title") ?: "",
        kind = str("kind") ?: TemplateKind.TEXT, content = str("content") ?: "", modelId = str("model_id"),
        status = str("status") ?: AiStatus.DONE, progress = long("progress").toInt(), total = long("total").toInt(),
        error = str("error"), createdAt = long("created_at"),
    )
}
