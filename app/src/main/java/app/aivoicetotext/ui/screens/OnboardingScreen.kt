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
import androidx.compose.runtime.LaunchedEffect
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
import app.aivoicetotext.util.Device
import app.aivoicetotext.util.Fmt
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun OnboardingScreen(app: App, activity: MainActivity, done: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ram = remember { Device.totalRamGb(app) }
    val options = remember {
        val ids = listOf("whisper-base-q8", "whisper-small-q5", "whisper-turbo-q5")
        app.models.catalog().filter { it.type == ModelType.STT && it.id in ids }.sortedBy { ids.indexOf(it.id) }
    }
    var selected by remember { mutableStateOf(options.firstOrNull { recommended(it, ram) }?.id ?: options.getOrNull(1)?.id) }

    LaunchedEffect(Unit) { activity.requestBasicPermissions() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = app.models.import(uri)
            Toast.makeText(ctx, r.fold({ "Модель добавлена: ${it.name}" }, { "Ошибка: ${it.message}" }), Toast.LENGTH_LONG).show()
            if (r.isSuccess) done()
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AI Voice to Text", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Запись и расшифровка речи прямо на телефоне. Звук и текст никуда не отправляются: " +
                    "интернет нужен только один раз — чтобы скачать модель распознавания.",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!Device.cpuSupported) {
                Text("Внимание: процессор этого телефона не поддерживает нужные инструкции (ARMv8.2 dotprod), распознавание работать не будет.",
                    color = MaterialTheme.colorScheme.error)
            }
            Text(String.format(Locale("ru"), "В вашем телефоне %.1f ГБ ОЗУ. Выберите модель:", ram), style = MaterialTheme.typography.titleSmall)
            if (BuildConfig.ALLOW_NETWORK) {
                options.forEach { m ->
                    val isSel = selected == m.id
                    Row(
                        Modifier.fillMaxWidth()
                            .border(if (isSel) 2.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .clickable { selected = m.id }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSel, onClick = { selected = m.id })
                        Column(Modifier.weight(1f)) {
                            Text(m.name + if (recommended(m, ram)) " · рекомендуем" else "", fontWeight = FontWeight.SemiBold)
                            Text("${Fmt.size(m.sizeBytes)} · ${m.description}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        val m = options.firstOrNull { it.id == selected } ?: return@Button
                        val err = app.models.startDownload(m)
                        if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                        else {
                            app.prefs.update { copy(mainModelId = m.id, liveModelId = if (m.id == "whisper-turbo-q5") liveModelId else m.id) }
                            Toast.makeText(ctx, "Загрузка началась. Записывать можно уже сейчас.", Toast.LENGTH_LONG).show()
                            done()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Скачать и начать") }
            } else {
                Text("Это офлайн-сборка: добавьте файл модели whisper.cpp (.bin), например ggml-small-q5_1.bin.")
            }
            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("У меня есть файл модели") }
            TextButton(onClick = done, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Пропустить") }
            Text(
                "Позже в разделе «Модели» можно скачать модель побольше для лекций и LLM для конспектов и списков задач.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
