package app.aivoicetotext.data

object NoteStatus {
    const val RECORDING = "RECORDING"
    const val QUEUED = "QUEUED"
    const val ENCODING = "ENCODING"
    const val DECODING = "DECODING"
    const val TRANSCRIBING = "TRANSCRIBING"
    const val DONE = "DONE"
    const val ERROR = "ERROR"
    const val NO_MODEL = "NO_MODEL"

    fun isBusy(s: String) = s == QUEUED || s == ENCODING || s == DECODING || s == TRANSCRIBING

    fun label(s: String) = when (s) {
        RECORDING -> "Идёт запись"
        QUEUED -> "В очереди"
        ENCODING -> "Сохранение аудио"
        DECODING -> "Подготовка аудио"
        TRANSCRIBING -> "Распознавание"
        DONE -> "Готово"
        ERROR -> "Ошибка"
        NO_MODEL -> "Нужна модель"
        else -> s
    }
}

data class Note(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val audioPath: String?,
    val pcmPath: String?,
    val language: String,
    val detectedLanguage: String?,
    val folderId: Long?,
    val tags: List<String>,
    val pinned: Boolean,
    val deletedAt: Long?,
    val status: String,
    val progressMs: Long,
    val error: String?,
    val modelId: String?,
    val speed: Double,
    val snippet: String,
    val updatedAt: Long,
)

data class Segment(
    val id: Long,
    val noteId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val draft: Boolean,
    val edited: Boolean,
)

data class Folder(val id: Long, val name: String)

data class Marker(val id: Long, val noteId: Long, val timeMs: Long)

object AiStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val ERROR = "ERROR"
    const val CANCELLED = "CANCELLED"
}

data class AiResult(
    val id: Long,
    val noteId: Long,
    val templateId: Long,
    val title: String,
    val kind: String,
    val content: String,
    val modelId: String?,
    val status: String,
    val progress: Int,
    val total: Int,
    val error: String?,
    val createdAt: Long,
)

object TemplateKind {
    const val TEXT = "text"
    const val CHECKLIST = "checklist"
}

object TemplateMode {
    /** every part is processed independently and the outputs are concatenated */
    const val TRANSFORM = "transform"
    /** outputs of the parts are merged into one result (map-reduce) */
    const val AGGREGATE = "aggregate"
}

data class PromptTemplate(
    val id: Long,
    val name: String,
    val emoji: String,
    val systemPrompt: String,
    val kind: String,
    val mode: String,
    val temperature: Float,
    val builtin: Boolean,
    val sort: Int,
)
