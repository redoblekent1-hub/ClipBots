package com.clipbots.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * BOT 4: editor. Cuts the segments, alternates a punch-in zoom between them,
 * crops/scales to the requested size (16:9 or 9:16), keeps the original audio,
 * and optionally lays a voiceover on top. Also extracts audio for transcription.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object Editor {
    private const val ZOOM = 1.15f

    private fun clip(src: File, start: Long, end: Long) = MediaItem.Builder()
        .setUri(Uri.fromFile(src))
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(start).setEndPositionMs(end).build()
        ).build()

    /** Runs a Transformer on the main looper and blocks the calling (background) thread until done. */
    private fun runTransformer(ctx: Context, audioOnly: Boolean, startIt: (Transformer) -> Unit) {
        val latch = CountDownLatch(1)
        var failure: Exception? = null
        Handler(Looper.getMainLooper()).post {
            try {
                val b = Transformer.Builder(ctx)
                if (audioOnly) b.setAudioMimeType(MimeTypes.AUDIO_AAC) else b.setVideoMimeType(MimeTypes.VIDEO_H264)
                val t = b.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        latch.countDown()
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        failure = exportException
                        latch.countDown()
                    }
                }).build()
                startIt(t)
            } catch (e: Exception) {
                failure = e
                latch.countDown()
            }
        }
        if (!latch.await(45, TimeUnit.MINUTES)) throw IOException("Render timed out")
        val f = failure
        if (f != null) throw f
    }

    fun extractAudio(ctx: Context, src: File, startMs: Long, endMs: Long, out: File) {
        val item = EditedMediaItem.Builder(clip(src, startMs, endMs)).setRemoveVideo(true).build()
        runTransformer(ctx, audioOnly = true) { it.start(item, out.absolutePath) }
    }

    fun render(ctx: Context, src: File, segments: List<Pair<Long, Long>>, voice: File?, out: File, w: Int, h: Int) {
        val items = segments.mapIndexed { i, (start, end) ->
            val fx = ArrayList<Effect>()
            if (i % 2 == 1) fx.add(ScaleAndRotateTransformation.Builder().setScale(ZOOM, ZOOM).build())
            fx.add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            EditedMediaItem.Builder(clip(src, start, end))
                .setEffects(Effects(emptyList(), fx))
                .build()
        }
        val sequences = ArrayList<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence(items))
        if (voice != null) {
            val v = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(voice))).setRemoveVideo(true).build()
            sequences.add(EditedMediaItemSequence(listOf(v)))
        }
        val composition = Composition.Builder(sequences).build()
        runTransformer(ctx, audioOnly = false) { it.start(composition, out.absolutePath) }
    }
}
