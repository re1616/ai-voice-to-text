package app.aivoicetotext

import app.aivoicetotext.engine.AiProcessor
import app.aivoicetotext.engine.AutoSummary
import app.aivoicetotext.engine.Diarizer
import app.aivoicetotext.engine.Fbank
import app.aivoicetotext.engine.Transcriber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class EngineTest {

    @Test
    fun fbankPeakFollowsFrequency() {
        // 0.5 s at 300 Hz, then 0.5 s at 3 kHz
        val x = FloatArray(16000) { i -> val f = if (i < 8000) 300.0 else 3000.0; (0.3 * sin(2 * PI * f * i / 16000)).toFloat() }
        val f = Fbank.compute(x)
        assertEquals(98, f.size) // 1 + (16000 - 400) / 160
        assertEquals(80, f[0].size)
        val early = f[10].indices.maxBy { f[10][it] }
        val late = f[85].indices.maxBy { f[85][it] }
        assertTrue("300 Hz bin $early should be below 3 kHz bin $late", early < late)
        assertTrue(f.all { row -> row.all { it.isFinite() } })
    }

    @Test
    fun clusteringFindsTwoSpeakers() {
        val rnd = Random(1)
        fun unit(v: FloatArray): FloatArray { val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(v.size) { v[it] / n } }
        val a = FloatArray(32) { rnd.nextFloat() - 0.5f }
        val b = FloatArray(32) { rnd.nextFloat() - 0.5f }
        val items = (0 until 40).map { i ->
            val base = if (i % 2 == 0) a else b
            unit(FloatArray(32) { base[it] + (rnd.nextFloat() - 0.5f) * 0.15f })
        }
        val auto = Diarizer.cluster(items, null)
        assertEquals(2, auto.size)
        assertTrue(auto.all { c -> c.all { it % 2 == c.first() % 2 } })
        assertEquals(3, Diarizer.cluster(items, 3).size)
    }

    @Test
    fun autoSummaryParsing() {
        val (t, s) = AutoSummary.parse("НАЗВАНИЕ: Встреча с подрядчиком\n- Сроки сдвинуты на неделю\n- Бюджет утверждён\n- Нужен новый дизайнер\n- лишний")
        assertEquals("Встреча с подрядчиком", t)
        assertEquals(3, s!!.lines().size)
        val (t2, _) = AutoSummary.parse("<think>\n</think>\n**Название:** План ремонта")
        assertEquals("План ремонта", AiProcessor.stripThinking(t2 ?: ""))
        assertTrue(AutoSummary.isDefaultTitle("Запись 16 окт., 05:02"))
        assertTrue(AutoSummary.isDefaultTitle("audio_2026-10-16_12-00-00"))
        assertTrue(!AutoSummary.isDefaultTitle("Лекция по физике"))
    }

    @Test
    fun hallucinationFilter() {
        assertTrue(Transcriber.isHallucination("Продолжение следует..."))
        assertTrue(Transcriber.isHallucination("♪♪"))
        assertTrue(!Transcriber.isHallucination("Добрый день, коллеги."))
    }

    @Test
    fun checklistPostProcess() {
        val t = app.aivoicetotext.data.PromptTemplate(1, "Задачи", "✅", "", "checklist", "aggregate", 0.2f, true, 0)
        val out = AiProcessor.postProcess("Вот задачи:\n1. Позвонить Ивану\n- Отправить отчёт\n* [ ] Купить билеты", t)
        assertNotNull(out)
        assertEquals(listOf("- [ ] Позвонить Ивану", "- [ ] Отправить отчёт", "- [ ] Купить билеты"), out.lines())
    }
}
