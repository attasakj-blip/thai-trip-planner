package th.trip.planner.ui.scenes

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import th.trip.planner.model.TravelMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the progress scene should show. */
sealed class SceneKind {
    data class Moving(val mode: TravelMode) : SceneKind()
    data object AtPlace : SceneKind()
    data object Break : SceneKind()
    data object Waiting : SceneKind()
}

/**
 * A cute animated scene matching how the traveller is moving right now: a plane above the
 * clouds for flights, a train through the hills, a bus on the highway, a long-tail boat
 * among limestone islands, or a van on the road.
 */
@Composable
fun TravelScene(kind: SceneKind, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "scene")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "t")
    val bob by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "bob")
    Canvas(modifier) {
        when (kind) {
            is SceneKind.Moving -> when (kind.mode) {
                TravelMode.FLIGHT -> flight(t, bob)
                TravelMode.TRAIN -> train(t, bob)
                TravelMode.BUS -> road(t, bob, bus = true)
                TravelMode.CAR -> road(t, bob, bus = false)
                TravelMode.BOAT -> boat(t, bob)
            }
            SceneKind.AtPlace -> atPlace(t, bob)
            SceneKind.Break -> rest(t, bob)
            SceneKind.Waiting -> rest(t, bob)
        }
    }
}

private fun wrap(x: Float, w: Float, m: Float): Float {
    val span = w + 2 * m
    return ((x % span) + span) % span - m
}

private fun DrawScope.sky(top: Color, bottom: Color) =
    drawRect(Brush.verticalGradient(listOf(top, bottom)))

private fun DrawScope.cloud(cx: Float, cy: Float, s: Float, alpha: Float = 1f) {
    val c = Color.White.copy(alpha = alpha)
    drawCircle(c, 18f * s, Offset(cx, cy))
    drawCircle(c, 24f * s, Offset(cx + 22f * s, cy - 8f * s))
    drawCircle(c, 16f * s, Offset(cx + 44f * s, cy))
    drawRoundRect(c, Offset(cx - 10f * s, cy), Size(64f * s, 16f * s), CornerRadius(8f * s))
}

private fun DrawScope.flight(t: Float, bob: Float) {
    val w = size.width; val h = size.height
    sky(Color(0xFF8EC5FC), Color(0xFFE0F2FF))
    drawCircle(Color(0xFFFFE08A), h * 0.12f, Offset(w * 0.82f, h * 0.2f))
    // Far land seen through gaps in the clouds.
    for (i in 0 until 6) {
        val x = wrap(i * w / 4f - t * w * 0.4f, w, 80f)
        drawRoundRect(Color(0xFF9BD39B), Offset(x, h * 0.86f), Size(70f, 18f), CornerRadius(9f))
        drawRoundRect(Color(0xFFF2D49B), Offset(x + 40f, h * 0.9f), Size(50f, 12f), CornerRadius(6f))
    }
    for (i in 0 until 5) cloud(wrap(i * w / 3.5f - t * w * 1.2f, w, 120f), h * (0.25f + 0.13f * (i % 3)), 0.8f + 0.2f * (i % 2), 0.85f)
    // Plane.
    val cx = w * 0.45f; val cy = h * 0.5f + bob * 6f
    translate(cx, cy) {
        for (k in 1..4) drawLine(Color.White.copy(alpha = 0.7f), Offset(-70f - k * 30f, 0f), Offset(-55f - k * 30f, 0f), 4f, StrokeCap.Round)
        val wing = Path().apply { moveTo(-10f, 0f); lineTo(-35f, 38f); lineTo(-18f, 38f); lineTo(22f, 0f); close() }
        drawPath(wing, Color(0xFF5B8DEF))
        drawRoundRect(Color.White, Offset(-60f, -14f), Size(130f, 28f), CornerRadius(14f))
        val tail = Path().apply { moveTo(-58f, -6f); lineTo(-72f, -36f); lineTo(-50f, -36f); lineTo(-36f, -8f); close() }
        drawPath(tail, Color(0xFFE76F51))
        val nose = Path().apply { moveTo(60f, -14f); quadraticTo(86f, 0f, 60f, 14f); close() }
        drawPath(nose, Color.White)
        for (k in 0 until 6) drawCircle(Color(0xFF7FB3FF), 4f, Offset(-30f + k * 15f, -3f))
        val wing2 = Path().apply { moveTo(0f, 0f); lineTo(-22f, -26f); lineTo(-10f, -26f); lineTo(18f, 0f); close() }
        drawPath(wing2, Color(0xFF3F6FD1))
    }
}

