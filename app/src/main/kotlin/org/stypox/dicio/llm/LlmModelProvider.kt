package org.stypox.dicio.llm

import javax.inject.Inject
import javax.inject.Singleton

interface LlmModelProvider {
    fun getModelPath(): String?
}

@Singleton
class DefaultLlmModelProvider @Inject constructor(
    private val downloader: LlmModelDownloader,
) : LlmModelProvider {
    override fun getModelPath(): String? = downloader.currentModelPathIfReady()
}
