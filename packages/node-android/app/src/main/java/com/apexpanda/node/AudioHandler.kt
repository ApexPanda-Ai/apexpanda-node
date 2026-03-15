package com.apexpanda.node

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import kotlin.coroutines.resume
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

/**
 * 处理 audio.record、audio.playback 命令
 * 语音唤醒：录音 → voice_audio_ready → ASR → Agent → TTS → playback
 */
class AudioHandler(
    private val context: Context,
    private val onSendVoiceAudio: (base64: String, format: String) -> Unit
) {
    suspend fun record(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.IO) {
        val durationSec = (params["duration"] as? Number)?.toInt()?.coerceIn(1, 60) ?: 10
        val outputFormat = when (params["format"]?.toString()?.lowercase()) {
            "3gp" -> "3gp"
            "m4a", "mp4" -> "m4a"
            else -> "m4a" // 默认 m4a，飞书 ASR 支持较好
        }
        var recorder: MediaRecorder? = null
        var tempFile: File? = null
        try {
            tempFile = File.createTempFile("apex_voice_", ".$outputFormat", context.cacheDir)
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(
                    if (outputFormat == "m4a") MediaRecorder.OutputFormat.MPEG_4
                    else MediaRecorder.OutputFormat.THREE_GPP
                )
                setAudioEncoder(
                    if (outputFormat == "m4a") MediaRecorder.AudioEncoder.AAC
                    else MediaRecorder.AudioEncoder.AMR_NB
                )
                setOutputFile(tempFile.absolutePath)
                prepare()
                start()
            }
            withTimeoutOrNull((durationSec + 2) * 1000L) {
                kotlinx.coroutines.delay(durationSec * 1000L)
            }
            recorder.stop()
            recorder.release()
            recorder = null
            val bytes = tempFile.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val ext = if (outputFormat == "m4a") "m4a" else "3gp"
            onSendVoiceAudio(base64, ext)
            mapOf("ok" to true, "base64" to base64, "format" to ext)
        } catch (e: SecurityException) {
            Log.e(TAG, "录音需要 RECORD_AUDIO 权限", e)
            mapOf("error" to "需要麦克风权限")
        } catch (e: Exception) {
            Log.e(TAG, "录音失败", e)
            mapOf("error" to (e.message ?: "录音失败"))
        } finally {
            try {
                recorder?.stop()
                recorder?.release()
            } catch (_: Exception) { }
            tempFile?.delete()
        }
    }

    suspend fun playback(params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.IO) {
        val base64 = (params["audioBase64"] ?: params["base64"])?.toString()?.trim() ?: ""
        val format = params["format"]?.toString()?.lowercase() ?: "mp3"
        if (base64.isEmpty()) return@withContext mapOf("error" to "audioBase64 required")
        var tempFile: File? = null
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val ext = when (format) {
                "mp3", "m4a", "wav", "ogg" -> format
                else -> "mp3"
            }
            tempFile = File.createTempFile("apex_playback_", ".$ext", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(bytes) }
            withContext(Dispatchers.Main) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    val path = tempFile!!.absolutePath
                    val player = MediaPlayer().apply {
                        setDataSource(path)
                        prepare()
                        setOnCompletionListener {
                            it.release()
                            if (cont.isActive) cont.resume(Unit) {}
                        }
                        setOnErrorListener { mp, _, _ ->
                            mp.release()
                            if (cont.isActive) cont.resume(Unit) {}
                            true
                        }
                        start()
                    }
                    cont.invokeOnCancellation { try { player.release() } catch (_: Exception) {} }
                }
            }
            mapOf("ok" to true)
        } catch (e: Exception) {
            Log.e(TAG, "播放失败", e)
            mapOf("error" to (e.message ?: "播放失败"))
        } finally {
            tempFile?.delete()
        }
    }

    companion object {
        private const val TAG = "AudioHandler"
    }
}
