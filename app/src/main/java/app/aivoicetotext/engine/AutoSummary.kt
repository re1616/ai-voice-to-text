package app.aivoicetotext.engine

import app.aivoicetotext.data.PromptTemplate
import app.aivoicetotext.data.Repository
import app.aivoicetotext.data.TemplateKind
import app.aivoicetotext.data.TemplateMode
import app.aivoicetotext.models.ModelInfo

/** Automatic title + 3-point summary, generated right after transcription. */
object AutoSummary {

    private val template = PromptTemplate(
        id = -1, name = "Авто-выжимка", emoji = "✨",
        systemPrompt = "Прочитай расшифровку и ответь строго в таком формате:\n" +
            "НАЗВАНИЕ: короткое название записи из 3–7 слов, без кавычек\n" +
            "- главный тезис 1\n- главный тезис 2\n- главный тезис 3\n" +
            "Ничего больше не пиши. Отвечай на языке исходного текста.",
        kind = TemplateKind.TEXT, mode = TemplateMode.AGGREGATE, temperature = 0.2f, builtin = true, sort = 0,
    )

    suspend fun generate(repo: Repository, noteId: Long, model: ModelInfo, llm: LlmSession): Pair<String?, String?> {
        val raw = AiProcessor(repo).run(noteId, null, template, model, llm) { _, _ -> }
        return parse(raw)
    }

    /** @return (title, summary as "- a\n- b\n- c") */
    fun parse(input: String): Pair<String?, String?> {
        val raw = AiProcessor.stripThinking(input)
        var title: String? = null
        val bullets = ArrayList<String>()
        for (line in raw.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = Regex("^\\**\\s*(НАЗВАНИЕ|Название|TITLE|Title|Заголовок)\\s*\\**\\s*[:：]\\s*\\**\\s*(.+)$").find(line)
            when {
                m != null && title == null -> title = m.groupValues[2]
                line.startsWith("-") || line.startsWith("•") || line.startsWith("*") || Regex("^\\d+[.)]\\s").containsMatchIn(line) ->
                    bullets += line.replace(Regex("^(?:[-•*]+|\\d+[.)])\\s*"), "").trim()
            }
        }
        title = title?.trim('"', '«', '»', '*', ' ', '.')?.take(80)?.ifBlank { null }
        val summary = bullets.filter { it.isNotBlank() }.take(3).joinToString("\n") { "- $it" }.ifBlank { null }
        if (title == null && summary == null) {
            // the model ignored the format: use the first line as a title
            title = raw.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(60)
        }
        return title to summary
    }

    private val defaultTitle = Regex(
        "^(Запись \\d|Импорт|Аудио|audio|voice|AUD|PTT|Recording|rec|record|VID|video|WhatsApp|Голосов|Новая запись|\\d{4}-\\d{2}-\\d{2}|\\d{8})",
        RegexOption.IGNORE_CASE,
    )

    fun isDefaultTitle(title: String) = defaultTitle.containsMatchIn(title.trim())

    /** Title without an LLM: the first sentence of the transcript, cut at a word boundary. */
    fun fallbackTitle(repo: Repository, noteId: Long): String? {
        val text = repo.segments(noteId).filter { !it.draft }.joinToString(" ") { it.text.trim() }.trim()
        if (text.isEmpty()) return null
        val sentence = text.split(Regex("(?<=[.!?…])\\s+")).firstOrNull { it.split(' ').size >= 3 } ?: text
        val clean = sentence.trim().trimEnd('.', '!', '?', '…', ',')
        if (clean.length <= 60) return clean.replaceFirstChar { it.uppercase() }
        val cut = clean.take(60)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 20 } ?: cut.length).trimEnd(',', ' ') + "…"
    }
}
