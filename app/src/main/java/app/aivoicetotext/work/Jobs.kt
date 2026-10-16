package app.aivoicetotext.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.aivoicetotext.R
import app.aivoicetotext.ui.MainActivity

object Jobs {
    const val CH_RECORD = "recording"
    const val CH_WORK = "processing"
    const val CH_DONE = "done"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RECORD, "Запись", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_WORK, "Расшифровка и ИИ", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Готовые заметки", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun constraints(context: Context): Constraints {
        val onlyCharging = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("onlyCharging", false)
        return Constraints.Builder().setRequiresCharging(onlyCharging).build()
    }

    fun transcribe(context: Context, noteId: Long, replace: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setInputData(workDataOf("noteId" to noteId))
            .setConstraints(constraints(context))
            .addTag("transcribe")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "transcribe-$noteId",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            req,
        )
    }

    fun cancelTranscription(context: Context, noteId: Long) =
        WorkManager.getInstance(context).cancelUniqueWork("transcribe-$noteId")

    fun ai(context: Context, resultId: Long) {
        val req = OneTimeWorkRequestBuilder<AiWorker>()
            .setInputData(workDataOf("resultId" to resultId))
            .setConstraints(constraints(context))
            .addTag("ai")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("ai-$resultId", ExistingWorkPolicy.KEEP, req)
    }

    fun cancelAi(context: Context, resultId: Long) =
        WorkManager.getInstance(context).cancelUniqueWork("ai-$resultId")

    /** Live state of background post-processing, shown on the note screen (noteId -> text). */
    val activity = kotlinx.coroutines.flow.MutableStateFlow<Map<Long, String>>(emptyMap())

    fun setActivity(noteId: Long, text: String?) {
        activity.value = if (text == null) activity.value - noteId else activity.value + (noteId to text)
    }

    /** Chains speaker detection and the automatic summary after a finished transcription. */
    fun afterTranscription(app: app.aivoicetotext.App, noteId: Long) {
        val s = app.prefs.value
        when {
            s.autoSpeakers && app.models.speakerModel() != null -> diarize(app, noteId, 0, thenSummary = true)
            s.autoSummary && app.models.llm() != null -> summary(app, noteId)
        }
    }

    fun summary(context: Context, noteId: Long) {
        val req = OneTimeWorkRequestBuilder<SummaryWorker>()
            .setInputData(workDataOf("noteId" to noteId))
            .setConstraints(constraints(context))
            .addTag("summary")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("summary-$noteId", ExistingWorkPolicy.REPLACE, req)
    }

    fun diarize(context: Context, noteId: Long, speakers: Int, thenSummary: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<DiarizeWorker>()
            .setInputData(workDataOf("noteId" to noteId, "speakers" to speakers, "thenSummary" to thenSummary))
            .setConstraints(constraints(context))
            .addTag("diarize")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("diarize-$noteId", ExistingWorkPolicy.REPLACE, req)
    }

    fun openNoteIntent(context: Context, noteId: Long): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_NOTE)
            .putExtra(MainActivity.EXTRA_NOTE_ID, noteId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, noteId.toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun notifyDone(context: Context, noteId: Long, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openNoteIntent(context, noteId))
            .setAutoCancel(true)
            .build()
        nm.notify(("done$noteId").hashCode(), n)
    }
}

/** Shared helper: show a progress notification and try to run as a foreground service. */
suspend fun CoroutineWorker.foreground(notificationId: Int, title: String, text: String, progress: Int, max: Int, noteId: Long) {
    val n = NotificationCompat.Builder(applicationContext, Jobs.CH_WORK)
        .setSmallIcon(R.drawable.ic_stat_mic)
        .setContentTitle(title)
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(max, progress, max == 0)
        .setContentIntent(Jobs.openNoteIntent(applicationContext, noteId))
        .build()
    val info = when {
        Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else -> ForegroundInfo(notificationId, n)
    }
    // Starting a foreground service from the background is restricted on Android 12+.
    // If it is refused, the job keeps running as normal background work and resumes later if stopped.
    runCatching { setForeground(info) }
}
