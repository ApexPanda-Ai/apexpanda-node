package com.apexpanda.node

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 处理 node.invoke 命令：camera.snap, camera.clip, screen.record, location.get
 */
class CommandHandler(private val context: Context) {

    private val cameraHandler by lazy { CameraHandler(context) }
    private val screenRecordHandler by lazy { ScreenRecordHandler(context) }
    private val locationHandler by lazy { LocationHandler(context) }
    private val automationHandler by lazy { AutomationHandler(context) }

    suspend fun execute(command: String, params: Map<String, Any?>): Map<String, Any?> {
        return when (command) {
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
            "ui.sequence" -> automationHandler.sequence(params)
            "screen.ocr" -> automationHandler.ocr(params)
            "ui.analyze" -> automationHandler.analyze(params)
            else -> mapOf("error" to "Unknown command: $command")
        }
    }

    companion object {
        private const val TAG = "CommandHandler"
    }
}
