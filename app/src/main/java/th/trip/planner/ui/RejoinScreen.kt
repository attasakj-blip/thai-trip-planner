@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package th.trip.planner.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import th.trip.planner.TripViewModel
import th.trip.planner.logic.Itinerary
import th.trip.planner.logic.RailPlan
import th.trip.planner.logic.RailPlanner
import th.trip.planner.logic.Rejoin
import th.trip.planner.logic.TransitPlan
import th.trip.planner.logic.TransitPlanner
import th.trip.planner.logic.TransitStop
import th.trip.planner.logic.TripLogic
import th.trip.planner.model.HubType
import th.trip.planner.model.RejoinInfo
import th.trip.planner.model.Trip
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Points of the plan

/** Where stop [i] really is: the stay when we know where it is, otherwise the place. */
private fun stopEnd(vm: TripViewModel, trip: Trip, i: Int): End? {
    val p = trip.stops.getOrNull(i)?.let { vm.repo.placeById[it.placeId] } ?: return null
    val stay = trip.lodgings[p.id]
    if (stay != null && stay.lat != null && stay.lon != null && TripLogic.distanceKm(stay.lat, stay.lon, p.lat, p.lon) < 60)
        return End(stay.name.ifBlank { p.name.get(I18n.thai) }, stay.lat, stay.lon, true)
    return End(p.name.get(I18n.thai), p.lat, p.lon, false)
}

private fun stopPoint(vm: TripViewModel, trip: Trip, i: Int): Itinerary.Point? {
    val p = trip.stops.getOrNull(i)?.let { vm.repo.placeById[it.placeId] } ?: return null
    val e = stopEnd(vm, trip, i) ?: return null
    return Itinerary.Point(e.lat, e.lon, p.island, p.islandGroup)
}

/** A GPS point, with the island it is on when it is close to a known island place. */
private fun gpsPoint(vm: TripViewModel, lat: Double, lon: Double): Itinerary.Point {
    val near = vm.repo.places.minByOrNull { TripLogic.distanceKm(lat, lon, it.lat, it.lon) }
    return if (near != null && near.island && TripLogic.distanceKm(lat, lon, near.lat, near.lon) <= 15) Itinerary.Point(lat, lon, true, near.islandGroup)
    else Itinerary.Point(lat, lon)
}

/** Timetable search for the trip's legs, remembered so the same search isn't run twice. */
private fun legPlanner(vm: TripViewModel, trip: Trip): Rejoin.LegPlanner {
    val memo = HashMap<Triple<Int, LocalDate, Int>, List<Itinerary.Option>>()
    return Rejoin.LegPlanner { i, date, earliest ->
        memo.getOrPut(Triple(i, date, earliest)) {
            val a = stopPoint(vm, trip, i); val b = stopPoint(vm, trip, i + 1)
            if (a == null || b == null) emptyList()
            else Itinerary.options(a, b, date, vm.repo.hubs, vm.repo.routes, earliest = earliest)
        }
    }
}

private fun legGroundMinutes(vm: TripViewModel, trip: Trip, i: Int): Int {
    val a = stopEnd(vm, trip, i); val b = stopEnd(vm, trip, i + 1)
    return if (a == null || b == null) 120 else Itinerary.groundMinutes(TripLogic.distanceKm(a.lat, a.lon, b.lat, b.lon))
}

/**
 * A point between two places of the plan. [rest] is the rest of the planned journey from here
 * (still on the same bus / train) on [date], or null when the plan has no timetable through it.
 */
private data class MidInfo(val leg: Int, val rest: Itinerary.Journey?, val date: LocalDate?)

/** One part of the plan the traveller can go back to. */
private data class JoinPoint(
    val target: Rejoin.Target, val title: String, val detail: String, val end: End, val point: Itinerary.Point,
    val mid: MidInfo? = null,
)

private fun hubPoint(h: th.trip.planner.model.Hub) = Itinerary.Point(h.lat, h.lon, h.islandGroup != null, h.islandGroup)

/**
 * Where along segment a→b (0..1) the point p lies, and how far from the line it is (km),
 * on a flat local projection (good enough within a country).
 */
private fun along(aLat: Double, aLon: Double, bLat: Double, bLon: Double, lat: Double, lon: Double): Pair<Double, Double> {
    val k = kotlin.math.cos(Math.toRadians((aLat + bLat) / 2))
    val bx = (bLon - aLon) * k * 111.0; val by = (bLat - aLat) * 111.0
    val px = (lon - aLon) * k * 111.0; val py = (lat - aLat) * 111.0
    val len2 = bx * bx + by * by
    if (len2 < 1e-6) return 0.0 to kotlin.math.hypot(px, py)
    val t = (px * bx + py * by) / len2
    val dx = px - t * bx; val dy = py - t * by
    return t to kotlin.math.hypot(dx, dy)
}

/**
 * Points between stop [i] and stop i+1: every station the planned bus / train / boat stops at,
 * or, for a leg with no timetable, the stations and terminals close to the way between the two.
 */
