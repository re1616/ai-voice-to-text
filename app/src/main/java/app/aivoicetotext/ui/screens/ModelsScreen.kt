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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aivoicetotext.App
import app.aivoicetotext.BuildConfig
import app.aivoicetotext.models.DownloadState
import app.aivoicetotext.models.ModelInfo
import app.aivoicetotext.models.ModelType
import app.aivoicetotext.ui.ConfirmDialog
import app.aivoicetotext.ui.speedLabel
import app.aivoicetotext.ui.title
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
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { while (true) { app.models.refresh(); delay(1000) } }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            val r = app.models.import(uri)
            importing = false
            Toast.makeText(ctx, r.fold({ "Модель добавлена" }, { "Не подошёл файл: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }

    val mainId = remember(version, settings) { app.models.mainStt()?.id }
    val liveId = remember(version, settings) { app.models.liveStt()?.id }
    val llmId = remember(version, settings) { app.models.llm()?.id }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Модели") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            actions = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Ещё") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Добавить модель из файла") }, onClick = { menu = false; importer.launch(arrayOf("*/*")) })
                }
            },
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Модели скачиваются один раз и работают без интернета. Свободно: ${Fmt.size(app.models.freeSpace())}.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                if (!BuildConfig.ALLOW_NETWORK) {
                    Text("Офлайн-версия без интернета: добавляйте модели через меню ⋮ → «Добавить модель из файла».",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item { Section("Распознавание речи", "Чем точнее модель, тем дольше расшифровка") }
            val stt = catalog.filter { it.type == ModelType.STT } + installed.filter { it.custom && it.type == ModelType.STT }
            items(stt, key = { it.id }) { m ->
                ModelCard(app, m, installed.any { it.id == m.id }, downloads[m.id], ram, onDelete = { deleteFor = m }) {
                    FilterChip(selected = mainId == m.id, onClick = { app.prefs.update { copy(mainModelId = m.id) } }, label = { Text("Для расшифровки") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = liveId == m.id, onClick = { app.prefs.update { copy(liveModelId = m.id) } }, label = { Text("Для черновика") })
                }
            }
            item { Section("ИИ-помощник", "Названия, выжимки, задачи, письма и ответы по заметкам") }
            val llms = catalog.filter { it.type == ModelType.LLM } + installed.filter { it.custom && it.type == ModelType.LLM }
            items(llms, key = { it.id }) { m ->
                ModelCard(app, m, installed.any { it.id == m.id }, downloads[m.id], ram, onDelete = { deleteFor = m }) {
                    FilterChip(selected = llmId == m.id, onClick = { app.prefs.update { copy(llmModelId = m.id) } }, label = { Text("Использовать") })
                }
            }
            item { Section("Кто говорит", "Подписывает в тексте, где чей голос") }
            items(catalog.filter { it.type == ModelType.SPEAKER }, key = { it.id }) { m ->
                ModelCard(app, m, installed.any { it.id == m.id }, downloads[m.id], ram, onDelete = { deleteFor = m }) {
                    Text("Установлено", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (BuildConfig.ALLOW_NETWORK) item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Скачивать через мобильный интернет", style = MaterialTheme.typography.bodyLarge)
                        Text("Если выключено — загрузка ждёт Wi-Fi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.allowMetered, onCheckedChange = { v -> app.prefs.update { copy(allowMetered = v) } })
                }
            }
        }
    }

    deleteFor?.let { m ->
        ConfirmDialog("Удалить модель «${m.title()}»?", "Освободится ${Fmt.size(m.sizeBytes)}. Её можно скачать снова.", "Удалить", onDismiss = { deleteFor = null }) {
            app.models.delete(m); deleteFor = null
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelCard(
    app: App,
    m: ModelInfo,
    isInstalled: Boolean,
    dl: DownloadState?,
    ramGb: Double,
    onDelete: () -> Unit,
    roles: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    var details by remember { mutableStateOf(false) }
    val recommended = isRecommended(m, ramGb)
    val tooBig = m.minRamGb > 0 && ramGb + 0.5 < m.minRamGb
    Card(
        Modifier.fillMaxWidth(),
        colors = if (recommended && !isInstalled) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.title(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (recommended) Text("Подходит вашему телефону", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            Text(m.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
            Text(
                listOfNotNull(Fmt.size(m.sizeBytes), speedLabel(m, app.prefs.modelSpeed(m.id))).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tooBig) Text("На этом телефоне может работать медленно", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(6.dp))
            when {
                isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                    roles()
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                }
                dl is DownloadState.Running -> {
                    LinearProgressIndicator(progress = { if (dl.total > 0) dl.downloaded.toFloat() / dl.total else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                dl.waitingForWifi -> "Ждёт Wi-Fi (можно разрешить мобильный интернет внизу)"
                                dl.paused -> "Пауза — нет сети?"
                                else -> "${Fmt.size(dl.downloaded)} из ${Fmt.size(dl.total)}"
                            },
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { app.models.cancelDownload(m) }) { Text("Отмена") }
                    }
                }
                dl is DownloadState.Verifying -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Text("Проверяю файл…", style = MaterialTheme.typography.bodySmall)
                }
                m.url != null && app.models.networkAllowed -> {
                    if (dl is DownloadState.Failed) Text("Не удалось скачать: ${dl.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    val label = if (dl is DownloadState.Failed) "Повторить" else "Скачать · ${Fmt.size(m.sizeBytes)}"
                    if (recommended) Button(onClick = { start(app, m, ctx) }) { Text(label) }
                    else OutlinedButton(onClick = { start(app, m, ctx) }) { Text(label) }
                }
                else -> Text("Добавьте файл ${m.fileName} через меню ⋮", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) {
                Text(if (details) "Скрыть подробности" else "Подробнее", style = MaterialTheme.typography.labelMedium)
            }
            if (details) {
                Text(
                    "${m.name} · ${m.quant} · файл ${m.fileName}\nЛицензия: ${m.license}" +
                        (if (m.minRamGb > 0) String.format(Locale("ru"), "\nРекомендуется от %.0f ГБ памяти (у вас %.1f ГБ)", m.minRamGb, ramGb) else "") +
                        (if (m.sha256 != null) "\nПроверка SHA-256: ${m.sha256.take(12)}…" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun start(app: App, m: ModelInfo, ctx: android.content.Context) {
    app.models.clearFailure(m.id)
    app.models.startDownload(m)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
}

fun isRecommended(m: ModelInfo, ramGb: Double): Boolean = when (m.id) {
    "whisper-small-q5" -> ramGb in 3.5..7.5
    "whisper-base-q8" -> ramGb < 3.5
    "whisper-turbo-q5" -> ramGb >= 7.5
    "gemma3-1b" -> ramGb < 7.5
    "qwen3-4b-2507" -> ramGb >= 7.5
    "speaker-resnet34" -> true
    else -> false
}
