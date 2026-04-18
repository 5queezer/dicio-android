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
private const val SYSTEM_INSTRUCTION =
    "You are a voice assistant. " +
    "When the user asks you to perform an action that matches one of your available tools " +
    "(for example starting a timer), call the tool IMMEDIATELY — do NOT produce any text " +
    "before the tool call. " +
    "After the tool response arrives, reply with exactly one short confirmation sentence " +
    "in the same language as the user's request (e.g. \"Timer für 3 Minuten gestartet.\" " +
    "on success, or a brief explanation of the problem on failure). Never repeat yourself " +
    "and never end your turn silently after a tool call. " +
    "Only answer in words without calling a tool when no tool applies or the request is " +
    "purely informational. Keep textual answers to at most two short sentences, in the " +
    "same language as the user. " +
    "Use plain text only — no markdown, no bold, no bullet points, no headings, no code."

@Singleton
class RealLlmEngine @Inject constructor(
    private val modelProvider: LlmModelProvider,
    private val dicioTools: DicioTools,
) : LlmInferenceEngine {

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null

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

        conversation = newEngine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                systemInstruction = Contents.of(listOf(Content.Text(SYSTEM_INSTRUCTION))),
                tools = listOf(tool(dicioTools)),
            )
        )
        engine = newEngine
    }

    override suspend fun generate(prompt: String): String = mutex.withLock {
        if (conversation == null) {
            initialize()
        }
        val conv = conversation ?: error("Conversation is null after initialize()")

        suspendCancellableCoroutine { cont ->
            val accumulated = StringBuilder()
            conv.sendMessageAsync(
                Contents.of(listOf(Content.Text(prompt))),
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
