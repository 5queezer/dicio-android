package org.stypox.dicio.llm

import org.stypox.dicio.ui.util.Progress

sealed interface LlmModelDownloadState {

    data object NotDownloaded : LlmModelDownloadState

    data class Downloading(
        val progress: Progress,
    ) : LlmModelDownloadState

    data object Downloaded : LlmModelDownloadState

    data class ErrorDownloading(
        val throwable: Throwable,
    ) : LlmModelDownloadState
}
