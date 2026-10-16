package app.aivoicetotext.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import app.aivoicetotext.App
import app.aivoicetotext.data.Note
import app.aivoicetotext.data.NoteStatus
import app.aivoicetotext.models.ModelInfo
import app.aivoicetotext.models.ModelType
import app.aivoicetotext.util.Fmt
import app.aivoicetotext.work.Jobs
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Plain-language names for the catalog models. */
fun ModelInfo.title(): String = when (id) {
    "whisper-base-q8" -> "Быстрая"
    "whisper-small-q5" -> "Сбалансированная"
    "whisper-small-q8" -> "Точная"
    "whisper-turbo-q5" -> "Максимальная"
    "gemma3-1b" -> "Лёгкий помощник"
    "qwen3-1.7b" -> "Стандартный помощник"
    "qwen3-4b-2507" -> "Умный помощник"
    "gemma3-4b" -> "Умный помощник (Gemma)"
    "speaker-resnet34" -> "Определение голосов"
    else -> name
}

/** Typical speed on a mid-range phone, x realtime (replaced by the measured value once known). */
private fun ModelInfo.typicalSpeed(): Double = when (id) {
    "whisper-base-q8" -> 8.0
    "whisper-small-q5" -> 3.0
    "whisper-small-q8" -> 2.5
    "whisper-turbo-q5" -> 1.2
    else -> 0.0
}

/** "≈ 20 мин на час записи" */
fun speedLabel(m: ModelInfo, measured: Float): String? {
    if (m.type != ModelType.STT) return null
    val x = if (measured > 0) measured.toDouble() else m.typicalSpeed()
    if (x <= 0) return null
    val min = ceil(60 / x).toInt()
    return "≈ $min мин на час записи" + if (measured > 0) "" else " (примерно)"
}

/** Human-friendly progress line for a note being processed. */
fun progressLine(app: App, n: Note): String? = when (n.status) {
    NoteStatus.TRANSCRIBING -> {
        val remaining = (n.durationMs - n.progressMs).coerceAtLeast(0)
        if (n.speed > 0.05 && n.progressMs > 0) {
            val min = (remaining / n.speed / 60_000.0).roundToInt()
            if (min <= 0) "Почти готово" else "Готово примерно через ${min.coerceAtLeast(1)} мин"
        } else "Распознаю речь…"
    }
    NoteStatus.QUEUED -> {
        val pos = app.repo.queuePosition(n.id)
        when {
            app.prefs.value.onlyCharging -> "Ждёт зарядки (настройка «Только на зарядке»)"
            pos > 1 -> "В очереди: ${pos}-я"
            else -> "Скоро начнётся"
        }
    }
    NoteStatus.ENCODING -> "Сохраняю аудио…"
    NoteStatus.DECODING -> "Готовлю аудио…"
    NoteStatus.RECORDING -> "Идёт запись"
    else -> null
}

data class Fix(val label: String, val action: () -> Unit)

/** Every error comes with a one-tap fix where possible. */
fun errorFix(app: App, ctx: Context, n: Note): Fix? {
    val err = n.error.orEmpty()
    return when {
        n.status == NoteStatus.NO_MODEL -> {
            val m = app.models.recommended(ModelType.STT)
            if (m != null && app.models.networkAllowed) Fix("Скачать модель (${Fmt.size(m.sizeBytes)})") {
                app.models.startDownload(m)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                    ?: run { app.repo.setStatus(n.id, NoteStatus.QUEUED); Jobs.transcribe(ctx, n.id, replace = true) }
            } else null
        }
        err.contains("места", ignoreCase = true) || err.contains("space", ignoreCase = true) ->
            Fix("Освободить место") { ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        err.contains("Процессор") -> null
        else -> Fix("Повторить") { app.repo.setStatus(n.id, NoteStatus.QUEUED); Jobs.transcribe(ctx, n.id, replace = true) }
    }
}

/** Friendly wording for technical errors. */
fun friendlyError(err: String?): String = when {
    err == null -> "Что-то пошло не так"
    err.contains("dotprod") || err.contains("Процессор") -> "Этот телефон слишком старый для распознавания речи на устройстве. Аудио сохранено — его можно прослушать и отправить."
    err.contains("памят", ignoreCase = true) -> "Не хватило памяти. Закройте другие приложения или выберите модель полегче в разделе «Модели»."
    err.contains("Остановлено") -> "Вы остановили расшифровку."
    else -> err
}

/** Date group header for the notes list. */
fun dateGroup(time: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = time }
    val startToday = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val day = 24 * 3600_000L
    return when {
        time >= startToday -> "Сегодня"
        time >= startToday - day -> "Вчера"
        time >= startToday - 6 * day -> "На этой неделе"
        c.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> MONTHS[c.get(Calendar.MONTH)]
        else -> MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR)
    }
}

private val MONTHS = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")

fun View.haptic() {
    performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
}

fun shareText(ctx: Context, text: String, title: String = "Отправить") {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
