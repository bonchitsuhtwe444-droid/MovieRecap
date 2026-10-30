package com.example.movierecap

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object VoskTranscriber {


    /** Model zip ကို အင်တာနက်ကနေ ဒေါင်းတယ် */
    fun download(context: Context, url: String, onProgress: (Int) -> Unit): File {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val out = File(context.cacheDir, "vosk_model.zip")
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Model ဒေါင်းလို့မရပါ (HTTP ${r.code})")
            val body = r.body ?: throw IOException("Model ဖိုင် အလွတ်ဖြစ်နေတယ်")
            val total = body.contentLength()
            body.byteStream().use { ins ->
                FileOutputStream(out).use { o ->
                    val buf = ByteArray(65536)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    /** Model zip ကို ဖြေပြီး destDir ထဲ ထည့်ပေးတယ် */
    fun installModelZip(context: Context, zipUri: Uri, destDir: File) {
        val tmp = File(context.filesDir, "vosk_tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val tmpPath = tmp.canonicalPath

        context.contentResolver.openInputStream(zipUri)!!.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val out = File(tmp, e.name)
                    require(out.canonicalPath.startsWith(tmpPath)) { "zip ထဲက လမ်းကြောင်း မမှန်ပါ" }
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                    }
                    e = zis.nextEntry
                }
            }
        }

        val root = tmp.walkTopDown().firstOrNull {
            it.isDirectory && File(it, "am").exists() && File(it, "conf").exists()
        } ?: tmp.walkTopDown().firstOrNull {
            it.isDirectory && File(it, "conf").exists()
        } ?: error("Vosk model folder မတွေ့ပါ၊ zip မှန်မမှန် စစ်ပါ")

        destDir.deleteRecursively()
        if (!root.renameTo(destDir)) {
            root.copyRecursively(destDir, overwrite = true)
        }
        tmp.deleteRecursively()
    }

    fun isModelReady(dir: File): Boolean =
        dir.isDirectory && File(dir, "conf").exists()

    /** 16kHz mono WAV ကို စာသားပြောင်းပြီး "[စတင်-ပြီး] စာသား" လိုင်းတွေ ပြန်ပေးတယ် */
    fun transcribe(modelDir: File, wav: File, onProgress: (Int) -> Unit): String {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val model = Model(modelDir.absolutePath)
        val rec = Recognizer(model, 16000.0f)
        val sb = StringBuilder()
        try {
            val total = (wav.length() - 44).coerceAtLeast(1)
            var segStart = 0.0
            var read = 0L
            FileInputStream(wav).use { ins ->
                ins.skip(44)
                val buf = ByteArray(8000)
                var lastPct = -1
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    read += n
                    val now = read / 32000.0
                    if (rec.acceptWaveForm(buf, n)) {
                        val t = JSONObject(rec.result).optString("text")
                        if (t.isNotBlank()) {
                            sb.append("[${segStart.toInt()}-${now.toInt()}] $t\n")
                        }
                        segStart = now
                    }
                    val pct = (read * 100 / total).toInt().coerceIn(0, 100)
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct)
                    }
                }
            }
            val last = JSONObject(rec.finalResult).optString("text")
            if (last.isNotBlank()) {
                sb.append("[${segStart.toInt()}-${(read / 32000.0).toInt()}] $last\n")
            }
        } finally {
            rec.close()
            model.close()
        }
        return sb.toString()
    }
}
