package com.example.movierecap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** DS Recap Tool — Developed by Dexter Syntax */
class LogoView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val u = minOf(width, height) / 108f
        c.save()
        c.scale(u, u)

        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 108f, 108f, Color.parseColor("#3A3D47"), Color.parseColor("#14151A"), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, 108f, 108f), 24f, 24f, p)

        val inner = Path().apply {
            moveTo(54f, 25f); lineTo(79f, 39.5f); lineTo(79f, 68.5f)
            lineTo(54f, 83f); lineTo(29f, 68.5f); lineTo(29f, 39.5f); close()
        }
        p.shader = null
        p.color = Color.parseColor("#33FFFFFF")
        c.drawPath(inner, p)

        val ring = Path().apply {
            moveTo(54f, 22f); lineTo(81.7f, 38f); lineTo(81.7f, 70f)
            lineTo(54f, 86f); lineTo(26.3f, 70f); lineTo(26.3f, 38f); close()
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.strokeJoin = Paint.Join.ROUND
        p.shader = LinearGradient(26f, 22f, 82f, 86f, Color.WHITE, Color.parseColor("#B5B9C4"), Shader.TileMode.CLAMP)
        c.drawPath(ring, p)

        val d = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            moveTo(33f, 40f); lineTo(40f, 40f)
            arcTo(RectF(30f, 40f, 50f, 68f), -90f, 180f)
            lineTo(33f, 68f); close()
            moveTo(38f, 46f); lineTo(38f, 62f); lineTo(46f, 54f); close()
        }
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 40f, 0f, 68f, Color.WHITE, Color.parseColor("#C4C8D2"), Shader.TileMode.CLAMP)
        c.drawPath(d, p)

        val s = Path().apply {
            moveTo(73f, 42.5f); lineTo(58.5f, 42.5f); lineTo(58.5f, 52f)
            lineTo(71f, 52f); lineTo(71f, 65.5f); lineTo(56f, 65.5f)
        }
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4.5f
        p.strokeCap = Paint.Cap.SQUARE
        p.strokeJoin = Paint.Join.MITER
        p.color = Color.parseColor("#EDEFF3")
        c.drawPath(s, p)
        c.restore()
    }
}

class MainActivity : AppCompatActivity() {

