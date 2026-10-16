import app.aivoicetotext.llama.LlamaLib;
import app.aivoicetotext.whisper.WhisperLib;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** java NativeTest <whisper.so> <llama.so> <whisper-model> <vad-model> <wav16k> <gguf> */
public class NativeTest {
    public static void main(String[] a) throws Exception {
        System.load(new File(a[0]).getAbsolutePath());
        System.load(new File(a[1]).getAbsolutePath());
        byte[] wav = Files.readAllBytes(Paths.get(a[4]));
        ShortBuffer sb = ByteBuffer.wrap(wav, 44, wav.length - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        float[] pcm = new float[sb.remaining()];
        for (int i = 0; i < pcm.length; i++) pcm[i] = sb.get(i) / 32768f;
        System.out.println("samples: " + pcm.length);

        long vad = WhisperLib.vadInit(a[3], 2);
        if (vad == 0) throw new RuntimeException("VAD init failed");
        float[] segs = WhisperLib.vadSegments(vad, pcm, 0.5f, 250, 500, 25f, 200);
        System.out.println("VAD segments: " + (segs.length / 2));
        for (int i = 0; i < segs.length; i += 2) System.out.printf("  %.2f - %.2f%n", segs[i], segs[i + 1]);
        WhisperLib.vadFree(vad);
        if (segs.length == 0) throw new RuntimeException("no speech found");

        long w = WhisperLib.initContext(a[2], false);
        if (w == 0) throw new RuntimeException("whisper init failed");
        int rc = WhisperLib.transcribe(w, pcm, "auto", 4, "Kennedy", false);
        System.out.println("transcribe rc=" + rc + " lang=" + WhisperLib.detectedLanguage(w));
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < WhisperLib.segmentCount(w); i++) {
            String t = new String(WhisperLib.segmentText(w, i), StandardCharsets.UTF_8);
            all.append(t);
            System.out.printf("  [%d-%d cs] %s%n", WhisperLib.segmentT0(w, i), WhisperLib.segmentT1(w, i), t);
        }
        WhisperLib.freeContext(w);
        if (!all.toString().toLowerCase().contains("country")) throw new RuntimeException("unexpected transcript");

        long l = LlamaLib.load(a[5], 2048, 4);
        if (l == 0) throw new RuntimeException("llama load failed");
        System.out.println("n_ctx=" + LlamaLib.contextSize(l));
        byte[] prompt = LlamaLib.applyTemplate(l,
            new byte[][]{"system".getBytes(), "user".getBytes()},
            new byte[][]{"Сделай краткую выжимку списком.".getBytes(StandardCharsets.UTF_8),
                ("Текст расшифровки:\n\n" + all + " /no_think").getBytes(StandardCharsets.UTF_8)});
        System.out.println("prompt tokens=" + LlamaLib.countTokens(l, prompt));
        System.out.println(new String(prompt, StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n = LlamaLib.generate(l, prompt, 120, 0.3f, b -> { out.write(b, 0, b.length); return true; });
        System.out.println("generated " + n + " tokens:\n" + out.toString("UTF-8"));
        LlamaLib.free(l);
        if (n <= 0) throw new RuntimeException("generation failed");
        System.out.println("NATIVE TEST OK");
    }
}
