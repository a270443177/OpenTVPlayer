package com.opentv.player.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** 节目提醒：用 AlarmManager 在节目开始时通知用户。 */
object Reminders {

    private const val KEY = "reminders"
    private const val PREFS = "opentv_reminders"

    data class Item(
        val key: String,
        val channelName: String,
        val channelUrl: String,
        val group: String?,
        val title: String,
        val startMs: Long
    )

    fun keyOf(tvgId: String?, channelUrl: String, startMs: Long): String =
        "${tvgId ?: channelUrl}@$startMs"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Item> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Item(
                        key = o.optString("key"),
                        channelName = o.optString("channelName"),
                        channelUrl = o.optString("channelUrl"),
                        group = o.optStringOrNull("group"),
                        title = o.optString("title"),
                        startMs = o.optLong("startMs")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Item>) {
        val arr = JSONArray()
        for (i in list) {
            arr.put(
                JSONObject().apply {
                    put("key", i.key)
                    put("channelName", i.channelName)
                    put("channelUrl", i.channelUrl)
                    put("group", i.group ?: JSONObject.NULL)
                    put("title", i.title)
                    put("startMs", i.startMs)
                }
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun has(ctx: Context, key: String): Boolean = all(ctx).any { it.key == key }

    fun add(ctx: Context, item: Item) {
        val list = all(ctx).filter { it.key != item.key } + item
        save(ctx, list)
        schedule(ctx, item)
    }

    fun remove(ctx: Context, key: String) {
        val item = all(ctx).firstOrNull { it.key == key }
        save(ctx, all(ctx).filter { it.key != key })
        if (item != null) cancel(ctx, item)
    }

    /** 清理已经开始超过 5 分钟的过期提醒。 */
    fun prune(ctx: Context) {
        val now = System.currentTimeMillis()
        val list = all(ctx)
        val keep = list.filter { it.startMs > now - 5 * 60_000 }
        if (keep.size != list.size) save(ctx, keep)
    }

    private fun pendingIntent(ctx: Context, item: Item): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = "com.opentv.player.REMINDER"
            putExtra("title", item.title)
            putExtra("channel", item.channelName)
            putExtra("url", item.channelUrl)
            putExtra("key", item.key)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, item.key.hashCode(), intent, flags)
    }

    private fun schedule(ctx: Context, item: Item) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx, item)
        val trigger = (item.startMs - 10_000).coerceAtLeast(System.currentTimeMillis() + 1000)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (e: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    private fun cancel(ctx: Context, item: Item) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(ctx, item))
    }
}
