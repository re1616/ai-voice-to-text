package app.aivoicetotext.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.widget.Toast
import app.aivoicetotext.App
import app.aivoicetotext.data.Folder
import app.aivoicetotext.data.Note
import app.aivoicetotext.data.NoteFilter
import app.aivoicetotext.data.NoteStatus
import app.aivoicetotext.models.DownloadState
import app.aivoicetotext.models.ModelType
import app.aivoicetotext.record.RecorderState
import app.aivoicetotext.record.RecordingService
import app.aivoicetotext.ui.ChoiceDialog
import app.aivoicetotext.ui.ConfirmDialog
import app.aivoicetotext.ui.DictationActivity
import app.aivoicetotext.ui.MainActivity
import app.aivoicetotext.ui.TextInputDialog
import app.aivoicetotext.ui.dateGroup
import app.aivoicetotext.ui.friendlyError
import app.aivoicetotext.ui.haptic
import app.aivoicetotext.ui.progressLine
import app.aivoicetotext.util.Device
import app.aivoicetotext.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NotesScreen(
    app: App,
    activity: MainActivity,
    openNote: (Long) -> Unit,
    openModels: () -> Unit,
    openSettings: () -> Unit,
    openAsk: () -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(NoteFilter.ALL) }
    var folderId by remember { mutableStateOf<Long?>(null) }
    var tag by remember { mutableStateOf<String?>(null) }
    val notes by remember(filter, folderId, tag, query) { app.repo.observeNotes(filter, folderId, tag, query) }
        .collectAsStateWithLifecycle(emptyList())
    val folders by remember { app.repo.observeFolders() }.collectAsStateWithLifecycle(emptyList())
    val tags by remember { app.repo.allTags() }.collectAsStateWithLifecycle(emptyList())
    val recorder by RecorderState.ui.collectAsStateWithLifecycle()
    val installedVersion by app.models.installedVersion.collectAsStateWithLifecycle()
    val downloads by app.models.downloads.collectAsStateWithLifecycle()
    val hasStt = remember(installedVersion) { app.models.installed().any { it.type == ModelType.STT } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var menuFor by remember { mutableStateOf<Note?>(null) }
    var moveFor by remember { mutableStateOf<Note?>(null) }
    var deleteFor by remember { mutableStateOf<Note?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    var filterSheet by remember { mutableStateOf(false) }
    var plusMenu by remember { mutableStateOf(false) }

    LaunchedEffect(recorder.active) { if (recorder.active) sheetOpen = true }
    LaunchedEffect(Unit) { app.models.refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) activity.import(uris)
    }

    fun trash(n: Note) {
        app.repo.moveToTrash(n.id)
        scope.launch {
            val r = snackbar.showSnackbar("«${n.title.take(30)}» в корзине", actionLabel = "Отменить", withDismissAction = true)
            if (r == SnackbarResult.ActionPerformed) app.repo.restore(n.id)
        }
    }

    val filterActive = filter != NoteFilter.ALL || folderId != null || tag != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (filter == NoteFilter.TRASH) "Корзина" else "Заметки") },
                actions = { IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Настройки") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box {
                        IconButton(onClick = { plusMenu = true }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.Add, "Добавить: импорт файла или диктовка", Modifier.size(30.dp))
                        }
                        DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            DropdownMenuItem(text = { Text("Импортировать аудио или видео") }, leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                                onClick = { plusMenu = false; picker.launch(arrayOf("audio/*", "video/*")) })
                            DropdownMenuItem(text = { Text("Диктовка в буфер обмена") }, leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                                onClick = { plusMenu = false; ctx.startActivity(Intent(ctx, DictationActivity::class.java)) })
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = {
                                view.haptic()
                                if (recorder.active) sheetOpen = true else activity.startRecording()
                            },
                            modifier = Modifier.size(76.dp).semantics {
                                contentDescription = if (recorder.active) "Открыть запись, идёт ${Fmt.clock(recorder.elapsedMs)}" else "Начать запись"
                            },
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (recorder.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            ),
                        ) { Icon(if (recorder.active) Icons.Default.Stop else Icons.Default.Mic, null, Modifier.size(38.dp)) }
                        Text(if (recorder.active) Fmt.clock(recorder.elapsedMs) else "Запись", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = openAsk, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.AutoAwesome, "Спросить у заметок", Modifier.size(30.dp))
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить поиск") } },
                    placeholder = { Text("Поиск") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.weight(1f),
                )
                BadgedBox(badge = { if (filterActive) Badge() }) {
                    IconButton(onClick = { filterSheet = true }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.FilterList, "Фильтр: папки, теги, корзина") }
                }
            }
            if (filterActive) {
                Row(Modifier.padding(horizontal = 16.dp)) {
                    val label = when {
                        filter == NoteFilter.TRASH -> "Корзина"
                        filter == NoteFilter.PINNED -> "Закреплённые"
                        folderId != null -> "📁 " + (folders.firstOrNull { it.id == folderId }?.name ?: "")
                        else -> "#$tag"
                    }
                    InputChip(selected = true, onClick = { filter = NoteFilter.ALL; folderId = null; tag = null }, label = { Text(label) },
                        trailingIcon = { Icon(Icons.Default.Close, "Сбросить фильтр", Modifier.size(16.dp)) })
                }
            }

            when {
                !Device.cpuSupported -> Banner("Этот телефон слишком старый для распознавания речи на устройстве. Записи сохраняются, их можно слушать и отправлять.")
                !hasStt -> {
                    val m = app.models.recommended(ModelType.STT)
                    val dl = m?.let { downloads[it.id] }
                    if (dl is DownloadState.Running) {
                        Banner("Скачивается модель распознавания: ${Fmt.size(dl.downloaded)} из ${Fmt.size(dl.total)}" +
                            if (dl.waitingForWifi) " · ждёт Wi-Fi" else "", progress = if (dl.total > 0) dl.downloaded.toFloat() / dl.total else null)
                    } else if (m != null && app.models.networkAllowed) {
                        Banner("Чтобы получать текст, нужна модель распознавания.", "Скачать ${Fmt.size(m.sizeBytes)}") {
                            app.models.startDownload(m)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                        }
                    } else Banner("Добавьте модель распознавания в разделе «Модели».", "Модели", onAction = openModels)
                }
            }

            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            query.isNotBlank() -> "Ничего не найдено. Попробуйте «Спросить у заметок» ✨ внизу справа."
                            filter == NoteFilter.TRASH -> "Корзина пуста. Удалённые заметки хранятся здесь 30 дней."
                            else -> "Нажмите большую кнопку внизу, чтобы записать голос.\n\nИли поделитесь голосовым сообщением из мессенджера с этим приложением."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val grouped = remember(notes) { notes.groupBy { if (it.pinned && filter == NoteFilter.ALL) "Закреплённые" else dateGroup(it.createdAt) } }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    grouped.forEach { (group, items) ->
                        item(key = "h-$group") {
                            Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp).semantics { heading() })
                        }
                        items(items, key = { it.id }) { n ->
                            val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                                if (v == SwipeToDismissBoxValue.EndToStart) {
                                    if (n.deletedAt != null) app.repo.restore(n.id) else trash(n)
                                    true
                                } else false
                            })
                            SwipeToDismissBox(
                                state = state,
                                enableDismissFromStartToEnd = false,
                                backgroundContent = {
                                    Box(
                                        Modifier.fillMaxSize().background(
                                            if (n.deletedAt != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                                            RoundedCornerShape(12.dp),
                                        ).padding(horizontal = 24.dp),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Icon(if (n.deletedAt != null) Icons.Default.RestoreFromTrash else Icons.Default.Delete,
                                            if (n.deletedAt != null) "Восстановить" else "В корзину")
                                    }
                                },
                            ) {
                                Box {
                                    NoteCard(app, n, onMenu = { menuFor = n },
                                        modifier = Modifier.combinedClickable(onClick = { openNote(n.id) }, onLongClick = { menuFor = n }))
                                    DropdownMenu(expanded = menuFor?.id == n.id, onDismissRequest = { menuFor = null }) {
                                        if (n.deletedAt != null) {
                                            DropdownMenuItem(text = { Text("Восстановить") }, onClick = { app.repo.restore(n.id); menuFor = null })
                                            DropdownMenuItem(text = { Text("Удалить навсегда") }, onClick = { deleteFor = n; menuFor = null })
                                        } else {
                                            DropdownMenuItem(text = { Text(if (n.pinned) "Открепить" else "Закрепить") },
                                                onClick = { app.repo.setPinned(n.id, !n.pinned); menuFor = null })
                                            DropdownMenuItem(text = { Text("Переместить в папку") }, onClick = { moveFor = n; menuFor = null })
                                            DropdownMenuItem(text = { Text("В корзину") }, onClick = { trash(n); menuFor = null })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (sheetOpen && recorder.active) RecordSheet(app, onDismiss = { sheetOpen = false })

    if (filterSheet) {
        ModalBottomSheet(onDismissRequest = { filterSheet = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Показать", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == NoteFilter.ALL && folderId == null && tag == null, { filter = NoteFilter.ALL; folderId = null; tag = null; filterSheet = false }, label = { Text("Все") })
                    FilterChip(filter == NoteFilter.PINNED, { filter = NoteFilter.PINNED; folderId = null; tag = null; filterSheet = false }, label = { Text("Закреплённые") })
                    FilterChip(filter == NoteFilter.TRASH, { filter = NoteFilter.TRASH; folderId = null; tag = null; filterSheet = false }, label = { Text("Корзина") })
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Папки", style = MaterialTheme.typography.titleMedium)
                folders.forEach { f ->
                    TextButton(onClick = { folderId = f.id; filter = NoteFilter.ALL; tag = null; filterSheet = false }) { Text("📁 ${f.name}") }
                }
                TextButton(onClick = { newFolder = true }) { Text("+ Новая папка") }
                if (tags.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Теги", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.take(12).forEach { t -> FilterChip(tag == t, { tag = t; folderId = null; filter = NoteFilter.ALL; filterSheet = false }, label = { Text("#$t") }) }
                    }
                }
            }
        }
    }
    if (newFolder) {
        TextInputDialog("Новая папка", "", onDismiss = { newFolder = false }) { name ->
            if (name.isNotBlank()) app.repo.createFolder(name.trim())
            newFolder = false
        }
    }
    moveFor?.let { n ->
        ChoiceDialog("Переместить в папку", listOf<Folder?>(null) + folders, { it?.name ?: "Без папки" }, onDismiss = { moveFor = null }) { f ->
            app.repo.setFolder(n.id, f?.id); moveFor = null
        }
    }
    deleteFor?.let { n ->
        ConfirmDialog("Удалить навсегда?", "Текст и аудио будут удалены без возможности восстановления.", "Удалить", onDismiss = { deleteFor = null }) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    val removed = app.repo.deleteForever(n.id)
                    app.deleteNoteFiles(removed?.audioPath, removed?.pcmPath)
                }
            }
            deleteFor = null
        }
    }
}

