#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <chrono>
#include <android/log.h>
#include <mutex>
#include "llama.h"

#define TAG "LlamaCppNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global mutex to ensure thread-safety across native calls
static std::mutex g_llm_mutex;

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    llama_backend_init();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM* vm, void* reserved) {
    llama_backend_free();
}

struct LlamaState {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * smpl = nullptr;

    ~LlamaState() {
        if (smpl) llama_sampler_free(smpl);
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_mike_lets_textEntry_LlamaCppClient_nativeInit(JNIEnv* env, jobject, jstring modelPath) {
    std::lock_guard<std::mutex> lock(g_llm_mutex);

    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    llama_model_params model_params = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(path, model_params);

    if (!model) {
        LOGE("Failed to load model: %s", path);
        env->ReleaseStringUTFChars(modelPath, path);
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 512;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        env->ReleaseStringUTFChars(modelPath, path);
        return 0;
    }

    auto* state = new LlamaState();
    state->model = model;
    state->ctx = ctx;
    state->smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());

    // Sampling configuration
    llama_sampler_chain_add(state->smpl, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(state->smpl, llama_sampler_init_dist(42));

    env->ReleaseStringUTFChars(modelPath, path);
    LOGD("Llama initialized successfully");
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mike_lets_textEntry_LlamaCppClient_nativeGetCompletion(JNIEnv* env, jobject, jlong ptr, jstring prompt) {
    // Lock the mutex to prevent concurrent inference
    std::lock_guard<std::mutex> lock(g_llm_mutex);

    auto* state = reinterpret_cast<LlamaState*>(ptr);
    if (!state || !state->ctx) return env->NewStringUTF("");

    auto t_start = std::chrono::high_resolution_clock::now();

    const char* p = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(p);
    env->ReleaseStringUTFChars(prompt, p);

    const struct llama_vocab * vocab = llama_model_get_vocab(state->model);

    // Properly clear KV cache before each completion
    llama_memory_clear(llama_get_memory(state->ctx), true);

    std::vector<llama_token> tokens;
    tokens.resize(prompt_str.length() + 1);
    int n_tokens = (int)llama_tokenize(vocab, prompt_str.c_str(), (int)prompt_str.length(), tokens.data(), (int)tokens.size(), true, true);
    tokens.resize(n_tokens);

    if (n_tokens <= 0) return env->NewStringUTF("");

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(state->ctx, batch) != 0) {
        LOGE("LLM Decode failed");
        return env->NewStringUTF("Error: decode failed");
    }

    auto t_prompt = std::chrono::high_resolution_clock::now();

    std::string response;
    int n_predict = 40;

    for (int i = 0; i < n_predict; i++) {
        llama_token curr_token = llama_sampler_sample(state->smpl, state->ctx, -1);

        if (llama_vocab_is_eog(vocab, curr_token)) break;

        char buf[128];
        int n = llama_token_to_piece(vocab, curr_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            response.append(buf, n);

            // Check for stop sequences in response
            if (response.find("<end_of_turn>") != std::string::npos ||
                response.find("<eos>") != std::string::npos ||
                response.find("model") != std::string::npos) {
                break;
            }
        }

        llama_batch batch_gen = llama_batch_get_one(&curr_token, 1);
        if (llama_decode(state->ctx, batch_gen) != 0) {
            LOGE("LLM Generation decode failed");
            break;
        }
    }

    auto t_end = std::chrono::high_resolution_clock::now();
    double ms_total = std::chrono::duration<double, std::milli>(t_end - t_start).count();

    LOGD("LLM Inference completed in %.2fms", ms_total);

    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_mike_lets_textEntry_LlamaCppClient_nativeRelease(JNIEnv*, jobject, jlong ptr) {
    std::lock_guard<std::mutex> lock(g_llm_mutex);
    auto* state = reinterpret_cast<LlamaState*>(ptr);
    delete state;
    LOGD("Llama released");
}
