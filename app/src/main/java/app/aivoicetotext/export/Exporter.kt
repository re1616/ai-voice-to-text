package app.aivoicetotext.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import app.aivoicetotext.App
import app.aivoicetotext.data.AiStatus
import app.aivoicetotext.data.DbHelper
import app.aivoicetotext.data.Note
import app.aivoicetotext.data.Segment
import app.aivoicetotext.util.Fmt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class ExportFormat(val ext: String, val mime: String, val label: String) {
    MD("md", "text/markdown", "Markdown (.md)"),
    TXT("txt", "text/plain", "Текст (.txt)"),
    SRT("srt", "application/x-subrip", "Субтитры SRT"),
    VTT("vtt", "text/vtt", "Субтитры WebVTT"),
    JSON("json", "application/json", "JSON"),
}

object Exporter {

    fun render(app: App, noteId: Long, format: ExportFormat, timestamps: Boolean = true): String {
        val note = app.repo.note(noteId) ?: return ""
        val segs = app.repo.segments(noteId).filter { !it.draft }
        val ai = app.repo.aiResults(noteId).filter { it.status == AiStatus.DONE }
        return when (format) {
            ExportFormat.MD -> markdown(note, segs, ai.map { it.title to it.content }, timestamps)
            ExportFormat.TXT -> buildString {
                appendLine(note.title)
                appendLine()
                segs.forEach { if (timestamps) appendLine("[${Fmt.clock(it.startMs)}] ${it.text}") else appendLine(it.text) }
            }
            ExportFormat.SRT -> buildString {
                segs.forEachIndexed { i, s ->
                    appendLine(i + 1)
                    appendLine("${Fmt.srt(s.startMs)} --> ${Fmt.srt(s.endMs)}")
                    appendLine(s.text)
                    appendLine()
                }
            }
            ExportFormat.VTT -> buildString {
                appendLine("WEBVTT")
                appendLine()
                segs.forEach { s ->
                    appendLine("${Fmt.srt(s.startMs, '.')} --> ${Fmt.srt(s.endMs, '.')}")
                    appendLine(s.text)
                    appendLine()
                }
            }
            ExportFormat.JSON -> JSONObject().apply {
                put("title", note.title)
                put("created", Fmt.isoDate(note.createdAt))
                put("durationMs", note.durationMs)
                put("language", note.detectedLanguage ?: note.language)
                put("tags", JSONArray(note.tags))
                put("segments", JSONArray().apply {
                    segs.forEach { put(JSONObject().put("start", it.startMs).put("end", it.endMs).put("text", it.text)) }
                })
                put("ai", JSONArray().apply { ai.forEach { put(JSONObject().put("title", it.title).put("content", it.content)) } })
            }.toString(2)
        }
    }

