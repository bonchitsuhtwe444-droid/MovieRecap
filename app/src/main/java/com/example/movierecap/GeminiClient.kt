package com.example.movierecap

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object GeminiClient {

    data class Seg(val text: String, val start: Double, val end: Double)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    suspend fun writeScript(
        apiKey: String,
        preferredModel: String,
        prompt: String,
        log: (String) -> Unit
    ): List<Seg> = withContext(Dispatchers.IO) {
        val models = listOf(
            preferredModel.trim(),
            "gemini-flash-latest",
            "gemini-3-flash-preview",
            "gemini-3.1-flash-lite"
        ).filter { it.isNotEmpty() }.distinct()

        var lastErr = ""
        for (m in models) {
            var attempt = 0
            while (attempt < 3) {
                attempt++
                log("Gemini ($m) ကို ခေါ်နေပါတယ်...")
                val (code, resp) = post(apiKey, m, prompt)
                when {
                    code == 200 -> return@withContext parseSegments(extractText(resp))
                    code == 404 -> {
                        lastErr = "$m model မရှိတော့ပါ"
                        log("⚠️ $lastErr၊ နောက် model စမ်းမယ်")
                        break
                    }
                    code == 429 || code == 503 -> {
                        lastErr = "HTTP $code (quota/လူများနေ)"
                        log("⚠️ $lastErr၊ ${20 * attempt} စက္ကန့်စောင့်မယ်")
                        delay(20_000L * attempt)
                    }
                    code == 400 && resp.contains("location is not supported") -> throw IOException(
                        "Gemini က ဒီ ကွန်ရက်/VPN ရဲ့ နိုင်ငံကို ထောက်ပံ့မထားပါ။ VPN ကို US / Singapore / Japan လို ထောက်ပံ့တဲ့ နိုင်ငံကို ပြောင်းပြီး ပြန်စမ်းပါ။"
                    )
                    else -> throw IOException("Gemini HTTP $code: ${resp.take(300)}")
                }
            }
        }
        throw IOException("Gemini အလုပ်မလုပ်ပါ: $lastErr")
    }

    // ------------------------------------------------------------ batch translate
    suspend fun generateText(
        apiKey: String,
        preferredModel: String,
        prompt: String,
        log: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val models = listOf(
            preferredModel.trim(),
            "gemini-flash-latest",
            "gemini-3-flash-preview",
            "gemini-3.1-flash-lite"
        ).filter { it.isNotEmpty() }.distinct()

        var lastErr = ""
        for (m in models) {
            var attempt = 0
            while (attempt < 3) {
                attempt++
                val (code, resp) = post(apiKey, m, prompt)
                when {
                    code == 200 -> return@withContext extractText(resp)
                    code == 404 -> {
                        lastErr = "$m model မရှိတော့ပါ"
                        break
                    }
                    code == 429 || code == 503 -> {
                        lastErr = "HTTP $code (quota/လူများနေ)"
                        log("⚠️ Gemini $lastErr၊ ${20 * attempt} စက္ကန့်စောင့်မယ်")
                        delay(20_000L * attempt)
                    }
                    code == 400 && resp.contains("location is not supported") -> throw IOException(
                        "Gemini က ဒီ ကွန်ရက်/VPN ရဲ့ နိုင်ငံကို ထောက်ပံ့မထားပါ။ VPN ကို US / Singapore / Japan လို နိုင်ငံကို ပြောင်းပါ။"
                    )
                    else -> throw IOException("Gemini HTTP $code: ${resp.take(300)}")
                }
            }
        }
        throw IOException("Gemini အလုပ်မလုပ်ပါ: $lastErr")
    }

    fun parseIdTexts(raw: String): Map<Int, String> {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        val arr: JSONArray = if (t.startsWith("[")) {
            JSONArray(t)
        } else {
            val o = JSONObject(t)
            val key = o.keys().asSequence().firstOrNull { o.optJSONArray(it) != null }
                ?: throw IOException("Gemini ရလဒ်ထဲမှာ list မတွေ့ပါ")
            o.getJSONArray(key)
        }
        val map = HashMap<Int, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", -1)
            val text = o.optString("text", "").trim()
            if (id >= 0 && text.isNotEmpty()) map[id] = text
        }
        return map
    }

    /** ခန်းတစ်ခုချင်းစီကို မြန်မာ narration အဖြစ် ဘာသာပြန်/ပြန်ရေး (၂၅ ခန်းစီ batch) */
    suspend fun translateScenes(
        apiKey: String,
        model: String,
        langName: String,
        scenes: List<SceneSplitter.Scene>,
        onProgress: (Int) -> Unit,
        log: (String) -> Unit
    ): List<String> {
        val result = Array(scenes.size) { "" }
        val batch = 25
        var context = ""
        var start = 0
        while (start < scenes.size) {
            val end = minOf(start + batch, scenes.size)
            val items = JSONArray()
            for (i in start until end) {
                val sc = scenes[i]
                val sec = sc.endSec - sc.startSec
                val target = (sec * 10).toInt().coerceIn(15, 380)
                items.put(
                    JSONObject()
                        .put("id", i)
                        .put("seconds", Math.round(sec * 10) / 10.0)
                        .put("target_chars", target)
                        .put("text", sc.text.take(600))
                )
            }
            val prompt = """
You are a professional movie-recap narrator who writes natural spoken Burmese (မြန်မာဘာသာ).
Below is a JSON array of consecutive dialogue scenes from one movie. The dialogue is in $langName and comes from speech recognition, so it may contain mistakes; infer the meaning from context.

For EVERY item write ONE Burmese narration text:
- Tell what is said / what happens in that scene in an engaging recap voice (third-person narration is fine). Keep character names consistent.
- Length: about "target_chars" Burmese characters (never more than 450).
- Plain spoken Burmese only. No markdown, no emojis, no English except names.
- If the transcript is unusable noise, write one short neutral line (about 20 characters).
${if (context.isNotEmpty()) "Narration of the previous scenes (for continuity only, do not repeat): $context\n" else ""}
Output ONLY a JSON array with the same ids: [{"id":0,"text":"..."}]

Scenes:
$items
""".trimIndent()

            var ok = false
            var lastErr = ""
            for (attempt in 1..2) {
                try {
                    val raw = generateText(apiKey, model, prompt, log)
                    val map = parseIdTexts(raw)
                    for ((id, t) in map) if (id in start until end) result[id] = t
                    ok = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = "${e.javaClass.simpleName}: ${e.message}"
                    log("⚠️ Gemini ရလဒ် ဖတ်မရလို့ ပြန်စမ်းနေတယ်...")
                }
            }
            if (!ok) throw IOException("Gemini ရလဒ် ဖတ်မရပါ: $lastErr")
            context = result.slice(start until end).filter { it.isNotEmpty() }.takeLast(2).joinToString(" ").take(500)
            onProgress(end * 100 / scenes.size)
            start = end
        }
        return result.toList()
    }

    private fun post(apiKey: String, model: String, prompt: String): Pair<Int, String> {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("temperature", 0.8)
            )
            .toString()

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { r ->
            return r.code to (r.body?.string() ?: "")
        }
    }

    private fun extractText(resp: String): String {
        val parts = JSONObject(resp)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        return sb.toString()
    }

    private fun parseSegments(raw: String): List<Seg> {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        val arr: JSONArray = if (t.startsWith("[")) {
            JSONArray(t)
        } else {
            val o = JSONObject(t)
            val key = o.keys().asSequence().firstOrNull { o.optJSONArray(it) != null }
                ?: throw IOException("Gemini ရလဒ်ထဲမှာ segment list မတွေ့ပါ")
            o.getJSONArray(key)
        }
        val list = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Seg(
                o.optString("text", "").trim(),
                o.optDouble("start", -1.0),
                o.optDouble("end", -1.0)
            )
        }.filter { it.text.isNotEmpty() }
        if (list.isEmpty()) throw IOException("Gemini က script အလွတ် ပြန်ပေးတယ်")
        return list
    }
}
