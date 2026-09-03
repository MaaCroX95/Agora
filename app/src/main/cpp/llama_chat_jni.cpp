#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <string>
#include <vector>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <android/log.h>
#include "llama.h"
#include "chat.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "LlamaChatEngine"
#ifndef NDEBUG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#define LOGE(...) ((void)0)
#endif

static constexpr int32_t CALLBACK_TOKEN_BATCH = 4;
static constexpr size_t CALLBACK_BYTE_BATCH = 64;
static constexpr int32_t PENALTY_LAST_N = 64;

struct ChatHandle {
    llama_model * model   = nullptr;
    llama_context * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    common_chat_templates_ptr chat_templates;
    std::string path;
    int32_t n_ctx = 0;
    std::atomic<bool> cancelled{false};
    mtmd_context * mtmd_ctx = nullptr;  // multimodal context (for vision models)
};

static bool abort_callback(void * data) {
    ChatHandle * handle = (ChatHandle *)data;
    return handle->cancelled.load(std::memory_order_relaxed);
}

// Returns the byte length of the largest prefix of `text` that ends on a
// complete UTF-8 character boundary. llama frequently splits a multi-byte glyph
// (CJK, Arabic/Persian, emoji, …) across token pieces, so a single piece may end
// with a truncated sequence. Handing those raw bytes to NewStringUTF aborts the
// VM ("input is not valid Modified UTF-8"), so callers buffer the incomplete tail
// until the next token completes it.
static size_t utf8_complete_prefix_len(const std::string & text) {
    size_t len = text.length();
    // A truncated lead byte can only be within the last 3 bytes of the buffer.
    for (size_t i = 1; i <= 4 && i <= len; ++i) {
        unsigned char c = static_cast<unsigned char>(text[len - i]);
        if ((c & 0xE0) == 0xC0) return i < 2 ? len - i : len; // 2-byte sequence
        if ((c & 0xF0) == 0xE0) return i < 3 ? len - i : len; // 3-byte sequence
        if ((c & 0xF8) == 0xF0) return i < 4 ? len - i : len; // 4-byte sequence
        // ASCII or continuation byte: keep scanning back for the lead byte.
    }
    return len;
}

