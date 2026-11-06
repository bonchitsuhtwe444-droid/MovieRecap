package com.example.movierecap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.widget.CheckBox
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
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
import java.io.FileOutputStream
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
        p.shader = LinearGradient(0f, 0f, 108f, 108f, Color.parseColor("#8B5CF6"), Color.parseColor("#312E81"), Shader.TileMode.CLAMP)
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
        p.shader = LinearGradient(26f, 22f, 82f, 86f, Color.WHITE, Color.parseColor("#FFB3D9"), Shader.TileMode.CLAMP)
        c.drawPath(ring, p)

        val d = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            moveTo(33f, 40f); lineTo(40f, 40f)
            arcTo(RectF(30f, 40f, 50f, 68f), -90f, 180f)
            lineTo(33f, 68f); close()
            moveTo(38f, 46f); lineTo(38f, 62f); lineTo(46f, 54f); close()
        }
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 40f, 0f, 68f, Color.WHITE, Color.parseColor("#FFD6EC"), Shader.TileMode.CLAMP)
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
        p.color = Color.WHITE
        c.drawPath(s, p)
        c.restore()
    }
}

class MainActivity : AppCompatActivity() {

    private companion object {
        const val GEMINI_MODEL = "gemini-3-flash-preview"
        val C_CHAR = Color.parseColor("#4F46E5")
        val C_CHAR2 = Color.parseColor("#EC4899")
        val C_VIOLET = Color.parseColor("#9333EA")
        val C_HOT1 = Color.parseColor("#FF8A1F")
        val C_HOT2 = Color.parseColor("#FF3D81")
        val C_PINK = Color.parseColor("#EC4899")
        val C_BG1 = Color.parseColor("#F6F3FF")
        val C_BG2 = Color.parseColor("#E1F3FF")
        val C_STROKE = Color.parseColor("#E0D6FF")
        val C_INPUT = Color.parseColor("#F8F6FF")
        val C_TEXT = Color.parseColor("#231B4D")
        val C_TEXT2 = Color.parseColor("#7A5FD0")
        val C_ACCENT = Color.parseColor("#7C3AED")
        val C_PLAT = Color.parseColor("#FFFFFF")
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private data class VoicePreset(val label: String, val base: String, val semis: Double)

    // Bing ရဲ့ မြန်မာအသံ ၂ မျိုး (Nilar/Thiha) ကို pitch မတူအောင်ချိန်ပြီး ဇာတ်ကောင်အသံ ၁၀ မျိုး
    private val voices = listOf(
        VoicePreset("နီလာ · မ", "my-MM-NilarNeural", 0.0),
        VoicePreset("မေသူ · မ", "my-MM-NilarNeural", 2.0),
        VoicePreset("ခင်မေ · မ", "my-MM-NilarNeural", -2.0),
        VoicePreset("သဇင် · မ", "my-MM-NilarNeural", 4.0),
        VoicePreset("ဒေါ်နန်း · မ", "my-MM-NilarNeural", -4.0),
        VoicePreset("သီဟ · ကျား", "my-MM-ThihaNeural", 0.0),
        VoicePreset("ကျော်ဇေယျာ · ကျား", "my-MM-ThihaNeural", -2.0),
        VoicePreset("အောင်မြင့် · ကျား", "my-MM-ThihaNeural", 2.0),
        VoicePreset("မောင်မောင် · ကျား", "my-MM-ThihaNeural", 4.0),
        VoicePreset("ဦးဘချို · ကျား", "my-MM-ThihaNeural", -4.0)
    )

    private var videoUri: Uri? = null
    private var outUri: Uri? = null
    private var busy = false
    private var player: MediaPlayer? = null

    private val prefs by lazy { getSharedPreferences("recap", Context.MODE_PRIVATE) }
    private val modelDir by lazy { File(filesDir, "vosk_model") }

    private lateinit var etApiKey: EditText
    private lateinit var tvEye: TextView
    private lateinit var btnModeRecap: Button
    private lateinit var btnModeDub: Button
    private lateinit var etFrom: EditText
    private lateinit var etTo: EditText
    private lateinit var tvRange: TextView
    private var dubMode = false
    private var keyHidden = false
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

    private val pickLogo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(uri)!!.use { ins ->
                            var bmp = BitmapFactory.decodeStream(ins) ?: return@withContext false
                            val m = maxOf(bmp.width, bmp.height)
                            if (m > 800) {
                                val sc = 800f / m
                                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
                            }
                            FileOutputStream(File(filesDir, "logo.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                if (ok) {
                    overlay.logo.on = true
                    loadLogoBitmap()
                    showTab(2)
                    saveOverlay()
                } else {
                    Toast.makeText(this@MainActivity, "Logo ပုံ ဖတ်လို့မရပါ", Toast.LENGTH_SHORT).show()
                }
            }
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
        background = rounded(Color.WHITE, dp(18), dp(1), C_STROKE)
        elevation = dp(3).toFloat()
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 10f
        setTextColor(C_TEXT2)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
    }

    private fun gradH(c1: Int, c2: Int, radius: Int, strokeW: Int = 0, strokeColor: Int = 0) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply {
            cornerRadius = radius.toFloat()
            if (strokeW > 0) setStroke(strokeW, strokeColor)
        }

    private fun styledButton(text: String, primary: Boolean): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.stateListAnimator = null
        b.textSize = 14f
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(if (primary) C_PLAT else C_ACCENT)
        val normal = if (primary) gradH(C_HOT1, C_HOT2, dp(16))
        else grad(Color.WHITE, Color.parseColor("#F1EAFF"), dp(14), dp(1), Color.parseColor("#CDBFFF"))
        val pressed = if (primary) gradH(Color.parseColor("#E67800"), Color.parseColor("#E02A6B"), dp(16))
        else grad(Color.parseColor("#F1EAFF"), Color.parseColor("#E0D4FF"), dp(14), dp(1), Color.parseColor("#CDBFFF"))
        val disabled = rounded(Color.parseColor("#D9D3EE"), dp(if (primary) 16 else 14))
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
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C_CHAR, C_VIOLET, C_CHAR2)).apply {
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
            setTextColor(Color.parseColor("#F3E8FF"))
            letterSpacing = 0.04f
        })
        header.addView(titles, lp(0, WRAP, 1f, l = 12))
        btnSettings = TextView(this).apply {
            text = "⚙"
            textSize = 24f
            setTextColor(Color.WHITE)
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
        val keyHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        keyHead.addView(label("🔑  GEMINI API KEY"), lp(0, WRAP, 1f))
        tvEye = TextView(this).apply {
            text = "ဖျောက်မယ်"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_ACCENT)
            setPadding(dp(10), dp(2), dp(4), dp(2))
        }
        keyHead.addView(tvEye, lp(WRAP, WRAP))
        c1.addView(keyHead)
        etApiKey = EditText(this).apply {
            hint = "AIza..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            minLines = 1
            maxLines = 3
            textSize = 13f
            setTextColor(C_TEXT)
            setHintTextColor(Color.parseColor("#9A9EAA"))
            background = rounded(C_INPUT, dp(10), dp(1), C_STROKE)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        c1.addView(etApiKey, lp(MATCH, WRAP, t = 4))
        tvEye.setOnClickListener {
            keyHidden = !keyHidden
            applyKeyVisibility()
        }
        body.addView(c1, lp(MATCH, WRAP, t = 8))

        // language + voice
        val c2 = card().apply { orientation = LinearLayout.HORIZONTAL }
        val colA = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colA.addView(label("🌍  ဇာတ်ကား ဘာသာစကား"))
        spLang = spinner(langs.map { it.second })
        colA.addView(spLang, lp(MATCH, dp(42), t = 4))
        val colB = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colB.addView(label("🎙  မြန်မာ အသံ"))
        spVoice = spinner(voices.map { it.label })
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
            thumbTintList = ColorStateList.valueOf(C_PINK)
        }
        sCol.addView(tvSpeed)
        sCol.addView(sbSpeed, lp(MATCH, WRAP, t = 2))
        val pCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvPitch = label("")
        sbPitch = SeekBar(this).apply {
            max = 100
            progress = 50
            progressTintList = ColorStateList.valueOf(C_ACCENT)
            thumbTintList = ColorStateList.valueOf(C_PINK)
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

        // mode + range
        val c6 = card()
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnModeRecap = styledButton("📖  Recap", false)
        btnModeDub = styledButton("🎭  Dub", false)
        modeRow.addView(btnModeRecap, lp(0, dp(40), 1f, r = 4))
        modeRow.addView(btnModeDub, lp(0, dp(40), 1f, l = 4))
        c6.addView(modeRow)
        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvRange = label("⏱  Dub ပိုင်း (မိနစ်) — အလွတ်ထားရင် တစ်ခုလုံး")
        fun numField(h: String) = EditText(this).apply {
            hint = h
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(C_TEXT)
            setHintTextColor(Color.parseColor("#9A9EAA"))
            background = rounded(C_INPUT, dp(10), dp(1), C_STROKE)
            setPadding(dp(6), 0, dp(6), 0)
        }
        etFrom = numField("စ")
        etTo = numField("ပြီး")
        rangeRow.addView(tvRange, lp(0, WRAP, 1f))
        rangeRow.addView(etFrom, lp(dp(56), dp(36), l = 6))
        rangeRow.addView(etTo, lp(dp(56), dp(36), l = 6))
        c6.addView(rangeRow, lp(MATCH, WRAP, t = 6))
        body.addView(c6, lp(MATCH, WRAP, t = 8))

        // next
        btnNext = styledButton("ဆက်သွားမယ်  ▶", true).apply {
            textSize = 16f
            isEnabled = false
        }
        body.addView(btnNext, lp(MATCH, dp(52), t = 12))

        // script box
        val c5 = card()
        val rowS = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rowS.addView(label("📝  မြန်မာ SCRIPT"), lp(0, WRAP, 1f))
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

    // ======================================================== PAGE 2: PREVIEW / EDITOR
    private lateinit var page1: View
    private lateinit var page2: View
    private lateinit var btnNext: Button
    private lateinit var btnBack: TextView
    private lateinit var editor: EditorView
    private lateinit var sbTime: SeekBar
    private lateinit var tvTime: TextView
    private lateinit var tabBtns: List<Button>
    private lateinit var panels: List<View>
    private var overlay = OverlayConfig.default()
    private var curTab = 0
    private var curCover = 0
    private var previewUri: Uri? = null
    private var syncing = false
    private var refreshCover: () -> Unit = {}
    private var refreshSub: () -> Unit = {}
    private var refreshLogo: () -> Unit = {}

    private val palette = intArrayOf(
        Color.parseColor("#FFFFFF"), Color.parseColor("#FFE600"), Color.parseColor("#000000"),
        Color.parseColor("#FF3B30"), Color.parseColor("#FF9500"), Color.parseColor("#34C759"),
        Color.parseColor("#00E5FF"), Color.parseColor("#FF4DA6")
    )

    private fun saveOverlay() {
        prefs.edit().putString("overlay", overlay.toJson()).apply()
    }

    private fun markSel(b: Button, sel: Boolean) {
        b.background = if (sel) gradH(C_ACCENT, C_PINK, dp(12))
        else grad(Color.WHITE, Color.parseColor("#F1EAFF"), dp(12), dp(1), Color.parseColor("#CDBFFF"))
        b.setTextColor(if (sel) Color.WHITE else C_ACCENT)
    }

    private fun smallBtn(text: String): Button = styledButton(text, false).apply {
        textSize = 12f
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun newSwitch(): Switch {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        return Switch(this).apply {
            thumbTintList = ColorStateList(states, intArrayOf(C_ACCENT, Color.parseColor("#B8B2D6")))
            trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#CDBFFF"), Color.parseColor("#E4E0F2")))
        }
    }

    private fun smallLabel(t: String) = TextView(this).apply {
        text = t
        textSize = 11.5f
        setTextColor(C_TEXT2)
    }

    private inner class Swatches(val colors: IntArray, val onPick: (Int) -> Unit) {
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        private val views = ArrayList<View>()

        init {
            for (c in colors) {
                val v = View(this@MainActivity)
                v.setOnClickListener {
                    onPick(c)
                    select(c)
                }
                views.add(v)
                row.addView(v, lp(dp(28), dp(28), r = 8))
            }
            select(colors[0])
        }

        fun select(c: Int) {
            for ((i, v) in views.withIndex()) {
                val on = colors[i] == c
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colors[i])
                    setStroke(dp(if (on) 3 else 1), if (on) C_ACCENT else Color.parseColor("#C9C2E6"))
                }
            }
        }
    }

    private fun buildCoverPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chips = listOf("ဘောင် ၁", "ဘောင် ၂", "ဘောင် ၃").map { smallBtn(it) }
        for ((i, b) in chips.withIndex()) {
            chipRow.addView(b, lp(0, dp(34), 1f, r = if (i < 2) 6 else 0))
            b.setOnClickListener {
                curCover = i
                editor.selected = i
                refreshCover()
            }
        }
        val sw = newSwitch()
        chipRow.addView(sw, lp(WRAP, WRAP, l = 10))
        col.addView(chipRow)

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bSolid = smallBtn("အရောင်ဖြည့်")
        val bBlur = smallBtn("ဝါး (blur)")
        modeRow.addView(bSolid, lp(0, dp(34), 1f, r = 6))
        modeRow.addView(bBlur, lp(0, dp(34), 1f))
        col.addView(modeRow, lp(MATCH, WRAP, t = 6))

        val sws = Swatches(palette) { color ->
            overlay.covers[curCover].color = color
            editor.invalidate()
            saveOverlay()
        }
        col.addView(sws.row, lp(MATCH, WRAP, t = 6))

        val tv = smallLabel("")
        val sb = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(C_ACCENT)
            thumbTintList = ColorStateList.valueOf(C_PINK)
        }
        col.addView(tv, lp(MATCH, WRAP, t = 4))
        col.addView(sb, lp(MATCH, WRAP))

        refreshCover = {
            syncing = true
            val c = overlay.covers[curCover]
            for ((i, b) in chips.withIndex()) markSel(b, i == curCover)
            sw.isChecked = c.on
            markSel(bSolid, c.mode == 1)
            markSel(bBlur, c.mode == 2)
            sws.select(c.color)
            sws.row.visibility = if (c.mode == 1) View.VISIBLE else View.GONE
            if (c.mode == 1) {
                sb.max = 80
                sb.progress = ((c.alpha * 100).toInt() - 20).coerceIn(0, 80)
                tv.text = "ပွင့်လင်းမြင်ရမှု ${(c.alpha * 100).toInt()}%"
            } else {
                sb.max = 39
                sb.progress = (c.blur - 1).coerceIn(0, 39)
                tv.text = "ဝါးအား ${c.blur}"
            }
            syncing = false
        }
        sw.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            overlay.covers[curCover].on = checked
            editor.selected = curCover
            editor.invalidate()
            saveOverlay()
        }
        bSolid.setOnClickListener {
            overlay.covers[curCover].mode = 1
            refreshCover()
            editor.invalidate()
            saveOverlay()
        }
        bBlur.setOnClickListener {
            overlay.covers[curCover].mode = 2
            refreshCover()
            editor.invalidate()
            saveOverlay()
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (syncing || !fromUser) return
                val c = overlay.covers[curCover]
                if (c.mode == 1) {
                    c.alpha = (p + 20) / 100f
                    tv.text = "ပွင့်လင်းမြင်ရမှု ${p + 20}%"
                } else {
                    c.blur = p + 1
                    tv.text = "ဝါးအား ${p + 1}"
                }
                editor.invalidate()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) = saveOverlay()
        })
        return col
    }

    private fun buildSubPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "📝  မြန်မာစာတန်း ထည့်မယ်"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_TEXT)
        }, lp(0, WRAP, 1f))
        val sw = newSwitch()
        head.addView(sw, lp(WRAP, WRAP))
        col.addView(head)

        col.addView(smallLabel("စာအရောင်"), lp(MATCH, WRAP, t = 4))
        val textSw = Swatches(palette) { color ->
            overlay.sub.textColor = color
            editor.invalidate()
            saveOverlay()
        }
        col.addView(textSw.row, lp(MATCH, WRAP, t = 3))

        col.addView(smallLabel("နောက်ခံ"), lp(MATCH, WRAP, t = 6))
        val bgRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bgNames = listOf("မရှိ", "အမည်း", "အမည်းပါး", "အဖြူ", "အဖြူပါး")
        val bgBtns = bgNames.map { smallBtn(it) }
        for ((i, b) in bgBtns.withIndex()) {
            bgRow.addView(b, lp(0, dp(32), 1f, r = if (i < 4) 4 else 0))
            b.setOnClickListener {
                overlay.sub.bgMode = i
                refreshSub()
                editor.invalidate()
                saveOverlay()
            }
        }
        col.addView(bgRow, lp(MATCH, WRAP, t = 3))

        val outRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        outRow.addView(smallLabel("စာလုံးအနားသတ်"), lp(0, WRAP, 1f))
        val swOut = newSwitch()
        outRow.addView(swOut, lp(WRAP, WRAP))
        col.addView(outRow, lp(MATCH, WRAP, t = 4))

        val tvSize = smallLabel("")
        val sbSize = SeekBar(this).apply {
            max = 55
            progressTintList = ColorStateList.valueOf(C_ACCENT)
            thumbTintList = ColorStateList.valueOf(C_PINK)
        }
        col.addView(tvSize, lp(MATCH, WRAP, t = 2))
        col.addView(sbSize, lp(MATCH, WRAP))

        refreshSub = {
            syncing = true
            val s = overlay.sub
            sw.isChecked = s.on
            textSw.select(s.textColor)
            for ((i, b) in bgBtns.withIndex()) markSel(b, i == s.bgMode)
            swOut.isChecked = s.outline
            sbSize.progress = ((s.size * 1000).toInt() - 15).coerceIn(0, 55)
            tvSize.text = "စာလုံးအရွယ် ${(s.size * 1000).toInt()}"
            syncing = false
        }
        sw.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            overlay.sub.on = checked
            editor.selected = 3
            editor.invalidate()
            saveOverlay()
        }
        swOut.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            overlay.sub.outline = checked
            editor.invalidate()
            saveOverlay()
        }
        sbSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (syncing || !fromUser) return
                overlay.sub.size = (p + 15) / 1000f
                tvSize.text = "စာလုံးအရွယ် ${p + 15}"
                editor.invalidate()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) = saveOverlay()
        })
        return col
    }

    private fun buildLogoPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bPick = smallBtn("🖼  Logo ရွေးမယ်")
        val bDel = smallBtn("ဖယ်မယ်")
        row.addView(bPick, lp(0, dp(36), 1f, r = 6))
        row.addView(bDel, lp(dp(80), dp(36), r = 8))
        val sw = newSwitch()
        row.addView(sw, lp(WRAP, WRAP))
        col.addView(row)
        col.addView(smallLabel("Logo ကို preview ပေါ်မှာ ဆွဲရွှေ့ပြီး ထောင့်ကိုဆွဲရင် အကြီးအသေး ချိန်လို့ရတယ်"), lp(MATCH, WRAP, t = 6))
        val tv = smallLabel("")
        val sb = SeekBar(this).apply {
            max = 90
            progressTintList = ColorStateList.valueOf(C_ACCENT)
            thumbTintList = ColorStateList.valueOf(C_PINK)
        }
        col.addView(tv, lp(MATCH, WRAP, t = 6))
        col.addView(sb, lp(MATCH, WRAP))

        refreshLogo = {
            syncing = true
            val l = overlay.logo
            sw.isChecked = l.on
            sb.progress = ((l.alpha * 100).toInt() - 10).coerceIn(0, 90)
            tv.text = "ပွင့်လင်းမြင်ရမှု ${(l.alpha * 100).toInt()}%"
            syncing = false
        }
        bPick.setOnClickListener { pickLogo.launch("image/*") }
        bDel.setOnClickListener {
            File(filesDir, "logo.png").delete()
            overlay.logo.on = false
            editor.logo = null
            refreshLogo()
            saveOverlay()
        }
        sw.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            if (checked && !File(filesDir, "logo.png").exists()) {
                syncing = true
                sw.isChecked = false
                syncing = false
                pickLogo.launch("image/*")
                return@setOnCheckedChangeListener
            }
            overlay.logo.on = checked
            editor.selected = 4
            editor.invalidate()
            saveOverlay()
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (syncing || !fromUser) return
                overlay.logo.alpha = (p + 10) / 100f
                tv.text = "ပွင့်လင်းမြင်ရမှု ${p + 10}%"
                editor.invalidate()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) = saveOverlay()
        })
        return col
    }

    private fun showTab(i: Int) {
        curTab = i
        for ((k, pnl) in panels.withIndex()) pnl.visibility = if (k == i) View.VISIBLE else View.GONE
        for ((k, b) in tabBtns.withIndex()) markSel(b, k == i)
        when (i) {
            0 -> {
                editor.selected = curCover
                refreshCover()
            }
            1 -> {
                editor.selected = 3
                refreshSub()
            }
            else -> {
                editor.selected = 4
                refreshLogo()
            }
        }
    }

    private fun buildEditorPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(C_BG1, C_BG2))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(16), dp(10))
            val r = dp(18).toFloat()
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C_CHAR, C_VIOLET, C_CHAR2)).apply {
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
        }
        btnBack = TextView(this).apply {
            text = "◀"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(4), dp(14), dp(4))
        }
        bar.addView(btnBack, lp(WRAP, WRAP))
        bar.addView(TextView(this).apply {
            text = "Preview · ပြင်ဆင်ရန်"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, lp(0, WRAP, 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        editor = EditorView(this, overlay).apply { background = rounded(Color.parseColor("#1B1730"), dp(14)) }
        root.addView(editor, lp(MATCH, 0, 1f, l = 12, t = 8, r = 12))

        // time slider
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(2), dp(14), 0)
        }
        timeRow.addView(smallLabel("🎞 မူရင်းစာတန်းပေါ်တဲ့ အချိန်ကို ရွေး"), lp(WRAP, WRAP, r = 8))
        sbTime = SeekBar(this).apply {
            max = 1000
            progress = 100
            progressTintList = ColorStateList.valueOf(C_ACCENT)
            thumbTintList = ColorStateList.valueOf(C_PINK)
        }
        timeRow.addView(sbTime, lp(0, WRAP, 1f))
        tvTime = TextView(this).apply {
            text = "00:00"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_ACCENT)
        }
        timeRow.addView(tvTime, lp(WRAP, WRAP, l = 6))
        root.addView(timeRow, LinearLayout.LayoutParams(MATCH, WRAP))
        sbTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val ms = videoUri?.let { videoDurationMs(it) } ?: 0L
                updateTimeLabel(ms)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                val u = videoUri ?: return
                loadPreviewFrame(videoDurationMs(u) * sbTime.progress / 1000)
            }
        })

        // tabs
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), 0)
        }
        tabBtns = listOf("🟦  ဖုံးဘောင်", "📝  စာတန်း", "🖼  Logo").map { smallBtn(it) }
        for ((i, b) in tabBtns.withIndex()) {
            tabRow.addView(b, lp(0, dp(36), 1f, r = if (i < 2) 6 else 0))
            b.setOnClickListener { showTab(i) }
        }
        root.addView(tabRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val panelBox = card()
        val coverPanel = buildCoverPanel()
        val subPanel = buildSubPanel()
        val logoPanel = buildLogoPanel()
        panels = listOf(coverPanel, subPanel, logoPanel)
        panelBox.addView(coverPanel)
        panelBox.addView(subPanel)
        panelBox.addView(logoPanel)
        val panelScroll = ScrollView(this).apply { addView(panelBox) }
        root.addView(panelScroll, lp(MATCH, dp(172), l = 12, t = 6, r = 12))

        editor.onSelect = { idx ->
            when (idx) {
                0, 1, 2 -> {
                    curCover = idx
                    showTab(0)
                }
                3 -> showTab(1)
                else -> showTab(2)
            }
        }
        editor.onChange = { saveOverlay() }

        // generate row
        val rowG = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnGenerate = styledButton("▶  Recap ဖန်တီးမယ်", true).apply {
            textSize = 16f
            isEnabled = false
        }
        rowG.addView(btnGenerate, lp(0, dp(52), 1f))
        btnOpen = styledButton("ဖွင့်မယ်", false).apply { visibility = View.GONE }
        rowG.addView(btnOpen, lp(WRAP, dp(52), l = 8))
        root.addView(rowG, lp(MATCH, WRAP, l = 12, t = 8, r = 12))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(C_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#E4DBFF"))
            visibility = View.INVISIBLE
        }
        root.addView(progress, lp(MATCH, dp(6), l = 12, t = 8, r = 12))

        tvStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(C_TEXT)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            minLines = 1
        }
        root.addView(tvStatus, lp(MATCH, WRAP, l = 14, t = 4, r = 14, b = 8))

        showTab(0)
        return root
    }

    private fun updateTimeLabel(movieMs: Long) {
        val sec = (movieMs * sbTime.progress / 1000 / 1000).toInt()
        tvTime.text = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
    }

    private fun loadPreviewFrame(ms: Long) {
        val uri = videoUri ?: return
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(this@MainActivity, uri)
                    var b = r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    r.release()
                    if (b != null && maxOf(b.width, b.height) > 1100) {
                        val sc = 1100f / maxOf(b.width, b.height)
                        b = Bitmap.createScaledBitmap(b, (b.width * sc).toInt(), (b.height * sc).toInt(), true)
                    }
                    b
                } catch (e: Exception) {
                    null
                }
            }
            if (bmp != null) editor.frame = bmp
            else Toast.makeText(this@MainActivity, "Preview frame ယူလို့မရပါ", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadLogoBitmap() {
        val f = File(filesDir, "logo.png")
        editor.logo = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    private fun showPage2() {
        val uri = videoUri ?: return
        savePrefs()
        page1.visibility = View.GONE
        page2.visibility = View.VISIBLE
        updateModeButtons()
        loadLogoBitmap()
        if (previewUri != uri || editor.frame == null) {
            previewUri = uri
            val movieMs = videoDurationMs(uri)
            sbTime.progress = 100
            updateTimeLabel(movieMs)
            loadPreviewFrame(movieMs * sbTime.progress / 1000)
        }
        showTab(curTab)
    }

    private fun showPage1() {
        page2.visibility = View.GONE
        page1.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page2.visibility == View.VISIBLE && !busy) showPage1() else super.onBackPressed()
    }

    // ----------------------------------------------- overlay for the final video
    private fun videoDisplaySize(uri: Uri): Pair<Int, Int>? = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        r.release()
        if (w == null || h == null) null else if (rot == 90 || rot == 270) Pair(h, w) else Pair(w, h)
    } catch (e: Exception) {
        null
    }

    private fun prepareOverlay(uri: Uri): VideoMerger.OverlayPlan? {
        val (fw, fh) = videoDisplaySize(uri) ?: return null
        val (ow, oh) = OverlayRenderer.outSize(fw, fh)
        cacheDir.listFiles()?.filter { it.name.startsWith("sub_") && it.name.endsWith(".png") }?.forEach { it.delete() }
        val covers = overlay.covers.filter { it.on }
        var logoPng: String? = null
        val lf = File(filesDir, "logo.png")
        if (overlay.logo.on && lf.exists()) {
            val src = BitmapFactory.decodeFile(lf.absolutePath)
            if (src != null) {
                val lb = OverlayRenderer.logoBitmap(src, (overlay.logo.w * ow).toInt(), overlay.logo.alpha)
                val out = File(cacheDir, "logo_ov.png")
                FileOutputStream(out).use { lb.compress(Bitmap.CompressFormat.PNG, 100, it) }
                logoPng = out.absolutePath
            }
        }
        if (covers.isEmpty() && logoPng == null && !overlay.sub.on) return null
        return VideoMerger.OverlayPlan(ow, oh, covers, logoPng, overlay.logo.x, overlay.logo.y, overlay.sub.x, overlay.sub.y)
    }

    /** စာကြောင်းတစ်ကြောင်းချင်း (။ ? !) ခွဲ၊ တိုလွန်းရင် ပေါင်း၊ ရှည်လွန်းရင် ၊ မှာ ဖြတ် */
    private fun splitSentences(text: String, maxLen: Int = 150): List<String> {
        val raw = text.split(Regex("(?<=[။?!])")).map { it.trim() }.filter { it.isNotEmpty() }
        if (raw.isEmpty()) return listOf(text)
        val merged = ArrayList<String>()
        var carry = ""
        for (sn in raw) {
            val cur = if (carry.isEmpty()) sn else "$carry $sn"
            if (cur.length < 14) carry = cur else {
                merged.add(cur)
                carry = ""
            }
        }
        if (carry.isNotEmpty()) {
            if (merged.isEmpty()) merged.add(carry) else merged[merged.size - 1] = merged.last() + " " + carry
        }
        val out = ArrayList<String>()
        for (sn in merged) {
            if (sn.length <= maxLen) {
                out.add(sn)
                continue
            }
            val parts = sn.split(Regex("(?<=[၊,;])")).map { it.trim() }.filter { it.isNotEmpty() }
            var cur = StringBuilder()
            for (pt in parts) {
                if (cur.isNotEmpty() && cur.length + pt.length > maxLen) {
                    out.add(cur.toString())
                    cur = StringBuilder()
                }
                if (pt.length > maxLen) {
                    pt.chunked(maxLen).forEach { out.add(it) }
                } else {
                    if (cur.isNotEmpty()) cur.append(' ')
                    cur.append(pt)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
        }
        return out
    }

    /** စာကြောင်းတစ်ကြောင်းချင်း အသံထုတ် (စာတန်းထည့်ရင် အချိန်အတိအကျရဖို့) */
    private suspend fun synthSentences(text: String, split: Boolean): List<Pair<String, ByteArray>> {
        val sents = if (split) splitSentences(text) else listOf(text)
        val out = ArrayList<Pair<String, ByteArray>>()
        for (sn in sents) out.add(sn to synthSegmentPcm(sn))
        return out
    }

    /** အသံကြာချိန်အတိအကျအတိုင်း စာတန်း PNG တွေ ထုတ်ပြီး cue (စ/ပြီး) စာရင်း ပြန်ပေး */
    private fun makeCues(
        sents: List<Pair<String, ByteArray>>,
        headSec: Double,
        segTag: Int,
        plan: VideoMerger.OverlayPlan?
    ): List<VideoMerger.SubCue> {
        if (plan == null || !overlay.sub.on) return emptyList()
        val cues = ArrayList<VideoMerger.SubCue>()
        var t = headSec
        for ((k, sp) in sents.withIndex()) {
            val dur = sp.second.size / 48000.0
            val bmp = OverlayRenderer.subtitleBitmap(
                sp.first, overlay.sub,
                (overlay.sub.w * plan.outW).toInt(), (overlay.sub.h * plan.outH).toInt(), plan.outH.toFloat()
            )
            val f = File(cacheDir, "sub_${segTag}_$k.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            cues.add(VideoMerger.SubCue(f.absolutePath, t, t + dur + 0.05))
            t += dur
        }
        return cues
    }

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C_CHAR
        window.navigationBarColor = C_BG2
        overlay = OverlayConfig.parse(prefs.getString("overlay", null))
        val rootFrame = FrameLayout(this)
        page1 = buildUi()
        page2 = buildEditorPage()
        rootFrame.addView(page1, FrameLayout.LayoutParams(MATCH, MATCH))
        rootFrame.addView(page2, FrameLayout.LayoutParams(MATCH, MATCH))
        page2.visibility = View.GONE
        setContentView(rootFrame)

        etApiKey.setText(prefs.getString("key", ""))
        applyKeyVisibility()
        spLang.setSelection(prefs.getInt("lang", 0).coerceIn(0, langs.size - 1))
        spVoice.setSelection(prefs.getInt("voice", 0).coerceIn(0, voices.size - 1))
        sbSpeed.progress = prefs.getInt("speed", 50)
        sbPitch.progress = prefs.getInt("pitch", 50)
        updateVoiceLabels()
        dubMode = prefs.getBoolean("dub", false)
        etFrom.setText(prefs.getString("from", ""))
        etTo.setText(prefs.getString("to", ""))
        btnModeRecap.setOnClickListener { dubMode = false; updateModeButtons() }
        btnModeDub.setOnClickListener { dubMode = true; updateModeButtons() }
        updateModeButtons()

        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateVoiceLabels()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        sbSpeed.setOnSeekBarChangeListener(l)
        sbPitch.setOnSeekBarChangeListener(l)

        btnVideo.setOnClickListener { pickVideo.launch("video/*") }
        btnGenerate.setOnClickListener { startPipeline() }
        btnNext.setOnClickListener { showPage2() }
        btnBack.setOnClickListener { if (!busy) showPage1() }
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

    private fun updateModeButtons() {
        fun style(b: Button, sel: Boolean) {
            b.background = if (sel) gradH(C_ACCENT, C_PINK, dp(14))
            else grad(Color.WHITE, Color.parseColor("#F1EAFF"), dp(14), dp(1), Color.parseColor("#CDBFFF"))
            b.setTextColor(if (sel) Color.WHITE else C_ACCENT)
        }
        style(btnModeRecap, !dubMode)
        style(btnModeDub, dubMode)
        etFrom.isEnabled = dubMode && !busy
        etTo.isEnabled = dubMode && !busy
        tvRange.alpha = if (dubMode) 1f else 0.4f
        btnGenerate.text = if (dubMode) "▶  Dub ဖန်တီးမယ်" else "▶  Recap ဖန်တီးမယ်"
    }

    private fun applyKeyVisibility() {
        val pos = etApiKey.selectionStart.coerceAtLeast(0)
        if (keyHidden) {
            etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            tvEye.text = "ပြမယ်"
        } else {
            etApiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            tvEye.text = "ဖျောက်မယ်"
        }
        etApiKey.setSelection(pos.coerceAtMost(etApiKey.text.length))
    }

    private fun rateStr() = String.format(Locale.US, "%+d%%", sbSpeed.progress - 50)
    private fun pitchStr() = String.format(Locale.US, "%+.1f", (sbPitch.progress - 50) / 8.0)

    private fun updateVoiceLabels() {
        tvSpeed.text = "အမြန်နှုန်း  ${rateStr()}"
        tvPitch.text = "အသံ Pitch ထပ်ညှိ  ${pitchStr()} st"
    }

    private fun refreshButtons() {
        btnGenerate.isEnabled = !busy && videoUri != null
        btnNext.isEnabled = !busy && videoUri != null
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("key", etApiKey.text.toString().trim())
            .putInt("lang", spLang.selectedItemPosition)
            .putInt("voice", spVoice.selectedItemPosition)
            .putInt("speed", sbSpeed.progress)
            .putInt("pitch", sbPitch.progress)
            .putBoolean("dub", dubMode)
            .putString("from", etFrom.text.toString().trim())
            .putString("to", etTo.text.toString().trim())
            .putString("overlay", overlay.toJson())
            .apply()
    }

    private fun showSettings() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, dp(24))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            val r = dp(24).toFloat()
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C_CHAR, C_VIOLET, C_CHAR2)).apply {
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        head.addView(TextView(this).apply {
            text = "⚙  ချိန်ညှိချက်"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        root.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(6))
        }
        root.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))

        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        fun switchRow(title: String, desc: String, checked: Boolean): Switch {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = title
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(C_TEXT)
            })
            col.addView(TextView(this).apply {
                text = desc
                textSize = 11.5f
                setTextColor(C_TEXT2)
            })
            val sw = Switch(this).apply {
                isChecked = checked
                thumbTintList = ColorStateList(states, intArrayOf(C_ACCENT, Color.parseColor("#B8B2D6")))
                trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#CDBFFF"), Color.parseColor("#E4E0F2")))
            }
            row.addView(col, lp(0, WRAP, 1f, r = 10))
            row.addView(sw, lp(WRAP, WRAP))
            body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            body.addView(View(this).apply { setBackgroundColor(Color.parseColor("#EFEAFB")) }, LinearLayout.LayoutParams(MATCH, dp(1)))
            return sw
        }

        val swCut = switchRow("🎬  Scene ပြောင်းတာ ရှာမယ် (Dub)", "ဇာတ်ကောင်/shot ပြောင်းတဲ့နေရာမှာ စကားကိုခွဲပြီး ရုပ်နဲ့အသံ ပိုတိကျစေ (အချိန်ပိုကြာ)", prefs.getBoolean("cutdetect", true))
        val swDrop = switchRow("✂️  စကားမပြောတဲ့ အပိုင်း ဖယ်မယ် (Dub)", "စကားပြောခန်းတွေပဲ ထားမယ်၊ ကြားအပိုင်းတွေ ဖြတ်ထုတ်", prefs.getBoolean("dropgaps", false))
        val swFree = switchRow("🌐  Google Translate နဲ့ပဲ ဘာသာပြန် (Dub)", "Gemini quota မကုန်ပေမယ့် အရည်အသွေးနိမ့်", prefs.getBoolean("freetrans", false))
        val swBlend = switchRow("🎞  ရုပ်ချောမွေ့စေ (blend)", "ထစ်တာလျော့ပေမယ့် ရုပ်ထပ်ပေါ်တတ်", prefs.getBoolean("smooth", false))

        // sync offset
        val offTitle = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_TEXT)
            setPadding(0, dp(10), 0, 0)
        }
        val offDesc = TextView(this).apply {
            text = "အသံက ပါးစပ်လှုပ်တာထက် စောနေရင် (−)၊ နောက်ကျနေရင် (+) ဘက်ကို ဆွဲပါ"
            textSize = 11.5f
            setTextColor(C_TEXT2)
        }
        val sbOff = SeekBar(this).apply {
            max = 24
            progress = prefs.getInt("syncoff", 12)
            progressTintList = ColorStateList.valueOf(C_ACCENT)
            thumbTintList = ColorStateList.valueOf(C_PINK)
        }
        fun offLabel() {
            offTitle.text = "⏱  ရုပ်နဲ့အသံ အချိန်ညှိ  " + String.format(Locale.US, "%+.2f s", (sbOff.progress - 12) * 0.05)
        }
        offLabel()
        sbOff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = offLabel()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        body.addView(offTitle)
        body.addView(offDesc)
        body.addView(sbOff, lp(MATCH, WRAP, t = 2, b = 6))

        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(6), dp(20), dp(8))
        }
        val btnNet = styledButton("ကွန်ရက်စစ်မယ်", false)
        val btnSave = styledButton("သိမ်းမယ်", true)
        foot.addView(btnNet, lp(0, dp(46), 1f, r = 6))
        foot.addView(btnSave, lp(0, dp(46), 1f, l = 6))
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(TextView(this).apply {
            text = "DS Recap Tool · Developed by Dexter Syntax"
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#A79BD8"))
            setPadding(0, 0, 0, dp(14))
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(root) }).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        btnSave.setOnClickListener {
            prefs.edit()
                .putBoolean("cutdetect", swCut.isChecked)
                .putBoolean("dropgaps", swDrop.isChecked)
                .putBoolean("freetrans", swFree.isChecked)
                .putBoolean("smooth", swBlend.isChecked)
                .putInt("syncoff", sbOff.progress)
                .apply()
            dialog.dismiss()
        }
        btnNet.setOnClickListener {
            dialog.dismiss()
            runNetCheck()
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** VPN ထွက်ပေါက် နိုင်ငံ၊ Gemini နဲ့ Bing ချိတ်လို့ရမရ စစ်တယ် */
    private fun runNetCheck() {
        setStatus("ကွန်ရက် စစ်နေပါတယ်... (၁ မိနစ်လောက်ကြာနိုင်)")
        val key = etApiKey.text.toString().trim()
        lifecycleScope.launch {
            val sb = StringBuilder()
            sb.append("IP: ").append(BingTts.ipInfo()).append("\n\n")
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
            sb.append("Bing TTS: ")
            try {
                val b = BingTts.synthesize("မင်္ဂလာပါ", "my-MM-NilarNeural")
                sb.append("✅ OK (${b.size} bytes)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sb.append("❌ ${e.message}")
            }
            sb.append("\n\n")
            setStatus("")
            showReport("ကွန်ရက် စစ်ဆေးချက်", sb.toString())
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        listOf<View>(btnVideo, btnPreview, spLang, spVoice, sbSpeed, sbPitch, btnSettings, etApiKey, btnModeRecap, btnModeDub)
            .forEach { it.isEnabled = !b }
        btnBack.isEnabled = !b
        editor.isEnabled = !b
        updateModeButtons()
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

    // ------------------------------------------------------------ Bing TTS
    private var bingVoice = "my-MM-NilarNeural"
    private var ttsSpeed = 1.0
    private var ttsSemis = 0.0

    private fun engineLabel() = "Bing"

    private fun initEngine() {
        val v = voices[spVoice.selectedItemPosition.coerceIn(0, voices.size - 1)]
        bingVoice = v.base
        ttsSpeed = 1.0 + (sbSpeed.progress - 50) / 100.0
        ttsSemis = v.semis + (sbPitch.progress - 50) / 8.0
    }

    private suspend fun synthSegmentPcm(text: String): ByteArray {
        val pcm = ByteArrayOutputStream()
        for (chunk in chunkText(text, 500)) {
            val mp3 = BingTts.synthesize(chunk, bingVoice)
            pcm.write(VideoMerger.toPcm(this, mp3, "mp3", ttsSpeed, ttsSemis))
            delay(100)
        }
        return pcm.toByteArray()
    }

    // --------------------------------------------------------- voice preview
    private fun previewVoice() {
        savePrefs()
        initEngine()
        val v = voices[spVoice.selectedItemPosition.coerceIn(0, voices.size - 1)]
        btnPreview.isEnabled = false
        setStatus("အသံ စမ်းနေပါတယ်...")
        lifecycleScope.launch {
            try {
                val me = if (v.base.contains("Nilar")) "ကျွန်မ" else "ကျွန်တော်"
                val sample = "မင်္ဂလာပါ၊ $me နာမည်က ${v.label.substringBefore(" · ")} ပါ။ ဒီအသံနဲ့ ဇာတ်ကားကို မြန်မာလို ပြောပေးပါမယ်။"
                val pcm = synthSegmentPcm(sample)
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
                setStatus("▶ ဖွင့်နေပါတယ်")
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
                if (dubMode) runDubPipeline(key, uri) else runPipeline(key, uri)
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
        val gModel = GEMINI_MODEL
        val langName = langs[spLang.selectedItemPosition.coerceIn(0, langs.size - 1)].second
        val movieMs = videoDurationMs(uri)

        ensureModel()

        // 1) Audio
        setStatus("၁/၆ အသံထုတ်နေပါတယ်...")
        updateProgress(0)
        val wav = File(cacheDir, "audio_16k.wav")
        withContext(Dispatchers.IO) {
            AudioExtractor.extractToWav(this@MainActivity, uri, wav) { updateProgress(it) }
        }

        // 2) Vosk
        setStatus("၂/၆ စကားပြောကို စာသားပြောင်းနေပါတယ်...")
        updateProgress(0)
        val transcript = withContext(Dispatchers.Default) {
            VoskTranscriber.transcribe(modelDir, wav) { updateProgress(it) }
        }
        wav.delete()
        if (transcript.isBlank()) error("စကားပြောစာသား မရပါ။ ဘာသာစကား ကိုက်မကိုက် စစ်ပါ")
        File(filesDir, "transcript.txt").writeText(transcript)

        // 3) စကားပြောခန်းတွေ ခွဲ
        setStatus("၃/၆ စကားပြောခန်းတွေ ခွဲနေပါတယ်...")
        val scenes0 = SceneSplitter.group(SceneSplitter.parse(transcript), movieMs / 1000.0)
        if (scenes0.isEmpty()) error("စကားပြောခန်း မတွေ့ပါ")
        val talkSec = scenes0.sumOf { it.endSec - it.startSec }
        runOnUiThread {
            tvScript.text = "စကားပြောခန်း ${scenes0.size} ခန်း (စုစုပေါင်း ${talkSec.roundToInt()} စက္ကန့်) တွေ့ပါတယ်..."
        }

        // 4) ခန်းတစ်ခုချင်း ဘာသာပြန်
        setStatus("၄/၆ Gemini က ခန်းတစ်ခုချင်း ဘာသာပြန်နေပါတယ် (${scenes0.size} ခန်း)...")
        updateProgress(0)
        val texts0 = GeminiClient.translateScenes(key, gModel, langName, scenes0, { updateProgress(it) }) { setStatus(it) }
        val keep = scenes0.indices.filter { texts0[it].isNotBlank() }
        if (keep.isEmpty()) error("Gemini က ဘာသာပြန်စာ မထုတ်ပေးပါ")
        val scenes = keep.map { scenes0[it] }
        val texts = keep.map { texts0[it].trim() }
        File(filesDir, "script.txt").writeText(texts.joinToString("\n\n"))
        runOnUiThread { tvScript.text = texts.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n\n") }

        // 5) ခန်းတစ်ခုချင်း အသံထုတ်
        initEngine()
        val plan = withContext(Dispatchers.IO) { prepareOverlay(uri) }
        val splitSubs = plan != null && overlay.sub.on
        val sentLists = ArrayList<List<Pair<String, ByteArray>>>()
        for ((i, t) in texts.withIndex()) {
            setStatus("၅/၆ မြန်မာအသံ ${i + 1}/${texts.size} [${engineLabel()}]" + if (splitSubs) " · စာတန်း" else "")
            updateProgress((i * 100) / texts.size)
            sentLists.add(synthSentences(t, splitSubs))
        }
        val pcms = sentLists.map { l ->
            val bo = ByteArrayOutputStream()
            for (sp in l) bo.write(sp.second)
            bo.toByteArray()
        }

        // 6) ရုပ်နဲ့အသံ အတိအကျကိုက်အောင် ချိန်ညှိပြီး ပေါင်း
        setStatus("၆/၆ မူရင်း video ရဲ့ frame တွေကို စစ်ဆေးနေပါတယ်...")
        updateProgress(0)
        val uniq = VideoMerger.sourceUniqueFps(this, uri, movieMs)
        val smooth = prefs.getBoolean("smooth", false)
        val srcNote = if (uniq != null) {
            "မူရင်း ပုံအသစ် ~${String.format(Locale.US, "%.1f", uniq)}/စက္ကန့်" +
                    (if (uniq < 8.0) " (မူရင်းကိုယ်တိုင် ထစ်တာ)" else "")
        } else ""
        setStatus("၆/၆ ရုပ်နဲ့အသံ ချိန်ညှိပြီး ပေါင်းနေပါတယ် — $srcNote")
        val (clips, narrationPcm) = planScenes(scenes, pcms, movieMs, sentLists, plan)
        val narration = File(cacheDir, "narration.wav")
        VideoMerger.writeWav24k(narration, narrationPcm)
        val out = File(cacheDir, "recap_out.mp4")
        out.delete()
        VideoMerger.merge(this, uri, narration, clips, out, smooth, plan) { updateProgress(it) }

        val where = withContext(Dispatchers.IO) { saveToMovies(out) }
        updateProgress(100)
        val mins = clips.sumOf { it.frames } / 30 / 60.0
        setStatus(
            "✅ ပြီးပါပြီ — $where  [${clips.size} ခန်း · ${String.format(Locale.US, "%.1f", mins)} မိနစ် · " +
                    "${VideoMerger.lastEncoder} · ${engineLabel()}] $srcNote"
        )
        if (outUri != null) runOnUiThread { btnOpen.visibility = View.VISIBLE }
    }

    /**
     * ခန်းတစ်ခုချင်းအတွက် (ရုပ်ကြာချိန် R, အသံကြာချိန် D) ကို frame အတိအကျ ကိုက်အောင် ချိန်ညှိတယ်။
     * - အသံက ခန်းထက်ရှည်ရင်: ခန်းနောက်က footage ကို ထပ်ယူပြီး နှေး (အနှေးဆုံး ၀.၇၅ ဆ)၊ မလောက်ရင် နောက်ဆုံးပုံကို ထိန်း
     * - ခန်းက အသံထက်ရှည်ရင်: video ကို မြန် (အမြန်ဆုံး ၂.၅ ဆ)၊ ကျန်ရင် အသံကို တိတ်ဆိတ်ဖြည့်
     * ထွက်လာတဲ့ ရုပ် frame အရေအတွက်နဲ့ အသံ sample အရေအတွက် အတိအကျ တူတယ် (၁ frame = ၈၀၀ sample)
     */
    private fun planScenes(
        scenes: List<SceneSplitter.Scene>,
        pcms: List<ByteArray>,
        movieMs: Long,
        sentLists: List<List<Pair<String, ByteArray>>>,
        plan: VideoMerger.OverlayPlan?
    ): Pair<List<VideoMerger.Clip>, ByteArray> {
        val minSp = 0.75
        val maxSp = 2.5
        val fps = 30
        val clips = ArrayList<VideoMerger.Clip>()
        val pcmOut = ByteArrayOutputStream()
        val movieSec = if (movieMs > 0) movieMs / 1000.0 else scenes.last().endSec + 30.0

        for ((i, sc) in scenes.withIndex()) {
            val pcm = pcms[i]
            val d = (pcm.size / 48000.0).coerceAtLeast(0.5)
            val nextStart = if (i + 1 < scenes.size) scenes[i + 1].startSec else movieSec
            val maxExt = (nextStart - sc.startSec).coerceAtLeast(0.5)
            val r = (sc.endSec - sc.startSec).coerceAtLeast(0.5)

            var srcLen = r
            if (r < d * minSp) srcLen = minOf(d * 0.9, maxExt).coerceAtLeast(r)
            val speed = (srcLen / d).coerceIn(minSp, maxSp).let { if (it in 0.95..1.05) 1.0 else it }
            val v = srcLen / speed
            val total = maxOf(d, v)
            val n = Math.ceil(total * fps).toInt().coerceAtLeast(1)

            clips.add(
                VideoMerger.Clip(
                    (sc.startSec * 1000).toLong(),
                    ((sc.startSec + srcLen) * 1000).toLong(),
                    speed.toFloat(),
                    n,
                    makeCues(sentLists[i], 0.0, i, plan)
                )
            )

            val target = n * 1600
            pcmOut.write(pcm, 0, minOf(pcm.size, target))
            if (pcm.size < target) pcmOut.write(ByteArray(target - pcm.size))
        }
        return clips to pcmOut.toByteArray()
    }

    // ======================================================== DUB MODE
    private suspend fun runDubPipeline(key: String, uri: Uri) {
        val gModel = GEMINI_MODEL
        val (langCode, langName, _) = langs[spLang.selectedItemPosition.coerceIn(0, langs.size - 1)]
        val sl = if (langCode == "cn") "zh-CN" else langCode
        val movieMs = videoDurationMs(uri)
        val movieSec = if (movieMs > 0) movieMs / 1000.0 else 0.0
        val fromSec = ((etFrom.text.toString().toDoubleOrNull() ?: 0.0) * 60.0).coerceAtLeast(0.0)
        var toSec = (etTo.text.toString().toDoubleOrNull() ?: 0.0) * 60.0
        if (toSec <= fromSec) toSec = if (movieSec > 0) movieSec else fromSec + 3600.0
        if (movieSec > 0) toSec = minOf(toSec, movieSec)
        if (toSec - fromSec < 2.0) error("Dub လုပ်မယ့် ပိုင်း (စ/ပြီး) ကို မှန်မှန်ရွေးပါ")
        val keepGaps = !prefs.getBoolean("dropgaps", false)
        val freeOnly = prefs.getBoolean("freetrans", false)
        val detect = prefs.getBoolean("cutdetect", true)
        val offsetSec = (prefs.getInt("syncoff", 12) - 12) * 0.05

        ensureModel()

        // 1) Audio (ရွေးထားတဲ့ ပိုင်းပဲ)
        setStatus("၁/၆ အသံထုတ်နေပါတယ်...")
        updateProgress(0)
        val wav = File(cacheDir, "audio_16k.wav")
        withContext(Dispatchers.IO) {
            AudioExtractor.extractToWav(this@MainActivity, uri, wav, fromSec, toSec) { updateProgress(it) }
        }

        // 2) Vosk (စကားလုံးတစ်လုံးချင်း အချိန်နဲ့)
        setStatus("၂/၆ စကားပြောကို စာသားပြောင်းနေပါတယ်...")
        updateProgress(0)
        val wordSink = StringBuilder()
        val transcript = withContext(Dispatchers.Default) {
            VoskTranscriber.transcribe(modelDir, wav, wordSink) { updateProgress(it) }
        }
        wav.delete()
        if (transcript.isBlank()) error("စကားပြောစာသား မရပါ။ ဘာသာစကား ကိုက်မကိုက် စစ်ပါ")
        File(filesDir, "transcript.txt").writeText(transcript)

        // 3) scene ပြောင်းတဲ့နေရာ ရှာ + စကားတစ်ကြောင်းချင်း ခွဲ
        var cuts: List<Double> = emptyList()
        if (detect) {
            setStatus("၃/၆ scene ပြောင်းတဲ့နေရာတွေ ရှာနေပါတယ် (ခဏကြာနိုင်)...")
            updateProgress(0)
            cuts = VideoMerger.detectCuts(this, uri, fromSec, toSec)
        }
        setStatus("၃/၆ စကားတစ်ကြောင်းချင်း ခွဲနေပါတယ်...")
        val words = SceneSplitter.parseWords(wordSink.toString(), fromSec)
        val allLines = if (words.isNotEmpty()) SceneSplitter.linesFromWords(words, cuts, fromSec, toSec)
        else SceneSplitter.lines(SceneSplitter.parse(transcript, fromSec), fromSec, toSec)
        val dubLines = allLines.filter { it.dub }
        if (dubLines.isEmpty()) error("Dub လုပ်စရာ စကားပြောကြောင်း မတွေ့ပါ")
        runOnUiThread { tvScript.text = "စကားပြောကြောင်း ${dubLines.size} ကြောင်း၊ scene ပြောင်းချက် ${cuts.size} ခု တွေ့ပါတယ်..." }

        // 4) ဘာသာပြန်
        setStatus("၄/၆ ဘာသာပြန်နေပါတယ် (${dubLines.size} ကြောင်း)...")
        updateProgress(0)
        val items = dubLines.mapIndexed { i, l -> Translator.Item(i, l.end - l.start, l.text) }
        val texts0 = Translator.translate(
            File(filesDir, "trans_cache.json"), key, gModel, langName, langCode, sl, items, freeOnly,
            { updateProgress(it) }
        ) { setStatus(it) }
        val keep = dubLines.indices.filter { texts0[it].isNotBlank() }
        if (keep.isEmpty()) error("ဘာသာပြန်စာ မရပါ (Gemini/Google Translate နှစ်ခုလုံး မရ)")
        val lines = keep.map { dubLines[it] }
        val texts = keep.map { texts0[it].trim() }
        File(filesDir, "script.txt").writeText(texts.joinToString("\n"))
        runOnUiThread {
            tvScript.text = lines.indices.joinToString("\n\n") { i ->
                val t = lines[i].start.roundToInt()
                String.format(Locale.US, "[%02d:%02d] ", t / 60, t % 60) + texts[i]
            }
        }

        // 5) အသံ (တစ်ကြောင်းချင်း၊ ရွေးထားတဲ့ ဇာတ်ကောင်အသံ)
        initEngine()
        val plan = withContext(Dispatchers.IO) { prepareOverlay(uri) }
        val splitSubs = plan != null && overlay.sub.on
        val sentLists = ArrayList<List<Pair<String, ByteArray>>>()
        for ((i, t) in texts.withIndex()) {
            setStatus("၅/၆ မြန်မာအသံ ${i + 1}/${texts.size} [${engineLabel()}]" + if (splitSubs) " · စာတန်း" else "")
            updateProgress((i * 100) / texts.size)
            sentLists.add(synthSentences(t, splitSubs))
        }
        val pcms = sentLists.map { l ->
            val bo = ByteArrayOutputStream()
            for (sp in l) bo.write(sp.second)
            bo.toByteArray()
        }

        // 6) ရုပ်နဲ့အသံ frame အတိအကျ ညှိပြီး ပေါင်း
        setStatus("၆/၆ ရုပ်နဲ့အသံ ချိန်ညှိပြီး ပေါင်းနေပါတယ်...")
        updateProgress(0)
        val (clips, narrationPcm) = planDub(lines, pcms, fromSec, toSec, keepGaps, cuts, offsetSec, sentLists, plan)
        val narration = File(cacheDir, "narration.wav")
        VideoMerger.writeWav24k(narration, narrationPcm)
        val out = File(cacheDir, "recap_out.mp4")
        out.delete()
        VideoMerger.merge(this, uri, narration, clips, out, prefs.getBoolean("smooth", false), plan) { updateProgress(it) }

        val where = withContext(Dispatchers.IO) { saveToMovies(out) }
        updateProgress(100)
        val mins = clips.sumOf { it.frames } / 30 / 60.0
        setStatus(
            "✅ Dub ပြီးပါပြီ — $where  [${lines.size} ကြောင်း · ${String.format(Locale.US, "%.1f", mins)} မိနစ် · " +
                    "scene ${cuts.size} · ${VideoMerger.lastEncoder}]"
        )
        if (outUri != null) runOnUiThread { btnOpen.visibility = View.VISIBLE }
    }

    /**
     * စကားတစ်ကြောင်းချင်းအတွက် ရုပ်ကြာချိန် R နဲ့ မြန်မာအသံကြာချိန် D ကို အတိအကျ ညှိတယ်:
     * - စကားစတဲ့အချိန်မှာ အသံစအောင် ရှေ့မှာ ၀.၁ စက္ကန့် pre-roll ထည့်ပြီး အသံရှေ့မှာ အတိတ်ဆိတ်ထည့် (စောနေတာ မဖြစ်စေ)
     * - pre/post-roll က scene ပြောင်းချက်ကို မကျော်စေ (ရှေ့ဇာတ်ကောင် မပါလာစေ)
     * - အသံရှည်ရင် ရုပ်နှေး (အနှေးဆုံး ၀.၃ ဆ)၊ ရုပ်ရှည်ရင် မြန် (အမြန်ဆုံး ၂.၅ ဆ) ပြီး ပိုနေတဲ့အပိုင်းကို ဖြတ်ထုတ်
     * - ရုပ် frame အရေအတွက်နဲ့ အသံ sample အရေအတွက် အတိအကျတူ (၁ frame = ၈၀၀ sample)
     */
    private fun planDub(
        lines: List<SceneSplitter.Line>,
        pcms: List<ByteArray>,
        fromSec: Double,
        toSec: Double,
        keepGaps: Boolean,
        cuts: List<Double>,
        offsetSec: Double,
        sentLists: List<List<Pair<String, ByteArray>>>,
        plan: VideoMerger.OverlayPlan?
    ): Pair<List<VideoMerger.Clip>, ByteArray> {
        val minSp = 0.3
        val maxSp = 2.5
        val fps = 30
        val clips = ArrayList<VideoMerger.Clip>()
        val pcmOut = ByteArrayOutputStream()

        fun gap(a: Double, b: Double) {
            if (!keepGaps || b - a < 0.1) return
            val n = Math.round((b - a) * fps).toInt().coerceAtLeast(1)
            clips.add(VideoMerger.Clip((a * 1000).toLong(), (b * 1000).toLong(), 1f, n))
            pcmOut.write(ByteArray(n * 1600))
        }

        var cursor = fromSec
        for ((i, l) in lines.withIndex()) {
            val pcm = pcms[i]
            val d = (pcm.size / 48000.0).coerceAtLeast(0.4)
            val a = l.start
            val b = l.end
            val r = (b - a).coerceAtLeast(0.4)
            val sp0 = r / d
            var speed = sp0.coerceIn(minSp, maxSp)
            if (speed in 0.95..1.05) speed = 1.0
            val trimmed = sp0 > maxSp
            val speechSrc = if (trimmed) d * maxSp else r
            val b2 = a + speechSrc
            val nextLimit = if (i + 1 < lines.size) lines[i + 1].start else toSec

            // pre-roll (အသံကို နောက်ဆွဲချင်ရင် ပိုထည့်) — scene ပြောင်းချက်ကို မကျော်
            var pre = maxOf(0.10, -offsetSec * speed)
            pre = minOf(pre, maxOf(a - cursor, 0.0))
            var s = a - pre
            cuts.lastOrNull { it > s && it <= a }?.let { s = it }
            pre = a - s

            // post-roll — scene ပြောင်းချက်မှာ ရပ်၊ ဖြတ်ထုတ်ထားရင် မထည့်
            var post = if (trimmed) 0.0 else minOf(0.15, maxOf(nextLimit - b2, 0.0))
            var e = b2 + post
            cuts.firstOrNull { it >= b2 && it < e }?.let { e = it }
            post = e - b2

            gap(cursor, s)

            val srcLen = e - s
            val vidSec = srcLen / speed
            val headFrames = Math.round(maxOf(0.0, pre / speed + offsetSec) * fps).toInt()
            val audioFrames = Math.ceil(d * fps).toInt()
            val n = maxOf(Math.ceil(vidSec * fps).toInt(), headFrames + audioFrames).coerceAtLeast(1)

            clips.add(
                VideoMerger.Clip(
                    (s * 1000).toLong(), (e * 1000).toLong(), speed.toFloat(), n,
                    makeCues(sentLists[i], headFrames / 30.0, i, plan)
                )
            )

            val total = n * 1600
            pcmOut.write(ByteArray(headFrames * 1600))
            val room = total - headFrames * 1600
            pcmOut.write(pcm, 0, minOf(pcm.size, room))
            if (pcm.size < room) pcmOut.write(ByteArray(room - pcm.size))

            cursor = maxOf(e, b)
        }
        gap(cursor, toSec)
        return clips to pcmOut.toByteArray()
    }

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
