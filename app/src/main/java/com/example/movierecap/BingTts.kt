package com.example.movierecap

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Bing Translator ရဲ့ TTS (Edge နဲ့ တူတဲ့ Microsoft neural မြန်မာအသံ Nilar/Thiha)။
 * speech.platform.bing.com WebSocket အစား www.bing.com ကို HTTPS POST နဲ့ ခေါ်တဲ့ အခြားလမ်းကြောင်း။
 * (တရားဝင် API မဟုတ်ပါ၊ စာမျက်နှာပုံစံ ပြောင်းရင် ရပ်သွားနိုင်တယ်)
 */
object BingTts {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0"

    private data class Tok(val ig: String, val iid: String, val key: String, val token: String, val at: Long)

    @Volatile private var tok: Tok? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun esc(s: String): String = s
        .filter { it == '\n' || it == '\t' || it >= ' ' }
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun fetchToken(): Tok {
        val req = Request.Builder().url("https://www.bing.com/translator")
            .header("User-Agent", UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Bing translator စာမျက်နှာ HTTP ${r.code}")
            val html = r.body?.string().orEmpty()
            val ig = Regex("IG:\"([A-Za-z0-9]+)\"").find(html)?.groupValues?.get(1)
                ?: throw IOException("Bing IG မတွေ့ပါ (စာမျက်နှာပုံစံ ပြောင်းနေနိုင်)")
            val m = Regex("params_AbusePreventionHelper\\s*=\\s*\\[\\s*(\\d+)\\s*,\\s*\"([^\"]+)\"").find(html)
                ?: throw IOException("Bing token မတွေ့ပါ (စာမျက်နှာပုံစံ ပြောင်းနေနိုင်)")
            val iid = Regex("data-iid=\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: "translator.5023.1"
            return Tok(ig, iid, m.groupValues[1], m.groupValues[2], System.currentTimeMillis())
        }
    }

    private fun post(t: Tok, ssml: String): ByteArray {
        val url = "https://www.bing.com/tfettts?isVertical=1&&IG=${t.ig}&IID=${t.iid}"
        val body = FormBody.Builder()
            .add("ssml", ssml)
            .add("token", t.token)
            .add("key", t.key)
            .build()
        val req = Request.Builder().url(url).post(body)
            .header("User-Agent", UA)
            .header("Referer", "https://www.bing.com/translator")
            .header("Origin", "https://www.bing.com")
            .build()
        http.newCall(req).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val ct = r.header("Content-Type").orEmpty()
            if (!r.isSuccessful || bytes.isEmpty() || !ct.contains("audio")) {
                val peek = String(bytes, 0, minOf(bytes.size, 150), Charsets.UTF_8)
                throw IOException("HTTP ${r.code} ($ct) $peek")
            }
            return bytes
        }
    }

    /** ဒီ ကွန်ရက်ရဲ့ IP နဲ့ နိုင်ငံ (VPN ထွက်ပေါက် စစ်ဖို့) */
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errs.add("${url.substringAfter("//").substringBefore('/')}: ${e.javaClass.simpleName}")
            }
        }
        "စစ်လို့မရ (${errs.joinToString(", ")})"
    }

    /** MP3 bytes ပြန်ပေးတယ် */
    suspend fun synthesize(text: String, voice: String): ByteArray = withContext(Dispatchers.IO) {
        var last = ""
        for (attempt in 0..1) {
            try {
                var t = tok
                if (t == null || attempt > 0 || System.currentTimeMillis() - t.at > 8 * 60 * 1000L) {
                    t = fetchToken()
                    tok = t
                }
                val gender = if (voice.contains("Thiha")) "Male" else "Female"
                val ssml = "<speak version='1.0' xml:lang='my-MM'>" +
                        "<voice xml:lang='my-MM' xml:gender='$gender' name='$voice'>" +
                        "<prosody rate='-0.00%'>${esc(text)}</prosody></voice></speak>"
                return@withContext post(t, ssml)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        throw IOException("Bing TTS မရပါ: $last")
    }
}
