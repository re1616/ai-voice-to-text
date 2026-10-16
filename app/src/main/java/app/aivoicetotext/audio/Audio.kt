package app.aivoicetotext.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.floor

const val SAMPLE_RATE = 16_000

/** Raw 16 kHz mono signed 16-bit little-endian PCM file helpers. */
object PcmFile {
    fun durationMs(file: File): Long = file.length() / 2 * 1000 / SAMPLE_RATE

    fun sampleCount(file: File): Long = file.length() / 2

    /** Reads samples [from, from+count) as floats in -1..1 */
    fun readFloats(file: File, from: Long, count: Int): FloatArray {
        val total = sampleCount(file)
        val start = from.coerceIn(0, total)
        val n = minOf(count.toLong(), total - start).toInt().coerceAtLeast(0)
        val bytes = ByteArray(n * 2)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start * 2)
            raf.readFully(bytes)
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(n) { bb.get(it) / 32768f }
    }
}

/** Streaming mono resampler (linear interpolation with a one-pole anti-alias low-pass). */
private class Resampler(inRate: Int, private val outRate: Int = SAMPLE_RATE) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0
    private var prev = 0f
    private var lp = 0f
    private val alpha = if (inRate > outRate) (1.0 - exp(-2.0 * Math.PI * (outRate * 0.45) / inRate)).toFloat() else 1f

    fun process(input: FloatArray, n: Int, out: (Float) -> Unit) {
        if (n == 0) return
        for (i in 0 until n) { lp += alpha * (input[i] - lp); input[i] = lp }
        while (true) {
            val i = floor(pos).toInt()
            if (i + 1 > n - 1) break
            val frac = (pos - i).toFloat()
            val a = if (i < 0) prev else input[i]
            val b = input[i + 1]
            out(a + (b - a) * frac)
            pos += step
        }
        pos -= n
        prev = input[n - 1]
    }
}

object AudioDecoder {

    /**
     * Decodes any audio/video file supported by the platform into 16 kHz mono PCM16.
     * @return duration in ms
     */
    suspend fun decodeToPcm(context: Context, uri: Uri, out: File, onProgress: (Float) -> Unit): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var track = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) { track = i; break }
        }
        require(track >= 0) { "В файле нет аудиодорожки" }
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        var resampler = Resampler(sampleRate)
        var written = 0L
        val info = MediaCodec.BufferInfo()
        val outBuf = ByteBuffer.allocate(8192 * 2).order(ByteOrder.LITTLE_ENDIAN)
        var mono = FloatArray(16384)

        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
            fun emit(v: Float) {
                val s = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                outBuf.putShort(s)
                written++
                if (!outBuf.hasRemaining()) { os.write(outBuf.array(), 0, outBuf.position()); outBuf.clear() }
            }

            var inputDone = false
            var outputDone = false
            var lastProgress = -1
            try {
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val buf = codec.getInputBuffer(idx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val oidx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            resampler = Resampler(sampleRate)
                        }
                        oidx >= 0 -> {
                            if (info.size > 0) {
                                val buf = codec.getOutputBuffer(oidx)!!.order(ByteOrder.LITTLE_ENDIAN)
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val bytesPerSample = if (floatPcm) 4 else 2
                                val frames = info.size / bytesPerSample / channels
                                if (mono.size < frames) mono = FloatArray(frames)
                                for (fr in 0 until frames) {
                                    var acc = 0f
                                    for (ch in 0 until channels) {
                                        acc += if (floatPcm) buf.getFloat() else buf.getShort() / 32768f
                                    }
                                    mono[fr] = acc / channels
                                }
                                resampler.process(mono, frames, ::emit)
                                if (durationUs > 0) {
                                    val p = (info.presentationTimeUs * 100 / durationUs).toInt()
                                    if (p != lastProgress) { lastProgress = p; onProgress(p / 100f) }
                                }
                            }
                            codec.releaseOutputBuffer(oidx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
                extractor.release()
            }
            if (outBuf.position() > 0) os.write(outBuf.array(), 0, outBuf.position())
        }
        return written * 1000 / SAMPLE_RATE
    }
}

object AacEncoder {
    /** Encodes a 16 kHz mono PCM16 file into AAC-LC (.m4a), ~14 MB per hour. */
    suspend fun encode(pcm: File, out: File, bitrate: Int = 32_000, onProgress: (Float) -> Unit = {}) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val totalBytes = pcm.length().coerceAtLeast(1)
        var readBytes = 0L
        val info = MediaCodec.BufferInfo()
        val chunk = ByteArray(8192)

        try {
            pcm.inputStream().buffered(1 shl 16).use { input ->
                var inputDone = false
                var outputDone = false
                var lastP = -1
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val buf = codec.getInputBuffer(idx)!!
                            buf.clear()
                            val want = minOf(chunk.size, buf.remaining())
                            val n = input.read(chunk, 0, want)
                            val ptsUs = readBytes / 2 * 1_000_000L / SAMPLE_RATE
                            if (n <= 0) {
                                codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.put(chunk, 0, n)
                                codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
                                readBytes += n
                                val p = (readBytes * 100 / totalBytes).toInt()
                                if (p != lastP) { lastP = p; onProgress(p / 100f) }
                            }
                        }
                    }
                    val oidx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        oidx >= 0 -> {
                            val buf = codec.getOutputBuffer(oidx)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxerStarted) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, buf, info)
                            }
                            codec.releaseOutputBuffer(oidx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
