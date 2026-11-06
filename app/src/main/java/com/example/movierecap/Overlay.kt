package com.example.movierecap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ------------------------------------------------------------------ model
/** ဖုံးဘောင်: မူရင်းစာတန်း/watermark ကို အုပ်ဖို့ (mode 1 = အရောင်ဖြည့်, 2 = ဝါး) */
class CoverBox(
    var on: Boolean, var x: Float, var y: Float, var w: Float, var h: Float,
    var mode: Int, var color: Int, var alpha: Float, var blur: Int
)

/** စာတန်း: bgMode 0 မရှိ, 1 အမည်း, 2 အမည်းပါး, 3 အဖြူ, 4 အဖြူပါး — size = video အမြင့်ရဲ့ အချိုး */
class SubCfg(
    var on: Boolean, var x: Float, var y: Float, var w: Float, var h: Float,
    var textColor: Int, var bgMode: Int, var size: Float, var outline: Boolean
)

class LogoCfg(var on: Boolean, var x: Float, var y: Float, var w: Float, var alpha: Float)

class OverlayConfig(val covers: MutableList<CoverBox>, val sub: SubCfg, val logo: LogoCfg) {

    fun toJson(): String {
        val arr = JSONArray()
        for (c in covers) {
            arr.put(
                JSONObject().put("on", c.on).put("x", c.x.toDouble()).put("y", c.y.toDouble())
                    .put("w", c.w.toDouble()).put("h", c.h.toDouble()).put("mode", c.mode)
                    .put("color", c.color).put("alpha", c.alpha.toDouble()).put("blur", c.blur)
            )
        }
        val s = JSONObject().put("on", sub.on).put("x", sub.x.toDouble()).put("y", sub.y.toDouble())
            .put("w", sub.w.toDouble()).put("h", sub.h.toDouble()).put("textColor", sub.textColor)
            .put("bgMode", sub.bgMode).put("size", sub.size.toDouble()).put("outline", sub.outline)
        val l = JSONObject().put("on", logo.on).put("x", logo.x.toDouble()).put("y", logo.y.toDouble())
            .put("w", logo.w.toDouble()).put("alpha", logo.alpha.toDouble())
        return JSONObject().put("covers", arr).put("sub", s).put("logo", l).toString()
    }

    companion object {
        fun default(): OverlayConfig = OverlayConfig(
            mutableListOf(
                CoverBox(true, 0.04f, 0.80f, 0.92f, 0.12f, 1, Color.BLACK, 1f, 14),
                CoverBox(false, 0.04f, 0.04f, 0.30f, 0.06f, 1, Color.BLACK, 1f, 14),
                CoverBox(false, 0.66f, 0.04f, 0.30f, 0.06f, 1, Color.BLACK, 1f, 14)
            ),
            SubCfg(true, 0.06f, 0.80f, 0.88f, 0.14f, Color.parseColor("#FFE600"), 2, 0.032f, true),
            LogoCfg(false, 0.74f, 0.03f, 0.22f, 1f)
        )

        fun parse(text: String?): OverlayConfig {
            if (text.isNullOrEmpty()) return default()
            return try {
                val d = default()
                val o = JSONObject(text)
                val arr = o.optJSONArray("covers")
                if (arr != null) {
                    for (i in 0 until minOf(arr.length(), d.covers.size)) {
                        val j = arr.getJSONObject(i)
                        val c = d.covers[i]
                        c.on = j.optBoolean("on", c.on)
                        c.x = j.optDouble("x", c.x.toDouble()).toFloat()
                        c.y = j.optDouble("y", c.y.toDouble()).toFloat()
                        c.w = j.optDouble("w", c.w.toDouble()).toFloat()
                        c.h = j.optDouble("h", c.h.toDouble()).toFloat()
                        c.mode = j.optInt("mode", c.mode)
                        c.color = j.optInt("color", c.color)
                        c.alpha = j.optDouble("alpha", c.alpha.toDouble()).toFloat()
                        c.blur = j.optInt("blur", c.blur)
                    }
                }
                o.optJSONObject("sub")?.let { j ->
                    val s = d.sub
                    s.on = j.optBoolean("on", s.on)
                    s.x = j.optDouble("x", s.x.toDouble()).toFloat()
                    s.y = j.optDouble("y", s.y.toDouble()).toFloat()
                    s.w = j.optDouble("w", s.w.toDouble()).toFloat()
                    s.h = j.optDouble("h", s.h.toDouble()).toFloat()
                    s.textColor = j.optInt("textColor", s.textColor)
                    s.bgMode = j.optInt("bgMode", s.bgMode)
                    s.size = j.optDouble("size", s.size.toDouble()).toFloat()
                    s.outline = j.optBoolean("outline", s.outline)
                }
                o.optJSONObject("logo")?.let { j ->
                    val l = d.logo
                    l.on = j.optBoolean("on", l.on)
                    l.x = j.optDouble("x", l.x.toDouble()).toFloat()
                    l.y = j.optDouble("y", l.y.toDouble()).toFloat()
                    l.w = j.optDouble("w", l.w.toDouble()).toFloat()
                    l.alpha = j.optDouble("alpha", l.alpha.toDouble()).toFloat()
                }
                d
            } catch (e: Exception) {
                default()
            }
        }
    }
}

