package app.aivoicetotext.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DbHelper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                audio_path TEXT,
                pcm_path TEXT,
                language TEXT NOT NULL DEFAULT 'auto',
                detected_lang TEXT,
                folder_id INTEGER REFERENCES folders(id) ON DELETE SET NULL,
                tags TEXT NOT NULL DEFAULT '',
                pinned INTEGER NOT NULL DEFAULT 0,
                deleted_at INTEGER,
                status TEXT NOT NULL,
                progress_ms INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                model_id TEXT,
                speed REAL NOT NULL DEFAULT 0,
                snippet TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE TABLE folders(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE segments(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                text TEXT NOT NULL,
                draft INTEGER NOT NULL DEFAULT 0,
                edited INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX idx_segments_note ON segments(note_id, start_ms)")
        db.execSQL(
            """CREATE TABLE markers(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                time_ms INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE templates(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                emoji TEXT NOT NULL,
                system_prompt TEXT NOT NULL,
                kind TEXT NOT NULL,
                mode TEXT NOT NULL,
                temperature REAL NOT NULL,
                builtin INTEGER NOT NULL DEFAULT 0,
                sort INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE ai_results(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                template_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                kind TEXT NOT NULL,
                content TEXT NOT NULL DEFAULT '',
                model_id TEXT,
                status TEXT NOT NULL,
                progress INTEGER NOT NULL DEFAULT 0,
                total INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE ai_chunks(
                result_id INTEGER NOT NULL REFERENCES ai_results(id) ON DELETE CASCADE,
                level INTEGER NOT NULL,
                idx INTEGER NOT NULL,
                output TEXT NOT NULL,
                PRIMARY KEY(result_id, level, idx)
            )"""
        )
        // Full-text index; docid = note id. Text is pre-normalised (lower case, ё -> е).
        db.execSQL("CREATE VIRTUAL TABLE notes_fts USING fts4(title, body, tokenize=unicode61)")
        seedTemplates(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first schema; explicit migrations go here.
    }

    private fun seedTemplates(db: SQLiteDatabase) {
        val items = listOf(
            arrayOf(
                "Очистка", "🧹", TemplateKind.TEXT, TemplateMode.TRANSFORM, "0.2",
                "Ты редактор расшифровок устной речи. Перепиши текст в чистом литературном виде: " +
                    "убери слова-паразиты, повторы и оговорки, расставь знаки препинания, раздели на абзацы " +
                    "и при необходимости добавь короткие подзаголовки в формате Markdown (##). " +
                    "Не добавляй ничего от себя и не сокращай смысл. Отвечай на языке исходного текста."
            ),
            arrayOf(
                "Краткая выжимка", "📋", TemplateKind.TEXT, TemplateMode.AGGREGATE, "0.3",
                "Сделай краткую выжимку расшифровки: 5–10 главных тезисов маркированным списком (- ...). " +
                    "Пиши коротко и по существу, без вступлений. Отвечай на языке исходного текста."
            ),
            arrayOf(
                "Задачи", "✅", TemplateKind.CHECKLIST, TemplateMode.AGGREGATE, "0.2",
                "Найди в расшифровке все упомянутые задачи, поручения и договорённости. " +
                    "Выведи каждую отдельной строкой в формате \"- [ ] задача\". Если упомянуты срок или " +
                    "ответственный, добавь их в скобках. Если задач нет, ответь одной строкой: Задач не найдено."
            ),
            arrayOf(
                "Вопросы и решения", "💬", TemplateKind.TEXT, TemplateMode.AGGREGATE, "0.3",
                "Выдели из расшифровки встречи: 1) принятые решения, 2) открытые вопросы, 3) следующие шаги. " +
                    "Оформи тремя разделами Markdown с маркированными списками. Отвечай на языке исходного текста."
            ),
            arrayOf(
                "Конспект лекции", "🎓", TemplateKind.TEXT, TemplateMode.AGGREGATE, "0.3",
                "Оформи расшифровку как структурированный конспект лекции: тема, основные разделы с " +
                    "подзаголовками (##), ключевые определения и выводы. Отвечай на языке исходного текста."
            ),
        )
        items.forEachIndexed { i, a ->
            db.insert("templates", null, ContentValues().apply {
                put("name", a[0]); put("emoji", a[1]); put("kind", a[2]); put("mode", a[3])
                put("temperature", a[4].toFloat()); put("system_prompt", a[5])
                put("builtin", 1); put("sort", i)
            })
        }
    }

    companion object {
        const val NAME = "notes.db"
        const val VERSION = 1
    }
}
