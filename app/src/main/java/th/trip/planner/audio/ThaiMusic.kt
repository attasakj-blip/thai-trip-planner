package th.trip.planner.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A short, calm piece in the style of Thai classical music, synthesised on the device so
 * there is no licensing issue. It uses the Thai 7-tone equal-tempered scale (each step is
 * 2^(1/7)) with a pentatonic melody on a ranat-like mallet tone, and a soft ching on the
 * off-beats. Note tails that run past the end are wrapped to the start, so the loop is seamless.
 */
object ThaiMusic {
    const val RATE = 22050

    // (pentatonic index, beats). Index 0..9 spans two octaves; -1 is a rest.
    private val melody = listOf(
        2 to 1.0, 3 to 1.0, 4 to 2.0, 3 to 1.0, 2 to 1.0, 1 to 2.0,
        2 to 1.0, 4 to 1.0, 5 to 1.0, 4 to 1.0, 3 to 2.0, 2 to 2.0,
        5 to 1.0, 6 to 1.0, 7 to 2.0, 6 to 1.0, 5 to 1.0, 4 to 2.0,
        3 to 1.0, 2 to 1.0, 1 to 1.0, 2 to 1.0, 0 to 4.0,
        4 to 1.0, 5 to 1.0, 4 to 1.0, 3 to 1.0, 2 to 2.0, 3 to 2.0,
        4 to 1.0, 2 to 1.0, 1 to 1.0, 0 to 1.0, 1 to 2.0, 2 to 6.0,
    )

    private fun freq(p: Int): Double {
        val steps = intArrayOf(0, 1, 2, 4, 5)
        val semis = steps[p % 5] + 7 * (p / 5)
        return 293.66 * 2.0.pow(semis / 7.0)
    }

    fun render(): ShortArray {
        val beat = 60.0 / 72.0
        val totalBeats = melody.sumOf { it.second }
        val n = (totalBeats * beat * RATE).toInt()
        val buf = FloatArray(n)
        var t = 0.0
        for ((p, d) in melody) {
            if (p >= 0) {
                mallet(buf, (t * RATE).toInt(), freq(p), 0.5f)
                // A softer octave-below note on long notes, like a second instrument.
                if (d >= 2.0) mallet(buf, (t * RATE).toInt(), freq(p) / 2, 0.25f)
            }
            t += d * beat
        }
        var b = 1.0
        while (b < totalBeats) { ching(buf, (b * beat * RATE).toInt()); b += 2.0 }
        val peak = buf.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-3f)
        return ShortArray(n) { (buf[it] / peak * 0.6f * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun mallet(buf: FloatArray, start: Int, f: Double, amp: Float) {
        val len = (1.8 * RATE).toInt()
        for (i in 0 until len) {
            val tt = i.toDouble() / RATE
            val attack = min(1.0, i / 60.0)
            val v = sin(2 * PI * f * tt) * exp(-tt * 3.2) + 0.3 * sin(2 * PI * f * 3.93 * tt) * exp(-tt * 9.0)
            buf[(start + i) % buf.size] += (v * attack * amp).toFloat()
        }
    }

    private fun ching(buf: FloatArray, start: Int) {
        val len = (0.9 * RATE).toInt()
        for (i in 0 until len) {
            val tt = i.toDouble() / RATE
            val v = (sin(2 * PI * 2637.0 * tt) + 0.6 * sin(2 * PI * 3951.0 * tt) + 0.3 * sin(2 * PI * 5274.0 * tt)) * exp(-tt * 5.0)
            buf[(start + i) % buf.size] += (v * 0.05 * min(1.0, i / 20.0)).toFloat()
        }
    }
}

/** Plays the rendered piece in an endless, gap-free loop. */
class LoopPlayer {
    private var track: AudioTrack? = null

    fun start(data: ShortArray) {
        stop()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(ThaiMusic.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        t.write(data, 0, data.size)
        t.setLoopPoints(0, data.size, -1)
        t.play()
        track = t
    }

    fun setVolume(v: Float) { track?.setVolume(v) }

    fun stop() {
        track?.runCatching { stop(); release() }
        track = null
    }
}
