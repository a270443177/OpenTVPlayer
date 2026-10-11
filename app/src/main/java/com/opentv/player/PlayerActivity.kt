package com.opentv.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.opentv.player.data.Catchup
import com.opentv.player.data.Channel
import com.opentv.player.data.EpgParser
import com.opentv.player.data.EpgProgram
import com.opentv.player.data.EpgStore
import com.opentv.player.data.Http
import com.opentv.player.data.Library
import com.opentv.player.data.PlaybackPrefs
import com.opentv.player.data.PlaylistRepository
import com.opentv.player.net.StreamResolver
import com.opentv.player.ui.ChannelAdapter
import com.opentv.player.ui.LogoLoader
import com.opentv.player.ui.Ui
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/** 全屏播放器：信息面板 / 切台 / 音轨字幕 / 比例 / 睡眠定时 / 画中画。 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var prefs: PlaybackPrefs
    private lateinit var repo: PlaylistRepository

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var statusBox: View
    private lateinit var statusView: TextView
    private lateinit var statusSpinner: ProgressBar
    private lateinit var zapBadge: View
    private lateinit var zapNumber: TextView
    private lateinit var zapName: TextView
    private lateinit var titleName: TextView
    private lateinit var titleGroup: TextView
    private lateinit var clockView: TextView
    private lateinit var infoLogo: ImageView
    private lateinit var nowTitle: TextView
    private lateinit var nowTime: TextView
    private lateinit var nextTitle: TextView
    private lateinit var nowProgress: ProgressBar
    private lateinit var zapPanel: View
    private lateinit var zapList: RecyclerView
    private lateinit var btnFav: ImageButton
    private lateinit var numberInput: View
    private lateinit var numberDigits: TextView
    private lateinit var numberHint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var released = false
    private var firstReady = false
    private var backPressedAt = 0L
    private var sleepEndAt = 0L
    private var sleeping = false

    private var channels: List<Channel> = emptyList()
    private var zapAdapter: ChannelAdapter? = null
    private var currentChannel: Channel? = null
    private var currentIndex = -1
    private var currentEpg: List<EpgProgram>? = null

    // 数字键直选频道：输入缓冲区 + 自动跳转定时器（多位数字累积后再跳转）
    private val numberBuffer = StringBuilder()
    private var numberJumpRunnable: Runnable? = null
    private var allNumberedChannels: List<Channel>? = null

    // 回看模式：播放 [catchupStart, catchupEnd] 区间的历史节目
    private var catchupMode = false
    private var catchupStart = 0L
    private var catchupEnd = 0L
    private var catchupTitle: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            updateInfoBar()
            checkSleep()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        prefs = PlaybackPrefs(this)
        repo = PlaylistRepository(this)

        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        statusBox = findViewById(R.id.statusBox)
        statusView = findViewById(R.id.statusView)
        statusSpinner = findViewById(R.id.statusSpinner)
        zapBadge = findViewById(R.id.zapBadge)
        zapNumber = findViewById(R.id.zapNumber)
        zapName = findViewById(R.id.zapName)
        titleName = findViewById(R.id.titleName)
        titleGroup = findViewById(R.id.titleGroup)
        clockView = findViewById(R.id.clockView)
        infoLogo = findViewById(R.id.infoLogo)
        nowTitle = findViewById(R.id.nowTitle)
        nowTime = findViewById(R.id.nowTime)
        nextTitle = findViewById(R.id.nextTitle)
        nowProgress = findViewById(R.id.nowProgress)
        zapPanel = findViewById(R.id.zapPanel)
        zapList = findViewById(R.id.zapList)
        btnFav = findViewById(R.id.btnFav)
        numberInput = findViewById(R.id.numberInput)
        numberDigits = findViewById(R.id.numberDigits)
        numberHint = findViewById(R.id.numberHint)

        playerView.resizeMode = resizeModeOf(prefs.aspectMode)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME)
        val group = intent.getStringExtra(EXTRA_GROUP) ?: Library.GROUP_ALL
        val playlist = intent.getStringExtra(EXTRA_PLAYLIST)
        val tvgId = intent.getStringExtra(EXTRA_TVG_ID)
        val startUri = intent.getStringExtra(EXTRA_START_URI)

        catchupStart = intent.getLongExtra(EXTRA_CATCHUP_START, 0L)
        catchupEnd = intent.getLongExtra(EXTRA_CATCHUP_END, 0L)
        catchupTitle = intent.getStringExtra(EXTRA_CATCHUP_TITLE)
        catchupMode = catchupEnd > catchupStart

        val epgId = tvgId ?: Library.channels(this).firstOrNull { it.url == url }?.tvgId
        currentEpg = EpgStore.programsFor(epgId)
        if (!EpgStore.hasData()) EpgStore.load(this) { currentEpg = EpgStore.programsFor(epgId); updateInfoBar() }

        loadGroupChannels(group.let { if (it == Library.GROUP_FAV) Library.GROUP_ALL else it })
        val target = channels.firstOrNull { it.url == url }
            ?: Channel(name ?: "频道", url, group = group, playlist = playlist, tvgId = epgId)
        currentIndex = channels.indexOfFirst { it.url == url }

        zapList.layoutManager = LinearLayoutManager(this)
        // 窄屏（手机竖屏）收窄切台面板，宽屏（电视/平板）加宽让台名完整显示
        val narrowScreen = resources.configuration.screenWidthDp < 620
        zapPanel.layoutParams = zapPanel.layoutParams.apply {
            width = ((if (narrowScreen) 268 else 372) * resources.displayMetrics.density).toInt()
        }
        setupPlayer()
        playChannel(target, startUri ?: url)

        wireButtons()
        playerView.isClickable = true
        playerView.setOnClickListener { toggleOverlay() }
        showOverlay(true)
        updateClock()
        handler.postDelayed(ticker, 1000)
    }

    // ---------------- 播放器构建 ----------------

    private fun setupPlayer() {
        val renderers = DefaultRenderersFactory(this).apply {
            setEnableDecoderFallback(true)
            when (prefs.decoderMode) {
                PlaybackPrefs.DECODER_SW -> setMediaCodecSelector(preferSoftwareSelector)
                PlaybackPrefs.DECODER_HW -> setMediaCodecSelector(preferHardwareSelector)
                else -> Unit
            }
        }
        val buffer = prefs.bufferMs
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(buffer, buffer * 2, 800, 2000)
            .build()

        val exo = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .build()
        player = exo
        playerView.player = exo
        // P0：音频直通 + 播放速度（按当前偏好初始化）
        applyAudioPreferences(exo)
        applySpeed(exo, prefs.playbackSpeed)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    // 回看是 60 秒一个窗口接力播放，每次换窗口都会 BUFFERING，
                    // 只在首次缓冲时提示，避免每分钟闪一次"正在连接"。
                    Player.STATE_BUFFERING -> if (!firstReady) showStatus(getString(R.string.loading_stream), true)
                    Player.STATE_READY -> {
                        firstReady = true
                        hideStatus()
                    }
                    Player.STATE_ENDED -> Unit
                }
                updateInfoBar()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                updateInfoBar()
                applyAfr()
            }

            override fun onPlayerError(error: PlaybackException) {
                showStatus(getString(R.string.player_error_fmt, error.errorCodeName), false)
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateInfoBar()
                applyAfr()
            }
        })
        exo.playWhenReady = true
        // 临时网络诊断：记录 ExoPlayer 实际请求的地址
        exo.addAnalyticsListener(NetLogListener)
    }

    private val NetLogListener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onLoadStarted(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData
        ) {
            android.util.Log.i("OpenTVNet", "LOAD ${loadEventInfo.uri}")
        }
    }

    private val preferSoftwareSelector = object : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<MediaCodecInfo> =
            MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                .sortedBy { if (it.hardwareAccelerated) 1 else 0 }
    }

    private val preferHardwareSelector = object : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<MediaCodecInfo> =
            MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                .sortedByDescending { if (it.hardwareAccelerated) 1 else 0 }
    }

    private fun loadGroupChannels(group: String) {
        val all = Library.sorted(Library.withNumbers(Library.channels(this)), prefs.channelSort)
        val favs = repo.favorites()
        channels = Library.channelsOf(group, all, favs)
        if (channels.isEmpty()) channels = all
        zapAdapter = ChannelAdapter(
            items = channels.toMutableList(),
            programOf = { c -> EpgParser.current(EpgStore.programsFor(c.tvgId), System.currentTimeMillis()) },
            isFavorite = { c -> repo.isFavorite(c.url) },
            clock24h = prefs.clock24h,
            onClick = { c -> switchTo(c) },
            onLongClick = { c -> toggleFavorite(c) },
            highlight = { c -> c.url == currentChannel?.url },
            compact = true
        )
        zapList.adapter = zapAdapter
    }

    // ---------------- 播放 ----------------

    private fun playChannel(c: Channel, url: String) {
        val p = player ?: return
        currentChannel = c
        // 录制回放等本地临时播放不作为"上次频道"，避免文件删除后续播失败
        if (intent.getBooleanExtra(EXTRA_NO_RESUME, false)) {
            // 不写回 lastChannel*
        } else {
            prefs.lastChannelUrl = c.url
            prefs.lastChannelName = c.name
            prefs.lastChannelGroup = c.group
        }
        currentEpg = EpgStore.programsFor(c.tvgId)

        titleName.text = c.name
        titleGroup.text = listOfNotNull(
            if (catchupMode) getString(R.string.catchup_badge) else null,
            c.playlist,
            c.group
        ).joinToString(" · ")
        LogoLoader.load(infoLogo, c.logo)
        updateFavIcon()
        updateInfoBar()
        firstReady = false
        showStatus(getString(if (catchupMode) R.string.catchup_loading else R.string.loading_stream), true)
        showZapBadge(c)

        // 回看模式：把整档节目按 60 秒窗口串成播放列表（ZTE 时移接口每次只给 1 个窗口）
        if (catchupMode) {
            val windows = Catchup.buildWindows(c, catchupStart, catchupEnd)
            if (windows.isNotEmpty()) {
                p.setMediaItems(
                    windows.map {
                        MediaItem.Builder().setUri(StreamResolver.resolve(this, prefs, it))
                            .setMimeType(MimeTypes.APPLICATION_M3U8).build()
                    }
                )
                p.prepare()
                p.playWhenReady = true
                return
            }
        }

        // 组播地址（udp:// / rtp://）先解析为可播放的 HTTP 地址
        val playUrl = StreamResolver.resolve(this, prefs, url)
        detectMimeType(playUrl) { mime ->
            if (released || player == null) return@detectMimeType
            runOnUiThread {
                val pl = player ?: return@runOnUiThread
                if (released) return@runOnUiThread
                pl.setMediaItem(MediaItem.Builder().setUri(playUrl).setMimeType(mime).build())
                pl.prepare()
                pl.playWhenReady = true
            }
        }
    }

    private fun switchTo(c: Channel) {
        val idx = channels.indexOfFirst { it.url == c.url }
        if (idx >= 0) currentIndex = idx
        catchupMode = false
        playChannel(c, c.url)
        zapAdapter?.notifyDataSetChanged()
        if (idx >= 0) zapList.scrollToPosition(idx)
    }

    private fun switchBy(offset: Int) {
        if (channels.isEmpty()) return
        if (currentIndex < 0) {
            switchTo(channels[0])
            return
        }
        var idx = currentIndex + offset
        if (idx !in channels.indices) {
            if (prefs.loopChannels) {
                // 循环：越过列表末尾回到开头，越过开头回到末尾
                val n = channels.size
                idx = ((idx % n) + n) % n
            } else {
                Ui.toast(
                    this,
                    getString(if (offset >= 0) R.string.no_next_channel else R.string.no_prev_channel)
                )
                return
            }
        }
        // 只有一个频道时循环会落回自身，直接忽略避免重播
        if (idx == currentIndex) return
        switchTo(channels[idx])
    }

    /** 遥控换台：换台过程中收起控制层，只保留频道角标提示。 */
    private fun zapByRemote(offset: Int) {
        hideOverlay()
        switchBy(offset)
    }

    // ---------------- 数字键直选频道 ----------------

    /** 遥控器数字键 → 0..9；非数字键返回 -1。 */
    private fun digitOf(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> 0
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
        else -> -1
    }

    /** 全部频道（已补频道号），按号直选在此范围内查找，不受当前分组筛选影响。 */
    private fun allChannels(): List<Channel> {
        allNumberedChannels?.let { return it }
        val list = Library.sorted(Library.withNumbers(Library.channels(this)), prefs.channelSort)
        allNumberedChannels = list
        return list
    }

    private fun onDigit(d: Int) {
        // 最多累积 4 位，超出则重新开始
        if (numberBuffer.length >= MAX_CHANNEL_DIGITS) numberBuffer.setLength(0)
        numberBuffer.append(d)
        showNumberInput()
        // 每输入一位都重新计时：连续快按不会被中断（1、2 → 12）
        restartNumberJump()
    }

    private fun restartNumberJump() {
        cancelNumberJump()
        val r = Runnable { commitNumberInput() }
        numberJumpRunnable = r
        handler.postDelayed(r, NUMBER_JUMP_DELAY)
    }

    private fun showNumberInput() {
        numberDigits.text = numberBuffer.toString()
        val n = numberBuffer.toString().toIntOrNull()
        val match = if (n != null) allChannels().firstOrNull { it.number == n } else null
        numberHint.text = match?.name ?: getString(R.string.number_input_hint)
        numberInput.visibility = View.VISIBLE
    }

    private fun deleteLastDigit() {
        if (numberBuffer.isEmpty()) return
        numberBuffer.deleteCharAt(numberBuffer.length - 1)
        if (numberBuffer.isEmpty()) hideNumberInput() else { showNumberInput(); restartNumberJump() }
    }

    private fun cancelNumberJump() {
        numberJumpRunnable?.let { handler.removeCallbacks(it) }
        numberJumpRunnable = null
    }

    private fun hideNumberInput() {
        numberInput.visibility = View.GONE
    }

    /** 清空输入，不跳转（返回键 / 退出时调用）。 */
    private fun cancelNumberInput() {
        cancelNumberJump()
        numberBuffer.setLength(0)
        hideNumberInput()
    }

    /** 用当前输入的数字跳转到对应频道号。 */
    private fun commitNumberInput() {
        cancelNumberJump()
        val n = numberBuffer.toString().toIntOrNull()
        numberBuffer.setLength(0)
        hideNumberInput()
        if (n == null || n <= 0) return
        jumpToNumber(n)
    }

    private fun jumpToNumber(n: Int) {
        val target = allChannels().firstOrNull { it.number == n }
        if (target == null) {
            Ui.toast(this, getString(R.string.channel_not_found_fmt, n))
            return
        }
        // 目标频道可能不在当前分组内：切到"全部"分组后再跳转
        if (channels.none { it.url == target.url }) loadGroupChannels(Library.GROUP_ALL)
        switchTo(target)
    }

    private fun detectMimeType(url: String, onResult: (String?) -> Unit) {
        val byExtension = mimeFromExtension(url)
        if (byExtension != null) {
            onResult(byExtension)
            return
        }
        thread(name = "mime-sniff") {
            var detected: String? = null
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", prefs.userAgent)
                    setRequestProperty("Accept", "*/*")
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val ct = (conn.contentType ?: "").lowercase(Locale.US)
                        val head = ByteArray(512)
                        var n = 0
                        conn.inputStream.use { ins ->
                            while (n < head.size) {
                                val r = ins.read(head, n, head.size - n)
                                if (r < 0) break
                                n += r
                            }
                        }
                        val text = String(head, 0, n, Charsets.UTF_8).trimStart()
                        detected = when {
                            text.startsWith("#EXTM3U") -> MimeTypes.APPLICATION_M3U8
                            text.startsWith("<MPD", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                            ct.contains("mpegurl") -> MimeTypes.APPLICATION_M3U8
                            ct.contains("dash+xml") -> MimeTypes.APPLICATION_MPD
                            else -> null
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                // 保持 null，回退 progressive
            }
            if (released) return@thread
            onResult(detected)
        }
    }

    private fun mimeFromExtension(url: String): String? {
        val path = Uri.parse(url).path?.lowercase(Locale.US) ?: return null
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    // ---------------- 界面 ----------------

    private fun wireButtons() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { switchBy(-1) }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { switchBy(1) }
        btnFav.setOnClickListener { currentChannel?.let { toggleFavorite(it) } }
        findViewById<ImageButton>(R.id.btnChannels).setOnClickListener { toggleZapPanel() }
        findViewById<ImageButton>(R.id.btnAudio).setOnClickListener { showTracksDialog(audio = true) }
        findViewById<ImageButton>(R.id.btnSubs).setOnClickListener { showTracksDialog(audio = false) }
        findViewById<ImageButton>(R.id.btnSpeed).setOnClickListener { pickSpeed() }
        findViewById<ImageButton>(R.id.btnAspect).setOnClickListener { pickAspect() }
        findViewById<ImageButton>(R.id.btnSleep).setOnClickListener { pickSleepTimer() }
        findViewById<ImageButton>(R.id.btnExternal).setOnClickListener { openExternal() }
        findViewById<ImageButton>(R.id.btnPip).setOnClickListener { enterPip() }
    }

    private fun toggleOverlay() {
        if (overlay.visibility == View.VISIBLE) {
            hideOverlay()
        } else {
            showOverlay(true)
        }
    }

    private var hideRunnable: Runnable? = null

    private fun showOverlay(autoHide: Boolean) {
        overlay.visibility = View.VISIBLE
        // 控制层顶栏会盖住右上角，时钟/录制角标暂时让位
        clockView.visibility = View.GONE
        cancelAutoHide()
        if (autoHide) scheduleAutoHide()
        if (!window.decorView.isInTouchMode && !overlay.hasFocus()) {
            overlay.post {
                if (overlay.visibility == View.VISIBLE && !overlay.hasFocus()) {
                    findViewById<ImageButton>(R.id.btnChannels).requestFocus()
                }
            }
        }
        updateInfoBar()
    }

    private fun cancelAutoHide() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
    }

    /** 控制层显示后 [OVERLAY_HIDE_DELAY] 毫秒自动收起；期间任何按键都会重新计时。 */
    private fun scheduleAutoHide() {
        cancelAutoHide()
        val r = Runnable {
            if (overlay.visibility == View.VISIBLE && !isInPictureInPictureMode) hideOverlay()
        }
        hideRunnable = r
        handler.postDelayed(r, OVERLAY_HIDE_DELAY)
    }

    private fun hideOverlay() {
        cancelAutoHide()
        overlay.clearFocus()
        overlay.visibility = View.GONE
        zapPanel.visibility = View.GONE
        updateClock()
    }

    private fun showStatus(text: String, spinner: Boolean) {
        statusView.text = text
        statusSpinner.visibility = if (spinner) View.VISIBLE else View.GONE
        statusBox.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusBox.visibility = View.GONE
    }

    private fun showZapBadge(c: Channel) {
        zapNumber.text = c.number?.toString() ?: ""
        zapName.text = c.name
        zapBadge.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(zapBadge)
        handler.postDelayed({ zapBadge.visibility = View.GONE }, 2500)
    }

    private fun updateClock() {
        val clock = clockView
        // 控制层顶栏占据右上角时让位，避免重叠
        if (prefs.showClock && overlay.visibility != View.VISIBLE) {
            clock.visibility = View.VISIBLE
            clock.text = EpgParser.clock(System.currentTimeMillis(), prefs.clock24h)
            // 位置：0 右上 / 1 左上 / 2 右下 / 3 左下；大小：正常 / 大
            val grav = when (prefs.clockPos) {
                1 -> Gravity.TOP or Gravity.START
                2 -> Gravity.BOTTOM or Gravity.END
                3 -> Gravity.BOTTOM or Gravity.START
                else -> Gravity.TOP or Gravity.END
            }
            val dp = (20 * resources.displayMetrics.density).toInt()
            val lp = clock.layoutParams
            if (lp is FrameLayout.LayoutParams) {
                if (lp.gravity != grav || lp.leftMargin != dp) {
                    lp.gravity = grav
                    lp.setMargins(dp, dp, dp, dp)
                    clock.layoutParams = lp
                }
            }
            clock.textSize = if (prefs.clockLarge) 22f else 15f
        } else {
            clock.visibility = View.GONE
        }
    }

    private fun updateInfoBar() {
        val now = System.currentTimeMillis()
        if (catchupMode) {
            nowTitle.text = catchupTitle ?: currentChannel?.name.orEmpty()
            nowTime.text = "%s - %s · %s".format(
                EpgParser.timeOfDay(catchupStart, prefs.clock24h),
                EpgParser.timeOfDay(catchupEnd, prefs.clock24h),
                getString(R.string.catchup_badge)
            )
            val dur = (catchupEnd - catchupStart).coerceAtLeast(1L)
            val pos = player?.currentPosition ?: 0L
            nowProgress.progress = ((pos * 100) / dur).toInt().coerceIn(0, 100)
            nextTitle.text = currentChannel?.name.orEmpty()
            return
        }
        currentEpg = EpgStore.programsFor(currentChannel?.tvgId)
        val cur = EpgParser.current(currentEpg, now)
        val next = EpgParser.next(currentEpg, now)
        if (cur == null) {
            nowTitle.text = currentChannel?.name.orEmpty()
            nowTime.text = ""
            nowProgress.progress = 0
        } else {
            nowTitle.text = cur.title
            nowTime.text = "%s - %s · %s".format(
                EpgParser.timeOfDay(cur.start, prefs.clock24h),
                EpgParser.timeOfDay(cur.end, prefs.clock24h),
                currentChannel?.name.orEmpty()
            )
            nowProgress.progress = (cur.progress(now) * 100).toInt()
        }
        nextTitle.text = next?.let { getString(R.string.up_next_fmt, it.title) } ?: ""
    }

    private fun updateFavIcon() {
        val fav = currentChannel?.let { repo.isFavorite(it.url) } == true
        btnFav.setImageResource(if (fav) R.drawable.ic_star_filled else R.drawable.ic_star)
        btnFav.setColorFilter(getColorCompat(if (fav) R.color.star else R.color.text_dim))
    }

    private fun getColorCompat(res: Int) = androidx.core.content.ContextCompat.getColor(this, res)

    private fun toggleFavorite(c: Channel) {
        val added = repo.toggleFavorite(c.url)
        Ui.toast(this, getString(if (added) R.string.favorite_added else R.string.favorite_removed))
        updateFavIcon()
        zapAdapter?.notifyDataSetChanged()
    }

    private fun toggleZapPanel() {
        if (zapPanel.visibility == View.VISIBLE) closeZapPanel() else openZapPanel()
    }

    private fun openZapPanel() {
        zapPanel.visibility = View.VISIBLE
        findViewById<TextView>(R.id.zapPanelTitle).text = getString(
            R.string.channel_count_fmt, channels.size
        )
        val pos = if (currentIndex in channels.indices) currentIndex else 0
        zapList.scrollToPosition(pos)
        showOverlay(false)
        // 电视端：焦点落在当前频道，遥控器上下即可选台、确认即切台
        if (!window.decorView.isInTouchMode) {
            zapList.post {
                zapList.scrollToPosition(pos)
                val vh = zapList.findViewHolderForAdapterPosition(pos)
                if (vh != null) vh.itemView.requestFocus() else zapList.requestFocus()
            }
        }
    }

    private fun closeZapPanel() {
        // 列表收起时整块控制层一并隐藏，回到纯净播放画面
        hideOverlay()
    }

    // ---------------- 比例 / 音轨 / 字幕 ----------------

    // ---------------- P0：播放管线增强（音频直通 / 速度 / AFR） ----------------

    /** 音频直通：开=允许硬件透传/卸载解码（压缩音频交给电视/功放），关=强制本机解码。 */
    private fun applyAudioPreferences(exo: ExoPlayer) {
        val mode = if (prefs.audioPassthrough) {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        } else {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        }
        val offload = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(mode)
            .setIsGaplessSupportRequired(false)
            .setIsSpeedChangeSupportRequired(false)
            .build()
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(offload)
            .build()
    }

    /** 播放速度：非 1.0 时在画面上给出提示。 */
    private fun applySpeed(exo: ExoPlayer?, speed: Float) {
        exo ?: return
        exo.playbackParameters = PlaybackParameters(speed)
    }

    /** 读取当前所选视频轨的帧率（拿不到返回 0）。 */
    private fun currentVideoFrameRate(): Float {
        val tracks = player?.currentTracks ?: return 0f
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val f = group.getTrackFormat(i).frameRate
                if (f > 0f) return f
            }
        }
        return 0f
    }

    /** AFR：按视频帧率把屏幕刷新率切到最接近的模式（API 23+，仅物理 HDMI 显示有效）。 */
    @Suppress("DEPRECATION")
    private fun applyAfr() {
        if (!prefs.afrEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val fps = currentVideoFrameRate()
        if (fps <= 0f) return
        try {
            // TV 场景始终使用窗口所在的主显示
            val d = windowManager.defaultDisplay
            val modes = d?.supportedModes ?: return
            if (modes.size <= 1) return
            val target = nearestStandardRate(fps.toDouble())
            val best = modes.minByOrNull { kotlin.math.abs(it.refreshRate - target) } ?: return
            // 偏差过大（>1.5Hz）说明屏幕没有合适档位，保持原样
            if (kotlin.math.abs(best.refreshRate - target) > 1.5) return
            val lp = window.attributes
            if (lp.preferredDisplayModeId != best.modeId) {
                lp.preferredDisplayModeId = best.modeId
                window.attributes = lp
                Log.i("OpenTV", "AFR: ${fps}fps -> mode ${best.modeId}@${best.refreshRate}Hz")
            }
        } catch (e: Exception) {
            Log.w("OpenTV", "AFR 切换失败", e)
        }
    }

    /** 把视频帧率归到标准刷新率档位（含 1000/1001 折算）。 */
    private fun nearestStandardRate(fps: Double): Double {
        val std = doubleArrayOf(23.976, 24.0, 25.0, 29.97, 30.0, 50.0, 59.94, 60.0)
        var best = std[0]
        var diff = Double.MAX_VALUE
        for (r in std) {
            val dd = kotlin.math.abs(r - fps)
            if (dd < diff) {
                diff = dd
                best = r
            }
        }
        return best
    }

    /** 退出播放时把屏幕模式还给系统（回复自动）。 */
    @Suppress("DEPRECATION")
    private fun restoreDisplayMode() {
        if (!prefs.afrEnabled) return
        try {
            val lp = window.attributes
            if (lp.preferredDisplayModeId != 0) {
                lp.preferredDisplayModeId = 0
                window.attributes = lp
            }
        } catch (e: Exception) {
            Log.w("OpenTV", "AFR 复位失败", e)
        }
    }

    /** 播放速度选择（遥控可选）。 */
    private fun pickSpeed() {
        val opts = PlaybackPrefs.SPEED_OPTIONS
        val labels = opts.map {
            if (it == 1f) getString(R.string.speed_normal) else getString(R.string.speed_value_fmt, PlaybackPrefs.speedLabel(it))
        }.toTypedArray()
        val cur = opts.indexOfFirst { kotlin.math.abs(it - prefs.playbackSpeed) < 0.01f }.coerceAtLeast(0)
        Ui.dialog(this)
            .setTitle(R.string.playback_speed)
            .setSingleChoiceItems(labels, cur) { d, which ->
                prefs.playbackSpeed = opts[which]
                applySpeed(player, opts[which])
                d.dismiss()
                if (opts[which] != 1f) Ui.toast(this, getString(R.string.speed_toast_fmt, PlaybackPrefs.speedLabel(opts[which])))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resizeModeOf(mode: Int): Int = when (mode) {
        PlaybackPrefs.ASPECT_FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        PlaybackPrefs.ASPECT_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        PlaybackPrefs.ASPECT_STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        PlaybackPrefs.ASPECT_FIXED_WIDTH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun pickAspect() {
        Ui.dialog(this)
            .setTitle(R.string.aspect_ratio)
            .setSingleChoiceItems(PlaybackPrefs.ASPECT_NAMES, prefs.aspectMode) { d, which ->
                prefs.aspectMode = which
                playerView.resizeMode = resizeModeOf(which)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTracksDialog(audio: Boolean) {
        val p = player ?: return
        val type = if (audio) androidx.media3.common.C.TRACK_TYPE_AUDIO else androidx.media3.common.C.TRACK_TYPE_TEXT
        val labels = ArrayList<String>()
        val groups = ArrayList<Pair<Tracks.Group, Int>>() // group + track index

        if (!audio) labels.add(getString(R.string.track_off))
        labels.add(getString(R.string.track_auto))
        for (group in p.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                val fmt = group.getTrackFormat(i)
                val name = fmt.label ?: fmt.language ?: fmt.sampleMimeType ?: "Track ${groups.size + 1}"
                labels.add(name.toString())
                groups.add(group to i)
            }
        }

        Ui.dialog(this)
            .setTitle(if (audio) R.string.audio_tracks else R.string.subtitles)
            .setItems(labels.toTypedArray()) { _, which ->
                val builder = p.trackSelectionParameters.buildUpon()
                if (!audio && which == 0) {
                    builder.setTrackTypeDisabled(type, true)
                } else {
                    builder.setTrackTypeDisabled(type, false)
                    builder.clearOverridesOfType(type)
                    val offset = if (audio) 1 else 2
                    val idx = which - offset
                    if (idx >= 0 && idx < groups.size) {
                        val (g, ti) = groups[idx]
                        builder.setOverrideForType(
                            androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, ti)
                        )
                    }
                }
                p.trackSelectionParameters = builder.build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 睡眠定时 ----------------

    private fun pickSleepTimer() {
        val options = arrayOf(0, 15, 30, 60, 90, 120)
        val labels = options.map {
            if (it == 0) getString(R.string.sleep_off) else getString(R.string.sleep_minutes_fmt, it)
        }.toTypedArray()
        Ui.dialog(this)
            .setTitle(R.string.sleep_timer)
            .setItems(labels) { _, which ->
                val minutes = options[which]
                if (minutes == 0) {
                    sleeping = false
                    sleepEndAt = 0
                    findViewById<ImageButton>(R.id.btnSleep).setColorFilter(getColorCompat(R.color.text))
                    Ui.toast(this, getString(R.string.sleep_off))
                } else {
                    sleeping = true
                    sleepEndAt = System.currentTimeMillis() + minutes * 60_000L
                    findViewById<ImageButton>(R.id.btnSleep).setColorFilter(getColorCompat(R.color.accent))
                    Ui.toast(this, getString(R.string.sleep_set_fmt, minutes))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun checkSleep() {
        if (sleeping && System.currentTimeMillis() >= sleepEndAt) {
            sleeping = false
            sleepEndAt = 0
            player?.pause()
            findViewById<ImageButton>(R.id.btnSleep).setColorFilter(getColorCompat(R.color.text))
            Ui.toast(this, getString(R.string.sleep_ended))
        }
    }

    /** 用外部播放器（VLC 等）打开当前频道。 */
    private fun openExternal() {
        val c = currentChannel ?: return
        val url = StreamResolver.resolve(this, prefs, c.url)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            Ui.toast(this, getString(R.string.external_none))
        }
    }

    // ---------------- 画中画 ----------------

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Ui.toast(this, getString(R.string.pip))
            return
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        enterPictureInPictureMode(params)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (prefs.autoPip && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (isInPip) {
            cancelAutoHide()
            cancelNumberInput()
            overlay.visibility = View.GONE
            statusBox.visibility = View.GONE
            zapBadge.visibility = View.GONE
        } else {
            // 从画中画回到全屏：不自动弹控制层，避免挡画面
            hideOverlay()
        }
    }

    /** 从画中画恢复全屏（遥控确认 / 返回键触发）。 */
    private fun expandFromPip() {
        val i = Intent(this, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            android.util.Log.e("OpenTV", "退出画中画失败", e)
        }
    }

    // ---------------- 按键 / 生命周期 ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 数字输入进行中：确定键只用于"立即跳转"，不能透传给底层视图
        // （PlayerView / 按钮会消费确定键触发点击，导致误开控制面板）
        if (!isInPictureInPictureMode && numberBuffer.isNotEmpty() &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) commitNumberInput()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 画中画：确认 / 返回都用来回到全屏
        if (isInPictureInPictureMode) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    expandFromPip(); true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // 数字键直选频道：任何时候输入都生效，支持连续多位（如 1 → 2 到 12 台）
        val digit = digitOf(keyCode)
        if (digit >= 0) {
            if (zapPanel.visibility == View.VISIBLE) closeZapPanel()
            hideOverlay()
            onDigit(digit)
            return true
        }
        // 输入进行中：删除键退位、确定键立即跳转、返回键撤销
        if (numberBuffer.isNotEmpty()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> { deleteLastDigit(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { commitNumberInput(); return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { cancelNumberInput(); return true }
            }
        }

        // 遥控器：CH+ / CH- / 翻页键任何时候都可切台
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                zapByRemote(1); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                zapByRemote(-1); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                togglePlayPause(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                if (zapPanel.visibility == View.VISIBLE) closeZapPanel() else showOverlay(true)
                return true
            }
        }

        // 切台列表展开时：左右键收起，上下/确定交给列表自己导航
        if (zapPanel.visibility == View.VISIBLE) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { closeZapPanel(); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                    if (zapList.hasFocus()) super.onKeyDown(keyCode, event)
                    else { switchToCurrentZapItem(); true }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // 叠加控制层展开时：方向键用于在按钮之间移动焦点
        if (overlay.visibility == View.VISIBLE) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // 操作期间重新计时：停止操作 5 秒后自动收起
                    scheduleAutoHide()
                    super.onKeyDown(keyCode, event)
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // 控制层隐藏（正在看直播）：上/下换台（电视习惯），左开频道列表，右/OK 呼出控制栏
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                zapByRemote(if (prefs.zapUpIsNext) 1 else -1); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                zapByRemote(if (prefs.zapUpIsNext) -1 else 1); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { openZapPanel(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                showOverlay(true); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun switchToCurrentZapItem() {
        val pos = if (currentIndex in channels.indices) currentIndex else 0
        channels.getOrNull(pos)?.let { switchTo(it) }
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        showOverlay(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 数字键输入中：先撤销输入，不退出
        if (numberBuffer.isNotEmpty()) {
            cancelNumberInput()
            return
        }
        if (zapPanel.visibility == View.VISIBLE) {
            closeZapPanel()
            return
        }
        if (overlay.visibility == View.VISIBLE && !prefs.doubleBackExit) {
            hideOverlay()
            return
        }
        if (prefs.doubleBackExit) {
            val now = System.currentTimeMillis()
            if (now - backPressedAt < 2000) {
                super.onBackPressed()
            } else {
                backPressedAt = now
                Ui.toast(this, getString(R.string.exit_hint))
            }
            return
        }
        super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) player?.pause()
        // 退出播放时把屏幕刷新率还给系统，避免影响桌面与其他应用
        restoreDisplayMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        released = true
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    companion object {
        /** 控制层无操作自动收起延时（毫秒） */
        private const val OVERLAY_HIDE_DELAY = 5000L

        /** 数字键输入后自动跳转的等待时间（毫秒）；每按一位都会重新计时 */
        private const val NUMBER_JUMP_DELAY = 1500L

        /** 频道号最多允许输入几位数字 */
        private const val MAX_CHANNEL_DIGITS = 4

        const val EXTRA_URL = "extra_url"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_GROUP = "extra_group"
        const val EXTRA_PLAYLIST = "extra_playlist"
        const val EXTRA_TVG_ID = "extra_tvg_id"
        const val EXTRA_START_URI = "extra_start_uri"
        const val EXTRA_CATCHUP_START = "extra_catchup_start"
        const val EXTRA_CATCHUP_END = "extra_catchup_end"
        const val EXTRA_CATCHUP_TITLE = "extra_catchup_title"
        const val EXTRA_NO_RESUME = "extra_no_resume"

        fun intent(
            ctx: Context,
            url: String,
            name: String?,
            group: String?,
            playlist: String?,
            tvgId: String?,
            startUri: String? = null,
            catchupStart: Long = 0L,
            catchupEnd: Long = 0L,
            catchupTitle: String? = null
        ): Intent = Intent(ctx, PlayerActivity::class.java).apply {
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_GROUP, group)
            putExtra(EXTRA_PLAYLIST, playlist)
            putExtra(EXTRA_TVG_ID, tvgId)
            if (startUri != null) putExtra(EXTRA_START_URI, startUri)
            if (catchupEnd > catchupStart) {
                putExtra(EXTRA_CATCHUP_START, catchupStart)
                putExtra(EXTRA_CATCHUP_END, catchupEnd)
                putExtra(EXTRA_CATCHUP_TITLE, catchupTitle)
            }
        }
    }
}
