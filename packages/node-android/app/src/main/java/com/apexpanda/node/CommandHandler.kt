package com.apexpanda.node

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 处理 node.invoke 命令：camera.snap, camera.clip, screen.record, location.get, audio.record, audio.playback
 */
class CommandHandler(
    private val context: Context,
    private val onSendVoiceAudio: (base64: String, format: String) -> Unit = { _, _ -> }
) {

    private val cameraHandler by lazy { CameraHandler(context) }
    private val screenRecordHandler by lazy { ScreenRecordHandler(context) }
    private val locationHandler by lazy { LocationHandler(context) }
    private val automationHandler by lazy { AutomationHandler(context) }
    private val audioHandler by lazy { AudioHandler(context, onSendVoiceAudio) }

    suspend fun execute(command: String, params: Map<String, Any?>): Map<String, Any?> {
        val result = when (command) {
            "camera.snap" -> cameraHandler.snap(params)
            "camera.clip" -> cameraHandler.clip(params)
            "screen.record" -> screenRecordHandler.record(params)
            "location.get" -> locationHandler.get(params)
            "ui.tap" -> automationHandler.tap(params)
            "ui.input" -> automationHandler.input(params)
            "ui.swipe" -> automationHandler.swipe(params)
            "ui.back" -> automationHandler.back(params)
            "ui.home" -> automationHandler.home(params)
            "ui.dump" -> automationHandler.dump(params)
            "ui.longPress" -> automationHandler.longPress(params)
            "ui.launch" -> automationHandler.launch(params)
            "ui.scroll" -> automationHandler.scroll(params)
            "ui.waitFor" -> automationHandler.waitFor(params)
            "ui.wait" -> automationHandler.wait(params)
            "ui.doubleTap" -> automationHandler.doubleTap(params)
            "ui.takeOver" -> automationHandler.takeOver(params)
            "ui.listApps" -> automationHandler.listApps(params)
            "ui.tapByImage" -> automationHandler.tapByImage(params)
            "ui.sequence" -> automationHandler.sequence(params)
            "ui.flow" -> automationHandler.flow(params)
            "screen.ocr" -> automationHandler.ocr(params)
            "screen.findImage" -> automationHandler.findImage(params)
            "ui.analyze" -> automationHandler.analyze(params)
            "audio.record" -> audioHandler.record(params)
            "audio.playback" -> audioHandler.playback(params)
            else -> mapOf("error" to "Unknown command: $command")
        }
        if (result["error"] != null && (command.startsWith("ui.") || command == "screen.findImage")) {
            val base64 = automationHandler.captureScreenshotBase64()
            if (base64 != null) return result + ("screenshotBase64" to base64)
        }
        return result
    }

    companion object {
        private const val TAG = "CommandHandler"
    }
}
