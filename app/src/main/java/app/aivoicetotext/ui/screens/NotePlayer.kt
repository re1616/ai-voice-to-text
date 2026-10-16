package app.aivoicetotext.ui.screens

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Minimal MediaPlayer wrapper with Compose state. */
class NotePlayer(path: String?) {
    private var mp: MediaPlayer? = null
    var ready by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L); private set
    var speed by mutableFloatStateOf(1f); private set

    init {
        if (path != null && File(path).exists()) {
            runCatching {
                val p = MediaPlayer()
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                p.setDataSource(path)
                p.setOnCompletionListener { playing = false }
                p.prepare()
                duration = p.duration.toLong()
                mp = p
                ready = true
            }
        }
    }

    fun toggle() {
        val p = mp ?: return
        if (p.isPlaying) { p.pause(); playing = false } else { applySpeed(); p.start(); playing = true }
    }

    fun play() { val p = mp ?: return; if (!p.isPlaying) { applySpeed(); p.start(); playing = true } }

    fun seek(ms: Long) {
        val p = mp ?: return
        val t = ms.coerceIn(0, duration)
        if (android.os.Build.VERSION.SDK_INT >= 26) p.seekTo(t, MediaPlayer.SEEK_CLOSEST) else p.seekTo(t.toInt())
        position = t
    }

    fun setPlaybackSpeed(s: Float) {
        speed = s
        if (mp?.isPlaying == true) applySpeed()
    }

    private fun applySpeed() {
        val p = mp ?: return
        runCatching {
            val wasPlaying = p.isPlaying
            p.playbackParams = PlaybackParams().setSpeed(speed)
            if (!wasPlaying) p.pause() // setting params starts playback on some devices
        }
    }

    fun poll(): Long {
        val p = mp ?: return position
        if (p.isPlaying) position = p.currentPosition.toLong()
        playing = p.isPlaying
        return position
    }

    fun release() {
        mp?.release()
        mp = null
        ready = false
        playing = false
    }
}
