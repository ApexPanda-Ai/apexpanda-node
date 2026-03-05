package com.apexpanda.node

import android.Manifest
import android.provider.Settings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.apexpanda.node.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var receiverRegistered = false

    private fun formatPermItem(name: String, granted: Boolean) =
        if (granted) "✓ $name" else "○ $name"

    private fun updatePermissionStatus() {
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val locOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val projOk = ProjectionHolder.hasPermission()

        binding.tvPermCamera.text = formatPermItem("相机（拍照）", cameraOk)
        binding.tvPermMic.text = formatPermItem("麦克风", micOk)
        binding.tvPermLocation.text = formatPermItem("定位", locOk)
        binding.tvPermProjection.text = formatPermItem("录屏", projOk)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.tvPermNotification.visibility = View.VISIBLE
            binding.tvPermNotification.text = formatPermItem("通知", notifOk)
        }
    }
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(NodeService.EXTRA_STATUS) ?: return
            applyStatus(status)
        }
    }

    private fun applyStatus(status: String) {
        val ui = when (status) {
            "CONNECTING" -> StatusUi("连接中…", false, false)
            "PENDING_PAIRING" -> StatusUi("等待配对审批\n请在电脑浏览器打开 Dashboard 审批", false, true)
            "CONNECTED" -> StatusUi("已连接", false, true)
            else -> StatusUi("未连接", true, false)
        }
        binding.tvStatus.text = ui.text
        binding.viewStatusDot.setBackgroundResource(
            when (status) {
                "CONNECTED" -> R.drawable.bg_status_dot_connected
                "CONNECTING" -> R.drawable.bg_status_dot_connecting
                "PENDING_PAIRING" -> R.drawable.bg_status_dot_pending
                else -> R.drawable.bg_status_dot_disconnected
            }
        )
        binding.btnConnect.isEnabled = ui.connectEnabled
        binding.btnDisconnect.isEnabled = ui.disconnectEnabled
    }

    private data class StatusUi(val text: String, val connectEnabled: Boolean, val disconnectEnabled: Boolean)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
        updatePermissionStatus()
        if (!ProjectionHolder.hasPermission()) {
            requestProjection()
        } else if (map.values.all { it }) {
            Toast.makeText(this, "所有权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "部分权限未授予。若点过「不再询问」，请到 设置→应用→ApexPanda 节点→权限 中手动开启",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val mgr = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            ProjectionHolder.setFromResult(mgr, result.resultCode, result.data)
            Toast.makeText(this, "录屏权限已授权", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要录屏权限才能屏幕录制", Toast.LENGTH_LONG).show()
        }
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etGateway.setText(NodeConfig.getGatewayUrl(this))
        binding.etDisplayName.setText(NodeConfig.getDisplayName(this))
        applyStatus(NodeService.lastStatus)
        updatePermissionStatus()

        binding.root.post {
            when {
                needsPermissions() -> requestPermissions()
                !ProjectionHolder.hasPermission() -> requestProjection()
            }
        }

        binding.btnConnect.setOnClickListener {
            val url = binding.etGateway.text.toString().trim()
            val name = binding.etDisplayName.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "请输入 Gateway 地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            NodeConfig.setGatewayUrl(this, url)
            NodeConfig.setDisplayName(this, name.ifBlank { "我的手机" })
            requestPermissions()
            val serviceIntent = Intent(this, NodeService::class.java).apply {
                action = NodeService.ACTION_START
                putExtra(NodeService.EXTRA_GATEWAY_URL, url)
                putExtra(NodeService.EXTRA_DISPLAY_NAME, name.ifBlank { "我的手机" })
            }
            applyStatus("CONNECTING")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "正在连接…", Toast.LENGTH_SHORT).show()
        }

        binding.btnDisconnect.setOnClickListener {
            applyStatus(NodeService.EXTRA_STATUS_DISCONNECTED)
            NodeService.lastStatus = NodeService.EXTRA_STATUS_DISCONNECTED
            stopService(Intent(this, NodeService::class.java).apply { action = NodeService.ACTION_STOP })
            Toast.makeText(this, "已断开", Toast.LENGTH_SHORT).show()
        }

        binding.btnProjection.setOnClickListener { requestProjection() }

        binding.btnPermissions.setOnClickListener {
            requestPermissions()
        }

        binding.btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "请在列表中找到「ApexPanda 节点」并开启", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        applyStatus(NodeService.lastStatus)
        updatePermissionStatus()
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver, IntentFilter(NodeService.ACTION_STATUS_UPDATE), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, IntentFilter(NodeService.ACTION_STATUS_UPDATE))
            }
            receiverRegistered = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (receiverRegistered) {
            try { unregisterReceiver(statusReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
    }

    private fun needsPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return true
        }
        return false
    }

    private fun requestProjection() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        when {
            perms.isEmpty() -> {
                if (!ProjectionHolder.hasPermission()) requestProjection()
                else Toast.makeText(this, "所有权限已授予", Toast.LENGTH_SHORT).show()
            }
            else -> permissionLauncher.launch(perms.toTypedArray())
        }
    }
}
