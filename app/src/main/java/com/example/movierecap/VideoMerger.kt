package com.example.movierecap

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object VideoMerger {

    data class Clip(val startMs: Long, val endMs: Long, val speed: Float = 1f)

    /** ဇာတ်ကားကနေ clip တွေဖြတ်ပြီး (မူရင်းအသံဖယ်) မြန်မာ narration နဲ့ ပေါင်းတယ် */
    suspend fun merge(
        context: Context,
        videoUri: Uri,
        narration: File,
        clips: List<Clip>,
        out: File
    ) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val videoItems = clips.map { c ->
                    val mi = MediaItem.Builder()
                        .setUri(videoUri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(c.startMs)
                                .setEndPositionMs(c.endMs)
                                .build()
                        )
                        .build()
                    val videoEffects = if (kotlin.math.abs(c.speed - 1f) < 0.04f) {
                        listOf<Effect>(Presentation.createForHeight(720))
                    } else {
                        listOf<Effect>(SpeedChangeEffect(c.speed), Presentation.createForHeight(720))
                    }
                    EditedMediaItem.Builder(mi)
                        .setRemoveAudio(true)
                        .setEffects(Effects(emptyList(), videoEffects))
                        .build()
                }

                val audioItem = EditedMediaItem.Builder(
                    MediaItem.fromUri(Uri.fromFile(narration))
                ).build()

                val composition = Composition.Builder(
                    listOf(
                        EditedMediaItemSequence(videoItems),
                        EditedMediaItemSequence(listOf(audioItem))
                    )
                ).build()

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()

                transformer.start(composition, out.absolutePath)
                cont.invokeOnCancellation { transformer.cancel() }
            }
        }
    }
}
