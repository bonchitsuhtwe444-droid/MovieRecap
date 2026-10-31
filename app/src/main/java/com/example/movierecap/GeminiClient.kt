package com.example.movierecap

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