    private companion object {
        val C_CHAR = Color.parseColor("#1F2127")
        val C_CHAR2 = Color.parseColor("#3A3D47")
        val C_BG1 = Color.parseColor("#F5F6F8")
        val C_BG2 = Color.parseColor("#DEDFE5")
        val C_STROKE = Color.parseColor("#D0D3DA")
        val C_INPUT = Color.parseColor("#F4F5F8")
        val C_TEXT = Color.parseColor("#1F2127")
        val C_TEXT2 = Color.parseColor("#6B6F7B")
        val C_ACCENT = Color.parseColor("#4A5062")
        val C_PLAT = Color.parseColor("#F2F3F6")
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private var videoUri: Uri? = null
    private var outUri: Uri? = null
    private var busy = false
    private var player: MediaPlayer? = null

    private val prefs by lazy { getSharedPreferences("recap", Context.MODE_PRIVATE) }
    private val modelDir by lazy { File(filesDir, "vosk_model") }

    private lateinit var etApiKey: EditText
    private lateinit var spLang: Spinner
    private lateinit var spVoice: Spinner
    private lateinit var sbSpeed: SeekBar
    private lateinit var sbPitch: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var tvPitch: TextView
    private lateinit var btnPreview: Button
    private lateinit var btnVideo: Button
    private lateinit var tvVideo: TextView
    private lateinit var btnGenerate: Button
    private lateinit var btnOpen: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvScript: TextView
    private lateinit var btnSettings: TextView

    // (code, ပြမယ့်နာမည်, vosk model နာမည်)
    private val langs = listOf(
        Triple("en", "English", "vosk-model-small-en-us-0.15"),
        Triple("cn", "Chinese", "vosk-model-small-cn-0.22"),
        Triple("fr", "French", "vosk-model-small-fr-0.22"),
        Triple("de", "German", "vosk-model-small-de-0.15"),
        Triple("es", "Spanish", "vosk-model-small-es-0.42"),
        Triple("ru", "Russian", "vosk-model-small-ru-0.22"),
        Triple("pt", "Portuguese", "vosk-model-small-pt-0.3")
    )

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            videoUri = uri
            tvVideo.text = fileName(uri)
            refreshButtons()
        }
    }

    // ------------------------------------------------------------------ UI
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun rounded(color: Int, radius: Int, strokeW: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeW > 0) setStroke(strokeW, strokeColor)
        }

    private fun grad(c1: Int, c2: Int, radius: Int, strokeW: Int = 0, strokeColor: Int = 0) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2)).apply {
            cornerRadius = radius.toFloat()
            if (strokeW > 0) setStroke(strokeW, strokeColor)
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, dp(16), dp(1), C_STROKE)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 10f
        setTextColor(C_TEXT2)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
    }

    private fun styledButton(text: String, primary: Boolean): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.stateListAnimator = null
        b.textSize = 14f
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(if (primary) C_PLAT else C_TEXT)
        val normal = if (primary) grad(C_CHAR2, C_CHAR, dp(14))
        else grad(Color.WHITE, Color.parseColor("#E3E4E9"), dp(14), dp(1), Color.parseColor("#C5C8D0"))
        val pressed = if (primary) grad(C_CHAR, Color.BLACK, dp(14))
        else grad(Color.parseColor("#E3E4E9"), Color.parseColor("#CFD1D8"), dp(14), dp(1), Color.parseColor("#C5C8D0"))
        val disabled = rounded(Color.parseColor("#C9CBD3"), dp(14))
        val sl = StateListDrawable()
        sl.addState(intArrayOf(-android.R.attr.state_enabled), disabled)
        sl.addState(intArrayOf(android.R.attr.state_pressed), pressed)
        sl.addState(intArrayOf(), normal)
        b.background = sl
        b.setPadding(dp(14), 0, dp(14), 0)
        return b
    }

    private fun makeAdapter(items: List<String>) = object : ArrayAdapter<String>(
        this, android.R.layout.simple_spinner_item, items
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = (convertView as? TextView) ?: TextView(context)
            tv.text = getItem(position)
            tv.setTextColor(C_TEXT)
            tv.textSize = 14f
            tv.setSingleLine()
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.gravity = Gravity.CENTER_VERTICAL
            tv.setPadding(dp(12), 0, dp(8), 0)
            tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0)
            return tv
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = TextView(context)
            tv.text = getItem(position)
            tv.setTextColor(C_TEXT)
            tv.textSize = 15f
            tv.setPadding(dp(16), dp(13), dp(16), dp(13))
            tv.setBackgroundColor(Color.WHITE)
            return tv
        }
    }

    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = makeAdapter(items)
        background = rounded(C_INPUT, dp(10), dp(1), C_STROKE)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(C_BG1, C_BG2))
        }

        // ---- header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(14))
            val r = dp(22).toFloat()
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C_CHAR, C_CHAR2)).apply {
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
        }
        header.addView(LogoView(this), lp(dp(46), dp(46)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "DS Recap Tool"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_PLAT)
        })
        titles.addView(TextView(this).apply {
            text = "Developed by Dexter Syntax"
            textSize = 11f
            setTextColor(Color.parseColor("#A9AEBA"))
            letterSpacing = 0.04f
        })
        header.addView(titles, lp(0, WRAP, 1f, l = 12))
        btnSettings = TextView(this).apply {
            text = "⚙"
            textSize = 24f
            setTextColor(Color.parseColor("#C9CCD6"))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        header.addView(btnSettings, lp(WRAP, WRAP))
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- body
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(12))
        }
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // API key
        val c1 = card()
        c1.addView(label("GEMINI API KEY"))
        etApiKey = EditText(this).apply {
            hint = "AIza..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            textSize = 14f
            setTextColor(C_TEXT)
            setHintTextColor(Color.parseColor("#9A9EAA"))
            background = rounded(C_INPUT, dp(10), dp(1), C_STROKE)
            setPadding(dp(12), 0, dp(12), 0)
        }
        c1.addView(etApiKey, lp(MATCH, dp(42), t = 4))
        body.addView(c1, lp(MATCH, WRAP, t = 8))

        // language + voice
        val c2 = card().apply { orientation = LinearLayout.HORIZONTAL }
        val colA = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colA.addView(label("ဇာတ်ကား ဘာသာစကား"))
        spLang = spinner(langs.map { it.second })
        colA.addView(spLang, lp(MATCH, dp(42), t = 4))
        val colB = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colB.addView(label("မြန်မာ အသံ"))
        spVoice = spinner(listOf("Nilar · Edge (မ)", "Thiha · Edge (ကျား)", "Gemini AI အသံ", "Google အသံ"))
        colB.addView(spVoice, lp(MATCH, dp(42), t = 4))
        c2.addView(colA, lp(0, WRAP, 1f, r = 5))
        c2.addView(colB, lp(0, WRAP, 1f, l = 5))
        body.addView(c2, lp(MATCH, WRAP, t = 8))

        // voice tuning
        val c3 = card()
        val rowT = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvSpeed = label("")
        sbSpeed = SeekBar(this).apply {
            max = 150
            progress = 50
            progressTintList = ColorStateList.valueOf(C_ACCENT)
            thumbTintList = ColorStateList.valueOf(C_CHAR)
        }
        sCol.addView(tvSpeed)
        sCol.addView(sbSpeed, lp(MATCH, WRAP, t = 2))
        val pCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvPitch = label("")
        sbPitch = SeekBar(this).apply {
            max = 100
            progress = 50
            progressTintList = ColorStateList.valueOf(C_ACCENT)
            thumbTintList = ColorStateList.valueOf(C_CHAR)
        }
        pCol.addView(tvPitch)
        pCol.addView(sbPitch, lp(MATCH, WRAP, t = 2))
        rowT.addView(sCol, lp(0, WRAP, 1f, r = 6))
        rowT.addView(pCol, lp(0, WRAP, 1f, l = 6))
        c3.addView(rowT)
        btnPreview = styledButton("▶  အသံ စမ်းနားထောင်မယ်", false)
        c3.addView(btnPreview, lp(MATCH, dp(40), t = 8))
        body.addView(c3, lp(MATCH, WRAP, t = 8))

        // video
        val c4 = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnVideo = styledButton("🎬  Video ရွေးမယ်", false)
        c4.addView(btnVideo, lp(WRAP, dp(42)))
        tvVideo = TextView(this).apply {
            text = "Video မရွေးရသေးပါ"
            textSize = 13f
            setTextColor(C_TEXT2)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        c4.addView(tvVideo, lp(0, WRAP, 1f, l = 10))
        body.addView(c4, lp(MATCH, WRAP, t = 8))

        // generate
        val rowG = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnGenerate = styledButton("▶  Recap ဖန်တီးမယ်", true).apply {
            textSize = 16f
            isEnabled = false
        }
        rowG.addView(btnGenerate, lp(0, dp(52), 1f))
        btnOpen = styledButton("ဖွင့်မယ်", false).apply { visibility = View.GONE }
        rowG.addView(btnOpen, lp(WRAP, dp(52), l = 8))
        body.addView(rowG, lp(MATCH, WRAP, t = 12))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(C_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#C9CBD3"))
            visibility = View.INVISIBLE
        }
        body.addView(progress, lp(MATCH, dp(6), t = 10))

        tvStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(C_TEXT)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            minLines = 1
        }
        body.addView(tvStatus, lp(MATCH, WRAP, t = 4))

        // script box
        val c5 = card()
        val rowS = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rowS.addView(label("မြန်မာ SCRIPT"), lp(0, WRAP, 1f))
        val btnCopy = TextView(this).apply {
            text = "Copy"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_ACCENT)
            setPadding(dp(10), dp(2), dp(4), dp(2))
        }
        rowS.addView(btnCopy, lp(WRAP, WRAP))
        c5.addView(rowS)
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            background = rounded(C_INPUT, dp(10), dp(1), C_STROKE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        tvScript = TextView(this).apply {
            hint = "Script ထွက်လာရင် ဒီမှာ ပေါ်ပါမယ်"
            textSize = 13f
            setTextColor(C_TEXT)
            setHintTextColor(Color.parseColor("#9A9EAA"))
            setTextIsSelectable(true)
        }
        scroll.addView(tvScript, ViewGroup.LayoutParams(MATCH, WRAP))
        c5.addView(scroll, lp(MATCH, 0, 1f, t = 6))
        body.addView(c5, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })

        btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("script", tvScript.text))
            Toast.makeText(this, "Copy လုပ်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
        }
        return root
    }

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C_CHAR
        window.navigationBarColor = C_BG2
        setContentView(buildUi())

        etApiKey.setText(prefs.getString("key", ""))
        spLang.setSelection(prefs.getInt("lang", 0).coerceIn(0, langs.size - 1))
        spVoice.setSelection(prefs.getInt("voice", 0).coerceIn(0, 3))
        sbSpeed.progress = prefs.getInt("speed", 50)
        sbPitch.progress = prefs.getInt("pitch", 50)
        updateVoiceLabels()

        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateVoiceLabels()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        sbSpeed.setOnSeekBarChangeListener(l)
        sbPitch.setOnSeekBarChangeListener(l)

        btnVideo.setOnClickListener { pickVideo.launch("video/*") }
        btnGenerate.setOnClickListener { startPipeline() }
        btnPreview.setOnClickListener { previewVoice() }
        btnSettings.setOnClickListener { showSettings() }
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

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun rateStr() = String.format(Locale.US, "%+d%%", sbSpeed.progress - 50)
    private fun pitchStr() = String.format(Locale.US, "%+dHz", sbPitch.progress - 50)

    private fun updateVoiceLabels() {
        tvSpeed.text = "အမြန်နှုန်း  ${rateStr()}"
        tvPitch.text = "အသံ Pitch  ${pitchStr()}"
    }

    private fun refreshButtons() {
        btnGenerate.isEnabled = !busy && videoUri != null
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("key", etApiKey.text.toString().trim())
            .putInt("lang", spLang.selectedItemPosition)
            .putInt("voice", spVoice.selectedItemPosition)
            .putInt("speed", sbSpeed.progress)
            .putInt("pitch", sbPitch.progress)
            .apply()
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val etG = EditText(this).apply {
            hint = "Gemini model (ဥပမာ gemini-3-flash-preview)"
            setText(prefs.getString("gmodel", "gemini-3-flash-preview"))
            setSingleLine()
        }
        val etE = EditText(this).apply {
            hint = "Edge version (အလိုအလျောက်ဆို အလွတ်ထားပါ)"
            val saved = prefs.getString("edgever", "").orEmpty()
            setText(if (saved == "143.0.3650.75") "" else saved)
            setSingleLine()
        }
        val etTm = EditText(this).apply {
            hint = "Gemini TTS model (အလွတ်ထားရင် auto)"
            setText(prefs.getString("ttsmodel", ""))
            setSingleLine()
        }
        val etTv = EditText(this).apply {
            hint = "Gemini TTS voice (ဥပမာ Kore, Puck, Charon)"
            setText(prefs.getString("ttsvoice", "Kore"))
            setSingleLine()
        }
        box.addView(etG)
        box.addView(etE)
        box.addView(etTm)
        box.addView(etTv)
        AlertDialog.Builder(this)
            .setTitle("Advanced")
            .setView(box)
            .setPositiveButton("သိမ်းမယ်") { _, _ ->
                prefs.edit()
                    .putString("gmodel", etG.text.toString().trim())
                    .putString("edgever", etE.text.toString().trim())
                    .putString("ttsmodel", etTm.text.toString().trim())
                    .putString("ttsvoice", etTv.text.toString().trim())
                    .apply()
            }
            .setNegativeButton("မလုပ်တော့ဘူး", null)
            .setNeutralButton("ကွန်ရက်စစ်မယ်") { _, _ -> runNetCheck() }
            .show()
    }

    /** VPN ထွက်ပေါက် နိုင်ငံ၊ Gemini နဲ့ Edge ချိတ်လို့ရမရ စစ်တယ် */
    private fun runNetCheck() {
        setStatus("ကွန်ရက် စစ်နေပါတယ်... (၁ မိနစ်လောက်ကြာနိုင်)")
        val key = etApiKey.text.toString().trim()
        lifecycleScope.launch {
            val sb = StringBuilder()
            sb.append("IP: ").append(EdgeTts.ipInfo()).append("\n\n")
            sb.append("Gemini: ").append(withContext(Dispatchers.IO) {
                try {
                    val c = okhttp3.OkHttpClient()
                    val rq = okhttp3.Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models")
                        .header("x-goog-api-key", key).build()
                    c.newCall(rq).execute().use { r ->
                        val b = r.body?.string().orEmpty()
                        if (r.code == 200) "OK (HTTP 200)"
                        else if (b.contains("location is not supported")) "❌ ဒီနိုင်ငံ/VPN ကို ထောက်ပံ့မထားပါ"
                        else "HTTP ${r.code}: ${b.take(120)}"
                    }
                } catch (e: Exception) {
                    "${e.javaClass.simpleName}: ${e.message}"
                }
            }).append("\n\n")
            sb.append("Edge TTS:\n")
            try {
                EdgeTts.synthesize("မင်္ဂလာပါ", "my-MM-NilarNeural", "+0%", "+0Hz", edgeOverride(), true)
                sb.append("✅ အလုပ်လုပ်တယ် (${EdgeTts.lastReport})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sb.append(EdgeTts.lastReport.ifEmpty { e.message.orEmpty() })
            }
            setStatus("")
            showReport("ကွန်ရက် စစ်ဆေးချက်", sb.toString())
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        listOf<View>(btnVideo, btnPreview, spLang, spVoice, sbSpeed, sbPitch, btnSettings, etApiKey)
            .forEach { it.isEnabled = !b }
        progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
        if (b) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshButtons()
    }

    private fun setStatus(s: String) = runOnUiThread { tvStatus.text = s }
    private fun updateProgress(p: Int) = runOnUiThread { progress.progress = p }

    private fun fileName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "video ရွေးပြီးပါပြီ ✅"
    } catch (e: Exception) {
        "video ရွေးပြီးပါပြီ ✅"
    }

    // --------------------------------------------------------- voice preview
    // ------------------------------------------------------------ TTS engines
    private enum class Engine { EDGE, GEMINI, GOOGLE }

    private var engine = Engine.EDGE
    private var edgeVoice = "my-MM-NilarNeural"
    private var ttsRate = "+0%"
    private var ttsPitch = "+0Hz"
    private var ttsSpeed = 1.0
    private var ttsSemis = 0.0
    private var edgeVerOverride = ""
    private var geminiKey = ""
    private var geminiTtsModel = ""
    private var geminiTtsVoice = "Kore"
    private var engineNote = ""
    private var lastFallbackReport = ""

    private fun engineLabel() = when (engine) {
        Engine.EDGE -> "Edge"
        Engine.GEMINI -> "Gemini"
        Engine.GOOGLE -> "Google"
    }

    private fun initEngine() {
        val idx = spVoice.selectedItemPosition
        engine = when (idx) {
            0, 1 -> Engine.EDGE
            2 -> Engine.GEMINI
            else -> Engine.GOOGLE
        }
        edgeVoice = if (idx == 1) "my-MM-ThihaNeural" else "my-MM-NilarNeural"
        ttsRate = rateStr()
        ttsPitch = pitchStr()
        ttsSpeed = 1.0 + (sbSpeed.progress - 50) / 100.0
        ttsSemis = (sbPitch.progress - 50) / 8.0
        edgeVerOverride = edgeOverride()
        geminiKey = etApiKey.text.toString().trim()
        geminiTtsModel = prefs.getString("ttsmodel", "").orEmpty()
        geminiTtsVoice = prefs.getString("ttsvoice", "Kore").orEmpty().ifEmpty { "Kore" }
        engineNote = ""
        lastFallbackReport = ""
    }

    /** စာတိုတစ်ခု → PCM (24kHz mono s16le)။ engine မရရင် Google ကို အလိုအလျောက် ပြောင်းတယ်။ */
    private suspend fun synthChunkPcm(text: String, forceEdge: Boolean = false): ByteArray {
        while (true) {
            if (engine == Engine.GOOGLE && text.length > 180) {
                val bos = ByteArrayOutputStream()
                for (c in chunkText(text, 180)) bos.write(synthChunkPcm(c))
                return bos.toByteArray()
            }
            try {
                return when (engine) {
                    Engine.EDGE -> VideoMerger.toPcm(
                        this,
                        EdgeTts.synthesize(text, edgeVoice, ttsRate, ttsPitch, edgeVerOverride, forceEdge),
                        "mp3", 1.0, 0.0
                    )
                    Engine.GEMINI -> VideoMerger.toPcm(
                        this,
                        GeminiTts.synthesize(geminiKey, geminiTtsModel, geminiTtsVoice, text) { setStatus(it) },
                        "pcm", ttsSpeed, ttsSemis
                    )
                    Engine.GOOGLE -> VideoMerger.toPcm(this, GoogleTts.synthesize(text), "mp3", ttsSpeed, ttsSemis)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (engine == Engine.GOOGLE) throw e
                val from = engine
                engine = Engine.GOOGLE
                lastFallbackReport = if (from == Engine.EDGE) EdgeTts.lastReport.ifEmpty { e.message.orEmpty() } else e.message.orEmpty()
                engineNote = "⚠️ ${if (from == Engine.EDGE) "Edge" else "Gemini"} မရလို့ Google အသံသုံးတယ် — ${e.message?.take(100)}"
                if (from == Engine.EDGE) File(filesDir, "edge_report.txt").writeText(lastFallbackReport)
                setStatus(engineNote)
            }
        }
    }

    private suspend fun synthSegmentPcm(text: String): ByteArray {
        val pcm = ByteArrayOutputStream()
        for (chunk in chunkText(text, if (engine == Engine.GOOGLE) 180 else 500)) {
            pcm.write(synthChunkPcm(chunk))
            delay(100)
        }
        return pcm.toByteArray()
    }

    // --------------------------------------------------------- voice preview
    private fun previewVoice() {
        savePrefs()
        initEngine()
        btnPreview.isEnabled = false
        setStatus("အသံ စမ်းနေပါတယ် [${engineLabel()}]...")
        lifecycleScope.launch {
            try {
                val sample = "မင်္ဂလာပါ၊ ဒါက ဒီအက်စ် ရီကက်ပ် တူးလ်ရဲ့ မြန်မာအသံ စမ်းသပ်မှု ဖြစ်ပါတယ်။"
                val pcm = synthChunkPcm(sample, forceEdge = true)
                if (lastFallbackReport.isNotEmpty()) {
                    showReport("အသံ engine မရပါ — အကြောင်းရင်း", lastFallbackReport)
                }
                val f = File(cacheDir, "preview.wav")
                VideoMerger.writeWav24k(f, pcm)
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    setOnCompletionListener {
                        it.release()
                        player = null
                    }
                    prepare()
                    start()
                }
                setStatus(if (engineNote.isNotEmpty()) engineNote else "▶ ဖွင့်နေပါတယ် [${engineLabel()}]")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                setStatus("❌ ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
                showReport("အသံ မထွက်ပါ", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                if (!busy) btnPreview.isEnabled = true
            }
        }
    }

    private fun showReport(title: String, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val sc = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sc)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("report", text))
            }
            .setNegativeButton("ပိတ်မယ်", null)
            .show()
    }

    private fun edgeOverride(): String {
        val v = prefs.getString("edgever", "").orEmpty()
        return if (v == "143.0.3650.75") "" else v
    }

    // ------------------------------------------------------------- pipeline
    private fun startPipeline() {
        val key = etApiKey.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, "Gemini API key ထည့်ပါ", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = videoUri ?: return
        savePrefs()
        tvScript.text = ""
        btnOpen.visibility = View.GONE
        setBusy(true)
        lifecycleScope.launch {
            try {
                runPipeline(key, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                setStatus("❌ ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
                showReport("Error အသေးစိတ်", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun ensureModel() {
        val (code, _, name) = langs[spLang.selectedItemPosition.coerceIn(0, langs.size - 1)]
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
    }

    private suspend fun runPipeline(key: String, uri: Uri) {
        val gModel = prefs.getString("gmodel", "gemini-3-flash-preview").orEmpty()

        val movieMs = videoDurationMs(uri)
        val minutes = if (movieMs > 0) (movieMs / 60000.0).roundToInt().coerceIn(1, 20) else 5

        ensureModel()

        // 1) Audio
        setStatus("၁/၅ အသံထုတ်နေပါတယ်...")
        updateProgress(0)
        val wav = File(cacheDir, "audio_16k.wav")
        withContext(Dispatchers.IO) {
            AudioExtractor.extractToWav(this@MainActivity, uri, wav) { updateProgress(it) }
        }

        // 2) Vosk
        setStatus("၂/၅ စကားပြောကို စာသားပြောင်းနေပါတယ်...")
        updateProgress(0)
        val transcript = withContext(Dispatchers.Default) {
            VoskTranscriber.transcribe(modelDir, wav) { updateProgress(it) }
        }
        wav.delete()
        if (transcript.isBlank()) error("စကားပြောစာသား မရပါ။ ဘာသာစကား ကိုက်မကိုက် စစ်ပါ")
        File(filesDir, "transcript.txt").writeText(transcript)

        // 3) Gemini
        setStatus("၃/၅ Gemini က မြန်မာ script ရေးနေပါတယ်...")
        updateProgress(0)
        val clipped = if (transcript.length > 300_000) transcript.take(300_000) else transcript
        val segs = GeminiClient.writeScript(key, gModel, buildPrompt(minutes, clipped)) { setStatus(it) }
        val scriptText = segs.joinToString("\n\n") { it.text }
        File(filesDir, "script.txt").writeText(scriptText)
        runOnUiThread { tvScript.text = scriptText }

        // 4) TTS (Edge / Gemini / Google → PCM 24kHz)
        initEngine()
        val pcmAll = ByteArrayOutputStream()
        val durations = ArrayList<Long>()
        for ((i, seg) in segs.withIndex()) {
            setStatus("၄/၅ မြန်မာအသံထုတ်နေပါတယ် ${i + 1}/${segs.size} [${engineLabel()}]")
            updateProgress((i * 100) / segs.size)
            val pcm = synthSegmentPcm(seg.text)
            durations += (pcm.size * 1000L / 48000L).coerceAtLeast(800L)
            pcmAll.write(pcm)
        }
        val narration = File(cacheDir, "narration.wav")
        VideoMerger.writeWav24k(narration, pcmAll.toByteArray())

        // 5) Merge
        setStatus("၅/၅ ဗီဒီယိုနဲ့ ပေါင်းနေပါတယ် (မိနစ်အနည်းငယ် ကြာနိုင်)...")
        updateProgress(0)
        val clips = planClips(segs, durations, movieMs)
        val out = File(cacheDir, "recap_out.mp4")
        out.delete()
        VideoMerger.merge(this, uri, narration, clips, out) { updateProgress(it) }

        val where = withContext(Dispatchers.IO) { saveToMovies(out) }
        updateProgress(100)
        setStatus("✅ ပြီးပါပြီ — $where  [${VideoMerger.lastEncoder} · ${engineLabel()}]")
        if (outUri != null) runOnUiThread { btnOpen.visibility = View.VISIBLE }
    }

    private fun buildPrompt(minutes: Int, transcript: String): String = """
You are a professional Burmese movie-recap narrator.

Below is the dialogue transcript of a movie/video with timestamps in seconds, formatted as [start-end] text.
The dialogue may be in English or another language and may contain speech-recognition errors. Infer the story from context.

Task: Write a recap narration of the whole video in natural spoken Burmese (မြန်မာဘာသာ), in the style of popular movie-recap YouTube channels. Tone: exciting and engaging.
Rules:
- Total length: about $minutes minutes when read aloud (about ${minutes * 550} Burmese characters).
- Split the narration into about ${(minutes * 5).coerceIn(10, 80)} short consecutive segments in story order. Each segment is 1-2 sentences (roughly 8-15 seconds when read aloud) and covers ONE specific scene or moment.
- For each segment, give the start and end time in seconds (decimals allowed) of that exact scene in the video, using the transcript timestamps. Ranges must be accurate, in increasing order, and must not overlap. The range length should be roughly similar to the time needed to read the segment aloud.
- Plain spoken Burmese only. No bullet points, no markdown, no emojis, no English except character names.
Output ONLY a JSON array like: [{"text":"...","start":123.4,"end":170.0}]

Transcript:
$transcript
""".trimIndent()

    private fun chunkText(text: String, max: Int): List<String> {
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

    /**
     * Script တစ်ပိုင်းစီအတွက် scene ကို ဖြတ်ပြီး အမြန်နှုန်း ချိန်ညှိတယ်။
     * - narration က scene ထက်ရှည်ရင် နှေးတယ် (အများဆုံး ၀.၈ ဆ)၊ ဒီထက်ပိုလိုရင် scene ပတ်ဝန်းကျင် footage ကို ထပ်ယူတယ်
     * - scene က narration ထက်ရှည်ရင် မြန်တယ် (အများဆုံး ၂ ဆ)
     * - ထွက်လာမယ့် ကြာချိန် = narration ကြာချိန် အတိအကျ
     */
    private fun planClips(
        segs: List<GeminiClient.Seg>,
        durs: List<Long>,
        movieMs: Long
    ): List<VideoMerger.Clip> {
        val out = ArrayList<VideoMerger.Clip>()
        for ((i, s) in segs.withIndex()) {
            val d = durs[i]
            var sMs = (s.start * 1000).toLong()
            var eMs = (s.end * 1000).toLong()
            val valid = s.start >= 0 && s.end > s.start && (movieMs <= 0 || sMs < movieMs)
            if (!valid) {
                sMs = if (movieMs > 0) movieMs * i / segs.size else 0L
                eMs = sMs + d
            }
            if (movieMs > 0) eMs = eMs.coerceAtMost(movieMs)
            val range = (eMs - sMs).coerceAtLeast(500L)

            var speed = (range.toDouble() / d).coerceIn(0.8, 2.0)
            if (speed in 0.95..1.05) speed = 1.0
            val srcLen = (d * speed).toLong()

            // scene ထက် ပိုယူရရင် ရှေ့ ၂၅% / နောက် ၇၅% ကို ချဲ့၊ scene ထက်နည်းရင် အလယ်ကိုယူ
            var st = if (srcLen > range) sMs - ((srcLen - range) * 0.25).toLong()
            else sMs + (range - srcLen) / 2
            st = st.coerceAtLeast(0L)
            if (movieMs > 0 && st + srcLen > movieMs) st = (movieMs - srcLen).coerceAtLeast(0L)
            out.add(VideoMerger.Clip(st, st + srcLen, speed.toFloat()))
        }
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
        val name = "DS_recap_${System.currentTimeMillis()}.mp4"
        return if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DS Recap")
            }
            val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                ?: error("ဖိုင်သိမ်းလို့မရပါ")
            contentResolver.openOutputStream(u)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            outUri = u
            "Gallery › Movies/DS Recap"
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
            val dest = File(dir, name)
            src.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }
}
