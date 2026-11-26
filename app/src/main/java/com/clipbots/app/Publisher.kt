package com.clipbots.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.UUID

object Http {
    fun postForm(url: String, params: Map<String, String>): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val st = if (code in 200..299) c.inputStream else c.errorStream
        return code to (st?.use { String(it.readBytes()) } ?: "")
    }

    fun readBody(c: HttpURLConnection): Pair<Int, String> {
        val code = c.responseCode
        val st = if (code in 200..299) c.inputStream else c.errorStream
        return code to (st?.use { String(it.readBytes()) } ?: "")
    }
}

/** Connects your YouTube channel using Google's "device code" login (no redirect page needed). */
object YouTubeAuth {
    fun start(s: Store, onCode: (String, String) -> Unit, onResult: (String) -> Unit) {
        Thread {
            try {
                val (c, b) = Http.postForm(
                    "https://oauth2.googleapis.com/device/code",
                    mapOf("client_id" to s.ytClientId, "scope" to "https://www.googleapis.com/auth/youtube.upload"),
                )
                if (c !in 200..299) { onResult("YouTube connect failed to start: ${b.take(200)}"); return@Thread }
                val j = JSONObject(b)
                val device = j.getString("device_code")
                var interval = j.optInt("interval", 5)
                val deadline = System.currentTimeMillis() + j.getLong("expires_in") * 1000
                onCode(j.getString("user_code"), j.optString("verification_url", "https://www.google.com/device"))
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(interval * 1000L)
                    val (tc, tb) = Http.postForm(
                        "https://oauth2.googleapis.com/token",
                        mapOf(
                            "client_id" to s.ytClientId,
                            "client_secret" to s.ytClientSecret,
                            "device_code" to device,
                            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        ),
                    )
                    val tj = JSONObject(tb)
                    if (tc in 200..299) {
                        s.ytRefresh = tj.getString("refresh_token")
                        s.ytAccess = tj.getString("access_token")
                        s.ytAccessExp = System.currentTimeMillis() + tj.getLong("expires_in") * 1000
                        onResult("YouTube connected")
                        return@Thread
                    }
                    when (tj.optString("error")) {
                        "authorization_pending" -> {}
                        "slow_down" -> interval += 5
                        else -> { onResult("YouTube connect failed: ${tj.optString("error")}"); return@Thread }
                    }
                }
                onResult("The code expired. Tap Connect YouTube again.")
            } catch (e: Exception) {
                onResult("YouTube connect error: ${e.message}")
            }
        }.start()
    }
}

data class Post(
    val id: String,
    val file: String,
    val title: String,
    val caption: String,
    val tags: List<String>,
    var whenMs: Long,
    var yt: String,   // pending | done | failed | off
    var fb: String,
    var tries: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("file", file).put("title", title).put("caption", caption)
        .put("tags", JSONArray(tags)).put("when", whenMs).put("yt", yt).put("fb", fb).put("tries", tries)

    companion object {
        fun from(o: JSONObject): Post {
            val ta = o.optJSONArray("tags") ?: JSONArray()
            return Post(
                o.getString("id"), o.getString("file"), o.getString("title"), o.getString("caption"),
                (0 until ta.length()).map { ta.getString(it) },
                o.getLong("when"), o.getString("yt"), o.getString("fb"), o.optInt("tries", 0),
            )
        }
    }
}

/** The posting bots: upload a finished short to YouTube Shorts and/or a Facebook Page Reel. */
object Publisher {
    private fun ytToken(s: Store): String {
        if (s.ytAccess.isNotBlank() && System.currentTimeMillis() < s.ytAccessExp - 60_000) return s.ytAccess
        val (code, body) = Http.postForm(
            "https://oauth2.googleapis.com/token",
            mapOf(
                "client_id" to s.ytClientId, "client_secret" to s.ytClientSecret,
                "refresh_token" to s.ytRefresh, "grant_type" to "refresh_token",
            ),
        )
        if (code !in 200..299) throw IOException("YouTube login expired, tap Connect YouTube again (${body.take(120)})")
        val j = JSONObject(body)
        s.ytAccess = j.getString("access_token")
        s.ytAccessExp = System.currentTimeMillis() + j.getLong("expires_in") * 1000
        return s.ytAccess
    }