private fun midPoints(vm: TripViewModel, trip: Trip, i: Int, date: LocalDate?, planned: Itinerary.Journey?): List<JoinPoint> {
    val thai = I18n.thai
    val a = stopEnd(vm, trip, i) ?: return emptyList()
    val b = stopEnd(vm, trip, i + 1) ?: return emptyList()
    val fromName = vm.repo.placeById[trip.stops[i].placeId]?.name?.get(thai) ?: ""
    val toName = vm.repo.placeById[trip.stops[i + 1].placeId]?.name?.get(thai) ?: ""
    val span = tr("ระหว่าง $fromName → $toName", "between $fromName → $toName")
    val out = ArrayList<JoinPoint>()
    val seen = HashSet<String>()
    if (planned != null && date != null && planned.rides.isNotEmpty()) {
        val steps = planned.steps
        planned.rides.forEachIndexed { k, r ->
            val times = runTimes(r)
            val stepIdx = steps.indexOf(r)
            val after = steps.drop(stepIdx + 1)
            val last = k == planned.rides.lastIndex
            for (m in r.boardIdx..r.alightIdx) {
                if (k == 0 && m == r.boardIdx) continue // the first station is the "catch the ride" point
                if (m == r.alightIdx && !last) continue // the next ride's first station covers it
                val hub = vm.repo.hubById[r.route.stops.getOrNull(m) ?: continue] ?: continue
                if (!seen.add(hub.id)) continue
                val tm = (if (m == r.boardIdx) r.start else times?.getOrNull(m)) ?: continue
                val rest = when {
                    m < r.alightIdx -> Itinerary.Journey(listOf(r.copy(boardIdx = m, board = hub, start = tm)) + after)
                    after.isNotEmpty() -> Itinerary.Journey(after)
                    else -> null
                }
                val icon = modeIcon(r.route.mode)
                out += JoinPoint(
                    Rejoin.Target.Mid(i, hub.id),
                    if (m < r.alightIdx) tr("$icon ขึ้น${modeWord(r.route.mode)}คันเดิมต่อที่ ${hub.name.th}", "$icon Get on the same ${modeWord(r.route.mode)} at ${hub.name.en}")
                    else tr("$icon ไปที่ ${hub.name.th} (จุดลง${modeWord(r.route.mode)})", "$icon Go to ${hub.name.en} (where the ${modeWord(r.route.mode)} stops)"),
                    (if (m < r.alightIdx) tr("ผ่านราว ${Itinerary.formatTime(tm)} · ", "Passes around ${Itinerary.formatTime(tm)} · ") else tr("ถึงราว ${Itinerary.formatTime(tm)} · ", "Arrives around ${Itinerary.formatTime(tm)} · ")) +
                        fmt(date, thai) + " · " + span,
                    End(hub.name.get(thai), hub.lat, hub.lon, false), hubPoint(hub),
                    MidInfo(i, rest, date),
                )
            }
        }
        return out
    }
    // No timetable through this leg: stations near the way (straight line) between the two places.
    val km = TripLogic.distanceKm(a.lat, a.lon, b.lat, b.lon)
    if (km < 2) return emptyList()
    val width = (km * 0.08).coerceIn(0.6, 6.0)
    data class C(val id: String, val title: String, val lat: Double, val lon: Double, val pt: Itinerary.Point, val t: Double)
    val cands = ArrayList<C>()
    fun consider(id: String, title: String, lat: Double, lon: Double, pt: Itinerary.Point) {
        val (t, off) = along(a.lat, a.lon, b.lat, b.lon, lat, lon)
        if (t !in 0.03..0.97 || off > width) return
        if (TripLogic.distanceKm(lat, lon, a.lat, a.lon) < 0.8 || TripLogic.distanceKm(lat, lon, b.lat, b.lon) < 0.8) return
        cands += C(id, title, lat, lon, pt, t)
    }
    vm.repo.hubs.forEach { h -> if (h.type != HubType.AIRPORT) consider(h.id, hubTypeLabel(h.type, thai) + " " + h.name.get(thai), h.lat, h.lon, hubPoint(h)) }
    vm.repo.rail.forEach { l -> l.stations.forEach { st -> consider("r_" + st.id, "🚆 " + tr("สถานี", "") + st.name.get(thai) + " (" + l.name.get(thai) + ")", st.lat, st.lon, Itinerary.Point(st.lat, st.lon)) } }
    // Several lines share a station: keep one per spot, then at most 12 spread along the way.
    val uniq = cands.sortedBy { it.t }.fold(ArrayList<C>()) { acc, c ->
        if (acc.none { TripLogic.distanceKm(it.lat, it.lon, c.lat, c.lon) < 0.3 }) acc += c
        acc
    }
    val picked = if (uniq.size <= 12) uniq else List(12) { uniq[(it * (uniq.size - 1)) / 11] }.distinct()
    picked.forEach { c ->
        if (!seen.add(c.id)) return@forEach
        out += JoinPoint(
            Rejoin.Target.Mid(i, c.id), c.title,
            tr("ราว ${(c.t * 100).roundToInt()}% ของทาง · ", "About ${(c.t * 100).roundToInt()}% of the way · ") + span,
            End(c.title.substringAfter(' '), c.lat, c.lon, false), c.pt,
            MidInfo(i, null, date),
        )
    }
    return out
}

/** The stops, scheduled departures and points between them still ahead (today or later), in trip order. */
private fun joinPoints(vm: TripViewModel, trip: Trip, today: LocalDate, planner: Rejoin.LegPlanner): List<JoinPoint> {
    val thai = I18n.thai
    val out = ArrayList<JoinPoint>()
    trip.stops.forEachIndexed { i, s ->
        if (i > 0) {
            val legIdx = i - 1
            val date = TripLogic.legDate(trip, legIdx)
            val ahead = date == null || date >= today
            val j = if (date != null && ahead) Rejoin.plannedJourney(trip, legIdx, planner) else null
            val ride = j?.rides?.firstOrNull()
            if (j != null && ride != null) {
                val from = vm.repo.placeById[trip.stops[legIdx].placeId]?.name?.get(thai) ?: ""
                val to = vm.repo.placeById[s.placeId]?.name?.get(thai) ?: ""
                out += JoinPoint(
                    Rejoin.Target.Board(legIdx),
                    tr("${modeIcon(ride.route.mode)} ขึ้น${modeWord(ride.route.mode)} ${ride.route.name.th} ที่ ${ride.board.name.th}", "${modeIcon(ride.route.mode)} Catch the ${modeWord(ride.route.mode)} ${ride.route.name.en} at ${ride.board.name.en}"),
                    tr("ออก ${Itinerary.formatTime(ride.start)} · ${fmt(date!!, true)} · ช่วง $from → $to", "Leaves ${Itinerary.formatTime(ride.start)} · ${fmt(date!!, false)} · $from → $to"),
                    End(ride.board.name.get(thai), ride.board.lat, ride.board.lon, false),
                    hubPoint(ride.board),
                )
            }
            if (ahead) out += midPoints(vm, trip, legIdx, date, j)
        }
        val dates = TripLogic.dates(s)
        if (dates.isEmpty() || dates.last() >= today) {
            val p = vm.repo.placeById[s.placeId] ?: return@forEachIndexed
            val e = stopEnd(vm, trip, i) ?: return@forEachIndexed
            val pt = stopPoint(vm, trip, i) ?: return@forEachIndexed
            out += JoinPoint(
                Rejoin.Target.AtStop(i),
                "📍 ${i + 1}. ${p.name.get(thai)}",
                fmtDates(dates, thai) + if (e.isStay) tr(" · ไปที่พัก ${e.label}", " · to your stay, ${e.label}") else "",
                e, pt,
            )
        }
    }
    return out
}

