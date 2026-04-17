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
        val file = File(context.filesDir, "models/gemma-4-e4b-it.litertlm")
        return if (file.exists()) file.absolutePath else null
    }
}
