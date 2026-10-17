package th.trip.planner.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import th.trip.planner.logic.SlideTimeline.Kind
import th.trip.planner.model.Tempo
import th.trip.planner.model.Transition

class SlideTimelineTest {
    private val tl = SlideTimeline.build(listOf(
        SlideTimeline.Section(3, Tempo.MEDIUM, Transition.CROSSFADE_ZOOM),
        SlideTimeline.Section(0, Tempo.FAST, Transition.SLIDE), // no photos: skipped
        SlideTimeline.Section(2, Tempo.FAST, Transition.SLIDE),
    ))

    @Test fun eachPlaceStartsWithItsTitleCard() {
        assertEquals(2, tl.spans.size)
        val first = tl.spans[0]
        val f = tl.frameAt(first.start + 2.0)
        assertEquals(Kind.TITLE, f.layers.single().kind)
        assertEquals(1f, f.brightness, 1e-3f)
        assertEquals(0f, tl.frameAt(0.0).brightness, 1e-3f) // fades in from black
        val second = tl.spans[1]
        assertEquals(2, second.index)
        assertEquals(Kind.TITLE, tl.frameAt(second.start + 2.0).layers.single().kind)
    }

    @Test fun titleMorphsIntoFirstPhotoThenPhotosFollow() {
        val s = tl.spans[0]
        val mid = tl.frameAt(s.photoStart[0] + 0.7).layers
        assertEquals(listOf(Kind.TITLE, Kind.PHOTO), mid.map { it.kind })
        val held = tl.frameAt(s.photoStart[1] - 0.5).layers.single()
        assertEquals(0, held.photo)
        assertEquals(2, tl.frameAt(s.photoStart[2] + 2.0).layers.last().photo)
    }

    @Test fun lastPhotoFadesToBlackThenGap() {
        val s = tl.spans[0]
        assertTrue(tl.frameAt(s.fadeStart + SlideTimeline.FADE_OUT / 2).brightness in 0.3f..0.7f)
        val gap = tl.frameAt(s.end + 0.1)
        assertTrue(gap.layers.isEmpty())
        assertEquals(0f, gap.brightness, 1e-3f)
    }

    @Test fun musicLoopsSeamlesslyAndFadesWithEachPlace() {
        val rate = 100
        val piece = ShortArray(150) { (it * 100).toShort() } // 1.5 s piece, much shorter than the photos
        val pcm = tl.soundtrack(rate) { piece }
        val s = tl.spans[0]
        val from = (s.start * rate).toInt()
        val a = from + 300 // well inside the full-volume part, two loops in
        assertEquals(piece[300 % 150], pcm[a])
        assertEquals(piece[(301) % 150], pcm[a + 1])
        assertEquals(0, pcm[((s.end + 0.2) * rate).toInt()].toInt()) // silent in the gap
        assertTrue(kotlin.math.abs(pcm[from + 1].toInt()) < 50) // fading in
    }
}
