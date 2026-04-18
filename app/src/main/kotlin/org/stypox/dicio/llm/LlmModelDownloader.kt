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
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LlmModelDownloader"
private const val PROGRESS_EMIT_MIN_BYTES = 4L * 1024 * 1024

const val LLM_MODEL_URL =
    "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/9695417f248178c63a9f318c6e0c56cb917cb837/gemma-4-E4B-it.litertlm"
const val LLM_MODEL_FILENAME = "gemma-4-E4B-it.litertlm"
const val LLM_MODELS_SUBDIR = "models"

internal fun llmModelFile(context: Context): File =
    context.filesDir.resolve(LLM_MODELS_SUBDIR).resolve(LLM_MODEL_FILENAME)

internal fun llmFileToDownload(context: Context): FileToDownload =
    FileToDownload(LLM_MODEL_URL, llmModelFile(context))

@Singleton
class LlmModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val fileToDownload = llmFileToDownload(context)
    private val cacheDir: File = context.cacheDir

    private val _state = MutableStateFlow<LlmModelDownloadState>(initialState())
    val state: StateFlow<LlmModelDownloadState> = _state.asStateFlow()

    private fun initialState(): LlmModelDownloadState =
        if (!fileToDownload.needsToBeDownloaded()) LlmModelDownloadState.Downloaded
        else LlmModelDownloadState.NotDownloaded

    suspend fun download() {
        when (_state.value) {
            LlmModelDownloadState.Downloaded -> return
            is LlmModelDownloadState.Downloading -> return
            else -> {}
        }

        _state.value = LlmModelDownloadState.Downloading(Progress.UNKNOWN)

        try {
            withContext(Dispatchers.IO) {
                fileToDownload.file.parentFile?.mkdirs()
                var lastEmit = 0L
                downloadBinaryFilesWithPartial(
                    urlsFiles = listOf(fileToDownload),
                    httpClient = okHttpClient,
                    cacheDir = cacheDir,
                ) { progress ->
                    // Throttle: a 3.4 GB download at 256 KB chunks would otherwise emit ~14k
                    // states, each allocating a fresh Progress + triggering Compose recomposition.
                    if (progress.currentBytes - lastEmit >= PROGRESS_EMIT_MIN_BYTES ||
                        progress.currentBytes == progress.totalBytes) {
                        lastEmit = progress.currentBytes
                        _state.value = LlmModelDownloadState.Downloading(progress)
                    }
                }
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
}
