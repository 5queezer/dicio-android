package org.stypox.dicio.llm

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.ui.util.Progress
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LlmModelDownloader"
private const val PROGRESS_EMIT_MIN_BYTES = 4L * 1024 * 1024
private const val LLM_MODELS_SUBDIR = "models"

@Singleton
class LlmModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val dataStore: DataStore<UserSettings>,
    private val registry: LlmModelRegistry,
) {
    private val cacheDir: File = context.cacheDir
    private val modelsDir: File = context.filesDir.resolve(LLM_MODELS_SUBDIR)
    private val totalRamGb: Int = LlmDeviceCapability.totalRamGb(context)

    private val selectionMutex = Mutex()
    @Volatile
    private var currentDescriptor: LlmModelDescriptor = resolveDescriptor("")
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<LlmModelDownloadState>(initialStateFor(currentDescriptor))
    val state: StateFlow<LlmModelDownloadState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            dataStore.data
                .map { it.selectedLlmModelId }
                .distinctUntilChanged()
                .collect { id -> applySelection(id) }
        }
    }

    fun currentDescriptor(): LlmModelDescriptor = currentDescriptor

    fun currentModelFile(): File = modelsDir.resolve(currentDescriptor.fileName)

    fun currentModelPathIfReady(): String? {
        val f = fileToDownloadFor(currentDescriptor)
        return if (f.needsToBeDownloaded()) null else f.file.absolutePath
    }

    private fun resolveDescriptor(id: String): LlmModelDescriptor {
        val byId = if (id.isNotEmpty()) registry.findById(id) else null
        return byId ?: registry.selectDefault(totalRamGb)
    }

    private fun fileToDownloadFor(descriptor: LlmModelDescriptor): FileToDownload =
        FileToDownload(descriptor.url, modelsDir.resolve(descriptor.fileName))

    private fun initialStateFor(descriptor: LlmModelDescriptor): LlmModelDownloadState =
        if (!fileToDownloadFor(descriptor).needsToBeDownloaded())
            LlmModelDownloadState.Downloaded
        else
            LlmModelDownloadState.NotDownloaded

    private suspend fun applySelection(id: String) = selectionMutex.withLock {
        val newDescriptor = resolveDescriptor(id)
        if (newDescriptor.id == currentDescriptor.id) return@withLock

        // cancel any in-flight download for the previous descriptor
        downloadJob?.cancel()
        downloadJob = null

        currentDescriptor = newDescriptor
        withContext(Dispatchers.IO) { pruneUnselectedFiles(newDescriptor) }
        _state.value = initialStateFor(newDescriptor)
    }

    private fun pruneUnselectedFiles(keep: FileToDownload) {
        val parent = keep.file.parentFile ?: return
        val keepNames = setOf(keep.file.name, keep.lastDownloadedUrlFile.name)
        parent.listFiles()?.forEach { f ->
            if (f.name !in keepNames &&
                (f.name.endsWith(".litertlm") || f.name.endsWith(".litertlm.url.txt"))) {
                f.delete()
            }
        }
    }

    private fun pruneUnselectedFiles(descriptor: LlmModelDescriptor) {
        pruneUnselectedFiles(fileToDownloadFor(descriptor))
    }

    suspend fun download() {
        when (_state.value) {
            LlmModelDownloadState.Downloaded -> return
            is LlmModelDownloadState.Downloading -> return
            else -> {}
        }

        val descriptor = currentDescriptor
        val fileToDownload = fileToDownloadFor(descriptor)

        _state.value = LlmModelDownloadState.Downloading(Progress.UNKNOWN)

        val job = scope.launch {
            try {
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
                        if (currentDescriptor.id == descriptor.id) {
                            _state.value = LlmModelDownloadState.Downloading(progress)
                        }
                    }
                }
                if (currentDescriptor.id == descriptor.id) {
                    _state.value = LlmModelDownloadState.Downloaded
                }
            } catch (ce: CancellationException) {
                // settle to a resting state so the flow isn't stuck on Downloading
                if (currentDescriptor.id == descriptor.id) {
                    _state.value = initialStateFor(descriptor)
                }
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "Can't download LLM model", t)
                if (currentDescriptor.id == descriptor.id) {
                    _state.value = LlmModelDownloadState.ErrorDownloading(t)
                }
            }
        }
        downloadJob = job
        job.join()
    }
}
