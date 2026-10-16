package app.aivoicetotext.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.aivoicetotext.audio.PcmFile
import app.aivoicetotext.audio.SAMPLE_RATE
import app.aivoicetotext.data.Segment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Kaldi-compatible 80-dim log-mel filterbank (25 ms window, 10 ms shift, povey window). */
object Fbank {
    private const val N_FFT = 512
    private const val FRAME = 400
    private const val SHIFT = 160
    const val MELS = 80

    private val window = DoubleArray(FRAME) { i -> (0.5 - 0.5 * cos(2 * PI * i / (FRAME - 1))).pow(0.85) }

    private class Bin(val start: Int, val weights: FloatArray)

    private val bins: Array<Bin> by lazy {
        fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)
        val lowMel = mel(20.0)
        val highMel = mel(SAMPLE_RATE / 2.0 - 400.0)
        val delta = (highMel - lowMel) / (MELS + 1)
        val nBins = N_FFT / 2
        Array(MELS) { m ->
            val left = lowMel + m * delta
            val center = left + delta
            val right = center + delta
            val w = FloatArray(nBins)
            var first = -1
            var last = -1
            for (k in 0 until nBins) {
                val melK = mel(k * SAMPLE_RATE.toDouble() / N_FFT)
                if (melK > left && melK < right) {
                    w[k] = (if (melK <= center) (melK - left) / (center - left) else (right - melK) / (right - center)).toFloat()
                    if (first < 0) first = k
                    last = k
                }
            }
            if (first < 0) Bin(0, FloatArray(0)) else Bin(first, w.copyOfRange(first, last + 1))
        }
    }

    /** @param samples audio in -1..1 at 16 kHz; returns frames x 80, mean-normalised over time */
    fun compute(samples: FloatArray): Array<FloatArray> {
        if (samples.size < FRAME) return emptyArray()
        val frames = 1 + (samples.size - FRAME) / SHIFT
        val out = Array(frames) { FloatArray(MELS) }
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        val power = DoubleArray(N_FFT / 2)
        for (f in 0 until frames) {
            val off = f * SHIFT
            var mean = 0.0
            for (i in 0 until FRAME) { re[i] = samples[off + i] * 32768.0; mean += re[i] }
            mean /= FRAME
            for (i in 0 until FRAME) re[i] -= mean
            for (i in FRAME - 1 downTo 1) re[i] -= 0.97 * re[i - 1]
            re[0] -= 0.97 * re[0]
            for (i in 0 until FRAME) re[i] *= window[i]
            for (i in FRAME until N_FFT) re[i] = 0.0
            java.util.Arrays.fill(im, 0.0)
            fft(re, im)
            for (k in 0 until N_FFT / 2) power[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until MELS) {
                val b = bins[m]
                var e = 0.0
                for (j in b.weights.indices) e += b.weights[j] * power[b.start + j]
                out[f][m] = ln(max(e, 1.1920929e-7)).toFloat()
            }
        }
        // cepstral mean normalisation
        for (m in 0 until MELS) {
            var s = 0.0
            for (f in 0 until frames) s += out[f][m]
            val mu = (s / frames).toFloat()
            for (f in 0 until frames) out[f][m] -= mu
        }
        return out
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val xr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val xi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + xr; im[i + k] = ui + xi
                    re[i + k + len / 2] = ur - xr; im[i + k + len / 2] = ui - xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Speaker embedding model (WeSpeaker ResNet34, ONNX). */
class SpeakerEmbedder(modelFile: File, threads: Int) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads.coerceIn(1, 4))
    })
    private val inputName = session.inputNames.first()

    fun embed(samples: FloatArray): FloatArray? {
        val feats = Fbank.compute(samples)
        if (feats.size < 50) return null // < 0.5 s
        val flat = FloatArray(feats.size * Fbank.MELS)
        for (i in feats.indices) System.arraycopy(feats[i], 0, flat, i * Fbank.MELS, Fbank.MELS)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, feats.size.toLong(), Fbank.MELS.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val v = (res[0].value as Array<FloatArray>)[0]
                var n = 0.0
                for (x in v) n += x * x
                val norm = sqrt(n).toFloat().coerceAtLeast(1e-6f)
                return FloatArray(v.size) { v[it] / norm }
            }
        }
    }

    override fun close() = session.close()
}

/**
 * Segment-level speaker diarization: one embedding per transcript segment, then
 * agglomerative clustering (average linkage, cosine similarity).
 */
object Diarizer {
    private const val THRESHOLD = 0.42f
    private const val MAX_SPEAKERS = 8
    private const val MAX_CLUSTER_ITEMS = 500

    data class Result(val assignment: Map<Long, Int>, val count: Int)

