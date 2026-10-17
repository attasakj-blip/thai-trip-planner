package th.trip.planner.audio

import th.trip.planner.model.MusicChoice
import th.trip.planner.model.MusicStyle
import th.trip.planner.model.Tempo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates one-minute instrumental pieces on the device, so the app needs no licensed music.
 *
 * - 6 styles x 3 tempos x 3 pieces. Every piece is exactly 60 seconds: the tempo is set so a
 *   whole number of bars fills one minute.
 * - Each piece has a song form (A A' B A') built from a melodic motif, and the three pieces of a
 *   style differ in key, melody shape, rhythm, chords and arrangement (for example a counter-melody,
 *   arpeggios, call-and-response, ornaments).
 * - Note tails that run past the end are wrapped to the start and the melody resolves to the home
 *   note at the end, so looping the minute is seamless.
 */
object MusicGen {
    const val RATE = 22050
    const val SECONDS = 60
    const val LENGTH = RATE * SECONDS

    /** Bars in one minute. bpm = bars * 4 makes the piece exactly 60 s. */
    fun bars(style: MusicStyle, tempo: Tempo): Int = when (style) {
        MusicStyle.SAMBA -> when (tempo) { Tempo.SLOW -> 20; Tempo.MEDIUM -> 24; Tempo.FAST -> 28 }
        else -> when (tempo) { Tempo.SLOW -> 16; Tempo.MEDIUM -> 24; Tempo.FAST -> 32 }
    }

    fun bpm(style: MusicStyle, tempo: Tempo): Double = bars(style, tempo) * 4.0

    /** Short names of the three pieces of each style, shown in the app. */
    fun pieceName(style: MusicStyle, variant: Int, thai: Boolean): String {
        val th = when (style) {
            MusicStyle.THAI_LOCAL -> listOf("ระนาดเดี่ยว", "ระนาดคู่ฆ้องวง", "ระนาด ขิม และกลอง")
            MusicStyle.MUSIC_BOX -> listOf("กล่องดนตรีคลาสสิก", "กล่องดนตรีขี้เล่น", "กล่องดนตรีระยิบระยับ")
            MusicStyle.HARP -> listOf("ฮาร์ปพลิ้วไหว", "ฮาร์ปสองทำนอง", "ฮาร์ปถาม-ตอบ")
            MusicStyle.JAZZ -> listOf("แจ๊สคาเฟ่", "แจ๊สสวิง", "แจ๊สบอสซา")
            MusicStyle.SAMBA -> listOf("แซมบ้าริมหาด", "แซมบ้าเทศกาล", "แซมบ้าสบายๆ")
            MusicStyle.FLUTE -> listOf("ขลุ่ยกับฮาร์ป", "ขลุ่ยคู่กับกีตาร์", "ขลุ่ยกับกล่องดนตรี")
        }
        val en = when (style) {
            MusicStyle.THAI_LOCAL -> listOf("Solo ranat", "Ranat & gong circle", "Ranat, khim & drum")
            MusicStyle.MUSIC_BOX -> listOf("Classic music box", "Playful music box", "Twinkling music box")
            MusicStyle.HARP -> listOf("Flowing harp", "Harp duet", "Harp call & answer")
            MusicStyle.JAZZ -> listOf("Café jazz", "Swing", "Bossa jazz")
            MusicStyle.SAMBA -> listOf("Beach samba", "Carnival samba", "Easy samba")
            MusicStyle.FLUTE -> listOf("Flute & harp", "Flute duo & guitar", "Flute & music box")
        }
        return (if (thai) th else en)[variant.coerceIn(0, 2)]
    }

