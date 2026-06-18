package com.neuralbridge.companion.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.neuralbridge.companion.gesture.GestureEngine
import com.neuralbridge.companion.input.InputEngine
import com.neuralbridge.companion.notification.NotificationListener
import com.neuralbridge.companion.screenshot.ScreenshotPipeline
import com.neuralbridge.companion.service.AccessibilityEventListener
import com.neuralbridge.companion.service.GestureResultCallback
import com.neuralbridge.companion.service.NeuralBridgeAccessibilityService
import com.neuralbridge.companion.service.ScreenshotQuality
import com.neuralbridge.companion.service.UiElement
import com.neuralbridge.companion.service.UiTree
import com.neuralbridge.companion.uitree.UiTreeWalker
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import android.accessibilityservice.GestureDescription
import kotlin.coroutines.resume

class McpToolHandler(
    private val service: NeuralBridgeAccessibilityService,
    private val gestureEngine: GestureEngine,
    private val uiTreeWalker: UiTreeWalker,
    private val inputEngine: InputEngine,
    private val screenshotPipeline: ScreenshotPipeline
) {
    companion object {
        private const val TAG = "McpToolHandler"
        private const val DEFAULT_TIMEOUT_MS = 10000L
        private const val POLL_INTERVAL_MS = 300L
    }

    suspend fun handleToolCall(toolName: String, arguments: JsonObject?): McpToolCallResult {
        val args = arguments ?: JsonObject(emptyMap())
        return try {
            when (toolName) {
                // OBSERVE
                "android_get_ui_tree" -> handleGetUiTree(args)
                "android_screenshot" -> handleScreenshot(args)
                "android_find_elements" -> handleFindElements(args)
                "android_get_screen_context" -> handleGetScreenContext(args)
                "android_get_notifications" -> handleGetNotifications(args)
                "android_accessibility_audit" -> handleAccessibilityAudit(args)
                "android_screenshot_diff" -> handleScreenshotDiff(args)
                "android_get_recent_toasts" -> handleGetRecentToasts(args)

                // ACT
                "android_tap" -> handleTap(args)
                "android_long_press" -> handleLongPress(args)
                "android_double_tap" -> handleDoubleTap(args)
                "android_swipe" -> handleSwipe(args)
                "android_pinch" -> handlePinch(args)
                "android_drag" -> handleDrag(args)
                "android_input_text" -> handleInputText(args)
                "android_press_key" -> handlePressKey(args)
                "android_global_action" -> handleGlobalAction(args)
                "android_tap_text" -> handleTapText(args)
                "android_click_by_resource_id" -> handleClickByResourceId(args)
                "android_set_text" -> handleSetText(args)
                "android_clear_text" -> handleClearText(args)
                "android_dismiss_overlay" -> handleDismissOverlay(args)

                // MANAGE
                "android_launch_app" -> handleLaunchApp(args)
                "android_close_app" -> handleCloseApp(args)
                "android_open_url" -> handleOpenUrl(args)
                "android_set_clipboard" -> handleSetClipboard(args)
                "android_list_apps" -> handleListApps(args)
                "android_install_app" -> handleInstallApp(args)

                // WAIT
                "android_wait_for_element" -> handleWaitForElement(args)
                "android_wait_for_text" -> handleWaitForText(args)
                "android_wait_for_gone" -> handleWaitForGone(args)
                "android_wait_for_idle" -> handleWaitForIdle(args)
                "android_scroll_to_element" -> handleScrollToElement(args)

                // DEVICE (stubs — we are the device)
                "android_list_devices" -> handleListDevices()
                "android_select_device" -> textResult("{\"success\":true,\"message\":\"Already connected to this device\"}")

                // META
                "android_search_tools" -> handleSearchTools(args)
                "android_describe_tools" -> handleDescribeTools(args)

                // TEST
                "android_enable_events" -> handleEnableEvents(args)
                "android_get_device_info" -> handleGetDeviceInfo()
                "android_get_installed_package" -> handleGetInstalledPackage(args)
                "android_get_screen_state" -> handleGetScreenState()
                "android_wake_screen" -> handleWakeScreen()
                "android_unlock_device" -> handleUnlockDevice(args)
                "android_keep_awake" -> handleKeepAwake(args)

                else -> errorResult("Unknown tool: $toolName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool error: $toolName", e)
            errorResult("Tool error: ${e.message}")
        }
    }

    // =====================================================================
    // GESTURE BRIDGE: callback → suspend
    // =====================================================================

    private suspend fun executeGestureAndWait(block: (GestureResultCallback) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            block(object : GestureResultCallback {
                override fun onCompleted(gesture: GestureDescription) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gesture: GestureDescription) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            // AccessibilityService gestures cannot be cancelled once dispatched;
            // the callback guards with isActive so the result is silently discarded.
            cont.invokeOnCancellation { /* no-op: gesture runs to completion */ }
        }

    // =====================================================================
    // SELECTOR RESOLUTION
    // =====================================================================

    private fun resolveSelector(tree: UiTree, text: String?, resourceId: String?, contentDesc: String?): UiElement? {
        return tree.elements.firstOrNull { e ->
            (text != null && e.text?.contains(text, ignoreCase = true) == true) ||
            (resourceId != null && (e.resourceId?.endsWith(resourceId) == true || e.resourceId == resourceId)) ||
            (contentDesc != null && e.contentDescription?.contains(contentDesc, ignoreCase = true) == true)
        }
    }

    private fun nodeText(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString().orEmpty()

    private fun matchesText(value: String, expected: String, match: String): Boolean = when (match) {
        "exact" -> value == expected
        "regex" -> runCatching { Regex(expected).containsMatchIn(value) }.getOrDefault(false)
        else -> value.contains(expected, ignoreCase = true)
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        matches: (AccessibilityNodeInfo) -> Boolean,
        output: MutableList<AccessibilityNodeInfo> = mutableListOf()
    ): MutableList<AccessibilityNodeInfo> {
        if (node == null) return output
        if (matches(node)) output.add(node)
        for (i in 0 until node.childCount) {
            collectNodes(node.getChild(i), matches, output)
        }
        return output
    }

    private fun findNodeByText(text: String, match: String, index: Int): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return collectNodes(root, { node ->
            val value = nodeText(node)
            value.isNotEmpty() && matchesText(value, text, match)
        }).getOrNull(index)
    }

    private fun findNodeByResourceId(resourceId: String, index: Int): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val direct = runCatching { root.findAccessibilityNodeInfosByViewId(resourceId) }.getOrDefault(emptyList())
        if (direct.size > index) return direct[index]
        return collectNodes(root, { node ->
            node.viewIdResourceName == resourceId || node.viewIdResourceName?.endsWith(resourceId) == true
        }).getOrNull(index)
    }

    private fun clickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val candidate = current ?: return null
            if (candidate.isClickable && candidate.isEnabled) return candidate
            current = candidate.parent
        }
        return null
    }

    private suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val clickable = clickableNode(node)
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            return withTimeoutOrNull(5000L) {
                executeGestureAndWait { cb ->
                    gestureEngine.executeTap(rect.centerX().toFloat(), rect.centerY().toFloat(), cb)
                }
            } ?: false
        }
        return false
    }

    private fun boundsJson(node: AccessibilityNodeInfo): JsonArray {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return buildJsonArray {
            add(rect.left); add(rect.top); add(rect.right); add(rect.bottom)
        }
    }

    private suspend fun waitForNode(
        timeoutMs: Long,
        intervalMs: Long = POLL_INTERVAL_MS,
        finder: () -> AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() <= deadline) {
            finder()?.let { return it }
            delay(intervalMs)
        }
        return null
    }

    private fun packageInfoJson(packageName: String): JsonObject {
        val pm = service.packageManager
        val info = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
        return buildJsonObject {
            put("status", "ok")
            put("installed", info != null)
            put("package_name", packageName)
            if (info != null) {
                put("version_name", info.versionName ?: "")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    put("version_code", info.longVersionCode)
                } else {
                    @Suppress("DEPRECATION")
                    put("version_code", info.versionCode)
                }
            }
        }
    }

    private fun isPackageInstalled(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return runCatching { service.packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }

    private fun findDescendantText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
        collectNodes(node, { child -> nodeText(child) == text }).firstOrNull()

    private fun findInstallButtonNear(appNameNode: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
        var current: AccessibilityNodeInfo? = appNameNode
        repeat(6) {
            val ancestor = current ?: return null
            val buttons = collectNodes(ancestor, { node ->
                val value = nodeText(node)
                node.viewIdResourceName == "com.hihonor.appmarket:id/zy_state_app_btn" ||
                    value in listOf("安装", "打开", "更新", "继续", "暂停") ||
                    value.contains("%")
            })
            val button = buttons.firstOrNull { it != appNameNode }
            if (button != null) return button to nodeText(button)
            current = ancestor.parent
        }
        return null
    }

    private fun exactAppNameNode(appName: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val direct = collectNodes(root, { node ->
            node.viewIdResourceName == "com.hihonor.appmarket:id/zy_app_name_txt" && nodeText(node) == appName
        }).firstOrNull()
        return direct ?: findNodeByText(appName, "exact", 0)
    }

    private suspend fun compactUiTree(limit: Int = 30): String {
        val root = service.rootInActiveWindow ?: return "no_active_window"
        val tree = uiTreeWalker.walkTree(root)
        return tree.elements
            .filter { !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() || !it.resourceId.isNullOrBlank() }
            .take(limit)
            .joinToString("\n") { e ->
                "${e.resourceId ?: ""} | ${e.text ?: ""} | ${e.contentDescription ?: ""} | ${e.bounds ?: ""}"
            }
    }

    // =====================================================================
    // OBSERVE TOOLS
    // =====================================================================

    private suspend fun handleGetUiTree(args: JsonObject): McpToolCallResult {
        val includeInvisible = args["include_invisible"]?.jsonPrimitive?.booleanOrNull ?: false
        val maxDepth = args["max_depth"]?.jsonPrimitive?.intOrNull ?: 0
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull ?: "interactive"

        val rootNode = service.rootInActiveWindow
            ?: return errorResult("No active window available")

        val tree = uiTreeWalker.walkTree(rootNode, includeInvisible, maxDepth)

        val filtered = when (filter) {
            "all" -> tree.elements
            "text" -> tree.elements.filter { !it.text.isNullOrEmpty() || !it.contentDescription.isNullOrEmpty() }
            else -> tree.elements.filter { it.clickable || it.focusable || it.scrollable || it.checkable || !it.text.isNullOrEmpty() || !it.contentDescription.isNullOrEmpty() }
        }

        val table = buildString {
            append("IDX | resource_id | text | desc | flags | bounds\n")
            filtered.forEachIndexed { idx, e ->
                val flags = buildString {
                    if (e.clickable) append("c")
                    if (e.focusable) append("f")
                    if (e.scrollable) append("s")
                    if (e.checkable) append("k")
                }
                val bounds = e.bounds?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: ""
                append("$idx | ${e.resourceId ?: ""} | ${e.text ?: ""} | ${e.contentDescription ?: ""} | $flags | $bounds\n")
            }
        }

        val result = buildJsonObject {
            put("format", "compact")
            put("app", tree.foregroundApp)
            put("total", tree.totalNodes)
            put("shown", filtered.size)
            put("filter", filter)
            put("elements", table.trimEnd())
        }
        return textResult(result.toString())
    }

    private suspend fun handleScreenshot(args: JsonObject): McpToolCallResult {
        val quality = if (args["quality"]?.jsonPrimitive?.contentOrNull == "thumbnail")
            ScreenshotQuality.THUMBNAIL else ScreenshotQuality.FULL

        val jpegBytes = withTimeout(70000L) {
            screenshotPipeline.capture(quality)
        }
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val dm = service.resources.displayMetrics
        val meta = buildJsonObject {
            put("width", dm.widthPixels)
            put("height", dm.heightPixels)
            put("format", "jpeg")
        }.toString()
        return McpToolCallResult(content = listOf(
            McpContentBlock(type = "image", data = base64, mimeType = "image/jpeg"),
            McpContentBlock(type = "text", text = meta)
        ))
    }

    private suspend fun handleFindElements(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
        val className = args["class_name"]?.jsonPrimitive?.contentOrNull
        val findAll = args["find_all"]?.jsonPrimitive?.booleanOrNull ?: false

        if (text == null && resourceId == null && contentDesc == null && className == null) {
            return errorResult("At least one selector (text, resource_id, content_desc, class_name) required")
        }

        val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
        val tree = uiTreeWalker.walkTree(rootNode)

        val matches = tree.elements.filter { e ->
            (text != null && e.text?.contains(text, ignoreCase = true) == true) ||
            (resourceId != null && (e.resourceId?.endsWith(resourceId) == true)) ||
            (contentDesc != null && e.contentDescription?.contains(contentDesc, ignoreCase = true) == true) ||
            (className != null && e.className?.contains(className) == true)
        }.let { if (findAll) it else if (it.isNotEmpty()) listOf(it.first()) else it }

        val result = buildJsonObject {
            putJsonArray("elements") {
                matches.forEach { e ->
                    addJsonObject {
                        put("elementId", e.elementId)
                        e.resourceId?.let { put("resourceId", it) }
                        e.text?.let { put("text", it) }
                        e.contentDescription?.let { put("contentDescription", it) }
                        e.bounds?.let { b -> put("bounds", "[${b.left},${b.top},${b.right},${b.bottom}]") }
                        put("clickable", e.clickable)
                    }
                }
            }
            put("total_matches", matches.size)
        }
        return textResult(result.toString())
    }

    private suspend fun handleGetScreenContext(args: JsonObject): McpToolCallResult {
        val includeAll = args["include_all_elements"]?.jsonPrimitive?.booleanOrNull ?: false
        val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
        val tree = uiTreeWalker.walkTree(rootNode)
        val jpegBytes = screenshotPipeline.capture(ScreenshotQuality.THUMBNAIL)
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val filtered = if (includeAll) tree.elements
        else tree.elements.filter { it.clickable || it.focusable || it.checkable || it.scrollable || !it.text.isNullOrEmpty() }

        val meta = buildJsonObject {
            putJsonObject("app_info") { put("package_name", tree.foregroundApp) }
            putJsonObject("ui_tree") {
                put("total_elements", tree.totalNodes)
                put("filtered_elements", filtered.size)
                putJsonArray("elements") {
                    filtered.forEach { e ->
                        addJsonObject {
                            e.resourceId?.let { put("resourceId", it) }
                            e.text?.let { put("text", it) }
                            e.bounds?.let { b ->
                                put("center_x", (b.left + b.right) / 2)
                                put("center_y", (b.top + b.bottom) / 2)
                                put("bounds", "[${b.left},${b.top},${b.right},${b.bottom}]")
                            }
                        }
                    }
                }
            }
        }.toString()

        return McpToolCallResult(content = listOf(
            McpContentBlock(type = "text", text = meta),
            McpContentBlock(type = "image", data = base64, mimeType = "image/jpeg")
        ))
    }

    private fun handleGetNotifications(args: JsonObject): McpToolCallResult {
        val notifications = NotificationListener.instance?.getActiveNotificationsList() ?: emptyList()
        val result = buildJsonObject {
            putJsonArray("notifications") {
                notifications.forEach { n ->
                    addJsonObject {
                        put("package_name", n.packageName)
                        put("title", n.title)
                        put("text", n.text)
                        put("post_time", n.postTime)
                        put("ongoing", n.ongoing)
                        put("clearable", n.clearable)
                    }
                }
            }
            put("count", notifications.size)
        }
        return textResult(result.toString())
    }

    private suspend fun handleAccessibilityAudit(args: JsonObject): McpToolCallResult {
        val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
        val tree = uiTreeWalker.walkTree(rootNode, includeInvisible = false)

        val issues = mutableListOf<String>()
        tree.elements.forEach { e ->
            if (e.clickable && e.contentDescription.isNullOrEmpty() && e.text.isNullOrEmpty()) {
                val bounds = e.bounds?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "unknown"
                issues.add("Missing content description on clickable element (${e.className ?: "unknown"}) at $bounds")
            }
            e.bounds?.let { b ->
                val width = b.right - b.left
                val height = b.bottom - b.top
                val dm = service.resources.displayMetrics
                val minPx = (48 * dm.density).toInt()
                if (e.clickable && (width < minPx || height < minPx)) {
                    issues.add("Small touch target ${width}x${height}px (min ${minPx}px) on ${e.text ?: e.contentDescription ?: e.className ?: "element"}")
                }
            }
        }

        val result = buildJsonObject {
            put("issues_found", issues.size)
            put("pass", issues.isEmpty())
            putJsonArray("issues") { issues.forEach { add(JsonPrimitive(it)) } }
        }
        return textResult(result.toString())
    }

    private suspend fun handleScreenshotDiff(args: JsonObject): McpToolCallResult {
        val referenceBase64 = args["reference_base64"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("reference_base64 is required")
        val threshold = args["threshold"]?.jsonPrimitive?.doubleOrNull ?: 0.95

        val currentBytes = screenshotPipeline.capture(ScreenshotQuality.THUMBNAIL)
        val referenceBytes = Base64.decode(referenceBase64, Base64.DEFAULT)

        val currentBitmap = android.graphics.BitmapFactory.decodeByteArray(currentBytes, 0, currentBytes.size)
            ?: return errorResult("Failed to decode current screenshot as bitmap")
        val referenceBitmap = android.graphics.BitmapFactory.decodeByteArray(referenceBytes, 0, referenceBytes.size)
            ?: return errorResult("Failed to decode reference_base64 as bitmap")

        // Scale reference to current dimensions if needed
        val scaledRef = if (referenceBitmap.width != currentBitmap.width ||
                            referenceBitmap.height != currentBitmap.height) {
            android.graphics.Bitmap.createScaledBitmap(
                referenceBitmap, currentBitmap.width, currentBitmap.height, true)
        } else referenceBitmap

        val width = currentBitmap.width
        val height = currentBitmap.height
        val currentPixels = IntArray(width * height)
        val refPixels = IntArray(width * height)
        currentBitmap.getPixels(currentPixels, 0, width, 0, 0, width, height)
        scaledRef.getPixels(refPixels, 0, width, 0, 0, width, height)

        // Count pixels within ±10 per channel (RGB)
        var matching = 0L
        for (i in currentPixels.indices) {
            val c1 = currentPixels[i]; val c2 = refPixels[i]
            val rDiff = ((c1 shr 16 and 0xFF) - (c2 shr 16 and 0xFF)).let { if (it < 0) -it else it }
            val gDiff = ((c1 shr 8 and 0xFF) - (c2 shr 8 and 0xFF)).let { if (it < 0) -it else it }
            val bDiff = ((c1 and 0xFF) - (c2 and 0xFF)).let { if (it < 0) -it else it }
            if (rDiff + gDiff + bDiff <= 30) matching++
        }
        val similarity = if (currentPixels.isNotEmpty()) matching.toDouble() / currentPixels.size else 0.0
        val matches = similarity >= threshold

        val result = buildJsonObject {
            put("similarity", similarity)
            put("threshold", threshold)
            put("matches", matches)
        }
        return textResult(result.toString())
    }

    private fun handleGetRecentToasts(args: JsonObject): McpToolCallResult {
        val sinceMs = args["since_ms"]?.jsonPrimitive?.longOrNull ?: 5000L
        val cutoff = System.currentTimeMillis() - sinceMs
        val toasts = NeuralBridgeAccessibilityService.recentToasts.filter { it.second >= cutoff }
        val result = buildJsonObject {
            putJsonArray("toasts") {
                toasts.forEach { (text, time) ->
                    addJsonObject {
                        put("text", text)
                        put("timestamp", time)
                    }
                }
            }
        }
        return textResult(result.toString())
    }

    // =====================================================================
    // ACT TOOLS
    // =====================================================================

    private suspend fun handleTapText(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return errorResult("text required")
        val match = args["match"]?.jsonPrimitive?.contentOrNull ?: "exact"
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS
        val index = args["index"]?.jsonPrimitive?.intOrNull ?: 0
        val node = waitForNode(timeoutMs) { findNodeByText(text, match, index) }
            ?: return errorResult("Text not found: $text")
        val clicked = clickNode(node)
        val result = buildJsonObject {
            put("status", if (clicked) "ok" else "error")
            put("clicked", clicked)
            put("matched_text", nodeText(node))
            put("bounds", boundsJson(node))
        }
        return if (clicked) textResult(result.toString()) else errorResult(result.toString())
    }

    private suspend fun handleClickByResourceId(args: JsonObject): McpToolCallResult {
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull ?: return errorResult("resource_id required")
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS
        val index = args["index"]?.jsonPrimitive?.intOrNull ?: 0
        val node = waitForNode(timeoutMs) { findNodeByResourceId(resourceId, index) }
            ?: return errorResult("Resource ID not found: $resourceId")
        val clicked = clickNode(node)
        val result = buildJsonObject {
            put("status", if (clicked) "ok" else "error")
            put("clicked", clicked)
            put("resource_id", resourceId)
            put("text", nodeText(node))
            put("bounds", boundsJson(node))
        }
        return if (clicked) textResult(result.toString()) else errorResult(result.toString())
    }

    private suspend fun handleSetText(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return errorResult("text required")
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val elementText = args["element_text"]?.jsonPrimitive?.contentOrNull
        val clearFirst = args["clear_first"]?.jsonPrimitive?.booleanOrNull ?: true
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS
        val node = waitForNode(timeoutMs) {
            when {
                resourceId != null -> findNodeByResourceId(resourceId, 0)
                elementText != null -> findNodeByText(elementText, "contains", 0)
                else -> service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            }
        } ?: return errorResult("Input node not found")
        val value = if (clearFirst) text else "${node.text ?: ""}$text"
        val ok = inputEngine.inputText(node, value, append = false)
        delay(500)
        val refreshed = when {
            resourceId != null -> findNodeByResourceId(resourceId, 0)
            elementText != null -> findNodeByText(elementText, "contains", 0)
            else -> service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } ?: node
        val current = refreshed.text?.toString().orEmpty()
        val verified = current == value || current == text
        val result = buildJsonObject {
            put("status", if (ok && verified) "ok" else "error")
            put("set", ok)
            put("verified", verified)
            put("text", text)
            put("current_text", current)
            put("bounds", boundsJson(node))
        }
        return if (ok && verified) textResult(result.toString()) else errorResult(result.toString())
    }

    private suspend fun handleClearText(args: JsonObject): McpToolCallResult {
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val elementText = args["element_text"]?.jsonPrimitive?.contentOrNull
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS
        val node = waitForNode(timeoutMs) {
            when {
                resourceId != null -> findNodeByResourceId(resourceId, 0)
                elementText != null -> findNodeByText(elementText, "contains", 0)
                else -> service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            }
        } ?: return errorResult("Input node not found")
        val argsBundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, argsBundle) || inputEngine.clearText(node)
        delay(300)
        val refreshed = when {
            resourceId != null -> findNodeByResourceId(resourceId, 0)
            elementText != null -> findNodeByText(elementText, "contains", 0)
            else -> service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } ?: node
        val result = buildJsonObject {
            put("status", if (ok) "ok" else "error")
            put("cleared", ok)
            put("current_text", refreshed.text?.toString().orEmpty())
        }
        return if (ok) textResult(result.toString()) else errorResult(result.toString())
    }

    private suspend fun handleWaitForText(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return errorResult("text required")
        val match = args["match"]?.jsonPrimitive?.contentOrNull ?: "exact"
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS
        val intervalMs = args["interval_ms"]?.jsonPrimitive?.longOrNull ?: 500L
        val start = System.currentTimeMillis()
        val node = waitForNode(timeoutMs, intervalMs) { findNodeByText(text, match, 0) }
        val result = buildJsonObject {
            put("status", "ok")
            put("found", node != null)
            put("text", text)
            put("duration_ms", System.currentTimeMillis() - start)
            node?.let {
                put("matched_text", nodeText(it))
                put("bounds", boundsJson(it))
            }
        }
        return textResult(result.toString())
    }

    private suspend fun handleDismissOverlay(args: JsonObject): McpToolCallResult {
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 3000L
        val safeLabels = listOf("跳过", "稍后", "取消", "知道了", "暂不", "关闭", "不用了")
        val deadline = System.currentTimeMillis() + timeoutMs
        val dismissed = mutableListOf<String>()
        while (System.currentTimeMillis() < deadline) {
            val label = safeLabels.firstOrNull { findNodeByText(it, "exact", 0) != null }
            if (label == null) {
                delay(250)
                continue
            }
            val node = findNodeByText(label, "exact", 0) ?: continue
            if (clickNode(node)) {
                dismissed.add(label)
                delay(500)
            } else {
                break
            }
        }
        val result = buildJsonObject {
            put("status", "ok")
            put("dismissed", dismissed.isNotEmpty())
            putJsonArray("clicked") { dismissed.forEach { add(it) } }
        }
        return textResult(result.toString())
    }

    private suspend fun handleTap(args: JsonObject): McpToolCallResult {
        val x = args["x"]?.jsonPrimitive?.intOrNull
        val y = args["y"]?.jsonPrimitive?.intOrNull
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull

        val (tapX, tapY) = if (x != null && y != null) {
            x.toFloat() to y.toFloat()
        } else {
            val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
            val tree = uiTreeWalker.walkTree(rootNode)
            val element = resolveSelector(tree, text, resourceId, contentDesc)
                ?: return errorResult("Element not found: text=$text, resource_id=$resourceId, content_desc=$contentDesc")
            val b = element.bounds ?: return errorResult("Element has no bounds")
            ((b.left + b.right) / 2).toFloat() to ((b.top + b.bottom) / 2).toFloat()
        }

        val success = withTimeoutOrNull(5000L) {
            executeGestureAndWait { cb -> gestureEngine.executeTap(tapX, tapY, cb) }
        } ?: false

        return if (success) textResult("{\"latency_ms\":0}")
        else errorResult("Tap gesture was cancelled or timed out")
    }

    private suspend fun handleLongPress(args: JsonObject): McpToolCallResult {
        val x = args["x"]?.jsonPrimitive?.intOrNull?.toFloat()
        val y = args["y"]?.jsonPrimitive?.intOrNull?.toFloat()
        val durationMs = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 1000L

        val (lx, ly) = if (x != null && y != null) {
            x to y
        } else {
            val text = args["text"]?.jsonPrimitive?.contentOrNull
            val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
            val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
            val tree = uiTreeWalker.walkTree(rootNode)
            val element = resolveSelector(tree, text, resourceId, null)
                ?: return errorResult("Element not found")
            val b = element.bounds ?: return errorResult("Element has no bounds")
            ((b.left + b.right) / 2).toFloat() to ((b.top + b.bottom) / 2).toFloat()
        }

        val success = withTimeoutOrNull(durationMs + 2000L) {
            executeGestureAndWait { cb -> gestureEngine.executeLongPress(lx, ly, durationMs, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Long press cancelled")
    }

    private suspend fun handleDoubleTap(args: JsonObject): McpToolCallResult {
        val x = args["x"]?.jsonPrimitive?.intOrNull?.toFloat()
        val y = args["y"]?.jsonPrimitive?.intOrNull?.toFloat()

        val (dx, dy) = if (x != null && y != null) {
            x to y
        } else {
            val text = args["text"]?.jsonPrimitive?.contentOrNull
            val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
            val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
            val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
            val tree = uiTreeWalker.walkTree(rootNode)
            val element = resolveSelector(tree, text, resourceId, contentDesc)
                ?: return errorResult("Element not found")
            val b = element.bounds ?: return errorResult("Element has no bounds")
            ((b.left + b.right) / 2).toFloat() to ((b.top + b.bottom) / 2).toFloat()
        }

        val success = withTimeoutOrNull(5000L) {
            executeGestureAndWait { cb -> gestureEngine.executeDoubleTap(dx, dy, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Double tap cancelled")
    }

    private suspend fun handleSwipe(args: JsonObject): McpToolCallResult {
        val startX = args["start_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("start_x required")
        val startY = args["start_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("start_y required")
        val endX = args["end_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("end_x required")
        val endY = args["end_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("end_y required")
        val durationMs = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 300L

        val success = withTimeoutOrNull(durationMs + 2000L) {
            executeGestureAndWait { cb -> gestureEngine.executeSwipe(startX, startY, endX, endY, durationMs, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Swipe cancelled")
    }

    private suspend fun handlePinch(args: JsonObject): McpToolCallResult {
        val centerX = args["center_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("center_x required")
        val centerY = args["center_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("center_y required")
        val scale = args["scale"]?.jsonPrimitive?.floatOrNull ?: return errorResult("scale required")
        val durationMs = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 300L

        val success = withTimeoutOrNull(durationMs + 2000L) {
            executeGestureAndWait { cb -> gestureEngine.executePinch(centerX, centerY, scale, durationMs, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Pinch cancelled")
    }

    private suspend fun handleDrag(args: JsonObject): McpToolCallResult {
        val fromX = args["from_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("from_x required")
        val fromY = args["from_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("from_y required")
        val toX = args["to_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("to_x required")
        val toY = args["to_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("to_y required")
        val durationMs = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 1000L

        val success = withTimeoutOrNull(durationMs + 2000L) {
            executeGestureAndWait { cb -> gestureEngine.executeDrag(fromX, fromY, toX, toY, durationMs, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Drag cancelled")
    }

    private suspend fun handleInputText(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return errorResult("text required")
        val append = args["append"]?.jsonPrimitive?.booleanOrNull ?: false
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val elementText = args["element_text"]?.jsonPrimitive?.contentOrNull

        val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
        val tree = uiTreeWalker.walkTree(rootNode)

        val target = if (resourceId != null || elementText != null) {
            resolveSelector(tree, elementText, resourceId, null)
                ?: return errorResult("Element not found: resource_id=$resourceId, text=$elementText")
        } else {
            tree.elements.firstOrNull { e ->
                e.focusable && (
                    e.className?.contains("EditText") == true ||
                    e.className?.contains("TextField") == true ||
                    e.className == "android.widget.AutoCompleteTextView" ||
                    e.className?.contains("SearchView") == true
                )
            }
                ?: return errorResult("No editable element found. Specify resource_id or element_text")
        }

        // Walk node tree to find matching AccessibilityNodeInfo by resource ID or text
        val nodeInfo = findNodeByElement(rootNode, target.resourceId, target.text)
            ?: return errorResult("Could not get node reference for element")

        val success = inputEngine.inputText(nodeInfo, text, append)
        @Suppress("DEPRECATION")
        nodeInfo.recycle()
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Input text failed")
    }

    private fun findNodeByElement(
        root: android.view.accessibility.AccessibilityNodeInfo,
        resourceId: String?,
        text: String?
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (resourceId != null) {
            val results = root.findAccessibilityNodeInfosByViewId(resourceId)
            if (results.isNotEmpty()) return results.first()
        }
        if (text != null) {
            val results = root.findAccessibilityNodeInfosByText(text)
            if (results.isNotEmpty()) return results.first()
        }
        return null
    }

    private fun handlePressKey(args: JsonObject): McpToolCallResult {
        val key = args["key"]?.jsonPrimitive?.contentOrNull ?: return errorResult("key required")

        // Global actions first (don't need a focused node)
        val globalAction = when (key.lowercase()) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "power" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> null
        }
        if (globalAction != null) {
            service.performGlobalAction(globalAction)
            return textResult("{\"key\":\"$key\",\"latency_ms\":0}")
        }

        // For other keys, delegate to InputEngine
        val focusedNode = service.rootInActiveWindow?.findFocus(
            android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
        )
        val success = inputEngine.pressKey(key, focusedNode)
        @Suppress("DEPRECATION")
        focusedNode?.recycle()
        return if (success) textResult("{\"key\":\"$key\",\"latency_ms\":0}")
        else errorResult("Key '$key' not supported or no focused input field. Supported: back, home, recents, notifications, power (global); enter, delete/backspace, tab, escape, space, select_all, cut, copy, paste (requires focused field).")
    }

    private fun handleGlobalAction(args: JsonObject): McpToolCallResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return errorResult("action required")
        val globalAction = when (action.lowercase()) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return errorResult("Unknown action: $action. Valid: back, home, recents, notifications, quick_settings")
        }
        val success = service.performGlobalAction(globalAction)
        return if (success) textResult("{\"action\":\"$action\",\"latency_ms\":0}")
        else errorResult("Global action '$action' failed")
    }

    // =====================================================================
    // MANAGE TOOLS
    // =====================================================================

    private fun handleLaunchApp(args: JsonObject): McpToolCallResult {
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull ?: return errorResult("package_name required")
        val clearTask = args["clear_task"]?.jsonPrimitive?.booleanOrNull ?: false

        val pm = service.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: return errorResult("No launch intent found for package: $packageName")
        if (clearTask) launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        else launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(launchIntent)
        return textResult("{\"package_name\":\"$packageName\",\"latency_ms\":0}")
    }

    private fun handleCloseApp(args: JsonObject): McpToolCallResult {
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        return textResult("{\"success\":true,\"note\":\"Moved to background via HOME.\"}")
    }

    private fun handleOpenUrl(args: JsonObject): McpToolCallResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return errorResult("url required")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        service.startActivity(intent)
        return textResult("{\"url\":\"$url\",\"latency_ms\":0}")
    }

    private fun handleSetClipboard(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return errorResult("text required")
        inputEngine.setClipboardText(text)
        return textResult("{\"success\":true}")
    }

    private fun handleListApps(args: JsonObject): McpToolCallResult {
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull ?: "all"
        val pm = service.packageManager
        val packages = pm.getInstalledPackages(0)
        val filtered = when (filter) {
            "system" -> packages.filter { (it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 }
            "third_party" -> packages.filter { (it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            else -> packages
        }
        val result = buildJsonObject {
            putJsonArray("apps") {
                filtered.forEach { pkg ->
                    addJsonObject {
                        put("package_name", pkg.packageName)
                        put("version_name", pkg.versionName ?: "")
                        put("is_system", (pkg.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                    }
                }
            }
            put("count", filtered.size)
        }
        return textResult(result.toString())
    }

    private suspend fun handleInstallApp(args: JsonObject): McpToolCallResult {
        val appName = args["app_name"]?.jsonPrimitive?.contentOrNull ?: return errorResult("app_name required")
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull
        val market = args["market"]?.jsonPrimitive?.contentOrNull ?: "honor"
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 180000L
        val allowSimilarMatch = args["allow_similar_match"]?.jsonPrimitive?.booleanOrNull ?: false
        val openAfterInstall = args["open_after_install"]?.jsonPrimitive?.booleanOrNull ?: false
        val startedAt = System.currentTimeMillis()
        val steps = mutableListOf<JsonObject>()

        fun addStep(name: String, status: String, detail: String? = null, stepStartedAt: Long = System.currentTimeMillis()) {
            steps.add(buildJsonObject {
                put("name", name)
                put("status", status)
                put("duration_ms", System.currentTimeMillis() - stepStartedAt)
                detail?.let { put("detail", it) }
            })
        }

        fun installResult(status: String, extra: JsonObject = buildJsonObject {}): McpToolCallResult {
            val body = buildJsonObject {
                put("status", status)
                put("app_name", appName)
                packageName?.let { put("package_name", it) }
                put("market", market)
                put("duration_ms", System.currentTimeMillis() - startedAt)
                extra.forEach { (key, value) -> put(key, value) }
                putJsonArray("steps") { steps.forEach { add(it) } }
            }
            return if (status == "ok") textResult(body.toString()) else errorResult(body.toString())
        }

        if (market != "honor") {
            return installResult("error", buildJsonObject {
                put("error_code", "unsupported_market")
                put("message", "Only honor market is supported in this APK build")
            })
        }

        if (isPackageInstalled(packageName)) {
            addStep("check_installed", "ok", "package already installed")
            return installResult("ok", buildJsonObject {
                put("already_installed", true)
                put("installed", true)
                put("final_state", "package_installed")
            })
        }

        val launchStarted = System.currentTimeMillis()
        val launchIntent = service.packageManager.getLaunchIntentForPackage("com.hihonor.appmarket")
            ?: return installResult("error", buildJsonObject {
                put("error_code", "market_not_found")
                put("message", "Honor App Market package not found")
            })
        service.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1500)
        addStep("open_market", "ok", "foreground=com.hihonor.appmarket", launchStarted)
        handleDismissOverlay(buildJsonObject { put("timeout_ms", 1500) })

        val searchStarted = System.currentTimeMillis()
        val searchInputId = "com.hihonor.appmarket:id/et_search_content"
        val searchButtonId = "com.hihonor.appmarket:id/btn_do_search"
        val clearButtonId = "com.hihonor.appmarket:id/iv_search_clear"

        val inputNode = waitForNode(10000L) {
            findNodeByResourceId(searchInputId, 0) ?: findNodeByText("搜索", "contains", 0)
        } ?: return installResult("error", buildJsonObject {
            put("error_code", "search_input_not_found")
            put("message", "Honor App Market search input not found")
            put("last_ui_tree_compact", compactUiTree())
        })
        clickNode(inputNode)
        delay(500)
        findNodeByResourceId(clearButtonId, 0)?.let { clickNode(it); delay(300) }
        val editableNode = findNodeByResourceId(searchInputId, 0) ?: inputNode
        val setOk = inputEngine.inputText(editableNode, appName, append = false)
        if (!setOk) {
            return installResult("error", buildJsonObject {
                put("error_code", "set_search_text_failed")
                put("message", "Failed to input app name")
                put("last_ui_tree_compact", compactUiTree())
            })
        }
        val searchButton = waitForNode(5000L) { findNodeByResourceId(searchButtonId, 0) }
        if (searchButton != null) {
            clickNode(searchButton)
        } else {
            inputEngine.pressKey("enter", editableNode)
        }
        addStep("search_app", "ok", "query=$appName", searchStarted)

        val matchStarted = System.currentTimeMillis()
        val deadline = System.currentTimeMillis() + timeoutMs
        var matchedButton: AccessibilityNodeInfo? = null
        var matchedState = ""
        while (System.currentTimeMillis() < deadline) {
            handleDismissOverlay(buildJsonObject { put("timeout_ms", 500) })
            if (isPackageInstalled(packageName)) {
                addStep("wait_installed", "ok", "package installed")
                return installResult("ok", buildJsonObject {
                    put("already_installed", false)
                    put("installed", true)
                    put("final_state", "package_installed")
                })
            }

            val appNode = if (allowSimilarMatch) {
                findNodeByText(appName, "contains", 0)
            } else {
                exactAppNameNode(appName)
            }
            val pair = appNode?.let { findInstallButtonNear(it) }
            if (pair != null) {
                matchedButton = pair.first
                matchedState = pair.second
                addStep("match_result", "ok", "matched_title=$appName,state=$matchedState", matchStarted)
                break
            }
            delay(500)
        }

        val button = matchedButton ?: return installResult("error", buildJsonObject {
            put("error_code", "target_not_found")
            put("message", "Exact app result not found: $appName")
            put("foreground_app", service.rootInActiveWindow?.packageName?.toString() ?: "")
            put("last_ui_tree_compact", compactUiTree())
        })

        when {
            matchedState == "打开" -> {
                return installResult("ok", buildJsonObject {
                    put("already_installed", true)
                    put("installed", true)
                    put("final_state", "open_button_visible")
                    put("confidence", "ui")
                })
            }
            matchedState == "更新" -> {
                return installResult("ok", buildJsonObject {
                    put("already_installed", true)
                    put("installed", true)
                    put("update_available", true)
                    put("final_state", "update_button_visible")
                })
            }
            else -> {
                val tapStarted = System.currentTimeMillis()
                if (!clickNode(button)) {
                    return installResult("error", buildJsonObject {
                        put("error_code", "tap_install_failed")
                        put("message", "Failed to tap install button")
                    })
                }
                addStep("tap_install", "ok", matchedState, tapStarted)
            }
        }

        val waitStarted = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            handleDismissOverlay(buildJsonObject { put("timeout_ms", 700) })
            if (isPackageInstalled(packageName)) {
                addStep("wait_installed", "ok", "package installed", waitStarted)
                if (openAfterInstall && packageName != null) {
                    service.packageManager.getLaunchIntentForPackage(packageName)?.let {
                        service.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                return installResult("ok", buildJsonObject {
                    put("already_installed", false)
                    put("installed", true)
                    put("final_state", "package_installed")
                })
            }
            val appNode = exactAppNameNode(appName)
            val state = appNode?.let { findInstallButtonNear(it)?.second }.orEmpty()
            if (state == "打开") {
                addStep("wait_installed", "ok", "open button visible", waitStarted)
                return installResult("ok", buildJsonObject {
                    put("already_installed", false)
                    put("installed", true)
                    put("final_state", "open_button_visible")
                    put("confidence", if (packageName == null) "ui" else "ui_pending_package_check")
                })
            }
            delay(1000)
        }

        return installResult("error", buildJsonObject {
            put("error_code", "install_timeout")
            put("message", "Install did not complete within timeout")
            put("last_ui_tree_compact", compactUiTree())
        })
    }

    // =====================================================================
    // WAIT TOOLS
    // =====================================================================

    private suspend fun handleWaitForElement(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                val tree = uiTreeWalker.walkTree(rootNode)
                val found = resolveSelector(tree, text, resourceId, contentDesc)
                if (found != null) {
                    return textResult("{\"found\":true,\"element\":{\"text\":\"${found.text}\",\"bounds\":\"${found.bounds}\"}}")
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        return textResult("{\"found\":false,\"timeout_ms\":$timeoutMs}")
    }

    private suspend fun handleWaitForGone(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val rootNode = service.rootInActiveWindow
            if (rootNode == null) return textResult("{\"found\":false}")
            val tree = uiTreeWalker.walkTree(rootNode)
            val found = resolveSelector(tree, text, resourceId, contentDesc)
            if (found == null) return textResult("{\"found\":false}")
            delay(POLL_INTERVAL_MS)
        }
        return textResult("{\"found\":true,\"note\":\"Element still present after ${timeoutMs}ms\"}")
    }

    private suspend fun handleWaitForIdle(args: JsonObject): McpToolCallResult {
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L
        var lastEventTime = System.currentTimeMillis()
        val idleThreshold = 500L

        val listener = object : AccessibilityEventListener {
            override fun onEvent(event: android.view.accessibility.AccessibilityEvent) {
                lastEventTime = System.currentTimeMillis()
            }
        }
        service.registerEventListener(listener)
        return try {
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    delay(idleThreshold)
                    if (System.currentTimeMillis() - lastEventTime >= idleThreshold) break
                }
            }
            textResult("{\"idle\":true}")
        } finally {
            // Always unregister — covers normal exit, timeout, and coroutine cancellation
            service.unregisterEventListener(listener)
        }
    }

    private suspend fun handleScrollToElement(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
        val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
        val maxScrolls = args["max_scrolls"]?.jsonPrimitive?.intOrNull ?: 20

        val dm = service.resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val startY = if (direction == "down") dm.heightPixels * 0.7f else dm.heightPixels * 0.3f
        val endY = if (direction == "down") dm.heightPixels * 0.3f else dm.heightPixels * 0.7f

        repeat(maxScrolls) { scrollCount ->
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                val tree = uiTreeWalker.walkTree(rootNode)
                val found = resolveSelector(tree, text, resourceId, contentDesc)
                if (found != null) return textResult("{\"found\":true,\"scrolls\":$scrollCount}")
            }
            withTimeoutOrNull(1000L) {
                executeGestureAndWait { cb -> gestureEngine.executeSwipe(centerX, startY, centerX, endY, 300L, cb) }
            }
            delay(200)
        }
        return textResult("{\"found\":false,\"scrolls\":$maxScrolls}")
    }

    // =====================================================================
    // DEVICE / META / TEST TOOLS
    // =====================================================================

    private fun handleListDevices(): McpToolCallResult {
        val dm = service.resources.displayMetrics
        val result = buildJsonObject {
            putJsonArray("devices") {
                addJsonObject {
                    put("device_id", "local")
                    put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("android_version", Build.VERSION.RELEASE)
                    put("sdk_int", Build.VERSION.SDK_INT)
                    put("screen_width", dm.widthPixels)
                    put("screen_height", dm.heightPixels)
                    put("status", "connected")
                    put("all_permissions_ready", true)
                }
            }
        }
        return textResult(result.toString())
    }

    private fun handleSearchTools(args: JsonObject): McpToolCallResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return errorResult("query required")
        val category = args["category"]?.jsonPrimitive?.contentOrNull
        val allTools = McpToolRegistry.getAllTools()
        val matches = allTools.filter { tool ->
            (tool.name.contains(query, ignoreCase = true) || tool.description.contains(query, ignoreCase = true)) &&
            (category == null || tool.name.contains(category, ignoreCase = true))
        }
        val result = buildJsonObject {
            putJsonArray("tools") {
                matches.forEach { t ->
                    addJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                    }
                }
            }
            put("count", matches.size)
        }
        return textResult(result.toString())
    }

    private fun handleDescribeTools(args: JsonObject): McpToolCallResult {
        val toolNames = args["tools"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val result = buildJsonObject {
            putJsonArray("tools") {
                toolNames.forEach { name ->
                    val tool = McpToolRegistry.getTool(name)
                    if (tool != null) {
                        addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                        }
                    }
                }
            }
        }
        return textResult(result.toString())
    }

    private fun handleEnableEvents(args: JsonObject): McpToolCallResult {
        val enable = args["enable"]?.jsonPrimitive?.booleanOrNull ?: return errorResult("enable (boolean) required")
        service.setEventsEnabled(enable)
        return textResult("{\"events_enabled\":$enable}")
    }

    private fun handleGetDeviceInfo(): McpToolCallResult {
        val dm = service.resources.displayMetrics
        val result = buildJsonObject {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("screen_width", dm.widthPixels)
            put("screen_height", dm.heightPixels)
            put("density_dpi", dm.densityDpi)
            put("density", dm.density)
        }
        return textResult(result.toString())
    }

    private fun handleGetInstalledPackage(args: JsonObject): McpToolCallResult {
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("package_name required")
        return textResult(packageInfoJson(packageName).toString())
    }

    private fun handleGetScreenState(): McpToolCallResult =
        textResult(service.getScreenStateJson().toString())

    private fun handleWakeScreen(): McpToolCallResult {
        val result = service.wakeScreenJson()
        return if (result["status"]?.jsonPrimitive?.contentOrNull == "ok") {
            textResult(result.toString())
        } else {
            errorResult(result.toString())
        }
    }

    private suspend fun handleUnlockDevice(args: JsonObject): McpToolCallResult {
        val pin = args["pin"]?.jsonPrimitive?.contentOrNull ?: return errorResult("pin required")
        if (!pin.all { it.isDigit() }) {
            return errorResult(buildJsonObject {
                put("status", "error")
                put("error_code", "UNSUPPORTED_PIN_FORMAT")
                put("message", "Only numeric PIN unlock is supported")
                put("screen_state", service.getScreenStateJson())
            }.toString())
        }

        val wake = service.wakeScreenJson()
        if (wake["status"]?.jsonPrimitive?.contentOrNull != "ok") {
            return errorResult(buildJsonObject {
                put("status", "error")
                put("error_code", "WAKE_SCREEN_FAILED")
                put("message", "Could not wake screen before unlock")
                put("wake_result", wake)
                put("screen_state", service.getScreenStateJson())
            }.toString())
        }
        delay(800)

        var state = service.getScreenStateJson()
        if (state["keyguard_locked"]?.jsonPrimitive?.booleanOrNull == false) {
            return textResult(buildJsonObject {
                put("status", "ok")
                put("unlocked", true)
                put("already_unlocked", true)
                put("screen_state", state)
            }.toString())
        }

        val dm = service.resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.97f
        val endY = dm.heightPixels * 0.05f
        val swiped = executeGestureAndWait { cb ->
            gestureEngine.executeSwipe(centerX, startY, centerX, endY, 900L, cb)
        }
        if (!swiped) {
            return errorResult(buildJsonObject {
                put("status", "error")
                put("error_code", "KEYGUARD_SWIPE_FAILED")
                put("message", "Unable to swipe up on lockscreen")
                put("screen_state", service.getScreenStateJson())
            }.toString())
        }
        delay(900)
        if (!isPinKeypadVisible()) {
            executeGestureAndWait { cb ->
                gestureEngine.executeSwipe(centerX, startY, centerX, endY, 900L, cb)
            }
            delay(900)
        }

        val digitsOk = inputPinDigits(pin)
        delay(1200)
        state = service.getScreenStateJson()
        val unlocked = state["keyguard_locked"]?.jsonPrimitive?.booleanOrNull == false
        val result = buildJsonObject {
            put("status", if (unlocked) "ok" else "error")
            put("unlocked", unlocked)
            put("pin_input_attempted", digitsOk)
            if (!unlocked) {
                put("error_code", if (digitsOk) "KEYGUARD_UNLOCK_NOT_CONFIRMED" else "KEYGUARD_INPUT_BLOCKED")
                put("message", if (digitsOk) {
                    "PIN was entered but keyguard is still locked"
                } else {
                    "PIN input blocked or lockscreen keypad was not accessible"
                })
            }
            put("screen_state", state)
        }
        return if (unlocked) textResult(result.toString()) else errorResult(result.toString())
    }

    private fun isPinKeypadVisible(): Boolean =
        findNodeByResourceId("com.android.systemui:id/fixedPinEntry", 0) != null ||
            findNodeByResourceId("com.android.systemui:id/keyguard_fixed_length_pin_view", 0) != null ||
            findNodeByResourceId("com.android.systemui:id/key9", 0) != null

    private suspend fun inputPinDigits(pin: String): Boolean {
        clearPinEntry()
        var clickedAny = false
        for (digit in pin) {
            val node = findNodeByResourceId("com.android.systemui:id/key$digit", 0)
                ?: findNodeByText(digit.toString(), "exact", 0)
            val clicked = if (node != null) {
                clickNode(node)
            } else {
                tapApproximateKeypadDigit(digit)
            }
            clickedAny = clickedAny || clicked
            if (!clicked) return false
            delay(300)
        }
        findNodeByText("OK", "exact", 0)?.let {
            clickNode(it)
            delay(200)
        } ?: findNodeByText("确认", "exact", 0)?.let {
            clickNode(it)
            delay(200)
        }
        return clickedAny
    }

    private suspend fun clearPinEntry() {
        repeat(8) {
            val entryText = findNodeByResourceId("com.android.systemui:id/fixedPinEntry", 0)
                ?.text
                ?.toString()
                .orEmpty()
            if (entryText.isEmpty()) return
            val deleteNode = findNodeByResourceId("com.android.systemui:id/delete_button", 0)
                ?: findNodeByText("删除", "exact", 0)
                ?: return
            if (!clickNode(deleteNode)) return
            delay(120)
        }
    }

    private suspend fun tapApproximateKeypadDigit(digit: Char): Boolean {
        val dm = service.resources.displayMetrics
        val columns = listOf(dm.widthPixels * 0.25f, dm.widthPixels * 0.5f, dm.widthPixels * 0.75f)
        val rows = listOf(dm.heightPixels * 0.58f, dm.heightPixels * 0.68f, dm.heightPixels * 0.78f, dm.heightPixels * 0.88f)
        val position = when (digit) {
            '1' -> 0 to 0
            '2' -> 1 to 0
            '3' -> 2 to 0
            '4' -> 0 to 1
            '5' -> 1 to 1
            '6' -> 2 to 1
            '7' -> 0 to 2
            '8' -> 1 to 2
            '9' -> 2 to 2
            '0' -> 1 to 3
            else -> return false
        }
        return executeGestureAndWait { cb ->
            gestureEngine.executeTap(columns[position.first], rows[position.second], cb)
        }
    }

    private fun handleKeepAwake(args: JsonObject): McpToolCallResult {
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return errorResult("enabled (boolean) required")
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "partial"
        val result = service.setExecutorKeepAwakeJson(enabled, mode)
        return textResult(result.toString())
    }

}