private fun DrawScope.hills(t: Float, speed: Float, color: Color, baseY: Float, height: Float, count: Int) {
    val w = size.width
    for (i in 0 until count) {
        val x = wrap(i * w / (count - 1f) - t * w * speed, w, w / count)
        val p = Path().apply {
            moveTo(x - w / count, baseY); quadraticTo(x, baseY - height * (0.7f + 0.3f * (i % 2)), x + w / count, baseY); close()
        }
        drawPath(p, color)
    }
}

private fun DrawScope.tree(x: Float, ground: Float, s: Float) {
    drawRect(Color(0xFF8B5E3C), Offset(x - 3f * s, ground - 22f * s), Size(6f * s, 22f * s))
    drawCircle(Color(0xFF3FA34D), 16f * s, Offset(x, ground - 30f * s))
}

private fun DrawScope.train(t: Float, bob: Float) {
    val w = size.width; val h = size.height
    sky(Color(0xFFFFD6A5), Color(0xFFFFF1E0))
    drawCircle(Color(0xFFFFB347), h * 0.1f, Offset(w * 0.2f, h * 0.22f))
    hills(t, 0.15f, Color(0xFFB5D99C), h * 0.7f, h * 0.35f, 5)
    hills(t, 0.35f, Color(0xFF8CC084), h * 0.74f, h * 0.22f, 6)
    val ground = h * 0.78f
    drawRect(Color(0xFFC9B79C), Offset(0f, ground), Size(w, h - ground))
    // Sleepers and rails.
    for (i in 0 until 30) {
        val x = wrap(i * 30f - t * w * 2.5f, w, 30f)
        drawRect(Color(0xFF7A5C3E), Offset(x, ground + 8f), Size(10f, 14f))
    }
    drawLine(Color(0xFF555555), Offset(0f, ground + 10f), Offset(w, ground + 10f), 4f)
    for (i in 0 until 5) tree(wrap(i * w / 4f - t * w * 1.6f, w, 40f), ground, 1f)
    // Train: locomotive + two carriages, red and cream like Thai long-distance trains.
    val y = ground - 50f + bob * 1.5f
    val carW = w * 0.24f
    val start = w * 0.12f
    for (c in 0 until 3) {
        val x = start + c * (carW + 8f)
        val body = if (c == 2) Color(0xFFD62828) else Color(0xFFFFF3D6)
        drawRoundRect(body, Offset(x, y), Size(carW, 42f), CornerRadius(10f))
        drawRect(Color(0xFFD62828), Offset(x, y + 28f), Size(carW, 6f))
        val windows = if (c == 2) 1 else 4
        for (k in 0 until windows) drawRoundRect(Color(0xFF9AD0F5), Offset(x + 10f + k * (carW - 20f) / windows, y + 8f), Size((carW - 30f) / windows, 14f), CornerRadius(3f))
        for (k in 0..1) {
            val wx = x + carW * (0.2f + 0.6f * k)
            drawCircle(Color(0xFF333333), 8f, Offset(wx, y + 46f))
            rotate(t * 360f * 20f, Offset(wx, y + 46f)) { drawLine(Color.LightGray, Offset(wx - 6f, y + 46f), Offset(wx + 6f, y + 46f), 2f) }
        }
        if (c < 2) drawLine(Color(0xFF444444), Offset(x + carW, y + 30f), Offset(x + carW + 8f, y + 30f), 4f)
    }
    // Smoke puffs from the locomotive.
    val lx = start + 2 * (carW + 8f) + carW * 0.7f
    for (k in 0 until 3) {
        val p = (t * 6f + k / 3f) % 1f
        drawCircle(Color.White.copy(alpha = 0.6f * (1 - p)), 8f + 14f * p, Offset(lx - p * 60f, y - 10f - p * 40f))
    }
}

