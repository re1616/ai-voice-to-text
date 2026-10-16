package app.aivoicetotext.work

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.aivoicetotext.App
import app.aivoicetotext.audio.AacEncoder
import app.aivoicetotext.audio.AudioDecoder
import app.aivoicetotext.audio.PcmFile
import app.aivoicetotext.data.NoteStatus
import app.aivoicetotext.engine.AutoSummary
import app.aivoicetotext.engine.Engines
import app.aivoicetotext.engine.Transcriber
import app.aivoicetotext.export.Exporter
import app.aivoicetotext.util.Device
import app.aivoicetotext.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val repo = app.repo
        val noteId = inputData.getLong("noteId", -1)
        var note = repo.note(noteId) ?: return Result.success()
        if (note.deletedAt != null) return Result.success()
        val notifId = ("tr$noteId").hashCode()

        try {
            foreground(notifId, note.title, "Подготовка…", 0, 0, noteId)
            val audioDir = File(app.filesDir, "audio").apply { mkdirs() }
            val pcmDir = File(app.filesDir, "pcm").apply { mkdirs() }

            // 1. Recording: compress PCM into .m4a for playback (PCM is kept until transcription ends)
            if (note.audioPath == null && note.pcmPath != null) {
                repo.setStatus(noteId, NoteStatus.ENCODING)
                val m4a = File(audioDir, "$noteId.m4a")
                AacEncoder.encode(File(note.pcmPath!!), m4a)
                repo.updateNote(noteId, ContentValues().apply {
                    put("audio_path", m4a.path)
                    put("duration_ms", PcmFile.durationMs(File(note.pcmPath!!)))
                })
            }

            // 2. Import: decode to 16 kHz PCM
            note = repo.note(noteId) ?: return Result.success()
            var pcm = note.pcmPath?.let { File(it) }
            if (pcm == null || !pcm.exists()) {
                repo.setStatus(noteId, NoteStatus.DECODING)
                val target = File(pcmDir, "$noteId.pcm")
                val src = note.audioPath ?: error("Нет аудиофайла")
                val dur = AudioDecoder.decodeToPcm(app, Uri.fromFile(File(src)), target) { }
                repo.updateNote(noteId, ContentValues().apply {
                    put("pcm_path", target.path); put("duration_ms", dur); put("progress_ms", 0)
                })
                pcm = target
            }

            // 3. Transcription
            if (!Device.cpuSupported) {
                repo.setStatus(noteId, NoteStatus.ERROR, "Процессор телефона не поддерживает нужные инструкции (ARMv8.2 dotprod)")
                return Result.failure()
            }
            val model = app.models.mainStt()
            if (model == null) {
                repo.setStatus(noteId, NoteStatus.NO_MODEL, "Скачайте модель распознавания в разделе «Модели»")
                return Result.success()
            }
            repo.setStatus(noteId, NoteStatus.QUEUED)
            foreground(notifId, note.title, "Ожидание освобождения памяти…", 0, 0, noteId)

            val pcmFile: File = pcm ?: error("Нет PCM")
            Engines.lock.withLock {
                Engines.holder = "transcribe-$noteId"
                try {
                    note = repo.note(noteId) ?: return Result.success()
                    repo.setStatus(noteId, NoteStatus.TRANSCRIBING)
                    Transcriber(app, repo, app.prefs.value).run(note, pcmFile, model, app.models.fileFor(model)) { p ->
                        if (p.speed > 0) repo.updateNote(noteId, ContentValues().apply { put("speed", p.speed) }, notify = false)
                        val pct = if (p.totalMs > 0) (p.processedMs * 100 / p.totalMs).toInt() else 0
                        val text = String.format(Locale("ru"), "%d%% · %s из %s · %.1fx", pct, Fmt.clock(p.processedMs), Fmt.clock(p.totalMs), p.speed)
                        notifyProgress(notifId, note.title, text, pct, noteId)
                    }
                } finally {
                    Engines.holder = null
                }
            }

            // 4. Done
            val finished = repo.note(noteId) ?: return Result.success()
            if (finished.speed > 0) app.prefs.setModelSpeed(model.id, finished.speed.toFloat())
            val diarize = app.prefs.value.autoSpeakers && app.models.speakerModel() != null
            if (!diarize) {
                File(finished.pcmPath ?: "").takeIf { it.path.isNotEmpty() }?.delete()
                repo.updateNote(noteId, ContentValues().apply { putNull("pcm_path") })
            }
            repo.updateNote(noteId, ContentValues().apply { put("status", NoteStatus.DONE); putNull("error") })
            // a readable title right away, even without an LLM
            if (!finished.titleCustom && AutoSummary.isDefaultTitle(finished.title)) {
                AutoSummary.fallbackTitle(repo, noteId)?.let { repo.setAuto(noteId, it, null) }
            }
            repo.reindex(noteId)
            runCatching { Exporter.autoExport(app, noteId) }
            val title = repo.note(noteId)?.title ?: finished.title
            Jobs.notifyDone(app, noteId, "Расшифровка готова", title)
            Jobs.afterTranscription(app, noteId)
            return Result.success()
        } catch (e: CancellationException) {
            // stopped by the system (constraints, quota) or by the user; progress is saved
            val n = repo.note(noteId)
            if (n != null && n.status != NoteStatus.DONE) repo.setStatus(noteId, NoteStatus.QUEUED)
            throw e
        } catch (e: Throwable) {
            repo.setStatus(noteId, NoteStatus.ERROR, e.message ?: e.javaClass.simpleName)
            return Result.failure()
        }
    }

    private var lastNotify = 0L
    private suspend fun notifyProgress(id: Int, title: String, text: String, pct: Int, noteId: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1500) return
        lastNotify = now
        foreground(id, title, text, pct, 100, noteId)
    }
}
