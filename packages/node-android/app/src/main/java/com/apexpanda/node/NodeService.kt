package com.apexpanda.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：保持 WebSocket 连接
 */
class NodeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var client: NodeClient? = null
    private var lastIntent: Intent? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastIntent = intent
        when (intent?.action) {
            ACTION_START -> startClient()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lastStatus = EXTRA_STATUS_DISCONNECTED
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, EXTRA_STATUS_DISCONNECTED)
            setPackage(packageName)
        })
        client?.disconnect()
        client = null
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ApexPanda 节点",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForeground() {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ApexPanda 节点")
            .setContentText("保持连接中…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startClient() {
        val gatewayUrl = lastIntent?.getStringExtra(EXTRA_GATEWAY_URL) ?: NodeConfig.getGatewayUrl(this)
        if (gatewayUrl.isBlank()) return
        val displayName = lastIntent?.getStringExtra(EXTRA_DISPLAY_NAME) ?: NodeConfig.getDisplayName(this)
        val deviceId = NodeConfig.getDeviceId(this)
        val token = NodeConfig.getToken(this)

        startForeground()

        client?.disconnect()
        client = NodeClient(
            gatewayUrl = gatewayUrl,
            deviceId = deviceId,
            displayName = displayName,
            token = token,
            onStatus = { status ->
                lastStatus = status.name
                scope.launch(Dispatchers.Main) {
                    val text = when (status) {
                        NodeClient.Status.CONNECTING -> "连接中…"
                        NodeClient.Status.PENDING_PAIRING -> "等待配对审批…"
                        NodeClient.Status.CONNECTED -> "已连接"
                        NodeClient.Status.DISCONNECTED -> "未连接"
                    }
                    updateNotification(text)
                    sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
                        putExtra(EXTRA_STATUS, status.name)
                        setPackage(packageName)
                    })
                }
            },
            onCommand = { command, _, params ->
                val handler = CommandHandler(this)
                handler.execute(command, params)
            },
            onPaired = { token ->
                NodeConfig.setToken(this, token)
            }
        )
        client?.connect(scope)
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ApexPanda 节点")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_START = "com.apexpanda.node.START"
        const val ACTION_STOP = "com.apexpanda.node.STOP"
        const val ACTION_STATUS_UPDATE = "com.apexpanda.node.STATUS_UPDATE"
        const val EXTRA_GATEWAY_URL = "gateway_url"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_STATUS = "status"
        const val EXTRA_STATUS_DISCONNECTED = "DISCONNECTED"
        @Volatile var lastStatus: String = EXTRA_STATUS_DISCONNECTED
        private const val CHANNEL_ID = "apexpanda_node"
        private const val NOTIFICATION_ID = 1001
    }
}