@Composable
private fun Banner(text: String, action: String? = null, progress: Float? = null, onAction: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            if (action != null && onAction != null) Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(action) }
        }
    }
}

@Composable
private fun NoteCard(app: App, n: Note, onMenu: () -> Unit, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (n.pinned) {
                    Icon(Icons.Default.PushPin, "Закреплено", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(n.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, "Действия с заметкой") }
            }
            Column(Modifier.padding(end = 14.dp)) {
                Text(
                    "${Fmt.date(n.createdAt)} · ${Fmt.clock(n.durationMs)}" + if (n.tags.isNotEmpty()) " · " + n.tags.joinToString(" ") { "#$it" } else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    n.status == NoteStatus.ERROR || n.status == NoteStatus.NO_MODEL -> {
                        Spacer(Modifier.height(6.dp))
                        Text(if (n.status == NoteStatus.NO_MODEL) "Ждёт модель распознавания" else friendlyError(n.error),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    n.status != NoteStatus.DONE -> {
                        Spacer(Modifier.height(6.dp))
                        Text(progressLine(app, n) ?: NoteStatus.label(n.status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (n.status == NoteStatus.TRANSCRIBING && n.durationMs > 0)
                            LinearProgressIndicator(progress = { (n.progressMs.toFloat() / n.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        else if (NoteStatus.isBusy(n.status)) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
                val preview = n.summary?.lines()?.take(2)?.joinToString("\n") { "• " + it.removePrefix("- ") } ?: n.snippet
                if (preview.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Suppress("unused")
private fun stopRecording(ctx: android.content.Context) = RecordingService.send(ctx, RecordingService.ACTION_STOP)
