package com.apexpanda.node

import android.content.Context
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 屏幕录制 - 需要用户授权 MediaProjection
 * 通过 ActivityResultContract 在 MainActivity 获取 resultCode 和 data
 */
class ScreenRecordHandler(private val context: Context) {

    private var mediaProjection: MediaProjection? = null

    suspend fun record(params: Map<String, Any?>): Map<String, Any?> {
        val cachedProj = ProjectionHolder.mediaProjection
        val proj = cachedProj ?: run {
            if (!ProjectionHolder.hasPermission()) {
                return mapOf(
                    "error" to "PERMISSION_DENIED: 需要先授权屏幕录制。请在手机打开 ApexPanda App，点击「授权录屏」按钮并允许。若曾授权过，App 重启后需重新授权。"
                )
            }
            try {
                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mgr.getMediaProjection(ProjectionHolder.resultCode, ProjectionHolder.resultData!!)
            } catch (e: Exception) {
                Log.e(TAG, "getMediaProjection failed", e)
                return mapOf("error" to "获取录屏权限失败，请重新打开 App 并再次点击「授权录屏」")
            }
        }
        if (proj == null) {
            return mapOf("error" to "无法创建录屏，请重新打开 App 并再次点击「授权录屏」")
        }
        mediaProjection = proj

        val duration = (params["duration"] as? Number)?.toInt()?.coerceIn(1, 60) ?: 5
        val outputFile = File(context.cacheDir, "screen_record_${System.currentTimeMillis()}.webm")
        try {
            @Suppress("DEPRECATION")
            val recorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputFile.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(1280, 720)
                setVideoFrameRate(30)
                prepare()
            }
            val surface = recorder.surface
            val virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ApexPandaScreen",
                1280, 720, 1,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
            recorder.start()
            kotlinx.coroutines.delay(duration * 1000L)
            recorder.stop()
            recorder.release()
            virtualDisplay?.release()
            val wasCached = (proj === ProjectionHolder.mediaProjection)
            if (!wasCached) {
                mediaProjection?.stop()
            }
            mediaProjection = null
            val bytes = FileInputStream(outputFile).use { it.readBytes() }
            outputFile.delete()
            return mapOf(
                "ok" to true,
                "base64" to Base64.encodeToString(bytes, Base64.NO_WRAP),
                "format" to "mp4",
                "ext" to "mp4"
            )
        } catch (e: Exception) {
            Log.e(TAG, "record", e)
            val wasCached = mediaProjection === ProjectionHolder.mediaProjection
            if (!wasCached) mediaProjection?.stop()
            mediaProjection = null
            outputFile.delete()
            return mapOf("error" to (e.message ?: "record failed"))
        }
    }

    companion object {
        private const val TAG = "ScreenRecordHandler"
    }
}
