package th.trip.planner.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import th.trip.planner.logic.SlideTimeline
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns a [SlideTimeline] into an MP4 file (H.264 video + AAC audio) on the phone itself.
 * Frames are drawn with [FrameRenderer] into a bitmap and handed to the hardware encoder as YUV.
 */
object VideoExporter {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FPS = 24
    private const val AUDIO_RATE = 44100
    private const val TIMEOUT_US = 10_000L

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    /**
     * Writes the video to [out]. [soundtrack] is mono 16-bit PCM at [pcmRate] (or null for silence).
     * [onProgress] gets 0..1. Cancelling the coroutine stops the export and deletes the partial file.
     */
    suspend fun export(
        out: File,
        timeline: SlideTimeline.Timeline,
        renderer: FrameRenderer,
        soundtrack: ShortArray?,
        pcmRate: Int,
        onProgress: (Float) -> Unit,
    ) {
        val tmp = File(out.parentFile, out.name + ".part")
        tmp.delete()
        val audio = soundtrack?.takeIf { s -> s.any { it.toInt() != 0 } }?.let { encodeAudio(it, pcmRate) { p -> onProgress(p * 0.1f) } }
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxing = false
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val argb = IntArray(WIDTH * HEIGHT)
            val frames = kotlin.math.ceil(timeline.duration * FPS).toInt()
            val info = MediaCodec.BufferInfo()
            var videoTrack = -1
            var audioTrack = -1
            var audioNext = 0
            var frame = 0
            var inputDone = false
            var outputDone = false

            fun writeAudioUpTo(ptsUs: Long) {
                val a = audio ?: return
                while (audioNext < a.samples.size && a.samples[audioNext].ptsUs <= ptsUs) {
                    val s = a.samples[audioNext++]
                    val bi = MediaCodec.BufferInfo().apply { set(0, s.data.size, s.ptsUs, s.flags) }
                    muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), bi)
                }
            }

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val pts = frame * 1_000_000L / FPS
                        if (frame >= frames) {
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            renderer.draw(canvas, timeline.frameAt(frame.toDouble() / FPS))
                            bitmap.getPixels(argb, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                            val image = codec.getInputImage(idx) ?: error("encoder gave no input image")
                            fillYuv(image, argb)
                            codec.queueInputBuffer(idx, 0, WIDTH * HEIGHT * 3 / 2, pts, 0)
                            frame++
                            onProgress(0.1f + 0.9f * frame / frames)
                        }
                    }
                }
                // Drain whatever the encoder has ready.
                while (true) {
                    val o = codec.dequeueOutputBuffer(info, if (inputDone) TIMEOUT_US else 0)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrack = muxer.addTrack(codec.outputFormat)
                        if (audio != null) audioTrack = muxer.addTrack(audio.format)
                        muxer.start()
                        muxing = true
                        continue
                    }
                    if (o < 0) continue
                    val buf = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        writeAudioUpTo(info.presentationTimeUs)
                        muxer.writeSampleData(videoTrack, buf, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputDone = true; break }
                }
            }
            if (muxing) writeAudioUpTo(Long.MAX_VALUE)
            bitmap.recycle()
        } catch (e: Throwable) {
            runCatching { codec.stop() }; codec.release()
            runCatching { if (muxing) muxer.stop() }; runCatching { muxer.release() }
            tmp.delete()
            throw e
        }
        codec.stop(); codec.release()
        muxer.stop(); muxer.release()
        renderer.release()
        out.delete()
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
    }

    /** Copies the finished video into the phone's Movies folder so it also shows in the gallery (Android 10+). */
    fun saveToGallery(ctx: Context, file: File, name: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ThaiTrip")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            r.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            r.update(uri, values, null, null)
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ audio

    private class EncodedAudio(val format: MediaFormat, val samples: List<Sample>)

    private fun encodeAudio(pcm: ShortArray, rate: Int, onProgress: (Float) -> Unit): EncodedAudio {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val total = (pcm.size.toLong() * AUDIO_RATE / rate).toInt()
        val step = rate.toDouble() / AUDIO_RATE
        fun sampleAt(i: Int): Short { // linear interpolation up to 44.1 kHz
            val x = i * step
            val a = x.toInt().coerceAtMost(pcm.size - 1)
            val b = (a + 1).coerceAtMost(pcm.size - 1)
            val f = x - a
            return (pcm[a] * (1 - f) + pcm[b] * f).toInt().toShort()
        }
        val samples = mutableListOf<Sample>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var pos = 0
        var inputDone = false
        var done = false
        try {
            while (!done) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.clear()
                        val pts = pos * 1_000_000L / AUDIO_RATE
                        val n = minOf(buf.remaining() / 2, total - pos)
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            for (k in 0 until n) buf.putShort(sampleAt(pos + k))
                            codec.queueInputBuffer(idx, 0, n * 2, pts, 0)
                            pos += n
                            onProgress(pos.toFloat() / total)
                        }
                    }
                }
                while (true) {
                    val o = codec.dequeueOutputBuffer(info, if (inputDone) TIMEOUT_US else 0)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outFormat = codec.outputFormat; continue }
                    if (o < 0) continue
                    val buf = codec.getOutputBuffer(o)!!
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset); buf.get(bytes)
                        samples += Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { done = true; break }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
        }
        return EncodedAudio(outFormat ?: error("audio encoder gave no format"), samples)
    }

    // ------------------------------------------------------------------ colour conversion

    /** ARGB pixels → the encoder's YUV 4:2:0 image, whatever its plane layout. */
    private fun fillYuv(image: android.media.Image, argb: IntArray) {
        val planes = image.planes
        val yPlane = planes[0]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val row = ByteArray(WIDTH)
        for (j in 0 until HEIGHT) {
            val off = j * WIDTH
            for (i in 0 until WIDTH) {
                val c = argb[off + i]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                row[i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
            if (yPix == 1) { yBuf.position(j * yRow); yBuf.put(row) }
            else for (i in 0 until WIDTH) yBuf.put(j * yRow + i * yPix, row[i])
        }
        val uPlane = planes[1]; val vPlane = planes[2]
        val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
        val uRow = uPlane.rowStride; val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride; val vPix = vPlane.pixelStride
        for (j in 0 until HEIGHT / 2) {
            for (i in 0 until WIDTH / 2) {
                // Average the 2×2 block for smoother colour edges.
                var r = 0; var g = 0; var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = argb[(j * 2 + dy) * WIDTH + i * 2 + dx]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                }
                r = r shr 2; g = g shr 2; b = b shr 2
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                uBuf.put(j * uRow + i * uPix, u.toByte())
                vBuf.put(j * vRow + i * vPix, v.toByte())
            }
        }
    }
}
