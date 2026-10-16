package app.aivoicetotext.llama;

public class LlamaLib {
    public static native long load(String path, int nCtx, int threads);
    public static native void free(long h);
    public static native int contextSize(long h);
    public static native int countTokens(long h, byte[] text);
    public static native byte[] applyTemplate(long h, byte[][] roles, byte[][] contents);
    public static native int generate(long h, byte[] prompt, int maxTokens, float temperature, PieceCallback cb);
}
