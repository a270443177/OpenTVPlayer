package com.opentv.player.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 回看（catch-up / 时移）地址构造。
 *
 * 支持四类来源，按优先级：
 *  1) `catchup-source` 含 `${start}` / `${end}` 占位符 → UTC yyyyMMddHHmmss 直接替换
 *  2) `catchup-source` 无占位符 → 追加 utcstart / utcend
 *  3) 中兴 ZTE 平台（URL 含 `live_hls.zte.com`）→ 自动改域名为 `tstv_hls.zte.com`
 *     并追加 `playseek=<utc起>-<utc止>`（实测该接口每次只给 60 秒窗口，需客户端接力）
 *  4) 其余（Xtream append 风格）→ 追加 start / end（yyyy-MM-dd:HH-mm-ss，UTC）
 */
object Catchup {

    /** ZTE 时移单次请求返回的窗口长度：实测固定 10×6s = 60 秒 */
    const val ZTE_WINDOW_MS = 60_000L

    /** ZTE 时移可回看上限（网关 timeshift_length 实测 7200/10800 秒，取 3 小时） */
    const val ZTE_LOOKBACK_MS = 3 * 60 * 60 * 1000L

    private const val ZTE_LIVE_DOMAIN = "live_hls.zte.com"
    private const val ZTE_TSTV_DOMAIN = "tstv_hls.zte.com"

    private fun fmt(pattern: String, t: Long): String {
        val f = SimpleDateFormat(pattern, Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(t))
    }

    /** 是否中兴时移型直播地址（可自动推导回看）。 */
    fun isZteTimeshift(url: String?): Boolean =
        !url.isNullOrBlank() && url.contains(ZTE_LIVE_DOMAIN)

    /** 该频道是否支持回看。 */
    fun canCatchup(channel: Channel): Boolean =
        !channel.catchupSource.isNullOrBlank() ||
            channel.catchupDays > 0 ||
            !channel.catchup.isNullOrBlank() ||
            isZteTimeshift(channel.url)

    /**
     * 回看可追溯的最大时长（毫秒）；返回 null 表示不限制。
     * 用于在界面上判断"该节目是不是已经超出可回看范围"。
     */
    fun lookbackMs(channel: Channel): Long? = when {
        channel.catchupDays > 0 -> channel.catchupDays * 24L * 60 * 60 * 1000
        isZteTimeshift(channel.url) -> ZTE_LOOKBACK_MS
        else -> null
    }

    /** 生成的回看地址是否为"单窗口"型（需要客户端接力拉多个窗口）。 */
    fun isWindowed(channel: Channel): Boolean = isZteTimeshift(channel.url)

    /** 构造单个回看地址（[startMs, endMs] 区间）。 */
    fun buildUrl(channel: Channel, startMs: Long, endMs: Long): String? {
        val base = channel.url
        val src = channel.catchupSource

        if (!src.isNullOrBlank()) {
            val compactStart = fmt("yyyyMMddHHmmss", startMs)
            val compactEnd = fmt("yyyyMMddHHmmss", endMs)
            if (src.contains("\${start}") || src.contains("\${end}") || src.contains("\${utc}")) {
                return src
                    .replace("\${start}", compactStart)
                    .replace("\${end}", compactEnd)
                    .replace("\${utc}", compactStart)
            }
            val sep = if (src.contains('?')) '&' else '?'
            return "$src${sep}utcstart=$compactStart&utcend=$compactEnd"
        }

        zteUrl(base, startMs, endMs)?.let { return it }

        val mode = channel.catchup?.lowercase(Locale.US)
        if (mode == null || mode.isBlank() || mode == "append" || mode == "default" || mode == "xtream") {
            val sep = if (base.contains('?')) '&' else '?'
            val s = fmt("yyyy-MM-dd:HH-mm-ss", startMs)
            val e = fmt("yyyy-MM-dd:HH-mm-ss", endMs)
            return "$base${sep}start=$s&end=$e"
        }
        return null
    }

    /**
     * 构造整档节目的可播放地址列表。
     * ZTE 时移每次只返回 60 秒窗口，这里按 60 秒切段，播放器可把它们串成一条无缝播放列表。
     */
    fun buildWindows(channel: Channel, startMs: Long, endMs: Long, limit: Int = 300): List<String> {
        if (endMs <= startMs) return emptyList()
        if (!isZteTimeshift(channel.url)) {
            return listOfNotNull(buildUrl(channel, startMs, endMs))
        }
        val out = ArrayList<String>()
        var t = startMs
        var guard = 0
        while (t < endMs && guard < limit) {
            zteUrl(channel.url, t, t + ZTE_WINDOW_MS)?.let { out.add(it) }
            t += ZTE_WINDOW_MS
            guard++
        }
        return out
    }

    /** ZTE：仅换域名 + 追加 playseek，其余认证参数原样保留。（录制复用） */
    fun zteWindowUrl(url: String?, startMs: Long, endMs: Long): String? = zteUrl(url, startMs, endMs)

    /** ZTE：仅换域名 + 追加 playseek，其余认证参数原样保留。 */
    private fun zteUrl(url: String?, startMs: Long, endMs: Long): String? {
        if (!isZteTimeshift(url)) return null
        val tstv = url!!.replace(ZTE_LIVE_DOMAIN, ZTE_TSTV_DOMAIN)
        val sep = if (tstv.contains('?')) '&' else '?'
        return "$tstv${sep}playseek=${fmt("yyyyMMddHHmmss", startMs)}-${fmt("yyyyMMddHHmmss", endMs)}"
    }
}
