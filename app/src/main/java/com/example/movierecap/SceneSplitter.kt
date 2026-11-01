package com.example.movierecap

/** Vosk ရဲ့ "[စတင်-ပြီး] စာသား" လိုင်းတွေကနေ စကားပြောခန်း (scene) တွေ ခွဲထုတ်တယ် */
object SceneSplitter {

    data class Utt(val start: Double, val end: Double, val text: String)
    data class Scene(val startSec: Double, val endSec: Double, val text: String)

    private val LINE = Regex("^\\[(\\d+(?:\\.\\d+)?)-(\\d+(?:\\.\\d+)?)]\\s*(.*)$")

    fun parse(transcript: String): List<Utt> {
        val out = ArrayList<Utt>()
        for (line in transcript.lineSequence()) {
            val m = LINE.find(line.trim()) ?: continue
            val s = m.groupValues[1].toDoubleOrNull() ?: continue
            val e = m.groupValues[2].toDoubleOrNull() ?: continue
            val t = m.groupValues[3].trim()
            if (t.isNotEmpty() && e > s) out.add(Utt(s, e, t))
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
}
