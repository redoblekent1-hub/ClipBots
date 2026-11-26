package com.clipbots.app

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Minimal HTTP helpers for OpenAI-compatible APIs. */
object Api {
    private fun open(url: String, key: String, contentType: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 30_000
        c.readTimeout = 180_000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", contentType)
        return c
    }

    private fun read(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val data = stream?.use { it.readBytes() } ?: ByteArray(0)
        if (code !in 200..299) throw IOException("HTTP $code: ${String(data).take(300)}")
        return data
    }

    fun json(url: String, key: String, body: JSONObject): ByteArray {
        val c = open(url, key, "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return read(c)
    }

    fun multipart(url: String, key: String, fields: Map<String, String>, fileField: String, file: File): ByteArray {
        val b = "----clipbots${System.nanoTime()}"
        val c = open(url, key, "multipart/form-data; boundary=$b")
        c.setChunkedStreamingMode(0)
        c.outputStream.use { o ->
            fun w(s: String) = o.write(s.toByteArray())
            for ((k, v) in fields) w("--$b\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            w("--$b\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n")
            file.inputStream().use { it.copyTo(o) }
            w("\r\n--$b--\r\n")
        }
        return read(c)
    }
}

object Media {
    fun durationMs(video: File): Long {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(video.path)
            return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } finally { r.release() }
    }
}

/** BOT 1: takes the link, downloads the long-form video. */
object Ingest {
    fun download(url: String, dir: File): File {
        val out = File(dir, "source.mp4")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        val type = c.contentType ?: ""
        if (type.startsWith("text/html")) {
            throw IOException("That is a web page, not a direct video file. (YouTube-style links need yt-dlp, see README.)")
        }
        c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return out
    }
}

/** BOT 2a: listens to the whole video in 10-minute audio chunks and builds a timestamped transcript. */
object Transcriber {
    private const val CHUNK_MS = 10 * 60_000L

    fun run(ctx: Context, s: Store, src: File, work: File, durMs: Long): String {
        val sb = StringBuilder()
        var start = 0L
        var i = 0
        while (start < durMs) {
            val end = minOf(durMs, start + CHUNK_MS)
            if (end - start < 2000) break
            BotLog.log("Transcribing minutes ${start / 60000}-${end / 60000}")
            val audio = File(work, "audio$i.m4a")
            Editor.extractAudio(ctx, src, start, end, audio)
            val raw = Api.multipart(
                "${s.baseUrl}/audio/transcriptions", s.apiKey,
                mapOf("model" to "whisper-1", "response_format" to "verbose_json"),
                "file", audio,
            )
            audio.delete()
            val segs = JSONObject(String(raw)).optJSONArray("segments") ?: JSONArray()
            val off = start / 1000.0
            for (k in 0 until segs.length()) {
                val o = segs.getJSONObject(k)
                sb.append(String.format(Locale.US, "[%.1f-%.1f] %s\n",
                    o.getDouble("start") + off, o.getDouble("end") + off, o.getString("text").trim()))
            }
            start = end
            i++
        }
        if (sb.isBlank()) throw IOException("No speech found in this video")
        return sb.toString().take(200_000)
    }
}

data class Candidate(
    val title: String,
    val hook: String,
    val score: Int,
    val reason: String,
    val startMs: Long,
    val endMs: Long,
    val description: String = "",
    val hashtags: List<String> = emptyList(),
)

/**
 * BOT 2b: VIRALITY SCANNER. Reads the transcript, finds self-contained moments that
 * could work as vertical shorts, and scores each 0-100. Also writes the title and hook line.
 * Note: this is an AI estimate from the content, not real audience data.
 */
object Virality {
    private const val MAX_SHORT_MS = 60_000L

