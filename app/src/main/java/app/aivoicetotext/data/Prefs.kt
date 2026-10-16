package app.aivoicetotext.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val onboardingDone: Boolean = false,
    val language: String = "auto",
    val mainModelId: String? = null,
    val liveModelId: String? = null,
    val llmModelId: String? = null,
    val liveEnabled: Boolean = true,
    val threads: Int = 0,
    val vocabulary: String = "",
    val onlyCharging: Boolean = false,
    val secureScreen: Boolean = false,
    val autoExportUri: String? = null,
    val allowMetered: Boolean = true,
    val networkDisabled: Boolean = false,
    val autoScroll: Boolean = true,
    val skipSilence: Boolean = false,
    val playbackSpeed: Float = 1f,
)

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value: Settings get() = _state.value

    private fun read() = Settings(
        onboardingDone = sp.getBoolean("onboardingDone", false),
        language = sp.getString("language", "auto") ?: "auto",
        mainModelId = sp.getString("mainModelId", null),
        liveModelId = sp.getString("liveModelId", null),
        llmModelId = sp.getString("llmModelId", null),
        liveEnabled = sp.getBoolean("liveEnabled", true),
        threads = sp.getInt("threads", 0),
        vocabulary = sp.getString("vocabulary", "") ?: "",
        onlyCharging = sp.getBoolean("onlyCharging", false),
        secureScreen = sp.getBoolean("secureScreen", false),
        autoExportUri = sp.getString("autoExportUri", null),
        allowMetered = sp.getBoolean("allowMetered", true),
        networkDisabled = sp.getBoolean("networkDisabled", false),
        autoScroll = sp.getBoolean("autoScroll", true),
        skipSilence = sp.getBoolean("skipSilence", false),
        playbackSpeed = sp.getFloat("playbackSpeed", 1f),
    )

    fun update(block: Settings.() -> Settings) {
        val s = _state.value.block()
        sp.edit()
            .putBoolean("onboardingDone", s.onboardingDone)
            .putString("language", s.language)
            .putString("mainModelId", s.mainModelId)
            .putString("liveModelId", s.liveModelId)
            .putString("llmModelId", s.llmModelId)
            .putBoolean("liveEnabled", s.liveEnabled)
            .putInt("threads", s.threads)
            .putString("vocabulary", s.vocabulary)
            .putBoolean("onlyCharging", s.onlyCharging)
            .putBoolean("secureScreen", s.secureScreen)
            .putString("autoExportUri", s.autoExportUri)
            .putBoolean("allowMetered", s.allowMetered)
            .putBoolean("networkDisabled", s.networkDisabled)
            .putBoolean("autoScroll", s.autoScroll)
            .putBoolean("skipSilence", s.skipSilence)
            .putFloat("playbackSpeed", s.playbackSpeed)
            .apply()
        _state.value = s
    }

    /** Download bookkeeping: model id -> DownloadManager id. */
    fun downloadId(modelId: String): Long = sp.getLong("dl_$modelId", -1L)
    fun setDownloadId(modelId: String, id: Long) {
        if (id < 0) sp.edit().remove("dl_$modelId").apply() else sp.edit().putLong("dl_$modelId", id).apply()
    }

    /** Measured speed (x realtime) per model, shown in the model manager. */
    fun modelSpeed(modelId: String): Float = sp.getFloat("speed_$modelId", 0f)
    fun setModelSpeed(modelId: String, speed: Float) = sp.edit().putFloat("speed_$modelId", speed).apply()

    fun exportAll(): Map<String, *> = sp.all
    fun importAll(map: Map<String, Any?>) {
        val e = sp.edit().clear()
        for ((k, v) in map) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is Double -> e.putFloat(k, v.toFloat())
            is String -> e.putString(k, v)
        }
        e.commit()
        _state.value = read()
    }
}
