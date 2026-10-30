package com.example.movierecap

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {

    private var videoUri: Uri? = null
    private var outUri: Uri? = null
    private var busy = false

    private val prefs by lazy { getSharedPreferences("recap", Context.MODE_PRIVATE) }
    private val modelDir by lazy { File(filesDir, "vosk_model") }

    private val etApiKey by lazy { findViewById<EditText>(R.id.etApiKey) }
    private val etTitle by lazy { findViewById<EditText>(R.id.etTitle) }
    private val etMinutes by lazy { findViewById<EditText>(R.id.etMinutes) }
    private val etTone by lazy { findViewById<EditText>(R.id.etTone) }
    private val etGeminiModel by lazy { findViewById<EditText>(R.id.etGeminiModel) }
    private val etEdgeVersion by lazy { findViewById<EditText>(R.id.etEdgeVersion) }
    private val spVoice by lazy { findViewById<Spinner>(R.id.spVoice) }
    private val spLang by lazy { findViewById<Spinner>(R.id.spLang) }

    // (code, ပြမယ့်နာမည်, vosk model နာမည်)
    private val langs = listOf(
        Triple("en", "English (အင်္ဂလိပ်)", "vosk-model-small-en-us-0.15"),
        Triple("cn", "Chinese (တရုတ်)", "vosk-model-small-cn-0.22"),
        Triple("fr", "French (ပြင်သစ်)", "vosk-model-small-fr-0.22"),
        Triple("de", "German (ဂျာမန်)", "vosk-model-small-de-0.15"),
        Triple("es", "Spanish (စပိန်)", "vosk-model-small-es-0.42"),
        Triple("ru", "Russian (ရုရှား)", "vosk-model-small-ru-0.22"),
        Triple("pt", "Portuguese (ပေါ်တူဂီ)", "vosk-model-small-pt-0.3")
    )
    private val btnModel by lazy { findViewById<Button>(R.id.btnModel) }
    private val btnVideo by lazy { findViewById<Button>(R.id.btnVideo) }
    private val btnGenerate by lazy { findViewById<Button>(R.id.btnGenerate) }
    private val btnOpen by lazy { findViewById<Button>(R.id.btnOpen) }
    private val tvModel by lazy { findViewById<TextView>(R.id.tvModel) }
    private val tvVideo by lazy { findViewById<TextView>(R.id.tvVideo) }
    private val tvStatus by lazy { findViewById<TextView>(R.id.tvStatus) }
    private val tvLog by lazy { findViewById<TextView>(R.id.tvLog) }
    private val progress by lazy { findViewById<ProgressBar>(R.id.progress) }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            videoUri = uri
            tvVideo.text = "ရွေးထားပြီ ✅"
            refreshButtons()
        }
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) installModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey.setText(prefs.getString("key", ""))
        etTitle.setText(prefs.getString("title", ""))
        etMinutes.setText(prefs.getString("minutes", "5"))
        etTone.setText(prefs.getString("tone", "စိတ်လှုပ်ရှားဖွယ်"))
        etGeminiModel.setText(prefs.getString("gmodel", "gemini-3-flash-preview"))
        etEdgeVersion.setText(prefs.getString("edgever", "143.0.3650.75"))

        spVoice.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Nilar (အမျိုးသမီးအသံ)", "Thiha (အမျိုးသားအသံ)")
        )
        spVoice.setSelection(prefs.getInt("voice", 0))

        spLang.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            langs.map { it.second } + "ကိုယ်တိုင်ရွေးထားတဲ့ model zip"
        )
        spLang.setSelection(prefs.getInt("lang", 0).coerceIn(0, langs.size))

        tvModel.text = if (VoskTranscriber.isModelReady(modelDir)) "Model ရှိပြီးသား ✅" else "Model ကို ပထမဆုံး run တဲ့အခါ အလိုအလျောက် ဒေါင်းပါမယ်"

        btnModel.setOnClickListener { pickModel.launch("*/*") }
        btnVideo.setOnClickListener { pickVideo.launch("video/*") }
        btnGenerate.setOnClickListener { startPipeline() }
        btnOpen.setOnClickListener {
            outUri?.let {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(it, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }
        refreshButtons()
    }

    private fun refreshButtons() {
        btnGenerate.isEnabled = !busy && videoUri != null
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("key", etApiKey.text.toString().trim())
            .putString("title", etTitle.text.toString())
            .putString("minutes", etMinutes.text.toString())
            .putString("tone", etTone.text.toString())
            .putString("gmodel", etGeminiModel.text.toString().trim())
            .putString("edgever", etEdgeVersion.text.toString().trim())
            .putInt("voice", spVoice.selectedItemPosition)
            .putInt("lang", spLang.selectedItemPosition)
            .apply()
    }

    private fun installModel(uri: Uri) {
        tvModel.text = "Model ဖြေနေပါတယ်... (ခဏစောင့်ပါ)"
        btnModel.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { VoskTranscriber.installModelZip(this@MainActivity, uri, modelDir) }
                tvModel.text = "Model ထည့်ပြီးပါပြီ ✅"
                prefs.edit().putString("model_lang", "custom").apply()
                spLang.setSelection(langs.size)
            } catch (e: Exception) {
                tvModel.text = "❌ ${e.message}"
            } finally {
                btnModel.isEnabled = true
                refreshButtons()
            }
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        btnModel.isEnabled = !b
        btnVideo.isEnabled = !b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        if (b) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshButtons()
    }

    private fun setStatus(s: String) = runOnUiThread { tvStatus.text = s }
    private fun updateProgress(p: Int) = runOnUiThread { progress.progress = p }
    private fun log(s: String) = runOnUiThread { tvLog.append(s + "\n") }

    private fun startPipeline() {
        val key = etApiKey.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, "Gemini API key ထည့်ပါ", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = videoUri ?: return
        savePrefs()
        tvLog.text = ""
        btnOpen.visibility = View.GONE
        setBusy(true)
        lifecycleScope.launch {
            try {
                runPipeline(key, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                setStatus("❌ Error")
                log("❌ ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun runPipeline(key: String, uri: Uri) {
        val minutes = etMinutes.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 5
        val title = etTitle.text.toString().trim().ifEmpty { "(မသိ)" }
        val tone = etTone.text.toString().trim().ifEmpty { "စိတ်လှုပ်ရှားဖွယ်" }
        val voice = if (spVoice.selectedItemPosition == 0) "my-MM-NilarNeural" else "my-MM-ThihaNeural"
        val edgeVer = etEdgeVersion.text.toString().trim().ifEmpty { "143.0.3650.75" }
        val gModel = etGeminiModel.text.toString().trim()

        // 0) Model မရှိသေးရင် ဒေါင်း
        ensureModel()

        // 1) Audio ထုတ်
        setStatus("၁/၅ အသံထုတ်နေပါတယ်...")
        updateProgress(0)
        val wav = File(cacheDir, "audio_16k.wav")
        withContext(Dispatchers.IO) {
            AudioExtractor.extractToWav(this@MainActivity, uri, wav) { updateProgress(it) }
        }

        // 2) Vosk
        setStatus("၂/၅ စာသားပြောင်းနေပါတယ် (မိနစ်များစွာ ကြာနိုင်)...")
        updateProgress(0)
        val transcript = withContext(Dispatchers.Default) {
            VoskTranscriber.transcribe(modelDir, wav) { updateProgress(it) }
        }
        wav.delete()
        if (transcript.isBlank()) error("စကားပြောစာသား မရပါ။ Model ဘာသာစကား ကိုက်မကိုက် စစ်ပါ")
        File(filesDir, "transcript.txt").writeText(transcript)
        log("✅ စာသား ${transcript.lines().size} လိုင်း ရပြီ")

        // 3) Gemini
        setStatus("၃/၅ Gemini က မြန်မာ script ရေးနေပါတယ်...")
        updateProgress(0)
        val clipped = if (transcript.length > 300_000) transcript.take(300_000) else transcript
        val prompt = buildPrompt(title, tone, minutes, clipped)
        val segs = GeminiClient.writeScript(key, gModel, prompt) { log(it) }
        val scriptText = segs.joinToString("\n\n") { it.text }
        File(filesDir, "script.txt").writeText(scriptText)
        log("✅ Script ${segs.size} ပိုင်း ရပြီ\n\n$scriptText\n")

        // 4) Edge TTS
        val narrationBytes = ByteArrayOutputStream()
        val durations = ArrayList<Long>()
        for ((i, s) in segs.withIndex()) {
            setStatus("၄/၅ မြန်မာအသံထုတ်နေပါတယ် ${i + 1}/${segs.size}")
            updateProgress((i * 100) / segs.size)
            val segBytes = ByteArrayOutputStream()
            for (chunk in chunkText(s.text)) {
                segBytes.write(EdgeTts.synthesize(chunk, voice, edgeVer))
                delay(200)
            }
            val bytes = segBytes.toByteArray()
            val tmp = File(cacheDir, "seg.mp3")
            tmp.writeBytes(bytes)
            durations += (mp3DurationMs(tmp) ?: (bytes.size * 1000L / 6000)).coerceAtLeast(1000L)
            narrationBytes.write(bytes)
        }
        val narration = File(cacheDir, "narration.mp3")
        narration.writeBytes(narrationBytes.toByteArray())
        log("✅ Narration ${durations.sum() / 1000} စက္ကန့် ရပြီ")

        // 5) ပေါင်း
        setStatus("၅/၅ ဗီဒီယိုနဲ့ ပေါင်းနေပါတယ် (မိနစ်အနည်းငယ် ကြာနိုင်)...")
        updateProgress(0)
        val movieMs = videoDurationMs(uri)
        val clips = segs.mapIndexed { i, s ->
            val dur = durations[i]
            var start = if (s.start >= 0) (s.start * 1000).toLong() else movieMs * i / segs.size
            if (movieMs > 0 && start + dur > movieMs) start = (movieMs - dur).coerceAtLeast(0)
            VideoMerger.Clip(start, start + dur)
        }
        val out = File(cacheDir, "recap_out.mp4")
        out.delete()
        VideoMerger.merge(this, uri, narration, clips, out)

        val where = withContext(Dispatchers.IO) { saveToMovies(out) }
        updateProgress(100)
        setStatus("✅ ပြီးပါပြီ")
        log("📁 သိမ်းထားတဲ့နေရာ: $where")
        if (outUri != null) btnOpen.visibility = View.VISIBLE
    }

    private suspend fun ensureModel() {
        val idx = spLang.selectedItemPosition
        if (idx >= langs.size) {
            if (!VoskTranscriber.isModelReady(modelDir)) error("Model zip မရွေးရသေးပါ")
            return
        }
        val (code, _, name) = langs[idx]
        if (VoskTranscriber.isModelReady(modelDir) && prefs.getString("model_lang", "") == code) return

        setStatus("Model ဒေါင်းနေပါတယ် (တစ်ကြိမ်ပဲ၊ ~၄၀MB)...")
        updateProgress(0)
        val zip = withContext(Dispatchers.IO) {
            VoskTranscriber.download(this@MainActivity, "https://alphacephei.com/vosk/models/$name.zip") {
                updateProgress(it)
            }
        }
        setStatus("Model ဖြေနေပါတယ်...")
        withContext(Dispatchers.IO) {
            VoskTranscriber.installModelZip(this@MainActivity, Uri.fromFile(zip), modelDir)
        }
        zip.delete()
        prefs.edit().putString("model_lang", code).apply()
        runOnUiThread { tvModel.text = "Model ရပြီ ✅ (${langs[idx].second})" }
        log("✅ Model ဒေါင်းပြီးပါပြီ")
    }

    private fun buildPrompt(title: String, tone: String, minutes: Int, transcript: String): String = """
You are a professional Burmese movie-recap narrator.
Movie title: $title

Below is the dialogue transcript of the movie with timestamps in seconds, formatted as [start-end] text.
The dialogue may be in English or another language and may contain speech-recognition errors. Infer the story from context.

Task: Write a recap narration of the whole movie in natural spoken Burmese (မြန်မာဘာသာ), in the style of popular movie-recap YouTube channels. Tone: $tone.
Rules:
- Total length: about $minutes minutes when read aloud (about ${minutes * 600} Burmese characters).
- Split the narration into 12 to 40 consecutive segments in story order.
- For each segment, give the start and end time in seconds of the part of the movie where those events happen, using the transcript timestamps.
- Plain spoken Burmese only. No bullet points, no markdown, no emojis, no English except character names.
Output ONLY a JSON array like: [{"text":"...","start":123,"end":170}]

Transcript:
$transcript
""".trimIndent()

    private fun chunkText(text: String, max: Int = 500): List<String> {
        val parts = text.split(Regex("(?<=[။.!?])")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (p in parts) {
            if (cur.isNotEmpty() && cur.length + p.length > max) {
                out.add(cur.toString())
                cur = StringBuilder()
            }
            if (p.length > max) {
                p.chunked(max).forEach { out.add(it) }
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(p)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun mp3DurationMs(f: File): Long? = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.absolutePath)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        r.release()
        d
    } catch (e: Exception) {
        null
    }

    private fun videoDurationMs(uri: Uri): Long = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, uri)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        d
    } catch (e: Exception) {
        0L
    }

    private fun saveToMovies(src: File): String {
        val name = "recap_${System.currentTimeMillis()}.mp4"
        return if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MovieRecap")
            }
            val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                ?: error("ဖိုင်သိမ်းလို့မရပါ")
            contentResolver.openOutputStream(u)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            outUri = u
            "Movies/MovieRecap/$name (Gallery ထဲမှာ ကြည့်ပါ)"
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
            val dest = File(dir, name)
            src.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }
}