/** From a point between two places on to the next place: still on the planned ride, or another way. */
private data class MidRest(val arrive: LocalDateTime, val onPlan: Boolean, val journey: Itinerary.Journey?, val way: Way?)

private suspend fun midRest(vm: TripViewModel, trip: Trip, t: JoinPoint, at: LocalDateTime): MidRest {
    val mid = t.mid!!
    val rest = mid.rest
    if (rest != null && mid.date != null) {
        val first = rest.steps.first()
        val ready = if (first is Itinerary.Step.Ride) first.start - Itinerary.buffer(first.route.mode) else rest.depart
        val day = mid.date.atStartOfDay()
        if (!day.plusMinutes(ready.toLong()).isBefore(at)) return MidRest(day.plusMinutes(rest.arrive.toLong()), true, rest, null)
    }
    val to = stopEnd(vm, trip, mid.leg + 1)
    val toPt = stopPoint(vm, trip, mid.leg + 1)
    if (to == null || toPt == null) return MidRest(at, false, null, null)
    val min = at.hour * 60 + at.minute
    val w = runCatching { waysBetween(vm, t.point, toPt, to, at.toLocalDate(), min, I18n.thai).firstOrNull() }.getOrNull()
    val minutes = w?.minutes ?: Itinerary.groundMinutes(TripLogic.distanceKm(t.end.lat, t.end.lon, to.lat, to.lon))
    return MidRest(at.plusMinutes(minutes.toLong()), false, null, w)
}

// ---------------------------------------------------------------- Ways back

/** One way from where the traveller is to the join point. [minutes] counts from leaving now, waiting included. */
internal sealed class Way {
    abstract val key: String
    abstract val minutes: Int

    data class Taxi(val km: Double, override val minutes: Int) : Way() { override val key get() = "taxi" }
    data class Walk(val km: Double, override val minutes: Int) : Way() { override val key get() = "walk" }
    data class Rail(val plan: RailPlan) : Way() {
        override val key get() = "rail:" + plan.rides.joinToString(">") { it.line.id + "@" + it.board.id }
        override val minutes get() = plan.minutes
    }
    data class Bus(val plan: TransitPlan) : Way() {
        override val key get() = "bus:" + plan.rides.joinToString(">") { it.line.kind.name + it.line.ref.ifBlank { it.line.name } + "@" + it.board.id }
        override val minutes get() = plan.minutes
    }
    data class Scheduled(val journey: Itinerary.Journey, val nowMin: Int) : Way() {
        override val key get() = "it:" + journey.signature
        override val minutes get() = journey.arrive - nowMin
    }
}

internal fun wayTitle(w: Way): String = when (w) {
    is Way.Taxi -> tr("🚕 แท็กซี่ / เรียกรถ (Grab, Bolt, LINE MAN)", "🚕 Taxi / ride-hailing (Grab, Bolt, LINE MAN)")
    is Way.Walk -> tr("🚶 เดินไป", "🚶 Walk")
    is Way.Rail -> w.plan.rides.joinToString(" → ") { "🚆 " + it.line.name.get(I18n.thai) }
    is Way.Bus -> w.plan.rides.joinToString(" → ") { kindLabel(it.line.kind) + (if (it.line.ref.isNotBlank()) tr(" สาย ${it.line.ref}", " ${it.line.ref}") else "") }
    is Way.Scheduled -> w.journey.rides.joinToString(" → ") { "${modeIcon(it.route.mode)} ${modeWord(it.route.mode)}" }
}

/** Up to four quickest ways from ([lat], [lon]) to [to], leaving at minute [nowMin] of [date]. */
private suspend fun findWays(vm: TripViewModel, lat: Double, lon: Double, to: JoinPoint, date: LocalDate, nowMin: Int, thai: Boolean): List<Way> =
    waysBetween(vm, gpsPoint(vm, lat, lon), to.point, to.end, date, nowMin, thai).take(4)

/**
 * Every practical way between two points, quickest first: walking, taxi, Bangkok rail, local
 * buses / songthaews / boats (OpenStreetMap), and timetabled services for longer trips.
 */
internal suspend fun waysBetween(vm: TripViewModel, from: Itinerary.Point, target: Itinerary.Point, to: End, date: LocalDate, nowMin: Int, thai: Boolean): List<Way> {
    val lat = from.lat; val lon = from.lon
    val km = TripLogic.distanceKm(lat, lon, to.lat, to.lon)
    val sameLand = from.islandGroup == target.islandGroup && (from.islandGroup != null || (!from.island && !target.island) || km <= 10)
    val out = ArrayList<Way>()
    if (km <= 2.5) out += Way.Walk(km * 1.25, (km * 1.25 * 13).roundToInt().coerceAtLeast(2))
    if (sameLand && km > 0.4) {
        val drive = vm.remote.drive(lat, lon, to.lat, to.lon).getOrNull()
        // A few minutes for the car to come.
        out += Way.Taxi(drive?.first ?: km * 1.3, (drive?.second ?: Itinerary.groundMinutes(km)) + 6)
    }
    if (km > 1.2) withContext(Dispatchers.Default) { RailPlanner.plan(vm.repo.rail, lat, lon, to.lat, to.lon) }?.let { out += Way.Rail(it) }
    if (km in 0.8..40.0) vm.remote.transitLines(lat, lon, to.lat, to.lon, thai).getOrNull()?.let { lines ->
        TransitPlanner.plan(lines, lat, lon, to.lat, to.lon, limit = 6).forEach { out += Way.Bus(it) }
    }
    if (km > 8) withContext(Dispatchers.Default) {
        Itinerary.options(from, target, date, vm.repo.hubs, vm.repo.routes, earliest = nowMin, maxOptions = 6)
    }.forEach { o ->
        o.journeys.filter { it.rides.isNotEmpty() && it.depart >= nowMin - 5 }.minByOrNull { it.arrive }?.let { out += Way.Scheduled(it, nowMin) }
    }
    // A bus that takes much longer than walking the same short way isn't worth it.
    val walk = out.firstOrNull { it is Way.Walk }?.minutes
    return out.distinctBy { it.key }.filter { walk == null || it is Way.Walk || it.minutes < walk }.sortedBy { it.minutes }
}

