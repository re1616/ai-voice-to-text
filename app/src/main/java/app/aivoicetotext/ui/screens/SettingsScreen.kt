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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
fun SettingsScreen(app: App, activity: MainActivity, back: () -> Unit, openModels: () -> Unit, openTemplates: () -> Unit) {
    val ctx = LocalContext.current
    val s by app.prefs.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pickLang by remember { mutableStateOf(false) }
    var editVocab by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var about by remember { mutableStateOf(false) }

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
            Header("Модели и ИИ")
            Item("Модели", "Скачать, выбрать или удалить модели", onClick = openModels)
            Item("Шаблоны ИИ-действий", "Свои промпты: конспект, суммы и даты, протокол…", onClick = openTemplates)

            Header("Распознавание")
            Item("Язык по умолчанию", languageName(s.language)) { pickLang = true }
            Item("Словарь терминов", s.vocabulary.ifBlank { "Имена, названия и термины для точного написания" }) { editVocab = true }
            Toggle("Живой текст во время записи", "Черновик малой моделью; после остановки — чистовик основной моделью", s.liveEnabled) {
                app.prefs.update { copy(liveEnabled = it) }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Потоки процессора: " + if (s.threads == 0) "авто (${Device.bigCores})" else "${s.threads}", style = MaterialTheme.typography.bodyLarge)
                Slider(value = s.threads.toFloat(), onValueChange = { v -> app.prefs.update { copy(threads = v.toInt()) } }, valueRange = 0f..8f, steps = 7)
                Text("0 = только производительные ядра (рекомендуется)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Toggle("Обрабатывать только на зарядке", "Расшифровка и ИИ ждут подключения питания", s.onlyCharging) {
                app.prefs.update { copy(onlyCharging = it) }
            }

            Header("Хранение и экспорт")
            Item("Папка автоэкспорта (Obsidian)", s.autoExportUri?.let { Uri.parse(it).lastPathSegment ?: it } ?: "Не выбрана — заметки сохраняются как .md") {
                folderPicker.launch(null)
            }
            if (s.autoExportUri != null) Item("Отключить автоэкспорт", null) { app.prefs.update { copy(autoExportUri = null) } }
            Item("Создать резервную копию", "База заметок, аудио и настройки в .zip") {
                backupCreator.launch("ai-voice-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".zip")
            }
            Item("Восстановить из копии", "Заменит текущие заметки") { backupPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }

            Header("Приватность")
            Toggle("Скрывать содержимое", "Запрет скриншотов и превью в списке недавних приложений", s.secureScreen) {
                app.prefs.update { copy(secureScreen = it) }
            }
            if (BuildConfig.ALLOW_NETWORK) {
                Toggle("Запретить сеть", "Полностью отключить загрузку моделей", s.networkDisabled) {
                    app.prefs.update { copy(networkDisabled = it) }
                }
            }
            Text(
                "Приложение не собирает аналитику и не отправляет аудио или текст. Сеть используется только для скачивания моделей.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )

            Header("О приложении")
            Item("AI Voice to Text ${BuildConfig.VERSION_NAME}", "Открытые библиотеки: whisper.cpp (MIT), llama.cpp (MIT), Silero VAD (MIT)") { about = true }
        }
    }

    if (pickLang) ChoiceDialog("Язык по умолчанию", LANGUAGES, { it.second }, onDismiss = { pickLang = false }) {
        app.prefs.update { copy(language = it.first) }; pickLang = false
    }
    if (editVocab) TextInputDialog("Словарь терминов", s.vocabulary, label = "Через запятую: имена, бренды, термины", singleLine = false,
        onDismiss = { editVocab = false }) { app.prefs.update { copy(vocabulary = it.trim()) }; editVocab = false }
    restoreUri?.let { uri ->
        ConfirmDialog("Восстановить из копии?", "Текущие заметки будут заменены содержимым архива. Приложение перезапустится.", "Восстановить",
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
                    Toast.makeText(ctx, "Ошибка: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    if (about) {
        val info = remember { runCatching { WhisperLib.systemInfo() }.getOrDefault("—") }
        ConfirmDialog("О приложении",
            "Версия ${BuildConfig.VERSION_NAME}\nПроцессор: ${if (Device.cpuSupported) "поддерживается" else "не поддерживается"}\n\n$info",
            "OK", onDismiss = { about = false }) { about = false }
    }
}

@Composable
private fun Header(t: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Item(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
