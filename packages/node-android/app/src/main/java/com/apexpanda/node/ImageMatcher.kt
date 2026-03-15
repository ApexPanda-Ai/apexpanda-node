package com.apexpanda.node

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * 图像模板匹配：在屏幕截图中查找模板图并返回中心坐标
 * 使用 OpenCV matchTemplate (TM_CCOEFF_NORMED)，灰度匹配
 */
class ImageMatcher(private val context: android.content.Context) {

    @Volatile
    private var opencvInitialized = false

    private fun ensureOpenCV(): Boolean {
        if (opencvInitialized) return true
        // initDebug 加载打包在 APK 中的 OpenCV native 库
        opencvInitialized = try {
            OpenCVLoader.initDebug()
        } catch (e: Throwable) {
            Log.e(TAG, "OpenCV init failed", e)
            false
        }
        if (opencvInitialized) Log.d(TAG, "OpenCV initialized")
        return opencvInitialized
    }

    /**
     * 在屏幕中查找模板图，返回匹配中心的屏幕坐标 (x, y)，未找到返回 null
     * @param templateBase64 模板图 base64（PNG/JPEG）
     * @param threshold 匹配阈值 0-1，默认 0.8，越高越严格
     * @param maxWidth 截屏最大宽度，默认 1080
     */
    suspend fun findImage(
        templateBase64: String,
        threshold: Double = 0.8,
        maxWidth: Int = 1080
    ): Pair<Float, Float>? = withContext(Dispatchers.IO) {
        if (!ensureOpenCV()) {
            Log.e(TAG, "OpenCV not available")
            return@withContext null
        }
        val templateBytes = Base64.decode(templateBase64, Base64.DEFAULT)
        val templateBitmap = BitmapFactory.decodeByteArray(templateBytes, 0, templateBytes.size)
            ?: run {
                Log.e(TAG, "Failed to decode template image")
                return@withContext null
            }
        val screenBitmap = ScreenCaptureHelper.captureBitmap(context, maxWidth)
            ?: run {
                Log.e(TAG, "Screen capture failed")
                templateBitmap.recycle()
                return@withContext null
            }
        try {
            findImageInternal(screenBitmap, templateBitmap, threshold)
        } finally {
            templateBitmap.recycle()
            screenBitmap.recycle()
        }
    }

    private fun findImageInternal(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        threshold: Double
    ): Pair<Float, Float>? {
        val screenMat = Mat()
        val templateMat = Mat()
        val screenGray = Mat()
        val templateGray = Mat()
        val resultMat = Mat()
        try {
            Utils.bitmapToMat(screenBitmap, screenMat)
            Utils.bitmapToMat(templateBitmap, templateMat)
            // 转为灰度图，匹配更稳定
            when (screenMat.channels()) {
                3 -> Imgproc.cvtColor(screenMat, screenGray, Imgproc.COLOR_RGB2GRAY)
                4 -> Imgproc.cvtColor(screenMat, screenGray, Imgproc.COLOR_RGBA2GRAY)
                else -> screenMat.copyTo(screenGray)
            }
            when (templateMat.channels()) {
                3 -> Imgproc.cvtColor(templateMat, templateGray, Imgproc.COLOR_RGB2GRAY)
                4 -> Imgproc.cvtColor(templateMat, templateGray, Imgproc.COLOR_RGBA2GRAY)
                else -> templateMat.copyTo(templateGray)
            }
            if (screenGray.cols() < templateGray.cols() || screenGray.rows() < templateGray.rows()) {
                Log.e(TAG, "Template larger than screen")
                return null
            }
            Imgproc.matchTemplate(screenGray, templateGray, resultMat, Imgproc.TM_CCOEFF_NORMED)
            val minMax = Core.minMaxLoc(resultMat)
            val maxVal = minMax.maxVal
            if (maxVal < threshold) {
                Log.d(TAG, "No match: maxVal=$maxVal < threshold=$threshold")
                return null
            }
            val matchLoc = minMax.maxLoc
            val tw = templateGray.cols()
            val th = templateGray.rows()
            val centerX = matchLoc.x + tw / 2.0
            val centerY = matchLoc.y + th / 2.0
            val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val scaleX = screenW.toFloat() / screenBitmap.width
            val scaleY = screenH.toFloat() / screenBitmap.height
            val screenX = (centerX * scaleX).toFloat()
            val screenY = (centerY * scaleY).toFloat()
            Log.d(TAG, "Match at ($screenX, $screenY), maxVal=$maxVal")
            return Pair(screenX, screenY)
        } catch (e: Exception) {
            Log.e(TAG, "Template match failed", e)
            return null
        } finally {
            screenMat.release()
            templateMat.release()
            screenGray.release()
            templateGray.release()
            resultMat.release()
        }
    }

    companion object {
        private const val TAG = "ImageMatcher"
    }
}
