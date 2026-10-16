package app.aivoicetotext.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aivoicetotext.App
import app.aivoicetotext.record.RecorderState
import app.aivoicetotext.record.RecordingService
import app.aivoicetotext.ui.ConfirmDialog
import app.aivoicetotext.ui.Waveform
import app.aivoicetotext.ui.haptic
import app.aivoicetotext.util.Fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSheet(app: App, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val ui by RecorderState.ui.collectAsStateWithLifecycle()
    val settings by app.prefs.state.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDiscard by remember { mutableStateOf(false) }
    var liveExpanded by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(ui.liveText, liveExpanded) { scroll.animateScrollTo(scroll.maxValue) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Fmt.clock(ui.elapsedMs), fontSize = 56.sp, fontWeight = FontWeight.Light)
            Text(
                if (ui.paused) "Пауза" else "Идёт запись" + if (ui.markers > 0) " · ★ ${ui.markers}" else "",
                style = MaterialTheme.typography.titleMedium,
                color = if (ui.paused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            )
            Text("Сохраняется автоматически — можно свернуть приложение", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Waveform(ui.levels)
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = { RecordingService.send(ctx, if (ui.paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE) },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(if (ui.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (ui.paused) "Продолжить" else "Пауза")
                }
                FilledTonalButton(
                    onClick = { view.haptic(); RecordingService.send(ctx, RecordingService.ACTION_MARK) },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(Icons.Default.Star, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Важно")
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { view.haptic(); RecordingService.send(ctx, RecordingService.ACTION_STOP); onDismiss() },
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("Готово", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(12.dp))
            // live draft: one line by default, expandable
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().clickable { liveExpanded = !liveExpanded },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (!settings.liveEnabled) "Живой текст выключен"
                            else ui.liveText.takeLast(80).ifBlank { ui.liveStatus ?: "Живой текст появится после первой фразы" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Icon(if (liveExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            if (liveExpanded) "Свернуть живой текст" else "Развернуть живой текст", Modifier.size(24.dp))
                    }
                    if (liveExpanded) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Text("Показывать живой текст", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(checked = settings.liveEnabled, onCheckedChange = { v -> app.prefs.update { copy(liveEnabled = v) } })
                        }
                        if (settings.liveEnabled) {
                            ui.liveStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                            Column(Modifier.heightIn(max = 220.dp).verticalScroll(scroll)) {
                                Text(ui.liveText.ifBlank { "Черновик появляется через несколько секунд после каждой фразы. После остановки запись будет расшифрована заново, точнее." },
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Удалить запись", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog("Удалить запись?", "Записанный звук будет удалён.", "Удалить", onDismiss = { confirmDiscard = false }) {
            RecordingService.send(ctx, RecordingService.ACTION_DISCARD)
            confirmDiscard = false
            onDismiss()
        }
    }
}
