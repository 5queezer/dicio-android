package org.stypox.dicio.llm

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.stypox.dicio.ui.util.Progress
import org.stypox.dicio.util.downloadBinaryFileWithPartial
import org.stypox.dicio.util.getResponse
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmModelDownloader @Inject constructor(
    @ApplicationContext context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val modelFile: File =
        context.filesDir.resolve(MODELS_SUBDIR).resolve(MODEL_FILENAME)
    private val cacheDir: File = context.cacheDir

    private val _state = MutableStateFlow<LlmModelDownloadState>(initialState())
    val state: StateFlow<LlmModelDownloadState> = _state.asStateFlow()

    private fun initialState(): LlmModelDownloadState =
        if (modelFile.exists() && modelFile.length() == EXPECTED_SIZE_BYTES) {
            LlmModelDownloadState.Downloaded
        } else {
            LlmModelDownloadState.NotDownloaded
        }

    suspend fun download() {
        when (_state.value) {
            LlmModelDownloadState.Downloaded -> return
            is LlmModelDownloadState.Downloading -> return
            else -> {}
        }

        _state.value = LlmModelDownloadState.Downloading(Progress.UNKNOWN)

        try {
            withContext(Dispatchers.IO) {
                modelFile.parentFile?.mkdirs()
                downloadBinaryFileWithPartial(
                    response = okHttpClient.getResponse(MODEL_URL),
                    file = modelFile,
                    cacheDir = cacheDir,
                ) { currentBytes, totalBytes ->
                    _state.value = LlmModelDownloadState.Downloading(
                        Progress(0, 1, currentBytes, totalBytes)
                    )
                }
            }

            if (modelFile.length() != EXPECTED_SIZE_BYTES) {
                modelFile.delete()
                _state.value = LlmModelDownloadState.ErrorDownloading(
                    IOException("Downloaded file size mismatch")
                )
                return
            }

            _state.value = LlmModelDownloadState.Downloaded
        } catch (ce: CancellationException) {
            // settle to a resting state so the flow isn't stuck on Downloading
            _state.value = initialState()
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Can't download LLM model", t)
            _state.value = LlmModelDownloadState.ErrorDownloading(t)
        }
    }

    companion object {
        private const val TAG = "LlmModelDownloader"

        const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/9695417f248178c63a9f318c6e0c56cb917cb837/gemma-4-E4B-it.litertlm"
        const val EXPECTED_SIZE_BYTES = 3_654_467_584L
        const val MODEL_FILENAME = "gemma-4-E4B-it.litertlm"
        const val MODELS_SUBDIR = "models"
    }
}
