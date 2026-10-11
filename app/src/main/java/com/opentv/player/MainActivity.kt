package com.opentv.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.opentv.player.data.Channel
import com.opentv.player.data.EpgParser
import com.opentv.player.data.EpgProgram
import com.opentv.player.data.EpgStore
import com.opentv.player.data.Http
import com.opentv.player.data.Library
import com.opentv.player.data.M3uParser
import com.opentv.player.data.PlaybackPrefs
import com.opentv.player.data.Playlist
import com.opentv.player.data.PlaylistRepository
import com.opentv.player.data.PlaylistType
import com.opentv.player.data.XtreamClient
import com.opentv.player.net.StreamResolver
import com.opentv.player.ui.ChannelAdapter
import com.opentv.player.ui.GroupAdapter
import com.opentv.player.ui.Ui
import com.opentv.player.ui.askPin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var repo: PlaylistRepository
    private lateinit var prefs: PlaybackPrefs

    private lateinit var railPanel: View
    private lateinit var groupRail: RecyclerView
    private lateinit var groupChips: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var headerTitle: TextView
    private lateinit var headerSub: TextView
    private lateinit var statusLeft: TextView
    private lateinit var statusRight: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ticker = Handler(Looper.getMainLooper())

    private var playlists = mutableListOf<Playlist>()
    private var channels: List<Channel> = emptyList()
    private var favorites: Set<String> = emptySet()
    private var groups: List<Library.GroupInfo> = emptyList()
    private var currentGroup: String = Library.GROUP_ALL
    private var railAdapter: GroupAdapter? = null
    private var chipAdapter: GroupAdapter? = null
    private var channelAdapter: ChannelAdapter? = null
    private var refreshing = false

    /** 本地 m3u 文件选择（SAF）。 */
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importLocalFile(uri)
    }

    private val refreshTick = object : Runnable {
        override fun run() {
            channelAdapter?.notifyDataSetChanged()
            // 兜底：前台运行时若长时间未刷新，也检查一次（防止定时器被系统打断）
            maybeAutoRefresh()
            ticker.postDelayed(this, 5 * 60_000)
        }
    }

    /** 定时刷新：每隔设定的小时数自动重新拉取播放列表与 EPG。 */
    private val periodicRefresh = object : Runnable {
        override fun run() {
            if (prefs.autoRefresh && playlists.isNotEmpty()) refreshPlaylists(silent = true, quiet = true)
            ticker.postDelayed(this, prefs.autoRefreshHours * 3_600_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = PlaylistRepository(this)
        prefs = PlaybackPrefs(this)

        railPanel = findViewById(R.id.railPanel)
        groupRail = findViewById(R.id.groupRail)
        groupChips = findViewById(R.id.groupChips)
        channelList = findViewById(R.id.channelList)
        emptyView = findViewById(R.id.emptyView)
        progress = findViewById(R.id.progress)
        headerTitle = findViewById(R.id.headerTitle)
        headerSub = findViewById(R.id.headerSub)
        statusLeft = findViewById(R.id.statusLeft)
        statusRight = findViewById(R.id.statusRight)

        val narrow = resources.configuration.screenWidthDp < 620
        groupRail.layoutManager = LinearLayoutManager(this)
        groupChips.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        channelList.layoutManager = LinearLayoutManager(this)
        if (narrow) {
            railPanel.visibility = View.GONE
            groupChips.visibility = View.VISIBLE
        } else {
            groupChips.visibility = View.GONE
        }

        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnGuide).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnPlaylists).setOnClickListener { showPlaylistManager() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            if (prefs.parentalEnabled && prefs.lockSettings) {
                askPin(prefs, getString(R.string.pin_title)) { openSettings() }
            } else {
                openSettings()
            }
        }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { refreshPlaylists() }

        reloadAll()
        if (playlists.isNotEmpty() && prefs.autoRefresh) {
            // 启动后立即刷新播放列表(M3U)与 EPG，保证频道/节目单是最新的
            refreshPlaylists(silent = true)
        } else {
            EpgStore.load(this) { count -> onEpgLoaded(count) }
        }
        initTvFocus()

        if (savedInstanceState == null) maybeResumeLast()
    }

    /** 安排下一次定时刷新。 */
    private fun schedulePeriodicRefresh() {
        ticker.removeCallbacks(periodicRefresh)
        if (prefs.autoRefresh) {
            ticker.postDelayed(periodicRefresh, prefs.autoRefreshHours * 3_600_000L)
        }
    }

    /** 电视端：进入页面时把焦点交给频道列表，遥控器可直接上下选台。 */
    private fun initTvFocus() {
        if (window.decorView.isInTouchMode) return
        channelList.post {
            if ((channelAdapter?.itemCount ?: 0) > 0) {
                channelList.scrollToPosition(0)
                channelList.requestFocus()
            }
        }
    }

    /**
     * 兜底自动刷新：距上次成功刷新超过设定间隔时静默重新拉取。
     * 主要刷新由 [periodicRefresh] 定时触发，这里防止定时器被系统回收。
     */
    private fun maybeAutoRefresh() {
        if (!prefs.autoRefresh || refreshing) return
        if (playlists.isEmpty()) return
        if (!prefs.isRefreshStale()) return
        refreshPlaylists(silent = true, quiet = true)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    override fun onResume() {
        super.onResume()
        // 播放器/设置页可能改动过收藏或分组显示
        favorites = repo.favorites()
        rebuildGroups()
        refreshChannels()
    }

    override fun onStart() {
        super.onStart()
        ticker.postDelayed(refreshTick, 30_000)
        schedulePeriodicRefresh()
    }

    override fun onStop() {
        super.onStop()
        ticker.removeCallbacks(refreshTick)
        ticker.removeCallbacks(periodicRefresh)
    }

    override fun onDestroy() {
        super.onDestroy()
        ticker.removeCallbacks(periodicRefresh)
        scope.cancel()
    }

    // ---------------- 数据加载 ----------------

    private fun reloadAll() {
        playlists = repo.loadPlaylists()
        channels = Library.withNumbers(Library.channels(this))
        favorites = repo.favorites()
        rebuildGroups()
        refreshChannels()
        updateStatus()
        if (playlists.isEmpty()) showAddDialog()
    }

    private fun rebuildGroups() {
        groups = Library.groups(this, channels, favorites)
        if (groups.none { it.name == currentGroup }) currentGroup = Library.GROUP_ALL
        val idx = groups.indexOfFirst { it.name == currentGroup }.coerceAtLeast(0)

        val rail = GroupAdapter(groups.toMutableList(), compact = false) { i, g -> selectGroup(i, g) }
        rail.selected = idx
        railAdapter = rail
        groupRail.adapter = rail

        val chip = GroupAdapter(groups.toMutableList(), compact = true) { i, g -> selectGroup(i, g) }
        chip.selected = idx
        chipAdapter = chip
        groupChips.adapter = chip
        if (groupChips.visibility == View.VISIBLE) {
            groupChips.scrollToPosition(idx)
        }
    }

    private fun selectGroup(index: Int, group: Library.GroupInfo) {
        fun apply() {
            currentGroup = group.name
            railAdapter?.selected = index
            chipAdapter?.selected = index
            if (groupChips.visibility == View.VISIBLE) groupChips.scrollToPosition(index)
            refreshChannels()
        }
        if (prefs.parentalEnabled && prefs.lockAdultGroups && Library.isAdultGroup(group.name)) {
            askPin(prefs, getString(R.string.pin_title)) { apply() }
        } else {
            apply()
        }
    }

    private fun refreshChannels() {
        val list = Library.sorted(
            Library.channelsOf(currentGroup, channels, favorites),
            prefs.channelSort
        ).toMutableList()
        val now = System.currentTimeMillis()
        channelAdapter = ChannelAdapter(
            items = list,
            programOf = { c -> EpgParser.current(EpgStore.programsFor(c.tvgId), now) },
            isFavorite = { c -> favorites.contains(c.url) },
            clock24h = prefs.clock24h,
            onClick = { c -> play(c) },
            onLongClick = { c -> showChannelOptions(c) }
        )
        channelList.adapter = channelAdapter

        headerTitle.text = currentGroup
        headerSub.text = getString(R.string.channel_count_fmt, list.size)
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = getString(R.string.no_channels)
    }

    private fun updateStatus() {
        statusLeft.text = getString(R.string.group_summary_fmt, groups.size, channels.size)
        statusRight.text = if (EpgStore.hasData()) {
            getString(R.string.epg_loaded_fmt, countEpgChannels())
        } else {
            getString(R.string.loading_epg)
        }
    }

    private fun countEpgChannels(): Int {
        var n = 0
        for (c in channels) if (EpgStore.programsFor(c.tvgId) != null) n++
        return n
    }

    private fun onEpgLoaded(count: Int) {
        statusRight.text = getString(R.string.epg_loaded_fmt, count)
        channelAdapter?.notifyDataSetChanged()
    }

    // ---------------- 播放 / 频道选项 ----------------

    private fun play(c: Channel) {
        prefs.lastChannelUrl = c.url
        prefs.lastChannelName = c.name
        prefs.lastChannelGroup = currentGroup
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, c.url)
                putExtra(PlayerActivity.EXTRA_NAME, c.name)
                putExtra(PlayerActivity.EXTRA_GROUP, currentGroup)
                putExtra(PlayerActivity.EXTRA_PLAYLIST, c.playlist)
                putExtra(PlayerActivity.EXTRA_TVG_ID, c.tvgId)
            }
        )
    }

    private fun maybeResumeLast() {
        if (!prefs.resumeLast) return
        val url = prefs.lastChannelUrl ?: return
        // 本地录制文件可能已被删除：失效则清除记录，避免每次启动播放失败
        if (url.startsWith("file://")) {
            val f = File(Uri.parse(url).path!!)
            if (!f.exists()) {
                prefs.lastChannelUrl = null
                return
            }
        }
        if (playlists.isEmpty()) return
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, url)
                putExtra(PlayerActivity.EXTRA_NAME, prefs.lastChannelName)
                putExtra(PlayerActivity.EXTRA_GROUP, prefs.lastChannelGroup ?: Library.GROUP_ALL)
            }
        )
    }

    private fun showChannelOptions(c: Channel) {
        val fav = favorites.contains(c.url)
        val labels = arrayOf(
            getString(R.string.play_now),
            getString(if (fav) R.string.fav_remove else R.string.fav_add),
            getString(R.string.open_external),
            getString(R.string.ch_info),
            getString(R.string.copy_url)
        )
        Ui.dialog(this)
            .setTitle(c.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> play(c)
                    1 -> {
                        val added = repo.toggleFavorite(c.url)
                        favorites = repo.favorites()
                        Ui.toast(this, getString(if (added) R.string.favorite_added else R.string.favorite_removed))
                        rebuildGroups()
                        refreshChannels()
                    }
                    2 -> openExternal(c)
                    3 -> showChannelInfo(c)
                    4 -> Ui.copy(this, c.name, c.url)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 用外部播放器（VLC 等）打开当前频道。 */
    private fun openExternal(c: Channel) {
        val url = StreamResolver.resolve(this, prefs, c.url)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            Ui.toast(this, getString(R.string.external_none))
        }
    }

    private fun showChannelInfo(c: Channel) {
        val sb = StringBuilder()
        sb.append(getString(R.string.info_name)).append("：").append(c.name).append('\n')
        sb.append(getString(R.string.info_number)).append("：").append(c.number ?: "—").append('\n')
        sb.append(getString(R.string.info_group)).append("：").append(c.group ?: "—").append('\n')
        sb.append(getString(R.string.info_playlist)).append("：").append(c.playlist ?: "—").append('\n')
        sb.append(getString(R.string.info_catchup)).append("：")
            .append(getString(if (com.opentv.player.data.Catchup.canCatchup(c)) R.string.yes else R.string.no)).append('\n')
        sb.append(getString(R.string.info_url)).append("：\n").append(c.url)
        Ui.dialog(this)
            .setTitle(R.string.info_title)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.copy_url) { _, _ -> Ui.copy(this, c.name, c.url) }
            .show()
    }

    // ---------------- 播放列表管理 ----------------

    private fun showAddDialog() = showPlaylistDialog(existing = null, index = -1)

    /**
     * 添加 / 编辑播放列表共用一个对话框。
     * @param existing 非空即编辑模式，会预填原有字段（含 URL / 账号 / EPG）。
     * @param index    编辑时在原列表中的下标。
     */
    private fun showPlaylistDialog(existing: Playlist?, index: Int) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_playlist, null)
        val typeM3u = view.findViewById<RadioButton>(R.id.typeM3u)
        val typeXtream = view.findViewById<RadioButton>(R.id.typeXtream)
        val xtreamBox = view.findViewById<LinearLayout>(R.id.xtreamBox)
        val inputUrl = view.findViewById<EditText>(R.id.inputUrl)
        val inputName = view.findViewById<EditText>(R.id.inputName)
        val inputServer = view.findViewById<EditText>(R.id.inputServer)
        val inputUser = view.findViewById<EditText>(R.id.inputUser)
        val inputPass = view.findViewById<EditText>(R.id.inputPass)
        val inputEpg = view.findViewById<EditText>(R.id.inputEpg)

        fun syncType() {
            val m3u = typeM3u.isChecked
            inputUrl.visibility = if (m3u) View.VISIBLE else View.GONE
            xtreamBox.visibility = if (m3u) View.GONE else View.VISIBLE
        }
        typeM3u.setOnCheckedChangeListener { _, _ -> syncType() }
        typeXtream.setOnCheckedChangeListener { _, _ -> syncType() }

        // 编辑模式：预填现有内容
        if (existing != null) {
            inputName.setText(existing.name)
            if (existing.type == PlaylistType.XTREAM) {
                typeXtream.isChecked = true
                inputServer.setText(existing.server.orEmpty())
                inputUser.setText(existing.username.orEmpty())
                inputPass.setText(existing.password.orEmpty())
            } else {
                typeM3u.isChecked = true
                inputUrl.setText(existing.sourceUrl.orEmpty())
            }
            inputEpg.setText(existing.epgUrl.orEmpty())
        }
        syncType()

        val editing = existing != null
        Ui.dialog(this)
            .setTitle(if (editing) R.string.edit_playlist else R.string.add_playlist)
            .setView(view)
            .setNeutralButton(R.string.pick_local_file) { _, _ ->
                pickFile.launch(arrayOf("*/*"))
            }
            .setPositiveButton(if (editing) R.string.save else R.string.ok) { _, _ ->
                val type = if (typeM3u.isChecked) PlaylistType.M3U else PlaylistType.XTREAM
                val name = inputName.text.toString().trim()
                val epgUrl = inputEpg.text.toString().trim().takeIf { it.isNotBlank() }
                val id = existing?.id ?: UUID.randomUUID().toString()
                if (type == PlaylistType.M3U) {
                    val url = inputUrl.text.toString().trim()
                    if (url.isBlank()) {
                        Ui.toast(this, getString(R.string.err_empty_url))
                        return@setPositiveButton
                    }
                    commitPlaylist(
                        Playlist(id = id, name = name.ifBlank { "M3U" }, type = type, sourceUrl = url, epgUrl = epgUrl),
                        index, existing
                    )
                } else {
                    val server = inputServer.text.toString().trim()
                    val u = inputUser.text.toString().trim()
                    val p = inputPass.text.toString().trim()
                    if (server.isBlank() || u.isBlank()) {
                        Ui.toast(this, getString(R.string.err_empty_server))
                        return@setPositiveButton
                    }
                    commitPlaylist(
                        Playlist(
                            id = id, name = name.ifBlank { "Xtream" }, type = type, epgUrl = epgUrl,
                            server = server, username = u, password = p
                        ),
                        index, existing
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 拉取频道并落库；拉取失败时保留编辑前的频道，避免用户丢数据。 */
    private fun commitPlaylist(pl: Playlist, index: Int, existing: Playlist?) {
        showLoading(getString(R.string.loading_playlist))
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    loadChannels(pl)
                } catch (e: Exception) {
                    null
                }
            }
            hideLoading()
            val finalPl: Playlist
            if (loaded != null) {
                finalPl = pl.copy(channels = loaded)
            } else if (existing != null && existing.channels.isNotEmpty()) {
                finalPl = pl.copy(channels = existing.channels)
                Ui.toast(this@MainActivity, getString(R.string.playlist_saved_keep_fmt, finalPl.name))
            } else {
                Ui.toast(this@MainActivity, getString(R.string.failed_fmt, getString(R.string.err_network)))
                return@launch
            }
            if (index in playlists.indices) playlists[index] = finalPl else playlists.add(finalPl)
            repo.savePlaylists(playlists)
            reloadAll()
            EpgStore.clear()
            EpgStore.load(this@MainActivity) { onEpgLoaded(it) }
        }
    }

    private fun loadChannels(pl: Playlist): List<Channel> = when (pl.type) {
        PlaylistType.M3U -> {
            val src = pl.sourceUrl!!
            val text = if (src.startsWith("file://")) {
                File(Uri.parse(src).path!!).readText()
            } else {
                Http.get(src, prefs.userAgent)
            }
            M3uParser.parse(text, pl.name)
        }
        PlaylistType.XTREAM -> XtreamClient.load(
            pl.server!!, pl.username!!, pl.password!!, prefs.userAgent
        ).map { it.copy(playlist = pl.name) }
    }

    /**
     * 重新拉取全部播放列表与 EPG。
     * @param silent true = 后台刷新（无加载遮罩）；false = 手动刷新（带加载遮罩）
     * @param quiet  true = 不弹出结果提示（定时/兜底刷新时使用，避免打扰）
     */
    private fun refreshPlaylists(silent: Boolean = false, quiet: Boolean = false) {
        if (playlists.isEmpty()) {
            if (!silent) showAddDialog()
            return
        }
        if (refreshing) return
        refreshing = true
        if (silent) {
            if (!quiet) statusRight.text = getString(R.string.auto_refreshing)
        } else {
            showLoading(getString(R.string.loading_playlist))
        }
        scope.launch {
            var total = 0
            var ok = 0
            val updated = withContext(Dispatchers.IO) {
                val out = ArrayList<Playlist>(playlists.size)
                for (pl in playlists) {
                    try {
                        val ch = loadChannels(pl)
                        if (ch.isNotEmpty()) {
                            total += ch.size
                            ok++
                            out.add(pl.copy(channels = ch))
                        } else {
                            out.add(pl)
                        }
                    } catch (e: Exception) {
                        out.add(pl)
                    }
                }
                out
            }
            playlists = updated
            refreshing = false
            if (ok > 0) {
                repo.savePlaylists(playlists)
                prefs.lastRefreshAt = System.currentTimeMillis()
            }
            if (!silent) hideLoading()
            reloadAll()

            // 播放列表与 EPG 一起刷新
            if (EpgStore.loading) {
                if (!silent && !quiet) Ui.toast(this@MainActivity, getString(R.string.loaded_fmt, total))
            } else {
                EpgStore.load(this@MainActivity, force = true) { count ->
                    onEpgLoaded(count)
                    if (quiet) return@load
                    if (silent) {
                        if (ok > 0) Ui.toast(
                            this@MainActivity,
                            getString(R.string.auto_refreshed_fmt, total)
                        ) else Ui.toast(this@MainActivity, getString(R.string.auto_refresh_failed))
                    } else {
                        Ui.toast(this@MainActivity, getString(R.string.loaded_fmt, total))
                    }
                }
            }
        }
    }

    /** 导入本地 m3u 文件：拷贝进应用目录以便后续刷新重复读取。 */
    private fun importLocalFile(uri: Uri) {
        showLoading(getString(R.string.loading_playlist))
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val dir = File(filesDir, "imports").apply { mkdirs() }
                    var display = uri.lastPathSegment ?: "local.m3u"
                    display = display.substringAfterLast('/').ifBlank { "local.m3u" }
                    val target = File(dir, "${System.currentTimeMillis()}_$display")
                    contentResolver.openInputStream(uri)!!.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    val text = target.readText()
                    val name = display.substringBeforeLast('.').ifBlank { "本地列表" }
                    val pl = Playlist(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        type = PlaylistType.M3U,
                        sourceUrl = Uri.fromFile(target).toString()
                    )
                    pl to M3uParser.parse(text, name)
                } catch (e: Exception) {
                    null
                }
            }
            hideLoading()
            val (pl, chs) = result ?: run {
                Ui.toast(this@MainActivity, getString(R.string.import_failed))
                return@launch
            }
            if (chs.isEmpty()) {
                Ui.toast(this@MainActivity, getString(R.string.import_failed))
                return@launch
            }
            playlists.add(pl.copy(channels = chs))
            repo.savePlaylists(playlists)
            reloadAll()
            EpgStore.clear()
            EpgStore.load(this@MainActivity) { onEpgLoaded(it) }
            Ui.toast(this@MainActivity, getString(R.string.import_ok_fmt, pl.name, chs.size))
        }
    }

    private fun showPlaylistManager() {
        fun open() {
            if (playlists.isEmpty()) {
                showAddDialog()
                return
            }
            val labels: Array<CharSequence> = playlists
                .map { getString(R.string.multi_playlist_fmt, it.name, it.channels.size) }
                .toTypedArray()

            Ui.dialog(this)
                .setTitle(R.string.manage_title)
                .setItems(labels) { _, which ->
                    val pl = playlists[which]
                    Ui.dialog(this)
                        .setTitle(pl.name)
                        .setItems(
                            arrayOf(
                                getString(R.string.edit),
                                getString(R.string.refresh),
                                getString(R.string.delete)
                            )
                        ) { _, action ->
                            when (action) {
                                0 -> showPlaylistDialog(existing = pl, index = which)
                                1 -> refreshPlaylists()
                                2 -> confirmDelete(which, pl)
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                .setNeutralButton(R.string.add_playlist) { _, _ -> showAddDialog() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        if (prefs.parentalEnabled && prefs.lockPlaylists) {
            askPin(prefs, getString(R.string.pin_title)) { open() }
        } else {
            open()
        }
    }

    private fun confirmDelete(index: Int, pl: Playlist) {
        Ui.dialog(this)
            .setMessage(getString(R.string.delete_confirm_fmt, pl.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                playlists.removeAt(index)
                repo.savePlaylists(playlists)
                reloadAll()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 状态提示 ----------------

    private fun showLoading(msg: String) {
        progress.visibility = View.VISIBLE
        emptyView.visibility = View.VISIBLE
        emptyView.text = msg
    }

    private fun hideLoading() {
        progress.visibility = View.GONE
        refreshChannels()
    }
}