// ---------------------------------------------------------------- GPS

/** A fresh position (less than 10 minutes old), or null until one arrives. */
@SuppressLint("MissingPermission")
@Composable
private fun rememberFix(granted: Boolean, refresh: Int): Location? {
    val ctx = LocalContext.current
    var loc by remember(refresh) { mutableStateOf<Location?>(null) }
    DisposableEffect(granted, refresh) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val listener = LocationListener { l -> if (loc == null || l.accuracy <= (loc?.accuracy ?: Float.MAX_VALUE) || l.time - (loc?.time ?: 0) > 60_000) loc = l }
        if (granted && hasLocation(ctx)) {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < 10 * 60_000L }.maxByOrNull { it.time }?.let { loc = it }
            providers.forEach { runCatching { lm.requestLocationUpdates(it, 3_000L, 0f, listener, Looper.getMainLooper()) } }
        }
        onDispose { runCatching { lm.removeUpdates(listener) } }
    }
    return loc
}

private fun gpsOn(ctx: android.content.Context): Boolean {
    val lm = ctx.getSystemService(LocationManager::class.java)
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).any { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
}

// ---------------------------------------------------------------- Offer on the trip summary

/** Card on the trip summary: off the planned route? Use GPS to get back onto the plan. */
@Composable
fun RejoinOffer(vm: TripViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    val trip = vm.trip
    val last = TripLogic.lastDay(trip)
    if (trip.stops.isEmpty() || (last != null && last < LocalDate.now())) return
    var ask by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { nav.navigate(Routes.REJOIN) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("🧭 ออกนอกเส้นทางที่วางไว้?", "🧭 Off the planned route?"), fontWeight = FontWeight.Bold)
            Text(
                tr("เปิด GPS ให้แอปรู้ว่าคุณอยู่ที่ไหน แล้วเลือกว่าจะกลับมาเชื่อมกับส่วนไหนของทริปนี้ แอปจะหาวิธีเดินทางที่เร็วที่สุด 4 วิธี และปรับเวลาของทริปที่เหลือให้เท่าที่จำเป็น",
                    "Turn on GPS so the app knows where you are, then pick where to rejoin this trip. The app finds the 4 quickest ways there and adjusts the rest of the trip only where needed."),
                fontSize = 13.sp,
            )
            Button(onClick = { ask = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("📍 เชื่อมกลับเข้าแผนด้วย GPS", "📍 Rejoin the plan with GPS")) }
            trip.rejoin?.let { r ->
                val at = java.time.Instant.ofEpochMilli(r.atMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
                OutlinedButton(onClick = { nav.navigate(Routes.REJOIN_VIEW) }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("ดูเส้นทางกลับเข้าแผนที่เลือกไว้ (${fmt(at.toLocalDate(), true)} ${Itinerary.formatTime(at.hour * 60 + at.minute)})",
                        "See the way back you picked (${fmt(at.toLocalDate(), false)} ${Itinerary.formatTime(at.hour * 60 + at.minute)})"), fontSize = 12.sp)
                }
            }
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text(tr("เปิด GPS เพื่อเชื่อมกับแผนนี้ไหม?", "Use GPS to link up with this plan?")) },
        text = { Text(tr("แอปจะใช้ตำแหน่งปัจจุบันของเครื่องเพื่อหาทางกลับมาที่แผนการเดินทางในสรุปทริปนี้ ตำแหน่งจะใช้ในเครื่องเท่านั้น", "The app uses your phone's current position to find a way back to the plan in this summary. Your position stays on the phone.")) },
        confirmButton = {
            TextButton(onClick = {
                ask = false
                if (hasLocation(ctx)) nav.navigate(Routes.REJOIN)
                else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text(tr("เปิด GPS", "Use GPS")) }
        },
        dismissButton = { TextButton(onClick = { ask = false }) { Text(tr("ไม่ใช่ตอนนี้", "Not now")) } },
    )
}

// ---------------------------------------------------------------- The rejoin screen

@Composable
private fun Busy(text: String) = Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    Text("  $text", fontSize = 13.sp, color = Color.Gray)
}

private fun clock(t: LocalDateTime, thai: Boolean, today: LocalDate): String =
    Itinerary.formatTime(t.hour * 60 + t.minute) + if (t.toLocalDate() != today) " · " + fmt(t.toLocalDate(), thai) else ""

/**
 * Off the plan: find where the traveller is, let them pick the part of the trip to go back to
 * and one of the four quickest ways there, check the rest of the trip, and show both parts.
 * With [view] true it shows the way back picked earlier.
 */
