package org.stypox.dicio.llm

import kotlinx.serialization.Serializable

@Serializable
data class LlmModelDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val url: String,
    val fileName: String,
    val sizeInBytes: Long,
    val minDeviceMemoryInGb: Int,
)
