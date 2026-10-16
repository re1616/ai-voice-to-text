package app.aivoicetotext.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import app.aivoicetotext.BuildConfig
import app.aivoicetotext.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

sealed interface DownloadState {
    data class Running(val downloaded: Long, val total: Long, val waitingForWifi: Boolean, val paused: Boolean) : DownloadState
    data object Verifying : DownloadState
    data class Failed(val message: String) : DownloadState
}

class ModelManager(private val context: Context, private val prefs: Prefs) {

    val dir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    /** Bumped whenever the set of installed files changes. */
    private val _installedVersion = MutableStateFlow(0)
    val installedVersion: StateFlow<Int> = _installedVersion.asStateFlow()

    private val dm get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val refreshLock = Mutex()

    val networkAllowed: Boolean get() = BuildConfig.ALLOW_NETWORK && !prefs.value.networkDisabled

    fun catalog(): List<ModelInfo> = ModelCatalog.all(context)

    fun fileFor(info: ModelInfo): File = File(dir, info.fileName)

    fun isInstalled(info: ModelInfo): Boolean = fileFor(info).let { it.exists() && it.length() > 0 }

    /** Catalog models present on disk plus models imported by the user. */
    fun installed(): List<ModelInfo> {
        val cat = catalog()
        val known = cat.map { it.fileName }.toSet()
        val custom = dir.listFiles().orEmpty()
            .filter { it.isFile && it.name !in known && !it.name.endsWith(".part") && (it.name.endsWith(".bin") || it.name.endsWith(".gguf")) }
            .map { f ->
                val type = if (f.name.endsWith(".gguf")) ModelType.LLM else ModelType.STT
                val lower = f.name.lowercase()
                ModelInfo(
                    id = "custom:${f.name}", type = type, name = f.name.removePrefix("custom-"),
                    description = "Импортировано из файла", fileName = f.name, url = null, sizeBytes = f.length(),
                    sha256 = null, quant = "—", minRamGb = 0.0, license = "см. источник модели",
                    noThink = lower.contains("qwen3") && !lower.contains("2507") && !lower.contains("instruct"), custom = true,
                )
            }
        return cat.filter { isInstalled(it) } + custom
    }

    fun byId(id: String?): ModelInfo? = if (id == null) null else installed().firstOrNull { it.id == id }

    /** Main STT model: the chosen one, otherwise the most capable installed one. */
    fun mainStt(): ModelInfo? {
        val inst = installed().filter { it.type == ModelType.STT }
        return inst.firstOrNull { it.id == prefs.value.mainModelId } ?: inst.maxByOrNull { it.sizeBytes }
    }

    /** Live (draft) STT model: the chosen one, otherwise the smallest installed one. */
    fun liveStt(): ModelInfo? {
        val inst = installed().filter { it.type == ModelType.STT }
        return inst.firstOrNull { it.id == prefs.value.liveModelId } ?: inst.minByOrNull { it.sizeBytes }
    }

    fun llm(): ModelInfo? {
        val inst = installed().filter { it.type == ModelType.LLM }
        return inst.firstOrNull { it.id == prefs.value.llmModelId } ?: inst.minByOrNull { it.sizeBytes }
    }

