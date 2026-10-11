package com.opentv.player.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import com.opentv.player.R
import com.opentv.player.data.Http
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 轻量异步台标加载器：内存 LRU 缓存 + 固定线程池 + ImageView tag 防错位。
 * 不引入第三方依赖（Coil/Glide），保证离线可控构建。
 *
 * 底衬自适应：台标多数是"透明底 + 彩色/白色图形"，白色台标落在白色底衬上会完全看不见。
 * 因此解码后统计图形区域的平均亮度：
 *  - 亮色台标（白色字/白图形）→ 深色底衬
 *  - 暗色台标（深色字/深图形）→ 白色底衬
 * 底衬设在 ImageView 的父容器（各布局里都是 FrameLayout）上。
 */
object LogoLoader {

    private val cache = LruCache<String, Bitmap>(240)
    private val executor = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private const val MAX_PX = 160

    /** 图形区域平均亮度阈值（0.2126R+0.7152G+0.0722B），超过则认为是"亮色台标"。 */
    private const val BRIGHT_THRESHOLD = 160.0

    private val TILE_LIGHT = R.drawable.bg_logo
    private val TILE_DARK = R.drawable.bg_logo_dark

    fun load(view: ImageView, url: String?) {
        val tile = view.parent as? View
        view.setTag(R.id.logo_tag, url)

        if (url.isNullOrBlank()) {
            view.setImageResource(R.drawable.ic_tv_dim)
            tile?.setBackgroundResource(TILE_LIGHT)
            return
        }
        val hit = cache.get(url)
        if (hit != null) {
            applyTile(view, tile, hit)
            return
        }
        view.setImageResource(R.drawable.ic_tv_dim)
        tile?.setBackgroundResource(TILE_LIGHT)
        executor.execute {
            val bmp = try {
                fetch(url)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) cache.put(url, bmp)
            main.post {
                if (view.getTag(R.id.logo_tag) == url) applyTile(view, tile, bmp)
            }
        }
    }

    private fun applyTile(view: ImageView, tile: View?, bmp: Bitmap?) {
        if (bmp == null) {
            view.setImageResource(R.drawable.ic_tv_dim)
            tile?.setBackgroundResource(TILE_LIGHT)
            return
        }
        view.setImageBitmap(bmp)
        tile?.setBackgroundResource(if (isBright(bmp)) TILE_DARK else TILE_LIGHT)
    }

    /** 统计非透明像素的平均亮度，判断台标主体是否偏亮。 */
    private fun isBright(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return false
        val stepX = (w / 48).coerceAtLeast(1)
        val stepY = (h / 48).coerceAtLeast(1)
        var sum = 0.0
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bmp.getPixel(x, y)
                val a = (p ushr 24) and 0xFF
                if (a >= 40) {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
                    n++
                }
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return false
        return sum / n >= BRIGHT_THRESHOLD
    }

    private fun fetch(url: String): Bitmap? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", Http.DEFAULT_UA)
            setRequestProperty("Accept", "image/*,*/*")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opt = BitmapFactory.Options()
            var sample = 1
            while (bounds.outWidth / sample > MAX_PX || bounds.outHeight / sample > MAX_PX) sample *= 2
            opt.inSampleSize = sample
            // 统一转 ARGB_8888，保证 getPixel 能读到 alpha
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
        } finally {
            conn.disconnect()
        }
    }
}