    /** Returns the new video id. */
    fun youtube(s: Store, p: Post): String {
        val file = File(p.file)
        if (!file.exists()) throw IOException("video file is missing")
        val token = ytToken(s)

        val tags = JSONArray()
        (p.tags + "Shorts").distinct().forEach { tags.put(it) }
        val meta = JSONObject()
            .put("snippet", JSONObject()
                .put("title", "${p.title} #Shorts".take(100))
                .put("description", p.caption + "\n\n#Shorts")
                .put("tags", tags)
                .put("categoryId", "22"))
            .put("status", JSONObject()
                .put("privacyStatus", "public")
                .put("selfDeclaredMadeForKids", false))

        val init = URL("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status")
            .openConnection() as HttpURLConnection
        init.requestMethod = "POST"
        init.doOutput = true
        init.connectTimeout = 30_000
        init.readTimeout = 60_000
        init.setRequestProperty("Authorization", "Bearer $token")
        init.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        init.setRequestProperty("X-Upload-Content-Type", "video/mp4")
        init.setRequestProperty("X-Upload-Content-Length", file.length().toString())
        init.outputStream.use { it.write(meta.toString().toByteArray()) }
        val (ic, ib) = Http.readBody(init)
        if (ic !in 200..299) throw IOException("YouTube init HTTP $ic: ${ib.take(250)}")
        val loc = init.getHeaderField("Location") ?: throw IOException("YouTube gave no upload URL")

        val up = URL(loc).openConnection() as HttpURLConnection
        up.requestMethod = "PUT"
        up.doOutput = true
        up.connectTimeout = 30_000
        up.readTimeout = 300_000
        up.setFixedLengthStreamingMode(file.length())
        up.setRequestProperty("Content-Type", "video/mp4")
        up.setRequestProperty("Authorization", "Bearer $token")
        file.inputStream().use { i -> up.outputStream.use { o -> i.copyTo(o) } }
        val (uc, ub) = Http.readBody(up)
        if (uc !in 200..299) throw IOException("YouTube upload HTTP $uc: ${ub.take(250)}")
        return JSONObject(ub).getString("id")
    }

    fun facebook(s: Store, p: Post) {
        val file = File(p.file)
        if (!file.exists()) throw IOException("video file is missing")
        val v = "v26.0"
        val page = s.fbPageId

        val (c1, b1) = Http.postForm(
            "https://graph.facebook.com/$v/$page/video_reels",
            mapOf("upload_phase" to "start", "access_token" to s.fbToken),
        )
        if (c1 !in 200..299) throw IOException("Facebook start HTTP $c1: ${b1.take(250)}")
        val start = JSONObject(b1)
        val videoId = start.getString("video_id")
        val uploadUrl = start.optString("upload_url", "https://rupload.facebook.com/video-upload/$v/$videoId")

        val up = URL(uploadUrl).openConnection() as HttpURLConnection
        up.requestMethod = "POST"
        up.doOutput = true
        up.connectTimeout = 30_000
        up.readTimeout = 300_000
        up.setFixedLengthStreamingMode(file.length())
        up.setRequestProperty("Authorization", "OAuth ${s.fbToken}")
        up.setRequestProperty("offset", "0")
        up.setRequestProperty("file_size", file.length().toString())
        up.setRequestProperty("Content-Type", "application/octet-stream")
        file.inputStream().use { i -> up.outputStream.use { o -> i.copyTo(o) } }
        val (c2, b2) = Http.readBody(up)
        if (c2 !in 200..299) throw IOException("Facebook upload HTTP $c2: ${b2.take(250)}")

        val (c3, b3) = Http.postForm(
            "https://graph.facebook.com/$v/$page/video_reels",
            mapOf(
                "access_token" to s.fbToken, "video_id" to videoId, "upload_phase" to "finish",
                "video_state" to "PUBLISHED", "description" to p.caption,
            ),
        )
        if (c3 !in 200..299) throw IOException("Facebook publish HTTP $c3: ${b3.take(250)}")
    }
}

/** The scheduler bot: gives each short a posting slot and posts it when the time comes. */
object Scheduler {
    private val lock = Any()
    private const val RETRY_MS = 15 * 60_000L

    private fun load(s: Store): MutableList<Post> {
        val a = JSONArray(s.postsJson)
        return (0 until a.length()).map { Post.from(a.getJSONObject(it)) }.toMutableList()
    }

