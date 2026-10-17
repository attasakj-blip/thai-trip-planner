package th.trip.planner.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import th.trip.planner.audio.MusicGen
import th.trip.planner.model.MusicChoice
import th.trip.planner.model.MusicStyle
import th.trip.planner.model.Tempo
import kotlin.math.abs

class MusicGenTest {
    @Test fun everyPieceIsExactlyOneMinuteAndAudible() {
        for (s in MusicStyle.entries) for (t in Tempo.entries) for (v in 0..2) {
            val pcm = MusicGen.render(MusicChoice(s, t, v))
            assertEquals(MusicGen.LENGTH, pcm.size)
            val loud = pcm.count { abs(it.toInt()) > 1000 }
            assertTrue("$s $t $v is silent", loud > pcm.size / 50)
        }
    }

    @Test fun loopSeamIsSmooth() {
        for (s in MusicStyle.entries) for (v in 0..2) {
            val pcm = MusicGen.render(MusicChoice(s, Tempo.MEDIUM, v))
            // Wrapping from the end back to the start must be no rougher than any
            // step inside the piece (a note starting on beat 1 is allowed, a click is not).
            val seam = abs(pcm.last().toInt() - pcm.first().toInt())
            val inside = (1 until pcm.size).maxOf { abs(pcm[it].toInt() - pcm[it - 1].toInt()) }
            assertTrue("$s $v seam $seam > inside $inside", seam <= inside)
        }
    }

    @Test fun threePiecesPerStyleDiffer() {
        for (s in MusicStyle.entries) {
            val p = (0..2).map { MusicGen.render(MusicChoice(s, Tempo.MEDIUM, it)) }
            assertTrue(!p[0].contentEquals(p[1]) && !p[1].contentEquals(p[2]) && !p[0].contentEquals(p[2]))
        }
    }
}