    fun render(choice: MusicChoice): ShortArray {
        val bars = bars(choice.style, choice.tempo)
        val beat = 60.0 / bpm(choice.style, choice.tempo)
        val buf = FloatArray(LENGTH)
        val song = Song(buf, beat, bars, choice.variant.coerceIn(0, 2), Random(choice.style.ordinal * 97 + choice.variant * 13 + 5))
        when (choice.style) {
            MusicStyle.THAI_LOCAL -> song.thai()
            MusicStyle.MUSIC_BOX -> song.musicBox()
            MusicStyle.HARP -> song.harp()
            MusicStyle.JAZZ -> song.jazz()
            MusicStyle.SAMBA -> song.samba()
            MusicStyle.FLUTE -> song.flute()
        }
        // Even loudness across styles: aim for the same average level, and round off
        // the rare loud peaks softly instead of turning the whole piece down.
        var sum = 0.0
        for (x in buf) sum += x * x
        val rms = kotlin.math.sqrt(sum / LENGTH).toFloat().coerceAtLeast(1e-4f)
        val gain = 0.14f / rms
        return ShortArray(LENGTH) { (kotlin.math.tanh(buf[it] * gain) * 0.9f * Short.MAX_VALUE).toInt().toShort() }
    }

    /** A melody note: start and length in beats, scale index, loudness. */
    private data class Note(val start: Double, val len: Double, val deg: Int, val vel: Double = 1.0)

    private class Song(val buf: FloatArray, val beat: Double, val bars: Int, val v: Int, val rnd: Random) {
        val n = buf.size
        fun at(beats: Double) = (beats * beat * RATE).roundToInt()
        fun sec(beats: Double) = beats * beat

        fun add(start: Int, len: Int, sample: (Int, Double) -> Double) {
            for (i in 0 until len) buf[((start + i) % n + n) % n] += sample(i, i.toDouble() / RATE).toFloat()
        }

        // ------------------------------------------------------------ Instruments

        fun mallet(start: Int, f: Double, amp: Double) = add(start, (1.6 * RATE).toInt()) { i, t ->
            (sin(2 * PI * f * t) * exp(-t * 3.4) + 0.3 * sin(2 * PI * f * 3.93 * t) * exp(-t * 10.0)) * min(1.0, i / 50.0) * amp
        }

        fun gong(start: Int, f: Double, amp: Double) = add(start, (2.5 * RATE).toInt()) { i, t ->
            (sin(2 * PI * f * t) + 0.45 * sin(2 * PI * f * 2.02 * t) * exp(-t * 2.0) + 0.2 * sin(2 * PI * f * 3.1 * t) * exp(-t * 4.0)) *
                exp(-t * 1.4) * min(1.0, i / 40.0) * amp
        }

        fun bell(start: Int, f: Double, amp: Double) = add(start, (2.0 * RATE).toInt()) { i, t ->
            (sin(2 * PI * f * t) * exp(-t * 2.8) + 0.35 * sin(2 * PI * f * 2.76 * t) * exp(-t * 6.0) + 0.15 * sin(2 * PI * f * 5.4 * t) * exp(-t * 12.0)) *
                min(1.0, i / 12.0) * amp
        }

        /** Bright, happy flute: quick attack, light vibrato only on long notes, very little breath. */
        fun flute(start: Int, f: Double, durSec: Double, amp: Double) {
            val noise = Random(start)
            val vibDepth = if (durSec > 0.5) 0.0035 else 0.0
            add(start, ((durSec + 0.1) * RATE).toInt()) { _, t ->
                val env = min(1.0, t / 0.025) * (if (t > durSec) exp(-(t - durSec) * 40) else 1.0 - 0.15 * min(1.0, t / 0.4))
                val vib = 1 + vibDepth * sin(2 * PI * 5.6 * t) * min(1.0, t / 0.3)
                (sin(2 * PI * f * vib * t) + 0.32 * sin(4 * PI * f * vib * t) + 0.1 * sin(6 * PI * f * vib * t) + 0.015 * (noise.nextDouble() - 0.5)) * env * amp
            }
        }

        /** Plucked string (Karplus–Strong). */
        fun pluck(start: Int, f: Double, amp: Double, durSec: Double = 2.0, damp: Double = 0.996) {
            val period = (RATE / f).toInt().coerceAtLeast(2)
            val noise = Random(start xor (f * 10).toInt())
            val ring = DoubleArray(period) { noise.nextDouble() * 2 - 1 }
            var idx = 0
            add(start, (durSec * RATE).toInt()) { _, _ ->
                val next = (idx + 1) % period
                val out = ring[idx]
                ring[idx] = damp * 0.5 * (ring[idx] + ring[next])
                idx = next
                out * amp
            }
        }

