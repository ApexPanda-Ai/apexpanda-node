package com.apexpanda.node

import android.util.Base64
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 屏幕 OCR：截屏 + ML Kit 文字识别，返回文本及坐标
 * 需录屏权限，支持中文
 */
class OcrHandler(private val context: android.content.Context) {

    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    suspend fun ocr(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (!ProjectionHolder.hasPermission()) {
            return@withContext mapOf(
                "error" to "PERMISSION_DENIED: 需要先授权屏幕录制。请在 App 中点击「授权录屏」并允许。"
            )
        }
        val maxWidth = (params["maxWidth"] as? Number)?.toInt()?.coerceIn(480, 1920) ?: 1080
        val includeBase64 = params["includeBase64"] == true

        val bitmap = ScreenCaptureHelper.captureBitmap(context, maxWidth)
            ?: return@withContext mapOf("error" to "截屏失败，请确保已授权录屏")

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { cont ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val items = mutableListOf<Map<String, Any?>>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val box = element.boundingBox ?: continue
                        items.add(mapOf(
                            "text" to element.text,
                            "x" to box.left,
                            "y" to box.top,
                            "width" to box.width(),
                            "height" to box.height(),
                            "centerX" to (box.left + box.width() / 2),
                            "centerY" to (box.top + box.height() / 2)
                        ))
                    }
                }
            }
            val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val dm = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val out = mutableMapOf<String, Any?>(
                "ok" to true,
                "items" to items,
                "fullText" to result.text,
                "bitmapWidth" to bitmap.width,
                "bitmapHeight" to bitmap.height,
                "screenWidth" to screenW,
                "screenHeight" to screenH
            )
            if (includeBase64) {
                val baos = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                out["base64"] = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            }
            return@withContext out
        } catch (e: Exception) {
            Log.e(TAG, "ocr", e)
            return@withContext mapOf("error" to (e.message ?: "OCR 失败"))
        } finally {
            bitmap.recycle()
        }
    }

    /** 在 OCR 结果中按文字查找，返回第一个匹配的中心坐标（屏幕坐标系） */
    suspend fun findTextCoordinates(text: String): Pair<Float, Float>? = withContext(Dispatchers.IO) {
        val result = ocr(mapOf("maxWidth" to 1080, "includeBase64" to false))
        if (result["ok"] != true) return@withContext null
        val items = result["items"] as? List<*> ?: return@withContext null
        val bw = (result["bitmapWidth"] as? Number)?.toInt() ?: 1
        val bh = (result["bitmapHeight"] as? Number)?.toInt() ?: 1
        val sw = (result["screenWidth"] as? Number)?.toInt() ?: bw
        val sh = (result["screenHeight"] as? Number)?.toInt() ?: bh
        val scaleX = sw.toFloat() / bw
        val scaleY = sh.toFloat() / bh
        for (item in items) {
            val m = item as? Map<*, *> ?: continue
            val t = m["text"]?.toString() ?: ""
            if (t.contains(text, ignoreCase = true)) {
                val cx = (m["centerX"] as? Number)?.toFloat() ?: continue
                val cy = (m["centerY"] as? Number)?.toFloat() ?: continue
                val screenX = cx * scaleX
                val screenY = cy * scaleY
                return@withContext Pair(screenX, screenY)
            }
        }
        return@withContext null
    }

    companion object {
        private const val TAG = "OcrHandler"
    }
}
