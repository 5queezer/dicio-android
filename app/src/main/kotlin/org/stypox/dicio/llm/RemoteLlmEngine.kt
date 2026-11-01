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
    // the @Tool methods of DicioTools, described in the OpenAI function format
    private val tools: Map<String, KFunction<*>> = DicioTools::class.declaredMemberFunctions
        .filter { it.findAnnotation<Tool>() != null }
        .associateBy { it.name }
    private val toolsJson = JSONArray().apply {
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
    private val mutex = Mutex()
    private var messages = newConversation()

    override val isReady: Boolean
        get() = true

    override suspend fun generate(prompt: String): String = mutex.withLock {
        val settings = dataStore.data.first()
        val url = settings.llmRemoteUrl.trimEnd('/') + "/chat/completions"
        Log.d(TAG, "generate model=${settings.llmRemoteModel} prompt=$prompt")
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        repeat(MAX_TOOL_ROUNDS) {
            val message = complete(url, settings.llmRemoteModel)
            messages.put(message)
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val text = message.optString("content")
                Log.d(TAG, "reply $text")
                return@withLock text
            }
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", call.optString("id"))
                        .put("content", runTool(call.getJSONObject("function")))
                )
            }
        }
        error("no answer after $MAX_TOOL_ROUNDS tool rounds")
    }

    private suspend fun complete(url: String, model: String): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", toolsJson)
            .put("temperature", LLM_TEMPERATURE)
            .put("top_p", 0.95)
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

    private fun runTool(function: JSONObject): String {
        val name = function.getString("name")
        // arguments is a JSON string per the OpenAI API; some servers send an object
        val args = function.opt("arguments").let { if (it is JSONObject) it.toString() else it as? String }
        Log.d(TAG, "tool_call $name $args")
        val result = try {
            val function = tools[name] ?: error("unknown tool $name")
            val json = JSONObject(args?.ifBlank { null } ?: "{}")
            val values = mutableMapOf<KParameter, Any?>(function.parameters[0] to dicioTools)
            for (param in function.parameters.filter { it.kind == KParameter.Kind.VALUE }) {
                if (json.has(param.name)) {
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
            JSONObject(function.callBy(values) as Map<*, *>).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Tool $name failed", e)
            JSONObject().put("result", "error").put("reason", e.message ?: e.javaClass.simpleName).toString()
        }
        Log.d(TAG, "tool_result $result")
        return result
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