        fun strum(start: Int, freqs: List<Double>, amp: Double, durSec: Double = 0.6, down: Boolean = true) {
            val order = if (down) freqs else freqs.reversed()
            order.forEachIndexed { k, f -> pluck(start + k * (0.012 * RATE).toInt(), f, amp, durSec, 0.99) }
        }

        fun epiano(start: Int, f: Double, durSec: Double, amp: Double) = add(start, ((durSec + 0.3) * RATE).toInt()) { i, t ->
            val env = exp(-t * 1.5) * min(1.0, i / 40.0) * (if (t > durSec) exp(-(t - durSec) * 12) else 1.0)
            sin(2 * PI * f * t + 0.7 * exp(-t * 4) * sin(2 * PI * f * 2 * t)) * env * amp
        }

        fun bass(start: Int, f: Double, durSec: Double, amp: Double) = add(start, ((durSec + 0.08) * RATE).toInt()) { i, t ->
            (sin(2 * PI * f * t) + 0.3 * sin(4 * PI * f * t)) * exp(-t * 1.8) * min(1.0, i / 50.0) * (if (t > durSec) exp(-(t - durSec) * 35) else 1.0) * amp
        }

        fun drum(start: Int, amp: Double, pitch: Double = 60.0) = add(start, (0.4 * RATE).toInt()) { _, t ->
            sin(2 * PI * (pitch + pitch * exp(-t * 22)) * t) * exp(-t * 8) * amp
        }

        fun shaker(start: Int, amp: Double) {
            val noise = Random(start)
            add(start, (0.07 * RATE).toInt()) { _, t -> (noise.nextDouble() * 2 - 1) * exp(-t * 60) * amp }
        }

        fun ching(start: Int, open: Boolean) = add(start, ((if (open) 0.9 else 0.15) * RATE).toInt()) { i, t ->
            (sin(2 * PI * 2637.0 * t) + 0.6 * sin(2 * PI * 3951.0 * t)) * exp(-t * (if (open) 5.0 else 30.0)) * 0.05 * min(1.0, i / 20.0)
        }

        // ------------------------------------------------------------ Harmony

        val major = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val majorPenta = intArrayOf(0, 2, 4, 7, 9)
        val thaiPenta = intArrayOf(0, 1, 2, 4, 5) // degrees of the Thai 7-tone equal scale

        fun freq(root: Double, scale: IntArray, steps: Int, idx: Int): Double {
            val octave = Math.floorDiv(idx, scale.size)
            val d = scale[Math.floorMod(idx, scale.size)]
            return root * 2.0.pow((d + steps * octave) / steps.toDouble())
        }

        fun maj(root: Double, idx: Int) = freq(root, major, 12, idx)

        fun chord(root: Double, degree: Int, seventh: Boolean = false): List<Double> =
            (listOf(degree, degree + 2, degree + 4) + if (seventh) listOf(degree + 6) else emptyList()).map { maj(root, it) }

        /** Chord root (scale degree) for each bar, A sections and B sections use different progressions. */
        fun progression(bar: Int, form: List<Char>): Int {
            val a = listOf(listOf(0, 4, 5, 3), listOf(0, 5, 3, 4), listOf(0, 3, 4, 0))[v]
            val b = listOf(listOf(3, 4, 2, 5), listOf(3, 0, 3, 4), listOf(5, 3, 1, 4))[v]
            val block = form[bar / 4]
            val last = bar == bars - 1
            return if (last) 0 else (if (block == 'B') b else a)[bar % 4]
        }

        // ------------------------------------------------------------ Melody

        /** Song form in 4-bar blocks; the last block always resolves (A'). */
        val form: List<Char> = run {
            val blocks = bars / 4
            val base = "AaBa"
            val f = (0 until blocks).map { base[it % 4] }.toMutableList()
            f[blocks - 1] = 'a'
            f
        }

