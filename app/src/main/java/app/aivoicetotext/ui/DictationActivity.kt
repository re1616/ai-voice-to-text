package app.aivoicetotext.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.aivoicetotext.App
import app.aivoicetotext.audio.SAMPLE_RATE
import app.aivoicetotext.data.NoteStatus
import app.aivoicetotext.data.Segment
import app.aivoicetotext.engine.Engines
import app.aivoicetotext.engine.Transcriber
import app.aivoicetotext.engine.WhisperSession
import app.aivoicetotext.engine.withNativeAbort
import app.aivoicetotext.models.ModelType
import app.aivoicetotext.util.Device
import app.aivoicetotext.util.Fmt
import app.aivoicetotext.work.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Quick dictation: speak → the text is copied to the clipboard. Opened from the
 * Quick Settings tile, the launcher shortcut or the "+" menu.
 */
class DictationActivity : ComponentActivity() {

    private enum class Stage { RECORDING, TRANSCRIBING, RESULT, ERROR }

    private var stage by mutableStateOf(Stage.RECORDING)
    private var elapsedMs by mutableStateOf(0L)
    private var level by mutableStateOf(0f)
    private var text by mutableStateOf("")
    private var message by mutableStateOf("")
    private var pcm = ShortArray(0)
    private var recordJob: Job? = null
    @Volatile private var stopRequested = false

    private val app get() = application as App

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else { stage = Stage.ERROR; message = "Нужен доступ к микрофону" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { Content() } }
        when {
            !Device.cpuSupported -> { stage = Stage.ERROR; message = "Этот телефон слишком старый для распознавания речи на устройстве." }
            app.models.installed().none { it.type == ModelType.STT } -> { stage = Stage.ERROR; message = "Сначала скачайте модель распознавания в приложении." }
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startRecording()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        stage = Stage.RECORDING
        stopRequested = false
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        recordJob = lifecycleScope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, SAMPLE_RATE))
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                withContext(Dispatchers.Main) { stage = Stage.ERROR; message = "Микрофон недоступен" }
                return@launch
            }
            val out = ByteArrayOutputStream()
            val buf = ShortArray(SAMPLE_RATE / 10)
            val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            var samples = 0L
            var silenceMs = 0
            var voicedMs = 0
            rec.startRecording()
            try {
                while (isActive && !stopRequested && samples < SAMPLE_RATE * 180L) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    bytes.clear()
                    var sum = 0.0
                    for (i in 0 until n) { bytes.putShort(buf[i]); val f = buf[i] / 32768.0; sum += f * f }
                    out.write(bytes.array(), 0, n * 2)
                    samples += n
                    val rms = sqrt(sum / n).toFloat()
                    if (rms > 0.015f) { voicedMs += 100; silenceMs = 0 } else silenceMs += 100
                    // stops by itself after 2.5 s of silence once something was said
                    if (voicedMs >= 600 && silenceMs >= 2500) break
                    withContext(Dispatchers.Main) { elapsedMs = samples * 1000 / SAMPLE_RATE; level = rms }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
            val raw = out.toByteArray()
            val sb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            pcm = ShortArray(sb.remaining()).also { sb.get(it) }
            withContext(Dispatchers.Main) { transcribe() }
        }
    }

    private fun transcribe() {
        stage = Stage.TRANSCRIBING
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        lifecycleScope.launch {
            try {
                if (pcm.size < SAMPLE_RATE / 2) error("Ничего не записано")
                // the main model unless it is the slow "maximum" one
                val main = app.models.mainStt()
                val model = (if (main != null && main.sizeBytes > 400_000_000) (app.models.liveStt() ?: main) else main)
                    ?: error("Нет модели распознавания")
                if (Engines.lock.isLocked) message = "Ждём завершения другой обработки…"
                val result = Engines.lock.withLock {
                    message = ""
                    val session = withContext(Dispatchers.Default) { WhisperSession.open(app.models.fileFor(model)) }
                        ?: error("Не удалось загрузить модель")
                    try {
                        val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
                        withNativeAbort({ session.abort() }) {
                            session.transcribe(floats, app.prefs.value.language, Engines.threads(app.prefs.value), app.prefs.value.vocabulary.ifBlank { null })
                        } ?: error("Ошибка распознавания")
                    } finally {
                        session.close()
                    }
                }
                text = result.map { it.text.trim() }.filter { it.isNotEmpty() && !Transcriber.isHallucination(it) }.joinToString(" ")
                if (text.isBlank()) error("Речь не распознана, попробуйте ещё раз")
                copy(text)
                stage = Stage.RESULT
            } catch (e: Exception) {
                stage = Stage.ERROR
                message = e.message ?: "Ошибка"
            }
        }
    }

    private fun copy(t: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Диктовка", t))
        Toast.makeText(this, "Текст скопирован — вставьте его в любое приложение", Toast.LENGTH_SHORT).show()
    }

    /** Saves the dictation as a regular note (audio is kept and re-transcribed with timestamps). */
    private fun saveAsNote() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = File(filesDir, "pcm").apply { mkdirs() }
            val title = text.split(Regex("\\s+")).take(7).joinToString(" ").ifBlank { "Диктовка" }
            val id = app.repo.createNote(title, app.prefs.value.language, NoteStatus.QUEUED, audioPath = null, pcmPath = null)
            val f = File(dir, "$id.pcm")
            val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) bb.putShort(s)
            f.writeBytes(bb.array())
            app.repo.updateNote(id, ContentValues().apply { put("pcm_path", f.path); put("duration_ms", pcm.size * 1000L / SAMPLE_RATE) })
            app.repo.insertSegments(id, listOf(Segment(0, id, 0, pcm.size * 1000L / SAMPLE_RATE, text, draft = true, edited = false)), draft = true)
            Jobs.transcribe(app, id)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@DictationActivity, "Сохранено в заметки", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Content() {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(),
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (stage) {
                        Stage.RECORDING -> {
                            Text("Говорите…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(Fmt.clock(elapsedMs), fontSize = 36.sp, fontWeight = FontWeight.Light)
                            Text("Остановится само после паузы", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val scale = 1f + (level * 8f).coerceAtMost(0.6f)
                            FilledIconButton(
                                onClick = { stopRequested = true },
                                modifier = Modifier.size((72 * scale).dp).semantics { contentDescription = "Закончить диктовку" },
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) { Icon(Icons.Default.Stop, null, Modifier.size(36.dp)) }
                            TextButton(onClick = { recordJob?.cancel(); finish() }) { Text("Отмена") }
                        }
                        Stage.TRANSCRIBING -> {
                            CircularProgressIndicator()
                            Text(message.ifBlank { "Распознаю…" }, style = MaterialTheme.typography.titleMedium)
                        }
                        Stage.RESULT -> {
                            Text("Скопировано ✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { copy(text) }) { Text("Копировать") }
                                OutlinedButton(onClick = {
                                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Отправить"))
                                }) { Text("Отправить") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { saveAsNote() }) { Text("Сохранить в заметки") }
                                TextButton(onClick = { text = ""; startRecording() }) { Text("Ещё раз") }
                            }
                            Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
                        }
                        Stage.ERROR -> {
                            Text(message, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (pcm.isNotEmpty() || message.contains("Речь")) OutlinedButton(onClick = { startRecording() }) { Text("Ещё раз") }
                                if (message.contains("модел")) OutlinedButton(onClick = {
                                    startActivity(Intent(this@DictationActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    finish()
                                }) { Text("Открыть приложение") }
                                Button(onClick = { finish() }) { Text("Закрыть") }
                            }
                        }
                    }
                }
            }
        }
    }
}
