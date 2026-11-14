#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <fstream>
#include <sstream>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "JARVIS_NDK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if HAVE_LLAMA_CPP
#include "llama.h"
#endif

#if HAVE_WHISPER_CPP
#include "whisper.h"
#endif

namespace {

std::mutex g_engine_mutex;
std::string g_loaded_model_path;
bool g_virtual_model_loaded = false;

#if HAVE_LLAMA_CPP
llama_model* g_model = nullptr;
llama_context* g_ctx = nullptr;
llama_sampler* g_sampler = nullptr;
#endif

// Strict ChatML system prompt forcing SmolLM2-360M-Instruct-Q4_K_M.gguf to emit ONLY compact JSON
const char* kJarvisSystemPrompt =
    "<|im_start|>system\n"
    "You are JARVIS, a bilingual (English, Hindi, Hinglish) offline Android phone orchestrator by Prince Priyadarshi. "
    "Understand natural conversational requests and multi-step commands. "
    "Output ONLY a compact JSON object or JSON array matching these schemas and nothing else:\n"
    "{\"action\":\"wifi\",\"state\":\"on|off\"}\n"
    "{\"action\":\"bluetooth\",\"state\":\"on|off\"}\n"
    "{\"action\":\"flashlight\",\"state\":\"on|off\"}\n"
    "{\"action\":\"dnd\",\"state\":\"on|off\"}\n"
    "{\"action\":\"volume\",\"level\":50}\n"
    "{\"action\":\"open_app\",\"query\":\"whatsapp|youtube|camera|calculator|settings\"}\n"
    "{\"action\":\"youtube_play\",\"query\":\"shiv tandav\"}\n"
    "{\"action\":\"whatsapp_send\",\"contact\":\"Papa\",\"message\":\"hi\"}\n"
    "{\"action\":\"call\",\"name\":\"Mom\"}\n"
    "{\"action\":\"alarm\",\"minutes\":30}\n"
    "{\"action\":\"ui_click\",\"text\":\"Send\"}\n"
    "{\"action\":\"nav\",\"target\":\"home|back|recents|notifications|scroll_down|scroll_up\"}\n"
    "{\"action\":\"speak\",\"text\":\"Not understood\"}\n"
    "<|im_end|>\n";

std::string toLowerAscii(const std::string& input) {
    std::string out = input;
    std::transform(out.begin(), out.end(), out.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return out;
}

std::string trimString(const std::string& s) {
    size_t start = 0;
    while (start < s.size() && std::isspace(static_cast<unsigned char>(s[start]))) {
        start++;
    }
    size_t end = s.size();
    while (end > start && std::isspace(static_cast<unsigned char>(s[end - 1]))) {
        end--;
    }
    return s.substr(start, end - start);
}

std::string escapeJsonString(const std::string& input) {
    std::ostringstream oss;
    for (char c : input) {
        switch (c) {
            case '"': oss << "\\\""; break;
            case '\\': oss << "\\\\"; break;
            case '\n': oss << " "; break;
            case '\r': break;
            case '\t': oss << " "; break;
            default: oss << c; break;
        }
    }
    return oss.str();
}

int extractFirstNumber(const std::string& text, int fallback) {
    std::string digits;
    for (char c : text) {
        if (std::isdigit(static_cast<unsigned char>(c))) {
            digits.push_back(c);
        } else if (!digits.empty()) {
            break;
        }
    }
    if (digits.empty()) return fallback;
    try {
        return std::stoi(digits);
    } catch (...) {
        return fallback;
    }
}

std::string extractJsonObject(const std::string& raw) {
    size_t start = raw.find('{');
    if (start == std::string::npos) return "";
    int depth = 0;
    bool inQuotes = false;
    bool escaped = false;
    for (size_t i = start; i < raw.size(); ++i) {
        char c = raw[i];
        if (escaped) {
            escaped = false;
            continue;
        }
        if (c == '\\') {
            escaped = true;
            continue;
        }
        if (c == '"') {
            inQuotes = !inQuotes;
            continue;
        }
        if (!inQuotes) {
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substr(start, i - start + 1);
                }
            }
        }
    }
    return "";
}

