package com.apexpanda.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.os.Handler
import android.os.Looper
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2 拍照（从 Service 调用，无需 Activity）
 */
class CameraHandler(private val context: Context) {

    suspend fun snap(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return@withContext mapOf("error" to "PERMISSION_DENIED: 需要相机权限")
        }
        val maxWidth = (params["maxWidth"] as? Number)?.toInt() ?: 1200
        val quality = ((params["quality"] as? Number)?.toFloat() ?: 0.85f).coerceIn(0.1f, 1f)
        val facing = when (params["facing"]?.toString()) {
            "back" -> CameraCharacteristics.LENS_FACING_BACK
            else -> CameraCharacteristics.LENS_FACING_FRONT
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = manager.cameraIdList.find { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == facing
        } ?: manager.cameraIdList.firstOrNull() ?: run {
            return@withContext mapOf("error" to "No camera found")
        }

        val chars = manager.getCameraCharacteristics(camId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run {
            return@withContext mapOf("error" to "No stream config")
        }
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return@withContext mapOf("error" to "No JPEG sizes")
        val size = sizes.minByOrNull { s ->
            val dim = maxOf(s.width, s.height)
            if (dim >= maxWidth) dim - maxWidth else Int.MAX_VALUE - dim
        } ?: sizes.maxByOrNull { maxOf(it.width, it.height) } ?: sizes[0]

        val mainHandler = Handler(Looper.getMainLooper())
        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)

        suspendCancellableCoroutine { cont ->
            var captured = false
            imageReader.setOnImageAvailableListener({ reader ->
                if (captured) return@setOnImageAvailableListener
                captured = true
                try {
                    val image = reader.acquireNextImage() ?: run {
                        cont.resume(mapOf("error" to "No image"))
                        return@setOnImageAvailableListener
                    }
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    imageReader.close()
                    cont.resume(mapOf(
                        "ok" to true,
                        "base64" to Base64.encodeToString(bytes, Base64.NO_WRAP),
                        "format" to "jpg",
                        "ext" to "jpg",
                        "width" to size.width,
                        "height" to size.height
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "capture", e)
                    imageReader.close()
                    if (!cont.isCompleted) cont.resume(mapOf("error" to (e.message ?: "capture failed")))
                }
            }, mainHandler)

            manager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(imageReader.surface)
                        set(CaptureRequest.JPEG_ORIENTATION, 0)
                    }
                    camera.createCaptureSession(
                        listOf(imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.capture(requestBuilder.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {}, mainHandler)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                if (!captured) {
                                    captured = true
                                    camera.close()
                                    imageReader.close()
                                    cont.resume(mapOf("error" to "Session configure failed"))
                                }
                            }
                        },
                        mainHandler
                    )
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    if (!captured) {
                        captured = true
                        camera.close()
                        imageReader.close()
                        cont.resume(mapOf("error" to "Camera error: $error"))
                    }
                }
            }, mainHandler)
        }
    }

    suspend fun clip(params: Map<String, Any?>): Map<String, Any?> {
        return mapOf(
            "error" to "camera.clip 暂不支持，请使用 camera.snap 拍照。短视频录制将在后续版本支持。"
        )
    }

    companion object {
        private const val TAG = "CameraHandler"
    }
}
