package com.example.movierecap

/** Vosk ရဲ့ "[စတင်-ပြီး] စာသား" လိုင်းတွေကနေ စကားပြောခန်း (scene) တွေ ခွဲထုတ်တယ် */
object SceneSplitter {

    data class Utt(val start: Double, val end: Double, val text: String)
    data class Scene(val startSec: Double, val endSec: Double, val text: String)
    data class Line(val start: Double, val end: Double, val text: String, val dub: Boolean)
    data class Word(val start: Double, val end: Double, val text: String)

    private val LINE = Regex("^\\[(\\d+(?:\\.\\d+)?)-(\\d+(?:\\.\\d+)?)]\\s*(.*)$")

    fun parse(transcript: String, offsetSec: Double = 0.0): List<Utt> {
        val out = ArrayList<Utt>()
        for (line in transcript.lineSequence()) {
            val m = LINE.find(line.trim()) ?: continue
            val s = m.groupValues[1].toDoubleOrNull() ?: continue
            val e = m.groupValues[2].toDoubleOrNull() ?: continue
            val t = m.groupValues[3].trim()
            if (t.isNotEmpty() && e > s) out.add(Utt(s + offsetSec, e + offsetSec, t))
        }
        return out
    }

    /**
     * ကပ်လျက်စကားတွေကို ခန်းတစ်ခုအဖြစ် ပေါင်းတယ်။
     * - စကားကြား ၁.၂ စက္ကန့်ထက်နည်းရင် ခန်းတစ်ခုတည်း (အများဆုံး ၁၂ စက္ကန့်အထိ)
     * - ၂.၅ စက္ကန့်ထက်တိုတဲ့ ခန်းကို ကပ်လျက်ခန်းနဲ့ ပေါင်း
     * - ရှေ့ ၀.၂၅ / နောက် ၀.၃၅ စက္ကန့် ချဲ့ (ခန်းချင်း မထပ်စေဘဲ)
     */
    fun group(utts: List<Utt>, movieSec: Double): List<Scene> {
        if (utts.isEmpty()) return emptyList()
        data class Raw(var s: Double, var e: Double, val sb: StringBuilder)

        val raws = ArrayList<Raw>()
        var cur: Raw? = null
        for (u in utts) {
            val c = cur
            if (c == null) {
                cur = Raw(u.start, u.end, StringBuilder(u.text))
            } else if (u.start - c.e < 1.2 && c.e - c.s < 12.0) {
                c.e = u.end
                c.sb.append(' ').append(u.text)
            } else {
                raws.add(c)
                cur = Raw(u.start, u.end, StringBuilder(u.text))
            }
        }
        cur?.let { raws.add(it) }

        // တိုလွန်းတဲ့ခန်းကို နောက်ခန်းနဲ့ ပေါင်း
        var i = 0
        while (i < raws.size - 1) {
            val a = raws[i]
            val b = raws[i + 1]
            if (a.e - a.s < 2.5 && b.s - a.e < 3.0) {
                a.e = b.e
                a.sb.append(' ').append(b.sb)
                raws.removeAt(i + 1)
            } else {
                i++
            }
        }

        val limit = if (movieSec > 0) movieSec else raws.last().e + 5.0
        val scenes = ArrayList<Scene>()
        for ((idx, r) in raws.withIndex()) {
            val prevEnd = if (idx > 0) raws[idx - 1].e else 0.0
            val nextStart = if (idx + 1 < raws.size) raws[idx + 1].s else limit
            val s = maxOf(r.s - 0.25, (prevEnd + r.s) / 2.0, 0.0)
            val e = minOf(r.e + 0.35, (nextStart + r.e) / 2.0, limit)
            if (e - s >= 0.8) scenes.add(Scene(s, e, r.sb.toString()))
        }
        return scenes
    }

    private val FILLERS = setOf("嗯", "啊", "哦", "呃", "唉", "哎", "呵", "哈", "喂", "um", "uh", "ah", "oh", "hm", "hmm", "mm")

