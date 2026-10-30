package com.example.movierecap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Edge TTS မရတဲ့အခါ အသုံးပြုမယ့် အရန် (fallback) မြန်မာအသံ။ အမြန်နှုန်း/pitch ချိန်ညှိလို့မရပါ။ */
object GoogleTts {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=my&q=$q"
        val req = Request.Builder().url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            .header("Referer", "https://translate.google.com/")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Google TTS HTTP ${r.code}")
            val b = r.body?.bytes() ?: ByteArray(0)
            if (b.isEmpty()) throw IOException("Google TTS အသံ မရပါ")
            b
        }
    }
}