@Composable
fun RejoinScreen(vm: TripViewModel, nav: NavHostController, view: Boolean = false) {
    val thai = LocalThai.current
    val ctx = LocalContext.current
    val saved = if (view) vm.trip.rejoin else null
    var granted by remember { mutableStateOf(hasLocation(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasLocation(ctx) }
    var refresh by remember { mutableIntStateOf(0) }
    val fix = if (saved == null) rememberFix(granted, refresh) else null
    // Freeze the position once known so results don't jump around while reading them.
    var from by remember(refresh) { mutableStateOf<Pair<Double, Double>?>(saved?.let { it.fromLat to it.fromLon }) }
    LaunchedEffect(fix) { if (from == null && fix != null) from = fix.latitude to fix.longitude }
    val startAt = remember(refresh) {
        saved?.let { java.time.Instant.ofEpochMilli(it.atMillis).atZone(ZoneId.systemDefault()).toLocalDateTime() } ?: LocalDateTime.now()
    }
    val today = startAt.toLocalDate()
    val nowMin = startAt.hour * 60 + startAt.minute
    // The plan as it was when this screen opened (the result is applied to it).
    val trip = remember { vm.trip }
    val planner = remember(trip) { legPlanner(vm, trip) }
    val points by produceState<List<JoinPoint>?>(null, trip) { value = withContext(Dispatchers.Default) { joinPoints(vm, trip, today, planner) } }
    var targetKey by remember { mutableStateOf(saved?.target) }
    var openLegs by remember { mutableStateOf(setOf<Int>()) }
    val target = points?.firstOrNull { it.target.key == targetKey }
    val ways by produceState<List<Way>?>(null, from, targetKey, points) {
        value = null
        val f = from ?: return@produceState
        val t = target ?: return@produceState
        value = runCatching { findWays(vm, f.first, f.second, t, today, nowMin, thai) }.getOrDefault(emptyList())
    }
    var wayKey by remember { mutableStateOf(saved?.way) }
    val way = ways?.firstOrNull { it.key == wayKey }
    var showResult by remember { mutableStateOf(saved != null) }
    val arrival = way?.let { startAt.plusMinutes(it.minutes.toLong()) }
        ?: saved?.let { runCatching { LocalDateTime.parse(it.arrive) }.getOrNull() }
    // From a point between two places: how the traveller goes on to the next place.
    val midRest by produceState<MidRest?>(null, showResult, targetKey, arrival, points) {
        value = null
        val t = target ?: return@produceState
        val a = arrival ?: return@produceState
        if (!showResult || t.mid == null) return@produceState
        value = midRest(vm, trip, t, a)
    }
    val result by produceState<Rejoin.Result?>(null, showResult, targetKey, wayKey, arrival, midRest) {
        value = null
        val t = target ?: return@produceState
        val a = if (t.mid != null) midRest?.arrive ?: return@produceState else arrival ?: return@produceState
        if (!showResult) return@produceState
        value = withContext(Dispatchers.Default) { Rejoin.retime(trip, t.target, a, today, planner) { legGroundMinutes(vm, trip, it) } }
    }

    val bottomBar: (@Composable () -> Unit)? = if (showResult && result != null && saved == null) { {
        Button(onClick = {
            val r = result ?: return@Button
            val w = way ?: return@Button
            val t = target ?: return@Button
            val f = from ?: return@Button
            vm.update {
                r.trip.copy(
                    activated = it.activated, finished = it.finished, checkIns = it.checkIns,
                    rejoin = RejoinInfo(
                        System.currentTimeMillis(), f.first, f.second, t.target.key, w.key, wayTitle(w),
                        (arrival ?: startAt).toString(), changeLines(vm, trip, r.changes, thai),
                    ),
                )
            }
            nav.popBackStack()
        }, modifier = Modifier.fillMaxWidth()) { Text(tr("✅ ใช้แผนนี้ และอัปเดตสรุปทริป", "✅ Use this plan and update the summary")) }
    } } else null
    Page(tr("เชื่อมกลับเข้าแผน", "Back onto the plan"), nav, bottom = bottomBar) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 1. Where am I?
            if (saved == null) {
                when {
                    !granted -> {
                        Text(tr("ต้องอนุญาตให้แอปใช้ตำแหน่งก่อน", "Allow the app to use your location first."), color = Coral)
                        Button(onClick = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text(tr("อนุญาตตำแหน่ง", "Allow location")) }
                    }
                    !gpsOn(ctx) && from == null -> {
                        Text(tr("GPS ของเครื่องปิดอยู่ เปิด GPS (ตำแหน่ง) แล้วกลับมาที่หน้านี้", "Your phone's GPS (location) is off. Turn it on, then come back here."), color = Coral)
                        Button(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } }) { Text(tr("เปิดการตั้งค่า GPS", "Open GPS settings")) }
                        TextButton(onClick = { refresh++ }) { Text(tr("เปิดแล้ว ลองอีกครั้ง", "It's on, try again")) }
                    }
                    from == null -> Busy(tr("กำลังหาตำแหน่งของคุณ…", "Finding where you are…"))
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("📍 ได้ตำแหน่งของคุณแล้ว", "📍 Got your position") + (fix?.accuracy?.let { tr(" (แม่นยำราว ${it.roundToInt()} ม.)", " (within about ${it.roundToInt()} m)") } ?: ""), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { refresh++; wayKey = null; showResult = false }) { Text(tr("อัปเดต", "Refresh"), fontSize = 12.sp) }
                    }
                }
            }
            val f = from ?: return@Column

            if (!showResult) {
                // 2. Where to rejoin?
                SectionTitle(tr("อยากกลับไปเชื่อมกับส่วนไหนของทริป?", "Where would you like to rejoin the trip?"))
                val pts = points
                when {
                    pts == null -> Busy(tr("กำลังอ่านแผนการเดินทาง…", "Reading your plan…"))
                    pts.isEmpty() -> Text(tr("ไม่มีส่วนของทริปที่ยังมาไม่ถึง", "Nothing left ahead in this trip."), color = Color.Gray)
                    else -> pts.forEachIndexed { n, p ->
                        val on = p.target.key == targetKey
                        val mid = p.mid
                        if (mid != null) {
                            // Points between two places fold away under one line per leg.
                            if (pts.getOrNull(n - 1)?.mid?.leg != mid.leg) {
                                val count = pts.count { it.mid?.leg == mid.leg }
                                val open = mid.leg in openLegs
                                TextButton(onClick = { openLegs = if (open) openLegs - mid.leg else openLegs + mid.leg }, modifier = Modifier.padding(start = 16.dp)) {
                                    Text((if (open) "▾ " else "▸ ") + tr("จุดระหว่างทาง ($count จุด) เช่น สถานี ป้ายรถ จุดต่อรถ", "Points on the way ($count): stations, stops, transfers"), fontSize = 13.sp)
                                }
                            }
                            if (mid.leg !in openLegs && !on) return@forEachIndexed
                        }
                        Card(
                            Modifier.fillMaxWidth().padding(start = if (mid != null) 20.dp else 0.dp).clickable { targetKey = p.target.key; wayKey = null },
                            colors = CardDefaults.cardColors(containerColor = if (on) Color(0xFFE3F4EF) else Color(0xFFF6F8FB)),
                        ) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = on, onClick = { targetKey = p.target.key; wayKey = null })
                                Column(Modifier.weight(1f)) {
                                    Text(p.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(p.detail, fontSize = 12.sp, color = Color.Gray)
                                    Text(tr("ห่างจากคุณราว ", "About ") + kmText(TripLogic.distanceKm(f.first, f.second, p.end.lat, p.end.lon)) + tr("", " from you"), fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
                // 3. How to get there?
                val t = target ?: return@Column
                SectionTitle(tr("วิธีเดินทางที่เร็วที่สุด (ออกตอนนี้ ${Itinerary.formatTime(nowMin)})", "Quickest ways (leaving now, ${Itinerary.formatTime(nowMin)})"))
                val ws = ways
                when {
                    ws == null -> Busy(tr("กำลังหาวิธีเดินทาง…", "Looking for ways to get there…"))
                    ws.isEmpty() -> Text(tr("ไม่พบวิธีเดินทางในข้อมูลของแอป ลองเปิดเส้นทางใน Google Maps", "No way found in the app's data. Try Google Maps."), color = Coral)
                    else -> ws.forEachIndexed { n, w ->
                        val on = w.key == wayKey
                        Card(
                            Modifier.fillMaxWidth().clickable { wayKey = w.key },
                            colors = CardDefaults.cardColors(containerColor = if (on) Color(0xFFE3F4EF) else Color(0xFFF6F8FB)),
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = on, onClick = { wayKey = w.key })
                                    Column(Modifier.weight(1f)) {
                                        Text(tr("วิธีที่ ${n + 1}: ", "Way ${n + 1}: ") + wayTitle(w), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text(tr("ใช้เวลาราว ${durationText(w.minutes)} · ถึงประมาณ ", "About ${durationText(w.minutes)} · arrive around ") + clock(startAt.plusMinutes(w.minutes.toLong()), thai, today), fontSize = 12.sp, color = Teal)
                                    }
                                }
                                if (on) { HorizontalDivider(); WayDetails(vm, w, End(tr("ตำแหน่งของคุณ", "where you are"), f.first, f.second, false), t.end) }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { open(ctx, "https://www.google.com/maps/dir/?api=1&origin=${f.first},${f.second}&destination=${t.end.lat},${t.end.lon}&travelmode=transit") }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("เทียบกับเส้นทางใน Google Maps", "Compare with Google Maps"), fontSize = 12.sp)
                }
                if (way != null) Button(onClick = { showResult = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("ต่อไป: ตรวจแผนเดิมจากจุดที่เชื่อม", "Next: check the rest of the plan")) }
                VSpace(16)
                return@Column
            }

            // 4. The two parts: the way to the join point, and the trip from there.
            val t = target
            val r = result
            if (t == null || r == null) { Busy(tr("กำลังตรวจแผนการเดินทางเดิม…", "Checking the rest of your plan…")); return@Column }
            var tab by remember { mutableIntStateOf(0) }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("1. ไปจุดเชื่อม", "1. To the join point"), fontSize = 13.sp) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("2. จากจุดเชื่อมจนจบทริป", "2. From there to the end"), fontSize = 13.sp) })
            }
            if (tab == 0) {
                Text(t.title, fontWeight = FontWeight.Bold)
                Text(
                    tr("ออก ${clock(startAt, true, today)} → ถึงประมาณ ${arrival?.let { clock(it, true, today) } ?: "-"}", "Leave ${clock(startAt, false, today)} → arrive around ${arrival?.let { clock(it, false, today) } ?: "-"}"),
                    color = Teal, fontWeight = FontWeight.SemiBold,
                )
                val w = way
                when {
                    w != null -> {
                        Text(wayTitle(w), fontWeight = FontWeight.SemiBold)
                        WayDetails(vm, w, End(tr("ตำแหน่งของคุณ", "where you are"), f.first, f.second, false), t.end)
                    }
                    ways == null -> Busy(tr("กำลังโหลดเส้นทาง…", "Loading the route…"))
                    else -> Text(saved?.wayTitle ?: "", fontWeight = FontWeight.SemiBold)
                }
                if (saved == null) TextButton(onClick = { showResult = false }) { Text(tr("← เลือกวิธีอื่น", "← Pick another way")) }
            } else {
                val mid = t.mid
                if (mid != null) MidRestCard(vm, trip, t, mid, midRest, today, thai)
                RestOfTrip(vm, trip, r, saved, today, thai)
            }
            VSpace(16)
        }
    }
}

