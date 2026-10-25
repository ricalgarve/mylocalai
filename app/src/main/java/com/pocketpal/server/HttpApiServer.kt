package com.pocketpal.server

import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets

class HttpApiServer(
    port: Int,
    private val onLog: (String) -> Unit
) : NanoHTTPD("0.0.0.0", port) {

    private val gson = Gson()
    private val TAG = "HttpApiServer"

    init {
        LlamaEngine.initBackend()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        onLog("${method.name} $uri")

        // Handle CORS preflight
        if (method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCorsHeaders(response)
            return response
        }

        return try {
            when {
                uri == "/v1/models" && method == Method.GET -> handleGetModels()
                uri == "/v1/chat/completions" && method == Method.POST -> handleChatCompletions(session)
                uri == "/v1/completions" && method == Method.POST -> handleCompletions(session)
                uri == "/health" || uri == "/" -> handleHealth()
                else -> {
                    val resp = newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\": \"Endpoint não encontrado\"}")
                    addCorsHeaders(resp)
                    resp
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            onLog("Erro 500: ${e.message}")
            val resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\": \"${e.message}\"}")
            addCorsHeaders(resp)
            resp
        }
    }

    private fun handleHealth(): Response {
        val statusJson = """
            {
                "status": "online",
                "app": "PocketPal AI Server",
                "backend": "${LlamaEngine.loadedBackendName}",
                "model_loaded": ${LlamaEngine.isModelLoaded},
                "current_model": "${LlamaEngine.currentModelName}",
                "think_disabled": ${LlamaEngine.isThinkDisabled},
                "stream_disabled": ${LlamaEngine.isStreamDisabled}
            }
        """.trimIndent()
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", statusJson)
        addCorsHeaders(resp)
        return resp
    }

    private fun handleGetModels(): Response {
        val modelName = if (LlamaEngine.isModelLoaded) LlamaEngine.currentModelName else "none"
        val responseObj = ModelListResponse(
            data = listOf(ModelItem(id = modelName))
        )
        val json = gson.toJson(responseObj)
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        addCorsHeaders(resp)
        return resp
    }

    private fun handleChatCompletions(session: IHTTPSession): Response {
        val body = getRequestBody(session)
        val req = gson.fromJson(body, ChatCompletionRequest::class.java)

        if (!LlamaEngine.isModelLoaded) {
            val resp = newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                "{\"error\": \"Nenhum modelo GGUF carregado no servidor.\"}"
            )
            addCorsHeaders(resp)
            return resp
        }

        val prompt = LlamaEngine.formatChatMessages(req.messages, disableThink = LlamaEngine.isThinkDisabled)
        val isStream = if (LlamaEngine.isStreamDisabled) false else (req.stream ?: false)

        return if (isStream) {
            handleStreamingCompletion(prompt, req)
        } else {
            handleSyncCompletion(prompt, req)
        }
    }

    private fun handleCompletions(session: IHTTPSession): Response {
        val body = getRequestBody(session)
        val map = gson.fromJson(body, Map::class.java)
        val prompt = map["prompt"]?.toString() ?: ""
        val reqStream = map["stream"] as? Boolean ?: false
        val isStream = if (LlamaEngine.isStreamDisabled) false else reqStream

        val req = ChatCompletionRequest(
            temperature = (map["temperature"] as? Number)?.toFloat() ?: 0.7f,
            topP = (map["top_p"] as? Number)?.toFloat() ?: 0.9f,
            maxTokens = (map["max_tokens"] as? Number)?.toInt() ?: 512
        )

        return if (isStream) {
            handleStreamingCompletion(prompt, req)
        } else {
            handleSyncCompletion(prompt, req)
        }
    }

    private fun handleSyncCompletion(prompt: String, req: ChatCompletionRequest): Response {
        val sb = StringBuilder()
        var tokenCount = 0
        val startTime = System.currentTimeMillis()

        LlamaEngine.generate(
            prompt = prompt,
            temperature = req.temperature ?: 0.7f,
            topP = req.topP ?: 0.9f,
            maxTokens = req.maxTokens ?: 512,
            callback = object : TokenCallback {
                override fun onToken(token: String) {
                    sb.append(token)
                    tokenCount++
                }
                override fun onComplete() {}
            }
        )

        val durationSec = (System.currentTimeMillis() - startTime) / 1000.0
        val tokPerSec = if (durationSec > 0) String.format("%.1f", tokenCount / durationSec) else "0"
        onLog("Resposta completa gerada: $tokenCount tokens em ${durationSec}s ($tokPerSec tok/s)")

        val responseObj = ChatCompletionResponse(
            model = LlamaEngine.currentModelName,
            choices = listOf(
                ChatCompletionChoice(
                    message = ChatMessage(role = "assistant", content = sb.toString())
                )
            ),
            usage = Usage(
                promptTokens = 0,
                completionTokens = tokenCount,
                totalTokens = tokenCount
            )
        )

        val json = gson.toJson(responseObj)
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        addCorsHeaders(resp)
        return resp
    }

    private fun handleStreamingCompletion(prompt: String, req: ChatCompletionRequest): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 4096)

        // Launch inference thread to write SSE events into pipedOut
        Thread {
            try {
                var tokenCount = 0
                val startTime = System.currentTimeMillis()

                LlamaEngine.generate(
                    prompt = prompt,
                    temperature = req.temperature ?: 0.7f,
                    topP = req.topP ?: 0.9f,
                    maxTokens = req.maxTokens ?: 512,
                    callback = object : TokenCallback {
                        override fun onToken(token: String) {
                            tokenCount++
                            val chunk = ChatCompletionChunk(
                                model = LlamaEngine.currentModelName,
                                choices = listOf(
                                    ChatCompletionChunkChoice(
                                        delta = DeltaMessage(content = token)
                                    )
                                )
                            )
                            val sseData = "data: ${gson.toJson(chunk)}\n\n"
                            pipedOut.write(sseData.toByteArray(StandardCharsets.UTF_8))
                            pipedOut.flush()
                        }

                        override fun onComplete() {
                            val done = "data: [DONE]\n\n"
                            pipedOut.write(done.toByteArray(StandardCharsets.UTF_8))
                            pipedOut.flush()
                            pipedOut.close()

                            val durationSec = (System.currentTimeMillis() - startTime) / 1000.0
                            val tokPerSec = if (durationSec > 0) String.format("%.1f", tokenCount / durationSec) else "0"
                            onLog("Streaming concluído: $tokenCount tokens ($tokPerSec tok/s)")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Streaming error", e)
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.start()

        val resp = newChunkedResponse(Response.Status.OK, "text/event-stream", pipedIn)
        addCorsHeaders(resp)
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("Connection", "keep-alive")
        return resp
    }

    private fun getRequestBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With")
    }
}