// --------------------------------------------------------------- rendering
object OverlayRenderer {
    fun even(v: Double): Int = ((v / 2.0).roundToInt() * 2).coerceAtLeast(2)

    /** FFmpeg scale='if(gt(iw,ih),-2,720)':'if(gt(iw,ih),720,-2)' နဲ့ တူတဲ့ output အရွယ်အစား */
    fun outSize(w: Int, h: Int): Pair<Int, Int> =
        if (w > h) Pair(even(w * 720.0 / h), 720) else Pair(720, even(h * 720.0 / w))

    private fun isDark(c: Int): Boolean {
        val lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
        return lum < 0.45
    }

    /** စာတန်းတစ်ကြောင်းကို ပုံ (ARGB) အဖြစ် ဆွဲတယ်။ Preview နဲ့ final နှစ်ခုလုံး ဒီ function ကိုပဲ သုံးတယ် */
    fun subtitleBitmap(text: String, cfg: SubCfg, wPx: Int, hPx: Int, refH: Float): Bitmap {
        val w = max(wPx, 4)
        val h = max(hPx, 4)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bgColor = when (cfg.bgMode) {
            1 -> 0xFF000000.toInt()
            2 -> 0x99000000.toInt()
            3 -> 0xFFFFFFFF.toInt()
            4 -> 0x99FFFFFF.toInt()
            else -> 0
        }
        if (bgColor != 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG)
            bg.color = bgColor
            val r = min(w, h) * 0.18f
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, bg)
        }
        if (text.isBlank()) return bmp

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.typeface = Typeface.DEFAULT_BOLD
        val pad = w * 0.04f
        val avail = max((w - 2 * pad).toInt(), 4)
        var size = max(cfg.size * refH, 6f)
        val minSize = size * 0.45f

        fun build(sz: Float): StaticLayout {
            tp.textSize = sz
            tp.style = Paint.Style.FILL
            return StaticLayout.Builder.obtain(text, 0, text.length, tp, avail)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.08f)
                .build()
        }

        var layout = build(size)
        while (layout.height > h - pad * 0.5f && size > minSize) {
            size *= 0.92f
            layout = build(size)
        }
        c.save()
        c.translate(pad, (h - layout.height) / 2f)
        if (cfg.outline) {
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = size * 0.16f
            tp.strokeJoin = Paint.Join.ROUND
            tp.color = if (isDark(cfg.textColor)) Color.WHITE else Color.BLACK
            layout.draw(c)
        }
        tp.style = Paint.Style.FILL
        tp.color = cfg.textColor
        layout.draw(c)
        c.restore()
        return bmp
    }

    fun logoBitmap(src: Bitmap, widthPx: Int, alpha: Float): Bitmap {
        val w = max(widthPx, 4)
        val h = max((src.height * w.toFloat() / src.width).roundToInt(), 4)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.alpha = (alpha.coerceIn(0.05f, 1f) * 255).toInt()
        Canvas(out).drawBitmap(scaled, 0f, 0f, p)
        if (scaled !== src) scaled.recycle()
        return out
    }
}

