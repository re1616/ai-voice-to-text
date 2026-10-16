package app.aivoicetotext.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aivoicetotext.App
import app.aivoicetotext.engine.AskEngine
import app.aivoicetotext.engine.AskSource
import app.aivoicetotext.engine.Engines
import app.aivoicetotext.engine.LlmSession
import app.aivoicetotext.util.Device
import app.aivoicetotext.util.Fmt
import app.aivoicetotext.work.llmContextSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private class Turn(val question: String) {
    var answer by mutableStateOf("")
    var sources by mutableStateOf<List<AskSource>>(emptyList())
    var busy by mutableStateOf(true)
    var status by mutableStateOf("Ищу в заметках…")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskScreen(app: App, back: () -> Unit, openNote: (Long, Long) -> Unit, openModels: () -> Unit) {
    val version by app.models.installedVersion.collectAsStateWithLifecycle()
    val llm = remember(version) { app.models.llm() }
    val turns = remember { mutableStateListOf<Turn>() }
    var input by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1) }

    fun ask(q: String) {
        val question = q.trim()
        val model = llm ?: return
        if (question.isEmpty() || job?.isActive == true) return
        input = ""
        val turn = Turn(question)
        turns += turn
        job = scope.launch {
            try {
                if (!Device.cpuSupported) error("Этот телефон слишком старый для ИИ на устройстве")
                if (Engines.lock.isLocked) turn.status = "Жду, пока закончится другая обработка…"
                val (text, sources) = Engines.lock.withLock {
                    Engines.holder = "ask"
                    try {
                        turn.status = "Загружаю помощника…"
                        val session = withContext(Dispatchers.Default) {
                            LlmSession.open(app.models.fileFor(model), llmContextSize(app), Engines.threads(app.prefs.value))
                        } ?: error("Не удалось загрузить ${model.name}")
                        try {
                            turn.status = "Читаю заметки и думаю…"
                            AskEngine(app.repo).answer(question, model, session) { partial ->
                                scope.launch(Dispatchers.Main) { turn.answer = partial }
                            }
                        } finally {
                            withContext(Dispatchers.Default) { session.close() }
                        }
                    } finally {
                        Engines.holder = null
                    }
                }
                turn.answer = text
                turn.sources = sources
            } catch (e: CancellationException) {
                if (turn.answer.isBlank()) turn.answer = "Остановлено"
            } catch (e: Exception) {
                turn.answer = e.message ?: "Ошибка"
            } finally {
                turn.busy = false
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Спросить у заметок") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (turns.isEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Задайте вопрос обо всём, что вы записывали. Ответ строится только по вашим заметкам, на телефоне.",
                            style = MaterialTheme.typography.bodyLarge)
                        if (llm == null) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("Нужен ИИ-помощник", style = MaterialTheme.typography.titleSmall)
                                    Text("Скачайте его в разделе «Модели» — один раз.", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.material3.Button(onClick = openModels, modifier = Modifier.padding(top = 8.dp)) { Text("Открыть модели") }
                                }
                            }
                        } else {
                            Text("Например:", style = MaterialTheme.typography.labelLarge)
                            listOf("Что я обещал сделать на этой неделе?", "О чём была последняя встреча?", "Какие сроки обсуждали?")
                                .forEach { q -> AssistChip(onClick = { ask(q) }, label = { Text(q) }) }
                        }
                    }
                }
                items(turns) { t -> TurnCard(t, openNote) }
            }
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        placeholder = { Text("Ваш вопрос") },
                        enabled = llm != null,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { ask(input) }),
                        maxLines = 4,
                    )
                    if (job?.isActive == true) {
                        IconButton(onClick = { job?.cancel() }) { Icon(Icons.Default.Stop, "Остановить ответ") }
                    } else {
                        IconButton(onClick = { ask(input) }, enabled = llm != null && input.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Спросить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TurnCard(t: Turn, openNote: (Long, Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                if (t.busy && t.answer.isBlank()) {
                    Text(t.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                } else {
                    SelectionContainer { Text(t.answer, style = MaterialTheme.typography.bodyLarge) }
                }
                if (t.sources.isNotEmpty()) {
                    Text("Источники", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    t.sources.forEach { s ->
                        Text(
                            "[${s.index}] ${s.noteTitle} · ${Fmt.date(s.date)} · ${Fmt.clock(s.startMs)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { openNote(s.noteId, s.startMs) }.padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
