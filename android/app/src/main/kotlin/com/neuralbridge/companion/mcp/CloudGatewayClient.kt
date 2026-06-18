package com.neuralbridge.companion.mcp

import android.util.Log
import com.neuralbridge.companion.log.CommandLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
        private const val POLL_STALL_GRACE_MS = 20_000L
        private val NEXT_JOB_ID = AtomicInteger(1)
    }

    enum class State {
        STOPPED,
        STARTING,
        RUNNING,
        BACKOFF,
        STOPPING
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
    @Volatile
    private var lastPollStartedAtMs: Long = 0L
    @Volatile
    private var lastPollResponseAtMs: Long = 0L
    @Volatile
    private var lastLoopTickAtMs: Long = 0L
    @Volatile
    private var lastRegisterAtMs: Long = 0L
    @Volatile
    private var activePollStartedAtMs: Long = 0L
    @Volatile
    private var state: State = State.STOPPED
    @Volatile
    private var currentLoopId: Int = 0
    @Volatile
    private var lastExitReason: String = "not_started"
    private val pollSeq = AtomicLong(0L)

    @Synchronized
    fun start(reason: String = "start") {
        if (job?.isActive == true) {
            Log.i(TAG, "CloudPollingManager.ensureStart reason=$reason result=already_running ${describeState()}")
            return
        }
        touchActivity()
        val jobId = NEXT_JOB_ID.getAndIncrement()
        currentLoopId = jobId
        pollSeq.set(0L)
        lastExitReason = "running"
        setState(State.STARTING, reason)
        job = scope.launch {
            Log.i(TAG, "CloudPollingManager.ensureStart reason=$reason result=launch loopId=$jobId url=${config.gatewayUrl}")
            while (currentCoroutineContext().isActive) {
                try {
                    register(reason)
                    setState(State.RUNNING, "register_success")
                    Log.i(TAG, "CloudPollingManager.loop_enter loopId=$jobId")
                    while (currentCoroutineContext().isActive) {
                        touchLoopTick()
                        val seq = pollSeq.incrementAndGet()
                        val task = pollTask(jobId, seq)
                        if (task == null) {
                            Log.i(TAG, "CloudPollingManager.no_task_continue loopId=$jobId seq=$seq")
                            continue
                        }
                        executeAndReport(task, jobId, seq)
                    }
                    lastExitReason = "coroutine_inactive"
                    Log.w(TAG, "CloudPollingManager.loop_exit loopId=$jobId reason=coroutine_inactive isActive=${currentCoroutineContext().isActive}")
                } catch (e: CancellationException) {
                    lastExitReason = "cancelled:${e.message}"
                    Log.w(TAG, "CloudPollingManager.cancelled loopId=$jobId seq=${pollSeq.get()} message=${e.message}", e)
                    throw e
                } catch (e: Exception) {
                    lastExitReason = "exception:${e.message}"
                    setState(State.BACKOFF, "exception")
                    Log.w(TAG, "CloudPollingManager.exception loopId=$jobId seq=${pollSeq.get()} message=${e.message}", e)
                    Log.i(TAG, "CloudPollingManager.schedule_restart reason=backoff loopId=$jobId delay_ms=$RETRY_DELAY_MS")
                    delay(RETRY_DELAY_MS)
                }
            }
        }.also { pollingJob ->
            pollingJob.invokeOnCompletion { cause ->
                if (job === pollingJob) {
                    state = State.STOPPED
                    activePollStartedAtMs = 0L
                }
                if (cause == null) {
                    Log.w(TAG, "CloudPollingManager.loop_exit loopId=$jobId reason=completed_without_error isActive=false")
                } else {
                    Log.w(TAG, "CloudPollingManager.loop_exit loopId=$jobId reason=completed_with_cause message=${cause.message}", cause)
                }
            }
        }
    }

    @Synchronized
    fun stop(reason: String = "stop") {
        setState(State.STOPPING, reason)
        lastExitReason = reason
        Log.i(TAG, "CloudPollingManager.stop reason=$reason job=$job active=${job?.isActive}")
        job?.cancel()
        job = null
        activePollStartedAtMs = 0L
        setState(State.STOPPED, reason)
    }

    @Synchronized
    fun restart(reason: String) {
        Log.w(TAG, "CloudPollingManager.restart_begin reason=$reason previous=${describeState()}")
        stop("restart:$reason")
        start("restart:$reason")
        Log.w(TAG, "CloudPollingManager.restart_end reason=$reason current=${describeState()}")
    }

    fun isRunning(): Boolean = job?.isActive == true

    fun isHealthy(maxQuietMs: Long = 90_000L): Boolean {
        val running = isRunning()
        val now = System.currentTimeMillis()
        val quietMs = now - lastActivityAtMs
        val pollAgeMs = activePollStartedAtMs.takeIf { it > 0L }?.let { now - it }
        val activePollHealthy = pollAgeMs != null && pollAgeMs <= config.pollTimeoutMs + POLL_STALL_GRACE_MS
        val healthy = running && (quietMs <= maxQuietMs || activePollHealthy)
        Log.i(TAG, "PollingWatchdog.tick lastPollStartAgeMs=${age(lastPollStartedAtMs, now)} lastPollResponseAgeMs=${age(lastPollResponseAtMs, now)} lastLoopTickAgeMs=${age(lastLoopTickAtMs, now)} jobActive=$running state=$state loopId=$currentLoopId seq=${pollSeq.get()}")
        Log.i(TAG, if (healthy) "PollingWatchdog.ok" else "PollingWatchdog.stalled")
        return healthy
    }

    fun describeState(): String {
        val now = System.currentTimeMillis()
        return "state=$state running=${isRunning()} loopId=$currentLoopId seq=${pollSeq.get()} quiet_ms=${now - lastActivityAtMs} last_poll_start_age_ms=${age(lastPollStartedAtMs, now)} last_poll_response_age_ms=${age(lastPollResponseAtMs, now)} last_loop_tick_age_ms=${age(lastLoopTickAtMs, now)} active_poll_age_ms=${activePollStartedAtMs.takeIf { it > 0L }?.let { now - it } ?: -1} last_exit_reason=$lastExitReason"
    }

    private fun markPollStarted(): Long {
        val startedAt = System.currentTimeMillis()
        lastPollStartedAtMs = startedAt
        activePollStartedAtMs = startedAt
        return startedAt
    }

    private fun markPollFinished() {
        activePollStartedAtMs = 0L
    }

    private fun touchActivity() {
        lastActivityAtMs = System.currentTimeMillis()
    }

    private fun touchLoopTick() {
        lastLoopTickAtMs = System.currentTimeMillis()
    }

    private fun setState(next: State, reason: String) {
        if (state == next) return
        Log.i(TAG, "CloudPollingManager.state_change from=$state to=$next reason=$reason loopId=$currentLoopId")
        state = next
    }

    private fun age(timestampMs: Long, now: Long): Long =
        if (timestampMs > 0L) now - timestampMs else -1L

    private suspend fun <T> withPollInFlight(block: suspend () -> T): T {
        markPollStarted()
        return try {
            block()
        } finally {
            markPollFinished()
        }
    }

    private suspend fun register(reason: String) {
        touchActivity()
        Log.i(TAG, "CloudGatewayClient.register_start reason=$reason deviceId=${config.deviceId}")
        val body = buildJsonObject {
            put("device_id", config.deviceId)
            put("name", config.deviceName)
            put("executor_version", "neuralbridge-cloud-0.1")
        }
        try {
            postJson("/device/register", body, 10_000)
        } catch (e: Exception) {
            Log.w(TAG, "CloudGatewayClient.register_failure deviceId=${config.deviceId} error=${e.message}", e)
            throw e
        }
        touchActivity()
        lastRegisterAtMs = System.currentTimeMillis()
        Log.i(TAG, "CloudGatewayClient.register_success deviceId=${config.deviceId}")
        Log.i(TAG, "CloudGatewayClient.after_register_ensure_polling loopId=$currentLoopId")
    }

    private suspend fun pollTask(loopId: Int, seq: Long): JsonObject? {
        touchActivity()
        val device = urlEncode(config.deviceId)
        val path = "/device/$device/poll?timeout_ms=${config.pollTimeoutMs}"
        Log.i(TAG, "CloudPollingManager.poll_start loopId=$loopId seq=$seq url=${config.gatewayUrl.trimEnd('/')}$path")
        val response = try {
            withPollInFlight {
                requestJson("GET", path, null, config.pollTimeoutMs + 5_000)
            }
        } catch (e: java.net.SocketTimeoutException) {
            lastPollResponseAtMs = System.currentTimeMillis()
            Log.w(TAG, "CloudPollingManager.poll_response loopId=$loopId seq=$seq http=timeout kind=timeout message=${e.message}")
            return null
        }
        lastPollResponseAtMs = System.currentTimeMillis()
        touchActivity()
        Log.i(TAG, "CloudPollingManager.poll_response loopId=$loopId seq=$seq http=${response.statusCode} kind=${if (response.statusCode == HttpURLConnection.HTTP_NO_CONTENT) "no_task" else "task_or_error"} duration_ms=${System.currentTimeMillis() - lastPollStartedAtMs}")
        if (response.statusCode == HttpURLConnection.HTTP_NO_CONTENT) return null
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Poll failed: HTTP ${response.statusCode} ${response.body}")
        }
        val body = response.body ?: return null
        val task = json.parseToJsonElement(body).jsonObject
        Log.i(TAG, "CloudPollingManager.task_received loopId=$loopId seq=$seq taskId=${task["task_id"]?.jsonPrimitive?.contentOrNull ?: ""} stepId=${task["step_id"]?.jsonPrimitive?.contentOrNull ?: ""} action=${task["action"]?.jsonPrimitive?.contentOrNull ?: ""}")
        return task
    }

    private suspend fun executeAndReport(task: JsonObject, loopId: Int, seq: Long) {
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
        val response = postJson("/device/$device/result", result, 15_000)
        touchActivity()
        Log.i(TAG, "CloudPollingManager.task_result_posted loopId=$loopId seq=$seq taskId=$taskId stepId=$stepId tool=$tool status=${result["status"]?.jsonPrimitive?.contentOrNull} http=${response.statusCode}")
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
        val startedAt = System.currentTimeMillis()
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
            Log.i(TAG, "Cloud HTTP $method $path -> $statusCode in ${System.currentTimeMillis() - startedAt}ms")
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
