package com.pocketpal.server

import com.google.gson.annotations.SerializedName

data class ChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class ChatCompletionRequest(
    @SerializedName("model") val model: String? = null,
    @SerializedName("messages") val messages: List<ChatMessage> = emptyList(),
    @SerializedName("stream") val stream: Boolean? = false,
    @SerializedName("temperature") val temperature: Float? = 0.7f,
    @SerializedName("top_p") val topP: Float? = 0.9f,
    @SerializedName("max_tokens") val maxTokens: Int? = 512
)

// Response for stream = false
data class ChatCompletionChoice(
    @SerializedName("index") val index: Int = 0,
    @SerializedName("message") val message: ChatMessage,
    @SerializedName("finish_reason") val finishReason: String = "stop"
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int = 0,
    @SerializedName("completion_tokens") val completionTokens: Int = 0,
    @SerializedName("total_tokens") val totalTokens: Int = 0
)

data class ChatCompletionResponse(
    @SerializedName("id") val id: String = "chatcmpl-" + System.currentTimeMillis(),
    @SerializedName("object") val obj: String = "chat.completion",
    @SerializedName("created") val created: Long = System.currentTimeMillis() / 1000,
    @SerializedName("model") val model: String = "local-llama",
    @SerializedName("choices") val choices: List<ChatCompletionChoice>,
    @SerializedName("usage") val usage: Usage = Usage()
)

// Response for stream = true (SSE Chunks)
data class DeltaMessage(
    @SerializedName("role") val role: String? = null,
    @SerializedName("content") val content: String? = null
)

data class ChatCompletionChunkChoice(
    @SerializedName("index") val index: Int = 0,
    @SerializedName("delta") val delta: DeltaMessage,
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class ChatCompletionChunk(
    @SerializedName("id") val id: String = "chatcmpl-" + System.currentTimeMillis(),
    @SerializedName("object") val obj: String = "chat.completion.chunk",
    @SerializedName("created") val created: Long = System.currentTimeMillis() / 1000,
    @SerializedName("model") val model: String = "local-llama",
    @SerializedName("choices") val choices: List<ChatCompletionChunkChoice>
)

// Model listing
data class ModelItem(
    @SerializedName("id") val id: String,
    @SerializedName("object") val obj: String = "model",
    @SerializedName("created") val created: Long = System.currentTimeMillis() / 1000,
    @SerializedName("owned_by") val ownedBy: String = "local"
)

data class ModelListResponse(
    @SerializedName("object") val obj: String = "list",
    @SerializedName("data") val data: List<ModelItem>
)
