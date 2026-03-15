package com.apexpanda.node

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * WebSocket 客户端，连接 Gateway 节点协议
 */
class NodeClient(
    private val gatewayUrl: String,
    private val deviceId: String,
    private val displayName: String,
    private val token: String?,
    private val onStatus: (Status) -> Unit,
    private val onCommand: suspend (String, String, Map<String, Any?>) -> Map<String, Any?>,
    private val onPaired: ((String) -> Unit)? = null,
    private val onVoiceWakeConfig: ((JSONObject) -> Unit)? = null
) {
    enum class Status {
        CONNECTING,
        PENDING_PAIRING,
        CONNECTED,
        DISCONNECTED
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var nodeId: String? = null
    private var reconnectAttempts = 0
    @Volatile private var userDisconnected = false
    /** 是否曾有成功连接（CONNECTED 或 paired），有此标记后断连才自动重试 */
    @Volatile private var hasEverConnected = false
    private val RECONNECT_BASE_MS = 1000L
    private val RECONNECT_MAX_MS = 60_000L
    /** 首次连接前最多重试次数，避免错误配置时无限重试 */
    private val MAX_RECONNECT_BEFORE_FIRST_SUCCESS = 10
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null

    private val pendingExecApprovals = ConcurrentHashMap<String, (Boolean) -> Unit>()

    fun parseWsUrl(httpUrl: String): String {
        var u = (httpUrl.trim()).replace("http://", "ws://").replace("https://", "wss://")
        if (!u.startsWith("ws")) u = "ws://$httpUrl"
        // java.net.URL 不支持 ws 协议，用 http 解析
        val parseUrl = u.replace("ws://", "http://").replace("wss://", "https://")
        val url = java.net.URL(parseUrl)
        var path = url.path?.ifBlank { "/" } ?: "/"
        if (!path.endsWith("/ws")) path = path.trimEnd('/') + "/ws"
        val port = when (url.port) {
            -1 -> if (url.protocol == "wss") 443 else 80
            else -> url.port
        }
        val proto = if (url.protocol == "https") "wss" else "ws"
        return "$proto://${url.host}:$port$path?role=node"
    }

    fun connect(scope: CoroutineScope) {
        this.scope = scope
        userDisconnected = false
        doConnect()
    }

    private fun doConnect() {
        val wsUrl = parseWsUrl(gatewayUrl)
        Log.d(TAG, "Connecting to $wsUrl")
        onStatus(Status.CONNECTING)
        val request = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                val payload = JSONObject().apply {
                    put("role", "node")
                    put("deviceId", deviceId)
                    put("displayName", displayName)
                    put("platform", "android")
                    put("protocolVersion", "1")
                    put("capabilities", org.json.JSONArray(listOf(
                        "camera.snap", "camera.clip", "screen.record",
                        "location.get",
                        "ui.tap", "ui.input", "ui.swipe", "ui.back", "ui.home", "ui.dump",
                        "ui.longPress", "ui.launch", "ui.scroll", "ui.waitFor", "ui.wait",
                        "ui.doubleTap", "ui.takeOver", "ui.listApps", "ui.tapByImage", "ui.sequence", "ui.flow",
                        "screen.ocr", "screen.findImage", "ui.analyze",
                        "audio.record", "audio.playback"
                    )))
                    token?.let { put("token", it) }
                }
                val connect = JSONObject().apply {
                    put("type", "connect")
                    put("payload", payload)
                }
                webSocket.send(connect.toString())
                Log.d(TAG, "Sent connect: ${connect}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleMessage(webSocket, text)
                } catch (e: Exception) {
                    Log.e(TAG, "handleMessage", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closing: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                if (webSocket === ws) {
                    ws = null
                    nodeId = null
                    onStatus(Status.DISCONNECTED)
                    if (!userDisconnected) scheduleReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === ws) {
                    ws = null
                    nodeId = null
                    onStatus(Status.DISCONNECTED)
                    if (!userDisconnected) scheduleReconnect()
                }
            }
        })
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val frame = JSONObject(text)
        val type = frame.optString("type", "")

        when (type) {
            "ping" -> {
                webSocket.send(JSONObject().apply {
                    put("type", "pong")
                    put("ts", System.currentTimeMillis())
                }.toString())
            }
            "voicewake_config" -> {
                val payload = frame.optJSONObject("payload") ?: frame
                if (payload.length() > 0) onVoiceWakeConfig?.invoke(payload)
            }
            "connect_result" -> {
                val ok = frame.optBoolean("ok", false)
                if (ok) {
                    nodeId = frame.optString("nodeId", "").takeIf { it.isNotBlank() }
                    if (nodeId != null) {
                        hasEverConnected = true
                        onStatus(Status.CONNECTED)
                        Log.d(TAG, "Connected as nodeId=$nodeId")
                    }
                } else if (frame.optBoolean("needPairing", false)) {
                    hasEverConnected = true // 已与服务器建立连接并收到需配对响应，断连后继续重试
                    onStatus(Status.PENDING_PAIRING)
                    Log.d(TAG, "Need pairing: ${frame.optString("requestId")}")
                } else {
                    Log.e(TAG, "Connect failed: ${frame.optString("error")}")
                    onStatus(Status.DISCONNECTED)
                }
            }
            "paired" -> {
                val nid = frame.optString("nodeId")
                val tok = frame.optString("token")
                if (nid.isNotBlank() && tok.isNotBlank()) {
                    hasEverConnected = true
                    nodeId = nid
                    onPaired?.invoke(tok)
                    onStatus(Status.CONNECTED)
                    Log.d(TAG, "Paired: nodeId=$nid")
                    // 模仿桌面端：在当前连接上重发带 token 的 connect 帧，让 Gateway 立即注册上线
                    val reconnectPayload = JSONObject().apply {
                        put("role", "node")
                        put("deviceId", deviceId)
                        put("displayName", displayName)
                        put("platform", "android")
                        put("protocolVersion", "1")
                        put("token", tok)
                        put("capabilities", org.json.JSONArray(listOf(
                            "camera.snap", "camera.clip", "screen.record",
                            "location.get",
                            "ui.tap", "ui.input", "ui.swipe", "ui.back", "ui.home", "ui.dump",
                            "ui.longPress", "ui.launch", "ui.scroll", "ui.waitFor", "ui.wait",
                            "ui.doubleTap", "ui.takeOver", "ui.listApps", "ui.tapByImage", "ui.sequence", "ui.flow",
                            "screen.ocr", "screen.findImage", "ui.analyze",
                            "audio.record", "audio.playback"
                        )))
                    }
                    webSocket.send(JSONObject().apply {
                        put("type", "connect")
                        put("payload", reconnectPayload)
                    }.toString())
                }
            }
            "exec_approval_result" -> {
                val payload = frame.optJSONObject("payload") ?: frame
                val reqId = payload.optString("reqId")
                val approved = payload.optBoolean("approved", false)
                pendingExecApprovals.remove(reqId)?.invoke(approved)
            }
            "exec_approvals_update" -> {
                // Gateway 下发白名单更新，可持久化
            }
            "req" -> {
                if (frame.optString("method") == "node.invoke") {
                    val id = frame.optString("id")
                    val params = frame.optJSONObject("params") ?: JSONObject()
                    val command = params.optString("command", "")
                    val cmdParams = params.optJSONObject("params") ?: JSONObject()
                    val cmdMap = mutableMapOf<String, Any?>()
                    cmdParams.keys().forEach { k ->
                        cmdMap[k] = when (val v = cmdParams.opt(k)) {
                            is JSONObject -> v.toString()
                            else -> v
                        }
                    }
                    scope?.launch(Dispatchers.Default) {
                        try {
                            val result = onCommand(command, id, cmdMap)
                            sendRes(webSocket, id, true, result)
                        } catch (e: Exception) {
                            sendRes(webSocket, id, false, mapOf("error" to (e.message ?: "Unknown error")))
                        }
                    }
                }
            }
        }
    }

    private fun sendRes(webSocket: WebSocket, id: String, ok: Boolean, payload: Map<String, Any?>) {
        val res = JSONObject().apply {
            put("type", "res")
            put("id", id)
            put("ok", ok)
            put("payload", JSONObject(payload))
        }
        webSocket.send(res.toString())
    }

    /** 断连后自动重试：若曾有成功连接则一直重试；否则首次连接前最多重试 MAX_RECONNECT_BEFORE_FIRST_SUCCESS 次 */
    private fun scheduleReconnect() {
        scope ?: return
        if (!hasEverConnected && reconnectAttempts >= MAX_RECONNECT_BEFORE_FIRST_SUCCESS) {
            Log.w(TAG, "Reconnect stopped: never connected after $reconnectAttempts attempts. Tap Connect to retry.")
            return
        }
        reconnectJob?.cancel()
        val delay = minOf(RECONNECT_BASE_MS * (1L shl reconnectAttempts), RECONNECT_MAX_MS)
        reconnectAttempts++
        Log.d(TAG, "Reconnect in ${delay}ms (attempt $reconnectAttempts, hasEverConnected=$hasEverConnected)")
        reconnectJob = scope!!.launch {
            delay(delay)
            if (scope!!.isActive && !userDisconnected) doConnect()
        }
    }

    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        ws?.close(1000, "user disconnect")
        ws = null
        nodeId = null
        onStatus(Status.DISCONNECTED)
    }

    /** 发送语音录音完成事件（供 AudioHandler 在录音结束后调用） */
    fun sendVoiceAudioReady(base64: String, format: String) {
        val nid = nodeId ?: return
        if (base64.isBlank()) return
        ws?.send(JSONObject().apply {
            put("type", "voice_audio_ready")
            put("payload", JSONObject().apply {
                put("nodeId", nid)
                put("base64", base64)
                put("format", format)
            })
        }.toString())
    }

    companion object {
        private const val TAG = "NodeClient"
    }
}
