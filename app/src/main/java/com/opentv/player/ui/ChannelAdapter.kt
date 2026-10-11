package com.opentv.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.opentv.player.R
import com.opentv.player.data.Channel
import com.opentv.player.data.EpgParser
import com.opentv.player.data.EpgProgram

/**
 * 频道行适配器：TiviMate 风格（频道号 + 台标 + 名称 + 正在播出 + 进度条）。
 *
 * @param compact 紧凑模式（播放器切台列表用）：隐藏右侧时间/进度列，台名占满整行宽度。
 */
class ChannelAdapter(
    private val items: MutableList<Channel>,
    private val programOf: (Channel) -> EpgProgram?,
    private val isFavorite: (Channel) -> Boolean,
    private val clock24h: Boolean,
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Unit,
    /** 高亮当前正在播放的频道（播放器内切台列表用） */
    private val highlight: ((Channel) -> Boolean)? = null,
    private val compact: Boolean = false
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView? = v.findViewById(R.id.number)
        val logo: ImageView? = v.findViewById(R.id.logo)
        val name: TextView? = v.findViewById(R.id.name)
        val program: TextView? = v.findViewById(R.id.program)
        val time: TextView? = v.findViewById(R.id.time)
        val progress: ProgressBar? = v.findViewById(R.id.progress)
        val star: ImageView? = v.findViewById(R.id.star)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(
            LayoutInflater.from(parent.context)
                .inflate(
                    if (compact) R.layout.item_channel_compact else R.layout.item_channel,
                    parent, false
                )
        )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.number?.text = c.number?.toString() ?: "—"
        holder.name?.text = c.name
        holder.logo?.let { LogoLoader.load(it, c.logo) }

        val now = System.currentTimeMillis()
        val prog = programOf(c)
        if (prog == null) {
            holder.program?.text = ""
            holder.time?.text = ""
            holder.progress?.progress = 0
        } else {
            holder.program?.text = prog.title
            holder.time?.text = "%s-%s".format(
                EpgParser.timeOfDay(prog.start, clock24h),
                EpgParser.timeOfDay(prog.end, clock24h)
            )
            holder.progress?.progress = (prog.progress(now) * 100).toInt()
        }

        holder.star?.visibility = if (isFavorite(c)) View.VISIBLE else View.GONE
        holder.itemView.isSelected = highlight?.invoke(c) == true
        holder.itemView.isActivated = holder.itemView.isSelected

        holder.itemView.setOnClickListener { onClick(c) }
        holder.itemView.setOnLongClickListener {
            onLongClick(c)
            true
        }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.02f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(110).start()
        }
    }
}
