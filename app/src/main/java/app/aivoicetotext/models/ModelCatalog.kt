package app.aivoicetotext.models

import android.content.Context
import org.json.JSONObject

enum class ModelType { STT, LLM, SPEAKER }

data class ModelInfo(
    val id: String,
    val type: ModelType,
    val name: String,
    val description: String,
    val fileName: String,
    val url: String?,
    val sizeBytes: Long,
    val sha256: String?,
    val quant: String,
    val minRamGb: Double,
    val license: String,
    /** Qwen3 hybrid models: ask for a direct answer without the <think> block */
    val noThink: Boolean = false,
    val custom: Boolean = false,
)

/**
 * Built-in model catalog. During the CI build `scripts/gen_catalog.py` resolves every entry
 * on Hugging Face and writes `assets/catalog.json` with a pinned commit, size and SHA-256.
 * If that asset is missing, the fallback list below (branch "main", no checksum) is used.
 */
object ModelCatalog {

    private val fallback = listOf(
        stt("whisper-base-q8", "Whisper Base", "Самая быстрая. Для живого текста и коротких заметок; для русского — только черновик.",
            "ggml-base-q8_0.bin", 82_000_000, "q8_0", 3.0),
        stt("whisper-small-q5", "Whisper Small", "Основная модель для русского и английского: хорошая точность и пунктуация.",
            "ggml-small-q5_1.bin", 190_000_000, "q5_1", 4.0),
        stt("whisper-small-q8", "Whisper Small (q8)", "Чуть точнее Small q5, немного больше и медленнее.",
            "ggml-small-q8_0.bin", 264_000_000, "q8_0", 6.0),
        stt("whisper-turbo-q5", "Whisper Large-v3-Turbo", "Максимальная точность: лекции, интервью, термины. Для мощных телефонов.",
            "ggml-large-v3-turbo-q5_0.bin", 574_000_000, "q5_0", 6.0),
        llm("gemma3-1b", "Gemma 3 1B", "Самая лёгкая LLM: короткие выжимки и задачи. Подходит для 6 ГБ ОЗУ.",
            "unsloth/gemma-3-1b-it-GGUF", "gemma-3-1b-it-Q4_K_M.gguf", 806_000_000, 5.0, "Gemma Terms of Use"),
        llm("qwen3-1.7b", "Qwen3 1.7B", "Хороший русский язык при небольшом размере.",
            "unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf", 1_110_000_000, 6.0, "Apache-2.0", noThink = true),
        llm("qwen3-4b-2507", "Qwen3 4B Instruct", "Качественные конспекты и задачи. Для телефонов с 8+ ГБ ОЗУ.",
            "unsloth/Qwen3-4B-Instruct-2507-GGUF", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", 2_500_000_000, 8.0, "Apache-2.0"),
        llm("gemma3-4b", "Gemma 3 4B", "Альтернатива Qwen3 4B, сильна в пересказе.",
            "unsloth/gemma-3-4b-it-GGUF", "gemma-3-4b-it-Q4_K_M.gguf", 2_490_000_000, 8.0, "Gemma Terms of Use"),
        ModelInfo(
            "speaker-resnet34", ModelType.SPEAKER, "WeSpeaker ResNet34", "Различает голоса: подписывает, кто говорит.",
            "wespeaker_en_voxceleb_resnet34_LM.onnx",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx",
            26_500_000, null, "fp32", 3.0, "WeSpeaker (см. лицензию модели)",
        ),
    )

    private fun stt(id: String, name: String, desc: String, file: String, size: Long, quant: String, ram: Double) = ModelInfo(
        id, ModelType.STT, name, desc, file, "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file",
        size, null, quant, ram, "MIT",
    )

    private fun llm(id: String, name: String, desc: String, repo: String, file: String, size: Long, ram: Double, license: String, noThink: Boolean = false) = ModelInfo(
        id, ModelType.LLM, name, desc, file, "https://huggingface.co/$repo/resolve/main/$file",
        size, null, "Q4_K_M", ram, license, noThink,
    )

    @Volatile private var cached: List<ModelInfo>? = null

    fun all(context: Context): List<ModelInfo> {
        cached?.let { return it }
        val fromAsset = runCatching {
            val json = context.assets.open("catalog.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(json).getJSONArray("models")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ModelInfo(
                    id = o.getString("id"),
                    type = ModelType.valueOf(o.getString("type")),
                    name = o.getString("name"),
                    description = o.getString("description"),
                    fileName = o.getString("file"),
                    url = o.getString("url"),
                    sizeBytes = o.getLong("size"),
                    sha256 = o.optString("sha256").ifBlank { null },
                    quant = o.getString("quant"),
                    minRamGb = o.getDouble("minRamGb"),
                    license = o.getString("license"),
                    noThink = o.optBoolean("noThink", false),
                )
            }
        }.getOrNull()
        val list = if (fromAsset.isNullOrEmpty()) fallback else fromAsset
        cached = list
        return list
    }

    /** Catalog entries with descriptions, used by scripts/gen_catalog.py through the JSON template. */
    fun fallbackList(): List<ModelInfo> = fallback
}
