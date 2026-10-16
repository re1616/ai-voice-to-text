package app.aivoicetotext.whisper;

/** Desktop stand-in for the Kotlin object: same JNI symbol names, used by NativeTest. */
public class WhisperLib {
    public static native long initContext(String path, boolean useGpu);
    public static native void freeContext(long h);
    public static native int transcribe(long h, float[] samples, String language, int threads, String prompt, boolean translate);
    public static native int segmentCount(long h);
    public static native long segmentT0(long h, int i);
    public static native long segmentT1(long h, int i);
    public static native byte[] segmentText(long h, int i);
    public static native String detectedLanguage(long h);
    public static native String systemInfo();
    public static native long vadInit(String path, int threads);
    public static native void vadFree(long h);
    public static native float[] vadSegments(long h, float[] samples, float threshold, int minSpeechMs, int minSilenceMs, float maxSpeechS, int padMs);
}
