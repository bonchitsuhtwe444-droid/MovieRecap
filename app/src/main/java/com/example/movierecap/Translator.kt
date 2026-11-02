package com.example.movierecap

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Dub mode အတွက် စကားတစ်ကြောင်းချင်း မြန်မာပြန်တယ်။
 * Gemini quota ချွေတာဖို့: ၁။ စာသားပဲပို့ ၂။ batch ကြီးကြီး (၆၀ ကြောင်း) ၃။ ပြန်ပြီးသားကို cache ထဲသိမ်း
 * ၄။ Gemini မရ/quota ပြည့်ရင် Google Translate (key မလို) နဲ့ ဆက်လုပ်
 */
object Translator {
    data class Item(val id: Int, val sec: Double, val text: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun loadCache(f: File): JSONObject = try {
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    private fun freeTranslate(text: String, sl: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=my&dt=t&q=" +
                URLEncoder.encode(text, "UTF-8")
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Google Translate HTTP ${r.code}")
            val arr = JSONArray(r.body?.string().orEmpty()).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val seg = arr.optJSONArray(i) ?: continue
                sb.append(seg.optString(0, ""))
            }
            val t = sb.toString().trim()
            if (t.isEmpty()) throw IOException("Google Translate အလွတ်ပြန်တယ်")
            return t
        }
    }

    private suspend fun geminiBatch(
        apiKey: String,
        model: String,
        langName: String,
        batch: List<Item>,
        log: (String) -> Unit
    ): Map<Int, String> {
        val arr = JSONArray()
        for (it in batch) {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("seconds", Math.round(it.sec * 10) / 10.0)
                    .put("text", it.text.take(400))
            )
        }
        val prompt = """
You are translating movie dialogue from $langName into natural spoken Burmese (မြန်မာဘာသာ) for dubbing.
The lines come from speech recognition and may contain mistakes; use the surrounding lines for context.

For EVERY item write ONE Burmese line exactly as the character would say it (first-person direct speech, not narration).
- Keep the meaning, keep it short and natural. Keep names consistent across lines.
- Plain spoken Burmese only. No markdown, no emojis, no explanations.
- If a line is unusable noise, write a very short natural reaction.

Output ONLY a JSON array with the same ids: [{"id":0,"text":"..."}]

Lines:
$arr
""".trimIndent()
        val raw = GeminiClient.generateText(apiKey, model, prompt, log)
        val ids = batch.map { it.id }.toSet()
        return GeminiClient.parseIdTexts(raw).filterKeys { it in ids }
    }

    suspend fun translate(
        cacheFile: File,
        apiKey: String,
        model: String,
        langName: String,
        langCode: String,
        sl: String,
        items: List<Item>,
        freeOnly: Boolean,
        onProgress: (Int) -> Unit,
        log: (String) -> Unit
    ): List<String> {
        val cache = loadCache(cacheFile)
        val result = Array(items.size) { "" }
        fun ck(t: String) = "$langCode|$t"
        for (item in items) {
            val c = cache.optString(ck(item.text), "")
            if (c.isNotEmpty()) result[item.id] = c
        }
        fun pct() = result.count { it.isNotEmpty() } * 100 / maxOf(items.size, 1)
        onProgress(pct())

        if (!freeOnly) {
            val missing = items.filter { result[it.id].isEmpty() }
            var i = 0
            while (i < missing.size) {
                val batch = missing.subList(i, minOf(i + 60, missing.size))
                try {
                    val got = geminiBatch(apiKey, model, langName, batch, log)
                    for ((id, t) in got) {
                        if (id in items.indices && result[id].isEmpty()) {
                            result[id] = t
                            cache.put(ck(items[id].text), t)
                        }
                    }
                    cacheFile.writeText(cache.toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("⚠️ Gemini မရလို့ Google Translate နဲ့ ဆက်လုပ်မယ် — ${e.message?.take(80)}")
                    break
                }
                i += batch.size
                onProgress(pct())
            }
        }

        val rest = items.filter { result[it.id].isEmpty() }
        for ((n, item) in rest.withIndex()) {
            try {
                val t = withContext(Dispatchers.IO) { freeTranslate(item.text, sl) }
                result[item.id] = t
                cache.put(ck(item.text), t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ဒီစကားကြောင်းကို ကျော်
            }
            if (n % 10 == 9) cacheFile.writeText(cache.toString())
            onProgress(pct())
            delay(120)
        }
        cacheFile.writeText(cache.toString())
        return result.toList()
    }
}
