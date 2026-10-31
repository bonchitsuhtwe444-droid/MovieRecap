package com.example.movierecap

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * FFmpeg နဲ့ scene တစ်ခုချင်းကို ဖြတ်၊ speed ချိန်ညှိ၊ ၃၀fps အသေပြောင်းပြီး
 * မြန်မာ narration နဲ့ ပေါင်းတယ်။
 */
object VideoMerger {

    data class Clip(val startMs: Long, val endMs: Long, val speed: Float = 1f)

    @Volatile var lastEncoder: String = ""

    private data class Enc(val name: String, val args: List<String>)

    private fun f(v: Double, digits: Int = 3): String = String.format(Locale.US, "%.${digits}f", v)

    private fun run(args: List<String>): Pair<Boolean, String> {
        val s = FFmpegKit.executeWithArguments(args.toTypedArray())
        val ok = ReturnCode.isSuccess(s.returnCode)
        val log = if (ok) "" else (s.allLogsAsString ?: s.output ?: "").takeLast(1500)
        return ok to log
    }

    private fun detectEncoders(): List<Enc> {
        val s = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-encoders"))
        val text = (s.output ?: s.allLogsAsString ?: "")
        val list = ArrayList<Enc>()
        if (Regex("\\blibx264\\b").containsMatchIn(text)) {
            list.add(Enc("libx264", listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p")))
        }
        if (text.contains("h264_mediacodec")) {
            list.add(Enc("h264_mediacodec", listOf("-c:v", "h264_mediacodec", "-b:v", "4M")))
        }
        list.add(Enc("mpeg4", listOf("-c:v", "mpeg4", "-q:v", "3", "-pix_fmt", "yuv420p")))
        return list
    }

    private fun encodeSegment(context: Context, videoUri: Uri, c: Clip, outFile: File, enc: Enc): Pair<Boolean, String> {
        val saf = FFmpegKitConfig.getSafParameterForRead(context, videoUri)
        val startSec = f(c.startMs / 1000.0)
        val lenSec = f((c.endMs - c.startMs) / 1000.0)
        val speed = c.speed.toDouble().coerceIn(0.25, 4.0)
        val vf = "setpts=(PTS-STARTPTS)/${f(speed, 4)},fps=30," +
                "scale='if(gt(iw,ih),-2,720)':'if(gt(iw,ih),720,-2)',format=yuv420p"
        val args = ArrayList<String>()
        args += listOf("-y", "-hide_banner", "-ss", startSec, "-t", lenSec, "-i", saf, "-an", "-sn", "-dn", "-vf", vf)
        args += enc.args
        args += outFile.absolutePath
        return run(args)
    }

    suspend fun merge(
        context: Context,
        videoUri: Uri,
        narration: File,
        clips: List<Clip>,
        out: File,
        onProgress: (Int) -> Unit = {}
    ) {
        withContext(Dispatchers.IO) {
            val work = File(context.cacheDir, "ff_work")
            work.deleteRecursively()
            work.mkdirs()

            val encoders = detectEncoders()
            var chosen: Enc? = null
            val segFiles = ArrayList<File>()

            for ((i, c) in clips.withIndex()) {
                val seg = File(work, "seg_%04d.mp4".format(i))
                if (chosen == null) {
                    var lastLog = ""
                    for (e in encoders) {
                        seg.delete()
                        val (ok, log) = encodeSegment(context, videoUri, c, seg, e)
                        if (ok && seg.length() > 0) {
                            chosen = e
                            break
                        }
                        lastLog = "[${e.name}] $log"
                    }
                    if (chosen == null) throw IOException("FFmpeg encoder တစ်ခုမှ အလုပ်မလုပ်ပါ:\n$lastLog")
                    lastEncoder = chosen.name
                } else {
                    val (ok, log) = encodeSegment(context, videoUri, c, seg, chosen)
                    if (!ok || seg.length() == 0L) throw IOException("Scene ${i + 1}/${clips.size} ဖြတ်လို့မရပါ:\n$log")
                }
                segFiles.add(seg)
                onProgress(((i + 1) * 90) / clips.size)
            }

            val list = File(work, "list.txt")
            list.writeText(segFiles.joinToString("\n") { "file '${it.absolutePath}'" } + "\n")

            out.delete()
            val (ok, log) = run(
                listOf(
                    "-y", "-hide_banner",
                    "-f", "concat", "-safe", "0", "-i", list.absolutePath,
                    "-i", narration.absolutePath,
                    "-map", "0:v:0", "-map", "1:a:0",
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "128k",
                    "-shortest", "-movflags", "+faststart",
                    out.absolutePath
                )
            )
            if (!ok || out.length() == 0L) throw IOException("ပေါင်းစပ်တာ မအောင်မြင်ပါ:\n$log")
            work.deleteRecursively()
            onProgress(100)
        }
    }
}
