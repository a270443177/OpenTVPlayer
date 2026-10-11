package com.opentv.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.opentv.player.R
import com.opentv.player.data.Channel
import com.opentv.player.data.EpgParser
import com.opentv.player.data.EpgProgram
import com.opentv.player.data.EpgStore

/** 搜索结果项：分区标题 / 频道 / 节目。 */
sealed class SearchItem {
    data class Header(val text: String) : SearchItem()
    data class Ch(val channel: Channel) : SearchItem()
    data class Prog(val channel: Channel, val program: EpgProgram) : SearchItem()
}

class SearchAdapter(
    private val items: MutableList<SearchItem>,
    private val clock24h: Boolean,
    private val onChannel: (Channel) -> Unit,
    private val onProgram: (Channel, EpgProgram) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val T_HEADER = 0
        private const val T_CHANNEL = 1
        private const val T_PROGRAM = 2
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.headerText)
    }

    class ChannelVH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.number)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val program: TextView = v.findViewById(R.id.program)
        val time: TextView = v.findViewById(R.id.time)
    }

    class ProgramVH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val time: TextView = v.findViewById(R.id.time)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SearchItem.Header -> T_HEADER
        is SearchItem.Ch -> T_CHANNEL
        is SearchItem.Prog -> T_PROGRAM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            T_HEADER -> HeaderVH(inf.inflate(R.layout.item_search_header, parent, false))
            T_CHANNEL -> ChannelVH(inf.inflate(R.layout.item_channel, parent, false))
            else -> ProgramVH(inf.inflate(R.layout.item_search_program, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SearchItem.Header -> (holder as HeaderVH).text.text = item.text

            is SearchItem.Ch -> {
                val h = holder as ChannelVH
                h.number.text = item.channel.number?.toString() ?: "—"
                h.name.text = item.channel.name
                LogoLoader.load(h.logo, item.channel.logo)
                val prog = EpgParser.current(EpgStore.programsFor(item.channel.tvgId), System.currentTimeMillis())
                h.program.text = prog?.title ?: item.channel.group.orEmpty()
                h.time.text = item.channel.group.orEmpty()
                h.itemView.setOnClickListener { onChannel(item.channel) }
            }

            is SearchItem.Prog -> {
                val h = holder as ProgramVH
                val c = item.channel
                val p = item.program
                LogoLoader.load(h.logo, c.logo)
                h.title.text = p.title
                h.subtitle.text = c.name + if (!p.desc.isNullOrBlank()) " · ${p.desc}" else ""
                h.time.text = "${EpgParser.timeOfDay(p.start, clock24h)}-${EpgParser.timeOfDay(p.end, clock24h)}"
                h.itemView.setOnClickListener { onProgram(c, p) }
            }
        }
    }
}
