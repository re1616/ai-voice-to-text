package app.aivoicetotext.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aivoicetotext.App
import app.aivoicetotext.data.AiResult
import app.aivoicetotext.data.AiStatus
import app.aivoicetotext.data.Note
import app.aivoicetotext.data.NoteStatus
import app.aivoicetotext.data.Segment
import app.aivoicetotext.data.TemplateKind
import app.aivoicetotext.export.ExportFormat
import app.aivoicetotext.export.Exporter
import app.aivoicetotext.models.ModelInfo
import app.aivoicetotext.models.ModelType
import app.aivoicetotext.ui.ChoiceDialog
import app.aivoicetotext.ui.ConfirmDialog
import app.aivoicetotext.ui.LANGUAGES
import app.aivoicetotext.ui.MainActivity
import app.aivoicetotext.ui.TextInputDialog
import app.aivoicetotext.ui.languageName
import app.aivoicetotext.util.Fmt
import app.aivoicetotext.work.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.ContentValues

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(app: App, activity: MainActivity, noteId: Long, back: () -> Unit, openModels: () -> Unit) {
    val note by remember(noteId) { app.repo.observeNote(noteId) }.collectAsStateWithLifecycle(null)
    val segments by remember(noteId) { app.repo.observeSegments(noteId) }.collectAsStateWithLifecycle(emptyList())
    val results by remember(noteId) { app.repo.observeAiResults(noteId) }.collectAsStateWithLifecycle(emptyList())
    val templates by remember { app.repo.observeTemplates() }.collectAsStateWithLifecycle(emptyList())
    val settings by app.prefs.state.collectAsStateWithLifecycle()
    val installedVersion by app.models.installedVersion.collectAsStateWithLifecycle()
    val llm = remember(installedVersion, settings.llmModelId) { app.models.llm() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var editTags by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var retranscribe by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf(false) }
    var editSeg by remember { mutableStateOf<Segment?>(null) }

    val n = note
    if (n == null) {
        Box(Modifier.fillMaxSize())
        return
    }

    // Player: (re)created when the audio file becomes available
    val player = remember(n.audioPath) { NotePlayer(n.audioPath) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player.playing, settings.skipSilence, segments) {
        while (player.playing) {
            val pos = player.poll()
            if (settings.skipSilence && segments.isNotEmpty()) {
                val inside = segments.any { pos >= it.startMs - 300 && pos <= it.endMs + 300 }
                if (!inside) {
                    val next = segments.firstOrNull { it.startMs > pos }
                    if (next != null && next.startMs - pos > 1000) player.seek(next.startMs - 200)
                }
            }
            delay(100)
        }
    }
    LaunchedEffect(player) { player.setPlaybackSpeed(settings.playbackSpeed) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { rename = true }) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    Box {
                        IconButton(onClick = { exportMenu = true }) { Icon(Icons.Default.Share, "Экспорт") }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            ExportFormat.entries.forEach { f ->
                                DropdownMenuItem(text = { Text(f.label) }, onClick = {
                                    exportMenu = false
                                    scope.launch {
                                        val i = withContext(Dispatchers.IO) { Exporter.shareIntent(app, n.id, f) }
                                        ctx.startActivity(i)
                                    }
                                })
                            }
                            DropdownMenuItem(text = { Text("Аудиофайл") }, onClick = {
                                exportMenu = false
                                Exporter.shareAudioIntent(app, n.id)?.let { ctx.startActivity(it) } ?: toast(ctx, "Аудио недоступно")
                            })
                            if (settings.autoExportUri != null) {
                                DropdownMenuItem(text = { Text("Сохранить в папку автоэкспорта") }, onClick = {
                                    exportMenu = false
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { runCatching { Exporter.autoExport(app, n.id) }.getOrDefault(false) }
                                        toast(ctx, if (ok) "Сохранено" else "Нет доступа к папке")
                                    }
                                })
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Меню") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; rename = true })
                            DropdownMenuItem(text = { Text("Теги") }, onClick = { menu = false; editTags = true })
                            DropdownMenuItem(text = { Text(if (n.pinned) "Открепить" else "Закрепить") }, onClick = { menu = false; app.repo.setPinned(n.id, !n.pinned) })
                            DropdownMenuItem(text = { Text("Перерасшифровать…") }, onClick = { menu = false; retranscribe = true })
                            DropdownMenuItem(text = { Text("Копировать текст") }, onClick = {
                                menu = false
                                copy(ctx, segments.filter { !it.draft }.joinToString("\n") { it.text })
                            })
                            DropdownMenuItem(text = { Text("В корзину") }, onClick = { menu = false; deleteAsk = true })
                        }
                    }
                },
            )
        },
        bottomBar = { PlayerBar(player, settings.skipSilence, onSkip = { v -> app.prefs.update { copy(skipSilence = v) } },
            onSpeed = { s -> player.setPlaybackSpeed(s); app.prefs.update { copy(playbackSpeed = s) } }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusCard(app, n, openModels)
            val checklists = results.filter { it.kind == TemplateKind.CHECKLIST }
            val texts = results.filter { it.kind != TemplateKind.CHECKLIST }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Текст") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("ИИ-конспект" + if (texts.isNotEmpty()) " (${texts.size})" else "") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Чек-лист" + if (checklists.isNotEmpty()) " (${checklists.size})" else "") })
            }
            if (tab != 0 || segments.isNotEmpty()) {
                AiActionsRow(
                    templates = templates.map { Triple(it.id, it.emoji, it.name) },
                    enabled = llm != null && segments.any { !it.draft } && n.status == NoteStatus.DONE,
                    hint = when {
                        llm == null -> "Скачайте LLM в разделе «Модели», чтобы включить ИИ-действия"
                        n.status != NoteStatus.DONE -> "ИИ-действия станут доступны после расшифровки"
                        else -> null
                    },
                    onHint = openModels,
                ) { templateId ->
                    val t = templates.firstOrNull { it.id == templateId } ?: return@AiActionsRow
                    val model = llm ?: return@AiActionsRow
                    val id = app.repo.createAiResult(n.id, t, model.id)
                    Jobs.ai(app, id)
                    tab = if (t.kind == TemplateKind.CHECKLIST) 2 else 1
                }
            }
            when (tab) {
                0 -> TranscriptTab(segments, player, settings.autoScroll, onEdit = { editSeg = it },
                    onToggleAutoScroll = { app.prefs.update { copy(autoScroll = !autoScroll) } })
                1 -> ResultsTab(app, texts, checklist = false)
                2 -> ResultsTab(app, checklists, checklist = true)
            }
        }
    }

    if (rename) TextInputDialog("Название", n.title, onDismiss = { rename = false }) { app.repo.rename(n.id, it.ifBlank { n.title }); rename = false }
    if (editTags) TextInputDialog("Теги через запятую", n.tags.joinToString(", "), onDismiss = { editTags = false }) {
        app.repo.setTags(n.id, it.split(',').map { t -> t.trim().removePrefix("#") }.filter { t -> t.isNotEmpty() }); editTags = false
    }
    if (deleteAsk) ConfirmDialog("Переместить в корзину?", "Заметка будет храниться в корзине 30 дней.", "В корзину", onDismiss = { deleteAsk = false }) {
        app.repo.moveToTrash(n.id); deleteAsk = false; back()
    }
    if (retranscribe) RetranscribeDialog(app, n, onDismiss = { retranscribe = false })
    editSeg?.let { s -> SegmentEditDialog(app, s, onDismiss = { editSeg = null }) }
}

