package com.opentv.player.data

/** M3U / M3U8 播放列表解析器（#EXTINF 属性 + 回看属性 + URL）。 */
object M3uParser {

    private val ATTR = Regex("([A-Za-z0-9_.-]+)=\"([^\"]*)\"")
    const val UNGROUPED = "未分组"

    fun parse(text: String, playlistName: String? = null): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var tvgId: String? = null
        var number: Int? = null
        var catchup: String? = null
        var catchupSource: String? = null
        var catchupDays = 0
        var extGrp: String? = null
        var pendingUrl: String? = null

        fun flush() {
            val u = pendingUrl ?: return
            val n = name
            if (n != null) {
                out.add(
                    Channel(
                        name = n,
                        url = u,
                        logo = logo,
                        group = group ?: extGrp ?: UNGROUPED,
                        tvgId = tvgId,
                        number = number,
                        catchup = catchup,
                        catchupSource = catchupSource,
                        catchupDays = catchupDays,
                        playlist = playlistName
                    )
                )
            } else {
                val fallback = u.substringAfterLast('/').takeIf { it.isNotBlank() } ?: u
                out.add(Channel(fallback, u, null, extGrp ?: UNGROUPED, null, null, null, null, 0, playlistName))
            }
            name = null; logo = null; group = null; tvgId = null; number = null
            catchup = null; catchupSource = null; catchupDays = 0; pendingUrl = null
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = HashMap<String, String>()
                    ATTR.findAll(line).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }
                    val comma = line.indexOf(',')
                    val title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    name = when {
                        title.isNotBlank() -> title
                        !attrs["tvg-name"].isNullOrBlank() -> attrs["tvg-name"]!!
                        else -> "未命名频道"
                    }
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                    number = attrs["tvg-chno"]?.toIntOrNull() ?: attrs["channel-number"]?.toIntOrNull()
                    catchup = attrs["catchup"]?.takeIf { it.isNotBlank() }
                    catchupSource = (attrs["catchup-source"] ?: attrs["catchup-source-url"])?.takeIf { it.isNotBlank() }
                    catchupDays = attrs["catchup-days"]?.toIntOrNull()
                        ?: attrs["timeshift"]?.toIntOrNull() ?: 0
                }
                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    extGrp = line.substringAfter(':', "").trim().takeIf { it.isNotBlank() }
                }
                line.startsWith("#") -> {
                    // 其他指令行忽略（#EXTM3U / #EXTVLCOPT 等）
                }
                else -> {
                    pendingUrl = line
                    flush()
                }
            }
        }
        return out
    }
}
