package com.opentv.player.net

import android.content.Context
import com.opentv.player.data.PlaybackPrefs
import java.util.Locale

/**
 * 播放地址解析：组播地址 → 可播放的 HTTP 地址。
 *
 * 优先级：用户配置的 UDPXY 代理 > 内置组播代理（MulticastProxy）> 原样返回。
 */
object StreamResolver {

    private val SCHEMES = listOf("udp://", "rtp://")

    fun isMulticast(url: String): Boolean {
        val u = url.trim().lowercase(Locale.US)
        return SCHEMES.any { u.startsWith(it) }
    }

    /** 提取组播的 host:port 部分（去掉 @ 与参数）。 */
    private fun multicastAddr(url: String): String? {
        var s = url.trim()
        for (sc in SCHEMES) {
            if (s.lowercase(Locale.US).startsWith(sc)) {
                s = s.substring(sc.length)
                break
            }
        }
        if (s.startsWith("@")) s = s.substring(1)
        s = s.substringBefore('/').substringBefore('?')
        val sep = s.lastIndexOf(':')
        if (sep <= 0) return null
        val ip = s.substring(0, sep)
        val port = s.substring(sep + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return "$ip:$port"
    }

    /**
     * 解析为 ExoPlayer / 外部播放器可用的地址。
     * 非组播地址原样返回。
     */
    fun resolve(context: Context, prefs: PlaybackPrefs, url: String): String {
        if (!isMulticast(url)) return url
        val addr = multicastAddr(url) ?: return url
        val isRtp = url.trim().lowercase(Locale.US).startsWith("rtp://")

        // 1) UDPXY 代理（udpxy：http://host:port/udp/ip:port）
        val proxy = prefs.udpxyUrl.trim()
        if (proxy.isNotBlank()) {
            val base = proxy.trimEnd('/')
            return "$base/${if (isRtp) "rtp" else "udp"}/$addr"
        }

        // 2) 内置组播代理
        try {
            MulticastProxy.startIfNeeded(context)
            MulticastProxy.localUrlFor(addr)?.let { return it }
        } catch (ignored: Exception) {
        }
        return url
    }
}
