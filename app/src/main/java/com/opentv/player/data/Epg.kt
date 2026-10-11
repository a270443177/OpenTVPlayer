package com.opentv.player.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class EpgProgram(
    val start: Long,
    val end: Long,
    val title: String,
    val desc: String? = null,
    val category: String? = null
) {
    fun progress(now: Long): Float {
        val total = (end - start).toFloat()
        if (total <= 0f) return 0f
        return ((now - start).toFloat() / total).coerceIn(0f, 1f)
    }

    fun isLive(now: Long): Boolean = now >= start && now < end
}

/** 轻量 XMLTV 解析：提取 <programme> 的 channel / start / stop / title / desc / category。 */
object EpgParser {

    fun parse(xml: String): Map<String, List<EpgProgram>> {
        val map = HashMap<String, MutableList<EpgProgram>>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            var channelId: String? = null
            var start = 0L
            var end = 0L
            var title: String? = null
            var desc: String? = null
            var category: String? = null
            var tagOfInterest: String? = null

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name
                when (event) {
                    XmlPullParser.START_TAG -> when {
                        tag.equals("programme", true) -> {
                            channelId = parser.getAttributeValue(null, "channel")
                            start = parseXmltvTime(parser.getAttributeValue(null, "start"))
                            end = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                            title = null; desc = null; category = null
                        }
                        tag.equals("title", true) -> tagOfInterest = "title"
                        tag.equals("desc", true) -> tagOfInterest = "desc"
                        tag.equals("category", true) -> tagOfInterest = "category"
                        else -> { /* 其他子标签忽略 */ }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.takeIf { it.isNotBlank() }
                        if (text != null) {
                            when (tagOfInterest) {
                                "title" -> title = text
                                "desc" -> desc = text
                                "category" -> category = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when {
                        tag.equals("title", true) || tag.equals("desc", true) || tag.equals("category", true) ->
                            tagOfInterest = null
                        tag.equals("programme", true) -> {
                            val cid = channelId
                            if (cid != null && end > start) {
                                map.getOrPut(cid) { ArrayList() }.add(
                                    EpgProgram(start, end, title ?: "", desc, category)
                                )
                            }
                            channelId = null
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // 解析失败返回已解析部分
        }
        for (v in map.values) v.sortBy { it.start }
        return map
    }

    fun parseXmltvTime(s: String?): Long {
        if (s.isNullOrBlank() || s.length < 14) return 0L
        return try {
            val body = s.substring(0, 14)
            val tzRaw = if (s.length >= 19) s.substring(15, 20) else "+0000"
            val tz = TimeZone.getTimeZone("GMT" + tzRaw.substring(0, 3) + ":" + tzRaw.substring(3, 5))
            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            fmt.timeZone = tz
            fmt.parse(body)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun current(list: List<EpgProgram>?, now: Long): EpgProgram? {
        if (list == null) return null
        for (p in list) if (p.isLive(now)) return p
        return null
    }

    fun next(list: List<EpgProgram>?, now: Long): EpgProgram? {
        if (list == null) return null
        var best: EpgProgram? = null
        for (p in list) {
            if (p.start > now && (best == null || p.start < best.start)) best = p
        }
        return best
    }

    /** 取 [from, to) 时间窗内与窗口相交的节目 */
    fun window(list: List<EpgProgram>?, from: Long, to: Long): List<EpgProgram> {
        if (list == null) return emptyList()
        return list.filter { it.end > from && it.start < to }
    }

    // ---------------- 时间格式化 ----------------

    private fun fmt(pattern: String, t: Long): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(t))

    fun timeOfDay(t: Long, clock24h: Boolean = true): String =
        fmt(if (clock24h) "HH:mm" else "hh:mm", t)

    fun dateTime(t: Long): String = fmt("MM-dd HH:mm", t)

    fun fullDate(t: Long): String = fmt("yyyy-MM-dd HH:mm", t)

    fun clock(t: Long, clock24h: Boolean = true): String =
        fmt(if (clock24h) "HH:mm:ss" else "hh:mm:ss", t)

    /** 当天 00:00 的毫秒时间戳（用于指南时间轴） */
    fun startOfDay(t: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