std::string fallbackRuleBasedJson(const std::string& userText) {
    std::string lower = toLowerAscii(userText);
    bool turnOff = (lower.find("off") != std::string::npos ||
                    lower.find("disable") != std::string::npos ||
                    lower.find("stop") != std::string::npos ||
                    lower.find("band") != std::string::npos ||
                    lower.find("close") != std::string::npos);

    if (lower.find("wifi") != std::string::npos || lower.find("wi-fi") != std::string::npos) {
        return std::string("{\"action\":\"wifi\",\"state\":\"") + (turnOff ? "off" : "on") + "\"}";
    }
    if (lower.find("bluetooth") != std::string::npos) {
        return std::string("{\"action\":\"bluetooth\",\"state\":\"") + (turnOff ? "off" : "on") + "\"}";
    }
    if (lower.find("flash") != std::string::npos || lower.find("torch") != std::string::npos ||
        lower.find("light") != std::string::npos || lower.find("andhera") != std::string::npos) {
        return std::string("{\"action\":\"flashlight\",\"state\":\"") + (turnOff ? "off" : "on") + "\"}";
    }
    if (lower.find("dnd") != std::string::npos || lower.find("do not disturb") != std::string::npos ||
        lower.find("silent") != std::string::npos || lower.find("mute") != std::string::npos) {
        return std::string("{\"action\":\"dnd\",\"state\":\"") + (turnOff ? "off" : "on") + "\"}";
    }
    if (lower.find("volume") != std::string::npos || lower.find("louder") != std::string::npos ||
        lower.find("quieter") != std::string::npos || lower.find("awaz") != std::string::npos) {
        int lvl = extractFirstNumber(lower, -1);
        if (lvl < 0) {
            if (lower.find("max") != std::string::npos || lower.find("full") != std::string::npos) lvl = 100;
            else if (lower.find("louder") != std::string::npos || lower.find("up") != std::string::npos || lower.find("badha") != std::string::npos) lvl = 80;
            else if (lower.find("quieter") != std::string::npos || lower.find("down") != std::string::npos || lower.find("kam") != std::string::npos) lvl = 30;
            else lvl = 50;
        }
        lvl = std::max(0, std::min(100, lvl));
        return "{\"action\":\"volume\",\"level\":" + std::to_string(lvl) + "}";
    }
    if (lower.find("alarm") != std::string::npos || lower.find("timer") != std::string::npos ||
        lower.find("wake me") != std::string::npos) {
        int mins = extractFirstNumber(lower, 30);
        if (lower.find("hour") != std::string::npos && mins <= 12) {
            mins *= 60;
        }
        return "{\"action\":\"alarm\",\"minutes\":" + std::to_string(std::max(1, mins)) + "}";
    }
    if (lower.find("call ") != std::string::npos || lower.find("dial ") != std::string::npos) {
        size_t pos = lower.find("call ");
        size_t len = 5;
        if (pos == std::string::npos) {
            pos = lower.find("dial ");
            len = 5;
        }
        std::string target = userText.substr(pos + len);
        return "{\"action\":\"call\",\"name\":\"" + escapeJsonString(target) + "\"}";
    }
    if (lower.find("open ") != std::string::npos || lower.find("launch ") != std::string::npos) {
        size_t pos = lower.find("open ");
        size_t len = 5;
        if (pos == std::string::npos) {
            pos = lower.find("launch ");
            len = 7;
        }
        std::string appQuery = lower.substr(pos + len);
        return "{\"action\":\"open_app\",\"query\":\"" + escapeJsonString(appQuery) + "\"}";
    }
    if (lower.find("click ") != std::string::npos || lower.find("tap ") != std::string::npos ||
        lower.find("press ") != std::string::npos) {
        size_t pos = lower.find("click ");
        size_t len = 6;
        if (pos == std::string::npos) {
            pos = lower.find("tap ");
            len = 4;
        }
        if (pos == std::string::npos) {
            pos = lower.find("press ");
            len = 6;
        }
        std::string btnText = userText.substr(pos + len);
        return "{\"action\":\"ui_click\",\"text\":\"" + escapeJsonString(btnText) + "\"}";
    }
    if (lower.find("scroll down") != std::string::npos) {
        return "{\"action\":\"nav\",\"target\":\"scroll_down\"}";
    }
    if (lower.find("scroll up") != std::string::npos) {
        return "{\"action\":\"nav\",\"target\":\"scroll_up\"}";
    }
    if (lower.find("go home") != std::string::npos || lower == "home") {
        return "{\"action\":\"nav\",\"target\":\"home\"}";
    }
    if (lower.find("go back") != std::string::npos || lower == "back") {
        return "{\"action\":\"nav\",\"target\":\"back\"}";
    }
    if (lower.find("recent") != std::string::npos) {
        return "{\"action\":\"nav\",\"target\":\"recents\"}";
    }
    if (lower.find("notification") != std::string::npos) {
        return "{\"action\":\"nav\",\"target\":\"notifications\"}";
    }
    if (lower.find("battery") != std::string::npos) {
        return "{\"action\":\"battery\"}";
    }
    if (lower.find("who made you") != std::string::npos || lower.find("creator") != std::string::npos ||
        lower.find("engineer") != std::string::npos) {
        return "{\"action\":\"speak\",\"text\":\"I was created and engineered by Prince Priyadarshi.\"}";
    }
    if (lower.size() < 3 || lower == "the" || lower == "huh" || lower == "uh" || lower == "okay" || lower == "online") {
        return "{\"action\":\"noop\",\"text\":\"\"}";
    }
    return "{\"action\":\"speak\",\"text\":\"Not understood\"}";
}

