package th.trip.planner.logic

import th.trip.planner.model.Tempo
import th.trip.planner.model.Transition
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos

/**
 * When everything happens in the slideshow video. For each place:
 *
 *   black → title card fades in → title holds → title morphs into the first photo →
 *   photos one after another (transition suited to the tempo) → last photo and music fade out → short black gap
 *
 * Music for a place starts with its title card, loops (the pieces are built to wrap
 * seamlessly) for as long as that place's photos last, and fades out with the last photo.
 * Pure Kotlin so it can be unit-tested without a phone.
 */
object SlideTimeline {
    const val TITLE_IN = 1.2
    const val TITLE_HOLD = 2.8
    const val TITLE_TO_PHOTO = 1.4
    const val FADE_OUT = 1.8
    const val GAP = 0.6
    const val TAIL = 0.6

    fun hold(t: Tempo) = when (t) { Tempo.SLOW -> 5.0; Tempo.MEDIUM -> 3.8; Tempo.FAST -> 2.6 }
    fun transitionSeconds(t: Transition) = when (t) { Transition.MORPH -> 1.4; Transition.CROSSFADE_ZOOM -> 1.1; Transition.SLIDE -> 0.8 }

    data class Section(val photos: Int, val tempo: Tempo, val transition: Transition)

    /** Times (seconds) for one place. photoStart[k] is when photo k starts coming in. */
    data class Span(
        val index: Int,
        val start: Double,
        val photoStart: List<Double>,
        val photoIn: List<Double>,
        val fadeStart: Double,
        val end: Double,
    )

    enum class Kind { TITLE, PHOTO }

    /** One picture drawn in a frame. dx is a horizontal shift in screen widths; kb is Ken Burns progress 0..1. */
    data class Layer(
        val kind: Kind,
        val section: Int,
        val photo: Int = -1,
        val alpha: Float = 1f,
        val scale: Float = 1f,
        val dx: Float = 0f,
        val kb: Float = 0f,
    )

    /** What to draw at one moment: layers back to front, then [brightness] (0 = black). */
    data class Frame(val layers: List<Layer>, val brightness: Float)

    class Timeline(val sections: List<Section>, val spans: List<Span>) {
        val duration: Double = (spans.lastOrNull()?.end ?: 0.0) + TAIL

        fun frameAt(t: Double): Frame {
            val span = spans.lastOrNull { t >= it.start } ?: return Frame(emptyList(), 0f)
            if (t >= span.end) return Frame(emptyList(), 0f) // black gap
            val sec = sections[span.index]
            val p0 = span.photoStart[0]
            if (t < p0) {
                val e = ease(((t - span.start) / TITLE_IN).coerceIn(0.0, 1.0))
                return Frame(listOf(Layer(Kind.TITLE, span.index, alpha = 1f, scale = (1.05 - 0.05 * e).toFloat())), e.toFloat())
            }
            val k = span.photoStart.indexOfLast { t >= it }
            val inDur = span.photoIn[k]
            val u = (t - span.photoStart[k]) / inDur
            val cur = photoLayer(span, k, t)
            val layers = if (u < 1) {
                val p = ease(u).toFloat()
                val prev = if (k == 0) Layer(Kind.TITLE, span.index) else photoLayer(span, k - 1, t)
                val tr = if (k == 0) Transition.MORPH else sec.transition
                listOf(outOf(prev, tr, p), into(cur, tr, p))
            } else listOf(cur)
            val brightness = if (t >= span.fadeStart) (1 - ease((t - span.fadeStart) / FADE_OUT)).toFloat() else 1f
            return Frame(layers, brightness)
        }

        /** Loudness of the place's music at time [t]: fades in with the title, out with the last photo. */
        fun musicGain(span: Span, t: Double): Double = when {
            t < span.start || t >= span.end -> 0.0
            t < span.start + TITLE_IN -> ease((t - span.start) / TITLE_IN)
            t >= span.fadeStart -> 1 - ease((t - span.fadeStart) / FADE_OUT)
            else -> 1.0
        }

        /**
         * The whole soundtrack: each place's 1-minute piece looped from its title card to its fade-out.
         * [track] gives the piece for a section, or null for no music.
         */
        fun soundtrack(rate: Int, track: (Int) -> ShortArray?): ShortArray {
            val out = ShortArray(ceil(duration * rate).toInt())
            for (span in spans) {
                val music = track(span.index) ?: continue
                if (music.isEmpty()) continue
                val from = (span.start * rate).toInt()
                val to = minOf(out.size, ceil(span.end * rate).toInt())
                for (n in from until to) {
                    val g = musicGain(span, n.toDouble() / rate)
                    out[n] = (music[(n - from) % music.size] * g).toInt().toShort()
                }
            }
            return out
        }

        private fun photoLayer(span: Span, k: Int, t: Double): Layer {
            // On screen from when it starts coming in until the next photo has fully replaced it.
            val gone = if (k + 1 < span.photoStart.size) span.photoStart[k + 1] + span.photoIn[k + 1] else span.end
            val life = gone - span.photoStart[k]
            val kb = ((t - span.photoStart[k]) / life).coerceIn(0.0, 1.0).toFloat()
            return Layer(Kind.PHOTO, span.index, k, kb = kb)
        }
    }

    fun build(sections: List<Section>): Timeline {
        val spans = mutableListOf<Span>()
        var t = 0.0
        sections.forEachIndexed { i, s ->
            if (s.photos <= 0) return@forEachIndexed
            val start = t
            val starts = mutableListOf<Double>()
            val ins = mutableListOf<Double>()
            var p = start + TITLE_IN + TITLE_HOLD
            repeat(s.photos) { k ->
                val inDur = if (k == 0) TITLE_TO_PHOTO else transitionSeconds(s.transition)
                starts += p; ins += inDur
                p += inDur + hold(s.tempo)
            }
            val fadeStart = p
            val end = fadeStart + FADE_OUT
            spans += Span(i, start, starts, ins, fadeStart, end)
            t = end + GAP
        }
        return Timeline(sections, spans)
    }

    private fun ease(x: Double) = 0.5 - 0.5 * cos(PI * x.coerceIn(0.0, 1.0))

    private fun outOf(l: Layer, tr: Transition, p: Float) = when (tr) {
        Transition.MORPH -> l.copy(alpha = 1f, scale = l.scale * (1 + 0.12f * p))
        Transition.CROSSFADE_ZOOM -> l.copy(alpha = 1f)
        Transition.SLIDE -> l.copy(dx = -p)
    }

    private fun into(l: Layer, tr: Transition, p: Float) = when (tr) {
        Transition.MORPH -> l.copy(alpha = p, scale = l.scale * (1.12f - 0.12f * p))
        Transition.CROSSFADE_ZOOM -> l.copy(alpha = p, scale = l.scale * (1.06f - 0.06f * p))
        Transition.SLIDE -> l.copy(dx = 1 - p)
    }
}