private fun DrawScope.road(t: Float, bob: Float, bus: Boolean) {
    val w = size.width; val h = size.height
    sky(Color(0xFFA0E7E5), Color(0xFFE8FFF8))
    cloud(wrap(w * 0.7f - t * w * 0.3f, w, 100f), h * 0.18f, 0.7f)
    cloud(wrap(w * 0.2f - t * w * 0.3f, w, 100f), h * 0.12f, 0.5f)
    hills(t, 0.12f, Color(0xFFBFE3B4), h * 0.62f, h * 0.25f, 5)
    val roadTop = h * 0.66f
    for (i in 0 until 6) tree(wrap(i * w / 5f - t * w * 1.4f, w, 40f), roadTop + 2f, 0.9f)
    drawRect(Color(0xFF5E5E66), Offset(0f, roadTop), Size(w, h * 0.24f))
    for (i in 0 until 12) {
        val x = wrap(i * w / 6f - t * w * 3f, w, 40f)
        drawRoundRect(Color.White, Offset(x, roadTop + h * 0.11f), Size(36f, 6f), CornerRadius(3f))
    }
    drawRect(Color(0xFF9ED38A), Offset(0f, roadTop + h * 0.24f), Size(w, h))
    val bodyW = if (bus) w * 0.46f else w * 0.3f
    val bodyH = if (bus) 62f else 46f
    val x = w * 0.27f
    val y = roadTop + h * 0.08f - bodyH + bob * 2f
    val color = if (bus) Color(0xFF2A6FDB) else Color(0xFFF4A261)
    drawRoundRect(color, Offset(x, y), Size(bodyW, bodyH), CornerRadius(14f))
    drawRect(Color(0xFFFFD166), Offset(x, y + bodyH * 0.62f), Size(bodyW, 6f))
    val nWin = if (bus) 5 else 3
    for (k in 0 until nWin) drawRoundRect(Color(0xFFDDF3FF), Offset(x + 10f + k * (bodyW - 20f) / nWin, y + 8f), Size((bodyW - 20f) / nWin - 6f, bodyH * 0.38f), CornerRadius(4f))
    drawRoundRect(Color(0xFFFFF59D), Offset(x + bodyW - 8f, y + bodyH * 0.55f), Size(8f, 8f), CornerRadius(2f))
    for (wx in listOf(x + bodyW * 0.2f, x + bodyW * 0.8f)) {
        val c = Offset(wx, y + bodyH + 2f)
        drawCircle(Color(0xFF222222), 13f, c)
        drawCircle(Color(0xFFBBBBBB), 5f, c)
        rotate(t * 360f * 30f, c) { drawLine(Color(0xFF888888), Offset(c.x - 10f, c.y), Offset(c.x + 10f, c.y), 2f) }
    }
}

private fun DrawScope.boat(t: Float, bob: Float) {
    val w = size.width; val h = size.height
    sky(Color(0xFF7FDBFF), Color(0xFFDFF7FF))
    drawCircle(Color(0xFFFFE066), h * 0.1f, Offset(w * 0.78f, h * 0.2f))
    val sea = h * 0.6f
    // Limestone karst islands, typical of the Andaman coast.
    for (i in 0 until 4) {
        val x = wrap(i * w / 3f - t * w * 0.25f, w, 90f)
        val p = Path().apply {
            moveTo(x - 40f, sea); cubicTo(x - 46f, sea - 70f, x - 10f, sea - 90f, x + 4f, sea - 80f)
            cubicTo(x + 30f, sea - 70f, x + 46f, sea - 40f, x + 44f, sea); close()
        }
        drawPath(p, Color(0xFF6A9E7F))
        drawCircle(Color(0xFF4F8A64), 14f, Offset(x - 6f, sea - 82f))
    }
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2EC4B6), Color(0xFF0B7A8C)), startY = sea, endY = h), Offset(0f, sea), Size(w, h - sea))
    for (row in 0 until 4) {
        val y = sea + 14f + row * (h - sea) / 4f
        val path = Path()
        var x = -20f
        path.moveTo(x, y)
        while (x < w + 20f) {
            x += 10f
            path.lineTo(x, y + 4f * sin((x / 40f + t * 2 * PI.toFloat() * (3 + row)).toDouble()).toFloat())
        }
        drawPath(path, Color.White.copy(alpha = 0.45f), style = Stroke(width = 2.5f))
    }
    // Long-tail boat rocking on the waves.
    val cx = w * 0.45f; val cy = sea + 18f + bob * 4f
    rotate(bob * 4f, Offset(cx, cy)) {
        val hull = Path().apply {
            moveTo(cx - 90f, cy - 10f); lineTo(cx + 70f, cy - 10f); quadraticTo(cx + 110f, cy - 40f, cx + 116f, cy - 52f)
            quadraticTo(cx + 96f, cy + 18f, cx + 40f, cy + 16f); lineTo(cx - 80f, cy + 16f); close()
        }
        drawPath(hull, Color(0xFF8B5A2B))
        drawLine(Color(0xFFF4D35E), Offset(cx - 86f, cy - 4f), Offset(cx + 80f, cy - 4f), 4f)
        drawRoundRect(Color(0xFF2B9348), Offset(cx - 50f, cy - 46f), Size(90f, 8f), CornerRadius(4f))
        drawLine(Color(0xFF5C3D1E), Offset(cx - 40f, cy - 40f), Offset(cx - 40f, cy - 10f), 3f)
        drawLine(Color(0xFF5C3D1E), Offset(cx + 30f, cy - 40f), Offset(cx + 30f, cy - 10f), 3f)
        // Garlands on the bow.
        listOf(Color(0xFFE63946), Color(0xFFFFB703), Color(0xFF8338EC)).forEachIndexed { i, c ->
            drawLine(c, Offset(cx + 112f, cy - 50f), Offset(cx + 100f + i * 4f, cy - 30f + i * 3f), 3f)
        }
        // Engine and long tail shaft.
        drawRoundRect(Color(0xFF555555), Offset(cx - 100f, cy - 22f), Size(22f, 16f), CornerRadius(3f))
        drawLine(Color(0xFF777777), Offset(cx - 96f, cy - 10f), Offset(cx - 150f, cy + 22f), 3f)
        val sprayP = (t * 10f) % 1f
        drawCircle(Color.White.copy(alpha = 0.7f * (1 - sprayP)), 6f + 10f * sprayP, Offset(cx - 152f - sprayP * 20f, cy + 22f))
    }
}

