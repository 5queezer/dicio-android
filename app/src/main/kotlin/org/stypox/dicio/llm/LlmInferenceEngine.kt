package org.stypox.dicio.llm

interface LlmInferenceEngine {
    suspend fun generate(prompt: String): String
    val isReady: Boolean
    suspend fun close()
}
