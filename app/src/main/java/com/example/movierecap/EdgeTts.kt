package com.example.movierecap

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Microsoft Edge "Read Aloud" TTS (တရားဝင် API မဟုတ်ပါ)
 *
 * WebSocket ကို OkHttp နဲ့ မဟုတ်ဘဲ ကိုယ်တိုင်ရေးထားတဲ့ socket နဲ့ ချိတ်တယ်။
 * (OkHttp က permessage-deflate extension ကို အတင်းပို့လို့ 403 ဖြစ်နေနိုင်လို့ ရှောင်ထားတာ။)
 * မအောင်မြင်ရင် lastReport ထဲမှာ ဆာဗာက ပြန်ပေးတဲ့ header အပြည့်အစုံ ထည့်ပေးတယ်။
 */
object EdgeTts {
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WIN_EPOCH = 11_644_473_600L
    private const val HOST = "speech.platform.bing.com"
    private const val BASE_PATH = "/consumer/speech/synthesize/readaloud"
    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    private const val PROVEN_VERSION = "143.0.3650.75"
    private const val FALLBACK_1 = "154.0.4258.62"

    @Volatile private var skewSec = 0L
    @Volatile private var workingCfg: Cfg? = null
    @Volatile private var latestVersion: String? = null
    @Volatile private var fetched = false
    private val rnd = SecureRandom()

    @Volatile private var failedAt = 0L
    @Volatile var lastReport: String = ""

    private class Forbidden(val serverMs: Long?, val statusLine: String, val head: String) : IOException(statusLine)
    private class NoAudio(detail: String) : IOException(detail)

