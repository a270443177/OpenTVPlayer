package com.opentv.player.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** 极简 HTTP 客户端，仅依赖 JDK/Android 内置的 HttpURLConnection，避免额外依赖。 */
object Http {
    const val DEFAULT_UA = "OpenTVPlayer/1.0"

    @Throws(IOException::class)
    fun get(url: String, userAgent: String = DEFAULT_UA, timeoutMs: Int = 25000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 下载原始字节（XMLTV/台标等二进制安全场景）。 */
    @Throws(IOException::class)
    fun getBytes(url: String, userAgent: String = DEFAULT_UA, timeoutMs: Int = 25000): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载文本，自动识别 gzip（.xml.gz 常见的 XMLTV 压缩格式）：
     * 按 magic bytes 1f 8b 判断，而非仅看扩展名。
     */
    @Throws(IOException::class)
    fun getAutoText(url: String, userAgent: String = DEFAULT_UA, timeoutMs: Int = 25000): String {
        val bytes = getBytes(url, userAgent, timeoutMs)
        val gz = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        return if (gz) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            bytes.toString(Charsets.UTF_8)
        }
    }
}
