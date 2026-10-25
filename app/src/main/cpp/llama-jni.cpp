#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <atomic>
#include <algorithm>
#include <fstream>
#include <sstream>
#include <android/log.h>
#include "llama.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void * g_llama_lib = nullptr;
static std::string g_loaded_backend_name = "none";
static struct llama_model * g_model = nullptr;
static struct llama_context * g_context = nullptr;
static std::atomic<bool> g_stop_requested(false);

// Function pointers for dynamic backend binding
static void (*fn_llama_backend_init)(void) = nullptr;
static void (*fn_llama_backend_free)(void) = nullptr;
static struct llama_model_params (*fn_llama_model_default_params)(void) = nullptr;
static struct llama_model * (*fn_llama_model_load_from_file)(const char *, struct llama_model_params) = nullptr;
static void (*fn_llama_model_free)(struct llama_model *) = nullptr;
static struct llama_context_params (*fn_llama_context_default_params)(void) = nullptr;
static struct llama_context * (*fn_llama_init_from_model)(struct llama_model *, struct llama_context_params) = nullptr;
static void (*fn_llama_free)(struct llama_context *) = nullptr;
static const struct llama_vocab * (*fn_llama_model_get_vocab)(const struct llama_model *) = nullptr;
static llama_memory_t (*fn_llama_get_memory)(const struct llama_context *) = nullptr;
static void (*fn_llama_memory_clear)(llama_memory_t, bool) = nullptr;
static int32_t (*fn_llama_tokenize)(const struct llama_vocab *, const char *, int32_t, llama_token *, int32_t, bool, bool) = nullptr;
static uint32_t (*fn_llama_n_ctx)(const struct llama_context *) = nullptr;
static struct llama_sampler_chain_params (*fn_llama_sampler_chain_default_params)(void) = nullptr;
static struct llama_sampler * (*fn_llama_sampler_chain_init)(struct llama_sampler_chain_params) = nullptr;
static void (*fn_llama_sampler_chain_add)(struct llama_sampler *, struct llama_sampler *) = nullptr;
static struct llama_sampler * (*fn_llama_sampler_init_greedy)(void) = nullptr;
static struct llama_sampler * (*fn_llama_sampler_init_temp)(float) = nullptr;
static struct llama_sampler * (*fn_llama_sampler_init_top_p)(float, size_t) = nullptr;
static struct llama_sampler * (*fn_llama_sampler_init_dist)(uint32_t) = nullptr;
static void (*fn_llama_sampler_free)(struct llama_sampler *) = nullptr;
static llama_token (*fn_llama_sampler_sample)(struct llama_sampler *, struct llama_context *, int32_t) = nullptr;
static void (*fn_llama_sampler_accept)(struct llama_sampler *, llama_token) = nullptr;
static bool (*fn_llama_vocab_is_eog)(const struct llama_vocab *, llama_token) = nullptr;
static llama_token (*fn_llama_vocab_eos)(const struct llama_vocab *) = nullptr;
static int32_t (*fn_llama_token_to_piece)(const struct llama_vocab *, llama_token, char *, int32_t, int32_t, bool) = nullptr;
static struct llama_batch_ext * (*fn_llama_batch_ext_init)(struct llama_context *) = nullptr;
static void (*fn_llama_batch_ext_free)(struct llama_batch_ext *) = nullptr;
static void (*fn_llama_batch_ext_clear)(struct llama_batch_ext *) = nullptr;
static int32_t (*fn_llama_batch_ext_add_token)(struct llama_batch_ext *, llama_seq_id, llama_token) = nullptr;
static bool (*fn_llama_batch_ext_set_pos)(struct llama_batch_ext *, int32_t, const llama_pos *) = nullptr;
static bool (*fn_llama_batch_ext_set_output_logits)(struct llama_batch_ext *, int32_t, bool) = nullptr;
static int32_t (*fn_llama_process)(struct llama_context *, enum llama_process_type, struct llama_batch_ext *) = nullptr;

