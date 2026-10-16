package app.aivoicetotext.engine

import app.aivoicetotext.data.AiResult
import app.aivoicetotext.data.PromptTemplate
import app.aivoicetotext.data.Repository
import app.aivoicetotext.data.TemplateKind
import app.aivoicetotext.data.TemplateMode
import app.aivoicetotext.models.ModelInfo

/**
 * Runs a prompt template over a transcript with map-reduce, so recordings of any length fit
 * into the model's context. Every intermediate output is stored, so the job can resume.
 */
class AiProcessor(private val repo: Repository) {

    private val mapOutTokens = 700
    private val finalOutTokens = 1200

    suspend fun run(
        result: AiResult,
        template: PromptTemplate,
        model: ModelInfo,
        llm: LlmSession,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): String {
        val segments = repo.segments(result.noteId).filter { !it.draft }.map { it.text.trim() }.filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "Нет текста для обработки" }

        val system = template.systemPrompt + if (template.kind == TemplateKind.CHECKLIST)
            "\nФормат ответа: только строки вида \"- [ ] задача\"." else ""

        val nCtx = llm.contextSize
        val overhead = llm.countTokens(system) + 120
        val fullText = segments.joinToString(" ")
        val fullTokens = llm.countTokens(fullText)

        // Fits in one pass
        if (overhead + fullTokens + finalOutTokens <= nCtx) {
            onProgress(0, 1)
            val out = call(llm, model, system, fullText, finalOutTokens, template.temperature)
            onProgress(1, 1)
            return postProcess(out, template)
        }

        // Map: split by segment boundaries
        val budget = (nCtx - overhead - mapOutTokens - 64).coerceAtLeast(256)
        val parts = split(llm, segments, budget)
        val mapSystem = system + "\nЭто фрагмент длинной расшифровки. Обработай только этот фрагмент."
        val outputs = ArrayList<String>()
        var total = parts.size + if (template.mode == TemplateMode.AGGREGATE) 1 else 0
        for ((i, part) in parts.withIndex()) {
            onProgress(i, total)
            val cached = repo.chunkOutput(result.id, 0, i)
            val out = cached ?: call(llm, model, mapSystem, part, mapOutTokens, template.temperature).also {
                repo.saveChunkOutput(result.id, 0, i, it)
            }
            outputs += out.trim()
        }

        if (template.mode == TemplateMode.TRANSFORM) {
            onProgress(total, total)
            return postProcess(outputs.joinToString("\n\n"), template)
        }

        // Reduce, possibly in several levels
        val reduceSystem = system + "\nНиже промежуточные результаты по частям одной записи. " +
            "Объедини их в один итоговый результат по тем же правилам, убрав повторы."
        var level = 1
        var current: List<String> = outputs
        while (true) {
            val joined = current.joinToString("\n\n")
            if (overhead + llm.countTokens(joined) + finalOutTokens <= nCtx || level > 4) {
                onProgress(total - 1, total)
                val cached = repo.chunkOutput(result.id, level, 0)
                val out = cached ?: call(llm, model, reduceSystem, joined, finalOutTokens, template.temperature).also {
                    repo.saveChunkOutput(result.id, level, 0, it)
                }
                onProgress(total, total)
                return postProcess(out, template)
            }
            val groups = split(llm, current, budget)
            total += groups.size
            val next = ArrayList<String>()
            for ((i, g) in groups.withIndex()) {
                val cached = repo.chunkOutput(result.id, level, i + 1)
                next += cached ?: call(llm, model, reduceSystem, g, mapOutTokens, template.temperature).also {
                    repo.saveChunkOutput(result.id, level, i + 1, it)
                }
            }
            current = next
            level++
        }
    }

    private fun split(llm: LlmSession, items: List<String>, budget: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var curTokens = 0
        for (item in items) {
            val t = llm.countTokens(item) + 1
            if (curTokens + t > budget && cur.isNotEmpty()) {
                out += cur.toString(); cur.clear(); curTokens = 0
            }
            if (t > budget) {
                // a single huge item: cut by characters
                val approxChars = (item.length.toDouble() * budget / t).toInt().coerceAtLeast(200)
                item.chunked(approxChars).forEach { out += it }
                continue
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(item)
            curTokens += t
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private suspend fun call(llm: LlmSession, model: ModelInfo, system: String, text: String, maxTokens: Int, temperature: Float): String {
        val user = "Текст расшифровки:\n\n$text" + if (model.noThink) "\n\n/no_think" else ""
        val prompt = llm.format(system, user) ?: error("Модель не поддерживает формат чата")
        val raw = withNativeAbort({ llm.abort() }) { llm.generate(prompt, maxTokens, temperature) }
        return stripThinking(raw).trim()
    }

    companion object {
        fun stripThinking(s: String): String {
            var t = s.replace(Regex("(?s)<think>.*?</think>"), "")
            val open = t.indexOf("<think>")
            if (open >= 0) t = t.substring(0, open)
            return t.trim()
        }

        fun postProcess(raw: String, template: PromptTemplate): String {
            val text = stripThinking(raw)
            if (template.kind != TemplateKind.CHECKLIST) return text
            val items = text.lines().map { it.trim() }.mapNotNull { line ->
                when {
                    line.startsWith("- [ ]") || line.startsWith("- [x]") || line.startsWith("- [X]") -> line
                    line.startsWith("-") || line.startsWith("*") || line.startsWith("•") ->
                        "- [ ] " + line.trimStart('-', '*', '•', ' ').removePrefix("[ ]").trim()
                    Regex("^\\d+[.)]\\s+.*").matches(line) -> "- [ ] " + line.replace(Regex("^\\d+[.)]\\s+"), "")
                    else -> null
                }
            }.distinct()
            return if (items.isEmpty()) text.ifBlank { "Задач не найдено" } else items.joinToString("\n")
        }
    }
}