    private fun save(s: Store, list: List<Post>) {
        val done = list.filter { it.yt != "pending" && it.fb != "pending" }.sortedBy { it.whenMs }.takeLast(30)
        val keep = list.filter { it.yt == "pending" || it.fb == "pending" } + done
        val a = JSONArray()
        keep.sortedBy { it.whenMs }.forEach { a.put(it.toJson()) }
        s.postsJson = a.toString()
    }

    fun pending(s: Store): Int = synchronized(lock) { load(s).count { it.yt == "pending" || it.fb == "pending" } }

    private fun slots(s: Store): List<Pair<Int, Int>> {
        val parsed = s.postTimes.split(",").mapNotNull {
            val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(it.trim()) ?: return@mapNotNull null
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            if (h in 0..23 && mi in 0..59) h to mi else null
        }.sortedWith(compareBy({ it.first }, { it.second }))
        return parsed.ifEmpty { listOf(9 to 0, 19 to 0) }
    }

    private fun nextSlot(s: Store, after: Long): Long {
        val sl = slots(s)
        for (day in 0..60) {
            for ((h, m) in sl) {
                val c = Calendar.getInstance()
                c.add(Calendar.DAY_OF_YEAR, day)
                c.set(Calendar.HOUR_OF_DAY, h)
                c.set(Calendar.MINUTE, m)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                if (c.timeInMillis > after) return c.timeInMillis
            }
        }
        return after + 24 * 3600_000L
    }

    fun enqueue(s: Store, file: File, c: Candidate) {
        val yt = if (s.postYT && s.ytRefresh.isNotBlank()) "pending" else "off"
        val fb = if (s.postFB && s.fbPageId.isNotBlank() && s.fbToken.isNotBlank()) "pending" else "off"
        if (yt == "off" && fb == "off") {
            BotLog.log("Auto-post is on but no account is connected, skipping post for ${c.title}")
            return
        }
        val tags = (c.hashtags + s.extraTags.split(Regex("[,\\s]+")))
            .map { it.trim().trimStart('#').replace(Regex("[^\\p{L}\\p{N}_]"), "") }
            .filter { it.isNotEmpty() }.distinct().take(12)
        val caption = c.description.ifBlank { c.hook } + "\n\n" + tags.joinToString(" ") { "#$it" }

        synchronized(lock) {
            val all = load(s)
            val last = all.filter { it.yt == "pending" || it.fb == "pending" }.maxOfOrNull { it.whenMs } ?: 0L
            val whenMs = nextSlot(s, maxOf(System.currentTimeMillis(), last))
            all.add(Post(UUID.randomUUID().toString(), file.absolutePath, c.title, caption, tags, whenMs, yt, fb, 0))
            save(s, all)
            BotLog.log("Scheduled \"${c.title}\" for ${java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.US).format(java.util.Date(whenMs))}")
        }
    }

    /** Called every minute by the service: posts anything that is due. */
    fun tick(s: Store) {
        val now = System.currentTimeMillis()
        val due = synchronized(lock) { load(s).filter { it.whenMs <= now && (it.yt == "pending" || it.fb == "pending") } }
        for (p in due) {
            var failed = false
            if (p.yt == "pending") {
                try {
                    val id = Publisher.youtube(s, p)
                    p.yt = "done"
                    BotLog.log("YouTube posted: https://youtube.com/shorts/$id")
                } catch (e: Exception) { failed = true; BotLog.log("YouTube post failed: ${e.message}") }
            }
            if (p.fb == "pending") {
                try {
                    Publisher.facebook(s, p)
                    p.fb = "done"
                    BotLog.log("Facebook Reel published: ${p.title}")
                } catch (e: Exception) { failed = true; BotLog.log("Facebook post failed: ${e.message}") }
            }
            if (failed) {
                p.tries++
                if (p.tries >= 3) {
                    if (p.yt == "pending") p.yt = "failed"
                    if (p.fb == "pending") p.fb = "failed"
                    BotLog.log("Giving up on post: ${p.title}")
                } else {
                    p.whenMs = System.currentTimeMillis() + RETRY_MS
                    BotLog.log("Will retry this post in 15 min")
                }
            }
            synchronized(lock) {
                val all = load(s)
                val i = all.indexOfFirst { it.id == p.id }
                if (i >= 0) all[i] = p
                save(s, all)
            }
            if (s.deleteAfter && p.yt != "pending" && p.fb != "pending" && p.yt != "failed" && p.fb != "failed") {
                File(p.file).delete()
            }
        }
    }
}