bool verifyGgufHeader(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) return false;
    char magic[4] = {0};
    file.read(magic, 4);
    return (file.gcount() == 4 && magic[0] == 'G' && magic[1] == 'G' && magic[2] == 'U' && magic[3] == 'F');
}

void freeLlamaInternalLocked() {
#if HAVE_LLAMA_CPP
    if (g_sampler != nullptr) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
#endif
    g_loaded_model_path.clear();
    g_virtual_model_loaded = false;
}

} // namespace

// ==============================================================================
// STATE 3 (TRANSCRIBING): Load ggml-tiny.bin -> Transcribe 16kHz PCM -> Free Immediately
// ==============================================================================
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_engine_LlamaBridge_nativeWhisperTranscribe(
        JNIEnv* env,
        jobject /* thiz */,
        jstring modelPath,
        jshortArray pcmData) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);

    // Guarantee Llama is unloaded before Whisper loads into the 2 GB RAM budget
    freeLlamaInternalLocked();

    if (modelPath == nullptr || pcmData == nullptr) {
        return env->NewStringUTF("");
    }

    const char* pathChars = env->GetStringUTFChars(modelPath, nullptr);
    std::string pathStr(pathChars ? pathChars : "");
    if (pathChars) {
        env->ReleaseStringUTFChars(modelPath, pathChars);
    }

    jsize numSamples = env->GetArrayLength(pcmData);
    if (numSamples <= 0) {
        return env->NewStringUTF("");
    }

    std::vector<float> pcmf32(static_cast<size_t>(numSamples));
    jshort* rawShorts = env->GetShortArrayElements(pcmData, nullptr);
    if (rawShorts == nullptr) {
        return env->NewStringUTF("");
    }

    double sumSq = 0.0;
    for (jsize i = 0; i < numSamples; ++i) {
        float normalized = static_cast<float>(rawShorts[i]) / 32768.0f;
        pcmf32[static_cast<size_t>(i)] = normalized;
        sumSq += static_cast<double>(normalized) * static_cast<double>(normalized);
    }
    env->ReleaseShortArrayElements(pcmData, rawShorts, JNI_ABORT);

    double rms = std::sqrt(sumSq / static_cast<double>(numSamples));
    if (rms < 0.0015) {
        LOGI("nativeWhisperTranscribe: Silence detected (rms=%.5f), skipping", rms);
        return env->NewStringUTF("");
    }

