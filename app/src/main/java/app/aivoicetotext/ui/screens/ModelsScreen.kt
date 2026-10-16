package app.aivoicetotext.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aivoicetotext.App
import app.aivoicetotext.BuildConfig
import app.aivoicetotext.models.DownloadState
import app.aivoicetotext.models.ModelInfo
import app.aivoicetotext.models.ModelType
import app.aivoicetotext.ui.ConfirmDialog
import app.aivoicetotext.util.Device
import app.aivoicetotext.util.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(app: App, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.prefs.state.collectAsStateWithLifecycle()
    val downloads by app.models.downloads.collectAsStateWithLifecycle()
    val version by app.models.installedVersion.collectAsStateWithLifecycle()
    val catalog = remember { app.models.catalog() }
    val installed = remember(version) { app.models.installed() }
    val ram = remember { Device.totalRamGb(app) }
    var deleteFor by remember { mutableStateOf<ModelInfo?>(null) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) { app.models.refresh(); delay(1000) }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            val r = app.models.import(uri)
            importing = false
            Toast.makeText(ctx, r.fold({ "Модель добавлена: ${it.name}" }, { "Ошибка: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }

    val mainId = app.models.mainStt()?.id
    val liveId = app.models.liveStt()?.id
    val llmId = app.models.llm()?.id

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Модели") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            actions = { IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileOpen, "Импорт файла модели") } },
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    String.format(Locale("ru"), "Телефон: %.1f ГБ ОЗУ · потоков: %d · свободно %s", ram, Device.bigCores, Fmt.size(app.models.freeSpace())),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                if (!BuildConfig.ALLOW_NETWORK) {
                    Text("Это офлайн-сборка без доступа в интернет: добавляйте модели кнопкой импорта файла вверху.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Скачивать через мобильную сеть", Modifier.weight(1f))
                        Switch(checked = settings.allowMetered, onCheckedChange = { v -> app.prefs.update { copy(allowMetered = v) } })
                    }
                }
            }
            item { Section("Распознавание речи (Whisper)") }
            val stt = catalog.filter { it.type == ModelType.STT } + installed.filter { it.custom && it.type == ModelType.STT }
            items(stt, key = { it.id }) { m ->
                ModelCard(app, m, installed.any { it.id == m.id }, downloads[m.id], ram,
                    roles = {
                        FilterChip(selected = mainId == m.id, onClick = { app.prefs.update { copy(mainModelId = m.id) } }, label = { Text("Основная") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = liveId == m.id, onClick = { app.prefs.update { copy(liveModelId = m.id) } }, label = { Text("Живой текст") })
                    },
                    onDelete = { deleteFor = m })
            }
            item { Section("ИИ-обработка текста (LLM)") }
            val llms = catalog.filter { it.type == ModelType.LLM } + installed.filter { it.custom && it.type == ModelType.LLM }
            items(llms, key = { it.id }) { m ->
                ModelCard(app, m, installed.any { it.id == m.id }, downloads[m.id], ram,
                    roles = {
                        FilterChip(selected = llmId == m.id, onClick = { app.prefs.update { copy(llmModelId = m.id) } }, label = { Text("Использовать") })
                    },
                    onDelete = { deleteFor = m })
            }
            item {
                Text(
                    "Модели скачиваются с Hugging Face напрямую на телефон и проверяются по SHA-256. " +
                        "Расшифровка и ИИ работают без интернета. В памяти одновременно находится только одна модель.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }

    deleteFor?.let { m ->
        ConfirmDialog("Удалить модель?", "${m.name} (${Fmt.size(m.sizeBytes)}) будет удалена с телефона.", "Удалить", onDismiss = { deleteFor = null }) {
            app.models.delete(m); deleteFor = null
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ModelCard(
    app: App,
    m: ModelInfo,
    isInstalled: Boolean,
    dl: DownloadState?,
    ramGb: Double,
    roles: @Composable () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val tooBig = m.minRamGb > 0 && ramGb + 0.5 < m.minRamGb
                when {
                    tooBig -> Text("мало ОЗУ", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    recommended(m, ramGb) -> Text("Рекомендуем", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
            val speed = app.prefs.modelSpeed(m.id)
            Text(
                listOfNotNull(Fmt.size(m.sizeBytes), m.quant, if (m.minRamGb > 0) "от ${m.minRamGb.toInt()} ГБ ОЗУ" else null, m.license,
                    if (speed > 0) String.format(Locale("ru"), "скорость %.1fx", speed) else null).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(m.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
            when {
                isInstalled -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        roles()
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                    }
                }
                dl is DownloadState.Running -> {
                    val p = if (dl.total > 0) dl.downloaded.toFloat() / dl.total else 0f
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                dl.waitingForWifi -> "Ожидает Wi-Fi"
                                dl.paused -> "Пауза (нет сети?)"
                                else -> "${Fmt.size(dl.downloaded)} из ${Fmt.size(dl.total)}"
                            },
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { app.models.cancelDownload(m) }) { Text("Отмена") }
                    }
                }
                dl is DownloadState.Verifying -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Text("Проверка контрольной суммы…", style = MaterialTheme.typography.bodySmall)
                }
                m.url != null && app.models.networkAllowed -> {
                    if (dl is DownloadState.Failed) Text(dl.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    if (recommended(m, ramGb)) {
                        Button(onClick = { start(app, m, ctx) }) { Text("Скачать ${Fmt.size(m.sizeBytes)}") }
                    } else {
                        OutlinedButton(onClick = { start(app, m, ctx) }) { Text("Скачать ${Fmt.size(m.sizeBytes)}") }
                    }
                }
                else -> Text("Добавьте файл ${m.fileName} через импорт", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun start(app: App, m: ModelInfo, ctx: android.content.Context) {
    app.models.clearFailure(m.id)
    app.models.startDownload(m)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
}

fun recommended(m: ModelInfo, ramGb: Double): Boolean = when (m.id) {
    "whisper-small-q5" -> ramGb < 7.5
    "whisper-turbo-q5" -> ramGb >= 7.5
    "gemma3-1b" -> ramGb in 5.0..7.5
    "qwen3-4b-2507" -> ramGb >= 7.5
    else -> false
}
