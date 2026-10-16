package app.aivoicetotext.work

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.aivoicetotext.App
import app.aivoicetotext.audio.AudioDecoder
import app.aivoicetotext.engine.AutoSummary
import app.aivoicetotext.engine.Diarizer
import app.aivoicetotext.engine.Engines
import app.aivoicetotext.engine.LlmSession
import app.aivoicetotext.engine.SpeakerEmbedder
import app.aivoicetotext.export.Exporter
import app.aivoicetotext.util.Device
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Picks the LLM context size from the device RAM. */
fun llmContextSize(context: Context): Int {
    val ramGb = Device.totalRamGb(context)
    return when {
        ramGb >= 11 -> 8192
        ramGb >= 7 -> 4096
        else -> 2048
    }
}

/** Automatic title + 3-point summary (needs an LLM). */
class SummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val noteId = inputData.getLong("noteId", -1)
        val note = app.repo.note(noteId) ?: return Result.success()
        val model = app.models.llm() ?: return Result.success()
        if (!Device.cpuSupported) return Result.success()
        val notifId = ("sum$noteId").hashCode()
        return try {
            Jobs.setActivity(noteId, "Готовлю выжимку…")
            foreground(notifId, note.title, "Готовлю выжимку…", 0, 0, noteId)
            val (title, summary) = Engines.lock.withLock {
                Engines.holder = "summary-$noteId"
                try {
                    val llm = LlmSession.open(app.models.fileFor(model), llmContextSize(app), Engines.threads(app.prefs.value))
                        ?: error("Не удалось загрузить ${model.name}")
                    try { AutoSummary.generate(app.repo, noteId, model, llm) } finally { llm.close() }
                } finally {
                    Engines.holder = null
                }
            }
            app.repo.setAuto(noteId, title, summary)
            runCatching { Exporter.autoExport(app, noteId) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure()
        } finally {
            Jobs.setActivity(noteId, null)
        }
    }
}

/** Speaker detection: embeddings per segment + clustering. */
class DiarizeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val repo = app.repo
        val noteId = inputData.getLong("noteId", -1)
        val speakers = inputData.getInt("speakers", 0)
        val thenSummary = inputData.getBoolean("thenSummary", false)
        val note = repo.note(noteId) ?: return Result.success()
        val model = app.models.speakerModel() ?: return Result.success()
        val notifId = ("spk$noteId").hashCode()
        var tempPcm: File? = null
        return try {
            Jobs.setActivity(noteId, "Определяю спикеров…")
            foreground(notifId, note.title, "Определяю спикеров…", 0, 0, noteId)
            var pcm = note.pcmPath?.let { File(it) }?.takeIf { it.exists() }
            if (pcm == null) {
                val src = note.audioPath ?: error("Нет аудио")
                Jobs.setActivity(noteId, "Подготовка аудио…")
                val f = File(app.cacheDir, "diar-$noteId.pcm")
                AudioDecoder.decodeToPcm(app, Uri.fromFile(File(src)), f) { }
                tempPcm = f
                pcm = f
            }
            val pcmFile: File = pcm ?: error("Нет аудио")
            val result = Engines.lock.withLock {
                Engines.holder = "diarize-$noteId"
                try {
                    SpeakerEmbedder(app.models.fileFor(model), Engines.threads(app.prefs.value)).use { emb ->
                        Diarizer.run(pcmFile, repo.segments(noteId), emb, speakers.takeIf { it > 0 }) { done, total ->
                            val pct = if (total > 0) done * 100 / total else 0
                            Jobs.setActivity(noteId, "Определяю спикеров… $pct%")
                            if (done % 25 == 0) foreground(notifId, note.title, "Определяю спикеров… $pct%", done, total, noteId)
                        }
                    }
                } finally {
                    Engines.holder = null
                }
            }
            if (result.count > 0) repo.setSpeakers(noteId, result.assignment, result.count)
            // the PCM kept for speaker detection is no longer needed
            note.pcmPath?.let { File(it).delete() }
            repo.updateNote(noteId, ContentValues().apply { putNull("pcm_path") })
            runCatching { Exporter.autoExport(app, noteId) }
            if (thenSummary && app.prefs.value.autoSummary && app.models.llm() != null) Jobs.summary(app, noteId)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Jobs.setActivity(noteId, null)
            app.repo.updateNote(noteId, ContentValues().apply { put("error", "Спикеры: " + (e.message ?: e.javaClass.simpleName)) })
            Result.failure()
        } finally {
            tempPcm?.delete()
            Jobs.setActivity(noteId, null)
        }
    }
}
