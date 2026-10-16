package app.aivoicetotext.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import app.aivoicetotext.data.PromptTemplate
import app.aivoicetotext.data.Segment
import app.aivoicetotext.data.Speaker
import app.aivoicetotext.data.TemplateCategory
import app.aivoicetotext.data.TemplateKind
import app.aivoicetotext.export.ExportFormat
import app.aivoicetotext.export.Exporter
import app.aivoicetotext.models.DownloadState
import app.aivoicetotext.models.ModelInfo
import app.aivoicetotext.models.ModelType
import app.aivoicetotext.ui.ChoiceDialog
import app.aivoicetotext.ui.ConfirmDialog
import app.aivoicetotext.ui.LANGUAGES
import app.aivoicetotext.ui.MainActivity
import app.aivoicetotext.ui.TextInputDialog
import app.aivoicetotext.ui.errorFix
import app.aivoicetotext.ui.friendlyError
import app.aivoicetotext.ui.languageName
import app.aivoicetotext.ui.progressLine
import app.aivoicetotext.ui.shareText
import app.aivoicetotext.ui.title
import app.aivoicetotext.util.Fmt
import app.aivoicetotext.work.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SPEAKER_COLORS = listOf(
    Color(0xFF3F51B5), Color(0xFF00897B), Color(0xFFE65100), Color(0xFF8E24AA),
    Color(0xFF43A047), Color(0xFFD81B60), Color(0xFF6D4C41), Color(0xFF546E7A),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(
    app: App,
    @Suppress("UNUSED_PARAMETER") activity: MainActivity,
    noteId: Long,
    seekMs: Long,
    back: () -> Unit,
    openModels: () -> Unit,
    openTemplates: () -> Unit,
) {
    val note by remember(noteId) { app.repo.observeNote(noteId) }.collectAsStateWithLifecycle(null)
    val segments by remember(noteId) { app.repo.observeSegments(noteId) }.collectAsStateWithLifecycle(emptyList())
    val results by remember(noteId) { app.repo.observeAiResults(noteId) }.collectAsStateWithLifecycle(emptyList())
    val speakers by remember(noteId) { app.repo.observeSpeakers(noteId) }.collectAsStateWithLifecycle(emptyList())
    val markers by remember(noteId) { app.repo.observeMarkers(noteId) }.collectAsStateWithLifecycle(emptyList())
    val templates by remember { app.repo.observeTemplates() }.collectAsStateWithLifecycle(emptyList())
    val settings by app.prefs.state.collectAsStateWithLifecycle()
    val installedVersion by app.models.installedVersion.collectAsStateWithLifecycle()
    val downloads by app.models.downloads.collectAsStateWithLifecycle()
    val activityMap by Jobs.activity.collectAsStateWithLifecycle()
    val llm = remember(installedVersion, settings.llmModelId) { app.models.llm() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var editTags by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var retranscribe by remember { mutableStateOf(false) }
    var aiSheet by remember { mutableStateOf(false) }
    var speakersDialog by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var editSeg by remember { mutableStateOf<Segment?>(null) }
    var renameSpeaker by remember { mutableStateOf<Speaker?>(null) }

    val n = note
    if (n == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val finals = segments.filter { !it.draft }
    val done = n.status == NoteStatus.DONE
    val activityText = activityMap[n.id]

    // Player: (re)created when the audio file becomes available; remembers the position
    val player = remember(n.audioPath) { NotePlayer(n.audioPath) }
    DisposableEffect(player) {
        if (seekMs >= 0) player.seek(seekMs) else player.seek(app.prefs.playbackPosition(noteId))
        onDispose { if (player.ready) app.prefs.setPlaybackPosition(noteId, player.position); player.release() }
    }
    LaunchedEffect(player.playing, settings.skipSilence, segments) {
        var tick = 0
        while (player.playing) {
            val pos = player.poll()
            if (settings.skipSilence && segments.isNotEmpty()) {
                val inside = segments.any { pos >= it.startMs - 300 && pos <= it.endMs + 300 }
                if (!inside) {
                    val next = segments.firstOrNull { it.startMs > pos }
                    if (next != null && next.startMs - pos > 1000) player.seek(next.startMs - 200)
                }
            }
            if (++tick % 50 == 0) app.prefs.setPlaybackPosition(noteId, pos)
            delay(100)
        }
    }
    LaunchedEffect(player) { player.setPlaybackSpeed(settings.playbackSpeed) }
    LaunchedEffect(seekMs, player.ready) { if (seekMs >= 0 && player.ready) player.play() }

    fun runTemplate(t: PromptTemplate) {
        val model = llm ?: return
        val id = app.repo.createAiResult(n.id, t, model.id)
        Jobs.ai(app, id)
        scope.launch { snackbar.showSnackbar("Готовлю «${t.name}»… Результат появится ниже") }
    }

    fun deleteResult(r: AiResult) {
        app.repo.deleteAi(r.id, r.noteId)
        scope.launch {
            if (snackbar.showSnackbar("Удалено", actionLabel = "Отменить", withDismissAction = true) == SnackbarResult.ActionPerformed) app.repo.restoreAi(r)
        }
    }

    val listState = rememberLazyListState()
    // index of the first transcript row inside the LazyColumn (header items come first)
    val headerItems = 5 + results.size

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { rename = true }) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    Box {
                        IconButton(onClick = { exportMenu = true }) { Icon(Icons.Default.Share, "Поделиться или экспортировать") }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            DropdownMenuItem(text = { Text("Отправить текст") }, onClick = {
                                exportMenu = false
                                scope.launch {
                                    val t = withContext(Dispatchers.IO) { Exporter.render(app, n.id, ExportFormat.TXT, timestamps = false) }
                                    shareText(ctx, t)
                                }
                            })
                            ExportFormat.entries.forEach { f ->
                                DropdownMenuItem(text = { Text("Файл: ${f.label}") }, onClick = {
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
                                DropdownMenuItem(text = { Text("Сохранить в папку заметок") }, onClick = {
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
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Ещё действия") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; rename = true })
                            DropdownMenuItem(text = { Text("Теги") }, onClick = { menu = false; editTags = true })
                            DropdownMenuItem(text = { Text(if (n.pinned) "Открепить" else "Закрепить") }, onClick = { menu = false; app.repo.setPinned(n.id, !n.pinned) })
                            DropdownMenuItem(text = { Text("Расшифровать заново…") }, onClick = { menu = false; retranscribe = true })
                            DropdownMenuItem(text = { Text("Копировать весь текст") }, onClick = {
                                menu = false
                                copy(ctx, finals.joinToString("\n") { it.text })
                            })
                            DropdownMenuItem(text = { Text("В корзину") }, onClick = { menu = false; app.repo.moveToTrash(n.id); back() })
                        }
                    }
                },
            )
        },
        bottomBar = {
            PlayerBar(player, settings.skipSilence, onSkip = { v -> app.prefs.update { copy(skipSilence = v) } },
                onSpeed = { s -> player.setPlaybackSpeed(s); app.prefs.update { copy(playbackSpeed = s) } })
        },
    ) { padding ->
        val current = finals.indexOfLast { player.position >= it.startMs }.takeIf { player.ready && (player.playing || player.position > 0) } ?: -1
        LaunchedEffect(current, settings.autoScroll) {
            if (settings.autoScroll && player.playing && current >= 0) listState.animateScrollToItem(headerItems + maxOf(0, current - 1))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 0: status
            item(key = "status") { StatusCard(app, n, activityText) }
            // 1: summary / assistant promo
            item(key = "summary") {
                when {
                    n.summary != null -> SummaryCard(n.summary, onRefresh = if (llm != null && done) ({ Jobs.summary(app, n.id) }) else null,
                        onShare = { shareText(ctx, n.title + "\n\n" + n.summary) })
                    llm == null && done && finals.isNotEmpty() -> AssistantPromo(app, downloads)
                    llm != null && done && finals.isNotEmpty() && activityText == null ->
                        FilledTonalButton(onClick = { Jobs.summary(app, n.id) }, modifier = Modifier.fillMaxWidth()) { Text("✨ Сделать выжимку и название") }
                    else -> Spacer(Modifier.height(0.dp))
                }
            }
            // 2: main actions
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { aiSheet = true }, enabled = done && finals.isNotEmpty(), modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("ИИ-действия")
                    }
                    OutlinedButton(onClick = { speakersDialog = true }, enabled = done && finals.isNotEmpty(), modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Default.Groups, null); Spacer(Modifier.width(6.dp)); Text(if (speakers.isEmpty()) "Кто говорит" else "Спикеры: ${speakers.size}")
                    }
                }
            }
            // 3: AI results
            items(results, key = { "r${it.id}" }) { r -> ResultCard(app, r, onDelete = { deleteResult(r) }) }
            // 4: transcript header
            item(key = "th") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Расшифровка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    if (finals.isNotEmpty()) {
                        FilterChip(selected = settings.autoScroll, onClick = { app.prefs.update { copy(autoScroll = !autoScroll) } }, label = { Text("Следить") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = editMode, onClick = { editMode = !editMode },
                            leadingIcon = { Icon(if (editMode) Icons.Default.Done else Icons.Default.Edit, null, Modifier.size(16.dp)) },
                            label = { Text(if (editMode) "Готово" else "Править") })
                    }
                }
            }
            item(key = "hint") {
                if (segments.isEmpty()) {
                    Text(if (done) "Речь не найдена в записи." else "Текст появится здесь по мере расшифровки.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                } else if (editMode) {
                    Text("Нажмите на фразу, чтобы исправить её текст или спикера.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Spacer(Modifier.height(0.dp))
            }
            // transcript
            val shown = if (finals.isNotEmpty()) finals else segments
            itemsIndexed(shown, key = { _, s -> "s${s.id}" }) { i, s ->
                val prevSpeaker = shown.getOrNull(i - 1)?.speaker
                val starred = markers.any { it.timeMs >= s.startMs - 500 && it.timeMs <= s.endMs + 500 }
                SegmentRow(
                    s = s,
                    active = !s.draft && i == current,
                    starred = starred,
                    speaker = if (s.speaker != null && s.speaker != prevSpeaker) speakers.firstOrNull { it.idx == s.speaker } else null,
                    editMode = editMode,
                    onClick = {
                        if (editMode && !s.draft) editSeg = s else { player.seek(s.startMs); player.play() }
                    },
                    onSpeakerClick = { sp -> renameSpeaker = sp },
                )
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (aiSheet) AiSheet(templates, llm, onDismiss = { aiSheet = false }, openTemplates = { aiSheet = false; openTemplates() },
        openModels = { aiSheet = false; openModels() }) { t -> aiSheet = false; runTemplate(t) }
    if (speakersDialog) SpeakersDialog(app, n, downloads, onDismiss = { speakersDialog = false })
    if (rename) TextInputDialog("Название", n.title, onDismiss = { rename = false }) { app.repo.rename(n.id, it.ifBlank { n.title }); rename = false }
    if (editTags) TextInputDialog("Теги через запятую", n.tags.joinToString(", "), onDismiss = { editTags = false }) {
        app.repo.setTags(n.id, it.split(',').map { t -> t.trim().removePrefix("#") }.filter { t -> t.isNotEmpty() }); editTags = false
    }
    renameSpeaker?.let { sp ->
        TextInputDialog("Как зовут спикера?", sp.name, onDismiss = { renameSpeaker = null }) {
            if (it.isNotBlank()) app.repo.renameSpeaker(n.id, sp.idx, it.trim()); renameSpeaker = null
        }
    }
    if (retranscribe) RetranscribeDialog(app, n, onDismiss = { retranscribe = false })
    editSeg?.let { s -> SegmentEditDialog(app, s, speakers, onDismiss = { editSeg = null }) }
}

private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text))
    toast(ctx, "Скопировано")
}

