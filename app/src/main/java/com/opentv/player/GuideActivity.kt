package com.opentv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.opentv.player.data.Catchup
import com.opentv.player.data.Channel
import com.opentv.player.data.EpgParser
import com.opentv.player.data.EpgProgram
import com.opentv.player.data.EpgStore
import com.opentv.player.data.Library
import com.opentv.player.data.PlaybackPrefs
import com.opentv.player.data.Reminders
import com.opentv.player.ui.GuideAdapter
import com.opentv.player.ui.SyncScrollView
import com.opentv.player.ui.Ui

/** 电视指南：频道 × 时间轴 的节目网格。 */
class GuideActivity : AppCompatActivity(), SyncScrollView.Group {

    private lateinit var prefs: PlaybackPrefs
    private lateinit var list: RecyclerView
    private lateinit var timeHeader: SyncScrollView
    private lateinit var timeHeaderBox: LinearLayout
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var dateLabel: TextView
    private lateinit var btnGroup: Button

    private val scrollers = mutableSetOf<SyncScrollView>()
    private val ticker = Handler(Looper.getMainLooper())

    private var allChannels: List<Channel> = emptyList()
    private var shown: MutableList<Channel> = mutableListOf()
    private var currentGroup: String = Library.GROUP_ALL
    private var adapter: GuideAdapter? = null

    private var windowStart = 0L
    private var windowEnd = 0L
    private var pxPerMin = 6f

    private val tick = object : Runnable {
        override fun run() {
            adapter?.notifyDataSetChanged()
            ticker.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        prefs = PlaybackPrefs(this)

        list = findViewById(R.id.guideList)
        timeHeader = findViewById(R.id.timeHeader)
        timeHeaderBox = findViewById(R.id.timeHeaderBox)
        empty = findViewById(R.id.guideEmpty)
        progress = findViewById(R.id.guideProgress)
        status = findViewById(R.id.guideStatus)
        dateLabel = findViewById(R.id.dateLabel)
        btnGroup = findViewById(R.id.btnGroupFilter)

        timeHeader.group = this
        list.layoutManager = LinearLayoutManager(this)
        pxPerMin = 6f * resources.displayMetrics.density

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnNow).setOnClickListener { scrollToNow() }
        btnGroup.setOnClickListener { pickGroup() }

        Reminders.prune(this)
        allChannels = Library.withNumbers(Library.channels(this))
        buildWindow()
        buildHeader()
        applyFilter()
        focusFirstRow()

        if (EpgStore.hasData()) {
            showData()
        } else {
            progress.visibility = View.VISIBLE
            status.text = getString(R.string.loading_epg)
            EpgStore.load(this) { showData() }
        }
    }

    override fun onStart() {
        super.onStart()
        ticker.postDelayed(tick, 60_000)
    }

    override fun onStop() {
        super.onStop()
        ticker.removeCallbacks(tick)
    }

    private fun showData() {
        progress.visibility = View.GONE
        val has = EpgStore.hasData()
        empty.visibility = if (!has) View.VISIBLE else View.GONE
        empty.text = getString(R.string.guide_no_epg)
        status.text = if (has) {
            getString(R.string.epg_loaded_fmt, EpgStore.lastLoadedAt.let { countEpg() })
        } else {
            getString(R.string.loading_epg)
        }
        applyFilter()
        scrollToNow()
    }

    /** 电视端：焦点落到第一行频道单元格，遥控器可上下浏览、左右浏览节目。 */
    private fun focusFirstRow() {
        if (window.decorView.isInTouchMode) return
        list.post {
            if ((adapter?.itemCount ?: 0) > 0) {
                list.scrollToPosition(0)
                val row = list.findViewHolderForAdapterPosition(0)?.itemView
                val cell = row?.findViewById<View>(R.id.channelCell)
                if (cell != null) cell.requestFocus() else list.requestFocus()
            }
        }
    }

    private fun countEpg(): Int = allChannels.count { EpgStore.programsFor(it.tvgId) != null }

    private fun buildWindow() {
        val now = System.currentTimeMillis()
        val half = 30 * 60_000L
        // 往前保留 3 小时（覆盖 ZTE 时移可回看范围），往后 6 小时
        windowStart = (now - 3 * 60 * 60_000L) / half * half
        windowEnd = windowStart + 9 * 60 * 60_000L
    }