/** Plain-language lines for what had to change (kept with the trip so they can be shown again). */
private fun changeLines(vm: TripViewModel, trip: Trip, changes: List<Rejoin.Change>, thai: Boolean): List<String> = changes.map { c ->
    fun place(i: Int) = trip.stops.getOrNull(i)?.let { vm.repo.placeById[it.placeId]?.name?.get(thai) } ?: ""
    when (c) {
        is Rejoin.Change.StopDates -> tr("${place(c.stop)}: วันที่อยู่เปลี่ยนจาก ${fmtDates(c.before, true)} เป็น ${fmtDates(c.after, true)}", "${place(c.stop)}: days changed from ${fmtDates(c.before, false)} to ${fmtDates(c.after, false)}")
        is Rejoin.Change.LegTime -> {
            val before = (c.beforeDate?.let { fmt(it, thai) } ?: "") + (c.beforeDepart?.let { " " + Itinerary.formatTime(it) } ?: "")
            val after = fmt(c.afterDate, thai) + (c.afterDepart?.let { " " + Itinerary.formatTime(it) } ?: "")
            tr("${place(c.leg)} → ${place(c.leg + 1)}: เปลี่ยนรอบเดินทางจาก $before เป็น $after", "${place(c.leg)} → ${place(c.leg + 1)}: moved from $before to $after") +
                if (c.otherWay) tr(" (ใช้วิธีเดินทางอื่น เพราะรอบเดิมไม่มีแล้ว)", " (another way, the old one has no later run)") else ""
        }
        is Rejoin.Change.NoConnection -> tr("${place(c.leg)} → ${place(c.leg + 1)}: ไม่พบรอบที่ต่อได้ใน 3 วัน ต้องเลือกการเดินทางช่วงนี้ใหม่", "${place(c.leg)} → ${place(c.leg + 1)}: no connection in the next 3 days; pick this leg's transport again")
    }
}