// ------------------------------------------------------------------ editor
/**
 * Preview canvas: video frame တစ်ပုံပေါ်မှာ ဖုံးဘောင်၊ စာတန်း၊ logo ကို လက်နဲ့ ဆွဲရွှေ့/အကြီးအသေးချိန်တယ်။
 * နေရာအားလုံးကို frame ရဲ့ အချိုး (0..1) နဲ့ သိမ်းတယ်။
 * selected: 0..2 = ဖုံးဘောင်, 3 = စာတန်း, 4 = logo
 */
class EditorView(ctx: Context, private val cfg: OverlayConfig) : View(ctx) {
    var frame: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }
    var logo: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }
    var sample = "ဥပမာ စာတန်း — မြန်မာစာ စမ်းသပ်ချက်"
    var selected = 0
        set(v) {
            field = v
            invalidate()
        }
    var onSelect: ((Int) -> Unit)? = null
    var onChange: (() -> Unit)? = null

    private val density = ctx.resources.displayMetrics.density
    private val fr = RectF()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val borderDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#7C3AED")
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 8f * density)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EC4899") }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private var dragMode = 0 // 0 မရှိ, 1 ရွှေ့, 2 အကြီးအသေး
    private var corner = 0
    private var startRect = RectF()
    private var downFx = 0f
    private var downFy = 0f

    // -------- geometry
    private fun rectOf(i: Int): RectF? {
        val b = frame ?: return null
        return when (i) {
            0, 1, 2 -> cfg.covers.getOrNull(i)?.takeIf { it.on }?.let { RectF(it.x, it.y, it.x + it.w, it.y + it.h) }
            3 -> if (cfg.sub.on) RectF(cfg.sub.x, cfg.sub.y, cfg.sub.x + cfg.sub.w, cfg.sub.y + cfg.sub.h) else null
            4 -> {
                val lg = logo
                if (!cfg.logo.on || lg == null) null
                else {
                    val hFrac = cfg.logo.w * b.width * lg.height / (lg.width.toFloat() * b.height)
                    RectF(cfg.logo.x, cfg.logo.y, cfg.logo.x + cfg.logo.w, cfg.logo.y + hFrac)
                }
            }
            else -> null
        }
    }

    private fun setRect(i: Int, r: RectF) {
        when (i) {
            0, 1, 2 -> cfg.covers.getOrNull(i)?.let { it.x = r.left; it.y = r.top; it.w = r.width(); it.h = r.height() }
            3 -> {
                cfg.sub.x = r.left; cfg.sub.y = r.top; cfg.sub.w = r.width(); cfg.sub.h = r.height()
            }
            4 -> {
                cfg.logo.x = r.left; cfg.logo.y = r.top; cfg.logo.w = r.width()
            }
        }
    }

    private fun toPx(r: RectF) = RectF(
        fr.left + r.left * fr.width(), fr.top + r.top * fr.height(),
        fr.left + r.right * fr.width(), fr.top + r.bottom * fr.height()
    )

    // -------- drawing
    override fun onDraw(canvas: Canvas) {
        val b = frame ?: return
        val pad = 10f * density
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        if (aw <= 0 || ah <= 0) return
        val sc = min(aw / b.width, ah / b.height)
        val fw = b.width * sc
        val fh = b.height * sc
        fr.set((width - fw) / 2f, (height - fh) / 2f, (width + fw) / 2f, (height + fh) / 2f)
        canvas.drawBitmap(b, null, fr, p)

        for (i in 0..2) {
            val c = cfg.covers.getOrNull(i) ?: continue
            val r = rectOf(i) ?: continue
            val d = toPx(r)
            if (c.mode == 2) drawBlur(canvas, b, c, d)
            else {
                p.color = (c.color and 0x00FFFFFF) or ((c.alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
                canvas.drawRect(d, p)
                p.color = Color.WHITE
            }
        }
        rectOf(4)?.let { r ->
            val lg = logo
            if (lg != null) {
                p.alpha = (cfg.logo.alpha.coerceIn(0.05f, 1f) * 255).toInt()
                canvas.drawBitmap(lg, null, toPx(r), p)
                p.alpha = 255
            }
        }
        rectOf(3)?.let { r ->
            val d = toPx(r)
            val bmp = OverlayRenderer.subtitleBitmap(sample, cfg.sub, d.width().toInt(), d.height().toInt(), fr.height())
            canvas.drawBitmap(bmp, d.left, d.top, null)
            bmp.recycle()
        }

        rectOf(selected)?.let { r ->
            val d = toPx(r)
            canvas.drawRect(d, border)
            canvas.drawRect(d, borderDark)
            val cx = floatArrayOf(d.left, d.right, d.left, d.right)
            val cy = floatArrayOf(d.top, d.top, d.bottom, d.bottom)
            for (k in 0..3) {
                canvas.drawCircle(cx[k], cy[k], 7f * density, handleFill)
                canvas.drawCircle(cx[k], cy[k], 7f * density, handleRing)
            }
        }
    }

    private fun drawBlur(canvas: Canvas, b: Bitmap, c: CoverBox, dest: RectF) {
        try {
            val sx = (c.x * b.width).toInt().coerceIn(0, b.width - 1)
            val sy = (c.y * b.height).toInt().coerceIn(0, b.height - 1)
            val sw = (c.w * b.width).toInt().coerceIn(1, b.width - sx)
            val sh = (c.h * b.height).toInt().coerceIn(1, b.height - sy)
            val crop = Bitmap.createBitmap(b, sx, sy, sw, sh)
            val k = 1 + c.blur / 2
            val small = Bitmap.createScaledBitmap(crop, max(1, sw / k), max(1, sh / k), true)
            canvas.drawBitmap(small, null, dest, p)
            if (small !== crop) small.recycle()
            if (crop !== b) crop.recycle()
        } catch (e: Exception) {
            p.color = Color.parseColor("#66000000")
            canvas.drawRect(dest, p)
            p.color = Color.WHITE
        }
    }

    // -------- touch
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled || frame == null || fr.width() <= 0f) return false
        val fx = ((e.x - fr.left) / fr.width())
        val fy = ((e.y - fr.top) / fr.height())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragMode = 0
                val sr = rectOf(selected)
                if (sr != null) {
                    val d = toPx(sr)
                    val cx = floatArrayOf(d.left, d.right, d.left, d.right)
                    val cy = floatArrayOf(d.top, d.top, d.bottom, d.bottom)
                    for (k in 0..3) {
                        if (hypot(e.x - cx[k], e.y - cy[k]) < 26f * density) {
                            dragMode = 2
                            corner = k
                            startRect = RectF(sr)
                            return true
                        }
                    }
                }
                for (i in intArrayOf(4, 3, 2, 1, 0)) {
                    val r = rectOf(i) ?: continue
                    if (fx >= r.left && fx <= r.right && fy >= r.top && fy <= r.bottom) {
                        if (selected != i) {
                            selected = i
                            onSelect?.invoke(i)
                        }
                        dragMode = 1
                        startRect = RectF(r)
                        downFx = fx
                        downFy = fy
                        invalidate()
                        return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == 1) {
                    val w = startRect.width()
                    val h = startRect.height()
                    val l = (startRect.left + (fx - downFx)).coerceIn(0f, max(0f, 1f - w))
                    val t = (startRect.top + (fy - downFy)).coerceIn(0f, max(0f, 1f - h))
                    setRect(selected, RectF(l, t, l + w, t + h))
                    invalidate()
                } else if (dragMode == 2) {
                    resize(fx, fy)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != 0) onChange?.invoke()
                dragMode = 0
                return true
            }
        }
        return true
    }

    private fun resize(fx: Float, fy: Float) {
        val anchorX = if (corner % 2 == 0) startRect.right else startRect.left
        val anchorY = if (corner < 2) startRect.bottom else startRect.top
        val px = fx.coerceIn(0f, 1f)
        val py = fy.coerceIn(0f, 1f)
        var l: Float
        var t: Float
        var r: Float
        var bt: Float
        if (selected == 4) {
            val aspect = startRect.height() / startRect.width()
            val w2 = max(abs(px - anchorX), 0.05f)
            val h2 = w2 * aspect
            l = if (px < anchorX) anchorX - w2 else anchorX
            r = l + w2
            t = if (py < anchorY) anchorY - h2 else anchorY
            bt = t + h2
            if (l < 0f || r > 1f || t < 0f || bt > 1f) return
        } else {
            l = min(anchorX, px)
            r = max(anchorX, px)
            t = min(anchorY, py)
            bt = max(anchorY, py)
            if (r - l < 0.04f || bt - t < 0.02f) return
        }
        setRect(selected, RectF(l, t, r, bt))
    }
}
