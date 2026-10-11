package com.opentv.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.opentv.player.R
import com.opentv.player.data.Library

/** 分组适配器：竖排导航栏（rail）与横排 chips 两种形态。 */
class GroupAdapter(
    private val items: MutableList<Library.GroupInfo>,
    private val compact: Boolean,
    private val onClick: (Int, Library.GroupInfo) -> Unit
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    var selected = 0
        set(value) {
            val old = field
            field = value
            if (old >= 0) notifyItemChanged(old)
            if (value >= 0) notifyItemChanged(value)
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.groupName)
        val count: TextView = v.findViewById(R.id.groupCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(
            LayoutInflater.from(parent.context)
                .inflate(if (compact) R.layout.item_group_chip else R.layout.item_group, parent, false)
        )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = items[position]
        holder.name.text = g.name
        holder.count.text = g.count.toString()
        holder.itemView.isSelected = position == selected
        holder.itemView.setOnClickListener { onClick(position, g) }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.04f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
    }
}
