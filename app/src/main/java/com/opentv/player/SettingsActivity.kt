package com.opentv.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.opentv.player.data.EpgStore
import com.opentv.player.data.Library
import com.opentv.player.data.PlaybackPrefs
import com.opentv.player.data.PlaylistRepository
import com.opentv.player.ui.Ui
import com.opentv.player.ui.askPin
import com.opentv.player.ui.setupPin

/** 设置页：播放 / 界面 / 通用 / 家长控制 / 数据 / 关于。 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PlaybackPrefs
    private lateinit var repo: PlaylistRepository
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = PlaybackPrefs(this)
        repo = PlaylistRepository(this)
        content = findViewById(R.id.setContent)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        build()
        initTvFocus()
    }

    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        // 从系统授权页返回后刷新「后台启动权限」状态显示
        if (resumedOnce) {
            build()
            initTvFocus()
        }
        resumedOnce = true
    }

    /**
     * 是否具备后台启动 Activity 的能力。
     * Android 10 起系统禁止后台启动界面，只有「显示在其他应用上层」权限可豁免，
     * 这正是开机自启能否生效的关键。
     */
    private fun canStartFromBackground(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            android.provider.Settings.canDrawOverlays(this)

    /** 跳转到系统「显示在其他应用上层」授权页。 */
    private fun requestBootPermission() {
        val pkg = android.net.Uri.parse("package:$packageName")
        try {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg
                    )
                )
            } catch (e2: Exception) {
                Ui.toast(this, getString(R.string.boot_no_settings))
            }
        }
    }

    /** 电视端：焦点先落在返回键，遥控器可上下浏览设置项。 */
    private fun initTvFocus() {
        if (window.decorView.isInTouchMode) return
        content.post {
            val first = (0 until content.childCount)
                .map { content.getChildAt(it) }
                .firstOrNull { it.isFocusable }
            first?.requestFocus()
        }
    }

    private fun build() {
        content.removeAllViews()

        section(R.string.set_playback)
        valueRow(
            R.string.set_user_agent, shorten(prefs.userAgent), getString(R.string.set_user_agent_sum)
        ) { editUserAgent() }
        valueRow(
            R.string.set_decoder,
            PlaybackPrefs.DECODER_NAMES[prefs.decoderMode.coerceIn(0, 2)]
        ) { pickDecoder() }
        valueRow(
            R.string.set_buffer,
            getString(R.string.set_buffer_value_fmt, prefs.bufferMs / 1000)
        ) { pickBuffer() }
        switchRow(
            R.string.set_audio_passthrough, prefs.audioPassthrough,
            getString(R.string.set_audio_passthrough_sum)
        ) { prefs.audioPassthrough = it }
        valueRow(R.string.set_playback_speed, speedText(prefs.playbackSpeed), getString(R.string.set_playback_speed_sum)) { pickPlaybackSpeed() }
        switchRow(R.string.set_afr, prefs.afrEnabled, getString(R.string.set_afr_sum)) { prefs.afrEnabled = it }

        section(R.string.set_interface)
        switchRow(R.string.set_show_clock, prefs.showClock) { prefs.showClock = it; build() }
        switchRow(R.string.set_clock_24h, prefs.clock24h) { prefs.clock24h = it; build() }
        valueRow(R.string.set_clock_pos, resList(R.string.clock_pos_names)[prefs.clockPos]) { pickClockPos() }
        valueRow(R.string.set_clock_size, resList(R.string.clock_size_names)[if (prefs.clockLarge) 1 else 0]) { pickClockSize() }
        valueRow(R.string.set_channel_sort, resList(R.string.channel_sort_names)[prefs.channelSort]) { pickChannelSort() }
        switchRow(R.string.set_clean_names, prefs.cleanChannelNames, getString(R.string.set_clean_names_sum)) { prefs.cleanChannelNames = it }
        switchRow(R.string.set_group_sort, prefs.groupSortByCount) { prefs.groupSortByCount = it }
        valueRow(
            R.string.set_hidden_groups,
            getString(R.string.set_hidden_groups_fmt, prefs.hiddenGroups.size)
        ) { pickHiddenGroups() }

        section(R.string.set_search)
        switchRow(R.string.set_search_fav_first, prefs.searchFavFirst) { prefs.searchFavFirst = it }
        switchRow(R.string.set_search_in_desc, prefs.searchInDesc) { prefs.searchInDesc = it }

        section(R.string.set_general)
        valueRow(
            R.string.set_epg_sources,
            getString(R.string.set_epg_sources_fmt, com.opentv.player.data.EpgSources.all(this).size)
        ) { manageEpgSources() }
        valueRow(R.string.set_epg_offset, offsetLabel()) { pickEpgOffset() }
        valueRow(R.string.set_udpxy, prefs.udpxyUrl.ifBlank { "内置组播" }, getString(R.string.set_udpxy_sum)) { editUdpxy() }
        switchRow(
            R.string.set_auto_refresh, prefs.autoRefresh, getString(R.string.set_auto_refresh_sum)
        ) { prefs.autoRefresh = it; build() }
        valueRow(
            R.string.set_auto_refresh_interval,
            getString(R.string.set_hours_fmt, prefs.autoRefreshHours),
            lastRefreshSummary()
        ) { pickAutoRefreshInterval() }
        valueRow(R.string.set_refresh_now, "") { forceRefresh() }
        switchRow(R.string.set_boot_start, prefs.bootAutoStart, getString(R.string.set_boot_start_sum)) { on ->
            prefs.bootAutoStart = on
            // 未具备后台启动权限时，开机自启会被电视系统拦下，这里主动引导授权
            if (on && !canStartFromBackground()) {
                Ui.dialog(this)
                    .setTitle(R.string.set_boot_start)
                    .setMessage(R.string.boot_need_permission)
                    .setPositiveButton(R.string.boot_grant) { _, _ -> requestBootPermission() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        valueRow(
            R.string.set_boot_permission,
            getString(if (canStartFromBackground()) R.string.boot_granted else R.string.boot_not_granted),
            getString(R.string.set_boot_permission_sum)
        ) { requestBootPermission() }
        valueRow(
            R.string.set_zap_up,
            getString(if (prefs.zapUpIsNext) R.string.zap_up_next else R.string.zap_up_prev)
        ) { prefs.zapUpIsNext = !prefs.zapUpIsNext; build() }
        switchRow(R.string.set_loop_channels, prefs.loopChannels, getString(R.string.set_loop_channels_sum)) { prefs.loopChannels = it }
        switchRow(R.string.set_auto_pip, prefs.autoPip, getString(R.string.set_auto_pip_sum)) { prefs.autoPip = it }
        switchRow(R.string.set_double_back, prefs.doubleBackExit) { prefs.doubleBackExit = it }
        switchRow(R.string.set_resume, prefs.resumeLast) { prefs.resumeLast = it }
        valueRow(R.string.set_keys_help, "") {
            Ui.dialog(this)
                .setTitle(R.string.set_keys_help)
                .setMessage(R.string.keys_help_body)
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        section(R.string.set_parental)
        switchRow(R.string.set_parental_enabled, prefs.parentalEnabled) { on ->
            if (on && !prefs.hasPin()) {
                setupPin(prefs) { build() }
            } else {
                prefs.parentalEnabled = on
                build()
            }
        }
        valueRow(R.string.set_set_pin, if (prefs.hasPin()) "••••" else "—") {
            askPin(prefs, getString(R.string.pin_current_title)) {
                setupPin(prefs) { build() }
            }
        }
        switchRow(R.string.set_lock_settings, prefs.lockSettings) { prefs.lockSettings = it }
        switchRow(R.string.set_lock_adult, prefs.lockAdultGroups) { prefs.lockAdultGroups = it }
        switchRow(R.string.set_lock_playlists, prefs.lockPlaylists) { prefs.lockPlaylists = it }

        section(R.string.set_data)
        valueRow(R.string.set_backup, "") { doBackup() }
        valueRow(R.string.set_restore, "") { doRestore() }
        valueRow(R.string.set_clear_data, "") { confirmClear() }

        section(R.string.set_about)
        val about = TextView(this).apply {
            text = getString(R.string.about_text)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_dim))
            textSize = 13f
            setPadding(4, 6, 4, 6)
        }
        content.addView(about)
    }

    // ---------------- 行构建 ----------------

    private fun shorten(v: String): String = if (v.length <= 22) v else v.substring(0, 20) + "…"

    /** 上次刷新时间的可读文案。 */
    private fun lastRefreshSummary(): String {
        val at = prefs.lastRefreshAt
        if (at <= 0L) return getString(R.string.never_refreshed)
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return getString(R.string.last_refresh_fmt, fmt.format(java.util.Date(at)))
    }

    private fun section(titleRes: Int) {        val tv = TextView(this).apply {
            text = getString(titleRes)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_dim))
            textSize = 12f
            letterSpacing = 0.06f
            setPadding(6, 20, 6, 8)
        }
        content.addView(tv)
    }

    private fun rowView(titleRes: Int, summary: String?): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_setting, content, false)
        v.findViewById<TextView>(R.id.setTitle).text = getString(titleRes)
        val sum = v.findViewById<TextView>(R.id.setSummary)
        if (summary.isNullOrBlank()) sum.visibility = View.GONE else sum.text = summary
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = (4 * resources.displayMetrics.density).toInt()
        v.layoutParams = lp
        return v
    }

    private fun switchRow(
        titleRes: Int,
        checked: Boolean,
        summary: String? = null,
        onChange: (Boolean) -> Unit
    ) {
        val v = rowView(titleRes, summary)
        val sw = v.findViewById<SwitchCompat>(R.id.setSwitch)
        sw.visibility = View.VISIBLE
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        v.setOnClickListener { sw.isChecked = !sw.isChecked }
        content.addView(v)
    }

    private fun valueRow(titleRes: Int, value: String, summary: String? = null, onClick: () -> Unit) {
        val v = rowView(titleRes, summary)
        v.findViewById<TextView>(R.id.setValue).text = value
        v.setOnClickListener { onClick() }
        content.addView(v)
    }

    // ---------------- 交互 ----------------

    private fun editUserAgent() {
        val input = EditText(this).apply {
            setText(prefs.userAgent)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text))
            setSelection(text.length)
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding(40, 16, 40, 0)
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        Ui.dialog(this)
            .setTitle(R.string.set_user_agent)
            .setView(box)
            .setNeutralButton("VLC") { _, _ -> prefs.userAgent = "VLC/3.0.20 LibVLC/3.0.20"; build() }
            .setPositiveButton(R.string.ok) { _, _ ->
                prefs.userAgent = input.text.toString()
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickDecoder() {
        Ui.dialog(this)
            .setTitle(R.string.set_decoder)
            .setSingleChoiceItems(PlaybackPrefs.DECODER_NAMES, prefs.decoderMode) { d, which ->
                prefs.decoderMode = which
                d.dismiss()
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 速度显示文本：1.0 → "1.0x（正常）"。 */
    private fun speedText(v: Float): String =
        if (v == 1f) getString(R.string.speed_normal)
        else getString(R.string.speed_value_fmt, PlaybackPrefs.speedLabel(v))

    private fun pickPlaybackSpeed() {
        val opts = PlaybackPrefs.SPEED_OPTIONS
        val labels = opts.map {
            if (it == 1f) getString(R.string.speed_normal)
            else getString(R.string.speed_value_fmt, PlaybackPrefs.speedLabel(it))
        }.toTypedArray()
        val cur = opts.indexOfFirst { kotlin.math.abs(it - prefs.playbackSpeed) < 0.01f }.coerceAtLeast(0)
        Ui.dialog(this)
            .setTitle(R.string.set_playback_speed)
            .setSingleChoiceItems(labels, cur) { d, which ->
                prefs.playbackSpeed = opts[which]
                d.dismiss()
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickBuffer() {
        val options = intArrayOf(5, 10, 15, 30, 60)
        val labels = options.map { getString(R.string.set_buffer_value_fmt, it) }.toTypedArray()
        val current = options.indexOfFirst { it * 1000 >= prefs.bufferMs }.coerceAtLeast(0)
        Ui.dialog(this)
            .setTitle(R.string.set_buffer)
            .setSingleChoiceItems(labels, current) { d, which ->
                prefs.bufferMs = options[which] * 1000
                d.dismiss()
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickAutoRefreshInterval() {
        val options = intArrayOf(1, 3, 6, 12, 24)
        val labels = options.map { getString(R.string.set_hours_fmt, it) }.toTypedArray()
        val current = options.indexOf(prefs.autoRefreshHours).coerceAtLeast(0)
        Ui.dialog(this)
            .setTitle(R.string.set_auto_refresh_interval)
            .setSingleChoiceItems(labels, current) { d, which ->
                prefs.autoRefreshHours = options[which]
                d.dismiss()
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 标记为待刷新：清空上次刷新时间，返回主界面后立刻自动刷新。 */
    private fun forceRefresh() {
        prefs.lastRefreshAt = 0L
        Ui.toast(this, getString(R.string.refresh_pending))
        finish()
    }

    // ---------------- V1.4 新增交互 ----------------

    private fun resList(resId: Int): List<String> = getString(resId).split("|")

    private fun offsetLabel(): String {
        val m = prefs.epgOffsetMinutes
        return if (m == 0) "0（无偏移）" else getString(R.string.epg_offset_fmt, if (m > 0) "+$m" else "$m")
    }

    private fun pickClockPos() {
        val names = resList(R.string.clock_pos_names).toTypedArray()
        Ui.dialog(this)
            .setTitle(R.string.set_clock_pos)
            .setSingleChoiceItems(names, prefs.clockPos) { d, which ->
                prefs.clockPos = which; d.dismiss(); build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickClockSize() {
        val names = resList(R.string.clock_size_names).toTypedArray()
        val current = if (prefs.clockLarge) 1 else 0
        Ui.dialog(this)
            .setTitle(R.string.set_clock_size)
            .setSingleChoiceItems(names, current) { d, which ->
                prefs.clockLarge = which == 1; d.dismiss(); build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickChannelSort() {
        val names = resList(R.string.channel_sort_names).toTypedArray()
        Ui.dialog(this)
            .setTitle(R.string.set_channel_sort)
            .setSingleChoiceItems(names, prefs.channelSort) { d, which ->
                prefs.channelSort = which; d.dismiss(); build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickEpgOffset() {
        val options = intArrayOf(-120, -60, -30, 0, 30, 60, 120)
        val labels = options.map {
            if (it == 0) "0（无偏移）" else getString(R.string.epg_offset_fmt, if (it > 0) "+$it" else "$it")
        }.toTypedArray()
        val current = options.indexOf(prefs.epgOffsetMinutes).coerceAtLeast(0)
        Ui.dialog(this)
            .setTitle(R.string.set_epg_offset)
            .setSingleChoiceItems(labels, current) { d, which ->
                val changed = prefs.epgOffsetMinutes != options[which]
                prefs.epgOffsetMinutes = options[which]
                d.dismiss()
                build()
                if (changed) reloadEpg()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** EPG 源管理：点条目删除，底部按钮添加。 */
    private fun manageEpgSources() {
        val sources = com.opentv.player.data.EpgSources.all(this)
        if (sources.isEmpty()) {
            Ui.dialog(this)
                .setTitle(R.string.set_epg_sources)
                .setMessage(R.string.epg_no_source)
                .setPositiveButton(R.string.epg_add) { _, _ -> addEpgSource() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val labels = sources.map { "${it.name}\n${it.url}" }.toTypedArray()
        Ui.dialog(this)
            .setTitle(R.string.set_epg_sources)
            .setItems(labels) { _, which ->
                val s = sources[which]
                com.opentv.player.data.EpgSources.remove(this, s.id)
                reloadEpg()
                build()
            }
            .setPositiveButton(R.string.epg_add) { _, _ -> addEpgSource() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addEpgSource() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 16, 40, 0)
        }
        val inputName = EditText(this).apply {
            hint = getString(R.string.epg_source_name)
            isSingleLine = true
        }
        val inputUrl = EditText(this).apply {
            hint = getString(R.string.epg_source_url)
            isSingleLine = true
        }
        box.addView(inputName)
        box.addView(inputUrl)

        Ui.dialog(this)
            .setTitle(R.string.epg_add)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val url = inputUrl.text.toString().trim()
                if (url.isNotBlank()) {
                    com.opentv.player.data.EpgSources.add(this, inputName.text.toString().trim(), url)
                    reloadEpg()
                }
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editUdpxy() {
        val input = EditText(this).apply {
            setText(prefs.udpxyUrl)
            hint = getString(R.string.set_udpxy_hint)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text))
            setSelection(text.length)
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding(40, 16, 40, 0)
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        Ui.dialog(this)
            .setTitle(R.string.set_udpxy)
            .setMessage(getString(R.string.set_udpxy_sum))
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                prefs.udpxyUrl = input.text.toString()
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** EPG 配置变化后清空缓存并立即重新加载。 */
    private fun reloadEpg() {
        com.opentv.player.data.EpgStore.clear()
        com.opentv.player.data.EpgStore.load(applicationContext, force = true)
    }

    private fun pickHiddenGroups() {
        val all = Library.channels(this).mapNotNull { it.group }.distinct().sorted()
        if (all.isEmpty()) {
            Ui.toast(this, getString(R.string.no_channels))
            return
        }
        val hidden = prefs.hiddenGroups
        val checked = BooleanArray(all.size) { hidden.contains(all[it]) }
        Ui.dialog(this)
            .setTitle(R.string.hidden_groups_title)
            .setMultiChoiceItems(all.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.ok) { _, _ ->
                prefs.hiddenGroups = all.filterIndexed { i, _ -> checked[i] }.toSet()
                EpgStore.clear()
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doBackup() {
        val f = repo.backup()
        if (f == null) {
            Ui.toast(this, getString(R.string.backup_failed))
        } else {
            Ui.dialog(this)
                .setTitle(R.string.set_backup)
                .setMessage(getString(R.string.backup_done_fmt, f.absolutePath))
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun doRestore() {
        val list = repo.listBackups()
        if (list.isEmpty()) {
            Ui.toast(this, getString(R.string.no_backup))
            return
        }
        val labels = list.map { it.name }.toTypedArray()
        Ui.dialog(this)
            .setTitle(R.string.set_restore)
            .setItems(labels) { _, which ->
                val f = list[which]
                Ui.dialog(this)
                    .setMessage(getString(R.string.restore_confirm_fmt, f.name))
                    .setPositiveButton(R.string.ok) { _, _ ->
                        val ok = repo.restore(f)
                        Ui.toast(this, getString(if (ok) R.string.restore_done else R.string.restore_failed))
                        build()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        Ui.dialog(this)
            .setMessage(R.string.clear_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                repo.savePlaylists(emptyList())
                getSharedPreferences("opentv", MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("opentv_prefs", MODE_PRIVATE).edit().clear().apply()
                EpgStore.clear()
                Ui.toast(this, getString(R.string.cleared))
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
