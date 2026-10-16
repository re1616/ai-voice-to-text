package app.aivoicetotext.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aivoicetotext.App
import app.aivoicetotext.BuildConfig
import app.aivoicetotext.models.ModelType
import app.aivoicetotext.ui.MainActivity
import app.aivoicetotext.ui.title
import app.aivoicetotext.util.Device
import app.aivoicetotext.util.Fmt
import kotlinx.coroutines.launch

/** One screen, one button: the recommended model is chosen automatically. */
@Composable
fun OnboardingScreen(app: App, @Suppress("UNUSED_PARAMETER") activity: MainActivity, done: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recommended = remember { app.models.recommended(ModelType.STT) }
    val options = remember {
        val ids = listOf("whisper-base-q8", "whisper-small-q5", "whisper-turbo-q5")
        app.models.catalog().filter { it.id in ids }.sortedBy { ids.indexOf(it.id) }
    }
    var selected by remember { mutableStateOf(recommended?.id) }
    var more by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = app.models.import(uri)
            Toast.makeText(ctx, r.fold({ "Модель добавлена" }, { "Не подошёл файл: ${it.message}" }), Toast.LENGTH_LONG).show()
            if (r.isSuccess) done()
        }
    }

    fun start() {
        val m = options.firstOrNull { it.id == selected } ?: recommended ?: return
        val err = app.models.startDownload(m)
        if (err != null) { Toast.makeText(ctx, err, Toast.LENGTH_LONG).show(); return }
        app.prefs.update { copy(mainModelId = m.id, liveModelId = if (m.id == "whisper-turbo-q5") liveModelId else m.id) }
        done()
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = done) { Text("Пропустить") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("🎙️", style = MaterialTheme.typography.displayMedium)
                Text("Голос → текст", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Benefit("🔒", "Всё остаётся на телефоне", "Звук и текст никуда не отправляются")
                Benefit("📝", "Текст с таймкодами", "Лекции, встречи, голосовые из мессенджеров")
                Benefit("✨", "Выжимка и задачи", "Главное из записи — сразу после расшифровки")
                if (!Device.cpuSupported) {
                    Text("Этот телефон слишком старый для распознавания речи на устройстве: записывать можно, но текста не будет.",
                        color = MaterialTheme.colorScheme.error)
                }
                if (more) {
                    Text("Качество распознавания", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    if (BuildConfig.ALLOW_NETWORK) options.forEach { m ->
                        val isSel = selected == m.id
                        Row(
                            Modifier.fillMaxWidth()
                                .border(if (isSel) 2.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                .clickable { selected = m.id }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSel, onClick = { selected = m.id })
                            Column(Modifier.weight(1f)) {
                                Text(m.title() + if (m.id == recommended?.id) " · подходит вашему телефону" else "", fontWeight = FontWeight.SemiBold)
                                Text("${Fmt.size(m.sizeBytes)} · ${m.description}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("У меня есть файл модели") }
                }
            }
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (BuildConfig.ALLOW_NETWORK) {
                    val m = options.firstOrNull { it.id == selected } ?: recommended
                    Button(onClick = { start() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text("Начать" + (m?.let { " · скачать ${Fmt.size(it.sizeBytes)}" } ?: ""))
                    }
                    Text("Записывать можно сразу, пока модель скачивается", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Button(onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Выбрать файл модели") }
                }
                if (!more) TextButton(onClick = { more = true }) { Text("Другие варианты") }
            }
        }
    }
}

@Composable
private fun Benefit(icon: String, title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
