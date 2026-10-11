package com.opentv.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 附加 EPG 源（独立于播放列表的 XMLTV 地址，可添加 / 命名 / 删除多个）。
 * 存储在 SharedPreferences 的 JSON 数组里。
 */
object EpgSources {

    data class Source(val id: String, val name: String, val url: String)

    private const val PREFS = "opentv_epg"
    private const val KEY = "sources"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Source> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Source>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.optString("url")
                if (url.isNotBlank()) {
                    out.add(Source(o.optString("id"), o.optString("name").ifBlank { "EPG" }, url))
                }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Source>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("url", s.url)
                }
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, name: String, url: String) {
        val s = Source(java.util.UUID.randomUUID().toString(), name.ifBlank { "EPG" }, url.trim())
        save(ctx, all(ctx) + s)
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, all(ctx).filter { it.id != id })
    }

    /** 全部 EPG 源地址（播放列表自带 + 附加源），去重保序。 */
    fun allUrls(ctx: Context): List<String> {
        val urls = LinkedHashSet<String>()
        Library.playlists(ctx).mapNotNull { it.epgUrl?.takeIf { u -> u.isNotBlank() } }.forEach { urls.add(it) }
        all(ctx).forEach { urls.add(it.url) }
        return urls.toList()
    }
}