private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("text", text))
    toast(ctx, "Скопировано")
}

@Composable
private fun StatusCard(app: App, n: Note, openModels: () -> Unit) {
    if (n.status == NoteStatus.DONE) return
    val ctx = LocalContext.current
    val error = n.status == NoteStatus.ERROR || n.status == NoteStatus.NO_MODEL
    Card(
        colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            val pct = if (n.durationMs > 0) (n.progressMs * 100 / n.durationMs).coerceIn(0, 100) else 0
            Text(
                NoteStatus.label(n.status) + if (n.status == NoteStatus.TRANSCRIBING) " · $pct% · ${Fmt.clock(n.progressMs)} из ${Fmt.clock(n.durationMs)}" else "",
                style = MaterialTheme.typography.titleSmall,
            )
            n.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (NoteStatus.isBusy(n.status)) {
                Spacer(Modifier.height(6.dp))
                if (n.status == NoteStatus.TRANSCRIBING) LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (n.status == NoteStatus.QUEUED && app.prefs.value.onlyCharging)
                    Text("Ожидает подключения зарядки (см. настройки)", style = MaterialTheme.typography.bodySmall)
            }
            Row {
                when (n.status) {
                    NoteStatus.NO_MODEL -> {
                        TextButton(onClick = openModels) { Text("Модели") }
                        TextButton(onClick = { app.repo.setStatus(n.id, NoteStatus.QUEUED); Jobs.transcribe(ctx, n.id, replace = true) }) { Text("Повторить") }
                    }
                    NoteStatus.ERROR -> TextButton(onClick = { app.repo.setStatus(n.id, NoteStatus.QUEUED); Jobs.transcribe(ctx, n.id, replace = true) }) { Text("Повторить") }
                    NoteStatus.RECORDING -> {}
                    else -> TextButton(onClick = {
                        Jobs.cancelTranscription(ctx, n.id)
                        app.repo.setStatus(n.id, NoteStatus.ERROR, "Остановлено пользователем")
                    }) { Text("Остановить") }
                }
            }
        }
    }
}

