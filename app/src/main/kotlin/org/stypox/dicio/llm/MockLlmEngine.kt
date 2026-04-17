package org.stypox.dicio.llm

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockLlmEngine @Inject constructor() : LlmInferenceEngine {
    override val isReady: Boolean = true

    override suspend fun generate(prompt: String): String =
        "Mock LLM response: $prompt"
}
