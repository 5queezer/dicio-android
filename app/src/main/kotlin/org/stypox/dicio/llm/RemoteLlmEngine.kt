package org.stypox.dicio.llm

import android.util.Log
import androidx.datastore.core.DataStore
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.stypox.dicio.settings.datastore.UserSettings
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation

private const val TAG = "RemoteLlmEngine"
private const val MAX_TOOL_ROUNDS = 4
private const val MAX_HISTORY_TURNS = 6

/**
 * Runs the LLM fallback on an OpenAI-compatible server (`llm_remote_url`, e.g. Ollama or
 * llama-server on the home network) with the same system instruction and tools as [RealLlmEngine].
 */
@Singleton
class RemoteLlmEngine @Inject constructor(
    okHttpClient: OkHttpClient,
    private val dataStore: DataStore<UserSettings>,
    private val dicioTools: DicioTools,
) : LlmInferenceEngine {

    private val client = okHttpClient.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    // the @Tool methods of DicioTools, described in the OpenAI function format; lazy, since kotlin-reflect
    // takes seconds on first use and this engine is built at app start
    private val tools: Map<String, KFunction<*>> by lazy {
        DicioTools::class.declaredMemberFunctions
            .filter { it.findAnnotation<Tool>() != null }
            .associateBy { it.name }
    }
    private val toolsJson by lazy {
        JSONArray().apply {
            for ((name, function) in tools) {
                val properties = JSONObject()
                val required = JSONArray()
                for (param in function.parameters.filter { it.kind == KParameter.Kind.VALUE }) {
                    properties.put(
                        param.name, JSONObject().put("type", jsonType(param))
                            .put("description", param.findAnnotation<ToolParam>()?.description.orEmpty())
                    )
                    if (!param.isOptional) required.put(param.name)
                }
                put(
                    JSONObject().put("type", "function").put(
                        "function", JSONObject().put("name", name)
                            .put("description", function.findAnnotation<Tool>()!!.description)
                            .put("parameters", JSONObject().put("type", "object")
                                .put("properties", properties).put("required", required))
                    )
                )
            }
        }
    }
    private val mutex = Mutex()
    private var messages = newConversation()
    private var lastUse = 0L

    override val isReady: Boolean
        get() = true

    override suspend fun generate(prompt: String): String = mutex.withLock {
        val settings = dataStore.data.first()
        val url = settings.llmRemoteUrl.trimEnd('/') + "/chat/completions"
        Log.d(TAG, "generate model=${settings.llmRemoteModel} prompt=$prompt")
        if (System.currentTimeMillis() - lastUse > LLM_CONVERSATION_IDLE_MS) {
            messages = newConversation()
        }
        lastUse = System.currentTimeMillis()
        val start = messages.length()
        try {
            messages.put(JSONObject().put("role", "user").put("content", llmUserMessage(prompt)))
            for (round in 0..MAX_TOOL_ROUNDS) {
                // the last round offers no tools, so a model that keeps calling them has to answer
                val message = complete(url, settings.llmRemoteModel, tools = round < MAX_TOOL_ROUNDS)
                messages.put(message)
                val calls = message.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) {
                    val text = if (message.isNull("content")) "" else message.getString("content")
                    Log.d(TAG, "reply $text")
                    trimHistory()
                    return@withLock text
                }
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    messages.put(
                        JSONObject().put("role", "tool").put("tool_call_id", call.optString("id"))
                            .put("content", runTool(call))
                    )
                }
            }
            error("no answer after $MAX_TOOL_ROUNDS tool rounds")
        } catch (e: Throwable) {
            // a failed turn must not stay in the history, or the next request would resend it
            while (messages.length() > start) {
                messages.remove(messages.length() - 1)
            }
            throw e
        }
    }

    private suspend fun complete(url: String, model: String, tools: Boolean): JSONObject {
        val body = JSONObject()
            .put("messages", messages)
            .put("temperature", LLM_TEMPERATURE)
            .put("top_p", 0.95)
        if (model.isNotBlank()) body.put("model", model)  // servers with one model ignore or reject ""
        if (tools) body.put("tools", toolsJson)
        val request = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("LLM server answered ${response.code}: ${text.take(300)}")
                }
                JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            }
        }
    }

    private fun runTool(call: JSONObject): String {
        val result = try {
            val function = call.getJSONObject("function")
            val name = function.getString("name")
            // arguments is a JSON string per the OpenAI API; some servers send an object
            val args = function.opt("arguments").let { if (it is JSONObject) it.toString() else it as? String }
            Log.d(TAG, "tool_call $name $args")
            val tool = tools[name] ?: error("unknown tool $name")
            val json = JSONObject(args?.ifBlank { null } ?: "{}")
            val values = mutableMapOf<KParameter, Any?>(tool.parameters[0] to dicioTools)
            for (param in tool.parameters.filter { it.kind == KParameter.Kind.VALUE }) {
                if (json.has(param.name) && !json.isNull(param.name)) {
                    values[param] = when (jsonType(param)) {
                        "integer" -> json.getInt(param.name!!)
                        "number" -> json.getDouble(param.name!!)
                        "boolean" -> json.getBoolean(param.name!!)
                        else -> json.getString(param.name!!)
                    }
                } else if (!param.isOptional) {
                    error("missing argument ${param.name}")
                }
            }
            JSONObject(tool.callBy(values) as Map<*, *>).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Tool call $call failed", e)
            JSONObject().put("result", "error").put("reason", e.message ?: e.javaClass.simpleName).toString()
        }
        Log.d(TAG, "tool_result $result")
        return result
    }

    // keeps the system message and the last MAX_HISTORY_TURNS user turns with their answers
    private fun trimHistory() {
        val users = (0 until messages.length()).filter { messages.getJSONObject(it).optString("role") == "user" }
        if (users.size <= MAX_HISTORY_TURNS) return
        val kept = JSONArray().put(messages.get(0))
        for (i in users[users.size - MAX_HISTORY_TURNS] until messages.length()) {
            kept.put(messages.get(i))
        }
        messages = kept
    }

    private fun jsonType(param: KParameter) = when (param.type.classifier) {
        Int::class, Long::class -> "integer"
        Float::class, Double::class -> "number"
        Boolean::class -> "boolean"
        else -> "string"
    }

    private fun newConversation() = JSONArray()
        .put(JSONObject().put("role", "system").put("content", LLM_SYSTEM_INSTRUCTION))

    override suspend fun close() = mutex.withLock {
        messages = newConversation()
    }
}