    private fun markdown(note: Note, segs: List<Segment>, ai: List<Pair<String, String>>, timestamps: Boolean) = buildString {
        appendLine("---")
        appendLine("title: \"${note.title.replace("\"", "'")}\"")
        appendLine("date: ${Fmt.isoDate(note.createdAt)}")
        appendLine("duration: ${Fmt.clock(note.durationMs)}")
        if (note.tags.isNotEmpty()) appendLine("tags: [${note.tags.joinToString(", ")}]")
        appendLine("language: ${note.detectedLanguage ?: note.language}")
        appendLine("source: AI Voice to Text")
        appendLine("---")
        appendLine()
        appendLine("# ${note.title}")
        appendLine()
        for ((title, content) in ai) {
            appendLine("## $title")
            appendLine()
            appendLine(content.trim())
            appendLine()
        }
        if (ai.isNotEmpty()) { appendLine("## Расшифровка"); appendLine() }
        // group segments into paragraphs of ~45 s
        var paraStart = -1L
        val para = StringBuilder()
        fun flush() {
            if (para.isEmpty()) return
            if (timestamps) append("**[${Fmt.clock(paraStart)}]** ")
            appendLine(para.toString().trim())
            appendLine()
            para.clear()
        }
        for (s in segs) {
            if (paraStart < 0) paraStart = s.startMs
            if (s.startMs - paraStart > 45_000 && para.isNotEmpty()) { flush(); paraStart = s.startMs }
            para.append(s.text.trim()).append(' ')
        }
        flush()
    }

    /** Writes the export into the cache and returns a share intent. */
    fun shareIntent(app: App, noteId: Long, format: ExportFormat, timestamps: Boolean = true): Intent {
        val note = app.repo.note(noteId)
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, Fmt.safeFileName(note?.title ?: "note") + "." + format.ext)
        file.writeText(render(app, noteId, format, timestamps))
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(format.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, note?.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Экспорт заметки")
    }

    fun shareAudioIntent(app: App, noteId: Long): Intent? {
        val note = app.repo.note(noteId) ?: return null
        val f = File(note.audioPath ?: return null)
        if (!f.exists()) return null
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", f)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Отправить аудио",
        )
    }

    /** Obsidian-friendly auto export: <folder>/<title> (<id>).md, rewritten on every change. */
    fun autoExport(app: App, noteId: Long): Boolean {
        val uriStr = app.prefs.value.autoExportUri ?: return false
        val note = app.repo.note(noteId) ?: return false
        val tree = DocumentFile.fromTreeUri(app, Uri.parse(uriStr)) ?: return false
        if (!tree.canWrite()) return false
        val name = "${Fmt.safeFileName(note.title)} ($noteId).md"
        val existing = tree.listFiles().firstOrNull { it.name?.endsWith("($noteId).md") == true }
        val file = when {
            existing == null -> tree.createFile("text/markdown", name)
            existing.name != name -> { existing.renameTo(name); existing }
            else -> existing
        } ?: return false
        app.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(render(app, noteId, ExportFormat.MD).toByteArray()) }
        return true
    }

    // ------------------------------------------------------------------ backup

    fun writeBackup(app: App, target: Uri) {
        val dbFile = app.getDatabasePath(DbHelper.NAME)
        app.contentResolver.openOutputStream(target)?.use { os ->
            ZipOutputStream(os.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(JSONObject().put("app", "AI Voice to Text").put("schema", DbHelper.VERSION).toString().toByteArray())
                zip.closeEntry()
                synchronized(app.repo) {
                    zip.putNextEntry(ZipEntry("notes.db"))
                    dbFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                val settings = JSONObject()
                app.prefs.exportAll().forEach { (k, v) -> if (!k.startsWith("dl_")) settings.put(k, v) }
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(settings.toString().toByteArray())
                zip.closeEntry()
                File(app.filesDir, "audio").listFiles()?.forEach { f ->
                    zip.putNextEntry(ZipEntry("audio/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("Не удалось открыть файл для записи")
    }

    /** Restores a backup. The app process must be restarted afterwards. */
    fun restoreBackup(app: App, source: Uri) {
        val tmp = File(app.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var hasDb = false
        app.contentResolver.openInputStream(source)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = File(tmp, e.name).canonicalFile
                    require(out.path.startsWith(tmp.canonicalPath + File.separator)) { "Повреждённый архив" }
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                    if (e.name == "notes.db") hasDb = true
                }
            }
        } ?: error("Не удалось открыть архив")
        require(hasDb) { "В архиве нет базы заметок" }

        app.repo.close()
        val dbFile = app.getDatabasePath(DbHelper.NAME)
        File(tmp, "notes.db").copyTo(dbFile, overwrite = true)
        File(dbFile.path + "-journal").delete()
        val audio = File(app.filesDir, "audio").apply { mkdirs() }
        File(tmp, "audio").listFiles()?.forEach { it.copyTo(File(audio, it.name), overwrite = true) }
        File(tmp, "settings.json").takeIf { it.exists() }?.let { f ->
            val o = JSONObject(f.readText())
            val map = HashMap<String, Any?>()
            o.keys().forEach { k -> map[k] = o.get(k) }
            app.prefs.importAll(map)
        }
        // audio paths are absolute; rewrite them for this device
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        db.use {
            it.execSQL(
                "UPDATE notes SET audio_path = ? || substr(audio_path, length(rtrim(audio_path, replace(audio_path, '/', ''))) + 1) WHERE audio_path IS NOT NULL",
                arrayOf(audio.path + "/"),
            )
            it.execSQL("UPDATE notes SET pcm_path = NULL")
        }
        tmp.deleteRecursively()
    }
}
