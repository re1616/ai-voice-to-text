package app.aivoicetotext.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Process
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import app.aivoicetotext.export.Exporter
import app.aivoicetotext.ui.ChoiceDialog
import app.aivoicetotext.ui.ConfirmDialog
import app.aivoicetotext.ui.LANGUAGES
import app.aivoicetotext.ui.MainActivity
import app.aivoicetotext.ui.TextInputDialog
import app.aivoicetotext.ui.languageName
import app.aivoicetotext.ui.title
import app.aivoicetotext.util.Device
import app.aivoicetotext.whisper.WhisperLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: App,
    @Suppress("UNUSED_PARAMETER") activity: MainActivity,
    back: () -> Unit,
    openModels: () -> Unit,
    openTemplates: () -> Unit,
) {
    val ctx = LocalContext.current
    val s by app.prefs.state.collectAsStateWithLifecycle()
    val version by app.models.installedVersion.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pickLang by remember { mutableStateOf(false) }
    var editVocab by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var about by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val modelsSummary = remember(version, s) {
        listOfNotNull(app.models.mainStt()?.title(), app.models.llm()?.title()).joinToString(" · ").ifBlank { "Ничего не скачано" }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            app.prefs.update { copy(autoExportUri = uri.toString()) }
            scope.launch(Dispatchers.IO) {
                app.repo.allNotes().filter { it.deletedAt == null }.forEach { runCatching { Exporter.autoExport(app, it.id) } }
            }
        }
    }
    val backupCreator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Exporter.writeBackup(app, uri) } }
            Toast.makeText(ctx, if (r.isSuccess) "Резервная копия сохранена" else "Ошибка: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Настройки") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Item("Язык записей", languageName(s.language)) { pickLang = true }
            Item("Словарь", s.vocabulary.ifBlank { "Имена и термины, которые нужно писать правильно" }) { editVocab = true }
            Item("Модели", modelsSummary, onClick = openModels)
            Item("Папка для заметок", s.autoExportUri?.let { "Сохраняю в: " + (Uri.parse(it).lastPathSegment?.substringAfter(':') ?: it) }
                ?: "Сохранять каждую заметку файлом .md (например, для Obsidian)") { folderPicker.launch(null) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Резервная копия", style = MaterialTheme.typography.bodyLarge)
                Text("Все заметки, аудио и настройки одним файлом", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = {
                        backupCreator.launch("ai-voice-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".zip")
                    }) { Text("Сохранить") }
                    OutlinedButton(onClick = { backupPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        modifier = Modifier.padding(start = 8.dp)) { Text("Восстановить") }
                }
            }
            Toggle("Скрывать содержимое", "Без скриншотов и превью в списке приложений", s.secureScreen) { app.prefs.update { copy(secureScreen = it) } }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Дополнительно", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() })
                Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (advanced) "Свернуть" else "Развернуть")
            }
            if (advanced) {
                Toggle("Выжимка после каждой записи", "Название и 3 главных тезиса (нужен ИИ-помощник)", s.autoSummary) { app.prefs.update { copy(autoSummary = it) } }
                Toggle("Определять, кто говорит", "Автоматически для каждой записи (нужна модель голосов)", s.autoSpeakers) { app.prefs.update { copy(autoSpeakers = it) } }
                Toggle("Живой текст во время записи", "Черновик сразу, точный текст после остановки", s.liveEnabled) { app.prefs.update { copy(liveEnabled = it) } }
                Toggle("Только на зарядке", "Долгая обработка ждёт подключения питания", s.onlyCharging) { app.prefs.update { copy(onlyCharging = it) } }
                Item("Шаблоны ИИ-действий", "Свои инструкции для помощника", onClick = openTemplates)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Ядра процессора: " + if (s.threads == 0) "автоматически" else "${s.threads}", style = MaterialTheme.typography.bodyLarge)
                    Slider(value = s.threads.toFloat(), onValueChange = { v -> app.prefs.update { copy(threads = v.toInt()) } }, valueRange = 0f..8f, steps = 7)
                }
                if (BuildConfig.ALLOW_NETWORK) {
                    Toggle("Запретить интернет", "Отключить скачивание моделей", s.networkDisabled) { app.prefs.update { copy(networkDisabled = it) } }
                }
                if (s.autoExportUri != null) Item("Не сохранять заметки в папку", null) { app.prefs.update { copy(autoExportUri = null) } }
                Item("О приложении", "Версия ${BuildConfig.VERSION_NAME} · без рекламы и аналитики") { about = true }
            }
        }
    }

    if (pickLang) ChoiceDialog("Язык записей", LANGUAGES, { it.second }, onDismiss = { pickLang = false }) {
        app.prefs.update { copy(language = it.first) }; pickLang = false
    }
    if (editVocab) TextInputDialog("Словарь", s.vocabulary, label = "Через запятую: имена, названия, термины", singleLine = false,
        onDismiss = { editVocab = false }) { app.prefs.update { copy(vocabulary = it.trim()) }; editVocab = false }
    restoreUri?.let { uri ->
        ConfirmDialog("Восстановить из копии?", "Текущие заметки будут заменены. Приложение перезапустится.", "Восстановить",
            onDismiss = { restoreUri = null }) {
            restoreUri = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Exporter.restoreBackup(app, uri) } }
                if (r.isSuccess) {
                    val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (i != null) ctx.startActivity(i)
                    Process.killProcess(Process.myPid())
                } else {
                    app.reopenDatabase()
                    Toast.makeText(ctx, "Не получилось: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    if (about) {
        val info = remember { runCatching { WhisperLib.systemInfo() }.getOrDefault("—") }
        ConfirmDialog("О приложении",
            "AI Voice to Text ${BuildConfig.VERSION_NAME}\nВсё работает на телефоне, без рекламы и аналитики.\n" +
                "Открытые библиотеки: whisper.cpp, llama.cpp, Silero VAD, ONNX Runtime, WeSpeaker.\n\n" +
                "Процессор: ${if (Device.cpuSupported) "подходит" else "слишком старый"}\n$info",
            "OK", onDismiss = { about = false }) { about = false }
    }
}

@Composable
private fun Item(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
