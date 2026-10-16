package app.aivoicetotext.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aivoicetotext.App
import app.aivoicetotext.record.RecorderState
import app.aivoicetotext.record.RecordingService
import app.aivoicetotext.ui.ConfirmDialog
import app.aivoicetotext.ui.Waveform
import app.aivoicetotext.util.Fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSheet(app: App, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ui by RecorderState.ui.collectAsStateWithLifecycle()
    val settings by app.prefs.state.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDiscard by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(ui.liveText) { scroll.animateScrollTo(scroll.maxValue) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Fmt.clock(ui.elapsedMs), fontSize = 44.sp, fontWeight = FontWeight.Light)
            Text(
                if (ui.paused) "Пауза" else "Идёт запись" + if (ui.markers > 0) " · меток: ${ui.markers}" else "",
                color = if (ui.paused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Waveform(ui.levels)
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Живой текст", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(checked = settings.liveEnabled, onCheckedChange = { v -> app.prefs.update { copy(liveEnabled = v) } })
            }
            if (settings.liveEnabled) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp),
                ) {
                    Column(Modifier.verticalScroll(scroll).padding(12.dp)) {
                        ui.liveStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        Text(
                            ui.liveText.ifBlank { "Черновик появится через несколько секунд после каждой фразы. После остановки запись будет расшифрована заново основной моделью." },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (ui.liveText.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { RecordingService.send(ctx, if (ui.paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE) }) {
                    Icon(if (ui.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (ui.paused) "Продолжить" else "Пауза")
                }
                FilledTonalButton(onClick = { RecordingService.send(ctx, RecordingService.ACTION_MARK) }) {
                    Icon(Icons.Default.Bookmark, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Метка")
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { RecordingService.send(ctx, RecordingService.ACTION_STOP); onDismiss() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("Готово")
            }
            TextButton(onClick = { confirmDiscard = true }) { Text("Удалить запись", color = MaterialTheme.colorScheme.error) }
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
