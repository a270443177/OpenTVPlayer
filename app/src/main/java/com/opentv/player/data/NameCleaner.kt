package com.opentv.player.data

/**
 * 频道名批量清理：去掉播放列表里常见的画质 / 编码后缀与纯序号前缀，
 * 让频道列表和搜索更干净、更好读（如 "CCTV-1 高清" → "CCTV-1"）。
 */
object NameCleaner {

    /** 尾部画质 / 编码标记：HD、FHD、4K、H265、HEVC、高清、超清… 可带括号 */
    private val TAIL_QUALITY = Regex(
        "[\\s\\-_|·]*[\\[(（【]?\\s*" +
            "(FHD|UHD|HD|SD|4K|8K|2160P|1080P|1080I|720P|576P|480P|" +
            "H\\.?265|H\\.?264|HEVC|AVC|X265|X264|HDR10\\+?|HDR|HLG|DOLBY|ATMOS|" +
            "50FPS|60FPS|25FPS|30FPS|24FPS|FPS|" +
            "高清|超清|超高清|蓝光|标清|原画|流畅)" +
            "\\s*[\\])）】]?[\\s\\-_|·]*$",
        RegexOption.IGNORE_CASE
    )

    /** 开头的纯序号前缀："001 " / "1. " / "[02] " / "03、" */
    private val LEAD_INDEX = Regex("^\\s*[\\[(（【]?\\s*\\d{1,4}\\s*[\\])）】]?\\s*[\\.、:：\\-_|]?\\s+")

    /** 结尾残留的分隔符与空白 */
    private val TAIL_SEP = Regex("[\\s\\-_|·:：]+$")

    fun clean(raw: String): String {
        val original = raw.trim()
        if (original.isEmpty()) return original
        var s = original
        // 反复剥离叠加的画质标记（如 " CCTV1 HD 4K "）
        var guard = 0
        while (guard < 4) {
            val before = s
            s = TAIL_QUALITY.replace(s, "")
            s = TAIL_SEP.replace(s, "")
            if (s == before) break
            guard++
        }
        // 去掉纯序号前缀，但保证剩余名字仍有意义
        val withoutLead = LEAD_INDEX.replace(s, "").trim()
        if (withoutLead.length >= 2) s = withoutLead
        val result = s.trim()
        return result.ifBlank { original }
    }
}
