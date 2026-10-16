package app.aivoicetotext.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.aivoicetotext.App
import app.aivoicetotext.data.AiStatus
import app.aivoicetotext.engine.AiProcessor
import app.aivoicetotext.engine.Engines
import app.aivoicetotext.engine.LlmSession
import app.aivoicetotext.export.Exporter
import app.aivoicetotext.util.Device
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

class AiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val repo = app.repo
        val resultId = inputData.getLong("resultId", -1)
        val result = repo.aiResult(resultId) ?: return Result.success()
        val note = repo.note(result.noteId) ?: return Result.success()
        val template = repo.template(result.templateId)
        if (template == null) {
            repo.updateAi(resultId, status = AiStatus.ERROR, error = "Шаблон удалён")
            return Result.failure()
        }
        if (!Device.cpuSupported) {
            repo.updateAi(resultId, status = AiStatus.ERROR, error = "Процессор не поддерживается")
            return Result.failure()
        }
        val model = app.models.byId(result.modelId) ?: app.models.llm()
        if (model == null) {
            repo.updateAi(resultId, status = AiStatus.ERROR, error = "Скачайте LLM-модель в разделе «Модели»")
            return Result.failure()
        }
        val notifId = ("ai$resultId").hashCode()

        try {
            foreground(notifId, template.name, "Ожидание освобождения памяти…", 0, 0, note.id)
            val text = Engines.lock.withLock {
                Engines.holder = "ai-$resultId"
                try {
                    repo.updateAi(resultId, status = AiStatus.RUNNING)
                    foreground(notifId, template.name, "Загрузка модели ${model.name}…", 0, 0, note.id)
                    val ramGb = Device.totalRamGb(app)
                    val nCtx = when {
                        ramGb >= 11 -> 8192
                        ramGb >= 7 -> 4096
                        else -> 2048
                    }
                    val llm = LlmSession.open(app.models.fileFor(model), nCtx, Engines.threads(app.prefs.value))
                        ?: error("Не удалось загрузить модель ${model.name} (не хватает памяти?)")
                    try {
                        AiProcessor(repo).run(result, template, model, llm) { done, total ->
                            repo.updateAi(resultId, progress = done, total = total)
                            foreground(notifId, template.name, "${note.title}: часть $done из $total", done, total, note.id)
                        }
                    } finally {
                        llm.close()
                    }
                } finally {
                    Engines.holder = null
                }
            }
            repo.updateAi(resultId, status = AiStatus.DONE, content = text)
            repo.clearChunks(resultId)
            repo.reindex(note.id)
            runCatching { Exporter.autoExport(app, note.id) }
            Jobs.notifyDone(app, note.id, "${template.emoji} ${template.name} готово", note.title)
            return Result.success()
        } catch (e: CancellationException) {
            val r = repo.aiResult(resultId)
            if (r != null && r.status == AiStatus.RUNNING) repo.updateAi(resultId, status = AiStatus.QUEUED)
            throw e
        } catch (e: Throwable) {
            repo.updateAi(resultId, status = AiStatus.ERROR, error = e.message ?: e.javaClass.simpleName)
            return Result.failure()
        }
    }
}