    /** style: 0 = Chrome/Edge header အစဉ်အတိုင်း, 1 = style0 + client-hints/fetch-metadata, 2 = အရင် "full", 3 = minimal */
    private data class Cfg(val version: String, val style: Int, val shortVoice: Boolean)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- versions
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
        http.newCall(req).execute().use { r ->
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

    private fun configs(override: String): List<Cfg> {
        if (!fetched) {
            latestVersion = fetchLatest()
            fetched = true
        }
        val versions = listOfNotNull(
            override.trim().ifEmpty { null },
            PROVEN_VERSION,
            latestVersion,
            FALLBACK_1
        ).distinct()
        val list = ArrayList<Cfg>()
        workingCfg?.let { list.add(it) }
        for ((idx, v) in versions.withIndex()) {
            val styles = if (idx == 0) 0..3 else 0..1
            for (st in styles) list.add(Cfg(v, st, false))
        }
        list.add(Cfg(versions.first(), 0, true))
        return list.distinct()
    }

    // --------------------------------------------------------------- public API
    suspend fun synthesize(
        text: String,
        voice: String,
        rate: String = "+0%",
        pitch: String = "+0Hz",
        versionOverride: String = "",
        force: Boolean = false
    ): ByteArray = withContext(Dispatchers.IO) {
        if (!force && failedAt != 0L && System.currentTimeMillis() - failedAt < 300_000L) {
            throw IOException("Edge TTS: ခဏအရင်က မအောင်မြင်ခဲ့လို့ ကျော်လိုက်တယ် (အသံစမ်းခလုတ်နဲ့ ပြန်စစ်လို့ရ)")
        }
        val rep = StringBuilder()
        rep.append("ဖုန်းအချိန် (UTC): ${jsDate()}\n")
        rep.append("Voice: $voice  rate=$rate  pitch=$pitch\n")
        rep.append("Transport: raw WebSocket (OkHttp မသုံး)\n")
        rep.append("ကွန်ရက်: ${ipInfo()}\n\n")
        var lastMsg = ""
        var headShown = false
        var netErrors = 0
        val cfgs = configs(versionOverride)

        for (cfg in cfgs) {
            var tries = 0
            val maxTries = if (cfg == cfgs.first()) 2 else 1
            while (tries < maxTries) {
                tries++
                val styleName = arrayOf("chrome", "chrome+hints", "full", "min")[cfg.style]
                val tag = "v${cfg.version} $styleName${if (cfg.shortVoice) " shortVoice" else ""}"
                try {
                    val bytes = once(text, voice, cfg, rate, pitch)
                    workingCfg = cfg
                    failedAt = 0L
                    lastReport = "OK $tag"
                    return@withContext bytes
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Forbidden) {
                    lastMsg = e.statusLine
                    val s = e.serverMs
                    if (s != null) skewSec = s / 1000 - System.currentTimeMillis() / 1000 else skewSec += 60
                    rep.append("$tag → ${e.statusLine} (skew=${skewSec}s)\n")
                    if (!headShown) {
                        rep.append("--- ဆာဗာ ပြန်ပေးတဲ့ header ---\n${e.head.take(900)}\n-----------------\n")
                        headShown = true
                    }
                    if (tries >= maxTries) break
                    delay(200)
                } catch (e: NoAudio) {
                    lastMsg = e.message.orEmpty()
                    rep.append("$tag → ${e.message}\n")
                    break
                } catch (e: Exception) {
                    // ကွန်ရက်/TLS ပြတ်တာ — နောက် config ကို ဆက်စမ်းမယ်၊ ၃ ခါဆက်တိုက်ဆို ရပ်မယ်
                    netErrors++
                    lastMsg = "${e.javaClass.simpleName}: ${e.message}"
                    rep.append("$tag → $lastMsg\n")
                    if (netErrors >= 3) {
                        lastReport = rep.toString()
                        failedAt = System.currentTimeMillis()
                        throw IOException("Edge TTS: $lastMsg")
                    }
                    delay(1500)
                    break
                }
            }
        }
        rep.append("\n").append(probeVoices(cfgs.first().version))
        lastReport = rep.toString()
        failedAt = System.currentTimeMillis()
        throw IOException("Edge TTS မအောင်မြင်ပါ: $lastMsg")
    }

    // ---------------------------------------------------------------- internals
    private fun gec(): String {
        var ticks = System.currentTimeMillis() / 1000 + skewSec + WIN_EPOCH
        ticks -= ticks % 300
        val s = (ticks * 10_000_000L).toString() + TOKEN
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.US_ASCII))
        return d.joinToString("") { "%02X".format(it) }
    }

    private fun hex(n: Int): String {
        val b = ByteArray(n)
        rnd.nextBytes(b)
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

    private fun probeVoices(version: String): String = try {
        val url = "https://$HOST$BASE_PATH/voices/list?trustedclienttoken=$TOKEN" +
                "&Sec-MS-GEC=${gec()}&Sec-MS-GEC-Version=1-$version"
        val req = Request.Builder().url(url)
            .header("User-Agent", ua(version))
            .header("Origin", ORIGIN)
            .header("Accept", "*/*")
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            "voices-list: HTTP ${r.code}, Burmese voice=${body.contains("my-MM")}, bytes=${body.length}"
        }
    } catch (e: Exception) {
        "voices-list: ${e.javaClass.simpleName}: ${e.message}"
    }

    /** ဒီ ကွန်ရက်ရဲ့ အများသုံး IP နဲ့ နိုင်ငံကို ပြတယ် (VPN ထွက်ပေါက် စစ်ဖို့) */
    suspend fun ipInfo(): String = withContext(Dispatchers.IO) {
        val errs = ArrayList<String>()
        for (url in listOf("https://ipinfo.io/json", "https://api.myip.com", "https://ipapi.co/json/")) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                val txt = http.newCall(req).execute().use { r -> r.body?.string().orEmpty() }
                val j = org.json.JSONObject(txt)
                val ip = j.optString("ip")
                val country = j.optString("country").ifEmpty { j.optString("cc") }
                if (ip.isNotEmpty()) {
                    return@withContext "$ip · $country · ${j.optString("city")} · ${j.optString("org")}".trim()
                }
            } catch (e: Exception) {
                errs.add("${url.substringAfter("//").substringBefore('/')}: ${e.javaClass.simpleName}")
            }
        }
        "စစ်လို့မရ (${errs.joinToString(", ")})"
    }

    // ------------------------------------------------------ raw WebSocket client
    private fun readHead(inp: InputStream): String {
        val bos = ByteArrayOutputStream()
        var last = 0
        while (bos.size() < 16_384) {
            val b = inp.read()
            if (b < 0) break
            bos.write(b)
            last = (last shl 8) or b
            if (last == 0x0D0A0D0A) break
        }
        return bos.toString("ISO-8859-1")
    }

    private fun serverDateMs(head: String): Long? {
        val line = head.lineSequence().firstOrNull { it.startsWith("Date:", ignoreCase = true) } ?: return null
        return try {
            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            f.parse(line.substringAfter(':').trim())?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun sendFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4)
        rnd.nextBytes(mask)
        val bos = ByteArrayOutputStream()
        bos.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> bos.write(0x80 or n)
            n <= 0xFFFF -> {
                bos.write(0x80 or 126)
                bos.write(n shr 8)
                bos.write(n and 0xFF)
            }
            else -> {
                bos.write(0x80 or 127)
                for (i in 7 downTo 0) bos.write(((n.toLong() shr (8 * i)) and 0xFF).toInt())
            }
        }
        bos.write(mask)
        val masked = ByteArray(n) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        bos.write(masked)
        out.write(bos.toByteArray())
        out.flush()
    }

    private fun once(text: String, voice: String, cfg: Cfg, rate: String, pitch: String): ByteArray {
        val path = "$BASE_PATH/edge/v1?TrustedClientToken=$TOKEN" +
                "&ConnectionId=${hex(16)}" +
                "&Sec-MS-GEC=${gec()}" +
                "&Sec-MS-GEC-Version=1-${cfg.version}"

        val plain = Socket()
        plain.connect(InetSocketAddress(HOST, 443), 20_000)
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(plain, HOST, 443, true) as SSLSocket
        ssl.soTimeout = 30_000
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val sp = ssl.sslParameters
                sp.applicationProtocols = arrayOf("http/1.1")
                ssl.sslParameters = sp
            }
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, ssl.session)) {
                throw IOException("TLS hostname မကိုက်ပါ")
            }
            val out = BufferedOutputStream(ssl.outputStream)
            val inp = DataInputStream(BufferedInputStream(ssl.inputStream))

            val keyBytes = ByteArray(16)
            rnd.nextBytes(keyBytes)
            val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

            val major = cfg.version.substringBefore('.')
            val req = StringBuilder()
            fun h(name: String, value: String) { req.append(name).append(": ").append(value).append("\r\n") }
            req.append("GET $path HTTP/1.1\r\n")
            when (cfg.style) {
                0, 1 -> {
                    // Chrome/Edge က extension ကနေ WebSocket ချိတ်တဲ့ header အစဉ်အတိုင်း
                    h("Host", HOST)
                    h("Connection", "Upgrade")
                    h("Pragma", "no-cache")
                    h("Cache-Control", "no-cache")
                    h("User-Agent", ua(cfg.version))
                    h("Upgrade", "websocket")
                    h("Origin", ORIGIN)
                    if (cfg.style == 1) {
                        h("Sec-CH-UA", "\"Microsoft Edge\";v=\"$major\", \"Chromium\";v=\"$major\", \"Not_A Brand\";v=\"24\"")
                        h("Sec-CH-UA-Mobile", "?0")
                        h("Sec-CH-UA-Platform", "\"Windows\"")
                        h("Sec-Fetch-Dest", "websocket")
                        h("Sec-Fetch-Mode", "websocket")
                        h("Sec-Fetch-Site", "cross-site")
                    }
                    h("Sec-WebSocket-Version", "13")
                    h("Accept-Encoding", "gzip, deflate, br, zstd")
                    h("Accept-Language", "en-US,en;q=0.9")
                    h("Cookie", "muid=${hex(16)};")
                    h("Sec-WebSocket-Key", key)
                }
                2 -> {
                    h("Host", HOST)
                    h("Connection", "Upgrade")
                    h("Upgrade", "websocket")
                    h("Sec-WebSocket-Version", "13")
                    h("Sec-WebSocket-Key", key)
                    h("Origin", ORIGIN)
                    h("User-Agent", ua(cfg.version))
                    h("Cookie", "muid=${hex(16)};")
                    h("Pragma", "no-cache")
                    h("Cache-Control", "no-cache")
                    h("Accept-Language", "en-US,en;q=0.9")
                    h("Accept-Encoding", "gzip, deflate, br, zstd")
                }
                else -> {
                    h("Host", HOST)
                    h("Connection", "Upgrade")
                    h("Upgrade", "websocket")
                    h("Sec-WebSocket-Version", "13")
                    h("Sec-WebSocket-Key", key)
                    h("Origin", ORIGIN)
                    h("User-Agent", ua(cfg.version))
                }
            }
            req.append("\r\n")
            out.write(req.toString().toByteArray(Charsets.US_ASCII))
            out.flush()

            val head = readHead(inp)
            val statusLine = head.lineSequence().firstOrNull().orEmpty().trim()
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            if (code != 101) {
                if (code == 403) throw Forbidden(serverDateMs(head), statusLine, head)
                if (statusLine.isEmpty()) throw NoAudio("ဆာဗာက handshake အဖြေ မပေးဘဲ connection ပိတ်လိုက်တယ်")
                throw IOException("Handshake မအောင်ပါ: $statusLine")
            }

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

            sendFrame(out, 0x1, configMsg.toByteArray(Charsets.UTF_8))
            sendFrame(out, 0x1, ssmlMsg.toByteArray(Charsets.UTF_8))

            val audio = ByteArrayOutputStream()
            val msgBuf = ByteArrayOutputStream()
            var msgOp = 0
            try {
                loop@ while (true) {
                    val b0 = inp.readUnsignedByte()
                    val b1 = inp.readUnsignedByte()
                    val fin = (b0 and 0x80) != 0
                    val op = b0 and 0x0F
                    var len = (b1 and 0x7F).toLong()
                    if (len == 126L) len = inp.readUnsignedShort().toLong()
                    else if (len == 127L) len = inp.readLong()
                    if (len < 0 || len > 16_000_000L) throw IOException("WebSocket frame အရွယ်အစား မမှန်ပါ")
                    val mk = if ((b1 and 0x80) != 0) ByteArray(4).also { inp.readFully(it) } else null
                    val data = ByteArray(len.toInt())
                    inp.readFully(data)
                    if (mk != null) for (i in data.indices) data[i] = (data[i].toInt() xor mk[i % 4].toInt()).toByte()

                    when (op) {
                        0x8 -> break@loop
                        0x9 -> sendFrame(out, 0xA, data)
                        0xA -> {}
                        else -> {
                            if (op != 0) {
                                msgOp = op
                                msgBuf.reset()
                            }
                            msgBuf.write(data)
                            if (fin) {
                                val full = msgBuf.toByteArray()
                                msgBuf.reset()
                                if (msgOp == 1) {
                                    if (String(full, Charsets.UTF_8).contains("Path:turn.end")) break@loop
                                } else if (msgOp == 2 && full.size >= 2) {
                                    val hl = ((full[0].toInt() and 0xFF) shl 8) or (full[1].toInt() and 0xFF)
                                    if (2 + hl <= full.size) {
                                        val header = String(full, 2, hl, Charsets.UTF_8)
                                        if (header.contains("Path:audio")) {
                                            audio.write(full, 2 + hl, full.size - 2 - hl)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: EOFException) {
                // connection ပိတ်သွားပြီ — အသံရပြီးသားဆိုရင် ဆက်သုံး
            }
            if (audio.size() == 0) throw NoAudio("turn.end ရောက်ပေမယ့် အသံ မပါပါ (voice နာမည်/SSML မှားနိုင်)")
            return audio.toByteArray()
        } finally {
            try {
                ssl.close()
            } catch (e: Exception) {
            }
        }
    }
}
