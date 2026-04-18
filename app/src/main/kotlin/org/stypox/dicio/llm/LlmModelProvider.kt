package org.stypox.dicio.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface LlmModelProvider {
    fun getModelPath(): String?
}

@Singleton
class DefaultLlmModelProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : LlmModelProvider {
    override fun getModelPath(): String? {
        val download = llmFileToDownload(context)
        // needsToBeDownloaded() checks the URL-stamp written after the atomic rename in
        // BinaryFileDownloader, so a half-written file (no stamp) is correctly rejected.
        return if (download.needsToBeDownloaded()) null else download.file.absolutePath
    }
}
