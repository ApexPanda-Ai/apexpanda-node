package com.apexpanda.node

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * 无障碍服务：用于 UI 自动化（点击、滑动、输入、dump 节点树）
 * 用户需在 设置→无障碍→ApexPanda 节点 中手动开启
 */
class ApexPandaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 是否已连接并可执行操作 */
    fun isReady(): Boolean = rootInActiveWindow != null

    /** 在指定坐标点击 */
    @RequiresApi(Build.VERSION_CODES.N)
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 10)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** 长按 (x,y)，durationMs 毫秒，默认 500 */
    @RequiresApi(Build.VERSION_CODES.N)
    fun longPress(x: Float, y: Float, durationMs: Long = 500): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** 滑动：从 (fromX,fromY) 到 (toX,toY)，durationMs 毫秒 */
    @RequiresApi(Build.VERSION_CODES.N)
    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** 执行系统返回 */
    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** 执行系统 Home */
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** 递归 dump 节点树为可读文本，供 Agent 理解当前界面 */
    fun dumpTree(): String {
        val root = rootInActiveWindow ?: return "{\"error\":\"无法获取窗口，请确保辅助功能已开启\"}"
        return dumpNode(root, 0)
    }

    private fun dumpNode(node: AccessibilityNodeInfo, indent: Int): String {
        val sb = StringBuilder()
        val pad = "  ".repeat(indent)
        val text = node.text?.toString()?.take(100)?.replace("\n", " ") ?: ""
        val desc = node.contentDescription?.toString()?.take(100)?.replace("\n", " ") ?: ""
        val clickable = if (node.isClickable) " [可点击]" else ""
        val editable = if (node.isEditable) " [可输入]" else ""
        val bounds = run {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            "[${r.left},${r.top}-${r.right},${r.bottom}]"
        }
        val id = node.viewIdResourceName ?: ""
        sb.append("$pad• $bounds")
        if (text.isNotEmpty()) sb.append(" text=\"$text\"")
        if (desc.isNotEmpty()) sb.append(" desc=\"$desc\"")
        if (id.isNotEmpty()) sb.append(" id=$id")
        sb.append("$clickable$editable\n")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(dumpNode(child, indent + 1))
            child.recycle()
        }
        return sb.toString()
    }

    /** 按 text 或 desc 查找节点并点击第一个匹配 */
    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, text) ?: return false
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        node.recycle()
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) tap(cx, cy) else false
    }

    /** 向 focused 或第一个 editable 节点输入文字（先聚焦再通过 IME 输入，需配合 InputMethod）*/
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var target: AccessibilityNodeInfo? = null
        var focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null) target = focus
        if (target == null) target = findEditableNode(root)
        target ?: return false
        val arguments = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        target.recycle()
        focus?.recycle()
        return ok
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val t = node.text?.toString() ?: ""
        val d = node.contentDescription?.toString() ?: ""
        if (t.contains(text, ignoreCase = true) || d.contains(text, ignoreCase = true)) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, text)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    companion object {
        private const val TAG = "ApexAccessibility"
        @Volatile var instance: ApexPandaAccessibilityService? = null
    }
}
