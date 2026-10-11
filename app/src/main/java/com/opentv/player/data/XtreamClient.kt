package com.opentv.player.data

import org.json.JSONArray

/** Xtream Codes 兼容 API 客户端（公开协议：player_api.php）。 */
object XtreamClient {

    private const val UNGROUPED = "未分组"

    fun normalizeServer(input: String): String {
        var v = input.trim().trimEnd('/')
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://$v"
        return v
    }

    fun liveStreamUrl(server: String, user: String, pass: String, streamId: String): String {
        val base = normalizeServer(server)
        return "$base/live/$user/$pass/$streamId.m3u8"
    }

    fun load(
        server: String,
        user: String,
        pass: String,
        userAgent: String = Http.DEFAULT_UA,
        onProgress: (String) -> Unit = {}
    ): List<Channel> {
        val base = normalizeServer(server)
        val api = "$base/player_api.php?username=$user&password=$pass"

        val categories = HashMap<String, String>()
        onProgress("获取分类…")
        try {
            val arr = JSONArray(Http.get("$api&action=get_live_categories", userAgent))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                categories[o.optString("category_id")] = o.optString("category_name")
            }
        } catch (e: Exception) {
            // 分类获取失败不致命，继续拿频道
        }

        onProgress("获取频道…")
        val streams = JSONArray(Http.get("$api&action=get_live_streams", userAgent))
        val out = ArrayList<Channel>(streams.length())
        for (i in 0 until streams.length()) {
            val o = streams.getJSONObject(i)
            val id = o.optString("stream_id")
            if (id.isBlank()) continue
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: "Channel $id"
            val catId = o.optString("category_id")
            val group = categories[catId]?.takeIf { it.isNotBlank() } ?: UNGROUPED
            val logo = o.optString("stream_icon").takeIf { it.isNotBlank() }
            val tvg = o.optString("epg_channel_id").takeIf { it.isNotBlank() }
            out.add(Channel(name, liveStreamUrl(base, user, pass, id), logo, group, tvg))
        }
        onProgress("共 ${out.size} 个频道")
        return out
    }
}
