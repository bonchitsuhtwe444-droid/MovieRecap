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
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

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
        list.add(Enc("mpeg4", listOf("-c:v", "mpeg4", "-q:v", "2", "-g", "60", "-pix_fmt", "yuv420p")))
        return list
    }

    private fun encodeSegment(context: Context, videoUri: Uri, c: Clip, outFile: File, enc: Enc, smooth: Boolean): Pair<Boolean, String> {
        val saf = FFmpegKitConfig.getSafParameterForRead(context, videoUri)
        val startSec = f(c.startMs / 1000.0)
        val lenSec = f((c.endMs - c.startMs) / 1000.0)
        val speed = c.speed.toDouble().coerceIn(0.25, 4.0)
        val rateFilter = if (smooth) "framerate=fps=30:interp_start=0:interp_end=255:scene=14" else "fps=30"
        val vf = "setpts=(PTS-STARTPTS)/${f(speed, 4)},$rateFilter," +
                "scale='if(gt(iw,ih),-2,720)':'if(gt(iw,ih),720,-2)',format=yuv420p"
        val args = ArrayList<String>()
        args += listOf("-y", "-hide_banner", "-ss", startSec, "-t", lenSec, "-i", saf, "-an", "-sn", "-dn", "-vf", vf)
        args += enc.args
        args += outFile.absolutePath
        return run(args)
    }

    /**
     * မူရင်း video ထဲမှာ စက္ကန့်တိုင်း ပုံအသစ် ဘယ်နှပုံ တကယ်ပြောင်းလဲလဲ (ထပ်နေတဲ့ frame မရေ) ခန့်မှန်းတယ်။
     * ၂ မှ ၅ fps လောက်ပဲရှိရင် မူရင်းကိုယ်တိုင် ထစ်နေတာ။
     */
    suspend fun sourceUniqueFps(context: Context, uri: Uri, movieMs: Long): Double? = withContext(Dispatchers.IO) {
        try {
            var best: Double? = null
            val starts = if (movieMs > 20_000L) listOf(movieMs * 0.25, movieMs * 0.6) else listOf(0.0)
            val sec = 5.0
            for (st in starts) {
                val saf = FFmpegKitConfig.getSafParameterForRead(context, uri)
                val s = FFmpegKit.executeWithArguments(
                    arrayOf(
                        "-hide_banner", "-ss", f(st / 1000.0), "-t", f(sec), "-i", saf,
                        "-an", "-sn", "-dn", "-vf", "mpdecimate,showinfo", "-f", "null", "-"
                    )
                )
                val log = s.allLogsAsString ?: ""
                val n = Regex("Parsed_showinfo_\\d+ @ [^\\]]+\\]\\s+n:\\s*\\d+").findAll(log).count()
                if (n > 0) {
                    val fps = n / sec
                    best = maxOf(best ?: 0.0, fps)
                }
            }
            best
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- audio tools
    private fun audioFilter(speed: Double, semitones: Double): String {
        val needPitch = abs(semitones) > 0.05
        val needSpeed = abs(speed - 1.0) > 0.02
        if (!needPitch && !needSpeed) return ""
        val r = 2.0.pow(semitones / 12.0)
        val parts = ArrayList<String>()
        parts.add("aresample=24000")
        if (needPitch) {
            parts.add("asetrate=${(24000 * r).roundToInt()}")
            parts.add("aresample=24000")
        }
        var tempo = speed / (if (needPitch) r else 1.0)
        while (tempo > 2.0) {
            parts.add("atempo=2.0")
            tempo /= 2.0
        }
        while (tempo < 0.5) {
            parts.add("atempo=0.5")
            tempo /= 0.5
        }
        if (abs(tempo - 1.0) > 0.001) parts.add("atempo=${f(tempo, 4)}")
        return parts.joinToString(",")
    }

    /** mp3 / pcm(24kHz mono s16le) ကို အမြန်နှုန်း/pitch ချိန်ညှိပြီး PCM 24kHz mono s16le အဖြစ် ပြန်ပေးတယ် */
    suspend fun toPcm(context: Context, data: ByteArray, ext: String, speed: Double, semitones: Double): ByteArray =
        withContext(Dispatchers.IO) {
            val inF = File(context.cacheDir, "tts_in.$ext")
            val outF = File(context.cacheDir, "tts_out.pcm")
            inF.writeBytes(data)
            outF.delete()
            val args = ArrayList<String>()
            args += listOf("-y", "-hide_banner")
            if (ext == "pcm") args += listOf("-f", "s16le", "-ar", "24000", "-ac", "1")
            args += listOf("-i", inF.absolutePath)
            val af = audioFilter(speed, semitones)
            if (af.isNotEmpty()) args += listOf("-af", af)
            args += listOf("-f", "s16le", "-ar", "24000", "-ac", "1", outF.absolutePath)
            val (ok, log) = run(args)
            if (!ok || outF.length() == 0L) throw IOException("အသံ ပြောင်းလို့မရပါ:\n$log")
            val bytes = outF.readBytes()
            inF.delete()
            outF.delete()
            bytes
        }

    fun writeWav24k(out: File, pcm: ByteArray) {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt(36 + pcm.size)
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)
        h.putShort(1)
        h.putShort(1)
        h.putInt(24000)
        h.putInt(48000)
        h.putShort(2)
        h.putShort(16)
        h.put("data".toByteArray())
        h.putInt(pcm.size)
        out.outputStream().use {
            it.write(h.array())
            it.write(pcm)
        }
    }

    suspend fun merge(
        context: Context,
        videoUri: Uri,
        narration: File,
        clips: List<Clip>,
        out: File,
        smooth: Boolean = false,
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
                        val (ok, log) = encodeSegment(context, videoUri, c, seg, e, smooth)
                        if (ok && seg.length() > 0) {
                            chosen = e
                            break
                        }
                        lastLog = "[${e.name}] $log"
                    }
                    if (chosen == null) throw IOException("FFmpeg encoder တစ်ခုမှ အလုပ်မလုပ်ပါ:\n$lastLog")
                    lastEncoder = chosen.name
                } else {
                    val (ok, log) = encodeSegment(context, videoUri, c, seg, chosen, smooth)
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