// Build a jstring from standard UTF-8 bytes WITHOUT going through NewStringUTF.
// NewStringUTF expects *Modified* UTF-8, in which supplementary-plane code points
// (U+10000+ — emoji, CJK extensions) must be a 6-byte CESU-8 surrogate pair; a
// standard 4-byte UTF-8 sequence is invalid Modified UTF-8 and aborts the VM. We
// decode UTF-8 → UTF-16 (emitting surrogate pairs) and use NewString, which takes
// genuine UTF-16 and handles the whole BMP + supplementary range safely.
static jstring utf8_to_jstring(JNIEnv * env, const char * data, size_t len) {
    std::vector<jchar> utf16;
    utf16.reserve(len);
    size_t i = 0;
    while (i < len) {
        unsigned char c = static_cast<unsigned char>(data[i]);
        uint32_t cp;
        size_t adv;
        if (c < 0x80) {
            cp = c; adv = 1;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < len) {
            cp = (uint32_t(c & 0x1F) << 6) | (data[i + 1] & 0x3F); adv = 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < len) {
            cp = (uint32_t(c & 0x0F) << 12) | (uint32_t(data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F); adv = 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < len) {
            cp = (uint32_t(c & 0x07) << 18) | (uint32_t(data[i + 1] & 0x3F) << 12)
               | (uint32_t(data[i + 2] & 0x3F) << 6) | (data[i + 3] & 0x3F); adv = 4;
        } else {
            cp = 0xFFFD; adv = 1; // malformed lead/continuation → replacement char
        }
        i += adv;
        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

struct NativeChatCallbacks {
    jclass clazz = nullptr;
    jmethodID on_token = nullptr;
    jmethodID on_done = nullptr;
    jmethodID on_error = nullptr;
};

static bool init_callbacks(JNIEnv * env, jobject callback, NativeChatCallbacks & methods) {
    methods.clazz = env->GetObjectClass(callback);
    if (!methods.clazz) return false;
    methods.on_token = env->GetMethodID(methods.clazz, "onToken", "(Ljava/lang/String;)Z");
    methods.on_done = env->GetMethodID(methods.clazz, "onDone", "(Ljava/lang/String;II)V");
    methods.on_error = env->GetMethodID(methods.clazz, "onError", "(Ljava/lang/String;II)V");
    if (methods.on_token && methods.on_done && methods.on_error) return true;
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(methods.clazz);
    methods.clazz = nullptr;
    return false;
}

static jint report_error(
    JNIEnv * env,
    jobject callback,
    NativeChatCallbacks & methods,
    const char * message,
    int32_t input_tokens,
    int32_t output_tokens
) {
    jstring jmessage = utf8_to_jstring(env, message, std::strlen(message));
    env->CallVoidMethod(
        callback, methods.on_error, jmessage,
        static_cast<jint>(input_tokens), static_cast<jint>(output_tokens)
    );
    env->DeleteLocalRef(jmessage);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(methods.clazz);
    methods.clazz = nullptr;
    return -1;
}

static jint report_done(
    JNIEnv * env,
    jobject callback,
    NativeChatCallbacks & methods,
    const char * reason,
    int32_t input_tokens,
    int32_t output_tokens
) {
    jstring jreason = env->NewStringUTF(reason);
    env->CallVoidMethod(
        callback, methods.on_done, jreason,
        static_cast<jint>(input_tokens), static_cast<jint>(output_tokens)
    );
    env->DeleteLocalRef(jreason);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(methods.clazz);
    methods.clazz = nullptr;
    return output_tokens;
}

static bool report_token(
    JNIEnv * env,
    jobject callback,
    const NativeChatCallbacks & methods,
    const char * data,
    size_t length
) {
    jstring jtoken = utf8_to_jstring(env, data, length);
    jboolean accepted = env->CallBooleanMethod(callback, methods.on_token, jtoken);
    env->DeleteLocalRef(jtoken);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return accepted == JNI_TRUE;
}

static bool token_to_piece(
    const llama_vocab * vocab,
    llama_token token,
    std::string & piece
) {
    char inline_buffer[256];
    int32_t length = llama_token_to_piece(
        vocab, token, inline_buffer, sizeof(inline_buffer), 0, true
    );
    if (length >= 0) {
        piece.assign(inline_buffer, static_cast<size_t>(length));
        return true;
    }
    std::vector<char> dynamic_buffer(static_cast<size_t>(-length));
    length = llama_token_to_piece(
        vocab, token, dynamic_buffer.data(), dynamic_buffer.size(), 0, true
    );
    if (length < 0) return false;
    piece.assign(dynamic_buffer.data(), static_cast<size_t>(length));
    return true;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatLoadModel(
    JNIEnv * env, jclass /*clazz*/, jstring path, jint n_ctx) {

    const char * path_str = env->GetStringUTFChars(path, nullptr);
    if (!path_str) return 0;

    ChatHandle * handle = new ChatHandle();
    if (!handle) {
        env->ReleaseStringUTFChars(path, path_str);
        return 0;
    }

    const auto load_started = std::chrono::steady_clock::now();
    llama_backend_init();
    ggml_backend_load_all();

    llama_model_params model_params = llama_model_default_params();
    const auto model_started = std::chrono::steady_clock::now();
    handle->model = llama_model_load_from_file(path_str, model_params);
    const auto model_finished = std::chrono::steady_clock::now();
    env->ReleaseStringUTFChars(path, path_str);

    if (!handle->model) {
        LOGE("Failed to load model from file");
        delete handle;
        return 0;
    }

    handle->vocab = llama_model_get_vocab(handle->model);
    handle->n_ctx = n_ctx;

    try {
        handle->chat_templates = common_chat_templates_init(handle->model, "");
        if (!common_chat_templates_was_explicit(handle->chat_templates.get())) {
            handle->chat_templates.reset();
            LOGE("Model does not contain an explicit chat template");
        }
    } catch (const std::exception &) {
        handle->chat_templates.reset();
        LOGE("Failed to initialize model chat template");
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx   = n_ctx;
    // n_batch bounds the LOGITS/EMBEDDINGS buffers llama.cpp allocates up front, so tying it to
    // n_ctx made memory grow with the square of the context — the OOM on large-context local
    // models (#53). 512 is llama.cpp's own default and prefill is chunked to match; on-device
    // prompt-eval throughput is unaffected because it is compute-bound well below 512 tokens.
    ctx_params.n_batch = std::min(512, n_ctx);

    const auto context_started = std::chrono::steady_clock::now();
    handle->ctx = llama_init_from_model(handle->model, ctx_params);
    if (!handle->ctx) {
        LOGE("Failed to create context");
        llama_model_free(handle->model);
        delete handle;
        return 0;
    }

    llama_set_abort_callback(handle->ctx, abort_callback, handle);

    const auto load_finished = std::chrono::steady_clock::now();
    const auto model_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        model_finished - model_started
    ).count();
    const auto context_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        load_finished - context_started
    ).count();
    const auto total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        load_finished - load_started
    ).count();
    LOGD("Chat load: model_ms=%lld, context_ms=%lld, total_ms=%lld, n_ctx=%d, n_ctx_train=%d",
         (long long)model_ms, (long long)context_ms, (long long)total_ms,
         n_ctx, llama_model_n_ctx_train(handle->model));

    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jstring JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatGetTemplate(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return nullptr;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->model) return nullptr;

    const char * tmpl = llama_model_chat_template(handle->model, nullptr);
    if (!tmpl) return nullptr;
    return utf8_to_jstring(env, tmpl, strlen(tmpl));
}

JNIEXPORT jstring JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatApplyTemplate(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr,
    jobjectArray messages, jboolean add_ass, jboolean enable_thinking) {

    if (!handle_ptr) return nullptr;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->model || !handle->chat_templates) return nullptr;

    jint n_msg = env->GetArrayLength(messages);

    common_chat_templates_inputs inputs;
    inputs.messages.reserve(n_msg);
    inputs.add_generation_prompt = add_ass;
    inputs.enable_thinking = enable_thinking;
    inputs.use_jinja = true;

    for (jint i = 0; i < n_msg; i++) {
        jobject msg = env->GetObjectArrayElement(messages, i);
        jclass msg_class = env->GetObjectClass(msg);

        jfieldID role_field = env->GetFieldID(msg_class, "role", "Ljava/lang/String;");
        jfieldID content_field = env->GetFieldID(msg_class, "content", "Ljava/lang/String;");

        jstring role_jstr = (jstring)env->GetObjectField(msg, role_field);
        jstring content_jstr = (jstring)env->GetObjectField(msg, content_field);

        const char * role_cstr = env->GetStringUTFChars(role_jstr, nullptr);
        const char * content_cstr = env->GetStringUTFChars(content_jstr, nullptr);

        common_chat_msg chat_msg;
        chat_msg.role = role_cstr ? role_cstr : "user";
        chat_msg.content = content_cstr ? content_cstr : "";
        inputs.messages.push_back(std::move(chat_msg));

        if (role_cstr) env->ReleaseStringUTFChars(role_jstr, role_cstr);
        if (content_cstr) env->ReleaseStringUTFChars(content_jstr, content_cstr);

        env->DeleteLocalRef(msg_class);
        env->DeleteLocalRef(msg);
    }

    try {
        const common_chat_params params = common_chat_templates_apply(
            handle->chat_templates.get(), inputs
        );
        return utf8_to_jstring(env, params.prompt.data(), params.prompt.size());
    } catch (const std::exception &) {
        LOGE("Failed to apply model chat template");
        return nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatGenerate(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr,
    jstring prompt, jfloat temperature, jfloat top_p,
    jfloat frequency_penalty, jfloat presence_penalty, jint max_tokens,
    jobject callback) {

    NativeChatCallbacks callbacks;
    if (!init_callbacks(env, callback, callbacks)) return -1;
    if (!handle_ptr) return report_error(env, callback, callbacks, "Invalid model handle", 0, 0);
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->ctx || !handle->vocab) return report_error(
        env, callback, callbacks, "Model is not loaded", 0, 0
    );

    handle->cancelled.store(false, std::memory_order_relaxed);
    const auto request_started = std::chrono::steady_clock::now();

    const char * prompt_str = env->GetStringUTFChars(prompt, nullptr);
    if (!prompt_str) {
        return report_error(env, callback, callbacks, "Unable to read prompt", 0, 0);
    }

    std::string prompt_text(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    if (prompt_text.empty()) {
        return report_error(env, callback, callbacks, "Prompt is empty", 0, 0);
    }

    int32_t n_tokens_max = prompt_text.length() + 256;
    std::vector<llama_token> tokens(n_tokens_max);
    int32_t n_tokens = llama_tokenize(handle->vocab, prompt_text.c_str(),
                                       prompt_text.size(), tokens.data(),
                                       n_tokens_max, true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(handle->vocab, prompt_text.c_str(),
                                   prompt_text.size(), tokens.data(),
                                   -n_tokens, true, true);
    }
    if (n_tokens <= 0) {
        LOGE("Tokenization returned 0 tokens for prompt len=%zu", prompt_text.size());
        return report_error(env, callback, callbacks, "Tokenization failed", 0, 0);
    }
    tokens.resize(n_tokens);

    const int32_t n_ctx = llama_n_ctx(handle->ctx);
    const int32_t min_generation_room = 4;
    if (n_tokens + min_generation_room > n_ctx) {
        LOGE("Prompt too long: prompt=%d + reserved=%d > ctx=%d",
             n_tokens, min_generation_room, n_ctx);
        char error_msg[64];
        std::snprintf(error_msg, sizeof(error_msg),
                      "LOCAL_CONTEXT_EXCEEDED:%d:%d", n_tokens, n_ctx);
        return report_error(env, callback, callbacks, error_msg, n_tokens, 0);
    }

    LOGD("Generating: prompt_len=%zu, n_tokens=%d, max_tokens=%d",
         prompt_text.size(), n_tokens, max_tokens);

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
        PENALTY_LAST_N, 1.0f, frequency_penalty, presence_penalty
    ));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    int32_t n_ctx_used = llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) + 1;
    if (n_ctx_used + n_tokens > n_ctx) {
        LOGE("Context size exceeded: used=%d + prompt=%d > ctx=%d", n_ctx_used, n_tokens, n_ctx);
        llama_sampler_free(smpl);
        return report_error(env, callback, callbacks, "Context size exceeded", 0, 0);
    }

    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(handle->ctx));
    int32_t input_tokens = 0;
    const auto prefill_started = std::chrono::steady_clock::now();
    for (int32_t off = 0; off < n_tokens; off += n_batch) {
        if (handle->cancelled.load(std::memory_order_relaxed)) {
            LOGD("Cancelled during prefill at %d/%d tokens", off, n_tokens);
            llama_sampler_free(smpl);
            return report_done(env, callback, callbacks, "cancelled", input_tokens, 0);
        }
        const int32_t chunk = std::min(n_batch, n_tokens - off);
        llama_batch batch = llama_batch_get_one(tokens.data() + off, chunk);
        if (llama_decode(handle->ctx, batch) != 0) {
            LOGE("Prefill decode failed at offset %d (chunk=%d)", off, chunk);
            llama_sampler_free(smpl);
            return report_error(
                env, callback, callbacks, "Prefill decode failed", input_tokens, 0
            );
        }
        input_tokens += chunk;
    }
    const auto prefill_finished = std::chrono::steady_clock::now();
    const auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        prefill_finished - prefill_started
    ).count();
    LOGD("Text prefill: input_tokens=%d, duration_ms=%lld",
         input_tokens, (long long)prefill_ms);

    const int32_t context_after_prefill =
        llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) + 1;
    const int32_t remaining_context = std::max(0, n_ctx - context_after_prefill);
    const int32_t generation_limit = std::min(max_tokens, remaining_context);
    const bool context_limited = generation_limit < max_tokens;

    int32_t generated = 0;
    int32_t callback_tokens = 0;
    std::string utf8_pending;
    std::string callback_buffer;
    const char * stop_reason = nullptr;
    const char * failure = nullptr;
    bool consumer_closed = false;
    const auto decode_started = std::chrono::steady_clock::now();
    while (generated < generation_limit) {
        if (handle->cancelled.load(std::memory_order_relaxed)) {
            LOGD("Generation cancelled at %d tokens", generated);
            stop_reason = "cancelled";
            break;
        }

        int32_t n_ctx_used = llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) + 1;
        if (n_ctx_used + 1 > n_ctx) {
            LOGD("Context full at %d tokens", generated);
            stop_reason = "context_full";
            break;
        }

        llama_token new_token_id = llama_sampler_sample(smpl, handle->ctx, -1);

        if (llama_vocab_is_eog(handle->vocab, new_token_id)) {
            LOGD("EOG token %d at position %d", new_token_id, generated);
            stop_reason = "eog";
            break;
        }

        std::string piece;
        if (!token_to_piece(handle->vocab, new_token_id, piece)) {
            LOGE("llama_token_to_piece failed");
            failure = "Token conversion failed";
            break;
        }

        llama_batch single = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(handle->ctx, single) != 0) {
            LOGE("Decode failed at token %d", generated + 1);
            failure = "Decode failed";
            break;
        }
        generated++;

        utf8_pending.append(piece);
        const size_t complete_len = utf8_complete_prefix_len(utf8_pending);
        if (complete_len > 0) {
            callback_buffer.append(utf8_pending.data(), complete_len);
            utf8_pending.erase(0, complete_len);
        }
        callback_tokens++;
        while (!callback_buffer.empty() &&
               (callback_tokens >= CALLBACK_TOKEN_BATCH ||
                callback_buffer.size() >= CALLBACK_BYTE_BATCH)) {
            size_t emit_len = std::min(callback_buffer.size(), CALLBACK_BYTE_BATCH);
            while (emit_len < callback_buffer.size() && emit_len > 0 &&
                   (static_cast<unsigned char>(callback_buffer[emit_len]) & 0xC0) == 0x80) {
                emit_len--;
            }
            if (!report_token(
                    env, callback, callbacks,
                    callback_buffer.data(), emit_len
                )) {
                failure = "Stream consumer closed";
                consumer_closed = true;
                break;
            }
            callback_buffer.erase(0, emit_len);
            callback_tokens = 0;
        }
        if (consumer_closed) break;
    }

    if (!failure && !stop_reason) {
        stop_reason = context_limited ? "context_full" : "max_tokens";
    }
    if (!consumer_closed && !callback_buffer.empty()) {
        if (!report_token(
                env, callback, callbacks,
                callback_buffer.data(), callback_buffer.size()
            )) {
            failure = "Stream consumer closed";
            consumer_closed = true;
        }
    }
    if (!failure && !utf8_pending.empty()) failure = "Generated incomplete UTF-8 output";
    const auto decode_finished = std::chrono::steady_clock::now();
    const auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        decode_finished - decode_started
    ).count();
    const auto request_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        decode_finished - request_started
    ).count();
    const double tokens_per_second = decode_ms > 0
        ? generated * 1000.0 / static_cast<double>(decode_ms)
        : 0.0;
    LOGD("Text decode: output_tokens=%d, duration_ms=%lld, tokens_per_second=%.2f, terminal=%s",
         generated, (long long)decode_ms, tokens_per_second,
         failure ? "error" : stop_reason);
    LOGD("Text request: input_tokens=%d, output_tokens=%d, total_ms=%lld",
         input_tokens, generated, (long long)request_ms);
    llama_sampler_free(smpl);
    if (failure) {
        return report_error(env, callback, callbacks, failure, input_tokens, generated);
    }
    return report_done(
        env, callback, callbacks, stop_reason, input_tokens, generated
    );
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatReset(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (handle->ctx) {
        llama_memory_clear(llama_get_memory(handle->ctx), true);
        LOGD("KV cache cleared");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatLoadMmproj(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr, jstring mmproj_path) {

    if (!handle_ptr) return JNI_FALSE;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->model) return JNI_FALSE;

    const char * mmproj_str = env->GetStringUTFChars(mmproj_path, nullptr);
    if (!mmproj_str) return JNI_FALSE;

    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;
    params.n_threads = 4;
    params.print_timings = false;

    // Try loading new mmproj first (don't free old one yet)
    mtmd_context * new_mtmd = mtmd_init_from_file(mmproj_str, handle->model, params);
    env->ReleaseStringUTFChars(mmproj_path, mmproj_str);

    if (!new_mtmd) {
        LOGE("Failed to load mmproj, keeping previous if any");
        return JNI_FALSE;
    }

    // Success: free old, install new
    if (handle->mtmd_ctx) {
        mtmd_free(handle->mtmd_ctx);
    }
    handle->mtmd_ctx = new_mtmd;

    LOGD("mmproj loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatUnloadMmproj(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (handle->mtmd_ctx) {
        mtmd_free(handle->mtmd_ctx);
        handle->mtmd_ctx = nullptr;
        LOGD("mmproj unloaded");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatHasMmproj(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return JNI_FALSE;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    return handle->mtmd_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatGenerateWithImages(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr,
    jstring prompt, jobjectArray image_paths,
    jfloat temperature, jfloat top_p,
    jfloat frequency_penalty, jfloat presence_penalty, jint max_tokens,
    jobject callback) {

    NativeChatCallbacks callbacks;
    if (!init_callbacks(env, callback, callbacks)) return -1;
    if (!handle_ptr) {
        return report_error(env, callback, callbacks, "Invalid model handle", 0, 0);
    }
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->ctx || !handle->vocab) {
        return report_error(env, callback, callbacks, "Model is not loaded", 0, 0);
    }
    if (!handle->mtmd_ctx) {
        return report_error(
            env, callback, callbacks,
            "Vision projector not loaded. Add mmproj file in model settings.", 0, 0
        );
    }

    handle->cancelled.store(false, std::memory_order_relaxed);
    const auto request_started = std::chrono::steady_clock::now();

    const char * prompt_str = env->GetStringUTFChars(prompt, nullptr);
    if (!prompt_str) {
        return report_error(env, callback, callbacks, "Unable to read prompt", 0, 0);
    }
    std::string prompt_text(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);
    if (prompt_text.empty()) {
        return report_error(env, callback, callbacks, "Prompt is empty", 0, 0);
    }

    const auto prefill_started = std::chrono::steady_clock::now();

    // --- Build bitmaps from image paths ---
    jint n_images = env->GetArrayLength(image_paths);
    std::vector<mtmd_bitmap *> bitmaps(n_images, nullptr);
    std::vector<std::string> image_path_storage(n_images);

    for (jint i = 0; i < n_images; i++) {
        jstring jpath = (jstring)env->GetObjectArrayElement(image_paths, i);
        if (!jpath) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
            return report_error(
                env, callback, callbacks, "Unable to read image path.", 0, 0
            );
        }
        const char * cpath = env->GetStringUTFChars(jpath, nullptr);
        if (!cpath) {
            env->DeleteLocalRef(jpath);
            if (env->ExceptionCheck()) env->ExceptionClear();
            for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
            return report_error(
                env, callback, callbacks, "Unable to read image path.", 0, 0
            );
        }
        image_path_storage[i] = std::string(cpath);
        env->ReleaseStringUTFChars(jpath, cpath);
        env->DeleteLocalRef(jpath);

        bitmaps[i] = mtmd_helper_bitmap_init_from_file(handle->mtmd_ctx,
                                                       image_path_storage[i].c_str());
        if (!bitmaps[i]) {
            LOGE("Failed to load image at index %d of %d", i, n_images);
            // Clean up already-loaded bitmaps
            for (jint j = 0; j < i; j++) {
                if (bitmaps[j]) mtmd_bitmap_free(bitmaps[j]);
            }
            return report_error(
                env, callback, callbacks, "Failed to load image for multimodal input.", 0, 0
            );
        }
    }

    // --- Tokenize prompt with image markers ---
    mtmd_input_text text_input;
    text_input.text         = prompt_text.c_str();
    text_input.add_special  = true;
    text_input.parse_special = true;

    std::vector<const mtmd_bitmap *> bitmap_ptrs;
    for (auto & b : bitmaps) bitmap_ptrs.push_back(b);

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    if (!chunks) {
        for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
        return report_error(
            env, callback, callbacks, "Unable to allocate multimodal prompt chunks.", 0, 0
        );
    }
    int32_t tok_ret = mtmd_tokenize(handle->mtmd_ctx, chunks, &text_input,
                                    bitmap_ptrs.data(), bitmap_ptrs.size());
    if (tok_ret != 0) {
        LOGE("mtmd_tokenize failed with code %d (images=%d)", tok_ret, n_images);
        for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
        mtmd_input_chunks_free(chunks);
        return report_error(
            env, callback, callbacks, "Failed to tokenize multimodal prompt.", 0, 0
        );
    }

    llama_pos n_past = 0;
    int32_t n_ctx = llama_n_ctx(handle->ctx);
    if (handle->cancelled.load(std::memory_order_relaxed)) {
        for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
        mtmd_input_chunks_free(chunks);
        return report_done(env, callback, callbacks, "cancelled", 0, 0);
    }
    // The 6th argument is the helper's BATCH size, not the context size: passing n_ctx made it
    // build batches larger than the context's n_batch, which llama_decode rejects.
    int32_t eval_ret = mtmd_helper_eval_chunks(handle->mtmd_ctx, handle->ctx,
                                                chunks, n_past, 0,
                                                static_cast<int32_t>(llama_n_batch(handle->ctx)),
                                                true, &n_past);
    // Free bitmaps and chunks after evaluation
    for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
    mtmd_input_chunks_free(chunks);

    if (eval_ret != 0) {
        LOGE("mtmd_helper_eval_chunks failed with code %d", eval_ret);
        return report_error(
            env, callback, callbacks, "Multimodal prefill failed.",
            static_cast<int32_t>(n_past), 0
        );
    }
    if (handle->cancelled.load(std::memory_order_relaxed)) {
        return report_done(
            env, callback, callbacks, "cancelled", static_cast<int32_t>(n_past), 0
        );
    }

    const auto prefill_finished = std::chrono::steady_clock::now();
    const auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        prefill_finished - prefill_started
    ).count();
    LOGD("Multimodal prefill: input_tokens=%lld, images=%d, duration_ms=%lld",
         (long long)n_past, n_images, (long long)prefill_ms);

    // --- Generation loop (same as text-only path) ---
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
        PENALTY_LAST_N, 1.0f, frequency_penalty, presence_penalty
    ));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    const int32_t context_after_prefill =
        llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) + 1;
    const int32_t remaining_context = std::max(0, n_ctx - context_after_prefill);
    const int32_t generation_limit = std::min(max_tokens, remaining_context);
    const bool context_limited = generation_limit < max_tokens;

    int32_t generated = 0;
    int32_t callback_tokens = 0;
    std::string utf8_pending;
    std::string callback_buffer;
    const char * stop_reason = nullptr;
    const char * failure = nullptr;
    bool consumer_closed = false;
    const auto decode_started = std::chrono::steady_clock::now();
    while (generated < generation_limit) {
        if (handle->cancelled.load(std::memory_order_relaxed)) {
            stop_reason = "cancelled";
            break;
        }

        int32_t n_ctx_used = llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) + 1;
        if (n_ctx_used + 1 > n_ctx) {
            stop_reason = "context_full";
            break;
        }

        llama_token new_token_id = llama_sampler_sample(smpl, handle->ctx, -1);

        if (llama_vocab_is_eog(handle->vocab, new_token_id)) {
            stop_reason = "eog";
            break;
        }

        std::string piece;
        if (!token_to_piece(handle->vocab, new_token_id, piece)) {
            failure = "Token conversion failed";
            break;
        }

        llama_batch single = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(handle->ctx, single) != 0) {
            failure = "Decode failed";
            break;
        }
        generated++;

        utf8_pending.append(piece);
        const size_t complete_len = utf8_complete_prefix_len(utf8_pending);
        if (complete_len > 0) {
            callback_buffer.append(utf8_pending.data(), complete_len);
            utf8_pending.erase(0, complete_len);
        }
        callback_tokens++;
        while (!callback_buffer.empty() &&
               (callback_tokens >= CALLBACK_TOKEN_BATCH ||
                callback_buffer.size() >= CALLBACK_BYTE_BATCH)) {
            size_t emit_len = std::min(callback_buffer.size(), CALLBACK_BYTE_BATCH);
            while (emit_len < callback_buffer.size() && emit_len > 0 &&
                   (static_cast<unsigned char>(callback_buffer[emit_len]) & 0xC0) == 0x80) {
                emit_len--;
            }
            if (!report_token(
                    env, callback, callbacks,
                    callback_buffer.data(), emit_len
                )) {
                failure = "Stream consumer closed";
                consumer_closed = true;
                break;
            }
            callback_buffer.erase(0, emit_len);
            callback_tokens = 0;
        }
        if (consumer_closed) break;
    }

    if (!failure && !stop_reason) {
        stop_reason = context_limited ? "context_full" : "max_tokens";
    }
    if (!consumer_closed && !callback_buffer.empty()) {
        if (!report_token(
                env, callback, callbacks,
                callback_buffer.data(), callback_buffer.size()
            )) {
            failure = "Stream consumer closed";
            consumer_closed = true;
        }
    }
    if (!failure && !utf8_pending.empty()) failure = "Generated incomplete UTF-8 output";
    const auto decode_finished = std::chrono::steady_clock::now();
    const auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        decode_finished - decode_started
    ).count();
    const auto request_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        decode_finished - request_started
    ).count();
    const double tokens_per_second = decode_ms > 0
        ? generated * 1000.0 / static_cast<double>(decode_ms)
        : 0.0;
    LOGD("Multimodal decode: output_tokens=%d, duration_ms=%lld, tokens_per_second=%.2f, terminal=%s",
         generated, (long long)decode_ms, tokens_per_second,
         failure ? "error" : stop_reason);
    LOGD("Multimodal request: input_tokens=%lld, output_tokens=%d, total_ms=%lld",
         (long long)n_past, generated, (long long)request_ms);
    llama_sampler_free(smpl);
    const int32_t input_tokens = static_cast<int32_t>(n_past);
    if (failure) {
        return report_error(env, callback, callbacks, failure, input_tokens, generated);
    }
    return report_done(
        env, callback, callbacks, stop_reason, input_tokens, generated
    );
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatFreeModel(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);

    if (handle->mtmd_ctx) mtmd_free(handle->mtmd_ctx);
    handle->chat_templates.reset();
    if (handle->ctx)   llama_free(handle->ctx);
    if (handle->model) llama_model_free(handle->model);

    LOGD("Chat model freed");
    delete handle;
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatCancel(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    handle->cancelled.store(true, std::memory_order_relaxed);
    LOGD("Cancellation requested");
}

} // extern "C"
