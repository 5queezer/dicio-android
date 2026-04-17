package org.stypox.dicio.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
        val file = File(
            context.filesDir,
            "${LlmModelDownloader.MODELS_SUBDIR}/${LlmModelDownloader.MODEL_FILENAME}",
        )
        // only return path if file is fully downloaded — partial/corrupt file would crash RealLlmEngine
        return if (file.exists() && file.length() == LlmModelDownloader.EXPECTED_SIZE_BYTES) {
            file.absolutePath
        } else {
            null
        }
    }
}
