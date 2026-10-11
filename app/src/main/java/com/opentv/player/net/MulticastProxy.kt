package com.opentv.player.net

import android.content.Context
import android.net.wifi.WifiManager
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 内置组播代理：把 `udp://@239.x.x.x:port`（或 rtp://）转成本机 HTTP 流。
 *
 * 原理：监听 127.0.0.1 的本地端口，收到 `/udp/<ip>:<port>` 请求后加入组播组，
 * 把收到的数据包以 MPEG-TS 形式写给 HTTP 客户端（ExoPlayer / 外部播放器均可直接播放）。
 * RTP 流按首字节特征自动剥离 12 字节 RTP 头。
 */
object MulticastProxy {

    private const val PREFERRED_PORT = 9981

    @Volatile
    private var port = 0

    private val startLock = Any()

    /** 已启动则直接返回，否则在后台线程启动监听。 */
    fun startIfNeeded(context: Context) {
        synchronized(startLock) {
            if (port != 0) return
            val app = context.applicationContext
            thread(name = "multicast-proxy") {
                var server: ServerSocket? = null
                try {
                    val s = ServerSocket()
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), PREFERRED_PORT))
                    server = s
                    port = PREFERRED_PORT
                    while (true) {
                        val client = try {
                            s.accept()
                        } catch (e: Exception) {
                            break
                        }
                        thread(name = "mc-conn") { handle(client, app) }
                    }
                } catch (e: Exception) {
                    // 端口被占用等情况：代理不可用，调用方回退原始地址
                    port = 0
                    try { server?.close() } catch (ignored: Exception) {}
                }
            }
        }
    }

    /** 本机代理地址；代理未就绪返回 null。 */
    fun localUrlFor(addr: String): String? {
        val p = port
        return if (p == 0) null else "http://127.0.0.1:$p/udp/$addr"
    }

    fun isRunning(): Boolean = port != 0

    // ---------------- 连接处理 ----------------

    private fun handle(client: Socket, app: Context) {
        var multicast: MulticastSocket? = null
        var mcLock: WifiManager.MulticastLock? = null
        try {
            client.tcpNoDelay = true
            val first = ByteArray(2048)
            val n = readRequest(client.getInputStream(), first)
            if (n <= 0) return
            val req = String(first, 0, n, Charsets.ISO_8859_1)
            val line = req.lineSequence().firstOrNull().orEmpty()
            val path = line.split(" ").getOrNull(1) ?: return
            // /udp/239.1.1.1:5140 或 /rtp/...
            val isRtp = path.startsWith("/rtp/", ignoreCase = true)
            val addrRaw = path.substringAfter('/', "").substringBefore('?')
            val sep = addrRaw.lastIndexOf(':')
            if (sep <= 0) return
            val group = InetAddress.getByName(addrRaw.substring(0, sep))
            val portNum = addrRaw.substring(sep + 1).trim().toIntOrNull() ?: return
            if (group !is java.net.Inet4Address) return

            // 组播在 Wi-Fi 上需要 MulticastLock
            try {
                val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifi != null) {
                    mcLock = wifi.createMulticastLock("opentv-mc").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            } catch (ignored: Exception) {
            }

            val sock = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(portNum))
                soTimeout = 5000
            }
            multicast = sock
            try {
                sock.joinGroup(group)
            } catch (e: Exception) {
                return
            }

            val out = client.getOutputStream()
            val headers = "HTTP/1.0 200 OK\r\n" +
                "Content-Type: video/mp2t\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-store\r\n\r\n"
            out.write(headers.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            pump(sock, out, isRtp)
        } catch (e: Exception) {
            // 客户端断开 / 组播失败，静默结束
        } finally {
            try { multicast?.close() } catch (ignored: Exception) {}
            try { mcLock?.release() } catch (ignored: Exception) {}
            try { client.close() } catch (ignored: Exception) {}
        }
    }

    /** 读到请求头结束（\r\n\r\n）为止，最多读 4KB。 */
    private fun readRequest(input: InputStream, buf: ByteArray): Int {
        var n = 0
        var headerEnd = -1
        val tmp = ByteArray(1024)
        while (n < buf.size && headerEnd < 0) {
            val r = input.read(tmp)
            if (r < 0) break
            val copy = minOf(r, buf.size - n)
            System.arraycopy(tmp, 0, buf, n, copy)
            n += copy
            headerEnd = String(buf, 0, n, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        }
        return n
    }

    /** 把组播包写给 HTTP 客户端，直到客户端断开或出错。 */
    private fun pump(sock: MulticastSocket, out: OutputStream, isRtp: Boolean) {
        val pkt = DatagramPacket(ByteArray(1316 + 64), 1316 + 64)
        val outBuf = ByteArray(188 * 42)
        var buffered = 0
        var rtpMode = isRtp
        var rtpChecked = false
        var lastFlush = System.currentTimeMillis()

        fun flush() {
            if (buffered > 0) {
                out.write(outBuf, 0, buffered)
                out.flush()
                buffered = 0
            }
            lastFlush = System.currentTimeMillis()
        }

        while (true) {
            try {
                sock.receive(pkt)
            } catch (e: java.net.SocketTimeoutException) {
                // 超时无数据：若客户端早已断开会在此处由 flush 抛错退出
                flush()
                continue
            }
            var data = pkt.data
            var off = pkt.offset
            var len = pkt.length

            if (!rtpChecked) {
                rtpChecked = true
                // RTP 特征：version=2 (b0 & 0xC0)==0x80 且 payload type=33 (MPEG-TS)
                if (len > 12 && (data[off].toInt() and 0xC0) == 0x80 && (data[off + 1].toInt() and 0x1F) == 33) {
                    rtpMode = true
                }
            }
            if (rtpMode && len > 12) {
                val cc = (data[off + 0].toInt() and 0x0F)
                val headerLen = 12 + cc * 4
                if (len > headerLen) {
                    off += headerLen
                    len -= headerLen
                } else {
                    continue
                }
            }
            if (buffered + len > outBuf.size) flush()
            System.arraycopy(data, off, outBuf, buffered, len)
            buffered += len
            if (buffered >= outBuf.size || System.currentTimeMillis() - lastFlush > 120) flush()
        }
    }

    /** 代理描述（信息展示用）。 */
    fun describe(): String = if (isRunning()) "127.0.0.1:$port" else "未启动"
}
