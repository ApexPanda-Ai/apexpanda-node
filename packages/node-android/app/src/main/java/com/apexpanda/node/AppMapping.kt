package com.apexpanda.node

import android.util.Log
import org.json.JSONObject
import java.io.InputStreamReader

/**
 * 应用名 → 包名映射表（借鉴 Open-AutoGLM phone_agent/config/apps.py）
 * 支持 ui.launch 按应用名（如「微信」「美团」）启动，无需记忆包名
 *
 * 优先从 assets/app_mapping.json 加载，便于增删应用无需改 Kotlin 代码；
 * 若加载失败则使用内置映射。
 */
object AppMapping {

    private const val TAG = "AppMapping"
    private const val ASSET_FILE = "app_mapping.json"

    /** 内置映射（assets 加载失败时的后备） */
    private val BUILTIN = mapOf(
        "微信" to "com.tencent.mm",
        "QQ" to "com.tencent.mobileqq",
        "微博" to "com.sina.weibo",
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "美团" to "com.sankuai.meituan",
        "小红书" to "com.xingin.xhs",
        "抖音" to "com.ss.android.ugc.aweme",
        "Chrome" to "com.android.chrome",
        "Settings" to "com.android.settings",
        "WeChat" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "WhatsApp" to "com.whatsapp",
    )

    @Volatile
    private var _map: Map<String, String>? = null

    private fun getMap(): Map<String, String> {
        _map?.let { return it }
        synchronized(this) {
            _map?.let { return it }
            val loaded = loadFromAssets()
            _map = if (loaded.isNotEmpty()) loaded else BUILTIN
            return _map!!
        }
    }

    private fun loadFromAssets(): Map<String, String> {
        val app = ApexPandaApp.instance ?: return emptyMap()
        return try {
            app.assets.open(ASSET_FILE).use { input ->
                val json = InputStreamReader(input, Charsets.UTF_8).readText()
                val obj = JSONObject(json)
                val out = mutableMapOf<String, String>()
                obj.keys().forEach { key ->
                    obj.optString(key).takeIf { it.isNotBlank() }?.let { pkg ->
                        out[key] = pkg
                    }
                }
                Log.d(TAG, "Loaded ${out.size} app mappings from $ASSET_FILE")
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $ASSET_FILE, using builtin: ${e.message}")
            emptyMap()
        }
    }

    /** 根据应用名获取包名，支持忽略大小写、去除空格 */
    fun getPackage(appName: String): String? {
        val key = appName.trim()
        val m = getMap()
        return m[key]
            ?: m.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
    }

    /** 获取所有支持的应用名称列表 */
    fun listApps(): List<String> = getMap().keys.toList()
}
