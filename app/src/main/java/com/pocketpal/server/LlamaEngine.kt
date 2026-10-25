package com.pocketpal.server

import android.util.Log

interface TokenCallback {
    fun onToken(token: String)
    fun onComplete()
}

object LlamaEngine {
    private const val TAG = "LlamaEngine"

    init {
        try {
            System.loadLibrary("c++_shared")
            System.loadLibrary("llama-jni")
            Log.i(TAG, "Native libraries loaded successfully!")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native libraries: ${e.message}")
        }
    }

    private external fun nativeInitBackend()
    private external fun nativeGetLoadedBackend(): String
    private external fun nativeLoadModel(
        modelPath: String,
        nThreads: Int,
        nCtx: Int,
        nGpuLayers: Int
    ): Boolean
    private external fun nativeGenerate(
        prompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        callback: TokenCallback
    )
    private external fun nativeStop()
    private external fun nativeFree()

    var isModelLoaded: Boolean = false
        private set
    var currentModelName: String = "none"
        private set
    var loadedBackendName: String = "detectando..."
        private set

    // Performance and reasoning flags
    var isThinkDisabled: Boolean = true
    var isStreamDisabled: Boolean = false

    private val inferenceLock = Any()

    fun initBackend() {
        try {
            nativeInitBackend()
            loadedBackendName = try {
                nativeGetLoadedBackend()
            } catch (_: Exception) {
                "Padrão"
            }
            Log.i(TAG, "Backend ativo: $loadedBackendName")
        } catch (e: Exception) {
            Log.e(TAG, "initBackend error: ${e.message}")
        }
    }

    fun loadModel(path: String, threads: Int = 4, contextSize: Int = 2048, gpuLayers: Int = 0): Boolean {
        synchronized(inferenceLock) {
            val success = try {
                nativeLoadModel(path, threads, contextSize, gpuLayers)
            } catch (e: Exception) {
                Log.e(TAG, "nativeLoadModel error: ${e.message}")
                false
            }
            isModelLoaded = success
            if (success) {
                currentModelName = path.substringAfterLast("/")
                loadedBackendName = try {
                    nativeGetLoadedBackend()
                } catch (_: Exception) {
                    "Ativo"
                }
            }
            return success
        }
    }

    fun stop() {
        try {
            nativeStop()
        } catch (e: Exception) {
            Log.e(TAG, "nativeStop error: ${e.message}")
        }
    }

    fun free() {
        synchronized(inferenceLock) {
            try {
                nativeFree()
            } catch (e: Exception) {
                Log.e(TAG, "nativeFree error: ${e.message}")
            }
            isModelLoaded = false
            currentModelName = "none"
        }
    }

    fun generate(
        prompt: String,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        maxTokens: Int = 512,
        callback: TokenCallback
    ) {
        synchronized(inferenceLock) {
            if (!isModelLoaded) {
                callback.onToken("Erro: Nenhum modelo carregado na memória.")
                callback.onComplete()
                return
            }
            try {
                nativeGenerate(prompt, temperature, topP, maxTokens, callback)
            } catch (e: Exception) {
                Log.e(TAG, "nativeGenerate error: ${e.message}")
                callback.onToken("\n[Erro de inferência: ${e.message}]")
                callback.onComplete()
            }
        }
    }

    /**
     * Formats OpenAI messages into ChatML format.
     * When disableThink is true:
     * - Bypasses reasoning monologues by pre-closing the <think> tag
     * - Adds instruction to answer directly
     */
    fun formatChatMessages(
        messages: List<ChatMessage>,
        disableThink: Boolean = isThinkDisabled
    ): String {
        val sb = StringBuilder()

        if (disableThink) {
            val hasSystem = messages.any { it.role.equals("system", ignoreCase = true) }
            if (!hasSystem) {
                sb.append("<|im_start|>system\n")
                sb.append("Responda de forma direta e concisa. Não use blocos de pensamento <think>.\n")
                sb.append("<|im_end|>\n")
            }
        }

        for (msg in messages) {
            sb.append("<|im_start|>").append(msg.role).append("\n")
            sb.append(msg.content.trim()).append("\n")
            sb.append("<|im_end|>\n")
        }

        sb.append("<|im_start|>assistant\n")

        // Pre-fill closed <think> tag for reasoning models (DeepSeek-R1, Qwen reasoning)
        // This causes the model to skip the multi-second reasoning phase completely!
        if (disableThink) {
            sb.append("<think>\n</think>\n")
        }

        return sb.toString()
    }
}
