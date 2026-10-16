package app.aivoicetotext.record

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import app.aivoicetotext.R
import app.aivoicetotext.ui.MainActivity

/**
 * Invisible activity used by the widget, the Quick Settings tile and the app shortcut.
 * Being a visible activity lets it start the microphone foreground service on Android 14+.
 */
class QuickRecordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when {
            RecorderState.ui.value.active -> RecordingService.send(this, RecordingService.ACTION_STOP)
            granted -> {
                RecordingService.start(this)
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            else -> startActivity(
                Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_RECORD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    companion object {
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 7, Intent(context, QuickRecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_record)
            views.setOnClickPendingIntent(R.id.widget_root, QuickRecordActivity.pendingIntent(context))
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        fun refreshAll(context: Context) {
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, RecordWidget::class.java))
            if (ids.isNotEmpty()) RecordWidget().onUpdate(context, m, ids)
        }
    }
}

class RecordTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val active = RecorderState.ui.value.active
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "Остановить запись" else "Запись голоса"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(QuickRecordActivity.pendingIntent(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(Intent(this, QuickRecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