    private fun buildHeader() {
        timeHeaderBox.removeAllViews()
        val now = System.currentTimeMillis()
        var t = windowStart
        while (t < windowEnd) {
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams((30 * pxPerMin).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                text = EpgParser.timeOfDay(t, prefs.clock24h)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10, 0, 0, 0)
                textSize = 11f
                setTextColor(
                    ContextCompat.getColor(
                        this@GuideActivity,
                        if (now in t until (t + 30 * 60_000L)) R.color.accent else R.color.text_dim
                    )
                )
            }
            timeHeaderBox.addView(tv)
            t += 30 * 60_000L
        }
        dateLabel.text = EpgParser.fullDate(now).substring(0, 10)
        updateNowMarker(timeHeader.scrollX)
    }

    /** 当前时间刻度线：按滚动偏移绝对定位（仅表头区域可见时显示）。 */
    private fun updateNowMarker(scrollX: Int) {
        val marker = findViewById<View>(R.id.nowMarker) ?: return
        val offset = ((System.currentTimeMillis() - windowStart) / 60_000f * pxPerMin).toInt() - scrollX
        if (offset < 0) {
            marker.visibility = View.INVISIBLE
        } else {
            marker.visibility = View.VISIBLE
            marker.translationX = offset.toFloat()
        }
    }

    private fun applyFilter() {
        shown = Library.channelsOf(currentGroup, allChannels, emptySet()).toMutableList()
        // 有 EPG 的频道排前面，便于查看
        shown.sortByDescending { EpgStore.programsFor(it.tvgId) != null }
        val a = GuideAdapter(
            items = shown,
            epgOf = { c -> EpgStore.programsFor(c.tvgId) },
            windowStart = windowStart,
            windowEnd = windowEnd,
            pxPerMin = pxPerMin,
            clock24h = prefs.clock24h,
            onChannelClick = { c -> play(c) },
            onProgramClick = { c, p -> onProgram(c, p) },
            onProgramLongClick = { c, p -> programMenu(c, p) },
            canCatchup = { c -> Catchup.canCatchup(c) },
            sync = this
        )
        adapter = a
        list.adapter = a
        btnGroup.text = currentGroup
    }

    private fun pickGroup() {
        val groups = Library.groups(this, allChannels, emptySet()).map { it.name }
        Ui.dialog(this)
            .setTitle(R.string.nav_groups)
            .setItems(groups.toTypedArray()) { _, which ->
                currentGroup = groups[which]
                applyFilter()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun scrollToNow() {
        val x = (((System.currentTimeMillis() - windowStart) / 60_000f * pxPerMin).toInt() -
            resources.displayMetrics.widthPixels / 4).coerceAtLeast(0)
        scrollers.toList().forEach { it.scrollToSynced(x) }
    }

    // ---------------- 节目交互 ----------------

    private fun play(c: Channel, program: EpgProgram? = null) {
        prefs.lastChannelUrl = c.url
        prefs.lastChannelName = c.name
        prefs.lastChannelGroup = c.group
        if (program != null && program.end <= System.currentTimeMillis() && Catchup.canCatchup(c)) {
            startActivity(
                PlayerActivity.intent(
                    this, c.url, c.name, currentGroup, c.playlist, c.tvgId,
                    catchupStart = program.start, catchupEnd = program.end, catchupTitle = program.title
                )
            )
            return
        }
        startActivity(PlayerActivity.intent(this, c.url, c.name, currentGroup, c.playlist, c.tvgId))
    }

    private fun onProgram(c: Channel, p: EpgProgram) {
        val now = System.currentTimeMillis()
        when {
            p.isLive(now) -> play(c, p)
            p.start > now -> toggleReminder(c, p)
            else -> {
                if (!Catchup.canCatchup(c)) {
                    Ui.toast(this, getString(R.string.catchup_unsupported))
                } else {
                    val look = Catchup.lookbackMs(c)
                    if (look != null && p.start < now - look) {
                        Ui.toast(this, getString(R.string.catchup_expired))
                    } else {
                        play(c, p)
                    }
                }
            }
        }
    }

    private fun programMenu(c: Channel, p: EpgProgram) {
        val now = System.currentTimeMillis()
        val key = Reminders.keyOf(c.tvgId, c.url, p.start)
        val hasReminder = Reminders.has(this, key)
        val options = ArrayList<String>()
        options.add(getString(R.string.play_now))
        if (Catchup.canCatchup(c) && p.end <= now) options.add(getString(R.string.catchup_play))
        if (p.start > now) options.add(getString(if (hasReminder) R.string.reminder_cancel else R.string.reminder_action))
        options.add(getString(R.string.ch_info))

        Ui.dialog(this)
            .setTitle("${EpgParser.timeOfDay(p.start, prefs.clock24h)} ${p.title}")
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                when {
                    choice == getString(R.string.play_now) -> play(c, p)
                    choice == getString(R.string.catchup_play) -> play(c, p)
                    choice == getString(R.string.reminder_action) -> toggleReminder(c, p)
                    choice == getString(R.string.reminder_cancel) -> {
                        Reminders.remove(this, key)
                        Ui.toast(this, getString(R.string.reminder_cancelled))
                        adapter?.notifyDataSetChanged()
                    }
                    choice == getString(R.string.ch_info) -> showProgramInfo(c, p)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleReminder(c: Channel, p: EpgProgram) {
        val key = Reminders.keyOf(c.tvgId, c.url, p.start)
        if (Reminders.has(this, key)) {
            Reminders.remove(this, key)
            Ui.toast(this, getString(R.string.reminder_cancelled))
        } else {
            Reminders.add(
                this,
                Reminders.Item(
                    key = key,
                    channelName = c.name,
                    channelUrl = c.url,
                    group = c.group,
                    title = p.title,
                    startMs = p.start
                )
            )
            Ui.toast(
                this,
                getString(R.string.reminder_set_fmt, p.title, EpgParser.timeOfDay(p.start, prefs.clock24h))
            )
        }
        adapter?.notifyDataSetChanged()
    }

    private fun showProgramInfo(c: Channel, p: EpgProgram) {
        val sb = StringBuilder()
        sb.append(p.title).append('\n')
        sb.append(EpgParser.timeOfDay(p.start, prefs.clock24h))
            .append(" - ").append(EpgParser.timeOfDay(p.end, prefs.clock24h)).append('\n')
        sb.append(c.name)
        if (!p.category.isNullOrBlank()) sb.append(" · ").append(p.category)
        if (!p.desc.isNullOrBlank()) sb.append("\n\n").append(p.desc)
        Ui.dialog(this)
            .setTitle(R.string.info_title)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ---------------- SyncScrollView.Group ----------------

    override fun register(v: SyncScrollView) {
        scrollers.add(v)
    }

    override fun unregister(v: SyncScrollView) {
        scrollers.remove(v)
    }

    override fun onScrolled(source: SyncScrollView, x: Int) {
        for (v in scrollers) if (v !== source) v.scrollToSynced(x)
        if (source === timeHeader) updateNowMarker(x)
    }

    companion object {
        fun launch(ctx: Context) = ctx.startActivity(Intent(ctx, GuideActivity::class.java))
    }
}
