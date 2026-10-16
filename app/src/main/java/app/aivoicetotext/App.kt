package app.aivoicetotext

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.aivoicetotext.audio.PcmFile
import app.aivoicetotext.data.AiStatus
import app.aivoicetotext.data.DbHelper
import app.aivoicetotext.data.NoteStatus
import app.aivoicetotext.data.Prefs
import app.aivoicetotext.data.Repository
import app.aivoicetotext.models.ModelManager
import app.aivoicetotext.record.QuickRecordActivity
import app.aivoicetotext.record.RecorderState
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.aivoicetotext.work.Jobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class App : Application() {

    lateinit var prefs: Prefs; private set
    lateinit var models: ModelManager; private set
    lateinit var repo: Repository; private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = Repository(DbHelper(this))
        models = ModelManager(this, prefs)
        Jobs.createChannels(this)
        runCatching {
            // launcher long-press shortcut: start a recording
            val sc = ShortcutInfoCompat.Builder(this, "record")
                .setShortLabel("Новая запись")
                .setIcon(IconCompat.createWithResource(this, R.drawable.ic_stat_mic))
                .setIntent(Intent(this, QuickRecordActivity::class.java).setAction(Intent.ACTION_VIEW))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(this, sc)
            val dictate = ShortcutInfoCompat.Builder(this, "dictate")
                .setShortLabel("Диктовка в буфер")
                .setIcon(IconCompat.createWithResource(this, R.drawable.ic_stat_mic))
                .setIntent(Intent(this, app.aivoicetotext.ui.DictationActivity::class.java).setAction(Intent.ACTION_VIEW))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(this, dictate)
        }
        scope.launch(Dispatchers.IO) {
            recoverInterruptedWork()
            purgeTrash()
            runCatching { models.refresh() }
        }
    }

    /** Re-queues recordings and jobs interrupted by a crash, reboot or the system killing the app. */
    private fun recoverInterruptedWork() {
        if (!RecorderState.ui.value.active) {
            for (n in repo.notesWithStatus(NoteStatus.RECORDING)) {
                val pcm = n.pcmPath?.let { File(it) }
                if (pcm == null || !pcm.exists() || pcm.length() < 16_000) {
                    repo.deleteForever(n.id); pcm?.delete(); continue
                }
                repo.updateNote(n.id, ContentValues().apply {
                    put("duration_ms", PcmFile.durationMs(pcm)); put("status", NoteStatus.QUEUED)
                })
                Jobs.transcribe(this, n.id)
            }
        }
        for (n in repo.notesWithStatus(NoteStatus.QUEUED, NoteStatus.ENCODING, NoteStatus.DECODING, NoteStatus.TRANSCRIBING)) {
            Jobs.transcribe(this, n.id)
        }
        for (r in repo.aiResultsWithStatus(AiStatus.QUEUED, AiStatus.RUNNING)) Jobs.ai(this, r.id)
    }

    private fun purgeTrash() {
        val limit = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        for (n in repo.trashOlderThan(limit)) deleteNoteFiles(repo.deleteForever(n.id)?.audioPath, n.pcmPath)
    }

    fun deleteNoteFiles(audioPath: String?, pcmPath: String?) {
        audioPath?.let { File(it).delete() }
        pcmPath?.let { File(it).delete() }
    }

    /** Copies a shared/picked audio or video file into app storage and queues transcription. */
    suspend fun importAudio(uri: Uri): Long = withContext(Dispatchers.IO) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Аудио"
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 } ?: "audio"
        val title = name.substringBeforeLast('.').ifBlank { "Импорт" }
        val id = repo.createNote(title, prefs.value.language, NoteStatus.QUEUED, audioPath = null, pcmPath = null)
        val dir = File(filesDir, "audio").apply { mkdirs() }
        val target = File(dir, "$id.$ext")
        try {
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it, 1 shl 16) } }
                ?: error("Не удалось открыть файл")
        } catch (e: Exception) {
            repo.deleteForever(id)
            target.delete()
            throw e
        }
        repo.updateNote(id, ContentValues().apply { put("audio_path", target.path) })
        Jobs.transcribe(this@App, id)
        id
    }

    /** Re-opens the database after a backup restore. */
    fun reopenDatabase() {
        repo = Repository(DbHelper(this))
    }
}

/** Installs finished model downloads (verification + rename). */
class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        val pending = goAsync()
        app.scope.launch {
            try { app.models.refresh() } finally { pending.finish() }
        }
    }
}
