package com.apexpanda.node

import android.content.Intent
import android.os.Build
import android.view.WindowManager
import android.util.DisplayMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * UI 自动化：点击、输入、滑动、返回、Home、dump、OCR、分析、长按、启动应用
 */
class AutomationHandler(private val context: android.content.Context) {

    private val service: ApexPandaAccessibilityService?
        get() = ApexPandaAccessibilityService.instance

    private val ocrHandler by lazy { OcrHandler(context) }

    private fun notEnabledError(): Map<String, Any?> = mapOf(
        "error" to "AUTOMATION_DISABLED: 请在手机 设置→无障碍→ApexPanda 节点 中开启辅助功能"
    )

    suspend fun tap(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val text = params["text"]?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            val svc = service
            if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val ok = svc.tapByText(text)
                if (ok) return@withContext mapOf("ok" to true, "action" to "tap", "by" to "accessibility", "text" to text)
            }
            val coords = ocrHandler.findTextCoordinates(text)
            if (coords != null) {
                val svc2 = service
                if (svc2 != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val ok = svc2.tap(coords.first, coords.second)
                    return@withContext if (ok) mapOf("ok" to true, "action" to "tap", "by" to "ocr", "text" to text)
                    else mapOf("error" to "OCR 找到文字但点击失败")
                }
                return@withContext mapOf("error" to "OCR 找到「$text」但需开启辅助功能才能执行点击")
            }
            return@withContext mapOf("error" to "未找到包含「$text」的元素")
        }

        val x = (params["x"] as? Number)?.toFloat()
        val y = (params["y"] as? Number)?.toFloat()
        if (x != null && y != null) {
            val svc = service ?: return@withContext notEnabledError()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext mapOf("error" to "需要 Android 7.0 及以上")
            val ok = svc.tap(x, y)
            return@withContext if (ok) mapOf("ok" to true, "action" to "tap", "x" to x, "y" to y)
            else mapOf("error" to "点击执行失败")
        }

        return@withContext mapOf("error" to "请提供 text 或 x、y")
    }

    suspend fun input(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val svc = service ?: return@withContext notEnabledError()
        val text = params["text"]?.toString() ?: return@withContext mapOf("error" to "缺少 text 参数")
        val ok = svc.inputText(text)
        return@withContext if (ok) mapOf("ok" to true, "action" to "input", "text" to text)
        else mapOf("error" to "输入失败，请确保当前有可输入框获得焦点，或先点击输入框")
    }

    suspend fun swipe(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val svc = service ?: return@withContext notEnabledError()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext mapOf("error" to "需要 Android 7.0 及以上")

        val fromX = (params["fromX"] as? Number)?.toFloat() ?: return@withContext mapOf("error" to "缺少 fromX")
        val fromY = (params["fromY"] as? Number)?.toFloat() ?: return@withContext mapOf("error" to "缺少 fromY")
        val toX = (params["toX"] as? Number)?.toFloat() ?: return@withContext mapOf("error" to "缺少 toX")
        val toY = (params["toY"] as? Number)?.toFloat() ?: return@withContext mapOf("error" to "缺少 toY")
        val duration = ((params["duration"] as? Number)?.toLong() ?: 300L).coerceIn(50, 2000)

        val ok = svc.swipe(fromX, fromY, toX, toY, duration)
        return@withContext if (ok) mapOf("ok" to true, "action" to "swipe", "fromX" to fromX, "fromY" to fromY, "toX" to toX, "toY" to toY)
        else mapOf("error" to "滑动执行失败")
    }

    suspend fun back(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val svc = service ?: return@withContext notEnabledError()
        val ok = svc.back()
        return@withContext if (ok) mapOf("ok" to true, "action" to "back") else mapOf("error" to "返回执行失败")
    }

    suspend fun home(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val svc = service ?: return@withContext notEnabledError()
        val ok = svc.home()
        return@withContext if (ok) mapOf("ok" to true, "action" to "home") else mapOf("error" to "Home 执行失败")
    }

    suspend fun dump(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val svc = service ?: return@withContext notEnabledError()
        val tree = svc.dumpTree()
        return@withContext mapOf("ok" to true, "action" to "dump", "tree" to tree)
    }

    suspend fun ocr(params: Map<String, Any?>): Map<String, Any?> = ocrHandler.ocr(params)

    suspend fun analyze(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val dumpTree = service?.dumpTree() ?: "无法获取 accessibility 树（请开启辅助功能）"
        val ocrResult = ocrHandler.ocr(params + mapOf("includeBase64" to (params["includeBase64"] ?: false)))
        val ocrItems = (ocrResult["items"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        val ocrText = ocrResult["fullText"]?.toString() ?: ""
        return@withContext mapOf(
            "ok" to true,
            "accessibility" to dumpTree,
            "ocr" to mapOf("items" to ocrItems, "fullText" to ocrText),
            "base64" to (if (params["includeBase64"] == true) ocrResult["base64"] else null)
        )
    }

    suspend fun longPress(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val svc = service ?: return@withContext notEnabledError()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext mapOf("error" to "需要 Android 7.0 及以上")
        val x = (params["x"] as? Number)?.toFloat() ?: return@withContext mapOf("error" to "缺少 x")
        val y = (params["y"] as? Number)?.toFloat() ?: return@withContext mapOf("error" to "缺少 y")
        val duration = ((params["duration"] as? Number)?.toLong() ?: 500L).coerceIn(200, 2000)
        val ok = svc.longPress(x, y, duration)
        return@withContext if (ok) mapOf("ok" to true, "action" to "longPress", "x" to x, "y" to y)
        else mapOf("error" to "长按执行失败")
    }

    suspend fun launch(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val pkg = params["package"]?.toString()?.trim()
            ?: return@withContext mapOf("error" to "缺少 package 参数，如 com.tencent.mm")
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return@withContext mapOf("error" to "未找到应用: $pkg")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return@withContext mapOf("ok" to true, "action" to "launch", "package" to pkg)
        } catch (e: Exception) {
            return@withContext mapOf("error" to (e.message ?: "启动失败"))
        }
    }

    private fun getDisplaySize(): Pair<Int, Int> {
        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    suspend fun scroll(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val svc = service ?: return@withContext notEnabledError()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext mapOf("error" to "需要 Android 7.0 及以上")
        val dir = params["direction"]?.toString()?.lowercase() ?: return@withContext mapOf("error" to "缺少 direction: up/down/left/right")
        val (w, h) = getDisplaySize()
        val (fromX, fromY, toX, toY) = when (dir) {
            "down" -> arrayOf(w / 2f, h * 0.75f, w / 2f, h * 0.25f)
            "up" -> arrayOf(w / 2f, h * 0.25f, w / 2f, h * 0.75f)
            "left" -> arrayOf(w * 0.75f, h / 2f, w * 0.25f, h / 2f)
            "right" -> arrayOf(w * 0.25f, h / 2f, w * 0.75f, h / 2f)
            else -> return@withContext mapOf("error" to "direction 须为 up/down/left/right")
        }
        val ok = svc.swipe(fromX, fromY, toX, toY, 250)
        return@withContext if (ok) mapOf("ok" to true, "action" to "scroll", "direction" to dir)
        else mapOf("error" to "滚动执行失败")
    }

    suspend fun waitFor(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val text = params["text"]?.toString()?.trim() ?: return@withContext mapOf("error" to "缺少 text 参数")
        val timeoutMs = ((params["timeout"] as? Number)?.toLong() ?: 10000L).coerceIn(500, 60000)
        val pollInterval = 500L
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val svc = service
            if (svc != null) {
                val tree = svc.dumpTree()
                if (tree.contains(text, ignoreCase = true)) {
                    return@withContext mapOf("ok" to true, "action" to "waitFor", "text" to text, "foundIn" to "accessibility")
                }
            }
            val ocrResult = ocrHandler.ocr(mapOf("maxWidth" to 1080, "includeBase64" to false))
            val fullText = ocrResult["fullText"]?.toString() ?: ""
            if (fullText.contains(text, ignoreCase = true)) {
                val coords = ocrHandler.findTextCoordinates(text)
                return@withContext mapOf(
                    "ok" to true, "action" to "waitFor", "text" to text, "foundIn" to "ocr",
                    "centerX" to (coords?.first), "centerY" to (coords?.second)
                )
            }
            delay(pollInterval)
        }
        return@withContext mapOf("error" to "超时 ${timeoutMs}ms 内未找到「$text」")
    }

    suspend fun sequence(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val actionsRaw = params["actions"]
        val actions = when (actionsRaw) {
            is List<*> -> actionsRaw.filterIsInstance<Map<*, *>>()
            else -> return@withContext mapOf("error" to "缺少 actions 数组，如 [{action:\"tap\",text:\"登录\"},{action:\"input\",text:\"xxx\"}]")
        }
        if (actions.isEmpty()) return@withContext mapOf("error" to "actions 不能为空")
        val results = mutableListOf<Map<String, Any?>>()
        for ((i, action) in actions.withIndex()) {
            val act = (action["action"] ?: action["type"])?.toString() ?: continue
            val actParams = (action["params"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value }
                ?: action.filterKeys { it != "action" && it != "type" }.mapKeys { it.key.toString() }.mapValues { it.value }
            val result = when (act) {
                "tap" -> tap(actParams)
                "input" -> input(actParams)
                "swipe" -> swipe(actParams)
                "back" -> back(actParams)
                "home" -> home(actParams)
                "longPress" -> longPress(actParams)
                "launch" -> launch(actParams)
                "scroll" -> scroll(actParams)
                "waitFor" -> waitFor(actParams)
                else -> mapOf("error" to "未知 action: $act")
            }
            results.add(mapOf("step" to (i + 1), "action" to act, "result" to result))
            if (result["error"] != null) {
                return@withContext mapOf(
                    "ok" to false,
                    "error" to "第 ${i + 1} 步失败: ${result["error"]}",
                    "results" to results
                )
            }
            delay(300)
        }
        return@withContext mapOf("ok" to true, "action" to "sequence", "steps" to actions.size, "results" to results)
    }

    companion object {
        private const val TAG = "AutomationHandler"
    }
}