/** Tab 2: every stop and leg from the join point to the end, with what changed. */
/** The rest of the leg from a point between two places to the next place. */
@Composable
private fun MidRestCard(vm: TripViewModel, trip: Trip, t: JoinPoint, mid: MidInfo, rest: MidRest?, today: LocalDate, thai: Boolean) {
    val to = stopEnd(vm, trip, mid.leg + 1) ?: return
    val toName = vm.repo.placeById[trip.stops[mid.leg + 1].placeId]?.name?.get(thai) ?: to.label
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F7FF))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("ต่อจาก ${t.end.label} ไป $toName", "From ${t.end.label} on to $toName"), fontWeight = FontWeight.Bold)
            if (rest == null) { Busy(tr("กำลังหาทางไปต่อ…", "Finding the way on…")); return@Column }
            Text(tr("ถึง $toName ประมาณ ${clock(rest.arrive, true, today)}", "Arrive at $toName around ${clock(rest.arrive, false, today)}"), color = Teal, fontWeight = FontWeight.SemiBold)
            when {
                rest.onPlan && rest.journey != null -> {
                    Text(tr("✅ ทันรถคันเดิมตามแผน", "✅ In time for the planned ride"), fontSize = 13.sp)
                    JourneySteps(vm, rest.journey, t.end, to)
                }
                rest.way != null -> {
                    if (mid.rest != null) Text(tr("ไม่ทันรถคันเดิม ใช้วิธีที่เร็วที่สุดแทน:", "Too late for the planned ride; the quickest other way:"), fontSize = 13.sp, color = Coral)
                    Text(wayTitle(rest.way), fontWeight = FontWeight.SemiBold)
                    WayDetails(vm, rest.way, t.end, to)
                }
                else -> Text(tr("เรียกแท็กซี่หรือรถรับจ้างไปต่อ", "Take a taxi or ride-hailing car on from here."), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun RestOfTrip(vm: TripViewModel, trip: Trip, r: Rejoin.Result, saved: RejoinInfo?, today: LocalDate, thai: Boolean) {
    val notes = saved?.notes ?: changeLines(vm, trip, r.changes, thai)
    Card(colors = CardDefaults.cardColors(containerColor = if (notes.isEmpty()) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (notes.isEmpty()) Text(tr("✅ ไปถึงทันแผนเดิม ไม่ต้องเปลี่ยนอะไร", "✅ You'll be in time for the plan; nothing changes."), fontWeight = FontWeight.SemiBold)
            else {
                Text(tr("ปรับแผนเท่าที่จำเป็น:", "Changed only where needed:"), fontWeight = FontWeight.SemiBold)
                notes.forEach { Text("• $it", fontSize = 13.sp) }
                if (r.changes.any { it is Rejoin.Change.StopDates && trip.lodgings[trip.stops[it.stop].placeId]?.isEmpty == false })
                    Text(tr("วันที่พักเปลี่ยน อย่าลืมแจ้งหรือแก้การจองที่พัก", "Stay dates changed; remember to update your booking."), fontSize = 12.sp, color = Coral)
            }
        }
    }
    val newTrip = r.trip
    r.entries.forEach { e ->
        when (e) {
            is Rejoin.Entry.Stop -> {
                val p = newTrip.stops.getOrNull(e.index)?.let { vm.repo.placeById[it.placeId] } ?: return@forEach
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (e.changed) Color(0xFFFFF3E0) else Color.White)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("📍 ${e.index + 1}. ${p.name.get(thai)}", fontWeight = FontWeight.Bold)
                        Text(fmtDates(e.dates, thai) + if (e.changed) tr(" (เปลี่ยน)", " (changed)") else "", fontSize = 13.sp, color = if (e.changed) Coral else Color.Gray)
                        e.arrive?.let { Text(tr("ถึงประมาณ ", "Arrive around ") + clock(it, thai, today), fontSize = 12.sp, color = Teal) }
                        newTrip.lodgings[p.id]?.takeIf { !it.isEmpty }?.let { Text("🏨 " + it.name, fontSize = 12.sp) }
                    }
                }
            }
            is Rejoin.Entry.Leg -> {
                val from = newTrip.stops.getOrNull(e.index)?.let { vm.repo.placeById[it.placeId] } ?: return@forEach
                val to = newTrip.stops.getOrNull(e.index + 1)?.let { vm.repo.placeById[it.placeId] } ?: return@forEach
                val leg = newTrip.legs.getOrNull(e.index) ?: return@forEach
                Card(Modifier.fillMaxWidth().padding(start = 20.dp), colors = CardDefaults.cardColors(containerColor = if (e.changed) Color(0xFFFFF3E0) else Color(0xFFF1F7FF))) {
                    Column(Modifier.padding(12.dp)) {
                        Text(legTitle(leg, thai) + (e.date?.let { " · " + fmt(it, thai) } ?: ""), fontWeight = FontWeight.SemiBold)
                        when {
                            e.noConnection -> Text(tr("ไม่พบรอบที่ต่อได้ ต้องเลือกการเดินทางช่วงนี้ใหม่", "No connection found; pick this leg's transport again."), color = Coral, fontSize = 13.sp)
                            e.journey != null && e.depart != null && e.arrive != null ->
                                Text(tr("ออก ${clock(e.depart, true, today)} → ถึง ${clock(e.arrive, true, today)}", "Leave ${clock(e.depart, false, today)} → arrive ${clock(e.arrive, false, today)}") + if (e.changed) tr(" (เปลี่ยน)", " (changed)") else "",
                                    fontSize = 13.sp, color = if (e.changed) Coral else Teal)
                            e.changed -> Text(tr("เลื่อนเป็นวันนี้ เพราะไปถึงหลังวันเดิม", "Moved to this day, as you arrive after the old one."), fontSize = 13.sp, color = Coral)
                        }
                        var open by remember(e.index) { mutableStateOf(false) }
                        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text((if (open) "▾ " else "▸ ") + tr("รายละเอียดการเดินทาง", "Travel details"), fontSize = 12.sp)
                        }
                        if (open) {
                            if (leg.local != null) LocalWaySummary(vm, leg, from, to, newTrip.lodgings[from.id], newTrip.lodgings[to.id], e.date)
                            else if (leg.plan != null) ChosenPlanSummary(vm, leg, from, to, newTrip.lodgings[from.id], newTrip.lodgings[to.id], e.date)
                            else leg.mode?.let { LegDetails(vm, it, from, to, newTrip.lodgings[from.id], newTrip.lodgings[to.id], e.date) }
                        }
                    }
                }
            }
        }
    }
    if (r.entries.isNotEmpty()) Text(tr("🏁 จบทริป", "🏁 End of trip"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

// ---------------------------------------------------------------- Step-by-step details of a way

@Composable
internal fun WayDetails(vm: TripViewModel, w: Way, a: End, b: End) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFF7FAFF)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (w) {
            is Way.Taxi -> CarDetails(vm, a, b)
            is Way.Walk -> {
                Line(tr("🚶 เดินจาก${a.label}ไป ${b.label} ราว ${kmText(w.km)} · ${durationText(w.minutes)}", "🚶 Walk from ${a.label} to ${b.label}, about ${kmText(w.km)} · ${durationText(w.minutes)}"), Color.Black)
                TextButton(onClick = { open(ctx, "https://www.google.com/maps/dir/?api=1&origin=${a.lat},${a.lon}&destination=${b.lat},${b.lon}&travelmode=walking") }) { Text(tr("นำทางเดิน (Google Maps)", "Walking directions (Google Maps)"), fontSize = 12.sp) }
            }
            is Way.Rail -> {
                RailPlanView(w.plan, a.label, b.label)
                TextButton(onClick = { open(ctx, "https://www.google.com/maps/dir/?api=1&origin=${a.lat},${a.lon}&destination=${w.plan.rides[0].board.lat},${w.plan.rides[0].board.lon}&travelmode=walking") }) {
                    Text(tr("นำทางเดินไปสถานี ${w.plan.rides[0].board.name.th}", "Walk to ${w.plan.rides[0].board.name.en} station"), fontSize = 12.sp)
                }
            }
            is Way.Bus -> BusSteps(vm, w.plan, a, b)
            is Way.Scheduled -> JourneySteps(vm, w.journey, a, b)
        }
    }
}