        /** Rhythm pools per variant: straight, syncopated/dotted, flowing. Each bar sums to 4 beats. */
        fun rhythms(slowNotes: Boolean): List<List<Double>> = when (v) {
            0 -> if (slowNotes) listOf(listOf(2.0, 2.0), listOf(1.0, 1.0, 2.0), listOf(3.0, 1.0))
            else listOf(listOf(1.0, 1.0, 1.0, 1.0), listOf(1.0, 1.0, 2.0), listOf(2.0, 1.0, 1.0))
            1 -> if (slowNotes) listOf(listOf(1.5, 0.5, 2.0), listOf(2.0, 1.5, 0.5), listOf(1.0, 1.0, 2.0))
            else listOf(listOf(0.75, 0.25, 1.0, 1.0, 1.0), listOf(1.5, 0.5, 1.0, 1.0), listOf(0.5, 0.5, 1.0, 0.5, 1.5))
            else -> if (slowNotes) listOf(listOf(1.0, 3.0), listOf(2.0, 1.0, 1.0), listOf(4.0))
            else listOf(listOf(0.5, 0.5, 0.5, 0.5, 2.0), listOf(1.0, 0.5, 0.5, 2.0), listOf(0.5, 0.5, 1.0, 1.0, 1.0))
        }

        /**
         * Builds a 4-bar phrase following a contour (0 arch, 1 descending, 2 wave) around [base].
         * Returns notes relative to the phrase start.
         */
        fun phrase(base: Int, contour: Int, pool: List<List<Double>>, endDeg: Int, range: Int): List<Note> {
            val bars4 = (0 until 4).map { pool[rnd.nextInt(pool.size)] }
            val total = bars4.sumOf { it.size }
            val out = mutableListOf<Note>()
            var t = 0.0
            var k = 0
            for (bar in bars4) for (d in bar) {
                val pos = k.toDouble() / (total - 1).coerceAtLeast(1)
                val shape = when (contour) {
                    0 -> sin(pos * PI) * range
                    1 -> (1 - pos) * range
                    else -> sin(pos * 2 * PI) * range * 0.6
                }
                val deg = if (k == total - 1) endDeg else base + shape.roundToInt() + listOf(-1, 0, 0, 1).random(rnd)
                out += Note(t, d, deg, if (t % 4.0 == 0.0) 1.0 else 0.85)
                t += d; k++
            }
            return out
        }

        /** The whole melody: A, A' (ends home), B (higher, contrasting), A'. */
        fun melody(scaleSize: Int, slowNotes: Boolean): List<Note> {
            val pool = rhythms(slowNotes)
            val home = scaleSize // one octave up
            val a = phrase(home, listOf(0, 2, 1)[v], pool, home + 2, 3)
            val aHome = a.dropLast(1) + a.last().copy(deg = home)
            val b = phrase(home + 2, listOf(1, 0, 2)[v], rhythms(!slowNotes && v != 1), home + 4, 2)
            val out = mutableListOf<Note>()
            form.forEachIndexed { i, block ->
                val src = when (block) { 'A' -> a; 'B' -> b; else -> aHome }
                out += src.map { it.copy(start = it.start + i * 16.0) }
            }
            return out
        }

        // ------------------------------------------------------------ Styles

        fun thai() {
            val root = listOf(293.66, 329.63, 261.63)[v]
            val mel = melody(5, slowNotes = false)
            for (nt in mel) {
                val f = freq(root, thaiPenta, 7, nt.deg)
                mallet(at(nt.start), f, 0.5 * nt.vel)
                when (v) {
                    1 -> mallet(at(nt.start), f / 2, 0.22) // ranat thum doubling an octave below
                    2 -> if (nt.len >= 1.0) { // khim tremolo on longer notes
                        var t = 0.25
                        while (t < nt.len) { mallet(at(nt.start + t), f, 0.18 * (1 - t / nt.len)); t += 0.25 }
                    }
                }
            }
            for (bar in 0 until bars) {
                val b = bar * 4.0
                ching(at(b + 1), false); ching(at(b + 3), true)
                if (v == 1) for (k in 0 until 4) gong(at(b + k), freq(root, thaiPenta, 7, listOf(0, 2, 4, 2)[k]) / 2, 0.15)
                if (v == 2) { drum(at(b), 0.35, 70.0); drum(at(b + 1.5), 0.2, 110.0); drum(at(b + 2), 0.3, 70.0); drum(at(b + 3.5), 0.2, 110.0) }
                if (v == 0 && bar % 2 == 0) gong(at(b), root / 2, 0.18)
            }
        }

