package com.example.movierecap

import android.util.Base64
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

/** Gemini API ရဲ့ TTS (Preview)။ ထွက်လာတာ raw PCM 24kHz mono 16-bit */
object GeminiTts {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile private var workingModel: String? = null

    suspend fun synthesize(
        apiKey: String,
        preferredModel: String,
        voice: String,
        text: String,
        log: (String) -> Unit = {}
    ): ByteArray = withContext(Dispatchers.IO) {
        val models = listOfNotNull(
            workingModel,
            preferredModel.trim().ifEmpty { null },
            "gemini-3.1-flash-tts-preview",
            "gemini-2.5-flash-preview-tts"
        ).distinct()

        var last = ""
        for (m in models) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val (code, body) = post(apiKey, m, voice, text)
                when {
                    code == 200 -> {
                        val pcm = extractPcm(body)
                        workingModel = m
                        return@withContext pcm
                    }
                    code == 404 -> {
                        last = "$m model မရှိပါ"
                        break
                    }
                    code == 429 || code == 503 -> {
                        last = "$m HTTP $code (quota/လူများ)"
                        if (attempt < 2) {
                            log("Gemini TTS quota ပြည့်နေလို့ ၂၀ စက္ကန့် စောင့်နေတယ်...")
                            delay(20_000)
                        }
                    }
                    else -> throw IOException("Gemini TTS HTTP $code: ${body.take(200)}")
                }
            }
        }
        throw IOException("Gemini TTS အလုပ်မလုပ်ပါ: $last")
    }

    private fun post(apiKey: String, model: String, voice: String, text: String): Pair<Int, String> {
        val prompt = "Read the following Burmese text aloud in a clear, natural movie-recap narrator voice. " +
                "Read only the text after the colon, nothing else: $text"
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
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
                        )
                    )
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

    private fun extractPcm(resp: String): ByteArray {
        val cands = JSONObject(resp).optJSONArray("candidates")
            ?: throw IOException("Gemini TTS ရလဒ်မှာ candidates မပါပါ")
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw IOException("Gemini TTS က အသံ မထုတ်ပေးပါ (finishReason: ${cands.getJSONObject(0).optString("finishReason")})")
        for (i in 0 until parts.length()) {
            val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
            val data = inline.optString("data")
            if (data.isNotEmpty()) return Base64.decode(data, Base64.DEFAULT)
        }
        throw IOException("Gemini TTS ရလဒ်ထဲမှာ အသံ မပါပါ")
    }
}
