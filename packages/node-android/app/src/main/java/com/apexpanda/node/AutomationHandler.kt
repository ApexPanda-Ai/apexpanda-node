package com.apexpanda.node

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
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
    private val imageMatcher by lazy { ImageMatcher(context) }

    private fun notEnabledError(): Map<String, Any?> = mapOf(
        "error" to "AUTOMATION_DISABLED: 请在手机 设置→无障碍→ApexPanda 节点 中开启辅助功能"
    )

    /** 截屏并返回 JPEG base64，供失败时附加到错误结果 */
    suspend fun captureScreenshotBase64(maxWidth: Int = 1080): String? = withContext(Dispatchers.IO) {
        val bitmap = ScreenCaptureHelper.captureBitmap(context, maxWidth) ?: return@withContext null
        val baos = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        bitmap.recycle()
        Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    suspend fun tap(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val svc = service
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val id = params["id"]?.toString()?.trim()
            if (!id.isNullOrEmpty()) {
                val ok = svc.tapByResourceId(id)
                return@withContext if (ok) mapOf("ok" to true, "action" to "tap", "by" to "resourceId", "id" to id)
                else mapOf("error" to "未找到 resourceId=\"$id\" 的元素")
            }
            val className = params["className"]?.toString()?.trim()
            if (!className.isNullOrEmpty()) {
                val ok = svc.tapByClassName(className)
                return@withContext if (ok) mapOf("ok" to true, "action" to "tap", "by" to "className", "className" to className)
                else mapOf("error" to "未找到 className=\"$className\" 的元素")
            }
            val contentDesc = params["contentDesc"]?.toString()?.trim()
            if (!contentDesc.isNullOrEmpty()) {
                val ok = svc.tapByContentDesc(contentDesc)
                return@withContext if (ok) mapOf("ok" to true, "action" to "tap", "by" to "contentDesc", "contentDesc" to contentDesc)
                else mapOf("error" to "未找到 contentDesc=\"$contentDesc\" 的元素")
            }
        }

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

    suspend fun doubleTap(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val svc = service
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val id = params["id"]?.toString()?.trim()
            if (!id.isNullOrEmpty()) {
                val root = svc.rootInActiveWindow ?: return@withContext notEnabledError()
                val node = findNodeByResourceId(root, id)
                if (node != null) {
                    val r = android.graphics.Rect()
                    node.getBoundsInScreen(r)
                    node.recycle()
                    val cx = (r.left + r.right) / 2f
                    val cy = (r.top + r.bottom) / 2f
                    val ok = svc.doubleTap(cx, cy)
                    return@withContext if (ok) mapOf("ok" to true, "action" to "doubleTap", "by" to "resourceId", "id" to id)
                    else mapOf("error" to "双击执行失败")
                }
                return@withContext mapOf("error" to "未找到 resourceId=\"$id\" 的元素")
            }
            val className = params["className"]?.toString()?.trim()
            if (!className.isNullOrEmpty()) {
                val root = svc.rootInActiveWindow ?: return@withContext notEnabledError()
                val node = findNodeByClassName(root, className)
                if (node != null) {
                    val r = android.graphics.Rect()
                    node.getBoundsInScreen(r)
                    node.recycle()
                    val cx = (r.left + r.right) / 2f
                    val cy = (r.top + r.bottom) / 2f
                    val ok = svc.doubleTap(cx, cy)
                    return@withContext if (ok) mapOf("ok" to true, "action" to "doubleTap", "by" to "className", "className" to className)
                    else mapOf("error" to "双击执行失败")
                }
                return@withContext mapOf("error" to "未找到 className=\"$className\" 的元素")
            }
            val contentDesc = params["contentDesc"]?.toString()?.trim()
            if (!contentDesc.isNullOrEmpty()) {
                val root = svc.rootInActiveWindow ?: return@withContext notEnabledError()
                val node = findNodeByContentDesc(root, contentDesc)
                if (node != null) {
                    val r = android.graphics.Rect()
                    node.getBoundsInScreen(r)
                    node.recycle()
                    val cx = (r.left + r.right) / 2f
                    val cy = (r.top + r.bottom) / 2f
                    val ok = svc.doubleTap(cx, cy)
                    return@withContext if (ok) mapOf("ok" to true, "action" to "doubleTap", "by" to "contentDesc", "contentDesc" to contentDesc)
                    else mapOf("error" to "双击执行失败")
                }
                return@withContext mapOf("error" to "未找到 contentDesc=\"$contentDesc\" 的元素")
            }
        }

        val text = params["text"]?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val root = svc.rootInActiveWindow
                if (root != null) {
                    val node = findNodeByText(root, text)
                    if (node != null) {
                        val r = android.graphics.Rect()
                        node.getBoundsInScreen(r)
                        node.recycle()
                        val cx = (r.left + r.right) / 2f
                        val cy = (r.top + r.bottom) / 2f
                        val ok = svc.doubleTap(cx, cy)
                        return@withContext if (ok) mapOf("ok" to true, "action" to "doubleTap", "by" to "accessibility", "text" to text)
                        else mapOf("error" to "双击执行失败")
                    }
                }
            }
            val coords = ocrHandler.findTextCoordinates(text)
            if (coords != null) {
                val svc2 = service
                if (svc2 != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val ok = svc2.doubleTap(coords.first.toFloat(), coords.second.toFloat())
                    return@withContext if (ok) mapOf("ok" to true, "action" to "doubleTap", "by" to "ocr", "text" to text)
                    else mapOf("error" to "OCR 找到文字但双击失败")
                }
            }
            return@withContext mapOf("error" to "未找到包含「$text」的元素")
        }
        val x = (params["x"] as? Number)?.toFloat()
        val y = (params["y"] as? Number)?.toFloat()
        if (x != null && y != null) {
            val svc = service ?: return@withContext notEnabledError()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext mapOf("error" to "需要 Android 7.0 及以上")
            val ok = svc.doubleTap(x, y)
            return@withContext if (ok) mapOf("ok" to true, "action" to "doubleTap", "x" to x, "y" to y)
            else mapOf("error" to "双击执行失败")
        }
        return@withContext mapOf("error" to "请提供 text、id、className、contentDesc 或 x、y")
    }

    /** 按图像模板匹配并点击：在屏幕中查找模板图，找到则点击中心 */
    suspend fun tapByImage(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val findResult = findImage(params)
        if ((findResult["error"] != null)) return@withContext findResult
        val x = (findResult["x"] as? Number)?.toFloat() ?: return@withContext mapOf("error" to "findImage 返回异常")
        val y = (findResult["y"] as? Number)?.toFloat() ?: return@withContext mapOf("error" to "findImage 返回异常")
        val svc = service ?: return@withContext notEnabledError()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext mapOf("error" to "需要 Android 7.0 及以上")
        val ok = svc.tap(x, y)
        return@withContext if (ok) mapOf("ok" to true, "action" to "tapByImage", "x" to x, "y" to y)
        else mapOf("error" to "图像匹配成功但点击执行失败")
    }

    /** 查找图像模板在屏幕中的坐标，不执行点击。用于 Agent 获取位置后再决定操作 */
    suspend fun findImage(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val imageBase64 = params["image"]?.toString()?.trim()
            ?: return@withContext mapOf("error" to "缺少 image 参数（base64 编码的模板图）")
        val threshold = (params["threshold"] as? Number)?.toDouble()?.coerceIn(0.5, 0.99) ?: 0.8
        val maxWidth = (params["maxWidth"] as? Number)?.toInt() ?: 1080
        val coords = imageMatcher.findImage(imageBase64, threshold, maxWidth)
        if (coords == null) {
            return@withContext mapOf("error" to "未在屏幕中找到与模板匹配的区域，可尝试降低 threshold")
        }
        return@withContext mapOf("ok" to true, "action" to "findImage", "x" to coords.first, "y" to coords.second)
    }

    private fun findNodeByText(node: android.view.accessibility.AccessibilityNodeInfo, text: String): android.view.accessibility.AccessibilityNodeInfo? {
        val t = node.text?.toString() ?: ""
        val d = node.contentDescription?.toString() ?: ""
        if (t.contains(text, ignoreCase = true) || d.contains(text, ignoreCase = true)) return android.view.accessibility.AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, text)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    suspend fun wait(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val durationSec = (params["duration"] as? Number)?.toDouble() ?: 1.0
        val ms = (durationSec.coerceIn(0.1, 60.0) * 1000).toLong()
        delay(ms)
        return@withContext mapOf("ok" to true, "action" to "wait", "duration" to durationSec)
    }

    suspend fun takeOver(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val message = params["message"]?.toString()?.trim() ?: "请手动完成操作（登录、验证码等）"
        return@withContext mapOf("ok" to true, "action" to "takeOver", "message" to message)
    }

    suspend fun listApps(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val apps = AppMapping.listApps()
        return@withContext mapOf("ok" to true, "action" to "listApps", "apps" to apps)
    }

    suspend fun longPress(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val svc = service ?: return@withContext notEnabledError()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext mapOf("error" to "需要 Android 7.0 及以上")

        val duration = ((params["duration"] as? Number)?.toLong() ?: 500L).coerceIn(200, 2000)

        val id = params["id"]?.toString()?.trim()
        if (!id.isNullOrEmpty()) {
            val root = svc.rootInActiveWindow ?: return@withContext notEnabledError()
            val node = findNodeByResourceId(root, id)
            if (node != null) {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                node.recycle()
                val cx = (r.left + r.right) / 2f
                val cy = (r.top + r.bottom) / 2f
                val ok = svc.longPress(cx, cy, duration)
                return@withContext if (ok) mapOf("ok" to true, "action" to "longPress", "by" to "resourceId", "id" to id)
                else mapOf("error" to "长按执行失败")
            }
            return@withContext mapOf("error" to "未找到 resourceId=\"$id\" 的元素")
        }
        val className = params["className"]?.toString()?.trim()
        if (!className.isNullOrEmpty()) {
            val root = svc.rootInActiveWindow ?: return@withContext notEnabledError()
            val node = findNodeByClassName(root, className)
            if (node != null) {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                node.recycle()
                val cx = (r.left + r.right) / 2f
                val cy = (r.top + r.bottom) / 2f
                val ok = svc.longPress(cx, cy, duration)
                return@withContext if (ok) mapOf("ok" to true, "action" to "longPress", "by" to "className", "className" to className)
                else mapOf("error" to "长按执行失败")
            }
            return@withContext mapOf("error" to "未找到 className=\"$className\" 的元素")
        }
        val contentDesc = params["contentDesc"]?.toString()?.trim()
        if (!contentDesc.isNullOrEmpty()) {
            val root = svc.rootInActiveWindow ?: return@withContext notEnabledError()
            val node = findNodeByContentDesc(root, contentDesc)
            if (node != null) {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                node.recycle()
                val cx = (r.left + r.right) / 2f
                val cy = (r.top + r.bottom) / 2f
                val ok = svc.longPress(cx, cy, duration)
                return@withContext if (ok) mapOf("ok" to true, "action" to "longPress", "by" to "contentDesc", "contentDesc" to contentDesc)
                else mapOf("error" to "长按执行失败")
            }
            return@withContext mapOf("error" to "未找到 contentDesc=\"$contentDesc\" 的元素")
        }
        val text = params["text"]?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            val root = svc.rootInActiveWindow ?: return@withContext notEnabledError()
            var node = findNodeByText(root, text)
            if (node == null) {
                val coords = ocrHandler.findTextCoordinates(text)
                if (coords != null) {
                    val ok = svc.longPress(coords.first, coords.second, duration)
                    return@withContext if (ok) mapOf("ok" to true, "action" to "longPress", "by" to "ocr", "text" to text)
                    else mapOf("error" to "长按执行失败")
                }
            } else {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                node.recycle()
                val cx = (r.left + r.right) / 2f
                val cy = (r.top + r.bottom) / 2f
                val ok = svc.longPress(cx, cy, duration)
                return@withContext if (ok) mapOf("ok" to true, "action" to "longPress", "by" to "accessibility", "text" to text)
                else mapOf("error" to "长按执行失败")
            }
            return@withContext mapOf("error" to "未找到包含「$text」的元素")
        }

        val x = (params["x"] as? Number)?.toFloat()
        val y = (params["y"] as? Number)?.toFloat()
        if (x != null && y != null) {
            val ok = svc.longPress(x, y, duration)
            return@withContext if (ok) mapOf("ok" to true, "action" to "longPress", "x" to x, "y" to y)
            else mapOf("error" to "长按执行失败")
        }
        return@withContext mapOf("error" to "请提供 text、id、className、contentDesc 或 x、y")
    }

    suspend fun launch(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val appName = params["app"]?.toString()?.trim()
        val pkgParam = params["package"]?.toString()?.trim()
        val pkg = when {
            !appName.isNullOrEmpty() -> AppMapping.getPackage(appName) ?: return@withContext mapOf("error" to "未识别的应用名: $appName，支持列表见 ui.listApps")
            !pkgParam.isNullOrEmpty() -> pkgParam
            else -> return@withContext mapOf("error" to "缺少 app 或 package 参数，如 app:\"微信\" 或 package:\"com.tencent.mm\"")
        }
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
        val text = params["text"]?.toString()?.trim()
        val imageBase64 = params["image"]?.toString()?.trim()
        if (text.isNullOrEmpty() && imageBase64.isNullOrEmpty()) {
            return@withContext mapOf("error" to "请提供 text 或 image 参数")
        }
        val timeoutMs = ((params["timeout"] as? Number)?.toLong() ?: 10000L).coerceIn(500, 60000)
        val pollInterval = 500L
        val start = System.currentTimeMillis()

        if (!imageBase64.isNullOrEmpty()) {
            val threshold = (params["threshold"] as? Number)?.toDouble()?.coerceIn(0.5, 0.99) ?: 0.8
            val maxWidth = (params["maxWidth"] as? Number)?.toInt()?.coerceIn(320, 1920) ?: 1080
            while (System.currentTimeMillis() - start < timeoutMs) {
                val coords = imageMatcher.findImage(imageBase64, threshold, maxWidth)
                if (coords != null) {
                    return@withContext mapOf("ok" to true, "action" to "waitFor", "foundIn" to "image", "x" to coords.first, "y" to coords.second)
                }
                delay(pollInterval)
            }
            return@withContext mapOf("error" to "超时 ${timeoutMs}ms 内未匹配到图像")
        }

        while (System.currentTimeMillis() - start < timeoutMs) {
            val svc = service
            if (svc != null) {
                val tree = svc.dumpTree()
                if (tree.contains(text!!, ignoreCase = true)) {
                    return@withContext mapOf("ok" to true, "action" to "waitFor", "text" to text, "foundIn" to "accessibility")
                }
            }
            val ocrResult = ocrHandler.ocr(mapOf("maxWidth" to 1080, "includeBase64" to false))
            val fullText = ocrResult["fullText"]?.toString() ?: ""
            if (fullText.contains(text!!, ignoreCase = true)) {
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

    private fun findNodeByResourceId(node: android.view.accessibility.AccessibilityNodeInfo, id: String): android.view.accessibility.AccessibilityNodeInfo? {
        val rid = node.viewIdResourceName ?: ""
        if (rid.isNotEmpty() && (rid == id || rid.endsWith("/$id") || rid.endsWith(":$id"))) return android.view.accessibility.AccessibilityNodeInfo.obtain(node)
        for (j in 0 until node.childCount) {
            val child = node.getChild(j) ?: continue
            val found = findNodeByResourceId(child, id)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByClassName(node: android.view.accessibility.AccessibilityNodeInfo, className: String): android.view.accessibility.AccessibilityNodeInfo? {
        val cn = node.className?.toString() ?: ""
        if (cn.isNotEmpty() && (cn == className || cn.endsWith(".$className"))) return android.view.accessibility.AccessibilityNodeInfo.obtain(node)
        for (j in 0 until node.childCount) {
            val child = node.getChild(j) ?: continue
            val found = findNodeByClassName(child, className)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByContentDesc(node: android.view.accessibility.AccessibilityNodeInfo, desc: String): android.view.accessibility.AccessibilityNodeInfo? {
        val d = node.contentDescription?.toString() ?: ""
        if (d.contains(desc, ignoreCase = true)) return android.view.accessibility.AccessibilityNodeInfo.obtain(node)
        for (j in 0 until node.childCount) {
            val child = node.getChild(j) ?: continue
            val found = findNodeByContentDesc(child, desc)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private suspend fun runAction(act: String, actParams: Map<String, Any?>): Map<String, Any?> = when (act) {
        "tap" -> tap(actParams)
        "input" -> input(actParams)
        "swipe" -> swipe(actParams)
        "back" -> back(actParams)
        "home" -> home(actParams)
        "longPress" -> longPress(actParams)
        "launch" -> launch(actParams)
        "scroll" -> scroll(actParams)
        "waitFor" -> waitFor(actParams)
        "wait" -> wait(actParams)
        "doubleTap" -> doubleTap(actParams)
        "tapByImage" -> tapByImage(actParams)
        else -> mapOf("error" to "未知 action: $act")
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
                "wait" -> wait(actParams)
                "doubleTap" -> doubleTap(actParams)
                "tapByImage" -> tapByImage(actParams)
                "flow" -> flow(actParams)
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

    /**
     * 微脚本流程：waitFor（条件满足）+ then（执行动作），支持超时、重试
     * steps: [{ waitFor: { text|image|id|className|contentDesc }, then: "tap"|{action,params}, timeout?, retry? }]
     */
    suspend fun flow(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main) {
        val stepsRaw = params["steps"]
        val steps = when (stepsRaw) {
            is List<*> -> stepsRaw.filterIsInstance<Map<*, *>>()
            else -> return@withContext mapOf("error" to "缺少 steps 数组，如 [{waitFor:{text:\"登录\"},then:\"tap\"}]")
        }
        if (steps.isEmpty()) return@withContext mapOf("error" to "steps 不能为空")
        val repeatCount = ((params["repeat"] as? Number)?.toInt() ?: 1).coerceIn(1, 20)
        val results = mutableListOf<Map<String, Any?>>()
        outer@ for (round in 0 until repeatCount) {
        for ((i, step) in steps.withIndex()) {
            val waitFor = step["waitFor"]
            val waitForMap = when (waitFor) {
                is Map<*, *> -> waitFor.mapKeys { it.key.toString() }.mapValues { it.value }
                else -> return@withContext mapOf(
                    "ok" to false,
                    "error" to "第 ${i + 1} 步 waitFor 须为对象",
                    "results" to results
                )
            }
            val thenVal = step["then"]
            val timeoutMs = ((step["timeout"] as? Number)?.toLong() ?: 10000L).coerceIn(1000, 60000)
            val retryCount = ((step["retry"] as? Number)?.toInt() ?: 0).coerceIn(0, 5)
            var found: Map<String, Any?>? = null
            var lastError: String? = null
            for (attempt in 0..retryCount) {
                found = runWaitForStep(waitForMap, timeoutMs)
                if (found != null) break
                lastError = when {
                    "text" in waitForMap -> "超时未找到文本「${waitForMap["text"]}」"
                    "image" in waitForMap -> "超时未匹配到图像"
                    "id" in waitForMap -> "超时未找到 resourceId=${waitForMap["id"]}"
                    "className" in waitForMap -> "超时未找到 className=${waitForMap["className"]}"
                    "contentDesc" in waitForMap -> "超时未找到 contentDesc=${waitForMap["contentDesc"]}"
                    else -> "waitFor 须包含 text、image、id、className 或 contentDesc"
                }
                if (attempt < retryCount) delay(500)
            }
            if (found == null) {
                val elseActions = step["else"]
                if (elseActions is List<*> && elseActions.isNotEmpty()) {
                    val elseList = elseActions.filterIsInstance<Map<*, *>>()
                    val elseResults = runElseActions(elseList)
                    results.add(mapOf("round" to (round + 1), "step" to (i + 1), "branch" to "else", "reason" to lastError, "results" to elseResults))
                    val failed = elseResults.any { (it["result"] as? Map<*, *>)?.get("error") != null }
                    if (failed) {
                        val err = elseResults.firstOrNull { (it["result"] as? Map<*, *>)?.get("error") != null }
                        return@withContext mapOf(
                            "ok" to false,
                            "error" to "第 ${i + 1} 步 waitFor 未满足且 else 失败: ${(err?.get("result") as? Map<*, *>)?.get("error")}",
                            "results" to results
                        )
                    }
                    delay(300)
                } else {
                    return@withContext mapOf(
                        "ok" to false,
                        "error" to "第 ${i + 1} 步失败: $lastError",
                        "results" to results
                    )
                }
            } else {
                val thenResult = runThenStep(thenVal, found!!)
                results.add(mapOf("round" to (round + 1), "step" to (i + 1), "waitFor" to waitForMap.keys.firstOrNull(), "result" to thenResult))
                if (thenResult["error"] != null) {
                    return@withContext mapOf(
                        "ok" to false,
                        "error" to "第 ${i + 1} 步 then 失败: ${thenResult["error"]}",
                        "results" to results
                    )
                }
                delay(300)
            }
        }
        }
        return@withContext mapOf("ok" to true, "action" to "flow", "steps" to steps.size, "repeat" to repeatCount, "results" to results)
    }

    /** 执行 else 分支的动作列表（与 sequence 同格式）*/
    private suspend fun runElseActions(actions: List<Map<*, *>>): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        for ((idx, action) in actions.withIndex()) {
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
                "wait" -> wait(actParams)
                "doubleTap" -> doubleTap(actParams)
                "tapByImage" -> tapByImage(actParams)
                "flow" -> flow(actParams)
                else -> mapOf("error" to "未知 action: $act")
            }
            results.add(mapOf("step" to (idx + 1), "action" to act, "result" to result))
            if (result["error"] != null) return results
            delay(200)
        }
        return results
    }

    /** 执行 waitFor 条件，满足时返回用于 then 的参数 map，超时返回 null */
    private suspend fun runWaitForStep(waitFor: Map<String, Any?>, timeoutMs: Long): Map<String, Any?>? {
        val pollInterval = 500L
        val start = System.currentTimeMillis()
        when {
            waitFor["text"] != null -> {
                val text = waitFor["text"]?.toString()?.trim() ?: return null
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val svc = service
                    if (svc != null) {
                        val tree = svc.dumpTree()
                        if (tree.contains(text, ignoreCase = true)) return mapOf("text" to text)
                    }
                    val ocrResult = ocrHandler.ocr(mapOf("maxWidth" to 1080, "includeBase64" to false))
                    if ((ocrResult["fullText"]?.toString() ?: "").contains(text, ignoreCase = true)) {
                        return mapOf("text" to text)
                    }
                    delay(pollInterval)
                }
            }
            waitFor["image"] != null -> {
                val imageBase64 = waitFor["image"]?.toString()?.trim() ?: return null
                val threshold = (waitFor["threshold"] as? Number)?.toDouble()?.coerceIn(0.5, 0.99) ?: 0.8
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val coords = imageMatcher.findImage(imageBase64, threshold)
                    if (coords != null) return mapOf("x" to coords.first, "y" to coords.second)
                    delay(pollInterval)
                }
            }
            waitFor["id"] != null -> {
                val id = waitFor["id"]?.toString()?.trim() ?: return null
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val svc = service ?: break
                    val root = svc.rootInActiveWindow ?: break
                    val node = findNodeByResourceId(root, id)
                    node?.recycle()
                    if (node != null) return mapOf("id" to id)
                    delay(pollInterval)
                }
            }
            waitFor["className"] != null -> {
                val className = waitFor["className"]?.toString()?.trim() ?: return null
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val svc = service ?: break
                    val root = svc.rootInActiveWindow ?: break
                    val node = findNodeByClassName(root, className)
                    node?.recycle()
                    if (node != null) return mapOf("className" to className)
                    delay(pollInterval)
                }
            }
            waitFor["contentDesc"] != null -> {
                val desc = waitFor["contentDesc"]?.toString()?.trim() ?: return null
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val svc = service ?: break
                    val root = svc.rootInActiveWindow ?: break
                    val node = findNodeByContentDesc(root, desc)
                    node?.recycle()
                    if (node != null) return mapOf("contentDesc" to desc)
                    delay(pollInterval)
                }
            }
        }
        return null
    }

    /** 执行 then 动作，found 为 waitFor 返回的定位参数 */
    private suspend fun runThenStep(thenVal: Any?, found: Map<String, Any?>): Map<String, Any?> {
        val act: String
        val actParams: Map<String, Any?>
        when (thenVal) {
            "tap", null -> {
                act = "tap"
                actParams = found
            }
            is Map<*, *> -> {
                val m = thenVal.mapKeys { it.key.toString() }.mapValues { it.value }
                act = (m["action"] ?: "tap")?.toString() ?: "tap"
                val explicit = m.filterKeys { it != "action" && it != "type" }
                actParams = if (explicit.isEmpty()) found else (found + explicit)
            }
            else -> return mapOf("error" to "then 须为 \"tap\" 或 {action,params} 对象")
        }
        return when (act) {
            "tap" -> tap(actParams)
            "input" -> input(actParams)
            "swipe" -> swipe(actParams)
            "back" -> back(actParams)
            "home" -> home(actParams)
            "longPress" -> longPress(actParams)
            "scroll" -> scroll(actParams)
            "doubleTap" -> doubleTap(actParams)
            "tapByImage" -> tapByImage(actParams)
            "wait" -> wait(actParams)
            else -> mapOf("error" to "不支持的 then action: $act")
        }
    }

    companion object {
        private const val TAG = "AutomationHandler"
    }
}