/** Bus / songthaew / boat steps, with something easy to spot next to each stop. */
@Composable
private fun BusSteps(vm: TripViewModel, plan: TransitPlan, a: End, b: End) {
    val thai = LocalThai.current
    val ctx = LocalContext.current
    val stops: List<TransitStop> = (plan.rides.map { it.board } + plan.rides.last().alight).distinctBy { it.id }
    val marks by produceState<Map<Long, String>>(emptyMap(), plan, thai) {
        val m = HashMap<Long, String>()
        for (s in stops) {
            vm.remote.landmark(s.lat, s.lon, thai).getOrNull()?.let { (name, _) -> if (name != s.name) m[s.id] = name }
            value = HashMap(m)
        }
    }
    fun stopText(s: TransitStop) = "\"" + s.name.ifBlank { tr("ป้ายไม่มีชื่อ", "unnamed stop") } + "\"" + (marks[s.id]?.let { tr(" (อยู่ใกล้ $it)", " (near $it)") } ?: "")
    val first = plan.rides[0].board
    Line(tr("ประมาณ ${durationText(plan.minutes)}", "About ${durationText(plan.minutes)}"), Teal, bold = true)
    Line(tr("1. 🚶 เดินจาก${a.label} ${kmText(plan.walkToBoardKm * 1.2)} ไปที่ป้าย ", "1. 🚶 Walk ${kmText(plan.walkToBoardKm * 1.2)} from ${a.label} to the stop ") + stopText(first), Color.Black)
    TextButton(onClick = { open(ctx, "https://www.google.com/maps/dir/?api=1&origin=${a.lat},${a.lon}&destination=${first.lat},${first.lon}&travelmode=walking") }, contentPadding = PaddingValues(horizontal = 6.dp)) {
        Text(tr("นำทางเดินไปป้ายนี้", "Walk to this stop"), fontSize = 12.sp)
    }
    var n = 2
    plan.rides.forEachIndexed { i, r ->
        if (i > 0) {
            Line(
                "$n. " + if (plan.transferWalkKm < 0.03) tr("🔁 ลงแล้วต่อรถที่ป้ายเดียวกัน ", "🔁 Change at the same stop ") + stopText(r.board)
                else tr("🔁 เดิน ${kmText(plan.transferWalkKm * 1.2)} ไปต่อรถที่ป้าย ", "🔁 Walk ${kmText(plan.transferWalkKm * 1.2)} to change at ") + stopText(r.board),
                Color.Black,
            ); n++
        }
        val line = listOf(r.line.ref.takeIf { it.isNotBlank() }?.let { tr("สาย $it", "no. $it") }, r.line.name.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
        Line("$n. " + kindLabel(r.line.kind) + " " + line + (if (r.line.operator.isNotBlank()) " (${r.line.operator})" else ""), Color.Black, bold = true); n++
        Line(tr("   ขึ้นที่ป้าย ", "   Board at ") + stopText(r.board), size = 12)
        Line(tr("   นั่ง ${r.stopCount} ป้าย แล้วลงที่ป้าย ", "   Ride ${r.stopCount} stops, get off at ") + stopText(r.alight), size = 12)
    }
    Line("$n. " + tr("🚶 เดิน ${kmText(plan.walkFromAlightKm * 1.2)} ถึง ${b.label}", "🚶 Walk ${kmText(plan.walkFromAlightKm * 1.2)} to ${b.label}"), Color.Black)
    Text(tr("ข้อมูลสายรถและป้ายจาก OpenStreetMap อาจไม่ครบหรือเปลี่ยนได้ ถามคนขับให้แน่ใจก่อนขึ้น", "Lines and stops come from OpenStreetMap and may be incomplete; check with the driver before boarding."), fontSize = 11.sp, color = Color.Gray)
}
