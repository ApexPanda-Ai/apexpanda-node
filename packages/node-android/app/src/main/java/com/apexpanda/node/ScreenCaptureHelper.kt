package com.apexpanda.node

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 截屏：使用 MediaProjection 捕获当前屏幕为 Bitmap
 * 需用户已授权录屏
 */
object ScreenCaptureHelper {

    suspend fun captureBitmap(context: Context, maxWidth: Int = 1080): Bitmap? = withContext(Dispatchers.IO) {
        val proj = ProjectionHolder.mediaProjection ?: run {
            if (!ProjectionHolder.hasPermission()) return@withContext null
            try {
                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mgr.getMediaProjection(ProjectionHolder.resultCode, ProjectionHolder.resultData!!)
            } catch (e: Exception) {
                Log.e(TAG, "getMediaProjection", e)
                return@withContext null
            }
        } ?: return@withContext null

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi
        val scale = if (maxWidth > 0 && w > maxWidth) maxWidth.toFloat() / w else 1f
        val captureW = (w * scale).toInt().coerceAtLeast(1)
        val captureH = (h * scale).toInt().coerceAtLeast(1)

        val imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = proj.createVirtualDisplay(
            "ApexPandaOcr",
            captureW, captureH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, Handler(Looper.getMainLooper())
        )
        try {
            kotlinx.coroutines.delay(150)
            val image = imageReader.acquireLatestImage() ?: run {
                kotlinx.coroutines.delay(100)
                imageReader.acquireLatestImage()
            }
            if (image == null) {
                Log.w(TAG, "No image from ImageReader")
                return@withContext null
            }
            try {
                imageToBitmap(image)
            } finally {
                image.close()
            }
        } finally {
            virtualDisplay?.release()
            imageReader.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        val rowPadding = rowStride - pixelStride * w
        if (rowPadding == 0) {
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            val row = ByteArray(rowStride)
            val pixels = IntArray(w)
            for (y in 0 until h) {
                buffer.get(row)
                var offset = 0
                for (x in 0 until w) {
                    val r = row[offset].toInt() and 0xff
                    val g = row[offset + 1].toInt() and 0xff
                    val b = row[offset + 2].toInt() and 0xff
                    val a = row[offset + 3].toInt() and 0xff
                    pixels[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    offset += pixelStride
                }
                bitmap.setPixels(pixels, 0, w, 0, y, w, 1)
            }
        }
        return bitmap
    }

    private const val TAG = "ScreenCapture"
}
