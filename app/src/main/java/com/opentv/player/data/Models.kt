package com.opentv.player.data

import org.json.JSONArray
import org.json.JSONObject

enum class PlaylistType { M3U, XTREAM }

data class Channel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    /** 频道号（M3U tvg-chno / Xtream num） */
    val number: Int? = null,
    /** 回看模式：append / default / shift / "" */
    val catchup: String? = null,
    /** 回看地址模板，可含 ${start} / ${end} 占位符 */
    val catchupSource: String? = null,
    /** 可回看天数 */
    val catchupDays: Int = 0,
    /** 所属播放列表名（便于信息展示） */
    val playlist: String? = null
) {
    val supportsCatchup: Boolean get() = catchupDays > 0 || !catchupSource.isNullOrBlank() || !catchup.isNullOrBlank()
}

data class Playlist(
    val id: String,
    val name: String,
    val type: PlaylistType,
    val sourceUrl: String? = null,
    val epgUrl: String? = null,
    val server: String? = null,
    val username: String? = null,
    val password: String? = null,
    val channels: List<Channel> = emptyList()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("type", type.name)
        o.put("sourceUrl", sourceUrl ?: JSONObject.NULL)
        o.put("epgUrl", epgUrl ?: JSONObject.NULL)
        o.put("server", server ?: JSONObject.NULL)
        o.put("username", username ?: JSONObject.NULL)
        o.put("password", password ?: JSONObject.NULL)
        val arr = JSONArray()
        for (c in channels) {
            val co = JSONObject()
            co.put("name", c.name)
            co.put("url", c.url)
            co.put("logo", c.logo ?: JSONObject.NULL)
            co.put("group", c.group ?: JSONObject.NULL)
            co.put("tvgId", c.tvgId ?: JSONObject.NULL)
            co.put("number", c.number ?: JSONObject.NULL)
            co.put("catchup", c.catchup ?: JSONObject.NULL)
            co.put("catchupSource", c.catchupSource ?: JSONObject.NULL)
            co.put("catchupDays", c.catchupDays)
            arr.put(co)
        }
        o.put("channels", arr)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Playlist {
            val arr = o.optJSONArray("channels") ?: JSONArray()
            val list = ArrayList<Channel>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                list.add(
                    Channel(
                        name = c.optString("name"),
                        url = c.optString("url"),
                        logo = c.optStringOrNull("logo"),
                        group = c.optStringOrNull("group"),
                        tvgId = c.optStringOrNull("tvgId"),
                        number = if (c.isNull("number")) null else c.optInt("number", -1).takeIf { it >= 0 },
                        catchup = c.optStringOrNull("catchup"),
                        catchupSource = c.optStringOrNull("catchupSource"),
                        catchupDays = c.optInt("catchupDays", 0)
                    )
                )
            }
            val type = try {
                PlaylistType.valueOf(o.optString("type"))
            } catch (e: Exception) {
                PlaylistType.M3U
            }
            return Playlist(
                id = o.optString("id"),
                name = o.optString("name"),
                type = type,
                sourceUrl = o.optStringOrNull("sourceUrl"),
                epgUrl = o.optStringOrNull("epgUrl"),
                server = o.optStringOrNull("server"),
                username = o.optStringOrNull("username"),
                password = o.optStringOrNull("password"),
                channels = list
            )
        }
    }
}

fun JSONObject.optStringOrNull(key: String): String? {
    if (isNull(key)) return null
    val s = optString(key, "")
    return if (s.isBlank()) null else s
}
