package th.trip.planner.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import th.trip.planner.logic.SlideTimeline
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** What one place contributes to the video: its title card text and its photos in order. */
data class SectionContent(
    val name: String,
    val subtitle: String,
    val dates: String,
    val badge: String,
    val photos: List<File>,
)

/** Draws timeline frames: decorated title cards and photos (fitted over a soft blurred copy of themselves). */
class FrameRenderer(private val w: Int, private val h: Int, private val sections: List<SectionContent>) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val titles = HashMap<Int, Bitmap>()
    private val slides = object : LinkedHashMap<Pair<Int, Int>, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, Bitmap>?): Boolean {
            if (size > 4) { eldest?.value?.recycle(); return true }
            return false
        }
    }

    fun draw(canvas: Canvas, frame: SlideTimeline.Frame) {
        canvas.drawColor(Color.BLACK)
        for (layer in frame.layers) {
            val bmp = when (layer.kind) {
                SlideTimeline.Kind.TITLE -> titleCard(layer.section)
                SlideTimeline.Kind.PHOTO -> slide(layer.section, layer.photo)
            } ?: continue
            canvas.save()
            // Ken Burns: slow zoom, alternating in and out with a little drift.
            var scale = layer.scale
            var panX = 0f
            var panY = 0f
            if (layer.kind == SlideTimeline.Kind.PHOTO) {
                val zoomIn = layer.photo % 2 == 0
                val z = if (zoomIn) layer.kb else 1 - layer.kb
                scale *= 1f + 0.07f * z
                val dir = if (layer.photo % 4 < 2) 1 else -1
                panX = dir * 0.015f * w * (layer.kb - 0.5f)
                panY = -0.01f * h * (layer.kb - 0.5f)
            }
            canvas.translate(layer.dx * w + w / 2f + panX, h / 2f + panY)
            canvas.scale(scale, scale)
            canvas.translate(-w / 2f, -h / 2f)
            paint.alpha = (layer.alpha.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawBitmap(bmp, 0f, 0f, paint)
            canvas.restore()
        }
        paint.alpha = 255
        if (frame.brightness < 1f) canvas.drawColor(Color.argb(((1 - frame.brightness.coerceIn(0f, 1f)) * 255).toInt(), 0, 0, 0))
    }

    fun release() {
        titles.values.forEach { it.recycle() }; titles.clear()
        slides.values.forEach { it.recycle() }; slides.clear()
    }

    // ------------------------------------------------------------------ photos

    private fun slide(section: Int, photo: Int): Bitmap? {
        val key = section to photo
        slides[key]?.let { return it }
        val file = sections.getOrNull(section)?.photos?.getOrNull(photo) ?: return null
        val src = decode(file) ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // Background: the same photo, cropped to fill, heavily blurred and darkened.
        val small = Bitmap.createScaledBitmap(src, max(1, src.width / 24), max(1, src.height / 24), true)
        val fill = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val bw = src.width * fill
        val bh = src.height * fill
        c.drawBitmap(small, null, RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2), paint)
        small.recycle()
        c.drawColor(Color.argb(110, 0, 0, 0))
        // Foreground: the whole photo, fitted.
        val fit = min(w.toFloat() / src.width, h.toFloat() / src.height)
        val fw = src.width * fit
        val fh = src.height * fit
        c.drawBitmap(src, null, RectF((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2), paint)
        src.recycle()
        slides[key] = out
        return out
    }

    /** Decodes a photo at about screen size, turned upright according to its EXIF orientation. */
    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h / 2) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching { ExifInterface(file.path).rotationDegrees }.getOrDefault(0)
        if (rotation == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it != bmp) bmp.recycle() }
    }.getOrNull()

    // ------------------------------------------------------------------ title cards

    private val palettes = listOf(
        intArrayOf(0xFF0E6B5C.toInt(), 0xFF052E28.toInt()), // jade
        intArrayOf(0xFF4A2C82.toInt(), 0xFF1B0F3A.toInt()), // royal purple
        intArrayOf(0xFFC2482F.toInt(), 0xFF4A1020.toInt()), // sunset
        intArrayOf(0xFF1C5D99.toInt(), 0xFF0A1F3D.toInt()), // ocean
        intArrayOf(0xFF8E1B2C.toInt(), 0xFF2E0710.toInt()), // temple red
        intArrayOf(0xFF2E7D32.toInt(), 0xFF0B2A12.toInt()), // forest
    )
    private val gold = 0xFFE8C468.toInt()
    private val goldSoft = 0xFFF6E3A8.toInt()

    private fun titleCard(section: Int): Bitmap? {
        titles[section]?.let { return it }
        val s = sections.getOrNull(section) ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pal = palettes[section % palettes.size]

        // Background: diagonal gradient with a warm glow in the middle.
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), pal[0], pal[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w / 2f, h / 2f, w * 0.55f, Color.argb(70, 255, 236, 190), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // Little sparkles scattered around.
        val rnd = Random(section * 31 + 7)
        repeat(70) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            p.color = Color.argb(40 + rnd.nextInt(90), 255, 240, 200)
            c.drawCircle(x, y, 1f + rnd.nextFloat() * 2.2f, p)
        }

        // Double gold frame.
        val m1 = h * 0.06f
        val m2 = h * 0.085f
        p.style = Paint.Style.STROKE
        p.color = gold
        p.strokeWidth = h * 0.005f
        c.drawRoundRect(RectF(m1, m1, w - m1, h - m1), h * 0.03f, h * 0.03f, p)
        p.strokeWidth = h * 0.0022f
        p.color = goldSoft
        c.drawRoundRect(RectF(m2, m2, w - m2, h - m2), h * 0.02f, h * 0.02f, p)
        p.style = Paint.Style.FILL

        // Thai-style flame ornaments in the corners and lotus at the top and bottom centre.
        val o = h * 0.12f
        ornament(c, m2 + o * 0.35f, m2 + o * 0.35f, o, 45f)
        ornament(c, w - m2 - o * 0.35f, m2 + o * 0.35f, o, 135f)
        ornament(c, w - m2 - o * 0.35f, h - m2 - o * 0.35f, o, 225f)
        ornament(c, m2 + o * 0.35f, h - m2 - o * 0.35f, o, 315f)
        lotus(c, w / 2f, m2 + h * 0.005f, h * 0.07f, false)
        lotus(c, w / 2f, h - m2 - h * 0.005f, h * 0.07f, true)

        // Badge ("Stop 2 of 4").
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = goldSoft }
        text.textSize = h * 0.042f
        text.letterSpacing = 0.12f
        c.drawText(s.badge, w / 2f, h * 0.29f, text)

        // Place name: big, bold, shrunk to fit.
        text.letterSpacing = 0f
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.color = Color.WHITE
        text.textSize = h * 0.13f
        while (text.measureText(s.name) > w * 0.78f && text.textSize > h * 0.05f) text.textSize *= 0.94f
        text.setShadowLayer(h * 0.012f, 0f, h * 0.006f, Color.argb(160, 0, 0, 0))
        c.drawText(s.name, w / 2f, h * 0.5f, text)
        text.clearShadowLayer()

        // Divider with a diamond.
        p.color = gold
        p.strokeWidth = h * 0.003f
        val dy = h * 0.575f
        c.drawLine(w * 0.3f, dy, w * 0.47f, dy, p)
        c.drawLine(w * 0.53f, dy, w * 0.7f, dy, p)
        val d = h * 0.014f
        c.drawPath(Path().apply { moveTo(w / 2f, dy - d); lineTo(w / 2f + d, dy); lineTo(w / 2f, dy + d); lineTo(w / 2f - d, dy); close() }, p)

        // Province and dates.
        text.typeface = Typeface.DEFAULT
        text.color = Color.argb(230, 255, 255, 255)
        text.textSize = h * 0.05f
        if (s.subtitle.isNotBlank()) c.drawText(s.subtitle, w / 2f, h * 0.66f, text)
        text.color = goldSoft
        text.textSize = h * 0.042f
        c.drawText(s.dates, w / 2f, h * 0.735f, text)

        titles[section] = out
        return out
    }

    /** A kanok-like flame: three curling petals fanning out from a point. */
    private fun ornament(c: Canvas, x: Float, y: Float, size: Float, angle: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold }
        c.save()
        c.translate(x, y)
        c.rotate(angle)
        for ((i, a) in listOf(-38f, 0f, 38f).withIndex()) {
            c.save()
            c.rotate(a)
            val len = if (i == 1) size * 0.62f else size * 0.46f
            val wid = len * 0.32f
            val petal = Path().apply {
                moveTo(0f, 0f)
                cubicTo(wid, -len * 0.25f, wid * 0.9f, -len * 0.75f, wid * 0.25f, -len)
                cubicTo(wid * 0.05f, -len * 0.85f, -wid * 0.35f, -len * 0.8f, -wid * 0.15f, -len * 0.62f)
                cubicTo(-wid * 0.9f, -len * 0.45f, -wid * 0.7f, -len * 0.15f, 0f, 0f)
                close()
            }
            p.alpha = if (i == 1) 255 else 210
            c.drawPath(petal, p)
            c.restore()
        }
        p.alpha = 255
        c.drawCircle(0f, 0f, size * 0.07f, p)
        c.restore()
    }

    /** A small lotus sitting on (or hanging from) the frame. */
    private fun lotus(c: Canvas, x: Float, y: Float, size: Float, flip: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold }
        c.save()
        c.translate(x, y)
        if (flip) c.scale(1f, -1f)
        for (a in listOf(-50f, -25f, 0f, 25f, 50f)) {
            c.save()
            c.rotate(a)
            val len = if (a == 0f) size else size * 0.8f
            c.drawPath(Path().apply {
                moveTo(0f, 0f)
                quadTo(len * 0.28f, -len * 0.55f, 0f, -len)
                quadTo(-len * 0.28f, -len * 0.55f, 0f, 0f)
                close()
            }, p)
            c.restore()
        }
        c.restore()
    }
}
