package com.opentv.player.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** 播放列表 / 频道 / 分组的统一读取入口（各页面共用，避免重复代码）。 */
object Library {

    const val GROUP_ALL = "全部频道"
    const val GROUP_FAV = "★ 收藏"

    fun playlists(ctx: Context): MutableList<Playlist> = PlaylistRepository(ctx).loadPlaylists()

    fun channels(ctx: Context): List<Channel> {
        val cleanNames = PlaybackPrefs(ctx).cleanChannelNames
        val out = ArrayList<Channel>()
        for (pl in playlists(ctx)) {
            for (c in pl.channels) {
                var ch = if (c.playlist.isNullOrBlank()) c.copy(playlist = pl.name) else c
                // 频道名批量清理（可关闭）：去掉画质/序号等前后缀
                if (cleanNames) {
                    val cleaned = NameCleaner.clean(ch.name)
                    if (cleaned != ch.name) ch = ch.copy(name = cleaned)
                }
                out.add(ch)
            }
        }
        return out
    }

    data class GroupInfo(val name: String, val count: Int)

    /** 生成分组列表：全部 / 收藏 / 各分组（过滤隐藏分组，可选按数量排序）。 */
    fun groups(ctx: Context, channels: List<Channel>, favorites: Set<String>): List<GroupInfo> {
        val prefs = PlaybackPrefs(ctx)
        val hidden = prefs.hiddenGroups
        val counter = LinkedHashMap<String, Int>()
        for (c in channels) {
            val g = c.group ?: M3uParser.UNGROUPED
            counter[g] = (counter[g] ?: 0) + 1
        }
        val list = ArrayList<GroupInfo>()
        list.add(GroupInfo(GROUP_ALL, channels.size))
        val favCount = channels.count { favorites.contains(it.url) }
        list.add(GroupInfo(GROUP_FAV, favCount))
        val entries = counter.entries.filter { !hidden.contains(it.key) }
        val sorted = if (prefs.groupSortByCount) entries.sortedByDescending { it.value } else entries.sortedBy { it.key }
        for (e in sorted) list.add(GroupInfo(e.key, e.value))
        return list
    }

    fun channelsOf(group: String, channels: List<Channel>, favorites: Set<String>): List<Channel> = when (group) {
        GROUP_ALL -> channels
        GROUP_FAV -> channels.filter { favorites.contains(it.url) }
        else -> channels.filter { (it.group ?: M3uParser.UNGROUPED) == group }
    }

    /** 成人分组判断（家长控制用） */
    fun isAdultGroup(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase(Locale.getDefault())
        return n.contains("18+") || n.contains("成人") || n.contains("adult") || n.contains("xxx") || n.contains("adultos")
    }

    /** 生成一个频道号：优先使用列表内的 tvg-chno，否则按顺序编号 */
    fun withNumbers(channels: List<Channel>): List<Channel> =
        channels.mapIndexed { i, c -> if (c.number != null) c else c.copy(number = i + 1) }

    /** 频道排序：0 列表顺序 / 1 按频道号 / 2 按名称（中文按拼音） */
    fun sorted(channels: List<Channel>, mode: Int): List<Channel> = when (mode) {
        1 -> channels.sortedBy { it.number ?: Int.MAX_VALUE }
        2 -> {
            val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
            channels.sortedWith(compareBy(collator) { it.name })
        }
        else -> channels
    }
}

/**
 * EPG 内存缓存：所有页面共享同一份节目单，避免重复下载。
 */
object EpgStore {

    private val programs = HashMap<String, List<EpgProgram>>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var loading = false
        private set

    @Volatile
    var lastLoadedAt = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun hasData(): Boolean = programs.isNotEmpty()

    fun programsFor(tvgId: String?): List<EpgProgram>? {
        if (tvgId.isNullOrBlank()) return null
        return programs[tvgId]
    }

    /** 异步加载所有 EPG 源（播放列表自带 + 附加源）；onDone 在主线程回调（参数为频道映射数量）。 */
    fun load(ctx: Context, force: Boolean = false, onDone: (Int) -> Unit = {}) {
        if (loading) return
        if (!force && hasData()) {
            main.post { onDone(programs.size) }
            return
        }
        val urls = EpgSources.allUrls(ctx)
        val offsetMs = PlaybackPrefs(ctx).epgOffsetMinutes * 60_000L
        if (urls.isEmpty()) {
            main.post { onDone(0) }
            return
        }
        loading = true
        Thread {
            val merged = HashMap<String, MutableList<EpgProgram>>()
            var err: String? = null
            for (u in urls) {
                try {
                    val text = Http.getAutoText(u, PlaybackPrefs(ctx).userAgent, timeoutMs = 90000)
                    for ((k, v) in EpgParser.parse(text)) {
                        merged.getOrPut(k) { ArrayList() }.addAll(v)
                    }
                } catch (e: Exception) {
                    err = e.message ?: e.javaClass.simpleName
                }
            }
            programs.clear()
            for ((k, v) in merged) {
                val shifted = if (offsetMs != 0L) {
                    v.map { it.copy(start = it.start + offsetMs, end = it.end + offsetMs) }
                } else v
                programs[k] = ArrayList(shifted.sortedBy { it.start })
            }
            lastError = err
            lastLoadedAt = System.currentTimeMillis()
            loading = false
            main.post { onDone(programs.size) }
        }.start()
    }

    fun clear() {
        programs.clear()
        lastLoadedAt = 0L
    }
}
