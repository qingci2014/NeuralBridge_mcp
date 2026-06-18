package com.neuralbridge.companion.mcp

import android.util.Log
import com.neuralbridge.companion.log.CommandLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Pull-based cloud bridge.
 *
 * The phone keeps an outbound connection pattern to the Gateway:
 * register -> poll task -> execute locally -> post result.
 */
class CloudGatewayClient(
    private val scope: CoroutineScope,
    private val toolHandler: McpToolHandler,
    private val config: Config
) {
    data class Config(
        val gatewayUrl: String,
        val deviceId: String,
        val deviceName: String,
        val token: String,
        val pollTimeoutMs: Long = 25_000L
    )

    companion object {
        private const val TAG = "CloudGatewayClient"
        private const val RETRY_DELAY_MS = 3_000L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    private var job: Job? = null
    @Volatile
    private var lastActivityAtMs: Long = 0L

    fun start() {
        if (job?.isActive == true) return
        touchActivity()
        job = scope.launch {
            Log.i(TAG, "Starting cloud gateway client: ${config.gatewayUrl}")
            while (isActive) {
                try {
                    register()
                    pollLoop()
                } catch (e: Exception) {
                    Log.w(TAG, "Cloud gateway loop failed: ${e.message}", e)
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun isRunning(): Boolean = job?.isActive == true

    fun isHealthy(maxQuietMs: Long = 90_000L): Boolean =
        isRunning() && System.currentTimeMillis() - lastActivityAtMs <= maxQuietMs

    private suspend fun pollLoop() {
        while (scope.coroutineContext.isActive) {
            val task = pollTask() ?: continue
            executeAndReport(task)
        }
    }

    private suspend fun register() {
        touchActivity()
        val body = buildJsonObject {
            put("device_id", config.deviceId)
            put("name", config.deviceName)
            put("executor_version", "neuralbridge-cloud-0.1")
        }
        postJson("/device/register", body, 10_000)
        touchActivity()
        Log.i(TAG, "Registered cloud device: ${config.deviceId}")
    }

    private suspend fun pollTask(): JsonObject? {
        touchActivity()
        val device = urlEncode(config.deviceId)
        val path = "/device/$device/poll?timeout_ms=${config.pollTimeoutMs}"
        val response = requestJson("GET", path, null, config.pollTimeoutMs + 5_000)
        touchActivity()
        if (response.statusCode == HttpURLConnection.HTTP_NO_CONTENT) return null
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Poll failed: HTTP ${response.statusCode} ${response.body}")
        }
        val body = response.body ?: return null
        return json.parseToJsonElement(body).jsonObject
    }

    private suspend fun executeAndReport(task: JsonObject) {
        val taskId = task["task_id"]?.jsonPrimitive?.contentOrNull ?: ""
        val stepId = task["step_id"]?.jsonPrimitive?.contentOrNull ?: ""
        val action = task["action"]?.jsonPrimitive?.contentOrNull ?: ""
        val tool = task["tool"]?.jsonPrimitive?.contentOrNull ?: ""
        val arguments = task["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        val startedAt = System.currentTimeMillis()

        val result = try {
            val toolResult = toolHandler.handleToolCall(tool, arguments)
            val durationMs = (System.currentTimeMillis() - startedAt).toInt()
            CommandLog.add(CommandLog.Entry(
                timestamp = startedAt,
                command = "cloud:$tool",
                latencyMs = durationMs,
                success = !toolResult.isError,
                category = CommandLog.Category.CONNECT
            ))

            buildResultBody(
                taskId = taskId,
                stepId = stepId,
                action = action,
                tool = tool,
                status = if (toolResult.isError) "error" else "ok",
                startedAt = startedAt,
                durationMs = durationMs,
                mcp = buildJsonObject {
                    putJsonObject("result") {
                        put("isError", JsonPrimitive(toolResult.isError))
                        put("content", buildJsonArray {
                            toolResult.content.forEach { block ->
                                add(buildJsonObject {
                                    put("type", block.type)
                                    block.text?.let { put("text", it) }
                                    block.data?.let { put("data", it) }
                                    block.mimeType?.let { put("mimeType", it) }
                                })
                            }
                        })
                    }
                }
            )
        } catch (e: Exception) {
            val durationMs = (System.currentTimeMillis() - startedAt).toInt()
            Log.e(TAG, "Cloud task failed: $tool", e)
            CommandLog.add(CommandLog.Entry(
                timestamp = startedAt,
                command = "cloud:$tool",
                latencyMs = durationMs,
                success = false,
                category = CommandLog.Category.CONNECT
            ))

            buildResultBody(
                taskId = taskId,
                stepId = stepId,
                action = action,
                tool = tool,
                status = "error",
                startedAt = startedAt,
                durationMs = durationMs,
                error = e.message ?: "unknown error"
            )
        }

        val device = urlEncode(config.deviceId)
        touchActivity()
        postJson("/device/$device/result", result, 15_000)
        touchActivity()
    }

    private fun touchActivity() {
        lastActivityAtMs = System.currentTimeMillis()
    }

    private fun buildResultBody(
        taskId: String,
        stepId: String,
        action: String,
        tool: String,
        status: String,
        startedAt: Long,
        durationMs: Int,
        mcp: JsonObject? = null,
        error: String? = null
    ): JsonObject = buildJsonObject {
        put("task_id", taskId)
        put("step_id", stepId)
        put("device_id", config.deviceId)
        put("action", action)
        put("tool", tool)
        put("status", status)
        put("started_at", startedAt.toString())
        put("duration_ms", durationMs)
        mcp?.let { put("mcp", it) }
        error?.let { put("error", it) }
    }

    private suspend fun postJson(path: String, body: JsonObject, timeoutMs: Long): HttpResponse =
        requestJson("POST", path, body.toString(), timeoutMs)

    private suspend fun requestJson(
        method: String,
        path: String,
        body: String?,
        timeoutMs: Long
    ): HttpResponse = withContext(Dispatchers.IO) {
        val url = URL(config.gatewayUrl.trimEnd('/') + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
            setRequestProperty("Authorization", "Bearer ${config.token}")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "close")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                }
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }
            HttpResponse(statusCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private data class HttpResponse(
        val statusCode: Int,
        val body: String?
    )
}