#if HAVE_WHISPER_CPP
    LOGI("Memory Relay [WHISPER]: Loading %s for %d PCM samples...", pathStr.c_str(), static_cast<int>(numSamples));
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU-only for Exynos 7884

    whisper_context* wctx = whisper_init_from_file_with_params(pathStr.c_str(), cparams);
    if (wctx == nullptr) {
        LOGE("nativeWhisperTranscribe: Failed to load ggml-tiny.bin from %s", pathStr.c_str());
        return env->NewStringUTF("");
    }

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads = 4;               // 4 threads for Galaxy A10
    wparams.language = "auto";           // Bilingual Hindi + Hinglish + English support
    wparams.translate = false;
    wparams.no_context = true;
    wparams.single_segment = true;
    wparams.print_realtime = false;
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;

    std::string transcript;
    if (whisper_full(wctx, wparams, pcmf32.data(), static_cast<int>(pcmf32.size())) == 0) {
        const int n_segments = whisper_full_n_segments(wctx);
        for (int i = 0; i < n_segments; ++i) {
            const char* text = whisper_full_get_segment_text(wctx, i);
            if (text != nullptr) {
                transcript += text;
            }
        }
    } else {
        LOGE("nativeWhisperTranscribe: whisper_full failed");
    }

    // IMMEDIATELY free Whisper model from RAM before Llama loads
    whisper_free(wctx);
    wctx = nullptr;
    LOGI("Memory Relay [WHISPER]: Transcribed \"%s\" & freed ggml-tiny.bin from RAM", transcript.c_str());

    std::string cleaned = trimString(transcript);
    return env->NewStringUTF(cleaned.c_str());
#else
    LOGW("nativeWhisperTranscribe called before whisper.cpp was linked (rms=%.4f, model=%s)", rms, pathStr.c_str());
    return env->NewStringUTF("");
#endif
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_engine_LlamaBridge_nativeLoadModel(
        JNIEnv* env,
        jobject /* thiz */,
        jstring modelPath) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);

    if (modelPath == nullptr) {
        LOGE("nativeLoadModel received null modelPath");
        return JNI_FALSE;
    }

    const char* pathChars = env->GetStringUTFChars(modelPath, nullptr);
    std::string pathStr(pathChars ? pathChars : "");
    if (pathChars) {
        env->ReleaseStringUTFChars(modelPath, pathChars);
    }

