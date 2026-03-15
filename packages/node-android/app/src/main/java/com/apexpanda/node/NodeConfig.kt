package com.apexpanda.node

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

object NodeConfig {
    private const val PREFS = "apexpanda_node"
    private const val KEY_GATEWAY = "gateway_url"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_TOKEN = "token"
    private const val NODE_JSON = "node.json"
    private const val VOICEWAKE_JSON = "voicewake.json"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getGatewayUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_GATEWAY, "") ?: ""

    fun setGatewayUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_GATEWAY, url).apply()
    }

    fun getDisplayName(ctx: Context): String =
        prefs(ctx).getString(KEY_DISPLAY_NAME, "我的手机") ?: "我的手机"

    fun setDisplayName(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_DISPLAY_NAME, name).apply()
    }

    fun getDeviceId(ctx: Context): String {
        val prefs = prefs(ctx)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getToken(ctx: Context): String? =
        prefs(ctx).getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: readNodeJson(ctx)?.optString("token", null)?.takeIf { it.isNotBlank() }

    fun setToken(ctx: Context, token: String) {
        prefs(ctx).edit().putString(KEY_TOKEN, token).apply()
        val file = File(ctx.filesDir, NODE_JSON)
        val json = JSONObject().apply {
            put("token", token)
            put("deviceId", getDeviceId(ctx))
        }
        file.parentFile?.mkdirs()
        file.writeText(json.toString(2))
    }

    private fun readNodeJson(ctx: Context): JSONObject? {
        return try {
            val file = File(ctx.filesDir, NODE_JSON)
            if (file.exists()) JSONObject(file.readText()) else null
        } catch (_: Exception) {
            null
        }
    }

    /** 保存 voicewake 配置（从 Gateway 下发的 voicewake_config） */
    fun saveVoiceWakeConfig(ctx: Context, config: JSONObject) {
        try {
            File(ctx.filesDir, VOICEWAKE_JSON).writeText(config.toString(2))
        } catch (_: Exception) { }
    }
}
