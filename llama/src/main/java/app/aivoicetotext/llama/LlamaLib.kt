package app.aivoicetotext.llama

/** Receives generated text as complete UTF-8 byte sequences. Return false to stop. */
fun interface PieceCallback {
    fun onPiece(bytes: ByteArray): Boolean
}

/** Thin JNI binding to llama.cpp. All handles are opaque native pointers; 0 = failure. */
object LlamaLib {
    init {
        System.loadLibrary("llama_jni")
    }

    external fun load(path: String, nCtx: Int, threads: Int): Long
    external fun free(handle: Long)
    external fun requestAbort(handle: Long)
    external fun contextSize(handle: Long): Int
    external fun countTokens(handle: Long, text: ByteArray): Int
    external fun applyTemplate(handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>): ByteArray?

    /** @return generated token count, -1 error, -2 aborted, -3 prompt too long */
    external fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, callback: PieceCallback): Int
    external fun systemInfo(): String
}