#if HAVE_LLAMA_CPP
    if (g_model != nullptr && g_ctx != nullptr && g_loaded_model_path == pathStr) {
        LOGI("GGUF model already loaded in RAM: %s", pathStr.c_str());
        return JNI_TRUE;
    }

    freeLlamaInternalLocked();
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU-only for Exynos 7884
    model_params.use_mmap = true;
    model_params.use_mlock = false;

    LOGI("Memory Relay [LLAMA]: Loading GGUF model from %s (2 GB RAM tuned)...", pathStr.c_str());
    g_model = llama_model_load_from_file(pathStr.c_str(), model_params);
    if (g_model == nullptr) {
        LOGE("Failed to load GGUF model from %s", pathStr.c_str());
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 512;          // Strictly 512 context window for < 300 MB footprint
    ctx_params.n_batch = 128;        // 128 batch size tuned for Galaxy A10
    ctx_params.n_ubatch = 128;
    ctx_params.n_threads = 4;        // 4 threads (2x Cortex-A73 + 2x Cortex-A53)
    ctx_params.n_threads_batch = 4;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (g_ctx == nullptr) {
        LOGE("Failed to create llama_context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.10f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(42));

    g_loaded_model_path = pathStr;
    g_virtual_model_loaded = true;
    LOGI("SmolLM2 GGUF Brain online (n_ctx=512, n_threads=4, n_batch=128)");
    return JNI_TRUE;
#else
    if (!pathStr.empty() && verifyGgufHeader(pathStr)) {
        LOGI("Verified valid GGUF magic header at %s", pathStr.c_str());
    }
    g_loaded_model_path = pathStr;
    g_virtual_model_loaded = true;
    return JNI_TRUE;
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_engine_LlamaBridge_nativeInfer(
        JNIEnv* env,
        jobject /* thiz */,
        jstring userCommand) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);

    if (userCommand == nullptr) {
        return env->NewStringUTF("{\"action\":\"speak\",\"text\":\"Not understood\"}");
    }

    const char* cmdChars = env->GetStringUTFChars(userCommand, nullptr);
    std::string userStr(cmdChars ? cmdChars : "");
    if (cmdChars) {
        env->ReleaseStringUTFChars(userCommand, cmdChars);
    }

#if HAVE_LLAMA_CPP
    if (g_model == nullptr || g_ctx == nullptr) {
        LOGW("nativeInfer called while GGUF model is unloaded; falling back to C++ parser");
        std::string fallback = fallbackRuleBasedJson(userStr);
        return env->NewStringUTF(fallback.c_str());
    }

    std::string fullPrompt = std::string(kJarvisSystemPrompt) +
                             "<|im_start|>user\n" + userStr + "<|im_end|>\n"
                             "<|im_start|>assistant\n{";

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    llama_memory_clear(llama_get_memory(g_ctx), true);

    const int n_prompt_max = static_cast<int>(fullPrompt.size()) + 16;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(
            vocab,
            fullPrompt.c_str(),
            static_cast<int32_t>(fullPrompt.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            true);

    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
                vocab,
                fullPrompt.c_str(),
                static_cast<int32_t>(fullPrompt.size()),
                tokens.data(),
                static_cast<int32_t>(tokens.size()),
                true,
                true);
    }
    if (n_tokens <= 0) {
        std::string fallback = fallbackRuleBasedJson(userStr);
        return env->NewStringUTF(fallback.c_str());
    }
    tokens.resize(n_tokens);

    const int n_batch = 128;
    for (int i = 0; i < n_tokens; i += n_batch) {
        int cur_batch_size = std::min(n_batch, n_tokens - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, cur_batch_size);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode failed during prompt evaluation");
            std::string fallback = fallbackRuleBasedJson(userStr);
            return env->NewStringUTF(fallback.c_str());
        }
    }

    std::string generated = "{";
    const int max_new_tokens = 96;

    for (int step = 0; step < max_new_tokens; ++step) {
        llama_token new_token_id = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        char piece[128] = {0};
        int n_piece = llama_token_to_piece(vocab, new_token_id, piece, sizeof(piece) - 1, 0, true);
        if (n_piece > 0) {
            generated.append(piece, n_piece);
        }

        std::string extracted = extractJsonObject(generated);
        if (!extracted.empty()) {
            LOGI("GGUF JSON output: %s", extracted.c_str());
            return env->NewStringUTF(extracted.c_str());
        }

        llama_batch next_batch = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(g_ctx, next_batch) != 0) {
            break;
        }
    }

    std::string finalJson = extractJsonObject(generated);
    if (!finalJson.empty()) {
        return env->NewStringUTF(finalJson.c_str());
    }

    std::string fallback = fallbackRuleBasedJson(userStr);
    return env->NewStringUTF(fallback.c_str());
#else
    std::string result = fallbackRuleBasedJson(userStr);
    LOGI("JNI Fallback Infer: %s -> %s", userStr.c_str(), result.c_str());
    return env->NewStringUTF(result.c_str());
#endif
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_engine_LlamaBridge_nativeFreeModel(
        JNIEnv* /* env */,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    freeLlamaInternalLocked();
#if HAVE_LLAMA_CPP
    llama_backend_free();
    LOGI("Memory Relay [LLAMA]: GGUF model and context freed — ~300 MB RAM reclaimed (0 MB Idle)");
#else
    LOGI("nativeFreeModel invoked — Brain state reset to SLEEPING (0 MB Idle)");
#endif
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_engine_LlamaBridge_nativeIsModelLoaded(
        JNIEnv* /* env */,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
#if HAVE_LLAMA_CPP
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
#else
    return g_virtual_model_loaded ? JNI_TRUE : JNI_FALSE;
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_engine_LlamaBridge_nativeGetEngineInfo(
        JNIEnv* env,
        jobject /* thiz */) {
#if HAVE_LLAMA_CPP && HAVE_WHISPER_CPP
    return env->NewStringUTF("Memory Relay Active: Whisper.cpp + Llama.cpp (n_ctx=512, n_threads=4)");
#elif HAVE_LLAMA_CPP
    return env->NewStringUTF("Llama.cpp Active (Upload whisper.cpp/ for native Whisper Ear)");
#elif HAVE_WHISPER_CPP
    return env->NewStringUTF("Whisper.cpp Active (Upload llama.cpp/ for native GGUF Brain)");
#else
    return env->NewStringUTF("Memory Relay JNI Bridge Ready (Upload whisper.cpp/ & llama.cpp/)");
#endif
}
