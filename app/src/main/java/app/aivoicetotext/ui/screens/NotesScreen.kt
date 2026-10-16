package app.aivoicetotext.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aivoicetotext.App
import app.aivoicetotext.data.Folder
import app.aivoicetotext.data.Note
import app.aivoicetotext.data.NoteFilter
import app.aivoicetotext.data.NoteStatus
import app.aivoicetotext.models.ModelType
import app.aivoicetotext.record.RecorderState
import app.aivoicetotext.ui.ChoiceDialog
import app.aivoicetotext.ui.ConfirmDialog
import app.aivoicetotext.ui.MainActivity
import app.aivoicetotext.ui.TextInputDialog
import app.aivoicetotext.util.Device
import app.aivoicetotext.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(
    app: App,
    activity: MainActivity,
    openNote: (Long) -> Unit,
    openModels: () -> Unit,
    openSettings: () -> Unit,
) {
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
    val hasStt = remember(installedVersion) { app.models.installed().any { it.type == ModelType.STT } }
    val scope = rememberCoroutineScope()

    var menuFor by remember { mutableStateOf<Note?>(null) }
    var moveFor by remember { mutableStateOf<Note?>(null) }
    var deleteFor by remember { mutableStateOf<Note?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(recorder.active) { if (recorder.active) sheetOpen = true }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) activity.import(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Заметки") },
                actions = {
                    IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Настройки") }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }) {
                    Icon(Icons.Default.FileUpload, "Импорт аудиофайла")
                }
                ExtendedFloatingActionButton(
                    onClick = { if (recorder.active) sheetOpen = true else activity.startRecording() },
                    icon = { Icon(Icons.Default.Mic, null) },
                    text = { Text(if (recorder.active) Fmt.clock(recorder.elapsedMs) else "Запись") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить") } },
                placeholder = { Text("Поиск по тексту и конспектам") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(selected = filter == NoteFilter.ALL && folderId == null && tag == null,
                        onClick = { filter = NoteFilter.ALL; folderId = null; tag = null }, label = { Text("Все") })
                }
                item {
                    FilterChip(selected = filter == NoteFilter.PINNED, onClick = { filter = NoteFilter.PINNED; folderId = null; tag = null },
                        label = { Text("Закреплённые") })
                }
                items(folders, key = { "f${it.id}" }) { f ->
                    FilterChip(selected = folderId == f.id, onClick = { folderId = if (folderId == f.id) null else f.id; filter = NoteFilter.ALL },
                        label = { Text("📁 ${f.name}") })
                }
                items(tags, key = { "t$it" }) { t ->
                    FilterChip(selected = tag == t, onClick = { tag = if (tag == t) null else t; filter = NoteFilter.ALL },
                        label = { Text("#$t") })
                }
                item {
                    FilterChip(selected = filter == NoteFilter.TRASH, onClick = { filter = NoteFilter.TRASH; folderId = null; tag = null },
                        label = { Text("Корзина") }, leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(16.dp)) })
                }
                item { TextButton(onClick = { newFolder = true }) { Text("+ Папка") } }
            }

            if (!Device.cpuSupported) {
                Banner("Процессор этого телефона не поддерживает инструкции ARMv8.2 (dotprod). Распознавание работать не будет.", null, null)
            } else if (!hasStt) {
                Banner("Скачайте модель распознавания речи, чтобы получать текст.", "Модели", openModels)
            }

            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            query.isNotBlank() -> "Ничего не найдено"
                            filter == NoteFilter.TRASH -> "Корзина пуста"
                            else -> "Нажмите «Запись» или поделитесь голосовым сообщением из мессенджера с этим приложением."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(notes, key = { it.id }) { n ->
                        Box {
                            NoteCard(n, Modifier.combinedClickable(onClick = { openNote(n.id) }, onLongClick = { menuFor = n }))
                            DropdownMenu(expanded = menuFor?.id == n.id, onDismissRequest = { menuFor = null }) {
                                if (n.deletedAt != null) {
                                    DropdownMenuItem(text = { Text("Восстановить") }, onClick = { app.repo.restore(n.id); menuFor = null })
                                    DropdownMenuItem(text = { Text("Удалить навсегда") }, onClick = { deleteFor = n; menuFor = null })
                                } else {
                                    DropdownMenuItem(text = { Text(if (n.pinned) "Открепить" else "Закрепить") },
                                        onClick = { app.repo.setPinned(n.id, !n.pinned); menuFor = null })
                                    DropdownMenuItem(text = { Text("Переместить в папку") }, onClick = { moveFor = n; menuFor = null })
                                    DropdownMenuItem(text = { Text("В корзину") }, onClick = { app.repo.moveToTrash(n.id); menuFor = null })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (sheetOpen && recorder.active) {
        RecordSheet(app, onDismiss = { sheetOpen = false })
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
        ConfirmDialog("Удалить навсегда?", "Текст, аудио и результаты ИИ будут удалены без возможности восстановления.", "Удалить",
            onDismiss = { deleteFor = null }) {
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
private fun Banner(text: String, action: String?, onAction: (() -> Unit)?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun NoteCard(n: Note, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (n.pinned) {
                    Icon(Icons.Default.PushPin, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(n.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Text(
                "${Fmt.date(n.createdAt)} · ${Fmt.clock(n.durationMs)}" + if (n.tags.isNotEmpty()) " · " + n.tags.joinToString(" ") { "#$it" } else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (n.status != NoteStatus.DONE) {
                Spacer(Modifier.height(6.dp))
                val color = if (n.status == NoteStatus.ERROR || n.status == NoteStatus.NO_MODEL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                val label = NoteStatus.label(n.status) + if (n.status == NoteStatus.TRANSCRIBING && n.durationMs > 0)
                    " · ${(n.progressMs * 100 / n.durationMs).coerceIn(0, 100)}%" else ""
                Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                if (NoteStatus.isBusy(n.status)) {
                    if (n.status == NoteStatus.TRANSCRIBING && n.durationMs > 0)
                        LinearProgressIndicator(progress = { (n.progressMs.toFloat() / n.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            if (n.snippet.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(n.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