@Composable
private fun AiActionsRow(
    templates: List<Triple<Long, String, String>>,
    enabled: Boolean,
    hint: String?,
    onHint: () -> Unit,
    onRun: (Long) -> Unit,
) {
    Column {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(templates, key = { it.first }) { (id, emoji, name) ->
                AssistChip(onClick = { onRun(id) }, enabled = enabled, label = { Text("$emoji $name") })
            }
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).clickable(onClick = onHint))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptTab(
    segments: List<Segment>,
    player: NotePlayer,
    autoScroll: Boolean,
    onEdit: (Segment) -> Unit,
    onToggleAutoScroll: () -> Unit,
) {
    val listState = rememberLazyListState()
    val current = segments.indexOfLast { player.position >= it.startMs }.takeIf { player.ready && (player.playing || player.position > 0) } ?: -1
    LaunchedEffect(current, autoScroll) {
        if (autoScroll && player.playing && current >= 0) listState.animateScrollToItem(maxOf(0, current - 1))
    }
    if (segments.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Текст появится после расшифровки", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Нажмите на фразу, чтобы перейти к ней; удерживайте, чтобы редактировать",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            FilterChip(selected = autoScroll, onClick = onToggleAutoScroll, label = { Text("Автопрокрутка") })
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(segments, key = { _, s -> s.id }) { i, s ->
                val active = i == current
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = { player.seek(s.startMs); player.play() }, onLongClick = { if (!s.draft) onEdit(s) })
                        .padding(8.dp),
                ) {
                    Text(Fmt.clock(s.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(56.dp).padding(top = 2.dp))
                    Text(
                        s.text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = if (s.draft) FontStyle.Italic else FontStyle.Normal,
                        color = if (s.draft) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsTab(app: App, results: List<AiResult>, checklist: Boolean) {
    val ctx = LocalContext.current
    var deleteId by remember { mutableStateOf<AiResult?>(null) }
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(if (checklist) "Нажмите «✅ Задачи», чтобы извлечь дела из записи." else "Выберите действие выше: очистка, выжимка, конспект…",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(results, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("${r.modelId ?: ""} · ${Fmt.date(r.createdAt)}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (r.status == AiStatus.DONE) {
                            IconButton(onClick = { copy(ctx, r.content) }) { Icon(Icons.Default.ContentCopy, "Копировать") }
                        }
                        if (r.status == AiStatus.QUEUED || r.status == AiStatus.RUNNING) {
                            IconButton(onClick = { Jobs.cancelAi(ctx, r.id); app.repo.updateAi(r.id, status = AiStatus.CANCELLED) }) {
                                Icon(Icons.Default.Close, "Отменить")
                            }
                        } else {
                            IconButton(onClick = { deleteId = r }) { Icon(Icons.Default.Delete, "Удалить") }
                        }
                    }
                    when (r.status) {
                        AiStatus.QUEUED, AiStatus.RUNNING -> {
                            Text(if (r.total > 0) "Обработка: часть ${r.progress + 1} из ${r.total}" else if (r.status == AiStatus.QUEUED) "В очереди…" else "Загрузка модели…",
                                style = MaterialTheme.typography.bodySmall)
                            if (r.total > 0) LinearProgressIndicator(progress = { r.progress.toFloat() / r.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        AiStatus.ERROR -> Text("Ошибка: ${r.error}", color = MaterialTheme.colorScheme.error)
                        AiStatus.CANCELLED -> Text("Отменено", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> if (checklist) ChecklistContent(app, r) else SelectionContainer { Text(r.content, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }
    }
    deleteId?.let { r ->
        ConfirmDialog("Удалить результат?", r.title, "Удалить", onDismiss = { deleteId = null }) {
            app.repo.deleteAi(r.id, r.noteId); deleteId = null
        }
    }
}

@Composable
private fun ChecklistContent(app: App, r: AiResult) {
    val lines = r.content.lines().filter { it.isNotBlank() }
    Column {
        lines.forEachIndexed { i, line ->
            val t = line.trim()
            val isItem = t.startsWith("- [")
            if (!isItem) { Text(t); return@forEachIndexed }
            val done = t.startsWith("- [x]") || t.startsWith("- [X]")
            val text = t.drop(5).trim()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = done, onCheckedChange = { checked ->
                    val updated = lines.mapIndexed { j, l -> if (j == i) (if (checked) "- [x] " else "- [ ] ") + text else l }.joinToString("\n")
                    app.repo.updateAi(r.id, content = updated)
                })
                Text(text, textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun PlayerBar(player: NotePlayer, skipSilence: Boolean, onSkip: (Boolean) -> Unit, onSpeed: (Float) -> Unit) {
    if (!player.ready) return
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Slider(
                value = if (player.duration > 0) player.position.toFloat() / player.duration else 0f,
                onValueChange = { player.seek((it * player.duration).toLong()) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${Fmt.clock(player.position)} / ${Fmt.clock(player.duration)}", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { player.seek(player.position - 10_000) }) { Icon(Icons.Default.Replay10, "Назад 10 с") }
                IconButton(onClick = { player.toggle() }) {
                    Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (player.playing) "Пауза" else "Играть", Modifier.size(36.dp))
                }
                IconButton(onClick = { player.seek(player.position + 10_000) }) { Icon(Icons.Default.Forward10, "Вперёд 10 с") }
                val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                TextButton(onClick = {
                    val i = speeds.indexOfFirst { it >= player.speed - 0.01f }.coerceAtLeast(0)
                    onSpeed(speeds[(i + 1) % speeds.size])
                }) { Text("${player.speed}x".replace(".0x", "x")) }
                FilterChip(selected = skipSilence, onClick = { onSkip(!skipSilence) }, label = { Text("Тишина") })
            }
        }
    }
}

@Composable
private fun RetranscribeDialog(app: App, n: Note, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val models = remember { app.models.installed().filter { it.type == ModelType.STT } }
    var model by remember { mutableStateOf<ModelInfo?>(app.models.mainStt()) }
    var lang by remember { mutableStateOf(n.language) }
    var pickLang by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Перерасшифровать") },
        text = {
            Column {
                Text("Текущий текст будет заменён. Ручные правки сегментов тоже будут потеряны.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("Модель", style = MaterialTheme.typography.labelLarge)
                models.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { model = m }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = model?.id == m.id, onClick = { model = m })
                        Text(m.name)
                    }
                }
                if (models.isEmpty()) Text("Нет скачанных моделей", color = MaterialTheme.colorScheme.error)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(onClick = { pickLang = true }) { Text("Язык: ${languageName(lang)}") }
            }
        },
        confirmButton = {
            TextButton(enabled = model != null, onClick = {
                val m = model ?: return@TextButton
                app.prefs.update { copy(mainModelId = m.id) }
                app.repo.deleteAllSegments(n.id)
                app.repo.updateNote(n.id, ContentValues().apply {
                    put("progress_ms", 0); put("language", lang); putNull("detected_lang"); put("status", NoteStatus.QUEUED); putNull("error")
                })
                Jobs.transcribe(ctx, n.id, replace = true)
                onDismiss()
            }) { Text("Запустить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    if (pickLang) ChoiceDialog("Язык записи", LANGUAGES, { it.second }, onDismiss = { pickLang = false }) { lang = it.first; pickLang = false }
}

@Composable
private fun SegmentEditDialog(app: App, s: Segment, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(s.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Фраза ${Fmt.clock(s.startMs)}–${Fmt.clock(s.endMs)}") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { app.repo.mergeWithNext(s); onDismiss() }) { Text("Объединить со следующей") }
                    TextButton(onClick = { app.repo.deleteSegment(s.id, s.noteId); onDismiss() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { app.repo.updateSegmentText(s.id, s.noteId, text.trim()); onDismiss() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
