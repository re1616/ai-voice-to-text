// JNI bridge for whisper.cpp (speech-to-text + built-in Silero VAD)
#include <jni.h>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "whisper_jni", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

namespace {

struct WCtx {
    whisper_context * ctx = nullptr;
    std::atomic<bool> abort{false};
    std::atomic<int>  progress{0};
};

std::string jstr(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

jbyteArray bytes(JNIEnv * env, const char * text) {
    const jsize n = text ? (jsize) strlen(text) : 0;
    jbyteArray arr = env->NewByteArray(n);
    if (n > 0) env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte *>(text));
    return arr;
}

void log_cb(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) LOGI("%s", text);
}

} // namespace

#define JNI_FN(name) JNIEXPORT JNICALL Java_app_aivoicetotext_whisper_WhisperLib_##name

extern "C" {

jlong JNI_FN(initContext)(JNIEnv * env, jobject, jstring jpath, jboolean useGpu) {
    whisper_log_set(log_cb, nullptr);
    const std::string path = jstr(env, jpath);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = useGpu;
    cp.flash_attn = false;
    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cp);
    if (ctx == nullptr) {
        LOGI("failed to load model %s", path.c_str());
        return 0;
    }
    auto * w = new WCtx();
    w->ctx = ctx;
    return reinterpret_cast<jlong>(w);
}

void JNI_FN(freeContext)(JNIEnv *, jobject, jlong h) {
    auto * w = reinterpret_cast<WCtx *>(h);
    if (w == nullptr) return;
    whisper_free(w->ctx);
    delete w;
}

void JNI_FN(requestAbort)(JNIEnv *, jobject, jlong h) {
    auto * w = reinterpret_cast<WCtx *>(h);
    if (w) w->abort.store(true);
}

jint JNI_FN(progress)(JNIEnv *, jobject, jlong h) {
    auto * w = reinterpret_cast<WCtx *>(h);
    return w ? w->progress.load() : 0;
}

// Returns 0 on success, -1 on failure, -2 if aborted.
jint JNI_FN(transcribe)(JNIEnv * env, jobject, jlong h, jfloatArray jsamples, jstring jlang,
                        jint threads, jstring jprompt, jboolean translate) {
    auto * w = reinterpret_cast<WCtx *>(h);
    if (w == nullptr || w->ctx == nullptr) return -1;
    w->abort.store(false);
    w->progress.store(0);

    const std::string lang   = jstr(env, jlang);
    const std::string prompt = jstr(env, jprompt);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads        = threads > 0 ? threads : 4;
    p.translate        = translate;
    p.language         = lang.empty() ? "auto" : lang.c_str();
    p.detect_language  = false;
    p.no_context       = true;
    p.no_timestamps    = false;
    p.single_segment   = false;
    p.print_special    = false;
    p.print_progress   = false;
    p.print_realtime   = false;
    p.print_timestamps = false;
    p.suppress_blank   = true;
    p.suppress_nst     = true;
    p.token_timestamps = false;
    p.initial_prompt   = prompt.empty() ? nullptr : prompt.c_str();
    p.greedy.best_of   = 2;
    p.abort_callback   = [](void * ud) -> bool {
        return static_cast<WCtx *>(ud)->abort.load();
    };
    p.abort_callback_user_data = w;
    p.progress_callback = [](whisper_context *, whisper_state *, int pr, void * ud) {
        static_cast<WCtx *>(ud)->progress.store(pr);
    };
    p.progress_callback_user_data = w;

    const jsize n = env->GetArrayLength(jsamples);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(jsamples, 0, n, samples.data());

    const int rc = whisper_full(w->ctx, p, samples.data(), (int) samples.size());
    if (w->abort.load()) return -2;
    return rc == 0 ? 0 : -1;
}

jint JNI_FN(segmentCount)(JNIEnv *, jobject, jlong h) {
    auto * w = reinterpret_cast<WCtx *>(h);
    return w ? whisper_full_n_segments(w->ctx) : 0;
}

// centiseconds (1/100 s), relative to the start of the samples passed to transcribe()
jlong JNI_FN(segmentT0)(JNIEnv *, jobject, jlong h, jint i) {
    return whisper_full_get_segment_t0(reinterpret_cast<WCtx *>(h)->ctx, i);
}

jlong JNI_FN(segmentT1)(JNIEnv *, jobject, jlong h, jint i) {
    return whisper_full_get_segment_t1(reinterpret_cast<WCtx *>(h)->ctx, i);
}

jbyteArray JNI_FN(segmentText)(JNIEnv * env, jobject, jlong h, jint i) {
    return bytes(env, whisper_full_get_segment_text(reinterpret_cast<WCtx *>(h)->ctx, i));
}

jstring JNI_FN(detectedLanguage)(JNIEnv * env, jobject, jlong h) {
    auto * w = reinterpret_cast<WCtx *>(h);
    const int id = w ? whisper_full_lang_id(w->ctx) : -1;
    const char * s = id >= 0 ? whisper_lang_str(id) : "";
    return env->NewStringUTF(s ? s : "");
}

jstring JNI_FN(systemInfo)(JNIEnv * env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

// ---------------------------------------------------------------- VAD

jlong JNI_FN(vadInit)(JNIEnv * env, jobject, jstring jpath, jint threads) {
    const std::string path = jstr(env, jpath);
    whisper_vad_context_params vp = whisper_vad_default_context_params();
    vp.n_threads = threads > 0 ? threads : 2;
    vp.use_gpu = false;
    whisper_vad_context * v = whisper_vad_init_from_file_with_params(path.c_str(), vp);
    return reinterpret_cast<jlong>(v);
}

void JNI_FN(vadFree)(JNIEnv *, jobject, jlong h) {
    auto * v = reinterpret_cast<whisper_vad_context *>(h);
    if (v) whisper_vad_free(v);
}

// Returns [start0, end0, start1, end1, ...] in seconds, or null on error.
jfloatArray JNI_FN(vadSegments)(JNIEnv * env, jobject, jlong h, jfloatArray jsamples,
                                jfloat threshold, jint minSpeechMs, jint minSilenceMs,
                                jfloat maxSpeechS, jint padMs) {
    auto * v = reinterpret_cast<whisper_vad_context *>(h);
    if (v == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(jsamples);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(jsamples, 0, n, samples.data());

    whisper_vad_params p = whisper_vad_default_params();
    p.threshold               = threshold;
    p.min_speech_duration_ms  = minSpeechMs;
    p.min_silence_duration_ms = minSilenceMs;
    p.max_speech_duration_s   = maxSpeechS;
    p.speech_pad_ms           = padMs;

    whisper_vad_segments * segs = whisper_vad_segments_from_samples(v, p, samples.data(), (int) samples.size());
    if (segs == nullptr) return nullptr;

    const int ns = whisper_vad_segments_n_segments(segs);
    std::vector<float> out(ns * 2);
    for (int i = 0; i < ns; ++i) {
        out[2 * i]     = whisper_vad_segments_get_segment_t0(segs, i) / 100.0f;
        out[2 * i + 1] = whisper_vad_segments_get_segment_t1(segs, i) / 100.0f;
    }
    whisper_vad_free_segments(segs);

    jfloatArray arr = env->NewFloatArray((jsize) out.size());
    if (!out.empty()) env->SetFloatArrayRegion(arr, 0, (jsize) out.size(), out.data());
    return arr;
}

} // extern "C"
