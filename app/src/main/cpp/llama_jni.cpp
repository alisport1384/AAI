// Hushyar on-device core: JNI wrapper around llama.cpp (GGUF models).
//
// This file is deliberately small: it owns one loaded model per handle and
// exposes load / generate / cancel / free to Kotlin. All policy (which model,
// when to download, prompt text) lives in Kotlin; here we only run inference.
//
// Pinned upstream: llama.cpp v1.0.0 (commit b1b770d, 2026-11-13).
// Rebuild with: scripts/build-llama-android.sh

#include <jni.h>

#include <android/log.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "HushyarLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 0;
    std::atomic<bool> cancel{false};
    std::mutex mu;
};

std::string g_last_error;

void set_error(const std::string & msg) {
    g_last_error = msg;
    LOGE("%s", msg.c_str());
}

void free_engine(Engine * e) {
    if (e == nullptr) return;
    if (e->ctx != nullptr) llama_free(e->ctx);
    if (e->model != nullptr) llama_model_free(e->model);
    delete e;
}

// llama_token_to_piece may split a multi-byte UTF-8 code point across tokens.
// We append raw bytes and let Kotlin decode the finished buffer, so a split
// never corrupts the answer.
void append_piece(const llama_vocab * vocab, llama_token token, std::vector<char> & out) {
    char buf[256];
    const int32_t n = llama_token_to_piece(vocab, token, buf, (int32_t) sizeof(buf), 0, /*special=*/false);
    if (n > 0) out.insert(out.end(), buf, buf + n);
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_ir_aai_hushyar_core_LlamaNative_nativeBackendInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_ir_aai_hushyar_core_LlamaNative_nativeLastError(JNIEnv * env, jclass) {
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jlong JNICALL
Java_ir_aai_hushyar_core_LlamaNative_nativeLoad(JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) {
        set_error("model path is not readable");
        return 0;
    }

    auto * e = new Engine();
    e->n_ctx = n_ctx > 0 ? n_ctx : 2048;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // CPU-only: no GPU backend is shipped.
    // mmap keeps RSS low on phones: weights stay pageable, the model is never
    // copied into a second buffer in RAM.
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    e->model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);

    if (e->model == nullptr) {
        set_error("llama_model_load_from_file failed (file missing, truncated or not a GGUF file)");
        delete e;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) e->n_ctx;
    cparams.n_batch = 512;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    e->ctx = llama_init_from_model(e->model, cparams);
    if (e->ctx == nullptr) {
        set_error("llama_init_from_model failed (context too large for this device)");
        free_engine(e);
        return 0;
    }

    e->vocab = llama_model_get_vocab(e->model);
    g_last_error.clear();
    LOGI("loaded %s (n_ctx=%d, threads=%d)", path ? path : "?", e->n_ctx, (int) cparams.n_threads);
    return (jlong) (intptr_t) e;
}

JNIEXPORT void JNICALL
Java_ir_aai_hushyar_core_LlamaNative_nativeCancel(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<Engine *>((intptr_t) handle);
    if (e != nullptr) e->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_ir_aai_hushyar_core_LlamaNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<Engine *>((intptr_t) handle);
    if (e == nullptr) return;
    std::lock_guard<std::mutex> lock(e->mu);
    free_engine(e);
}

JNIEXPORT jbyteArray JNICALL
Java_ir_aai_hushyar_core_LlamaNative_nativeGenerate(JNIEnv * env, jclass, jlong handle,
                                                    jbyteArray jprompt, jint max_tokens,
                                                    jfloat temperature, jint seed) {
    auto * e = reinterpret_cast<Engine *>((intptr_t) handle);
    if (e == nullptr || e->ctx == nullptr || e->vocab == nullptr) {
        set_error("engine is not loaded");
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(e->mu);
    e->cancel.store(false);

    const jsize prompt_len = env->GetArrayLength(jprompt);
    std::vector<char> prompt((size_t) prompt_len);
    env->GetByteArrayRegion(jprompt, 0, prompt_len, reinterpret_cast<jbyte *>(prompt.data()));

    // The model is the only authority on how Persian text is tokenised; we pass
    // the raw UTF-8 through and never pre-split it.
    int32_t n_prompt = llama_tokenize(e->vocab, prompt.data(), (int32_t) prompt.size(),
                                      nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (n_prompt < 0) n_prompt = -n_prompt;  // required buffer length
    if (n_prompt <= 0) {
        set_error("prompt produced no tokens");
        return nullptr;
    }

    std::vector<llama_token> tokens((size_t) n_prompt);
    n_prompt = llama_tokenize(e->vocab, prompt.data(), (int32_t) prompt.size(),
                              tokens.data(), n_prompt, /*add_special=*/true, /*parse_special=*/true);
    if (n_prompt <= 0) {
        set_error("prompt tokenisation failed");
        return nullptr;
    }
    tokens.resize((size_t) n_prompt);

    const int max_new = max_tokens > 0 ? max_tokens : 128;
    const int room = e->n_ctx - max_new;
    if (room <= 0) {
        set_error("context window too small for the requested answer length");
        return nullptr;
    }
    if ((int) tokens.size() > room) {
        // Keep the tail: instructions and the question are at the end.
        tokens.erase(tokens.begin(), tokens.end() - room);
    }

    llama_memory_clear(llama_get_memory(e->ctx), true);

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) (seed > 0 ? seed : 0xBEEF)));
    }

    std::vector<char> out;
    out.reserve(2048);

    int n_past = 0;
    const int n_batch = 512;
    while (n_past < (int) tokens.size()) {
        const int n_eval = std::min(n_batch, (int) tokens.size() - n_past);
        llama_batch batch = llama_batch_get_one(tokens.data() + n_past, n_eval);
        const int32_t rc = llama_decode(e->ctx, batch);
        if (rc != 0) {
            set_error("llama_decode failed while reading the prompt (rc=" + std::to_string(rc) + ")");
            llama_sampler_free(smpl);
            return nullptr;
        }
        n_past += n_eval;
        if (e->cancel.load()) break;
    }

    int produced = 0;
    while (produced < max_new && !e->cancel.load()) {
        const llama_token id = llama_sampler_sample(smpl, e->ctx, -1);
        llama_sampler_accept(smpl, id);
        if (llama_vocab_is_eog(e->vocab, id)) break;
        append_piece(e->vocab, id, out);
        ++produced;
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&id), 1);
        const int32_t rc = llama_decode(e->ctx, batch);
        if (rc != 0) break;  // answer-so-far is still usable
    }

    llama_sampler_free(smpl);

    if (out.empty()) {
        set_error(e->cancel.load() ? "generation cancelled" : "model produced no tokens");
        return nullptr;
    }

    jbyteArray result = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(result, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

}  // extern "C"