@Composable
private fun StatusCard(app: App, n: Note, activityText: String?) {
    val ctx = LocalContext.current
    if (n.status == NoteStatus.DONE) {
        if (activityText != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(activityText, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        } else if (n.error?.startsWith("Спикеры") == true) {
            Text(n.error ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        return
    }
    val error = n.status == NoteStatus.ERROR || n.status == NoteStatus.NO_MODEL
    Card(
        colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (error) {
                Text(if (n.status == NoteStatus.NO_MODEL) "Нужна модель распознавания" else "Не получилось расшифровать",
                    style = MaterialTheme.typography.titleSmall)
                Text(if (n.status == NoteStatus.NO_MODEL) "Скачайте её один раз — дальше всё работает без интернета." else friendlyError(n.error),
                    style = MaterialTheme.typography.bodySmall)
                errorFix(app, ctx, n)?.let { fix -> Button(onClick = fix.action, modifier = Modifier.padding(top = 8.dp)) { Text(fix.label) } }
            } else {
                val pct = if (n.durationMs > 0) (n.progressMs * 100 / n.durationMs).coerceIn(0, 100) else 0
                Text(progressLine(app, n) ?: NoteStatus.label(n.status), style = MaterialTheme.typography.titleSmall)
                Text("Можно закрыть приложение — пришлём уведомление, когда будет готово", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                if (n.status == NoteStatus.TRANSCRIBING) LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (n.status != NoteStatus.RECORDING) {
                    TextButton(onClick = {
                        Jobs.cancelTranscription(ctx, n.id)
                        app.repo.setStatus(n.id, NoteStatus.ERROR, "Остановлено пользователем")
                    }) { Text("Остановить") }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: String, onRefresh: (() -> Unit)?, onShare: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✨ Главное", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onShare) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить выжимку") }
                if (onRefresh != null) IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Обновить выжимку") }
            }
            SelectionContainer {
                Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    summary.lines().filter { it.isNotBlank() }.forEach { Text("• " + it.removePrefix("- ").trim(), style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}

@Composable
private fun AssistantPromo(app: App, downloads: Map<String, DownloadState>) {
    val ctx = LocalContext.current
    val m = remember { app.models.recommended(ModelType.LLM) } ?: return
    val dl = downloads[m.id]
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("✨ ИИ-помощник", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Названия и выжимки после каждой записи, списки задач, письма и посты из голоса, ответы на вопросы по заметкам. Работает без интернета.",
                style = MaterialTheme.typography.bodyMedium)
            when (dl) {
                is DownloadState.Running -> {
                    LinearProgressIndicator(progress = { if (dl.total > 0) dl.downloaded.toFloat() / dl.total else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("${Fmt.size(dl.downloaded)} из ${Fmt.size(dl.total)}" + if (dl.waitingForWifi) " · ждёт Wi-Fi" else "", style = MaterialTheme.typography.bodySmall)
                }
                is DownloadState.Verifying -> Text("Проверяю файл…", style = MaterialTheme.typography.bodySmall)
                else -> {
                    if (dl is DownloadState.Failed) Text(dl.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (app.models.networkAllowed) Button(onClick = {
                        app.models.clearFailure(m.id)
                        app.models.startDownload(m)?.let { toast(ctx, it) }
                    }, modifier = Modifier.padding(top = 8.dp)) { Text("Скачать помощника · ${Fmt.size(m.sizeBytes)}") }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(app: App, r: AiResult, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (r.status == AiStatus.DONE) {
                    IconButton(onClick = { shareText(ctx, r.content) }) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
                    IconButton(onClick = { copy(ctx, r.content) }) { Icon(Icons.Default.ContentCopy, "Копировать") }
                }
                if (r.status == AiStatus.QUEUED || r.status == AiStatus.RUNNING) {
                    IconButton(onClick = { Jobs.cancelAi(ctx, r.id); app.repo.updateAi(r.id, status = AiStatus.CANCELLED) }) { Icon(Icons.Default.Close, "Отменить") }
                } else {
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить") }
                }
            }
            Column(Modifier.padding(end = 10.dp)) {
                when (r.status) {
                    AiStatus.QUEUED, AiStatus.RUNNING -> {
                        Text(when {
                            r.total > 1 -> "Обрабатываю длинную запись: часть ${minOf(r.progress + 1, r.total)} из ${r.total}"
                            r.status == AiStatus.QUEUED -> "В очереди…"
                            else -> "Думаю… обычно это занимает до минуты"
                        }, style = MaterialTheme.typography.bodySmall)
                        if (r.total > 1) LinearProgressIndicator(progress = { r.progress.toFloat() / r.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    AiStatus.ERROR -> {
                        Text(friendlyError(r.error), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { app.repo.updateAi(r.id, status = AiStatus.QUEUED, progress = 0, total = 0); Jobs.ai(ctx, r.id) }) { Text("Повторить") }
                    }
                    AiStatus.CANCELLED -> Text("Отменено", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> if (r.kind == TemplateKind.CHECKLIST) ChecklistContent(app, r) else {
                        val long = r.content.length > 600
                        SelectionContainer {
                            Text(if (long && !expanded) r.content.take(600) + "…" else r.content, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (long) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Свернуть" else "Показать полностью") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistContent(app: App, r: AiResult) {
    val lines = r.content.lines().filter { it.isNotBlank() }
    Column {
        lines.forEachIndexed { i, line ->
            val t = line.trim()
            if (!t.startsWith("- [")) { Text(t); return@forEachIndexed }
            val checked = t.startsWith("- [x]") || t.startsWith("- [X]")
            val text = t.drop(5).trim()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                val updated = lines.mapIndexed { j, l -> if (j == i) (if (!checked) "- [x] " else "- [ ] ") + text else l }.joinToString("\n")
                app.repo.updateAi(r.id, content = updated)
            }) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(text, textDecoration = if (checked) TextDecoration.LineThrough else null,
                    color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun SegmentRow(
    s: Segment,
    active: Boolean,
    starred: Boolean,
    speaker: Speaker?,
    editMode: Boolean,
    onClick: () -> Unit,
    onSpeakerClick: (Speaker) -> Unit,
) {
    Column {
        if (speaker != null) {
            val color = SPEAKER_COLORS[speaker.idx % SPEAKER_COLORS.size]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                .clickable { onSpeakerClick(speaker) }.semantics { contentDescription = "Спикер ${speaker.name}, нажмите чтобы переименовать" }) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(speaker.name, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Edit, null, Modifier.size(14.dp), tint = color)
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text((if (starred) "★ " else "") + Fmt.clock(s.startMs), style = MaterialTheme.typography.labelMedium,
                color = if (starred) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(64.dp).padding(top = 3.dp))
            Text(
                s.text,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (s.draft) FontStyle.Italic else FontStyle.Normal,
                color = if (s.draft) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (editMode && !s.draft) Icon(Icons.Default.Edit, "Исправить фразу", Modifier.size(20.dp).padding(start = 4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSheet(
    templates: List<PromptTemplate>,
    llm: ModelInfo?,
    onDismiss: () -> Unit,
    openTemplates: () -> Unit,
    openModels: () -> Unit,
    onRun: (PromptTemplate) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (llm == null) {
                Text("Сначала скачайте ИИ-помощника", style = MaterialTheme.typography.titleMedium)
                Button(onClick = openModels) { Text("Открыть модели") }
                return@Column
            }
            TemplateSection("Разобрать запись", templates.filter { it.category == TemplateCategory.ANALYZE && it.builtin }, onRun)
            TemplateSection("Переписать как…", templates.filter { it.category == TemplateCategory.REWRITE }, onRun)
            TemplateSection("Мои шаблоны", templates.filter { !it.builtin }, onRun)
            TextButton(onClick = openTemplates) { Text("Настроить шаблоны") }
        }
    }
}

@Composable
private fun TemplateSection(title: String, items: List<PromptTemplate>, onRun: (PromptTemplate) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { t ->
                    AssistChip(onClick = { onRun(t) }, label = { Text("${t.emoji} ${t.name}", maxLines = 1) }, modifier = Modifier.weight(1f).height(44.dp))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SpeakersDialog(app: App, n: Note, downloads: Map<String, DownloadState>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val version by app.models.installedVersion.collectAsStateWithLifecycle()
    val model = remember(version) { app.models.speakerModel() }
    val catalogModel = remember { app.models.recommended(ModelType.SPEAKER) }
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { app.models.refresh(); delay(1000) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Кто говорит?") },
        text = {
            Column {
                if (model == null) {
                    Text("Чтобы различать голоса, нужна небольшая модель (${catalogModel?.let { Fmt.size(it.sizeBytes) } ?: "≈27 МБ"}). Скачивается один раз.")
                    val dl = catalogModel?.let { downloads[it.id] }
                    if (dl is DownloadState.Running) LinearProgressIndicator(progress = { if (dl.total > 0) dl.downloaded.toFloat() / dl.total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (dl is DownloadState.Failed) Text(dl.message, color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Сколько человек говорит в записи?")
                    listOf(0 to "Определить автоматически", 2 to "Двое", 3 to "Трое", 4 to "Четверо", 5 to "Пятеро и больше").forEach { (v, label) ->
                        Row(Modifier.fillMaxWidth().clickable { count = v }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = count == v, onClick = { count = v })
                            Text(label)
                        }
                    }
                    Text("Имена можно поменять, нажав на имя спикера в тексте.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            if (model == null) {
                TextButton(enabled = catalogModel != null && downloads[catalogModel.id] !is DownloadState.Running, onClick = {
                    catalogModel?.let { m -> app.models.clearFailure(m.id); app.models.startDownload(m)?.let { toast(ctx, it) } }
                }) { Text("Скачать") }
            } else {
                TextButton(onClick = { Jobs.diarize(ctx, n.id, count); onDismiss() }) { Text("Определить") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun PlayerBar(player: NotePlayer, skipSilence: Boolean, onSkip: (Boolean) -> Unit, onSpeed: (Float) -> Unit) {
    if (!player.ready) return
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Slider(
                value = if (player.duration > 0) player.position.toFloat() / player.duration else 0f,
                onValueChange = { player.seek((it * player.duration).toLong()) },
                modifier = Modifier.semantics { contentDescription = "Позиция воспроизведения" },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${Fmt.clock(player.position)} / ${Fmt.clock(player.duration)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { player.seek(player.position - 10_000) }) { Icon(Icons.Default.Replay10, "Назад на 10 секунд") }
                FilledIconButton(onClick = { player.toggle() }, modifier = Modifier.size(52.dp), shape = CircleShape) {
                    Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (player.playing) "Пауза" else "Слушать", Modifier.size(32.dp))
                }
                IconButton(onClick = { player.seek(player.position + 10_000) }) { Icon(Icons.Default.Forward10, "Вперёд на 10 секунд") }
                val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                FilterChip(selected = player.speed != 1f, onClick = {
                    val i = speeds.indexOfFirst { it >= player.speed - 0.01f }.coerceAtLeast(0)
                    onSpeed(speeds[(i + 1) % speeds.size])
                }, label = { Text("${player.speed}x".replace(".0x", "x")) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                FilterChip(selected = skipSilence, onClick = { onSkip(!skipSilence) }, label = { Text("Пропускать паузы") })
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
        title = { Text("Расшифровать заново") },
        text = {
            Column {
                Text("Текст будет распознан заново, ваши правки в нём пропадут.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("Качество", style = MaterialTheme.typography.labelLarge)
                models.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { model = m }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = model?.id == m.id, onClick = { model = m })
                        Text(m.title())
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
            }) { Text("Начать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    if (pickLang) ChoiceDialog("Язык записи", LANGUAGES, { it.second }, onDismiss = { pickLang = false }) { lang = it.first; pickLang = false }
}

@Composable
private fun SegmentEditDialog(app: App, s: Segment, speakers: List<Speaker>, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(s.text) }
    var speaker by remember { mutableStateOf(s.speaker) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Фраза ${Fmt.clock(s.startMs)}") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth())
                if (speakers.isNotEmpty()) {
                    Text("Кто говорит", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    speakers.forEach { sp ->
                        Row(Modifier.fillMaxWidth().clickable { speaker = sp.idx }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = speaker == sp.idx, onClick = { speaker = sp.idx })
                            Text(sp.name)
                        }
                    }
                }
                Row {
                    TextButton(onClick = { app.repo.mergeWithNext(s); onDismiss() }) { Text("Склеить со следующей") }
                    TextButton(onClick = { app.repo.deleteSegment(s.id, s.noteId); onDismiss() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.trim() != s.text) app.repo.updateSegmentText(s.id, s.noteId, text.trim())
                if (speaker != s.speaker) app.repo.setSegmentSpeaker(s.id, speaker)
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
