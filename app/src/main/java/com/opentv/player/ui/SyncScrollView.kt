package com.opentv.player.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.HorizontalScrollView

/**
 * 可与其他横向滚动容器联动（同步滚动）的 HorizontalScrollView。
 * 电视指南的时间轴表头与各频道节目行都使用它，实现整页横向同步。
 */
class SyncScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    /** 同步组：同一组内的视图滚动时保持一致的横向偏移。 */
    interface Group {
        fun register(v: SyncScrollView)
        fun unregister(v: SyncScrollView)
        fun onScrolled(source: SyncScrollView, x: Int)
    }

    var group: Group? = null
        set(value) {
            field?.unregister(this)
            field = value
            value?.register(this)
        }

    private var suppress = false

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!suppress) group?.onScrolled(this, l)
    }

    /** 由同步组调用，避免回调风暴。 */
    fun scrollToSynced(x: Int) {
        if (scrollX == x) return
        suppress = true
        scrollTo(x, 0)
        suppress = false
    }
}
