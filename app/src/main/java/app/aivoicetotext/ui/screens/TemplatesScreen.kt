package app.aivoicetotext.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aivoicetotext.App
import app.aivoicetotext.data.PromptTemplate
import app.aivoicetotext.data.TemplateCategory
import app.aivoicetotext.data.TemplateKind
import app.aivoicetotext.data.TemplateMode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(app: App, back: () -> Unit) {
    val templates by remember { app.repo.observeTemplates() }.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<PromptTemplate?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Шаблоны ИИ-действий") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = PromptTemplate(0, "", "⚙️", "", TemplateKind.TEXT, TemplateMode.AGGREGATE, 0.3f, false, (templates.maxOfOrNull { it.sort } ?: 0) + 1, TemplateCategory.CUSTOM)
            }) { Icon(Icons.Default.Add, "Новый шаблон") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(templates, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().clickable { editing = t }) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${t.emoji} ${t.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(t.systemPrompt, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (t.kind == TemplateKind.CHECKLIST) "чек-лист" else "текст") + " · " +
                                (if (t.mode == TemplateMode.TRANSFORM) "по частям" else "сводный итог") + if (t.builtin) " · встроенный" else "",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    editing?.let { t ->
        TemplateEditor(t, onDismiss = { editing = null }, onDelete = if (t.id != 0L) ({ app.repo.deleteTemplate(t.id); editing = null }) else null) {
            app.repo.saveTemplate(it); editing = null
        }
    }
}

@Composable
private fun TemplateEditor(t: PromptTemplate, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (PromptTemplate) -> Unit) {
    var name by remember { mutableStateOf(t.name) }
    var emoji by remember { mutableStateOf(t.emoji) }
    var prompt by remember { mutableStateOf(t.systemPrompt) }
    var kind by remember { mutableStateOf(t.kind) }
    var mode by remember { mutableStateOf(t.mode) }
    var temp by remember { mutableFloatStateOf(t.temperature) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (t.id == 0L) "Новый шаблон" else "Шаблон") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row {
                    OutlinedTextField(emoji, { emoji = it.take(4) }, label = { Text("Иконка") }, singleLine = true, modifier = Modifier.width(88.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.padding(start = 8.dp).fillMaxWidth())
                }
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Инструкция для модели") }, minLines = 5,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("Результат", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row {
                    FilterChip(kind == TemplateKind.TEXT, { kind = TemplateKind.TEXT }, label = { Text("Текст") })
                    FilterChip(kind == TemplateKind.CHECKLIST, { kind = TemplateKind.CHECKLIST }, label = { Text("Чек-лист") }, modifier = Modifier.padding(start = 8.dp))
                }
                Text("Длинные записи", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row {
                    FilterChip(mode == TemplateMode.AGGREGATE, { mode = TemplateMode.AGGREGATE }, label = { Text("Сводный итог") })
                    FilterChip(mode == TemplateMode.TRANSFORM, { mode = TemplateMode.TRANSFORM }, label = { Text("По частям") }, modifier = Modifier.padding(start = 8.dp))
                }
                Text(String.format(Locale.ROOT, "Креативность: %.1f", temp), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Slider(temp, { temp = it }, valueRange = 0f..1f)
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Удалить шаблон", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && prompt.isNotBlank(), onClick = {
                onSave(t.copy(name = name.trim(), emoji = emoji.ifBlank { "⚙️" }, systemPrompt = prompt.trim(), kind = kind, mode = mode, temperature = temp))
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
