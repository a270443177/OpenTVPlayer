package com.opentv.player.data

import android.content.Context

/** 全局播放/界面偏好（对应 TiviMate 的设置项，精简版）。 */
class PlaybackPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("opentv_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DECODER_AUTO = 0
        const val DECODER_HW = 1
        const val DECODER_SW = 2

        const val ASPECT_FIT = 0
        const val ASPECT_FILL = 1
        const val ASPECT_ZOOM = 2
        const val ASPECT_STRETCH = 3
        const val ASPECT_FIXED_WIDTH = 4

        val ASPECT_NAMES = arrayOf("适应画面", "拉伸填满", "裁切填充", "铺满屏幕", "固定宽度")
        val DECODER_NAMES = arrayOf("自动", "优先硬件解码", "优先软件解码")

        /** 播放速度档位 */
        val SPEED_OPTIONS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        /** 速度显示文本：1.0 → "1"，1.25 → "1.25" */
        fun speedLabel(v: Float): String =
            if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    }

    var userAgent: String
        get() = prefs.getString("user_agent", Http.DEFAULT_UA) ?: Http.DEFAULT_UA
        set(v) = prefs.edit().putString("user_agent", v.trim().ifBlank { Http.DEFAULT_UA }).apply()

    /** 解码器优先级：DECODER_AUTO / DECODER_HW / DECODER_SW */
    var decoderMode: Int
        get() = prefs.getInt("decoder_mode", DECODER_AUTO)
        set(v) = prefs.edit().putInt("decoder_mode", v).apply()

    /** 播放缓冲（毫秒） */
    var bufferMs: Int
        get() = prefs.getInt("buffer_ms", 15000)
        set(v) = prefs.edit().putInt("buffer_ms", v.coerceIn(2000, 120000)).apply()

    var showClock: Boolean
        get() = prefs.getBoolean("show_clock", true)
        set(v) = prefs.edit().putBoolean("show_clock", v).apply()

    var clock24h: Boolean
        get() = prefs.getBoolean("clock_24h", true)
        set(v) = prefs.edit().putBoolean("clock_24h", v).apply()

    /** 按 Home 键自动进入画中画（默认关闭：部分电视进入后难以用遥控恢复） */
    var autoPip: Boolean
        get() = prefs.getBoolean("auto_pip", false)
        set(v) = prefs.edit().putBoolean("auto_pip", v).apply()

    /** 遥控上键 = 下一个频道（false 表示上键回到上一个频道） */
    var zapUpIsNext: Boolean
        get() = prefs.getBoolean("zap_up_is_next", true)
        set(v) = prefs.edit().putBoolean("zap_up_is_next", v).apply()

    /** 双击返回键退出播放器 */
    var doubleBackExit: Boolean
        get() = prefs.getBoolean("double_back", false)
        set(v) = prefs.edit().putBoolean("double_back", v).apply()

    /** 启动时恢复上次频道 */
    var resumeLast: Boolean
        get() = prefs.getBoolean("resume_last", true)
        set(v) = prefs.edit().putBoolean("resume_last", v).apply()

    // ---------------- 自动刷新 ----------------

    /** 播放列表与 EPG 过期后自动重新拉取 */
    var autoRefresh: Boolean
        get() = prefs.getBoolean("auto_refresh", true)
        set(v) = prefs.edit().putBoolean("auto_refresh", v).apply()

    /** 自动刷新间隔（小时） */
    var autoRefreshHours: Int
        get() = prefs.getInt("auto_refresh_hours", 6)
        set(v) = prefs.edit().putInt("auto_refresh_hours", v.coerceIn(1, 72)).apply()

    /** 上次成功刷新（播放列表 + EPG）的时间戳 */
    var lastRefreshAt: Long
        get() = prefs.getLong("last_refresh_at", 0L)
        set(v) = prefs.edit().putLong("last_refresh_at", v).apply()

    /** 数据是否已过期（需要自动刷新） */
    fun isRefreshStale(now: Long = System.currentTimeMillis()): Boolean =
        lastRefreshAt <= 0L || now - lastRefreshAt >= autoRefreshHours * 3_600_000L

    var lastChannelUrl: String?
        get() = prefs.getString("last_channel_url", null)
        set(v) = prefs.edit().putString("last_channel_url", v).apply()

    var lastChannelName: String?
        get() = prefs.getString("last_channel_name", null)
        set(v) = prefs.edit().putString("last_channel_name", v).apply()

    var lastChannelGroup: String?
        get() = prefs.getString("last_channel_group", null)
        set(v) = prefs.edit().putString("last_channel_group", v).apply()

    // ---------------- 家长控制 ----------------

    var parentalEnabled: Boolean
        get() = prefs.getBoolean("parental_enabled", false)
        set(v) = prefs.edit().putBoolean("parental_enabled", v).apply()

    var parentalPin: String
        get() = prefs.getString("parental_pin", "") ?: ""
        set(v) = prefs.edit().putString("parental_pin", v).apply()

    var lockSettings: Boolean
        get() = prefs.getBoolean("lock_settings", false)
        set(v) = prefs.edit().putBoolean("lock_settings", v).apply()

    /** 锁定成人分组（名称含 18+/成人/adult 的分组需要 PIN） */
    var lockAdultGroups: Boolean
        get() = prefs.getBoolean("lock_adult", true)
        set(v) = prefs.edit().putBoolean("lock_adult", v).apply()

    fun verifyPin(pin: String): Boolean = !parentalEnabled || pin == parentalPin

    fun hasPin(): Boolean = parentalPin.length >= 4

    // ---------------- 播放器 ----------------

    var aspectMode: Int
        get() = prefs.getInt("aspect_mode", ASPECT_FIT)
        set(v) = prefs.edit().putInt("aspect_mode", v).apply()

    /** 上次使用过的音轨/字幕语言偏好（简化保存语言码） */
    var preferredAudioLanguage: String?
        get() = prefs.getString("pref_audio_lang", null)
        set(v) = prefs.edit().putString("pref_audio_lang", v).apply()

    // ---------------- 分组 ----------------

    var hiddenGroups: Set<String>
        get() = prefs.getStringSet("hidden_groups", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("hidden_groups", v).apply()

    var groupSortByCount: Boolean
        get() = prefs.getBoolean("group_sort_count", false)
        set(v) = prefs.edit().putBoolean("group_sort_count", v).apply()

    // ---------------- V1.4 新增 ----------------

    /** 开机自启 */
    var bootAutoStart: Boolean
        get() = prefs.getBoolean("boot_auto_start", false)
        set(v) = prefs.edit().putBoolean("boot_auto_start", v).apply()

    /** EPG 时间偏移（分钟，正值 = 节目整体后移） */
    var epgOffsetMinutes: Int
        get() = prefs.getInt("epg_offset_minutes", 0)
        set(v) = prefs.edit().putInt("epg_offset_minutes", v.coerceIn(-720, 720)).apply()

    /** UDPXY 代理地址（空 = 使用内置组播接收） */
    var udpxyUrl: String
        get() = prefs.getString("udpxy_url", "") ?: ""
        set(v) = prefs.edit().putString("udpxy_url", v.trim()).apply()

    /** 频道排序：0 列表顺序 / 1 按频道号 / 2 按名称 */
    var channelSort: Int
        get() = prefs.getInt("channel_sort", 0)
        set(v) = prefs.edit().putInt("channel_sort", v.coerceIn(0, 2)).apply()

    /** 搜索：收藏频道优先 */
    var searchFavFirst: Boolean
        get() = prefs.getBoolean("search_fav_first", false)
        set(v) = prefs.edit().putBoolean("search_fav_first", v).apply()

    /** 搜索：包含节目描述 */
    var searchInDesc: Boolean
        get() = prefs.getBoolean("search_in_desc", true)
        set(v) = prefs.edit().putBoolean("search_in_desc", v).apply()

    /** 家长控制：锁定播放列表管理 */
    var lockPlaylists: Boolean
        get() = prefs.getBoolean("lock_playlists", false)
        set(v) = prefs.edit().putBoolean("lock_playlists", v).apply()

    /** 播放器时钟位置：0 右上 / 1 左上 / 2 右下 / 3 左下 */
    var clockPos: Int
        get() = prefs.getInt("clock_pos", 0)
        set(v) = prefs.edit().putInt("clock_pos", v.coerceIn(0, 3)).apply()

    /** 播放器时钟大小：0 正常 / 1 大 */
    var clockLarge: Boolean
        get() = prefs.getBoolean("clock_large", false)
        set(v) = prefs.edit().putBoolean("clock_large", v).apply()

    // ---------------- V1.5 新增（P0 播放体验） ----------------

    /** 音频直通：优先把压缩音频（AC3/EAC3 等）交给电视/功放解码播放 */
    var audioPassthrough: Boolean
        get() = prefs.getBoolean("audio_passthrough", false)
        set(v) = prefs.edit().putBoolean("audio_passthrough", v).apply()

    /** 播放速度倍率（0.5 ~ 2.0） */
    var playbackSpeed: Float
        get() = prefs.getFloat("playback_speed", 1f)
        set(v) = prefs.edit().putFloat("playback_speed", v.coerceIn(0.25f, 4f)).apply()

    /** AFR 自动帧率：按视频帧率切换屏幕刷新率（仅对物理 HDMI 显示有效） */
    var afrEnabled: Boolean
        get() = prefs.getBoolean("afr_enabled", false)
        set(v) = prefs.edit().putBoolean("afr_enabled", v).apply()

    /** 导入时自动清理频道名（去掉画质 / 序号等前后缀） */
    var cleanChannelNames: Boolean
        get() = prefs.getBoolean("clean_channel_names", true)
        set(v) = prefs.edit().putBoolean("clean_channel_names", v).apply()

    /** 频道循环：最后一个频道再往下回到第一个，第一个往上回到最后一个 */
    var loopChannels: Boolean
        get() = prefs.getBoolean("loop_channels", true)
        set(v) = prefs.edit().putBoolean("loop_channels", v).apply()
}
