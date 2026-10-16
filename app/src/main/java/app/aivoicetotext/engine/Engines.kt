package app.aivoicetotext.engine

import android.content.Context
import app.aivoicetotext.data.Settings
import app.aivoicetotext.llama.LlamaLib
import app.aivoicetotext.llama.PieceCallback
import app.aivoicetotext.util.Device
import app.aivoicetotext.whisper.WhisperLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Guarantees that at most one large model (Whisper or LLM) is in memory at a time.
 * Every job loads its model inside [lock] and frees it before releasing the lock.
 */
object Engines {
    val lock = Mutex()

    @Volatile var holder: String? = null

    fun threads(s: Settings): Int = if (s.threads > 0) s.threads else Device.bigCores

    private const val VAD_ASSET = "ggml-silero-v5.1.2.bin"

    /** Silero VAD (ggml) ships inside the APK; whisper.cpp needs it as a file. */
    fun vadModel(context: Context): File? {
        val out = File(context.filesDir, VAD_ASSET)
        if (out.exists() && out.length() > 0) return out
        return runCatching {
            context.assets.open(VAD_ASSET).use { input -> out.outputStream().use { input.copyTo(it) } }
            out
        }.getOrNull()
    }
}

/**
 * Runs a blocking native call on a background thread; if the coroutine is cancelled
 * meanwhile, [abort] is invoked so the native loop stops early.
 */
suspend fun <T> withNativeAbort(abort: () -> Unit, block: () -> T): T = coroutineScope {
    var finished = false
    val watcher = launch {
        try { awaitCancellation() } finally { if (!finished) abort() }
    }
    try {
        withContext(Dispatchers.Default) { block() }
    } finally {
        finished = true
        watcher.cancel()
    }
}

data class RawSegment(val startMs: Long, val endMs: Long, val text: String)

class WhisperSession private constructor(private val handle: Long) : Closeable {

    fun transcribe(samples: FloatArray, language: String, threads: Int, prompt: String?): List<RawSegment>? {
        val rc = WhisperLib.transcribe(handle, samples, language, threads, prompt, false)
        if (rc == -2) throw InterruptedException("cancelled")
        if (rc != 0) return null
        val n = WhisperLib.segmentCount(handle)
        return (0 until n).map { i ->
            RawSegment(
                startMs = WhisperLib.segmentT0(handle, i) * 10,
                endMs = WhisperLib.segmentT1(handle, i) * 10,
                text = String(WhisperLib.segmentText(handle, i), Charsets.UTF_8),
            )
        }
    }

    fun detectedLanguage(): String = WhisperLib.detectedLanguage(handle)
    fun abort() = WhisperLib.requestAbort(handle)
    fun progress(): Int = WhisperLib.progress(handle)

    override fun close() = WhisperLib.freeContext(handle)

    companion object {
        fun open(path: File): WhisperSession? {
            val h = WhisperLib.initContext(path.absolutePath, false)
            return if (h == 0L) null else WhisperSession(h)
        }
    }
}

class VadSession private constructor(private val handle: Long) : Closeable {
    /** Speech regions in seconds relative to [samples]. */
    fun speech(samples: FloatArray): List<Pair<Float, Float>>? {
        val arr = WhisperLib.vadSegments(handle, samples, 0.5f, 250, 500, 25f, 200) ?: return null
        return (0 until arr.size / 2).map { arr[2 * it] to arr[2 * it + 1] }
    }

    override fun close() = WhisperLib.vadFree(handle)

    companion object {
        fun open(path: File, threads: Int): VadSession? {
            val h = WhisperLib.vadInit(path.absolutePath, threads)
            return if (h == 0L) null else VadSession(h)
        }
    }
}

class LlmSession private constructor(private val handle: Long) : Closeable {
    val contextSize: Int get() = LlamaLib.contextSize(handle)

    fun countTokens(text: String): Int = LlamaLib.countTokens(handle, text.toByteArray(Charsets.UTF_8))

    fun format(system: String, user: String): String? {
        val roles = arrayOf("system".toByteArray(), "user".toByteArray())
        val contents = arrayOf(system.toByteArray(Charsets.UTF_8), user.toByteArray(Charsets.UTF_8))
        return LlamaLib.applyTemplate(handle, roles, contents)?.toString(Charsets.UTF_8)
    }

    /** @return generated text; throws on error or cancellation */
    fun generate(prompt: String, maxTokens: Int, temperature: Float, onText: (String) -> Unit = {}): String {
        val bytes = java.io.ByteArrayOutputStream()
        val rc = LlamaLib.generate(handle, prompt.toByteArray(Charsets.UTF_8), maxTokens, temperature, PieceCallback { piece ->
            bytes.write(piece)
            onText(bytes.toString("UTF-8"))
            true
        })
        when (rc) {
            -2 -> throw InterruptedException("cancelled")
            -3 -> throw IllegalStateException("Текст не помещается в контекст модели")
            -1 -> throw IllegalStateException("Ошибка генерации")
        }
        return bytes.toString("UTF-8")
    }

    fun abort() = LlamaLib.requestAbort(handle)
    override fun close() = LlamaLib.free(handle)

    companion object {
        fun open(path: File, nCtx: Int, threads: Int): LlmSession? {
            val h = LlamaLib.load(path.absolutePath, nCtx, threads)
            return if (h == 0L) null else LlmSession(h)
        }
    }
}
