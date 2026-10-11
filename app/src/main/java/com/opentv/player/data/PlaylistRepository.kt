package com.opentv.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本地持久化：播放列表存 JSON 文件，收藏/设置存 SharedPreferences；含备份/恢复。 */
class PlaylistRepository(private val context: Context) {

    private val file = File(context.filesDir, "playlists.json")
    private val prefs = context.getSharedPreferences("opentv", Context.MODE_PRIVATE)

    fun loadPlaylists(): MutableList<Playlist> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            val list = ArrayList<Playlist>(arr.length())
            for (i in 0 until arr.length()) list.add(Playlist.fromJson(arr.getJSONObject(i)))
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun savePlaylists(list: List<Playlist>): Boolean = try {
        val arr = JSONArray()
        for (p in list) arr.put(p.toJson())
        file.writeText(arr.toString())
        true
    } catch (e: Exception) {
        false
    }

    // ---- 收藏 ----

    fun favorites(): MutableSet<String> =
        (prefs.getStringSet(KEY_FAV, emptySet()) ?: emptySet()).toMutableSet()

    /** 返回 true 表示加入收藏，false 表示移除。 */
    fun toggleFavorite(key: String): Boolean {
        val set = favorites()
        val added: Boolean
        if (set.contains(key)) {
            set.remove(key); added = false
        } else {
            set.add(key); added = true
        }
        prefs.edit().putStringSet(KEY_FAV, set).apply()
        return added
    }

    fun isFavorite(key: String): Boolean = favorites().contains(key)

    // ---- 备份 / 恢复 ----

    fun backupDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups").apply { mkdirs() }

    /** 导出播放列表 + 收藏 + 设置到 JSON 文件，返回生成的文件。 */
    fun backup(): File? = try {
        val root = JSONObject()
        root.put("version", 1)
        root.put("createdAt", System.currentTimeMillis())
        val plArr = JSONArray()
        for (p in loadPlaylists()) plArr.put(p.toJson())
        root.put("playlists", plArr)
        root.put("favorites", JSONArray(favorites().toList()))
        val settings = JSONObject()
        val all = prefs.all
        for ((k, v) in all) {
            if (k == KEY_FAV) continue
            when (v) {
                is String -> settings.put(k, v)
                is Boolean -> settings.put(k, v)
                is Int -> settings.put(k, v)
                is Long -> settings.put(k, v)
                is Set<*> -> settings.put(k, JSONArray(v.toList()))
                else -> { /* 忽略其他类型 */ }
            }
        }
        root.put("settings", settings)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(backupDir(), "opentv-backup-$stamp.json")
        out.writeText(root.toString())
        out
    } catch (e: Exception) {
        null
    }

    fun listBackups(): List<File> =
        backupDir().listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** 从备份文件恢复（覆盖播放列表、收藏与可迁移设置）。 */
    fun restore(from: File): Boolean = try {
        val root = JSONObject(from.readText())
        val plArr = root.optJSONArray("playlists") ?: JSONArray()
        val list = ArrayList<Playlist>(plArr.length())
        for (i in 0 until plArr.length()) list.add(Playlist.fromJson(plArr.getJSONObject(i)))
        savePlaylists(list)

        val edit = prefs.edit()
        val favArr = root.optJSONArray("favorites")
        if (favArr != null) {
            val set = HashSet<String>()
            for (i in 0 until favArr.length()) set.add(favArr.getString(i))
            edit.putStringSet(KEY_FAV, set)
        }
        val settings = root.optJSONObject("settings")
        if (settings != null) {
            for (k in settings.keys()) {
                when (val v = settings.get(k)) {
                    is Boolean -> edit.putBoolean(k, v)
                    is Int -> edit.putInt(k, v)
                    is Long -> edit.putLong(k, v)
                    is String -> edit.putString(k, v)
                    is JSONArray -> {
                        val set = HashSet<String>()
                        for (i in 0 until v.length()) set.add(v.getString(i))
                        edit.putStringSet(k, set)
                    }
                }
            }
        }
        edit.apply()
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val KEY_FAV = "favorites"
    }
}
