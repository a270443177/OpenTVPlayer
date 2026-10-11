package com.opentv.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
import com.opentv.player.ui.SearchAdapter
import com.opentv.player.ui.SearchItem
import com.opentv.player.ui.Ui
import java.util.Locale

/** 搜索：按频道名与节目名检索。 */
class SearchActivity : AppCompatActivity() {

    private lateinit var prefs: PlaybackPrefs
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var adapter: SearchAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var channels: List<Channel> = emptyList()
    private val items = mutableListOf<SearchItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        prefs = PlaybackPrefs(this)

        list = findViewById(R.id.resultList)
        empty = findViewById(R.id.searchEmpty)
        status = findViewById(R.id.searchStatus)
        input = findViewById(R.id.searchInput)

        list.layoutManager = LinearLayoutManager(this)
        adapter = SearchAdapter(
            items = items,
            clock24h = prefs.clock24h,
            onChannel = { c -> play(c, null) },
            onProgram = { c, p -> onProgramClick(c, p) }
        )
        list.adapter = adapter

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        channels = Library.withNumbers(Library.channels(this))
        var pending: Runnable? = null
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val r = Runnable { doSearch(s?.toString().orEmpty()) }
                pending = r
                handler.postDelayed(r, 220)
            }
        })

        empty.text = getString(R.string.search_hint)
        status.text = getString(R.string.channel_count_fmt, channels.size)
    }

    private fun doSearch(query: String) {
        val q = query.trim().lowercase(Locale.getDefault())
        items.clear()
        if (q.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = getString(R.string.search_hint)
            status.text = getString(R.string.channel_count_fmt, channels.size)
            adapter.notifyDataSetChanged()
            return
        }

        var chHits = channels.filter { it.name.lowercase(Locale.getDefault()).contains(q) }.take(300)
        if (prefs.searchFavFirst) {
            val favs = com.opentv.player.data.PlaylistRepository(this).favorites()
            chHits = chHits.sortedByDescending { favs.contains(it.url) }
        }
        val progHits = ArrayList<Pair<Channel, EpgProgram>>()
        val now = System.currentTimeMillis()
        for (c in channels) {
            val list = EpgStore.programsFor(c.tvgId) ?: continue
            for (p in list) {
                if (p.end < now - 12 * 60 * 60_000L) continue
                val titleHit = p.title.lowercase(Locale.getDefault()).contains(q)
                val descHit = prefs.searchInDesc &&
                    !p.desc.isNullOrBlank() &&
                    p.desc.lowercase(Locale.getDefault()).contains(q)
                if (titleHit || descHit) {
                    progHits.add(c to p)
                    if (progHits.size >= 300) break
                }
            }
            if (progHits.size >= 300) break
        }

        if (chHits.isNotEmpty()) {
            items.add(SearchItem.Header(getString(R.string.search_channels) + " · " + chHits.size))
            chHits.forEach { items.add(SearchItem.Ch(it)) }
        }
        if (progHits.isNotEmpty()) {
            items.add(SearchItem.Header(getString(R.string.search_programs) + " · " + progHits.size))
            progHits.sortedBy { it.second.start }.forEach { items.add(SearchItem.Prog(it.first, it.second)) }
        }

        adapter.notifyDataSetChanged()
        val total = chHits.size + progHits.size
        empty.visibility = if (total == 0) View.VISIBLE else View.GONE
        if (total == 0) empty.text = getString(R.string.search_no_result)
        status.text = getString(R.string.search_result_fmt, total)
    }

    private fun play(c: Channel, program: EpgProgram?) {
        prefs.lastChannelUrl = c.url
        prefs.lastChannelName = c.name
        prefs.lastChannelGroup = c.group
        if (program != null && program.end <= System.currentTimeMillis() && c.supportsCatchup) {
            val url = Catchup.buildUrl(c, program.start, program.end)
            if (url != null) {
                startActivity(PlayerActivity.intent(this, url, c.name, c.group, c.playlist, c.tvgId))
                return
            }
        }
        startActivity(PlayerActivity.intent(this, c.url, c.name, c.group, c.playlist, c.tvgId))
    }

    private fun onProgramClick(c: Channel, p: EpgProgram) {
        val now = System.currentTimeMillis()
        when {
            p.isLive(now) -> play(c, p)
            p.start > now -> {
                val key = Reminders.keyOf(c.tvgId, c.url, p.start)
                if (Reminders.has(this, key)) {
                    Reminders.remove(this, key)
                    Ui.toast(this, getString(R.string.reminder_cancelled))
                } else {
                    Reminders.add(
                        this,
                        Reminders.Item(key, c.name, c.url, c.group, p.title, p.start)
                    )
                    Ui.toast(
                        this,
                        getString(R.string.reminder_set_fmt, p.title, EpgParser.timeOfDay(p.start, prefs.clock24h))
                    )
                }
            }
            else -> play(c, p)
        }
    }
}
