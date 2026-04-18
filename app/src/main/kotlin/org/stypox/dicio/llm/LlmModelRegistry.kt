package org.stypox.dicio.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val ASSET_FILE = "llm_models.json"
private val JSON = Json { ignoreUnknownKeys = true }

@Singleton
class LlmModelRegistry @Inject constructor(
    @ApplicationContext context: Context,
) {
    val models: List<LlmModelDescriptor> = context.assets.open(ASSET_FILE)
        .use { it.bufferedReader().readText() }
        .let { JSON.decodeFromString(it) }

    init {
        require(models.isNotEmpty()) { "llm_models.json must contain at least one entry" }
    }

    // Picks the first entry that fits the device, relying on JSON order (descending RAM).
    // Falls back to the smallest entry if none fit.
    fun selectDefault(totalMemoryGb: Int): LlmModelDescriptor {
        return models.firstOrNull { it.minDeviceMemoryInGb <= totalMemoryGb }
            ?: models.minBy { it.minDeviceMemoryInGb }
    }

    fun findById(id: String): LlmModelDescriptor? = models.firstOrNull { it.id == id }
}