static bool load_symbols(void * handle) {
    #define BIND(name) \
        fn_##name = (decltype(fn_##name))dlsym(handle, #name); \
        if (!fn_##name) { LOGE("Symbol not found: %s", #name); return false; }

    BIND(llama_backend_init);
    BIND(llama_backend_free);
    BIND(llama_model_default_params);
    BIND(llama_model_load_from_file);
    BIND(llama_model_free);
    BIND(llama_context_default_params);
    BIND(llama_init_from_model);
    BIND(llama_free);
    BIND(llama_model_get_vocab);
    BIND(llama_get_memory);
    BIND(llama_memory_clear);
    BIND(llama_tokenize);
    BIND(llama_n_ctx);
    BIND(llama_sampler_chain_default_params);
    BIND(llama_sampler_chain_init);
    BIND(llama_sampler_chain_add);
    BIND(llama_sampler_init_greedy);
    BIND(llama_sampler_init_temp);
    BIND(llama_sampler_init_top_p);
    BIND(llama_sampler_init_dist);
    BIND(llama_sampler_free);
    BIND(llama_sampler_sample);
    BIND(llama_sampler_accept);
    BIND(llama_vocab_is_eog);
    BIND(llama_vocab_eos);
    BIND(llama_token_to_piece);
    BIND(llama_batch_ext_init);
    BIND(llama_batch_ext_free);
    BIND(llama_batch_ext_clear);
    BIND(llama_batch_ext_add_token);
    BIND(llama_batch_ext_set_pos);
    BIND(llama_batch_ext_set_output_logits);
    BIND(llama_process);

    #undef BIND
    return true;
}

static bool init_best_backend() {
    if (g_llama_lib) return true;

    std::string cpuinfo;
    std::ifstream f("/proc/cpuinfo");
    if (f.is_open()) {
        std::stringstream ss;
        ss << f.rdbuf();
        cpuinfo = ss.str();
    }

    std::vector<std::pair<std::string, std::string>> candidates;
    if (cpuinfo.find("i8mm") != std::string::npos) {
        candidates.push_back({"librnllama_v8_2_dotprod_i8mm.so", "ARMv8.2 + DotProd + i8mm (Ultra Rápido)"});
    }
    if (cpuinfo.find("asimddp") != std::string::npos || cpuinfo.find("dotprod") != std::string::npos) {
        candidates.push_back({"librnllama_v8_2_dotprod.so", "ARMv8.2 + DotProd (Hardware Acelerado)"});
    }
    if (cpuinfo.find("fphp") != std::string::npos || cpuinfo.find("asimdhp") != std::string::npos) {
        candidates.push_back({"librnllama_v8_2.so", "ARMv8.2 FP16"});
    }
    candidates.push_back({"librnllama_v8.so", "ARMv8 Baseline"});
    candidates.push_back({"librnllama.so", "Padrão"});

    for (const auto & item : candidates) {
        const std::string & lib_name = item.first;
        const std::string & desc = item.second;
        void * handle = dlopen(lib_name.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (handle) {
            if (load_symbols(handle)) {
                g_llama_lib = handle;
                g_loaded_backend_name = desc;
                LOGI("Backend carregado com sucesso: %s (%s)", lib_name.c_str(), desc.c_str());
                break;
            } else {
                dlclose(handle);
            }
        } else {
            LOGI("dlopen %s ignorado: %s", lib_name.c_str(), dlerror());
        }
    }

    if (!g_llama_lib) {
        LOGE("Nenhuma biblioteca nativa de aceleração pôde ser carregada.");
        return false;
    }

    return true;
}

static void batch_set_tokens(llama_batch_ext * batch, const llama_token * tokens, int32_t n_tokens, llama_pos pos_0) {
    fn_llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < n_tokens; ++i) {
        const int32_t idx = fn_llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = pos_0 + i;
        fn_llama_batch_ext_set_pos(batch, idx, &pos);
    }
    fn_llama_batch_ext_set_output_logits(batch, n_tokens - 1, true);
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_pocketpal_server_LlamaEngine_nativeGetLoadedBackend(JNIEnv *env, jobject thiz) {
    return env->NewStringUTF(g_loaded_backend_name.c_str());
}

JNIEXPORT void JNICALL
Java_com_pocketpal_server_LlamaEngine_nativeInitBackend(JNIEnv *env, jobject thiz) {
    if (!init_best_backend()) {
        LOGE("init_best_backend falhou!");
        return;
    }
    fn_llama_backend_init();
    LOGI("llama_backend_init() completado com sucesso via backend: %s", g_loaded_backend_name.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_pocketpal_server_LlamaEngine_nativeLoadModel(
    JNIEnv *env,
    jobject thiz,
    jstring model_path,
    jint n_threads,
    jint n_ctx,
    jint n_gpu_layers
) {
    if (!init_best_backend()) {
        LOGE("Backend nativo não inicializado");
        return JNI_FALSE;
    }

    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) {
        LOGE("Caminho do modelo inválido");
        return JNI_FALSE;
    }

    LOGI("Carregando modelo: %s (Backend: %s)", path, g_loaded_backend_name.c_str());

    if (g_context) {
        fn_llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        fn_llama_model_free(g_model);
        g_model = nullptr;
    }

    struct llama_model_params mparams = fn_llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;
    mparams.check_tensors = false;

    g_model = fn_llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("Falha ao carregar llama_model");
        return JNI_FALSE;
    }

    struct llama_context_params cparams = fn_llama_context_default_params();
    cparams.n_ctx = (uint32_t)(n_ctx > 0 ? n_ctx : 2048);
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = n_threads > 0 ? n_threads : 4;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED; // Reduz largura de banda e acelera atenção

    g_context = fn_llama_init_from_model(g_model, cparams);
    if (!g_context) {
        LOGE("Falha ao criar llama_context");
        fn_llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Modelo e Contexto carregados! Ctx: %u, Threads: %d, FlashAttn: Ativo", cparams.n_ctx, cparams.n_threads);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_pocketpal_server_LlamaEngine_nativeStop(JNIEnv *env, jobject thiz) {
    g_stop_requested.store(true);
    LOGI("Parada de inferência solicitada.");
}

JNIEXPORT void JNICALL
Java_com_pocketpal_server_LlamaEngine_nativeFree(JNIEnv *env, jobject thiz) {
    g_stop_requested.store(true);
    if (g_context && fn_llama_free) {
        fn_llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model && fn_llama_model_free) {
        fn_llama_model_free(g_model);
        g_model = nullptr;
    }
    if (fn_llama_backend_free) {
        fn_llama_backend_free();
    }
    LOGI("Recursos liberados.");
}

JNIEXPORT void JNICALL
Java_com_pocketpal_server_LlamaEngine_nativeGenerate(
    JNIEnv *env,
    jobject thiz,
    jstring j_prompt,
    jfloat temperature,
    jfloat top_p,
    jint max_tokens,
    jobject callback
) {
    if (!g_model || !g_context) {
        LOGE("Modelo ou contexto nulo");
        return;
    }

    g_stop_requested.store(false);

    const char * prompt_cstr = env->GetStringUTFChars(j_prompt, nullptr);
    if (!prompt_cstr) return;
    std::string prompt_str(prompt_cstr);
    env->ReleaseStringUTFChars(j_prompt, prompt_cstr);

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID mid_on_token = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");
    jmethodID mid_on_complete = env->GetMethodID(cb_class, "onComplete", "()V");

    const struct llama_vocab * vocab = fn_llama_model_get_vocab(g_model);
    if (!vocab) {
        LOGE("Vocabulário nulo");
        return;
    }

    llama_memory_t mem = fn_llama_get_memory(g_context);
    fn_llama_memory_clear(mem, true);

    const int n_prompt_raw = -fn_llama_tokenize(vocab, prompt_str.c_str(), (int32_t)prompt_str.length(), nullptr, 0, true, true);
    if (n_prompt_raw <= 0) {
        LOGE("Falha ao calcular tamanho dos tokens do prompt");
        return;
    }
    std::vector<llama_token> prompt_tokens(n_prompt_raw);
    if (fn_llama_tokenize(vocab, prompt_str.c_str(), (int32_t)prompt_str.length(), prompt_tokens.data(), (int32_t)prompt_tokens.size(), true, true) < 0) {
        LOGE("Falha na tokenização");
        return;
    }

    const int32_t n_prompt = (int32_t)prompt_tokens.size();
    LOGI("Prompt tokenizado: %d tokens.", n_prompt);

    struct llama_sampler_chain_params sparams = fn_llama_sampler_chain_default_params();
    sparams.no_perf = true;
    struct llama_sampler * smpl = fn_llama_sampler_chain_init(sparams);
    if (temperature <= 0.01f) {
        fn_llama_sampler_chain_add(smpl, fn_llama_sampler_init_greedy());
    } else {
        fn_llama_sampler_chain_add(smpl, fn_llama_sampler_init_temp(temperature));
        if (top_p > 0.0f && top_p < 1.0f) {
            fn_llama_sampler_chain_add(smpl, fn_llama_sampler_init_top_p(top_p, 1));
        }
        fn_llama_sampler_chain_add(smpl, fn_llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    llama_batch_ext * batch = fn_llama_batch_ext_init(g_context);

    // Prompt evaluation in chunks
    int32_t n_eval = 0;
    while (n_eval < n_prompt && !g_stop_requested.load()) {
        int32_t n_chunk = std::min((int32_t)512, n_prompt - n_eval);
        batch_set_tokens(batch, prompt_tokens.data() + n_eval, n_chunk, n_eval);
        if (n_eval + n_chunk < n_prompt) {
            fn_llama_batch_ext_set_output_logits(batch, n_chunk - 1, false);
        } else {
            fn_llama_batch_ext_set_output_logits(batch, n_chunk - 1, true);
        }

        if (fn_llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            LOGE("llama_process falhou nos tokens do prompt");
            fn_llama_batch_ext_free(batch);
            fn_llama_sampler_free(smpl);
            return;
        }
        n_eval += n_chunk;
    }

    // Token generation loop
    int32_t n_pos = n_prompt;
    int32_t n_generated = 0;
    int32_t max_gen = max_tokens > 0 ? max_tokens : 512;
    uint32_t max_ctx = fn_llama_n_ctx(g_context);

    while (n_generated < max_gen && (uint32_t)n_pos < max_ctx && !g_stop_requested.load()) {
        llama_token new_token = fn_llama_sampler_sample(smpl, g_context, -1);
        fn_llama_sampler_accept(smpl, new_token);

        if (fn_llama_vocab_is_eog(vocab, new_token) || new_token == fn_llama_vocab_eos(vocab)) {
            LOGI("Fim de geração (EOG / EOS).");
            break;
        }

        char piece_buf[128] = {0};
        int piece_len = fn_llama_token_to_piece(vocab, new_token, piece_buf, sizeof(piece_buf), 0, false);
        if (piece_len > 0) {
            std::string piece_str(piece_buf, piece_len);
            jstring j_piece = env->NewStringUTF(piece_str.c_str());
            if (mid_on_token && j_piece) {
                env->CallVoidMethod(callback, mid_on_token, j_piece);
                env->DeleteLocalRef(j_piece);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    break;
                }
            }
        }

        batch_set_tokens(batch, &new_token, 1, n_pos);
        n_pos++;
        n_generated++;

        if (fn_llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            LOGE("llama_process falhou na decodificação");
            break;
        }
    }

    fn_llama_batch_ext_free(batch);
    fn_llama_sampler_free(smpl);

    if (mid_on_complete && !env->ExceptionCheck()) {
        env->CallVoidMethod(callback, mid_on_complete);
    }
    LOGI("Geração concluída. Tokens gerados: %d", n_generated);
}

} // extern "C"