        fun musicBox() {
            val root = listOf(523.25, 587.33, 698.46)[v]
            for (nt in melody(5, slowNotes = false)) {
                bell(at(nt.start), freq(root, majorPenta, 12, nt.deg - 5), 0.45 * nt.vel)
                if (v == 1 && form[(nt.start / 16).toInt().coerceAtMost(form.size - 1)] == 'B') // harmony a third below in B
                    bell(at(nt.start), freq(root, majorPenta, 12, nt.deg - 6), 0.22)
            }
            for (bar in 0 until bars) {
                val c = chord(root / 2, progression(bar, form))
                val b = bar * 4.0
                when (v) {
                    0 -> c.forEachIndexed { k, f -> bell(at(b + k * 0.5), f, 0.17) }
                    1 -> listOf(0, 1, 2, 1, 0, 1, 2, 1).forEachIndexed { k, ci -> bell(at(b + k * 0.5), c[ci], 0.12) }
                    else -> { // sparkles: high bells on the off-beats + low chord
                        c.forEach { bell(at(b), it, 0.1) }
                        for (k in 0 until 4) bell(at(b + k + 0.5), c[k % 3] * 4, 0.06)
                    }
                }
            }
        }

        fun harp() {
            val root = listOf(261.63, 293.66, 349.23)[v]
            val pattern = listOf(listOf(0, 1, 2, 3, 2, 1, 0, 1), listOf(0, 2, 1, 3, 0, 2, 1, 3), listOf(0, 1, 2, 3, 3, 2, 1, 0))[v]
            for (bar in 0 until bars) {
                val deg = progression(bar, form)
                val c = chord(root / 2, deg) + maj(root / 2, deg + 7)
                pattern.forEachIndexed { k, ci -> pluck(at(bar * 4.0 + k * 0.5), c[ci], 0.3, 2.2, 0.997) }
            }
            val mel = melody(7, slowNotes = true)
            for (nt in mel) {
                pluck(at(nt.start), maj(root, nt.deg - 7), 0.42 * nt.vel, 2.5, 0.998)
                if (v == 1) pluck(at(nt.start), maj(root, nt.deg - 9), 0.2, 2.5, 0.998) // second voice in thirds
                if (v == 2) pluck(at(nt.start + 1.0), maj(root * 2, nt.deg - 7), 0.15, 1.5, 0.997) // echo answer an octave up
            }
        }

        fun jazz() {
            val root = listOf(261.63, 233.08, 311.13)[v]
            val swing = if (v == 2) 0.5 else 0.66 // v2 is bossa (straight)
            for (bar in 0 until bars) {
                val b = bar * 4.0
                val deg = listOf(listOf(1, 4, 0, 5), listOf(0, 5, 1, 4), listOf(1, 4, 0, 0))[v][bar % 4].let { if (bar == bars - 1) 0 else it }
                val c = chord(root, deg, seventh = true)
                when (v) {
                    0 -> listOf(1.0, 3.0).forEach { x -> c.forEach { epiano(at(b + x), it, sec(0.6), 0.11) } }
                    1 -> listOf(0.0, 1.0 + swing, 3.0).forEach { x -> c.forEach { epiano(at(b + x), it, sec(0.4), 0.1) } }
                    else -> listOf(0.0, 1.5, 3.0).forEach { x -> c.forEach { epiano(at(b + x), it, sec(1.0), 0.1) } }
                }
                val bassRoot = maj(root / 4, deg)
                if (v == 2) { bass(at(b), bassRoot, sec(1.4), 0.35); bass(at(b + 1.5), bassRoot * 1.5, sec(0.4), 0.25); bass(at(b + 2), bassRoot, sec(1.4), 0.35); bass(at(b + 3.5), bassRoot * 1.5, sec(0.4), 0.25) }
                else listOf(0, 2, 4, 5).forEachIndexed { k, s -> bass(at(b + k), maj(bassRoot, s), sec(0.9), 0.35) }
                for (k in 0 until 4) { shaker(at(b + k), 0.07); shaker(at(b + k + swing), 0.045) }
            }
            for (nt in melody(7, slowNotes = false)) {
                val len = if (v == 1) nt.len * 0.7 else nt.len * 0.9
                val start = if (v == 1 && nt.start % 1.0 == 0.5) nt.start + (swing - 0.5) else nt.start
                epiano(at(start), maj(root * 2, nt.deg - 7), sec(len), 0.3 * nt.vel)
            }
        }