    fun freeSpace(): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    /** @return null on success or a user-facing error. */
    fun startDownload(info: ModelInfo): String? {
        if (!networkAllowed) return "Сеть отключена в настройках"
        val url = info.url ?: return "У модели нет адреса загрузки"
        if (freeSpace() < (info.sizeBytes * 1.2).toLong()) return "Недостаточно места: нужно ${info.sizeBytes * 12 / 10 / 1_000_000} МБ"
        val existing = prefs.downloadId(info.id)
        if (existing >= 0) dm.remove(existing)
        File(dir, info.fileName + ".part").delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Модель ${info.name}")
            .setDescription("AI Voice to Text")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(File(dir, info.fileName + ".part")))
            .setAllowedOverMetered(prefs.value.allowMetered)
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(req)
        prefs.setDownloadId(info.id, id)
        _downloads.value = _downloads.value + (info.id to DownloadState.Running(0, info.sizeBytes, false, false))
        return null
    }

    fun cancelDownload(info: ModelInfo) {
        val id = prefs.downloadId(info.id)
        if (id >= 0) dm.remove(id)
        prefs.setDownloadId(info.id, -1)
        File(dir, info.fileName + ".part").delete()
        _downloads.value = _downloads.value - info.id
    }

    fun delete(info: ModelInfo) {
        fileFor(info).delete()
        prefs.update {
            copy(
                mainModelId = mainModelId.takeIf { it != info.id },
                liveModelId = liveModelId.takeIf { it != info.id },
                llmModelId = llmModelId.takeIf { it != info.id },
            )
        }
        _installedVersion.value++
    }

    /** Polls DownloadManager; verifies and installs finished downloads. */
    suspend fun refresh() = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val states = HashMap<String, DownloadState>()
            for (info in catalog()) {
                val id = prefs.downloadId(info.id)
                if (id < 0) continue
                val q = DownloadManager.Query().setFilterById(id)
                val cursor = dm.query(q)
                if (cursor == null || !cursor.moveToFirst()) {
                    cursor?.close()
                    prefs.setDownloadId(info.id, -1)
                    continue
                }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: info.sizeBytes
                cursor.close()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        _downloads.value = _downloads.value + (info.id to DownloadState.Verifying)
                        val part = File(dir, info.fileName + ".part")
                        val err = verify(part, info)
                        prefs.setDownloadId(info.id, -1)
                        if (err == null) {
                            val target = fileFor(info)
                            target.delete()
                            if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
                            _installedVersion.value++
                            autoSelect(info)
                        } else {
                            part.delete()
                            states[info.id] = DownloadState.Failed(err)
                        }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        dm.remove(id)
                        prefs.setDownloadId(info.id, -1)
                        states[info.id] = DownloadState.Failed("Ошибка загрузки (код $reason)")
                    }
                    else -> states[info.id] = DownloadState.Running(
                        downloaded = done, total = total,
                        waitingForWifi = status == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI,
                        paused = status == DownloadManager.STATUS_PAUSED,
                    )
                }
            }
            // keep failures visible until the user retries
            val failures = _downloads.value.filterValues { it is DownloadState.Failed }
            _downloads.value = failures + states
        }
    }

    fun clearFailure(id: String) { _downloads.value = _downloads.value - id }

    private fun autoSelect(info: ModelInfo) {
        prefs.update {
            when (info.type) {
                ModelType.STT -> copy(
                    mainModelId = mainModelId ?: info.id,
                    liveModelId = liveModelId ?: info.id,
                )
                ModelType.LLM -> copy(llmModelId = llmModelId ?: info.id)
            }
        }
    }

    private fun verify(file: File, info: ModelInfo): String? {
        if (!file.exists()) return "Файл не найден после загрузки"
        if (info.sizeBytes > 0 && info.sha256 != null && file.length() != info.sizeBytes) return "Размер файла не совпадает"
        val expected = info.sha256 ?: return checkMagic(file, info.type)
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        return if (actual.equals(expected, ignoreCase = true)) null else "Контрольная сумма SHA-256 не совпадает"
    }

    /** Recognises the file type by its header: whisper ggml ("lmgg") or GGUF. */
    private fun checkMagic(file: File, type: ModelType): String? {
        val head = ByteArray(4)
        FileInputStream(file).use { if (it.read(head) != 4) return "Файл повреждён" }
        val s = String(head, Charsets.ISO_8859_1)
        val ok = when (type) {
            ModelType.STT -> s == "lmgg" || s == "ggml"
            ModelType.LLM -> s == "GGUF"
        }
        return if (ok) null else "Неверный формат файла модели"
    }

    /** Imports a .bin (whisper) or .gguf (LLM) file chosen by the user. */
    suspend fun import(uri: Uri): Result<ModelInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "model.bin"
            val head = ByteArray(4)
            resolver.openInputStream(uri)?.use { it.read(head) } ?: error("Не удалось открыть файл")
            val magic = String(head, Charsets.ISO_8859_1)
            val ext = when (magic) {
                "GGUF" -> ".gguf"
                "lmgg", "ggml" -> ".bin"
                else -> error("Это не модель whisper.cpp (.bin) и не GGUF")
            }
            val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_")
            val catalogMatch = catalog().firstOrNull { it.fileName.equals(name, ignoreCase = true) }
            val target = if (catalogMatch != null) fileFor(catalogMatch) else File(dir, "custom-$base$ext")
            val tmp = File(dir, target.name + ".part")
            resolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
            target.delete()
            tmp.renameTo(target)
            _installedVersion.value++
            val info = installed().first { it.fileName == target.name }
            autoSelect(info)
            info
        }
    }
}
