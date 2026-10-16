package app.aivoicetotext.record

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.aivoicetotext.App
import app.aivoicetotext.R
import app.aivoicetotext.audio.SAMPLE_RATE
import app.aivoicetotext.data.NoteStatus
import app.aivoicetotext.data.Segment
import app.aivoicetotext.engine.Engines
import app.aivoicetotext.engine.Transcriber
import app.aivoicetotext.engine.WhisperSession
import app.aivoicetotext.engine.withNativeAbort
import app.aivoicetotext.ui.MainActivity
import app.aivoicetotext.util.Device
import app.aivoicetotext.util.Fmt
import app.aivoicetotext.work.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

data class RecorderUi(
    val active: Boolean = false,
    val noteId: Long = -1,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    val levels: List<Float> = emptyList(),
    val liveText: String = "",
    val liveStatus: String? = null,
    val markers: Int = 0,
)

object RecorderState {
    internal val _ui = MutableStateFlow(RecorderUi())
    val ui: StateFlow<RecorderUi> = _ui.asStateFlow()
}

/** Foreground service (type microphone): records 16 kHz mono PCM and optionally transcribes live. */
class RecordingService : LifecycleService() {

    private var record: AudioRecord? = null
    private var recordJob: Job? = null
    private var liveJob: Job? = null
    private var noteId = -1L
    private var pcmFile: File? = null
    @Volatile private var paused = false
    @Volatile private var samplesWritten = 0L
    private val phrases = Channel<Pair<Long, ShortArray>>(capacity = 4)
    private var modeListener: Any? = null

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_LANGUAGE))
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_MARK -> mark()
            ACTION_STOP -> stop(discard = false)
            ACTION_DISCARD -> stop(discard = true)
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun start(languageOverride: String?) {
        if (recordJob != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        val app = application as App
        val lang = languageOverride ?: app.prefs.value.language
        val title = "Запись " + SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date())
        val dir = File(filesDir, "pcm").apply { mkdirs() }
        noteId = app.repo.createNote(title, lang, NoteStatus.RECORDING, audioPath = null, pcmPath = null)
        val file = File(dir, "$noteId.pcm")
        pcmFile = file
        app.repo.updateNote(noteId, ContentValues().apply { put("pcm_path", file.path) })

        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification(),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            app.repo.deleteForever(noteId)
            stopSelf(); return
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, SAMPLE_RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            app.repo.setStatus(noteId, NoteStatus.ERROR, "Микрофон недоступен")
            stopSelf(); return
        }
        record = rec
        samplesWritten = 0
        paused = false
        RecorderState._ui.value = RecorderUi(active = true, noteId = noteId)
        registerCallListener()

        rec.startRecording()
        recordJob = lifecycleScope.launch(Dispatchers.IO) { recordLoop(rec, file) }
        liveJob = lifecycleScope.launch(Dispatchers.Default) { liveLoop() }
    }

    private suspend fun recordLoop(rec: AudioRecord, file: File) {
        val frame = SAMPLE_RATE / 10 // 100 ms
        val buf = ShortArray(frame)
        val bytes = ByteArray(frame * 2)
        val levels = ArrayDeque<Float>()
        // live phrase detection (energy based)
        val phrase = ShortArrayBuilder()
        var phraseStartSample = 0L
        var silenceMs = 0
        var voicedMs = 0
        var noiseFloor = 0.01f
        var lastFlush = System.currentTimeMillis()
        var lastUi = 0L

        BufferedOutputStream(FileOutputStream(file, true), 1 shl 16).use { os ->
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                if (paused) { kotlinx.coroutines.delay(100); continue }
                val n = rec.read(buf, 0, frame)
                if (n <= 0) { kotlinx.coroutines.delay(20); continue } // e.g. right after pause/resume
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    bytes[2 * i] = (v and 0xff).toByte()
                    bytes[2 * i + 1] = ((v shr 8) and 0xff).toByte()
                }
                os.write(bytes, 0, n * 2)
                samplesWritten += n
                val now = System.currentTimeMillis()
                if (now - lastFlush > 5000) { os.flush(); lastFlush = now } // at most ~5 s lost on a crash

                var sum = 0.0
                for (i in 0 until n) { val f = buf[i] / 32768.0; sum += f * f }
                val rms = sqrt(sum / n).toFloat()
                levels.addLast(rms); if (levels.size > 60) levels.removeFirst()

                // phrase segmentation for the live draft
                if (phrase.size == 0) phraseStartSample = samplesWritten - n
                phrase.append(buf, n)
                val voiced = rms > maxOf(noiseFloor * 3f, 0.012f)
                if (voiced) { voicedMs += 100; silenceMs = 0 } else { silenceMs += 100; noiseFloor = noiseFloor * 0.95f + rms * 0.05f }
                val phraseMs = phrase.size * 1000L / SAMPLE_RATE
                val cut = (silenceMs >= 700 && phraseMs >= 1500) || phraseMs >= 25_000
                if (cut) {
                    if (voicedMs >= 400) phrases.trySend(phraseStartSample * 1000 / SAMPLE_RATE to phrase.toArray())
                    phrase.clear(); voicedMs = 0; silenceMs = 0
                } else if (phraseMs > 2000 && voicedMs == 0) {
                    phrase.clear(); silenceMs = 0
                }

                if (now - lastUi > 150) {
                    lastUi = now
                    val ui = RecorderState._ui.value
                    RecorderState._ui.value = ui.copy(elapsedMs = samplesWritten * 1000 / SAMPLE_RATE, levels = levels.toList())
                }
            }
            os.flush()
        }
    }

    /** Draft transcription of finished phrases with the small live model. */
    private suspend fun liveLoop() {
        val app = application as App
        if (!Device.cpuSupported) return
        var session: WhisperSession? = null
        var locked = false
        try {
            for ((startMs, pcm) in phrases) {
                if (!app.prefs.value.liveEnabled) continue
                if (session == null) {
                    val model = app.models.liveStt() ?: run {
                        updateLive(status = "Живой текст: нет модели распознавания"); null
                    } ?: continue
                    if (!Engines.lock.tryLock()) {
                        updateLive(status = "Живой текст недоступен: идёт другая обработка"); continue
                    }
                    locked = true
                    Engines.holder = "live"
                    updateLive(status = "Загрузка модели ${model.name}…")
                    session = withContext(Dispatchers.Default) { WhisperSession.open(app.models.fileFor(model)) }
                    if (session == null) { updateLive(status = "Не удалось загрузить модель"); Engines.holder = null; Engines.lock.unlock(); locked = false; continue }
                    updateLive(status = null)
                }
                val s = session ?: continue
                val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
                val lang = app.repo.note(noteId)?.language ?: "auto"
                val segs = withNativeAbort({ s.abort() }) {
                    s.transcribe(floats, lang, Engines.threads(app.prefs.value), app.prefs.value.vocabulary.ifBlank { null })
                } ?: continue
                val clean = segs.map { it.text.trim() }.filter { it.isNotEmpty() && !Transcriber.isHallucination(it) }
                if (clean.isEmpty()) continue
                val text = clean.joinToString(" ")
                val ui = RecorderState._ui.value
                RecorderState._ui.value = ui.copy(liveText = (ui.liveText + " " + text).trim().takeLast(4000))
                val endMs = startMs + pcm.size * 1000L / SAMPLE_RATE
                app.repo.insertSegments(noteId, listOf(Segment(0, noteId, startMs, endMs, text, draft = true, edited = false)), draft = true)
            }
        } finally {
            session?.close()
            if (locked) { Engines.holder = null; Engines.lock.unlock() }
        }
    }

    private fun updateLive(status: String?) {
        RecorderState._ui.value = RecorderState._ui.value.copy(liveStatus = status)
    }

    private fun setPaused(p: Boolean) {
        val rec = record ?: return
        paused = p
        if (p) rec.stop() else rec.startRecording()
        RecorderState._ui.value = RecorderState._ui.value.copy(paused = p)
        refreshNotification()
    }

    private fun mark() {
        if (noteId < 0) return
        (application as App).repo.addMarker(noteId, samplesWritten * 1000 / SAMPLE_RATE)
        RecorderState._ui.value = RecorderState._ui.value.copy(markers = RecorderState._ui.value.markers + 1)
    }

    private fun stop(discard: Boolean) {
        val app = application as App
        val id = noteId
        val rec = record
        val job = recordJob
        record = null
        recordJob = null
        unregisterCallListener()
        phrases.close()
        lifecycleScope.launch {
            job?.cancel()
            job?.join()
            runCatching { rec?.stop() }
            rec?.release()
            liveJob?.join()
            if (id >= 0) {
                if (discard) {
                    app.repo.deleteForever(id)
                    pcmFile?.delete()
                } else {
                    val dur = samplesWritten * 1000 / SAMPLE_RATE
                    app.repo.updateNote(id, ContentValues().apply {
                        put("duration_ms", dur); put("status", NoteStatus.QUEUED); put("progress_ms", 0)
                    })
                    if (dur < 300) {
                        app.repo.deleteForever(id); pcmFile?.delete()
                    } else {
                        Jobs.transcribe(app, id)
                    }
                }
            }
            RecorderState._ui.value = RecorderUi()
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        unregisterCallListener()
        super.onDestroy()
    }

    // pause automatically during phone calls
    private fun registerCallListener() {
        if (Build.VERSION.SDK_INT < 31) return
        val am = getSystemService(AudioManager::class.java)
        val l = AudioManager.OnModeChangedListener { mode ->
            if ((mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) && !paused) setPaused(true)
        }
        am.addOnModeChangedListener(ContextCompat.getMainExecutor(this), l)
        modeListener = l
    }

    private fun unregisterCallListener() {
        if (Build.VERSION.SDK_INT < 31) return
        val l = modeListener as? AudioManager.OnModeChangedListener ?: return
        getSystemService(AudioManager::class.java).removeOnModeChangedListener(l)
        modeListener = null
    }

    private fun refreshNotification() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIF_ID, notification())
    }

    private fun action(action: String, req: Int): PendingIntent =
        PendingIntent.getService(this, req, Intent(this, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, Jobs.CH_RECORD)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(if (paused) "Запись на паузе" else "Идёт запись")
            .setContentText("Нажмите, чтобы открыть")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (!paused) {
            b.setUsesChronometer(true)
            b.setWhen(System.currentTimeMillis() - samplesWritten * 1000 / SAMPLE_RATE)
        }
        b.addAction(0, if (paused) "Продолжить" else "Пауза", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
        b.addAction(0, "Метка", action(ACTION_MARK, 2))
        b.addAction(0, "Стоп", action(ACTION_STOP, 3))
        return b.build()
    }

    private class ShortArrayBuilder {
        private var data = ShortArray(SAMPLE_RATE * 4)
        var size = 0; private set
        fun append(src: ShortArray, n: Int) {
            if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
            System.arraycopy(src, 0, data, size, n)
            size += n
        }
        fun clear() { size = 0 }
        fun toArray(): ShortArray = data.copyOf(size)
    }

    companion object {
        const val NOTIF_ID = 1001
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_MARK = "mark"
        const val ACTION_STOP = "stop"
        const val ACTION_DISCARD = "discard"
        const val EXTRA_LANGUAGE = "language"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(ACTION_START))
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, RecordingService::class.java).setAction(action))
        }

        @Suppress("unused")
        fun elapsedLabel(ms: Long) = Fmt.clock(ms)
    }
}
