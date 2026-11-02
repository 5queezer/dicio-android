package org.stypox.dicio.llm

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
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "RealLlmEngine"

// Backend.GPU() needs play-services-tflite-gpu for OpenCL, which is a non-goal on GrapheneOS —
// the sampler fails at inference time, after initialize() returns. CPU only.

@Singleton
class RealLlmEngine @Inject constructor(
    private val modelProvider: LlmModelProvider,
    private val dicioTools: DicioTools,
) : LlmInferenceEngine {

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var lastUse = 0L

    override val isReady: Boolean
        get() = conversation != null

    private fun initialize() {
        val modelPath = modelProvider.getModelPath()
            ?: throw IllegalStateException("LLM model not downloaded")

        val newEngine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                maxNumTokens = 4096,
                cacheDir = null,
            )
        ).also { it.initialize() }

        conversation = newConversation(newEngine)
        engine = newEngine
    }

    private fun newConversation(engine: Engine) = engine.createConversation(
        ConversationConfig(
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = LLM_TEMPERATURE),
            systemInstruction = Contents.of(listOf(Content.Text(LLM_SYSTEM_INSTRUCTION))),
            tools = listOf(tool(dicioTools)),
        )
    )

    override suspend fun generate(prompt: String): String = mutex.withLock {
        if (conversation == null) {
            initialize()
        } else if (System.currentTimeMillis() - lastUse > LLM_CONVERSATION_IDLE_MS) {
            conversation?.close()
            conversation = newConversation(engine!!)
        }
        lastUse = System.currentTimeMillis()
        val conv = conversation ?: error("Conversation is null after initialize()")

        try {
            generateIn(conv, prompt)
        } catch (e: Throwable) {
            // e.g. the conversation exceeded maxNumTokens: start over instead of failing until idle
            conv.close()
            conversation = newConversation(engine!!)
            throw e
        }
    }

    private suspend fun generateIn(conv: Conversation, prompt: String): String =
        suspendCancellableCoroutine { cont ->
            val accumulated = StringBuilder()
            conv.sendMessageAsync(
                Contents.of(listOf(Content.Text(llmUserMessage(prompt)))),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        // message.toString() is a per-token delta on the main channel;
                        // thinking-mode tokens arrive on channels["thought"] — drop those.
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

    override suspend fun close() = mutex.withLock {
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
    }
}
