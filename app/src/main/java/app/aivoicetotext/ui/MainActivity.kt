package app.aivoicetotext.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.aivoicetotext.App
import app.aivoicetotext.record.RecorderState
import app.aivoicetotext.record.RecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Note id to open (from a notification or after an import). */
    val openNote = MutableStateFlow<Long?>(null)

    private val app get() = application as App

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) {
            if (pendingRecord) RecordingService.start(this)
        } else if (pendingRecord) {
            Toast.makeText(this, "Без доступа к микрофону запись невозможна", Toast.LENGTH_LONG).show()
        }
        pendingRecord = false
    }
    private var pendingRecord = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            app.prefs.state.collect { s ->
                if (s.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent { AppTheme { AppNav(this) } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { import(listOf(it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { import(it) }
            }
            Intent.ACTION_VIEW -> intent.data?.let { import(listOf(it)) }
            ACTION_OPEN_NOTE -> openNote.value = intent.getLongExtra(EXTRA_NOTE_ID, -1).takeIf { it > 0 }
            ACTION_RECORD -> startRecording()
        }
    }

    fun import(uris: List<Uri>) {
        lifecycleScope.launch {
            var last: Long? = null
            for (u in uris) {
                try {
                    last = app.importAudio(u)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Не удалось импортировать: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            if (uris.size == 1 && last != null) openNote.value = last
            else if (last != null) Toast.makeText(this@MainActivity, "Файлы добавлены в очередь: ${uris.size}", Toast.LENGTH_SHORT).show()
        }
    }

    fun startRecording() {
        if (RecorderState.ui.value.active) return
        val need = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Manifest.permission.RECORD_AUDIO in need) {
            pendingRecord = true
            permissions.launch(need.toTypedArray())
        } else {
            if (need.isNotEmpty()) permissions.launch(need.toTypedArray())
            RecordingService.start(this)
        }
    }

    fun requestBasicPermissions() {
        val need = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) permissions.launch(need.toTypedArray())
    }

    companion object {
        const val ACTION_OPEN_NOTE = "app.aivoicetotext.OPEN_NOTE"
        const val ACTION_RECORD = "app.aivoicetotext.RECORD"
        const val EXTRA_NOTE_ID = "noteId"
    }
}
