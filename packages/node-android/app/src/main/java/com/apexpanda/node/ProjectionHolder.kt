package com.apexpanda.node

import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log

/**
 * 存储 MediaProjection 授权结果，供 Service 中的 ScreenRecordHandler 使用
 * 授权成功后立即创建 MediaProjection 对象保存，避免 Intent 在某些设备上失效
 */
object ProjectionHolder {
    private const val TAG = "ProjectionHolder"

    @Volatile var resultCode: Int = -1
    @Volatile var resultData: Intent? = null
    @Volatile var mediaProjection: MediaProjection? = null

    fun set(code: Int, data: Intent?) {
        resultCode = code
        resultData = data
        mediaProjection = null
    }

    /** 授权成功后调用，立即创建并缓存 MediaProjection */
    fun setFromResult(manager: MediaProjectionManager, code: Int, data: Intent?) {
        resultCode = code
        resultData = data
        mediaProjection = if (code == android.app.Activity.RESULT_OK && data != null) {
            try {
                manager.getMediaProjection(code, data)
            } catch (e: Exception) {
                Log.e(TAG, "getMediaProjection failed", e)
                null
            }
        } else {
            null
        }
    }

    fun hasPermission(): Boolean =
        mediaProjection != null || (resultCode != -1 && resultData != null)
}
