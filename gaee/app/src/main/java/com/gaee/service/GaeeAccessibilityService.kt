package com.gaee.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.gaee.model.ToolResult

class GaeeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: GaeeAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tapElement(targetText: String): ToolResult {
        val root = rootInActiveWindow ?: return ToolResult(false, "Could not read the screen.")
        val node = findNodeByText(root, targetText)
            ?: return ToolResult(false, "Could not find \"$targetText\" on screen.")

        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        return if (result) ToolResult(true, "Tapped $targetText.")
        else ToolResult(false, "Could not tap $targetText.")
    }

    fun typeText(text: String): ToolResult {
        val root = rootInActiveWindow ?: return ToolResult(false, "Could not read the screen.")
        val focused = findFocusedEditText(root)
            ?: return ToolResult(false, "No text field is active.")

        val args = Bundle().apply {
            putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        return if (result) ToolResult(true, "Typed: $text")
        else ToolResult(false, "Could not type text.")
    }

    fun scroll(direction: String): ToolResult {
        val root = rootInActiveWindow ?: return ToolResult(false, "Could not read the screen.")
        val action = if (direction == "up")
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        else
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD

        val scrolled = scrollFirstScrollable(root, action)
        return if (scrolled) ToolResult(true, "Scrolled $direction.")
        else ToolResult(false, "Nothing to scroll.")
    }

    fun pressBack(): ToolResult {
        performGlobalAction(GLOBAL_ACTION_BACK)
        return ToolResult(true, "Went back.")
    }

    fun findElement(targetText: String): ToolResult {
        val root = rootInActiveWindow ?: return ToolResult(false, "Could not read the screen.")
        val node = findNodeByText(root, targetText)
        return if (node != null) {
            node.recycle()
            ToolResult(true, "Found $targetText on screen.")
        } else {
            ToolResult(false, "Could not find $targetText on screen.")
        }
    }

    /** Dump all readable text on the current screen — gives the LLM "eyes". */
    fun readScreen(): ToolResult {
        val root = rootInActiveWindow ?: return ToolResult(false, "Could not read the screen.")
        val collected = LinkedHashSet<String>()
        collectText(root, collected)
        val screenText = collected.joinToString("\n")
        val appName = root.packageName?.toString() ?: ""
        return ToolResult(
            success = true,
            speakAfter = if (screenText.isBlank()) "There is nothing readable on the screen." else screenText,
            data = mapOf("screenText" to screenText, "appName" to appName)
        )
    }

    /** Swipe in a direction across the middle of the screen — for carousels, galleries, chats. */
    fun swipe(direction: String): ToolResult {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val path = Path()
        when (direction.lowercase()) {
            "up" -> { path.moveTo(w / 2, h * 0.7f); path.lineTo(w / 2, h * 0.3f) }
            "down" -> { path.moveTo(w / 2, h * 0.3f); path.lineTo(w / 2, h * 0.7f) }
            "left" -> { path.moveTo(w * 0.8f, h / 2); path.lineTo(w * 0.2f, h / 2) }
            "right" -> { path.moveTo(w * 0.2f, h / 2); path.lineTo(w * 0.8f, h / 2) }
            else -> return ToolResult(false, "Unknown swipe direction: $direction")
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        return if (dispatched) ToolResult(true, "Swiped $direction.")
        else ToolResult(false, "Could not swipe $direction.")
    }

    /** Tap at exact coordinates — fallback when no text label is findable. */
    fun tapAt(x: Float, y: Float): ToolResult {
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        return if (dispatched) ToolResult(true, "Tapped the screen.")
        else ToolResult(false, "Could not tap the screen.")
    }

    /** Block until [targetText] appears on screen or [timeoutMs] elapses — for app transitions. */
    fun waitForScreen(targetText: String, timeoutMs: Long): ToolResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                val node = findNodeByText(root, targetText)
                if (node != null) {
                    node.recycle()
                    return ToolResult(true, "Found $targetText on screen.")
                }
            }
            Thread.sleep(150)
        }
        return ToolResult(false, "Timed out waiting for \"$targetText\".")
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        node ?: return
        // Skip invisible noise (progress bars, dividers carry no useful label)
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotBlank()) out.add(text)
        else if (desc.isNotBlank()) out.add(desc)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
            child.recycle()
        }
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val lowerText = text.lowercase()
        val byText = root.findAccessibilityNodeInfosByText(text)
        if (byText.isNotEmpty()) return byText[0]

        // Fallback: traverse tree for partial match
        return traverseForText(root, lowerText)
    }

    private fun traverseForText(node: AccessibilityNodeInfo, lowerText: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (nodeText.contains(lowerText) || desc.contains(lowerText)) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = traverseForText(child, lowerText)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findFocusedEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isFocused && root.className?.contains("EditText") == true) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findFocusedEditText(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun scrollFirstScrollable(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable) return node.performAction(action)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scrollFirstScrollable(child, action)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }
}
