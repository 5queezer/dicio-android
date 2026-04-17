package org.stypox.dicio.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class RealLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelProvider: LlmModelProvider,
) : LlmInferenceEngine {

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    @Volatile
    private var ready: Boolean = false

    override val isReady: Boolean
        get() = ready

    private fun buildEngineConfig(modelPath: String, backend: Backend) = EngineConfig(
        modelPath = modelPath,
        backend = backend,
        maxNumTokens = 4096,
        cacheDir = null,
    )

    private fun initialize() {
        val modelPath = modelProvider.getModelPath()
            ?: throw IllegalStateException("LLM model not downloaded")

        // Backend.GPU() needs play-services-tflite-gpu for OpenCL, which is a non-goal
        // on GrapheneOS — the sampler fails at inference time, after initialize() returns.
        // Stay on CPU unconditionally.
        val newEngine = Engine(buildEngineConfig(modelPath, Backend.CPU()))
            .also { it.initialize() }

        val newConversation = newEngine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                systemInstruction = null,
                tools = listOf(),
            )
        )

        engine = newEngine
        conversation = newConversation
        ready = true
    }

    override suspend fun generate(prompt: String): String = mutex.withLock {
        if (!ready) {
            initialize()
        }
        val conv = conversation ?: error("Conversation is null after initialize()")

        suspendCancellableCoroutine { cont ->
            val accumulated = StringBuilder()
            conv.sendMessageAsync(
                Contents.of(listOf(Content.Text(prompt))),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        // message.toString() is a per-token delta on the main channel.
                        // Thinking-mode output would arrive on channels["thought"] — ignore it.
                        if (message.channels["thought"] == null) {
                            accumulated.append(message.toString())
                        }
                    }

                    override fun onDone() {
                        if (cont.isActive) cont.resume(accumulated.toString())
                    }

                    override fun onError(throwable: Throwable) {
                        if (cont.isActive) cont.resumeWithException(throwable)
                    }
                },
                emptyMap(),
            )
            cont.invokeOnCancellation {
                try {
                    conv.cancelProcess()
                } catch (t: Throwable) {
                    Log.w(TAG, "Error cancelling LLM generation", t)
                }
            }
        }
    }

    suspend fun close() = mutex.withLock {
        try {
            conversation?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing conversation", t)
        }
        try {
            engine?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing engine", t)
        }
        conversation = null
        engine = null
        ready = false
    }

    companion object {
        private const val TAG = "RealLlmEngine"
    }
}
