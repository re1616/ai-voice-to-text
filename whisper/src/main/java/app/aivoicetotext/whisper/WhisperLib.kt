package app.aivoicetotext.whisper

/** Thin JNI binding to whisper.cpp. All handles are opaque native pointers; 0 = failure. */
object WhisperLib {
    init {
        System.loadLibrary("whisper_jni")
    }

    external fun initContext(path: String, useGpu: Boolean): Long
    external fun freeContext(handle: Long)
    external fun requestAbort(handle: Long)
    external fun progress(handle: Long): Int

    /** @return 0 ok, -1 error, -2 aborted */
    external fun transcribe(
        handle: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
        prompt: String?,
        translate: Boolean,
    ): Int

    external fun segmentCount(handle: Long): Int
    /** centiseconds relative to the passed samples */
    external fun segmentT0(handle: Long, index: Int): Long
    external fun segmentT1(handle: Long, index: Int): Long
    external fun segmentText(handle: Long, index: Int): ByteArray
    external fun detectedLanguage(handle: Long): String
    external fun systemInfo(): String

    external fun vadInit(path: String, threads: Int): Long
    external fun vadFree(handle: Long)
    /** [start0, end0, start1, end1, ...] in seconds */
    external fun vadSegments(
        handle: Long,
        samples: FloatArray,
        threshold: Float,
        minSpeechMs: Int,
        minSilenceMs: Int,
        maxSpeechS: Float,
        padMs: Int,
    ): FloatArray?
}