    fun scan(s: Store, transcript: String, durMs: Long): List<Candidate> {
        val system = "You are a viral short-form content analyst for TikTok, Reels and YouTube Shorts. " +
            "You get a timestamped transcript of a long video. Find 6 to 10 self-contained moments of 20 to 60 seconds that could work as vertical shorts. " +
            "Start each at the beginning of a sentence and end at a natural stop. " +
            "Score each from 0 to 100 on: hook strength in the first 3 seconds, emotional intensity or surprise, curiosity gap, clarity without outside context, payoff or punchline, and shareability. " +
            "Be strict: most moments should score under 70. " +
            "Reply ONLY with JSON: {\"clips\":[{\"title\":\"\",\"hook\":\"\",\"score\":0,\"reason\":\"\",\"description\":\"\",\"hashtags\":[\"\"],\"start\":0.0,\"end\":0.0}]} " +
            "where title is max 6 words, hook is one punchy spoken line under 15 words, reason is one short sentence, " +
            "description is a 1 to 2 sentence social media caption that ends with a question or call to action, " +
            "hashtags is 5 to 8 relevant hashtags without the # symbol and without spaces, times are in seconds."
        val body = JSONObject()
            .put("model", s.model)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", transcript)))
        val resp = JSONObject(String(Api.json("${s.baseUrl}/chat/completions", s.apiKey, body)))
        val content = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        val arr = JSONObject(content).getJSONArray("clips")

        val out = ArrayList<Candidate>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val st = (o.getDouble("start") * 1000).toLong().coerceIn(0, durMs)
            var en = (o.getDouble("end") * 1000).toLong().coerceIn(0, durMs)
            if (en - st > MAX_SHORT_MS) en = st + MAX_SHORT_MS
            if (en - st < 10_000) continue
            val ht = ArrayList<String>()
            val ha = o.optJSONArray("hashtags")
            if (ha != null) for (k in 0 until ha.length()) ht.add(ha.optString(k))
            out.add(Candidate(
                o.optString("title", "Clip ${i + 1}"), o.optString("hook", ""),
                o.optInt("score", 0).coerceIn(0, 100), o.optString("reason", ""), st, en,
                o.optString("description", ""), ht,
            ))
        }
        return out.sortedByDescending { it.score }
    }

    /** Best non-overlapping clips at or above minScore. Always returns the single best if none pass. */
    fun select(all: List<Candidate>, count: Int, minScore: Int): List<Candidate> {
        val picked = ArrayList<Candidate>()
        for (c in all) {
            if (c.score < minScore || picked.size >= count) continue
            if (picked.none { c.startMs < it.endMs && it.startMs < c.endMs }) picked.add(c)
        }
        if (picked.isEmpty() && all.isNotEmpty()) picked.add(all.first())
        return picked
    }

    fun report(all: List<Candidate>, picked: List<Candidate>): String {
        val sb = StringBuilder("VIRALITY REPORT\n\n")
        for (c in all) {
            val mark = if (c in picked) "[PICKED]" else "        "
            sb.append(String.format(Locale.US, "%s %3d  %s  (%.0fs-%.0fs)\n         hook: %s\n         why: %s\n         caption: %s\n         tags: %s\n\n",
                mark, c.score, c.title, c.startMs / 1000.0, c.endMs / 1000.0, c.hook, c.reason,
                c.description, c.hashtags.joinToString(" ") { "#$it" }))
        }
        return sb.toString()
    }
}

/** BOT 3: voice actor, speaks the hook line (optional). */
object Voice {
    fun speak(s: Store, text: String, dir: File, name: String): File? {
        if (text.isBlank()) return null
        val body = JSONObject()
            .put("model", "tts-1")
            .put("voice", s.voice)
            .put("input", text.take(4000))
        val out = File(dir, name)
        out.writeBytes(Api.json("${s.baseUrl}/audio/speech", s.apiKey, body))
        return out
    }
}

/** BOT 5: thumbnail maker. Grabs a frame, center-crops to the ratio, adds bold outlined text. */
object Thumb {
    fun make(video: File, atMs: Long, text: String, out: File, w: Int, h: Int) {
        val r = MediaMetadataRetriever()
        val frame: Bitmap
        try {
            r.setDataSource(video.path)
            frame = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IOException("Could not grab a frame")
        } finally { r.release() }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val s = maxOf(w.toFloat() / frame.width, h.toFloat() / frame.height)
        val m = Matrix()
        m.postScale(s, s)
        m.postTranslate((w - frame.width * s) / 2f, (h - frame.height * s) / 2f)
        canvas.drawBitmap(frame, m, Paint(Paint.FILTER_BITMAP_FLAG))

        val shade = Paint().apply {
            shader = LinearGradient(0f, h * 0.5f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(190, 0, 0, 0), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, h * 0.5f, w.toFloat(), h.toFloat(), shade)

        val t = text.uppercase()
        val size = if (w > h) 96f else 88f
        fun paint(stroke: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            if (stroke) { style = Paint.Style.STROKE; strokeWidth = 16f; color = Color.BLACK; strokeJoin = Paint.Join.ROUND }
            else { style = Paint.Style.FILL; color = Color.WHITE }
        }
        for (stroke in listOf(true, false)) {
            val layout = StaticLayout.Builder.obtain(t, 0, t.length, paint(stroke), w - 120)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(4).build()
            canvas.save()
            canvas.translate(60f, h - 40f - layout.height)
            layout.draw(canvas)
            canvas.restore()
        }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }
}