    /**
     * Dub mode အတွက် စကားတစ်ကြောင်းချင်း (အချိန်တိကျ) ခွဲတယ်။
     * - စကားကြား ၀.၃၅ စက္ကန့်အောက်၊ ပေါင်းရင် ၈ စက္ကန့်အောက်ဆို ပေါင်း
     * - "嗯/啊" လို အသံတိုလေးတွေ (dub=false) က ဘာသာမပြန်၊ အသံမထုတ်
     * - ရှေ့ ၀.၁၂ / နောက် ၀.၂၀ စက္ကန့် ချဲ့ (ကပ်လျက်စကားနဲ့ မထပ်စေဘဲ)
     */
    fun lines(utts: List<Utt>, rangeStart: Double, rangeEnd: Double): List<Line> {
        val inRange = utts.filter { it.end > rangeStart && it.start < rangeEnd }
        if (inRange.isEmpty()) return emptyList()
        data class Raw(var s: Double, var e: Double, val sb: StringBuilder)

        val raws = ArrayList<Raw>()
        var cur: Raw? = null
        for (u in inRange) {
            val c = cur
            if (c == null) {
                cur = Raw(u.start, u.end, StringBuilder(u.text))
            } else if (u.start - c.e < 0.35 && u.end - c.s < 8.0) {
                c.e = u.end
                c.sb.append(' ').append(u.text)
            } else {
                raws.add(c)
                cur = Raw(u.start, u.end, StringBuilder(u.text))
            }
        }
        cur?.let { raws.add(it) }

        val out = ArrayList<Line>()
        for ((idx, r) in raws.withIndex()) {
            val prevEnd = if (idx > 0) raws[idx - 1].e else rangeStart
            val nextStart = if (idx + 1 < raws.size) raws[idx + 1].s else rangeEnd
            val s = maxOf(r.s - 0.12, (prevEnd + r.s) / 2.0, rangeStart)
            val e = minOf(r.e + 0.20, (nextStart + r.e) / 2.0, rangeEnd)
            if (e - s < 0.4) continue
            val text = r.sb.toString().trim()
            val core = text.filter { it.isLetterOrDigit() }
            val dub = core.length >= 2 && core.lowercase() !in FILLERS
            out.add(Line(s, e, text, dub))
        }
        return out
    }

    fun parseWords(raw: String, offsetSec: Double): List<Word> {
        val out = ArrayList<Word>()
        for (line in raw.lineSequence()) {
            val p = line.split('\t')
            if (p.size < 3) continue
            val s = p[0].toDoubleOrNull() ?: continue
            val e = p[1].toDoubleOrNull() ?: continue
            val t = p[2].trim()
            if (t.isNotEmpty() && e >= s) out.add(Word(s + offsetSec, e + offsetSec, t))
        }
        return out
    }

    /** scene ပြောင်းတဲ့ အချိန်နားက စကားလုံးအနားသတ်မှာ ဖြတ်တယ် (ဘေးတစ်ဖက်စီမှာ ၂ လုံးအနည်းဆုံးရှိမှ) */
    private fun splitByCuts(g: List<Word>, cuts: List<Double>): List<List<Word>> {
        if (g.size < 4 || cuts.isEmpty()) return listOf(g)
        val idxs = sortedSetOf<Int>()
        for (c in cuts) {
            if (c <= g.first().start + 0.2 || c >= g.last().end - 0.2) continue
            var best = -1
            var bd = 0.6
            for (i in 0 until g.size - 1) {
                val mid = (g[i].end + g[i + 1].start) / 2.0
                val d = Math.abs(c - mid)
                if (d < bd) {
                    bd = d
                    best = i
                }
            }
            if (best >= 0) idxs.add(best)
        }
        val parts = ArrayList<List<Word>>()
        var from = 0
        for (i in idxs) {
            if (i + 1 - from >= 2 && g.size - (i + 1) >= 2) {
                parts.add(g.subList(from, i + 1))
                from = i + 1
            }
        }
        parts.add(g.subList(from, g.size))
        return parts
    }

    /**
     * စကားလုံးအချိန်တွေကနေ စကားတစ်ကြောင်းချင်း ခွဲတယ်။
     * - စကားလုံးကြား ၀.၃ စက္ကန့်ထက်ပိုရင် (ဇာတ်ကောင်ပြောင်းတာ/အနားယူတာ) ခွဲ
     * - scene ပြောင်းတဲ့နေရာမှာ ခွဲ (ဇာတ်ကောင်တစ်ယောက်ချင်းစီ သီးသန့်ဖြစ်အောင်)
     */
    fun linesFromWords(words: List<Word>, cuts: List<Double>, rangeStart: Double, rangeEnd: Double): List<Line> {
        val ws = words.filter { it.end > rangeStart && it.start < rangeEnd }.sortedBy { it.start }
        val groups = ArrayList<List<Word>>()
        var cur = ArrayList<Word>()
        for (w in ws) {
            if (cur.isNotEmpty()) {
                val prev = cur.last()
                val gap = w.start - prev.end
                val dur = w.end - cur.first().start
                if (gap > 0.30 || (dur > 7.0 && gap > 0.12)) {
                    groups.add(cur)
                    cur = ArrayList()
                }
            }
            cur.add(w)
        }
        if (cur.isNotEmpty()) groups.add(cur)

        val out = ArrayList<Line>()
        for (g0 in groups) {
            for (g in splitByCuts(g0, cuts)) {
                val s = maxOf(g.first().start, rangeStart)
                val e = minOf(g.last().end, rangeEnd)
                if (e - s < 0.35) continue
                val text = g.joinToString(" ") { it.text }
                val core = text.filter { it.isLetterOrDigit() }
                val dub = core.length >= 2 && core.lowercase() !in FILLERS
                out.add(Line(s, e, text, dub))
            }
        }
        return out
    }
}
