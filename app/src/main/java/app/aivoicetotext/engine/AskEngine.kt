package app.aivoicetotext.engine

import app.aivoicetotext.data.Note
import app.aivoicetotext.data.Repository
import app.aivoicetotext.models.ModelInfo
import app.aivoicetotext.util.Fmt
import app.aivoicetotext.util.TextNorm
import java.util.Calendar

data class AskSource(val index: Int, val noteId: Long, val noteTitle: String, val date: Long, val startMs: Long, val text: String)

/**
 * "Ask your notes": finds relevant passages with the full-text index and lets the local
 * LLM answer using only those passages (retrieval-augmented generation, fully offline).
 */
class AskEngine(private val repo: Repository) {

    private val stop = setOf(
        "что", "как", "где", "когда", "кто", "какой", "какая", "какие", "каких", "это", "этот", "эта", "эти", "был", "была", "было",
        "были", "мне", "меня", "мой", "моя", "мои", "про", "для", "или", "его", "её", "ему", "они", "она", "оно", "все", "всё",
        "так", "уже", "еще", "ещё", "был", "есть", "нет", "надо", "чем", "там", "тут", "нас", "вас", "вам", "нам", "сказал",
        "говорил", "the", "and", "what", "when", "who", "where", "how", "did", "was", "were", "about", "with",
    )

    private fun stems(question: String): List<String> =
        TextNorm.normalize(question).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .filter { w -> w !in stop && !w.startsWith("запис") && !w.startsWith("заметк") }
            .map { if (it.length > 5) it.take(maxOf(4, it.length - 2)) else it }
            .distinct()
            .take(8)

    private fun timeFilter(question: String): Long? {
        val q = TextNorm.normalize(question)
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        return when {
            "сегодня" in q || "today" in q -> c.timeInMillis
            "вчера" in q || "yesterday" in q -> c.timeInMillis - 24 * 3600_000L
            "недел" in q || "week" in q -> c.timeInMillis - 7 * 24 * 3600_000L
            "месяц" in q || "month" in q -> c.timeInMillis - 31 * 24 * 3600_000L
            else -> null
        }
    }

    private class Passage(val note: Note, val startMs: Long, val text: String, val score: Int)

    fun retrieve(question: String, budgetChars: Int): List<AskSource> {
        val terms = stems(question)
        val since = timeFilter(question)
        val recentHint = "последн" in TextNorm.normalize(question) || "last" in TextNorm.normalize(question)

        val candidates = LinkedHashMap<Long, Note>()
        if (terms.isNotEmpty()) {
            for (id in repo.searchNoteIds(terms.joinToString(" OR ") { "$it*" }, 30)) repo.note(id)?.let { candidates[id] = it }
        }
        val timeNotes = when {
            since != null -> repo.recentNotes(15, since)
            recentHint || candidates.isEmpty() -> repo.recentNotes(if (recentHint) 3 else 6)
            else -> emptyList()
        }
        for (n in timeNotes) candidates.putIfAbsent(n.id, n)
        val inTime = timeNotes.map { it.id }.toSet()

        val passages = ArrayList<Passage>()
        for (note in candidates.values) {
            if (since != null && note.createdAt < since) continue
            note.summary?.let { passages += Passage(note, 0, "Краткое содержание: " + it.replace("\n", "; "), 1 + if (note.id in inTime) 3 else 0) }
            val segs = repo.segments(note.id).filter { !it.draft }
            var i = 0
            while (i < segs.size) {
                val start = segs[i].startMs
                val sb = StringBuilder()
                while (i < segs.size && (segs[i].startMs - start < 40_000 || sb.isEmpty()) && sb.length < 500) {
                    sb.append(segs[i].text.trim()).append(' '); i++
                }
                val text = sb.toString().trim()
                val norm = TextNorm.normalize(text)
                val words = norm.split(Regex("[^\\p{L}\\p{N}]+"))
                val score = terms.sumOf { t -> words.count { it.startsWith(t) } } + if (note.id in inTime) 2 else 0
                if (score > 0) passages += Passage(note, start, text, score)
            }
        }
        val picked = ArrayList<Passage>()
        var used = 0
        for (p in passages.sortedWith(compareByDescending<Passage> { it.score }.thenByDescending { it.note.createdAt })) {
            if (used + p.text.length > budgetChars) continue
            picked += p; used += p.text.length
            if (picked.size >= 12) break
        }
        return picked.sortedWith(compareByDescending<Passage> { it.note.createdAt }.thenBy { it.startMs })
            .mapIndexed { i, p -> AskSource(i + 1, p.note.id, p.note.title, p.note.createdAt, p.startMs, p.text) }
    }

    suspend fun answer(question: String, model: ModelInfo, llm: LlmSession, onText: (String) -> Unit): Pair<String, List<AskSource>> {
        // ~3 characters per token for Russian text, leave room for the answer
        val budget = ((llm.contextSize - 900) * 2.5).toInt().coerceAtLeast(1200)
        val sources = retrieve(question, budget)
        if (sources.isEmpty()) return "В ваших заметках не нашлось ничего по этому вопросу." to emptyList()
        val context = sources.joinToString("\n\n") { s ->
            "[${s.index}] «${s.noteTitle}», ${Fmt.date(s.date)}, ${Fmt.clock(s.startMs)}:\n${s.text}"
        }
        val system = "Ты помощник по личным голосовым заметкам пользователя. Отвечай кратко и по делу, " +
            "используя только приведённые фрагменты заметок. После каждого утверждения ставь номер фрагмента в квадратных скобках, например [2]. " +
            "Если во фрагментах нет ответа, так и скажи. Отвечай на языке вопроса."
        val user = "Фрагменты заметок:\n\n$context\n\nВопрос: $question"
        val text = AiProcessor.ask(llm, model, system, user, 700, 0.2f, onText)
        return text to sources
    }
}
