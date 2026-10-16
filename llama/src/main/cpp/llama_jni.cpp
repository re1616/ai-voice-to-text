// JNI bridge for llama.cpp (local text generation for summaries / clean-up / action items)
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "llama_jni", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

namespace {

struct LCtx {
    llama_model       * model = nullptr;
    llama_context     * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    std::atomic<bool>   abort{false};
};

std::string from_bytes(JNIEnv * env, jbyteArray arr) {
    if (arr == nullptr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::string s((size_t) n, '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

jbyteArray to_bytes(JNIEnv * env, const char * data, size_t n) {
    jbyteArray arr = env->NewByteArray((jsize) n);
    if (n > 0) env->SetByteArrayRegion(arr, 0, (jsize) n, reinterpret_cast<const jbyte *>(data));
    return arr;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, add_special, true);
    if (n <= 0) return {};
    std::vector<llama_token> toks(n);
    if (llama_tokenize(vocab, text.data(), (int32_t) text.size(), toks.data(), n, add_special, true) < 0) return {};
    return toks;
}

// Length of the longest prefix of s that does not end inside an incomplete UTF-8 sequence.
size_t complete_utf8_prefix(const std::string & s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) != 0x80) {             // lead byte or ASCII
            int need = 1;
            if      ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            return (n - (i - 1) >= (size_t) need) ? n : i - 1;
        }
        --i; ++back;
    }
    return n;
}

void log_cb(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) LOGI("%s", text);
}

} // namespace

#define JNI_FN(name) JNIEXPORT JNICALL Java_app_aivoicetotext_llama_LlamaLib_##name

