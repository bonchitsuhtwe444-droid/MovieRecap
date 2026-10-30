package com.example.movierecap

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Microsoft Edge "Read Aloud" TTS (တရားဝင် API မဟုတ်ပါ)
 * မအောင်မြင်ရင် lastReport ထဲမှာ အကြောင်းရင်းအသေးစိတ် ထည့်ပေးတယ်။
 */
object EdgeTts {
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WIN_EPOCH = 11_644_473_600L
    private const val HOST = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    private const val FALLBACK_1 = "154.0.4258.62"
    private const val FALLBACK_2 = "143.0.3650.75"

    @Volatile private var skewSec = 0L
    @Volatile private var workingCfg: Cfg? = null
    @Volatile private var latestVersion: String? = null
    @Volatile private var fetched = false

    /** နောက်ဆုံး မအောင်မြင်မှုရဲ့ အသေးစိတ် (UI မှာ ပြဖို့) */
    @Volatile var lastReport: String = ""

    private class Forbidden(val serverMs: Long?, detail: String) : IOException(detail)
    private class NoAudio(detail: String) : IOException(detail)

    private data class Cfg(val version: String, val minimal: Boolean, val shortVoice: Boolean)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ---------- version discovery ----------
    private fun cmp(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p.compareTo(q)
        }
        return 0
    }

    private fun fetchLatest(): String? = try {
        val req = Request.Builder()
            .url("https://edgeupdates.microsoft.com/api/products")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                null
            } else {
                val arr = JSONArray(r.body?.string().orEmpty())
                var best: String? = null
                val re = Regex("""\d+\.\d+\.\d+\.\d+""")
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    if (p.optString("Product") != "Stable") continue
                    val rel = p.optJSONArray("Releases") ?: continue
                    for (j in 0 until rel.length()) {
                        val v = rel.getJSONObject(j).optString("ProductVersion")
                        if (re.matches(v) && (best == null || cmp(v, best) > 0)) best = v
                    }
                }
                best
            }
        }
    } catch (e: Exception) {
        null
    }

    private suspend fun configs(override: String): List<Cfg> {
        if (!fetched) {
            latestVersion = withContext(Dispatchers.IO) { fetchLatest() }
            fetched = true
        }
        val versions = listOfNotNull(
            override.trim().ifEmpty { null },
            latestVersion,
            FALLBACK_1,
            FALLBACK_2
        ).distinct()
        val list = ArrayList<Cfg>()
        workingCfg?.let { list.add(it) }
        for (v in versions) {
            list.add(Cfg(v, false, false))
            list.add(Cfg(v, true, false))
        }
        list.add(Cfg(versions.first(), false, true))
        list.add(Cfg(versions.first(), true, true))
        return list.distinct()
    }

    // ---------- public API ----------
    suspend fun synthesize(
        text: String,
        voice: String,
        rate: String = "+0%",
        pitch: String = "+0Hz",
        versionOverride: String = ""
    ): ByteArray {
        val rep = StringBuilder()
        rep.append("ဖုန်းအချိန် (UTC): ${jsDate()}\n")
        rep.append("Voice: $voice  rate=$rate  pitch=$pitch\n\n")
        var lastMsg = ""
        val cfgs = configs(versionOverride)

        for (cfg in cfgs) {
            var tries = 0
            while (tries < 2) {
                tries++
                val tag = "v${cfg.version}${if (cfg.minimal) " min" else ""}${if (cfg.shortVoice) " shortVoice" else ""}"
                try {
                    val bytes = once(text, voice, cfg, rate, pitch)
                    workingCfg = cfg
                    lastReport = "OK $tag"
                    return bytes
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Forbidden) {
                    lastMsg = e.message.orEmpty()
                    val s = e.serverMs
                    if (s != null) skewSec = s / 1000 - System.currentTimeMillis() / 1000 else skewSec += 60
                    rep.append("$tag → ${e.message}  (skew=${skewSec}s)\n")
                    if (tries >= 2) break
                    delay(200)
                } catch (e: NoAudio) {
                    lastMsg = e.message.orEmpty()
                    rep.append("$tag → ${e.message}\n")
                    break
                } catch (e: Exception) {
                    // ကွန်ရက်/TLS/DNS ပြဿနာ — ဆက်စမ်းလို့မရ
                    rep.append("$tag → ${e.javaClass.simpleName}: ${e.message}\n")
                    lastReport = rep.toString()
                    throw IOException("Edge TTS: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        rep.append("\n").append(probeVoices(cfgs.first().version))
        lastReport = rep.toString()
        throw IOException("Edge TTS မအောင်မြင်ပါ: $lastMsg")
    }

    // ---------- internals ----------
    private fun gec(): String {
        var ticks = System.currentTimeMillis() / 1000 + skewSec + WIN_EPOCH
        ticks -= ticks % 300
        val s = (ticks * 10_000_000L).toString() + TOKEN
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.US_ASCII))
        return d.joinToString("") { "%02X".format(it) }
    }

    private fun hex(n: Int): String {
        val b = ByteArray(n)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02X".format(it) }
    }

    private fun jsDate(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date()) + " GMT+0000 (Coordinated Universal Time)"
    }

    private fun ua(version: String): String {
        val major = version.substringBefore('.')
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$major.0.0.0 Safari/537.36 Edg/$major.0.0.0"
    }

    private fun longVoice(short: String): String {
        val p = short.split("-")
        if (p.size < 3) return short
        return "Microsoft Server Speech Text to Speech Voice (${p[0]}-${p[1]}, ${p.drop(2).joinToString("-")})"
    }

    private fun esc(s: String): String = s
        .filter { it == '\n' || it == '\t' || it >= ' ' }
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private suspend fun probeVoices(version: String): String = withContext(Dispatchers.IO) {
        try {
            val url = "https://$HOST/voices/list?trustedclienttoken=$TOKEN" +
                    "&Sec-MS-GEC=${gec()}&Sec-MS-GEC-Version=1-$version"
            val req = Request.Builder().url(url)
                .header("User-Agent", ua(version))
                .header("Origin", ORIGIN)
                .header("Accept", "*/*")
                .build()
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                "voices-list: HTTP ${r.code}, serverDate=${r.header("Date")}, " +
                        "Burmese voice=${body.contains("my-MM")}, bytes=${body.length}"
            }
        } catch (e: Exception) {
            "voices-list: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun once(
        text: String,
        voice: String,
        cfg: Cfg,
        rate: String,
        pitch: String
    ): ByteArray = suspendCancellableCoroutine { cont ->
        val url = "wss://$HOST/edge/v1?TrustedClientToken=$TOKEN" +
                "&ConnectionId=${hex(16)}" +
                "&Sec-MS-GEC=${gec()}" +
                "&Sec-MS-GEC-Version=1-${cfg.version}"

        val rb = Request.Builder().url(url)
            .header("Origin", ORIGIN)
            .header("User-Agent", ua(cfg.version))
        if (!cfg.minimal) {
            rb.header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=${hex(16)};")
        }
        val req = rb.build()

        val audio = ByteArrayOutputStream()
        val finished = AtomicBoolean(false)

        val configMsg = "X-Timestamp:${jsDate()}\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

        val voiceName = if (cfg.shortVoice) voice else longVoice(voice)
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='$voiceName'>" +
                "<prosody pitch='$pitch' rate='$rate' volume='+0%'>${esc(text)}</prosody>" +
                "</voice></speak>"

        val ssmlMsg = "X-RequestId:${hex(16)}\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:${jsDate()}Z\r\n" +
                "Path:ssml\r\n\r\n" + ssml

        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(configMsg)
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    if (finished.compareAndSet(false, true)) {
                        if (audio.size() > 0) cont.resume(audio.toByteArray())
                        else cont.resumeWithException(NoAudio("turn.end ရောက်ပေမယ့် အသံ မပါပါ"))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val hl = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                if (2 + hl > b.size) return
                val header = String(b, 2, hl, Charsets.UTF_8)
                if (header.contains("Path:audio")) {
                    audio.write(b, 2 + hl, b.size - 2 - hl)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!finished.compareAndSet(false, true)) return
                if (response != null) {
                    val body = try {
                        response.body?.string()?.take(160).orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                    val date = response.header("Date")
                    val detail = "HTTP ${response.code} (serverDate=$date) $body".trim()
                    if (response.code == 403) {
                        cont.resumeWithException(Forbidden(response.headers.getDate("Date")?.time, detail))
                    } else {
                        cont.resumeWithException(IOException(detail))
                    }
                } else {
                    cont.resumeWithException(t)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (finished.compareAndSet(false, true)) {
                    if (audio.size() > 0) cont.resume(audio.toByteArray())
                    else cont.resumeWithException(NoAudio("connection ပိတ်သွားတယ် (code $code $reason)"))
                }
            }
        })
        cont.invokeOnCancellation { ws.cancel() }
    }
}
