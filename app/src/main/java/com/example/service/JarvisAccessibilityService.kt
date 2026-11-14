package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        JarvisStateBus.appendLog("ACCESSIBILITY", "JarvisAccessibilityService connected and active.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Lightweight passive hook: zero overhead while idle to preserve Galaxy A10 2GB RAM
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        super.onDestroy()
    }

    /**
     * Clicks any visible screen node or button matching `targetText` (case-insensitive).
     */
    fun clickByText(targetText: String): Boolean {
        val query = targetText.trim()
        if (query.isEmpty()) return false

        val root = rootInActiveWindow ?: return false
        try {
            val directMatches = root.findAccessibilityNodeInfosByText(query)
            if (!directMatches.isNullOrEmpty()) {
                for (node in directMatches) {
                    if (performClickUpHierarchy(node)) {
                        return true
                    }
                }
            }

            val lowerQuery = query.lowercase(Locale.US)
            val fallbackMatch = findNodeRecursive(root, lowerQuery)
            if (fallbackMatch != null) {
                return performClickUpHierarchy(fallbackMatch)
            }
        } catch (_: Exception) {
            return false
        }
        return false
    }

    /**
     * Scans the active YouTube search results screen and clicks the first playable video item.
     * Uses both AccessibilityNodeInfo hierarchy matching and a fallback Litho canvas tap gesture.
     */
    fun clickFirstYoutubeVideoResult(query: String): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val minTopY = (screenHeight * 0.18f).toInt()
        val maxBottomY = (screenHeight * 0.88f).toInt()

        val root = rootInActiveWindow
        if (root != null) {
            try {
                val queryKeywords = query.lowercase(Locale.US)
                    .split(" ")
                    .map { it.trim() }
                    .filter { it.length >= 3 }

                // 1. Try finding a clickable video card below the search bar matching the query or duration markers
                val candidate = findFirstYoutubeVideoNode(root, queryKeywords, minTopY, maxBottomY)
                if (candidate != null && performClickUpHierarchy(candidate)) {
                    return true
                }
            } catch (_: Exception) {
            }
        }

        // 2. Litho / Custom Surface Fallback: Tap the center of the first video thumbnail card below the filter chips
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val tapX = screenWidth * 0.48f
            val tapY = screenHeight * 0.36f
            val tapPath = Path().apply {
                moveTo(tapX, tapY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(tapPath, 0L, 85L))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun findFirstYoutubeVideoNode(
        node: AccessibilityNodeInfo?,
        queryKeywords: List<String>,
        minTopY: Int,
        maxBottomY: Int
    ): AccessibilityNodeInfo? {
        if (node == null) return null

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val viewId = node.viewIdResourceName?.lowercase(Locale.US).orEmpty()
        val text = node.text?.toString()?.lowercase(Locale.US).orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase(Locale.US).orEmpty()
        val combined = "$text $desc"

        val isBelowHeader = bounds.top in minTopY..maxBottomY && bounds.height() > 60
        val isSearchOrFilterControl = viewId.contains("search") ||
            viewId.contains("chip") ||
            desc == "search" ||
            desc == "voice search" ||
            desc == "navigate up"

        if (isBelowHeader && !isSearchOrFilterControl) {
            val matchesKeyword = queryKeywords.isNotEmpty() && queryKeywords.any { combined.contains(it) }
            val looksLikeVideoRow = combined.contains("views") ||
                combined.contains("minutes") ||
                combined.contains("seconds") ||
                combined.contains("ago") ||
                viewId.contains("video_info") ||
                viewId.contains("thumbnail")

            if ((matchesKeyword || looksLikeVideoRow) && (node.isClickable || node.parent?.isClickable == true)) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findFirstYoutubeVideoNode(child, queryKeywords, minTopY, maxBottomY)
            if (match != null) return match
        }
        return null
    }

    /**
     * Automatically clicks the WhatsApp Send button (`com.whatsapp:id/send` or contentDescription "Send").
     */
    fun clickWhatsAppSendButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            // 1. Check direct view IDs for WhatsApp and WhatsApp Business
            val sendIds = listOf(
                "com.whatsapp:id/send",
                "com.whatsapp.w4b:id/send",
                "com.whatsapp:id/ send_container"
            )
            for (id in sendIds) {
                val matches = root.findAccessibilityNodeInfosByViewId(id)
                if (!matches.isNullOrEmpty()) {
                    for (node in matches) {
                        if (performClickUpHierarchy(node)) return true
                    }
                }
            }

            // 2. Check contentDescription / text "Send" or "भेजें"
            val sendNode = findSendButtonRecursive(root)
            if (sendNode != null && performClickUpHierarchy(sendNode)) {
                return true
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun findSendButtonRecursive(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
        val text = node.text?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
        val viewId = node.viewIdResourceName?.lowercase(Locale.US).orEmpty()

        if (desc == "send" || desc == "भेजें" || text == "send" || viewId.endsWith(":id/send")) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendButtonRecursive(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Types text into the currently focused or editable input field on screen.
     */
    fun inputTextIntoEditableField(textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editable = findEditableNodeRecursive(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                textToType
            )
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditableNodeRecursive(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNodeRecursive(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Executes global navigation or vertical scroll gestures:
     * home | back | recents | notifications | scroll_down | scroll_up
     */
    fun executeNavigation(target: String): Boolean {
        return when (target.trim().lowercase(Locale.US)) {
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "recents", "recent" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications", "notification" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "scroll_down" -> performScroll(forward = true)
            "scroll_up" -> performScroll(forward = false)
            else -> false
        }
    }

    private fun performClickUpHierarchy(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.isEnabled) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun findNodeRecursive(
        node: AccessibilityNodeInfo?,
        lowerQuery: String
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val nodeText = node.text?.toString()?.lowercase(Locale.US).orEmpty()
        val nodeDesc = node.contentDescription?.toString()?.lowercase(Locale.US).orEmpty()

        if (nodeText.contains(lowerQuery) || nodeDesc.contains(lowerQuery)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findNodeRecursive(child, lowerQuery)
            if (match != null) return match
        }
        return null
    }

    private fun performScroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            val scrollable = findScrollableNode(root)
            if (scrollable != null) {
                val action = if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                if (scrollable.performAction(action)) {
                    return true
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val displayMetrics = resources.displayMetrics
            val centerX = displayMetrics.widthPixels / 2f
            val startY = if (forward) displayMetrics.heightPixels * 0.75f else displayMetrics.heightPixels * 0.25f
            val endY = if (forward) displayMetrics.heightPixels * 0.25f else displayMetrics.heightPixels * 0.75f

            val swipePath = Path().apply {
                moveTo(centerX, startY)
                lineTo(centerX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipePath, 0L, 280L))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findScrollableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }
}
