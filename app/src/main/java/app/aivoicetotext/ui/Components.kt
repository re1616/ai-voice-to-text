package app.aivoicetotext.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String = "",
    singleLine: Boolean = true,
    confirm: String = "Сохранить",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { if (label.isNotEmpty()) Text(label) },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun <T> ChoiceDialog(title: String, items: List<T>, label: (T) -> String, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                items.forEach { item ->
                    TextButton(onClick = { onPick(item) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label(item), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
fun Waveform(levels: List<Float>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp)) {
        val n = 60
        val step = size.width / n
        val mid = size.height / 2
        val start = n - levels.size
        levels.forEachIndexed { i, v ->
            val h = (v * 6f).coerceIn(0.03f, 1f) * size.height / 2
            val x = (start + i) * step + step / 2
            drawLine(color, Offset(x, mid - h), Offset(x, mid + h), strokeWidth = step * 0.6f, cap = StrokeCap.Round)
        }
    }
}

val LANGUAGES = listOf(
    "auto" to "Автоопределение",
    "ru" to "Русский",
    "en" to "English",
    "uk" to "Українська",
    "be" to "Беларуская",
    "kk" to "Қазақша",
    "de" to "Deutsch",
    "fr" to "Français",
    "es" to "Español",
    "it" to "Italiano",
    "pl" to "Polski",
    "tr" to "Türkçe",
    "zh" to "中文",
    "ja" to "日本語",
)

fun languageName(code: String?) = LANGUAGES.firstOrNull { it.first == code }?.second ?: (code ?: "—")