    suspend fun run(
        pcm: File,
        segments: List<Segment>,
        embedder: SpeakerEmbedder,
        numSpeakers: Int?,
        onProgress: suspend (Int, Int) -> Unit,
    ): Result {
        val segs = segments.filter { !it.draft }
        val embeddings = arrayOfNulls<FloatArray>(segs.size)
        val durations = FloatArray(segs.size) { (segs[it].endMs - segs[it].startMs) / 1000f }
        val total = PcmFile.sampleCount(pcm)
        for ((i, s) in segs.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (i % 5 == 0) onProgress(i, segs.size)
            if (durations[i] < 0.8f) continue
            // at most 10 s from the middle of long segments
            val len = (s.endMs - s.startMs).coerceAtMost(10_000)
            val mid = (s.startMs + s.endMs) / 2
            val from = ((mid - len / 2) * SAMPLE_RATE / 1000).coerceIn(0, total)
            val samples = PcmFile.readFloats(pcm, from, (len * SAMPLE_RATE / 1000).toInt())
            embeddings[i] = embedder.embed(samples)
        }
        onProgress(segs.size, segs.size)

        val usable = segs.indices.filter { embeddings[it] != null }
        if (usable.isEmpty()) return Result(emptyMap(), 0)

        // cluster the longest segments, then attach the rest to the nearest centroid
        val core = usable.sortedByDescending { durations[it] }.take(MAX_CLUSTER_ITEMS)
        val clusters = cluster(core.map { embeddings[it]!! }, numSpeakers)
        val centroids = clusters.map { members -> centroid(members.map { embeddings[core[it]]!! }) }

        val label = IntArray(segs.size) { -1 }
        for (i in usable) label[i] = nearest(embeddings[i]!!, centroids)

        // drop tiny clusters (< 4 s of speech) into the nearest big one
        val speech = FloatArray(centroids.size)
        for (i in usable) speech[label[i]] += durations[i]
        val big = centroids.indices.filter { speech[it] >= 4f }.ifEmpty { listOf(speech.indices.maxBy { speech[it] }) }
        for (i in usable) if (label[i] !in big) label[i] = big[nearest(embeddings[i]!!, big.map { centroids[it] })]

        // short segments: same speaker as the previous (or next) labelled segment
        for (i in segs.indices) if (label[i] < 0) {
            label[i] = (i - 1 downTo 0).firstOrNull { label[it] >= 0 }?.let { label[it] }
                ?: (i + 1 until segs.size).firstOrNull { label[it] >= 0 }?.let { label[it] } ?: big.first()
        }

        // renumber by first appearance
        val order = LinkedHashMap<Int, Int>()
        for (i in segs.indices) order.getOrPut(label[i]) { order.size }
        val assignment = segs.indices.associate { segs[it].id to order[label[it]]!! }
        return Result(assignment, order.size)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun centroid(vs: List<FloatArray>): FloatArray {
        val c = FloatArray(vs.first().size)
        for (v in vs) for (i in c.indices) c[i] += v[i]
        var n = 0.0
        for (x in c) n += x * x
        val norm = sqrt(n).toFloat().coerceAtLeast(1e-6f)
        for (i in c.indices) c[i] /= norm
        return c
    }

    private fun nearest(v: FloatArray, cs: List<FloatArray>): Int {
        var best = 0
        var bestSim = -2f
        for ((i, c) in cs.withIndex()) { val s = dot(v, c); if (s > bestSim) { bestSim = s; best = i } }
        return best
    }

    /** Average-linkage agglomerative clustering. Returns clusters as lists of item indices. */
    internal fun cluster(items: List<FloatArray>, k: Int?): List<List<Int>> {
        val n = items.size
        val members = MutableList(n) { mutableListOf(it) }
        val alive = BooleanArray(n) { true }
        val sim = Array(n) { i -> FloatArray(n) { j -> if (i == j) -2f else dot(items[i], items[j]) } }
        var count = n
        val target = k?.coerceIn(1, n)
        while (count > 1) {
            var bi = -1; var bj = -1; var best = -2f
            for (i in 0 until n) if (alive[i]) {
                val row = sim[i]
                for (j in i + 1 until n) if (alive[j] && row[j] > best) { best = row[j]; bi = i; bj = j }
            }
            if (bi < 0) break
            if (target != null) { if (count <= target) break } else if (best < THRESHOLD && count <= MAX_SPEAKERS) break
            // merge bj into bi (size-weighted average of similarities)
            val si = members[bi].size.toFloat()
            val sj = members[bj].size.toFloat()
            for (x in 0 until n) if (alive[x] && x != bi && x != bj) {
                val v = (sim[bi][x] * si + sim[bj][x] * sj) / (si + sj)
                sim[bi][x] = v; sim[x][bi] = v
            }
            members[bi].addAll(members[bj])
            alive[bj] = false
            count--
        }
        return (0 until n).filter { alive[it] }.map { members[it] }
    }
}
