package app.aivoicetotext.util

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TextNorm {
    fun normalize(s: String): String = s.lowercase(Locale.ROOT).replace('ё', 'е')

    /** Builds an FTS4 prefix query: every word must match as a prefix ("лекц" -> "лекция", "лекции"). */
    fun ftsQuery(raw: String): String? {
        val words = normalize(raw).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.take(8)
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "$it*" }
    }
}

object Fmt {
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    fun srt(ms: Long, sep: Char = ','): String {
        val v = ms.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", v / 3_600_000, (v / 60_000) % 60, (v / 1000) % 60, sep, v % 1000)
    }

    fun date(time: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru")).format(Date(time))
    fun isoDate(time: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date(time))

    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale("ru"), "%.1f ГБ", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(Locale("ru"), "%.0f МБ", bytes / 1e6)
        else -> String.format(Locale("ru"), "%.0f КБ", bytes / 1e3)
    }

    fun safeFileName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "note" }
}

object Device {
    fun totalRamBytes(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem
    }

    fun availRamBytes(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem
    }

    fun totalRamGb(ctx: Context): Double = totalRamBytes(ctx) / 1_073_741_824.0

    /** Number of "big" cores: cores whose max frequency is within 75% of the fastest core. */
    val bigCores: Int by lazy {
        val n = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until n).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        val result = if (freqs.size >= 2) {
            val max = freqs.max()
            freqs.count { it >= max * 0.75 }
        } else n / 2
        result.coerceIn(2, 8)
    }

    /** ggml is built for armv8.2-a+dotprod; older CPUs (e.g. Cortex-A53 only) would crash. */
    val cpuSupported: Boolean by lazy {
        runCatching {
            val info = File("/proc/cpuinfo").readText()
            info.contains("asimddp")
        }.getOrDefault(true)
    }
}
