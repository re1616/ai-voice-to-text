package app.aivoicetotext.engine

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.PowerManager
import app.aivoicetotext.audio.PcmFile
import app.aivoicetotext.audio.SAMPLE_RATE
import app.aivoicetotext.data.Note
import app.aivoicetotext.data.Repository
import app.aivoicetotext.data.Segment
import app.aivoicetotext.data.Settings
import app.aivoicetotext.models.ModelInfo
import kotlinx.coroutines.delay
import java.io.File

/**
 * Batch transcription of a 16 kHz PCM file, resumable from note.progressMs.
 *
 * The audio is read in 5-minute windows. Silero VAD finds speech in each window; speech
 * regions are merged into chunks of up to ~28 s and each chunk is recognised separately.
 * Every chunk is saved immediately, so a killed process continues where it stopped.
 */
class Transcriber(
    private val context: Context,
    private val repo: Repository,
    private val settings: Settings,
) {
    data class Progress(val processedMs: Long, val totalMs: Long, val speed: Double)

    private val window = 300 * SAMPLE_RATE
    private val maxChunkS = 28f

    suspend fun run(note: Note, pcm: File, model: ModelInfo, modelFile: File, onProgress: suspend (Progress) -> Unit) {
        val threads = Engines.threads(settings)
        val totalSamples = PcmFile.sampleCount(pcm)
        val totalMs = totalSamples * 1000 / SAMPLE_RATE
        var language = note.language.ifBlank { "auto" }
        if (language == "auto" && !note.detectedLanguage.isNullOrBlank() && note.progressMs > 0) language = note.detectedLanguage!!

        // a restarted job drops unedited final segments after the last checkpoint
        repo.deleteFinalSegmentsFrom(note.id, note.progressMs)

        val whisper = WhisperSession.open(modelFile) ?: error("Не удалось загрузить модель ${model.name}")
        val vadFile = Engines.vadModel(context)
        val vad = vadFile?.let { VadSession.open(it, 2) }
        try {
            var pos = note.progressMs * SAMPLE_RATE / 1000
            var prevText = lastText(note.id)
            val started = System.currentTimeMillis()
            val startPos = pos

            while (pos < totalSamples) {
                waitIfHot()
                val count = minOf(window.toLong(), totalSamples - pos).toInt()
                val samples = PcmFile.readFloats(pcm, pos, count)
                val isLast = pos + count >= totalSamples
                val windowS = count / SAMPLE_RATE.toFloat()

                val speech = withNativeAbort({}) { vad?.speech(samples) } ?: fixedChunks(windowS)
                var chunks = mergeChunks(speech)
                var nextPos = pos + count

                if (!isLast && chunks.isNotEmpty()) {
                    // a chunk touching the window edge may be cut in the middle of a word:
                    // defer it to the next window
                    val last = chunks.last()
                    if (last.second >= windowS - 1f && chunks.size > 1) {
                        chunks = chunks.dropLast(1)
                        nextPos = pos + (last.first * SAMPLE_RATE).toLong()
                    } else if (last.second >= windowS - 1f) {
                        nextPos = pos + (last.second * SAMPLE_RATE).toLong().coerceAtLeast(SAMPLE_RATE.toLong())
                    }
                }

                for ((cs, ce) in chunks) {
                    val from = ((cs - 0.2f).coerceAtLeast(0f) * SAMPLE_RATE).toInt()
                    val to = ((ce + 0.2f).coerceAtMost(windowS) * SAMPLE_RATE).toInt()
                    if (to - from < SAMPLE_RATE / 2) continue
                    val slice = samples.copyOfRange(from, to)
                    val prompt = buildPrompt(prevText)
                    val segs = withNativeAbort({ whisper.abort() }) {
                        whisper.transcribe(slice, language, threads, prompt)
                    } ?: error("Ошибка распознавания")

                    if (language == "auto") {
                        val detected = whisper.detectedLanguage()
                        if (detected.isNotBlank()) {
                            language = detected
                            repo.updateNote(note.id, ContentValues().apply { put("detected_lang", detected) }, notify = false)
                        }
                    }

                    val offsetMs = (pos + from) * 1000 / SAMPLE_RATE
                    val chunkEndMs = (pos + to) * 1000 / SAMPLE_RATE
                    val clean = segs.mapNotNull { s ->
                        val text = s.text.trim()
                        if (text.isEmpty() || isHallucination(text)) null
                        else Segment(0, note.id, offsetMs + s.startMs, minOf(offsetMs + s.endMs, chunkEndMs), text, draft = false, edited = false)
                    }
                    if (clean.isNotEmpty()) {
                        repo.deleteDraftsBefore(note.id, chunkEndMs)
                        repo.insertSegments(note.id, clean, draft = false)
                        prevText = clean.joinToString(" ") { it.text }
                    }
                    checkpoint(note.id, chunkEndMs)
                    report(onProgress, chunkEndMs, totalMs, startPos, started)
                }

                pos = maxOf(nextPos, pos + 1)
                checkpoint(note.id, pos * 1000 / SAMPLE_RATE)
                report(onProgress, pos * 1000 / SAMPLE_RATE, totalMs, startPos, started)
            }
            repo.deleteDrafts(note.id)
            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
            val speed = (totalSamples - startPos) * 1000.0 / SAMPLE_RATE / elapsed
            repo.updateNote(note.id, ContentValues().apply {
                put("speed", speed); put("model_id", model.id); put("progress_ms", totalMs)
            })
        } finally {
            vad?.close()
            whisper.close()
        }
    }

    private suspend fun report(onProgress: suspend (Progress) -> Unit, doneMs: Long, totalMs: Long, startPos: Long, started: Long) {
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
        val processed = doneMs - startPos * 1000 / SAMPLE_RATE
        onProgress(Progress(doneMs, totalMs, processed.toDouble() / elapsed))
    }

    private fun checkpoint(noteId: Long, ms: Long) {
        repo.updateNote(noteId, ContentValues().apply { put("progress_ms", ms) })
    }

    private fun lastText(noteId: Long): String =
        repo.segments(noteId).filter { !it.draft }.takeLast(2).joinToString(" ") { it.text }

    /** Vocabulary plus the tail of the previous chunk keeps names and punctuation consistent. */
    private fun buildPrompt(prev: String): String? {
        val vocab = settings.vocabulary.trim()
        val tail = prev.takeLast(160)
        val p = listOf(vocab, tail).filter { it.isNotBlank() }.joinToString(". ")
        return p.ifBlank { null }
    }

    private fun fixedChunks(windowS: Float): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        var t = 0f
        while (t < windowS) { out += t to minOf(windowS, t + 25f); t += 25f }
        return out
    }

    /** Joins neighbouring speech regions into chunks of at most [maxChunkS] seconds. */
    private fun mergeChunks(speech: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        var cs = -1f
        var ce = -1f
        for ((s, e) in speech.sortedBy { it.first }) {
            if (cs < 0) { cs = s; ce = e; continue }
            if (e - cs <= maxChunkS && s - ce < 3f) ce = maxOf(ce, e)
            else { out += cs to ce; cs = s; ce = e }
        }
        if (cs >= 0) out += cs to ce
        return out
    }

    private suspend fun waitIfHot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        var waited = 0
        while (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE && waited < 600) {
            delay(15_000); waited += 15
        }
    }

    companion object {
        private val hallucinations = listOf(
            "продолжение следует", "субтитры", "редактор субтитров", "корректор", "спасибо за просмотр",
            "подписывайтесь на канал", "thank you for watching", "thanks for watching", "subtitles by",
            "amara.org", "dimatorzok", "♪",
        )

        fun isHallucination(text: String): Boolean {
            val t = text.lowercase().trim(' ', '.', '!', '…', '-', '"')
            if (t.isEmpty()) return true
            if (t.length > 80) return false
            return hallucinations.any { t.contains(it) } || t.all { !it.isLetterOrDigit() }
        }
    }
}