extern "C" {

jlong JNI_FN(load)(JNIEnv * env, jobject, jstring jpath, jint nCtx, jint threads) {
    llama_log_set(log_cb, nullptr);
    llama_backend_init();

    const char * cpath = env->GetStringUTFChars(jpath, nullptr);
    std::string path = cpath ? cpath : "";
    env->ReleaseStringUTFChars(jpath, cpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU only; the default load mode is mmap
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) {
        LOGI("failed to load model %s", path.c_str());
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) std::max(512, (int) nCtx);
    cp.n_batch         = 512;
    cp.n_ubatch        = 512;
    cp.n_threads       = threads > 0 ? threads : 4;
    cp.n_threads_batch = cp.n_threads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        LOGI("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto * l = new LCtx();
    l->model = model;
    l->ctx   = ctx;
    l->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(l);
}

void JNI_FN(free)(JNIEnv *, jobject, jlong h) {
    auto * l = reinterpret_cast<LCtx *>(h);
    if (l == nullptr) return;
    llama_free(l->ctx);
    llama_model_free(l->model);
    delete l;
}

void JNI_FN(requestAbort)(JNIEnv *, jobject, jlong h) {
    auto * l = reinterpret_cast<LCtx *>(h);
    if (l) l->abort.store(true);
}

jint JNI_FN(contextSize)(JNIEnv *, jobject, jlong h) {
    auto * l = reinterpret_cast<LCtx *>(h);
    return l ? (jint) llama_n_ctx(l->ctx) : 0;
}

jint JNI_FN(countTokens)(JNIEnv * env, jobject, jlong h, jbyteArray jtext) {
    auto * l = reinterpret_cast<LCtx *>(h);
    if (l == nullptr) return -1;
    return (jint) tokenize(l->vocab, from_bytes(env, jtext), false).size();
}

// Formats a chat with the model's built-in template (falls back to ChatML).
jbyteArray JNI_FN(applyTemplate)(JNIEnv * env, jobject, jlong h, jobjectArray jroles, jobjectArray jcontents) {
    auto * l = reinterpret_cast<LCtx *>(h);
    if (l == nullptr) return nullptr;
    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), contents(n);
    std::vector<llama_chat_message> msgs(n);
    size_t total = 0;
    for (jsize i = 0; i < n; ++i) {
        auto jr = (jbyteArray) env->GetObjectArrayElement(jroles, i);
        auto jc = (jbyteArray) env->GetObjectArrayElement(jcontents, i);
        roles[i]    = from_bytes(env, jr);
        contents[i] = from_bytes(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        total += roles[i].size() + contents[i].size();
    }
    for (jsize i = 0; i < n; ++i) msgs[i] = { roles[i].c_str(), contents[i].c_str() };

    const char * tmpl = llama_model_chat_template(l->model, nullptr);
    std::vector<char> buf(total * 2 + 1024);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (len < 0 && tmpl != nullptr) {
        tmpl = "chatml";
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (len < 0) return nullptr;
    if ((size_t) len > buf.size()) {
        buf.resize(len + 1);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
        if (len < 0) return nullptr;
    }
    return to_bytes(env, buf.data(), (size_t) len);
}

// Generates a completion for an already formatted prompt.
// callback: object with method `boolean onPiece(byte[])`; return false to stop.
// Returns number of generated tokens, -1 on error, -2 if aborted, -3 if the prompt does not fit.
jint JNI_FN(generate)(JNIEnv * env, jobject, jlong h, jbyteArray jprompt, jint maxTokens,
                      jfloat temperature, jobject callback) {
    auto * l = reinterpret_cast<LCtx *>(h);
    if (l == nullptr) return -1;
    l->abort.store(false);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onPiece = env->GetMethodID(cbClass, "onPiece", "([B)Z");
    if (onPiece == nullptr) return -1;

    const std::string prompt = from_bytes(env, jprompt);
    // the chat template already contains BOS where needed; parse special tokens
    std::vector<llama_token> toks = tokenize(l->vocab, prompt, true);
    if (toks.empty()) return -1;

    const int n_ctx = (int) llama_n_ctx(l->ctx);
    if ((int) toks.size() + 16 > n_ctx) return -3;
    const int budget = std::min((int) maxTokens, n_ctx - (int) toks.size() - 1);

    llama_memory_clear(llama_get_memory(l->ctx), true);

    llama_batch_ext * batch = llama_batch_ext_init(l->ctx);
    const int n_batch = (int) llama_n_batch(l->ctx);

    // prompt processing in chunks of n_batch
    int pos = 0;
    for (size_t start = 0; start < toks.size(); start += n_batch) {
        if (l->abort.load()) { llama_batch_ext_free(batch); return -2; }
        const size_t end = std::min(toks.size(), start + (size_t) n_batch);
        llama_batch_ext_clear(batch);
        for (size_t i = start; i < end; ++i) {
            const int32_t idx = llama_batch_ext_add_token(batch, 0, toks[i]);
            const llama_pos p = pos++;
            llama_batch_ext_set_pos(batch, idx, &p);
            if (i == toks.size() - 1) llama_batch_ext_set_output_logits(batch, idx, true);
        }
        if (llama_process(l->ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            llama_batch_ext_free(batch);
            return -1;
        }
    }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(l->vocab), 64, 1.1f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string pending;
    int generated = 0;
    int rc = 0;
    char piece[256];
    while (generated < budget) {
        if (l->abort.load()) { rc = -2; break; }
        const llama_token id = llama_sampler_sample(smpl, l->ctx, -1);
        if (llama_vocab_is_eog(l->vocab, id)) break;

        const int n = llama_token_to_piece(l->vocab, id, piece, sizeof(piece), 0, false);
        if (n > 0) pending.append(piece, n);
        ++generated;

        const size_t ready = complete_utf8_prefix(pending);
        if (ready > 0) {
            jbyteArray arr = to_bytes(env, pending.data(), ready);
            const jboolean cont = env->CallBooleanMethod(callback, onPiece, arr);
            env->DeleteLocalRef(arr);
            pending.erase(0, ready);
            if (env->ExceptionCheck()) { env->ExceptionClear(); rc = -1; break; }
            if (!cont) break;
        }

        llama_batch_ext_clear(batch);
        const int32_t idx = llama_batch_ext_add_token(batch, 0, id);
        const llama_pos p = pos++;
        llama_batch_ext_set_pos(batch, idx, &p);
        llama_batch_ext_set_output_logits(batch, idx, true);
        if (llama_process(l->ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) { rc = -1; break; }
    }

    if (rc == 0 && !pending.empty()) {
        jbyteArray arr = to_bytes(env, pending.data(), pending.size());
        env->CallBooleanMethod(callback, onPiece, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    llama_sampler_free(smpl);
    llama_batch_ext_free(batch);
    return rc == 0 ? generated : rc;
}

jstring JNI_FN(systemInfo)(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
