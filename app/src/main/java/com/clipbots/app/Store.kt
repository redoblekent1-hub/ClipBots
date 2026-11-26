package com.clipbots.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Job(val url: String, val tries: Int)

class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("clipbots", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = p.getString("baseUrl", "https://api.openai.com/v1")!!.trimEnd('/')
        set(v) { p.edit().putString("baseUrl", v).apply() }
    var apiKey: String
        get() = p.getString("apiKey", "")!!
        set(v) { p.edit().putString("apiKey", v).apply() }
    var model: String
        get() = p.getString("model", "gpt-4o-mini")!!
        set(v) { p.edit().putString("model", v).apply() }
    var voice: String
        get() = p.getString("voice", "alloy")!!
        set(v) { p.edit().putString("voice", v).apply() }
    var autostart: Boolean
        get() = p.getBoolean("autostart", false)
        set(v) { p.edit().putBoolean("autostart", v).apply() }

    var p1080: Boolean
        get() = p.getBoolean("p1080", true)
        set(v) { p.edit().putBoolean("p1080", v).apply() }
    var out916: Boolean
        get() = p.getBoolean("out916", true)
        set(v) { p.edit().putBoolean("out916", v).apply() }
    var out169: Boolean
        get() = p.getBoolean("out169", true)
        set(v) { p.edit().putBoolean("out169", v).apply() }
    var voiceover: Boolean
        get() = p.getBoolean("voiceover", false)
        set(v) { p.edit().putBoolean("voiceover", v).apply() }
    var clipCount: Int
        get() = p.getInt("clipCount", 3)
        set(v) { p.edit().putInt("clipCount", v).apply() }
    var minScore: Int
        get() = p.getInt("minScore", 60)
        set(v) { p.edit().putInt("minScore", v).apply() }

    var ytClientId: String
        get() = p.getString("ytClientId", "")!!
        set(v) { p.edit().putString("ytClientId", v).apply() }
    var ytClientSecret: String
        get() = p.getString("ytClientSecret", "")!!
        set(v) { p.edit().putString("ytClientSecret", v).apply() }
    var ytRefresh: String
        get() = p.getString("ytRefresh", "")!!
        set(v) { p.edit().putString("ytRefresh", v).apply() }
    var ytAccess: String
        get() = p.getString("ytAccess", "")!!
        set(v) { p.edit().putString("ytAccess", v).apply() }
    var ytAccessExp: Long
        get() = p.getLong("ytAccessExp", 0L)
        set(v) { p.edit().putLong("ytAccessExp", v).apply() }
    var fbPageId: String
        get() = p.getString("fbPageId", "")!!
        set(v) { p.edit().putString("fbPageId", v).apply() }
    var fbToken: String
        get() = p.getString("fbToken", "")!!
        set(v) { p.edit().putString("fbToken", v).apply() }
    var autoPost: Boolean
        get() = p.getBoolean("autoPost", false)
        set(v) { p.edit().putBoolean("autoPost", v).apply() }
    var postYT: Boolean
        get() = p.getBoolean("postYT", true)
        set(v) { p.edit().putBoolean("postYT", v).apply() }
    var postFB: Boolean
        get() = p.getBoolean("postFB", true)
        set(v) { p.edit().putBoolean("postFB", v).apply() }
    var postTimes: String
        get() = p.getString("postTimes", "09:00,13:00,19:00")!!
        set(v) { p.edit().putString("postTimes", v).apply() }
    var extraTags: String
        get() = p.getString("extraTags", "")!!
        set(v) { p.edit().putString("extraTags", v).apply() }
    var deleteAfter: Boolean
        get() = p.getBoolean("deleteAfter", false)
        set(v) { p.edit().putBoolean("deleteAfter", v).apply() }
    var postsJson: String
        get() = p.getString("posts", "[]")!!
        set(v) { p.edit().putString("posts", v).apply() }

    private fun arr() = JSONArray(p.getString("queue", "[]"))
    private fun save(a: JSONArray) { p.edit().putString("queue", a.toString()).apply() }

    @Synchronized fun enqueue(url: String, tries: Int = 0) {
        val a = arr()
        a.put(JSONObject().put("u", url).put("t", tries))
        save(a)
    }

    @Synchronized fun pop(): Job? {
        val a = arr()
        if (a.length() == 0) return null
        val o = a.getJSONObject(0)
        val rest = JSONArray()
        for (i in 1 until a.length()) rest.put(a.get(i))
        save(rest)
        return Job(o.getString("u"), o.getInt("t"))
    }

    @Synchronized fun size(): Int = arr().length()
}

object BotLog {
    private val lines = ArrayDeque<String>()
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized fun log(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.addLast("$t  $s")
        while (lines.size > 150) lines.removeFirst()
        android.util.Log.i("ClipBots", s)
        listener?.invoke()
    }

    @Synchronized fun dump(): String = lines.joinToString("\n")
}
