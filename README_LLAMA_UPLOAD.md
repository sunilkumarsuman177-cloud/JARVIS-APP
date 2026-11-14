# JARVIS — `llama.cpp` + `whisper.cpp` NDK Memory Relay Guide
**Engineered by Prince Priyadarshi**

To enable the full C++ NDK **Memory Relay Pipeline** (`Vosk Wake -> Whisper.cpp Transcribe -> Llama.cpp Execute`) on the **Samsung Galaxy A10 (2 GB RAM, 32-bit `armeabi-v7a` & 64-bit `arm64-v8a`)**, upload `llama.cpp` and `whisper.cpp` into the exact directory tree below:

## Exact Directory Structure Inside `app/src/main/cpp/`

```text
/ (Workspace Root)
└── app/
    └── src/
        └── main/
            └── cpp/
                ├── CMakeLists.txt                  <-- (Auto-isolates & shares ggml between whisper & llama)
                ├── native-lib.cpp                  <-- (JNI Bridge: nativeWhisperTranscribe + nativeInfer)
                ├── llama.cpp/                      <-- 1. UPLOAD llama.cpp HERE
                │   ├── CMakeLists.txt              <-- Required: root CMakeLists.txt of llama.cpp
                │   ├── ggml/                       <-- Required: shared ggml tensor library
                │   │   ├── CMakeLists.txt
                │   │   ├── include/
                │   │   │   ├── ggml.h
                │   │   │   ├── ggml-alloc.h
                │   │   │   ├── ggml-backend.h
                │   │   │   └── ggml-cpu.h
                │   │   └── src/
                │   ├── include/
                │   │   └── llama.h                 <-- Required: public llama.h header
                │   ├── src/
                │   │   ├── CMakeLists.txt
                │   │   └── llama.cpp ...
                │   └── common/
                └── whisper.cpp/                    <-- 2. UPLOAD whisper.cpp HERE
                    ├── CMakeLists.txt              <-- Root CMakeLists.txt of whisper.cpp
                    ├── include/
                    │   └── whisper.h               <-- Required: public whisper.h header
                    ├── src/
                    │   ├── whisper.cpp             <-- Required: core Whisper implementation
                    │   └── whisper-arch.h          <-- Required if present in your whisper.cpp version
                    └── ggml/                       <-- Optional (CMake automatically shares llama.cpp/ggml!)
```

## How `CMakeLists.txt` Prevents `ggml` Duplicate Symbol Crashes
1. **Shared `ggml` Object Reuse:** When both `llama.cpp` and `whisper.cpp` are present, `CMakeLists.txt` compiles `ggml` (`ggml`, `ggml-base`, `ggml-cpu`) **once** via `llama.cpp`, then compiles `whisper.cpp/src/whisper.cpp` as an isolated static target (`whisper-isolated`) linked directly against the already-compiled `ggml` targets. This prevents both CMake `add_library(ggml)` target collisions and linker duplicate symbol crashes.
2. **Hidden Symbol Visibility (`-fvisibility=hidden` + `-Wl,--exclude-libs,ALL`):** All internal C/C++ symbols in `ggml`, `whisper`, and `llama` are hidden so only the `JNIEXPORT` functions in `native-lib.cpp` are exported in `libjarvis-native.so`.
3. **Strict 2 GB RAM Memory Relay:**
   - **State 1 (`SLEEPING`):** Vosk listens offline (`< 45 MB`). Both Whisper and Llama are unloaded (`0 MB`).
   - **State 2 (`RECORDING`):** Vosk pauses (`speechService.setPause(true)`), and `AudioRecord` captures 5 seconds of 16kHz Mono 16-bit PCM audio (`ShortArray(80000)`).
   - **State 3 (`TRANSCRIBING`):** `nativeWhisperTranscribe` loads `ggml-tiny.bin`, transcribes the 5-second PCM buffer, and **immediately calls `whisper_free(ctx)`** before returning.
   - **State 4 (`EXECUTING`):** `nativeInfer` loads `SmolLM2-360M-Instruct-Q4_K_M.gguf`, generates the compact action JSON, and **immediately calls `llama_model_free()`** before `PhoneOrchestrator` executes the action and unpauses Vosk.
