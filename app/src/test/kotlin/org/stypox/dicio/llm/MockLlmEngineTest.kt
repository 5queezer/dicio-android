package org.stypox.dicio.llm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MockLlmEngineTest : StringSpec({
    "isReady is true" {
        MockLlmEngine().isReady shouldBe true
    }

    "generate echoes the prompt with canned prefix" {
        MockLlmEngine().generate("hello world") shouldBe "Mock LLM response: hello world"
    }
})