        fun samba() {
            val root = listOf(293.66, 329.63, 349.23)[v]
            val hits = listOf(listOf(0.0, 0.75, 1.5, 2.0, 2.75, 3.5), listOf(0.0, 0.5, 1.25, 2.0, 2.5, 3.25, 3.75), listOf(0.0, 1.5, 2.0, 3.0))[v]
            for (bar in 0 until bars) {
                val b = bar * 4.0
                val deg = progression(bar, form)
                val c = chord(root / 2, deg)
                hits.forEachIndexed { k, h -> strum(at(b + h), c, 0.12, 0.45, down = k % 2 == 0) }
                drum(at(b + 1), 0.4); drum(at(b + 3), 0.55)
                val step = if (v == 2) 0.5 else 0.25
                var s = 0.0
                while (s < 4.0) { shaker(at(b + s), if (s % 1.0 == 0.0) 0.09 else 0.05); s += step }
                if (v == 1) { drum(at(b + 0.5), 0.15, 180.0); drum(at(b + 2.5), 0.15, 180.0) } // tamborim
                bass(at(b), maj(root / 4, deg), sec(1.5), 0.3)
                bass(at(b + 2), maj(root / 4, deg + 4), sec(1.5), 0.25)
            }
            for (nt in melody(7, slowNotes = false)) {
                val f = maj(root * 2, nt.deg - 7)
                if (v == 2) epiano(at(nt.start), f, sec(nt.len * 0.85), 0.28 * nt.vel)
                else flute(at(nt.start), f, sec(nt.len * 0.8), 0.25 * nt.vel)
            }
        }

        fun flute() {
            val root = listOf(392.0, 440.0, 349.23)[v] // G, A, F major: bright keys
            val mel = melody(5, slowNotes = false)
            for (nt in mel) {
                val f = freq(root * 2, majorPenta, 12, nt.deg - 5)
                // Short, bouncy notes; only long notes are held.
                val held = if (nt.len >= 2.0) nt.len * 0.9 else nt.len * 0.6
                flute(at(nt.start), f, sec(held), 0.34 * nt.vel)
                if (v == 1) flute(at(nt.start), freq(root * 2, majorPenta, 12, nt.deg - 6), sec(held), 0.16) // second flute below
            }
            for (bar in 0 until bars) {
                val b = bar * 4.0
                val deg = progression(bar, form)
                val c = chord(root / 2, deg)
                when (v) {
                    0 -> listOf(0, 1, 2, 1, 0, 1, 2, 1).forEachIndexed { k, ci -> pluck(at(b + k * 0.5), c[ci], 0.2, 1.6, 0.996) }
                    1 -> { listOf(0.0, 1.0, 1.5, 2.5, 3.0).forEachIndexed { k, h -> strum(at(b + h), c, 0.1, 0.4, k % 2 == 0) }; for (k in 0 until 8) shaker(at(b + k * 0.5), 0.04) }
                    else -> { c.forEachIndexed { k, f -> bell(at(b + k), f * 2, 0.12) }; pluck(at(b), maj(root / 4, deg), 0.25, 0.6, 0.98); pluck(at(b + 2), maj(root / 4, deg + 4), 0.2, 0.6, 0.98) }
                }
            }
        }
    }
}