private fun DrawScope.atPlace(t: Float, bob: Float) {
    val w = size.width; val h = size.height
    sky(Color(0xFFFFE5EC), Color(0xFFFFF8E7))
    hills(0f, 0f, Color(0xFFB7E4C7), h * 0.82f, h * 0.3f, 4)
    drawRect(Color(0xFF95D5B2), Offset(0f, h * 0.82f), Size(w, h))
    // Sparkles.
    for (i in 0 until 8) {
        val a = (t * 2 * PI + i * PI / 4).toFloat()
        val r = h * 0.28f
        val p = Offset(w / 2 + r * cos(a), h * 0.45f + r * 0.6f * sin(a))
        drawCircle(Color(0xFFFFC300).copy(alpha = 0.5f + 0.5f * sin(a * 2)), 5f, p)
    }
    // Map pin bouncing.
    val cx = w / 2; val cy = h * 0.42f + bob * 8f
    val pin = Path().apply {
        moveTo(cx, cy + 50f); cubicTo(cx - 50f, cy - 4f, cx - 34f, cy - 52f, cx, cy - 52f)
        cubicTo(cx + 34f, cy - 52f, cx + 50f, cy - 4f, cx, cy + 50f); close()
    }
    drawOval(Color.Black.copy(alpha = 0.12f), Offset(cx - 26f, h * 0.42f + 54f), Size(52f, 10f))
    drawPath(pin, Color(0xFFE76F51))
    drawCircle(Color.White, 15f, Offset(cx, cy - 18f))
}

private fun DrawScope.rest(t: Float, bob: Float) {
    val w = size.width; val h = size.height
    sky(Color(0xFFE0E7FF), Color(0xFFF5F3FF))
    drawRect(Color(0xFFD8CFC4), Offset(0f, h * 0.75f), Size(w, h))
    // A steaming cup of coffee: a break from sightseeing (work, visiting family...).
    val cx = w / 2; val cy = h * 0.6f
    drawRoundRect(Color(0xFFFFFFFF), Offset(cx - 40f, cy - 40f), Size(80f, 70f), CornerRadius(12f))
    drawCircle(Color.White, 18f, Offset(cx + 44f, cy - 6f), style = Stroke(8f))
    drawOval(Color(0xFF6F4E37), Offset(cx - 34f, cy - 46f), Size(68f, 14f))
    drawOval(Color(0xFFEDE0D4), Offset(cx - 70f, cy + 24f), Size(140f, 20f))
    for (k in 0 until 3) {
        val p = (t * 3f + k / 3f) % 1f
        drawCircle(Color.Gray.copy(alpha = 0.35f * (1 - p)), 6f + 6f * p, Offset(cx - 18f + k * 18f + bob * 4f, cy - 56f - p * 60f))
    }
}
