package com.opentv.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.opentv.player.R
import com.opentv.player.data.Channel
import com.opentv.player.data.EpgParser
import com.opentv.player.data.EpgProgram

/**
 * 电视指南适配器：每行 = 一个频道（左固定列）+ 横向节目块（右时间轴）。
 * 所有行的横向滚动通过 SyncScrollView.Group 同步。
 */
class GuideAdapter(
    private val items: MutableList<Channel>,
    private val epgOf: (Channel) -> List<EpgProgram>?,
    private var windowStart: Long,
    private var windowEnd: Long,
    private val pxPerMin: Float,
    private val clock24h: Boolean,
    private val onChannelClick: (Channel) -> Unit,
    private val onProgramClick: (Channel, EpgProgram) -> Unit,
    private val onProgramLongClick: (Channel, EpgProgram) -> Unit,
    private val canCatchup: (Channel) -> Boolean,
    private val sync: SyncScrollView.Group
) : RecyclerView.Adapter<GuideAdapter.VH>() {

    private val minBlockPx = (pxPerMin * 8).toInt()

    /** 节目块左右外边距之和（dp→px），用于让块宽扣除间距后仍与时间轴对齐。 */
    private fun sepPx(ctx: android.content.Context): Int =
        (6 * ctx.resources.displayMetrics.density).toInt()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cell: LinearLayout = v.findViewById(R.id.channelCell)
        val number: TextView = v.findViewById(R.id.number)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val scroll: SyncScrollView = v.findViewById(R.id.programScroll)
        val row: LinearLayout = v.findViewById(R.id.programRow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_guide_row, parent, false))

    override fun getItemCount(): Int = items.size

    fun setWindow(start: Long, end: Long) {
        windowStart = start
        windowEnd = end
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val now = System.currentTimeMillis()
        val ctx = holder.itemView.context

        holder.number.text = c.number?.toString() ?: "—"
        holder.name.text = c.name
        LogoLoader.load(holder.logo, c.logo)
        holder.cell.setOnClickListener { onChannelClick(c) }
        holder.scroll.group = sync

        // 重建节目行
        holder.row.removeAllViews()
        val programs = epgOf(c)?.filter { it.end > windowStart && it.start < windowEnd } ?: emptyList()

        if (programs.isEmpty()) {
            holder.row.addView(filler(holder.row, ((windowEnd - windowStart) / 60_000f * pxPerMin).toInt(), ctx.getString(R.string.no_epg)))
            return
        }

        val firstStart = maxOf(programs.first().start, windowStart)
        val leadPx = ((firstStart - windowStart) / 60_000f * pxPerMin).toInt()
        if (leadPx > 0) holder.row.addView(filler(holder.row, leadPx, ""))

        val sep = sepPx(holder.itemView.context)
        var cursor = firstStart
        for (p in programs) {
            val start = maxOf(p.start, windowStart)
            val end = minOf(p.end, windowEnd)
            val gapPx = ((start - cursor) / 60_000f * pxPerMin).toInt()
            if (gapPx > 4) holder.row.addView(filler(holder.row, gapPx, ""))
            // 扣除左右外边距，保证块+间距的总占用仍等于真实时长，时间轴不错位
            val widthPx = (((end - start) / 60_000f * pxPerMin).toInt() - sep).coerceAtLeast(minBlockPx)
            holder.row.addView(buildProgramView(holder.row, c, p, widthPx))
            cursor = end
        }
        val tailPx = ((windowEnd - cursor) / 60_000f * pxPerMin).toInt()
        if (tailPx > 4) holder.row.addView(filler(holder.row, tailPx, ""))
    }

    private fun filler(parent: LinearLayout, widthPx: Int, label: String): View {
        val ctx = parent.context
        val tv = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.MATCH_PARENT)
            text = label
            gravity = android.view.Gravity.CENTER
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_faint))
            textSize = 11f
        }
        return tv
    }

    private fun buildProgramView(parent: LinearLayout, channel: Channel, p: EpgProgram, widthPx: Int): View {
        val ctx = parent.context
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_program, parent, false)
        v.layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.MATCH_PARENT)

        val now = System.currentTimeMillis()
        val live = p.isLive(now)
        val past = p.end <= now

        v.setBackgroundResource(
            when {
                past -> R.drawable.bg_program_past
                live -> R.drawable.bg_program_now
                else -> R.drawable.bg_program
            }
        )

        v.findViewById<TextView>(R.id.programTitle).text = p.title.ifBlank { "—" }
        val timeView = v.findViewById<TextView>(R.id.programTime)
        val badge = v.findViewById<TextView>(R.id.liveBadge)
        val progress = v.findViewById<ProgressBar>(R.id.programProgress)
        val widthDp = widthPx / ctx.resources.displayMetrics.density

        // 太窄的节目块只显示标题
        if (widthDp < 92f) {
            timeView.visibility = View.GONE
            badge.visibility = View.GONE
            progress.visibility = View.GONE
        } else {
            val range = "%s - %s".format(
                EpgParser.timeOfDay(p.start, clock24h),
                EpgParser.timeOfDay(p.end, clock24h)
            )
            // 已结束且支持回看的节目，标出"回看"提示，让用户知道点得动
            timeView.text = if (past && canCatchup(channel)) "回看 · $range" else range
            badge.visibility = if (live) View.VISIBLE else View.GONE
            if (live) {
                progress.visibility = View.VISIBLE
                progress.progress = (p.progress(now) * 100).toInt()
            } else {
                progress.visibility = View.GONE
            }
        }

        v.setOnClickListener { onProgramClick(channel, p) }
        v.setOnLongClickListener {
            onProgramLongClick(channel, p)
            true
        }
        v.setOnFocusChangeListener { view, hasFocus ->
            val s = if (hasFocus) 1.03f else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(110).start()
        }
        return v
    }
}
