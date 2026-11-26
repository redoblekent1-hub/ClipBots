package com.clipbots.app

import android.content.Context
import java.io.File

/** Long-form video in, ranked shorts out, then scheduled for posting. */
object Pipeline {
    private fun dims(p1080: Boolean, vertical: Boolean): Pair<Int, Int> {
        val long = if (p1080) 1920 else 1280
        val short = if (p1080) 1080 else 720
        return if (vertical) short to long else long to short
    }

    /** Splits a clip into ~5s pieces so the editor can alternate punch-in zooms. */
    private fun splitForZoom(start: Long, end: Long, step: Long = 5000): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var s = start
        while (s < end) {
            var e = minOf(end, s + step)
            if (end - e < 1500) e = end
            out.add(s to e)
            s = e
        }
        return out
    }

    fun run(ctx: Context, store: Store, link: String) {
        val root = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val stamp = System.currentTimeMillis()
        val work = File(root, "work/$stamp").apply { mkdirs() }
        val outDir = File(root, "ClipBots").apply { mkdirs() }

        try {
            BotLog.log("1/7 Ingest: downloading")
            val src = Ingest.download(link, work)
            val dur = Media.durationMs(src)
            BotLog.log("Video length: ${dur / 60000} min")

            BotLog.log("2/7 Writer: transcribing")
            val transcript = Transcriber.run(ctx, store, src, work, dur)

            BotLog.log("3/7 Virality scan")
            val all = Virality.scan(store, transcript, dur)
            if (all.isEmpty()) throw IllegalStateException("Scanner found no usable moments")
            val picked = Virality.select(all, store.clipCount, store.minScore)
            File(outDir, "report_$stamp.txt").writeText(Virality.report(all, picked))
            for (c in picked) BotLog.log("Picked score ${c.score}: ${c.title}")

            val ratios = ArrayList<Pair<String, Boolean>>()
            if (store.out916 || store.autoPost) ratios.add("9x16" to true)
            if (store.out169) ratios.add("16x9" to false)
            if (ratios.isEmpty()) ratios.add("9x16" to true)

            picked.forEachIndexed { i, c ->
                val rank = i + 1
                val voice = if (store.voiceover) {
                    BotLog.log("4/7 Voice: hook line for short $rank")
                    Voice.speak(store, c.hook, work, "voice$rank.mp3")
                } else null

                for ((tag, vertical) in ratios) {
                    val (w, h) = dims(store.p1080, vertical)
                    val base = "short_${stamp}_${rank}_score${c.score}_$tag"
                    val videoFile = File(outDir, "$base.mp4")
                    BotLog.log("5/7 Editor: short $rank as $tag (${w}x$h)")
                    Editor.render(ctx, src, splitForZoom(c.startMs, c.endMs), voice, videoFile, w, h)

                    BotLog.log("6/7 Thumbnail: short $rank $tag")
                    val tw = if (vertical) 720 else 1280
                    val th = if (vertical) 1280 else 720
                    Thumb.make(src, c.startMs + 1500, c.title, File(outDir, "$base.jpg"), tw, th)

                    if (vertical && store.autoPost) {
                        BotLog.log("7/7 Scheduler: queueing post")
                        Scheduler.enqueue(store, videoFile, c)
                    }
                }
            }
            BotLog.log("DONE: ${picked.size} short(s) saved in ${outDir.path}")
        } finally {
            work.deleteRecursively()
        }
    }
}
