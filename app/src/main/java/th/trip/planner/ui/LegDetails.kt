@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package th.trip.planner.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import th.trip.planner.TripViewModel
import th.trip.planner.data.Poi
import th.trip.planner.logic.Fares
import th.trip.planner.logic.Itinerary
import th.trip.planner.logic.RailPlan
import th.trip.planner.logic.RailPlanner
import th.trip.planner.model.HubAccess
import th.trip.planner.logic.TransitKind
import th.trip.planner.logic.TransitPlan
import th.trip.planner.logic.TransitPlanner
import th.trip.planner.logic.TripLogic
import th.trip.planner.model.FlightRoute
import th.trip.planner.model.Hub
import th.trip.planner.model.HubType
import th.trip.planner.model.Lodging
import th.trip.planner.model.Place
import th.trip.planner.model.TravelMode
import java.net.URLEncoder
import java.time.LocalDate
import kotlin.math.roundToInt

/** A point a leg starts or ends at: the stay when we know where it is, otherwise the place. */
private data class End(val label: String, val lat: Double, val lon: Double, val isStay: Boolean)

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Rough road distance from a straight line (roads are rarely straight). */
private fun roadKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double) = TripLogic.distanceKm(lat1, lon1, lat2, lon2) * 1.3

fun kmText(km: Double): String = if (km < 1) "${(km * 1000 / 50).roundToInt() * 50} " + tr("ม.", "m") else "${if (km < 10) "%.1f".format(km) else km.roundToInt().toString()} " + tr("กม.", "km")

fun durationText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> tr("$m นาที", "$m min")
        m == 0 -> tr("$h ชม.", "$h h")
        else -> tr("$h ชม. $m นาที", "$h h $m min")
    }
}

/** Ride-hailing apps: opens the app, or its Play Store page when it isn't installed. */
private val RIDE_APPS = listOf(
    "Grab" to "com.grabtaxi.passenger",
    "Bolt" to "ee.mtakso.client",
    "LINE MAN" to "com.linecorp.linemanth",
)

private fun openApp(ctx: Context, pkg: String) {
    val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
    if (launch != null) runCatching { ctx.startActivity(launch) }
    else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
        .onFailure { open(ctx, "https://play.google.com/store/apps/details?id=$pkg") }
}

private fun transitUrl(a: End, b: End) =
    "https://www.google.com/maps/dir/?api=1&origin=${a.lat},${a.lon}&destination=${b.lat},${b.lon}&travelmode=transit"

@Composable
private fun Line(text: String, color: Color = Color.DarkGray, bold: Boolean = false, size: Int = 13) =
    Text(text, fontSize = size.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else null)

@Composable
private fun Loading() = Row(verticalAlignment = Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    Text("  " + tr("กำลังค้นหาข้อมูลการเดินทาง…", "Looking up travel details…"), fontSize = 12.sp, color = Color.Gray)
}

/** Where the leg really starts and ends: the stays, when we know (or can find) where they are. */
@Composable
private fun rememberEnds(vm: TripViewModel, from: Place, to: Place, fromStay: Lodging?, toStay: Lodging?): Pair<End, End>? {
    val thai = LocalThai.current
    val ends by produceState<Pair<End, End>?>(null, from.id, to.id, fromStay, toStay) {
        suspend fun end(p: Place, stay: Lodging?): End {
            if (stay != null && !stay.isEmpty) {
                val ll = if (stay.lat != null && stay.lon != null) stay.lat to stay.lon
                else listOf(stay.name, stay.address).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
                    ?.let { q -> vm.remote.geocode(q).getOrNull() ?: vm.remote.geocode(stay.address.ifBlank { stay.name }).getOrNull() }
                // Only trust a found address if it is near the place it belongs to.
                if (ll != null && TripLogic.distanceKm(ll.first, ll.second, p.lat, p.lon) < 60)
                    return End(stay.name.ifBlank { tr("ที่พัก", "your stay") }, ll.first, ll.second, true)
            }
            return End(p.name.get(thai), p.lat, p.lon, false)
        }
        value = end(from, fromStay) to end(to, toStay)
    }
    return ends
}

/**
 * Everything the traveller needs to decide on and use one way of travelling between two
 * places: where to board and get off, lines and changes, airlines and baggage, times,
 * distances to the place and the stay, rough costs and where to book.
 */
@Composable
fun LegDetails(vm: TripViewModel, mode: TravelMode, from: Place, to: Place, fromStay: Lodging?, toStay: Lodging?, date: LocalDate?) {
    val ends = rememberEnds(vm, from, to, fromStay, toStay)
    val e = ends
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF1F7FF)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (e == null) { Loading(); return@Column }
        val (a, b) = e
        val km = TripLogic.distanceKm(a.lat, a.lon, b.lat, b.lon)
        when (mode) {
            TravelMode.FLIGHT -> FlightDetails(vm, a, b, from, to, date)
            TravelMode.CAR -> CarDetails(vm, a, b)
            TravelMode.BUS, TravelMode.TRAIN -> if (km <= 60) CityTransit(vm, a, b, mode) else StationDetails(vm, mode, a, b, from, to, date)
            TravelMode.VAN -> StationDetails(vm, mode, a, b, from, to, date)
            TravelMode.BOAT -> BoatDetails(vm, a, b, from, to, date)
        }
        if (!a.isStay || !b.isStay) Text(
            tr("บันทึกที่พักในขั้นที่ 2 แล้ว ระยะทางจะคิดจากที่พักให้แม่นขึ้น", "Save your stays in step 2 and distances are measured from them."),
            fontSize = 11.sp, color = Color.Gray,
        )
    }
}

// ---------------------------------------------------------------- Flight

private data class FlightOption(val dep: Hub, val arr: Hub, val route: FlightRoute?)

@Composable
private fun FlightDetails(vm: TripViewModel, a: End, b: End, from: Place, to: Place, date: LocalDate?) {
    val thai = LocalThai.current
    val ctx = LocalContext.current
    val repo = vm.repo
    val airports = repo.hubs.filter { it.type == HubType.AIRPORT }
    fun nearest(e: End) = airports.sortedBy { TripLogic.distanceKm(e.lat, e.lon, it.lat, it.lon) }
        .filter { TripLogic.distanceKm(e.lat, e.lon, it.lat, it.lon) < 200 }.take(3)
    fun routeOf(x: Hub, y: Hub) = repo.flights.routes.firstOrNull { (it.a == x.id && it.b == y.id) || (it.a == y.id && it.b == x.id) }
    val options = nearest(a).flatMap { d -> nearest(b).filter { it.id != d.id }.map { r -> FlightOption(d, r, routeOf(d, r)) } }
        .filter { it.route != null }
        .sortedBy { roadKm(a.lat, a.lon, it.dep.lat, it.dep.lon) + roadKm(it.arr.lat, it.arr.lon, b.lat, b.lon) }
        .take(2)
    if (options.isEmpty()) {
        Line(tr("ไม่พบเที่ยวบินตรงจากสนามบินใกล้ ${from.name.th} ไปสนามบินใกล้ ${to.name.th} อาจต้องต่อเครื่องที่กรุงเทพฯ หรือเลือกวิธีเดินทางอื่น",
            "No direct flight between the airports near ${from.name.en} and ${to.name.en}. You may need to change planes in Bangkok, or pick another way."), Coral)
    }
    options.forEachIndexed { i, o ->
        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        val r = o.route!!
        Line("✈️ ${o.dep.name.get(thai)} → ${o.arr.name.get(thai)}", Color.Black, bold = true, size = 14)
        r.minutes?.let { Line(tr("เวลาบินประมาณ ", "Flight time about ") + durationText(it) + (r.perDay?.let { n -> tr(" · ประมาณ $n เที่ยวต่อวัน", " · about $n flights a day") } ?: "")) }
        Line(tr("สายการบินที่บินเส้นนี้", "Airlines on this route"), bold = true)
        r.airlines.mapNotNull { c -> repo.flights.airlines.firstOrNull { it.code == c } }.forEach { al ->
            Column(Modifier.padding(start = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("• ${al.name}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (!al.website.isNullOrBlank()) TextButton(onClick = { open(ctx, al.website) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(tr("เว็บไซต์", "Website"), fontSize = 12.sp) }
                }
                val carry = al.carryOnNote?.get(thai) ?: al.carryOnKg?.let { tr("ถือขึ้นเครื่อง $it กก.", "Carry-on $it kg") }
                carry?.let { Text("🎒 $it", fontSize = 12.sp, color = Color.DarkGray) }
                al.checkedNote?.let { Text("🧳 ${it.get(thai)}", fontSize = 12.sp, color = Color.DarkGray) }
            }
        }
        Line(tr("🚕 ${a.label} → สนามบิน ประมาณ ${kmText(roadKm(a.lat, a.lon, o.dep.lat, o.dep.lon))}", "🚕 ${a.label} → airport about ${kmText(roadKm(a.lat, a.lon, o.dep.lat, o.dep.lon))}"))
        Line(tr("🚕 สนามบินปลายทาง → ${b.label} ประมาณ ${kmText(roadKm(o.arr.lat, o.arr.lon, b.lat, b.lon))}", "🚕 Arrival airport → ${b.label} about ${kmText(roadKm(o.arr.lat, o.arr.lon, b.lat, b.lon))}"))
        val q = "Flights from ${o.dep.id.uppercase()} to ${o.arr.id.uppercase()}" + (date?.let { " on $it" } ?: "")
        OutlinedButton(onClick = { open(ctx, "https://www.google.com/travel/flights?q=" + enc(q)) }) {
            Text(tr("🕒 ดูเวลาออก-ถึงและราคา", "🕒 See departure/arrival times & fares") + (date?.let { " · " + fmt(it, thai) } ?: ""), fontSize = 12.sp)
        }
    }
    if (options.isNotEmpty()) Text(
        tr("เวลาเที่ยวบินเปลี่ยนทุกวัน ดูเวลาจริงจากปุ่มด้านบน · น้ำหนักสัมภาระเป็นเงื่อนไขทั่วไปของตั๋วราคาประหยัด ตรวจสอบตั๋วของคุณอีกครั้ง",
            "Flight times change daily; check the real times with the button above. Baggage is the usual allowance for the cheapest fares; check your own ticket."),
        fontSize = 11.sp, color = Color.Gray,
    )
}

// ---------------------------------------------------------------- Car / ride-hailing

@Composable
private fun CarDetails(vm: TripViewModel, a: End, b: End) {
    val ctx = LocalContext.current
    val drive by produceState<Result<Pair<Double, Int>>?>(null, a, b) { value = vm.remote.drive(a.lat, a.lon, b.lat, b.lon) }
    val d = drive
    if (d == null) Loading()
    val km = d?.getOrNull()?.first ?: roadKm(a.lat, a.lon, b.lat, b.lon)
    val minutes = d?.getOrNull()?.second ?: (km / 50 * 60).roundToInt()
    Line("🚗 ${a.label} → ${b.label}", Color.Black, bold = true, size = 14)
    Line(tr("ระยะทางถนน ${kmText(km)} · ขับประมาณ ${durationText(minutes)}", "${kmText(km)} by road · about ${durationText(minutes)} driving"))
    if (km <= 150) {
        val (lo, hi) = Fares.rideRange(km)
        Line(tr("เรียกรถ (Grab / Bolt / LINE MAN) หรือแท็กซี่มิเตอร์ ประมาณ $lo–$hi บาท", "Ride-hailing (Grab / Bolt / LINE MAN) or a metered taxi: about $lo–$hi baht"))
    } else {
        Line(tr("ระยะไกล: แนะนำเช่ารถขับเอง หรือจ้างรถพร้อมคนขับ (เรียกรถผ่านแอปได้แต่ราคาสูง)", "Long distance: renting a car or hiring a car with driver usually suits better (app rides are possible but costly)."))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RIDE_APPS.forEach { (name, pkg) -> OutlinedButton(onClick = { openApp(ctx, pkg) }) { Text(name, fontSize = 12.sp) } }
        OutlinedButton(onClick = { open(ctx, mapsDirections(a.lat, a.lon, b.lat, b.lon)) }) { Text(tr("เส้นทาง (Google Maps)", "Route (Google Maps)"), fontSize = 12.sp) }
        if (km > 150) OutlinedButton(onClick = { open(ctx, "https://www.google.com/maps/search/?api=1&query=" + enc("car rental near ${a.lat},${a.lon}")) }) { Text(tr("ร้านเช่ารถใกล้คุณ", "Car rental nearby"), fontSize = 12.sp) }
    }
    Text(tr("ค่าโดยสารเป็นการประมาณ ราคาจริงดูได้ในแอปก่อนกดเรียกรถ", "Fares are estimates; the app shows the real price before you book."), fontSize = 11.sp, color = Color.Gray)
}

// ---------------------------------------------------------------- City buses, songthaews, BTS/MRT, boats

@Composable
private fun CityTransit(vm: TripViewModel, a: End, b: End, mode: TravelMode) {
    val thai = LocalThai.current
    val ctx = LocalContext.current
    val plans by produceState<Result<List<TransitPlan>>?>(null, a, b, thai) {
        value = vm.remote.transitLines(a.lat, a.lon, b.lat, b.lon, thai).map { TransitPlanner.plan(it, a.lat, a.lon, b.lat, b.lon) }
    }
    Line(tr("🚏 ${a.label} → ${b.label} (ในเมือง)", "🚏 ${a.label} → ${b.label} (local)"), Color.Black, bold = true, size = 14)
    val p = plans
    when {
        p == null -> Loading()
        p.isFailure -> Line(tr("ดึงข้อมูลสายรถไม่ได้ในตอนนี้", "Couldn't load transit lines right now."), Coral)
        p.getOrThrow().isEmpty() -> Line(tr("ไม่พบสายรถที่ผ่านใกล้ทั้งสองจุดในข้อมูล OpenStreetMap ลองดูเส้นทางใน Google Maps หรือเรียกรถ", "No line in OpenStreetMap passes near both points. Try Google Maps directions or a ride."), Coral)
        else -> p.getOrThrow().forEachIndexed { i, plan ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            TransitPlanView(i + 1, plan, a, b)
        }
    }
    OutlinedButton(onClick = { open(ctx, transitUrl(a, b)) }) { Text(tr("ดูเส้นทางและเวลารถใน Google Maps", "Transit route & times in Google Maps"), fontSize = 12.sp) }
    Text(tr("ข้อมูลสายรถจาก OpenStreetMap อาจไม่ครบทุกสาย", "Line data comes from OpenStreetMap and may be incomplete."), fontSize = 11.sp, color = Color.Gray)
}

private fun kindLabel(k: TransitKind) = when (k) {
    TransitKind.BUS -> tr("🚌 รถเมล์", "🚌 Bus")
    TransitKind.SONGTHAEW -> tr("🛻 รถสองแถว", "🛻 Songthaew")
    TransitKind.METRO -> tr("🚇 รถไฟฟ้า", "🚇 Metro")
    TransitKind.TRAIN -> tr("🚆 รถไฟ", "🚆 Train")
    TransitKind.BOAT -> tr("⛴️ เรือ", "⛴️ Boat")
}

@Composable
private fun TransitPlanView(n: Int, plan: TransitPlan, a: End, b: End) {
    Line(tr("ทางเลือกที่ $n · ประมาณ ${durationText(plan.minutes)}", "Option $n · about ${durationText(plan.minutes)}"), Teal, bold = true)
    Line(tr("🚶 เดิน ${kmText(plan.walkToBoardKm)} จาก${a.label}ไปป้าย ", "🚶 Walk ${kmText(plan.walkToBoardKm)} from ${a.label} to ") + stopName(plan.rides[0].board.name))
    plan.rides.forEachIndexed { i, r ->
        if (i > 0) Line(
            if (plan.transferWalkKm < 0.03) tr("🔁 ต่อรถที่ป้ายเดียวกัน", "🔁 Change at the same stop")
            else tr("🔁 เดิน ${kmText(plan.transferWalkKm)} ไปต่อรถที่ ", "🔁 Walk ${kmText(plan.transferWalkKm)} to change at ") + stopName(r.board.name),
        )
        val line = listOf(r.line.ref, r.line.name).filter { it.isNotBlank() }.joinToString(" · ")
        Line("${kindLabel(r.line.kind)} $line" + (if (r.line.operator.isNotBlank()) " (${r.line.operator})" else ""), Color.Black, bold = true)
        Line(tr("   ขึ้นที่ ", "   Board at ") + stopName(r.board.name) + tr(" → ลงที่ ", " → get off at ") + stopName(r.alight.name) + tr(" (${r.stopCount} ป้าย)", " (${r.stopCount} stops)"))
    }
    Line(tr("🚶 เดิน ${kmText(plan.walkFromAlightKm)} ถึง${b.label}", "🚶 Walk ${kmText(plan.walkFromAlightKm)} to ${b.label}"))
}

private fun stopName(s: String) = s.ifBlank { tr("ป้ายไม่มีชื่อ", "an unnamed stop") }

// ---------------------------------------------------------------- Intercity bus / minivan / train

@Composable
private fun StationDetails(vm: TripViewModel, mode: TravelMode, a: End, b: End, from: Place, to: Place, date: LocalDate?) {
    val thai = LocalThai.current
    val ctx = LocalContext.current
    val hubType = if (mode == TravelMode.TRAIN) HubType.TRAIN else HubType.BUS
    val kind = if (mode == TravelMode.TRAIN) "train" else "bus"
    suspend fun station(e: End): Poi? {
        val hub = vm.repo.hubs.filter { it.type == hubType }.minByOrNull { TripLogic.distanceKm(e.lat, e.lon, it.lat, it.lon) }
            ?.takeIf { TripLogic.distanceKm(e.lat, e.lon, it.lat, it.lon) < 25 }
        if (hub != null) return Poi(hub.name.get(thai), "", hub.phone, hub.website, null, null, false, hub.lat, hub.lon, TripLogic.distanceKm(e.lat, e.lon, hub.lat, hub.lon))
        return vm.remote.stations(e.lat, e.lon, kind, thai).getOrNull()?.firstOrNull()
    }
    val found by produceState<Triple<Poi?, Poi?, Pair<Double, Int>?>?>(null, mode, a, b, thai) {
        val s1 = station(a)
        val s2 = station(b)
        val drive = if (s1 != null && s2 != null) vm.remote.drive(s1.lat, s1.lon, s2.lat, s2.lon).getOrNull() else null
        value = Triple(s1, s2, drive)
    }
    val f = found
    Line("${modeLabel(mode, thai)} · ${from.name.get(thai)} → ${to.name.get(thai)}", Color.Black, bold = true, size = 14)
    if (f == null) { Loading(); return }
    val (s1, s2, drive) = f
    if (s1 == null || s2 == null) {
        Line(
            if (mode == TravelMode.TRAIN) tr("ไม่พบสถานีรถไฟใกล้ต้นทางหรือปลายทาง ลองเลือกรถโดยสารแทน", "No railway station near one end. Try a bus instead.")
            else tr("ไม่พบสถานีขนส่งใกล้ต้นทางหรือปลายทางในข้อมูลแผนที่", "No bus terminal found near one end in the map data."),
            Coral,
        )
    }
    if (s1 != null) {
        Line(tr("🟢 ขึ้นรถที่ ", "🟢 Board at ") + s1.name, Color.Black, bold = true)
        Line(tr("   ห่างจาก${a.label} ${kmText(s1.distanceKm * 1.3)}", "   ${kmText(s1.distanceKm * 1.3)} from ${a.label}"))
        TextButton(onClick = { open(ctx, mapsDirections(a.lat, a.lon, s1.lat, s1.lon)) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(tr("นำทางไปจุดขึ้นรถ", "Directions to boarding point"), fontSize = 12.sp) }
    }
    if (s2 != null) {
        Line(tr("🔴 ลงรถที่ ", "🔴 Get off at ") + s2.name, Color.Black, bold = true)
        val toPlace = roadKm(s2.lat, s2.lon, to.lat, to.lon)
        Line(tr("   จากจุดลงถึง ${to.name.th} ประมาณ ${kmText(toPlace)}", "   About ${kmText(toPlace)} from there to ${to.name.en}"))
        if (b.isStay) Line(tr("   ถึงที่พัก ${b.label} ประมาณ ${kmText(roadKm(s2.lat, s2.lon, b.lat, b.lon))}", "   About ${kmText(roadKm(s2.lat, s2.lon, b.lat, b.lon))} to your stay, ${b.label}"))
        val last = roadKm(s2.lat, s2.lon, b.lat, b.lon)
        if (last > 1.5) {
            val (lo, hi) = Fares.rideRange(last)
            Line(tr("   ต่อแท็กซี่/เรียกรถ/สองแถว ประมาณ $lo–$hi บาท", "   Taxi, ride app or songthaew onward: about $lo–$hi baht"))
        }
    }
    drive?.let { (km, min) ->
        val factor = when (mode) { TravelMode.TRAIN -> 1.5; TravelMode.VAN -> 1.15; else -> 1.3 }
        Line(tr("ระยะทาง ${kmText(km)} · ใช้เวลาเดินทางประมาณ ${durationText((min * factor).roundToInt())}", "${kmText(km)} · about ${durationText((min * factor).roundToInt())} on the way"))
    }
    when (mode) {
        TravelMode.VAN -> Line(tr("รถตู้โดยสารระหว่างจังหวัดออกจากสถานีขนส่ง ซื้อตั๋วที่ช่องขายตั๋วรถตู้ในสถานี รถออกเมื่อคนเต็มหรือตามรอบ กระเป๋าใบใหญ่อาจต้องเสียค่าที่เพิ่ม",
            "Intercity minivans leave from the bus terminal; buy a ticket at the minivan counter there. They leave on schedule or when full. Big suitcases may need an extra seat."))
        TravelMode.BUS -> Line(tr("รถทัวร์ออกจากสถานีขนส่งตามรอบเวลา ซื้อตั๋วล่วงหน้าออนไลน์หรือที่ช่องขายตั๋ว", "Coaches leave the terminal on a timetable; buy tickets online or at the counter."))
        else -> Line(tr("ตรวจสอบขบวนรถ เวลา และที่นั่งก่อนเดินทาง", "Check trains, times and seats before you go."))
    }
    val fromCity = from.province.en
    val toCity = to.province.en
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (mode == TravelMode.TRAIN) {
            OutlinedButton(onClick = { open(ctx, "https://dticket.railway.co.th") }) { Text(tr("ดูเวลาและจองตั๋วรถไฟ", "Train times & tickets"), fontSize = 12.sp) }
        } else {
            OutlinedButton(onClick = { open(ctx, "https://12go.asia/en/travel/${enc(fromCity.lowercase().replace(' ', '-'))}/${enc(toCity.lowercase().replace(' ', '-'))}" + (date?.let { "?date=$it" } ?: "")) }) {
                Text(tr("ดูรอบรถและจองตั๋ว (12Go)", "Times & tickets (12Go)"), fontSize = 12.sp)
            }
            if (mode == TravelMode.BUS) OutlinedButton(onClick = { open(ctx, "https://www.transport.co.th") }) { Text(tr("บขส.", "Transport Co. (BKS)"), fontSize = 12.sp) }
        }
    }
}

// ---------------------------------------------------------------- Boat

@Composable
private fun BoatDetails(vm: TripViewModel, a: End, b: End, from: Place, to: Place, date: LocalDate?) {
    val thai = LocalThai.current
    val ctx = LocalContext.current
    val piers = vm.repo.hubs.filter { it.type == HubType.PIER }
    val p1 = piers.minByOrNull { TripLogic.distanceKm(a.lat, a.lon, it.lat, it.lon) }?.takeIf { TripLogic.distanceKm(a.lat, a.lon, it.lat, it.lon) < 80 }
    val p2 = piers.minByOrNull { TripLogic.distanceKm(b.lat, b.lon, it.lat, it.lon) }?.takeIf { TripLogic.distanceKm(b.lat, b.lon, it.lat, it.lon) < 80 && it != p1 }
    Line("⛴️ ${from.name.get(thai)} → ${to.name.get(thai)}", Color.Black, bold = true, size = 14)
    p1?.let {
        Line(tr("🟢 ลงเรือที่ ", "🟢 Board at ") + it.name.get(thai), Color.Black, bold = true)
        Line(tr("   ห่างจาก${a.label} ${kmText(roadKm(a.lat, a.lon, it.lat, it.lon))}", "   ${kmText(roadKm(a.lat, a.lon, it.lat, it.lon))} from ${a.label}"))
    }
    p2?.let {
        Line(tr("🔴 ขึ้นฝั่งที่ ", "🔴 Arrive at ") + it.name.get(thai), Color.Black, bold = true)
        Line(tr("   จากท่าเรือถึง${b.label} ประมาณ ${kmText(roadKm(it.lat, it.lon, b.lat, b.lon))}", "   About ${kmText(roadKm(it.lat, it.lon, b.lat, b.lon))} from the pier to ${b.label}"))
    }
    if (p1 == null && p2 == null) Line(tr("ไม่พบท่าเรือในข้อมูลของแอปใกล้สองจุดนี้", "No pier in the app's data near these places."), Coral)
    Line(tr("ตารางเรือขึ้นกับฤดูกาลและคลื่นลม ช่วงมรสุมอาจงดเดินเรือ", "Boat timetables depend on the season and the sea; some routes stop in the monsoon."))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOfNotNull(p1, p2).filter { it.website != null || it.phone != null }.forEach { h ->
            h.website?.let { w -> OutlinedButton(onClick = { open(ctx, w) }) { Text(h.name.get(thai), fontSize = 12.sp) } }
            h.phone?.let { ph -> OutlinedButton(onClick = { dial(ctx, ph) }) { Text("📞 $ph", fontSize = 12.sp) } }
        }
        OutlinedButton(onClick = { open(ctx, "https://12go.asia/en/travel/${enc(from.province.en.lowercase().replace(' ', '-'))}/${enc(to.province.en.lowercase().replace(' ', '-'))}" + (date?.let { "?date=$it" } ?: "")) }) {
            Text(tr("ดูรอบเรือและจองตั๋ว (12Go)", "Boat times & tickets (12Go)"), fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------- Travel options (scheduled, multi-leg)

fun modeIcon(m: TravelMode) = when (m) {
    TravelMode.FLIGHT -> "✈️"; TravelMode.TRAIN -> "🚆"; TravelMode.BUS -> "🚌"
    TravelMode.BOAT -> "⛴️"; TravelMode.CAR -> "🚕"; TravelMode.VAN -> "🚐"
}

private fun modeWord(m: TravelMode) = when (m) {
    TravelMode.FLIGHT -> tr("เครื่องบิน", "flight"); TravelMode.TRAIN -> tr("รถไฟ", "train"); TravelMode.BUS -> tr("รถทัวร์", "bus")
    TravelMode.BOAT -> tr("เรือ", "boat"); TravelMode.CAR -> tr("เรียกรถ / ขับรถ", "ride / drive"); TravelMode.VAN -> tr("รถตู้", "minivan")
}

/** "06:20" or "06:20 (+1 day)". */
private fun timeText(min: Int): String {
    val d = Itinerary.dayOffset(min)
    return Itinerary.formatTime(min) + when { d > 0 -> tr(" (+$d วัน)", " (+$d day)"); d < 0 -> tr(" (วันก่อน)", " (day before)"); else -> "" }
}

/** Title of an option: the chain of rides, e.g. "🚐 รถตู้ → 🚌 รถทัวร์ → ⛴️ เรือ". */
private fun optionTitle(o: Itinerary.Option): String =
    if (o.first.rides.isEmpty()) "🚕 " + tr("เรียกรถ / แท็กซี่ / ขับรถไปเอง", "Ride-hailing, taxi or drive")
    else o.first.rides.joinToString(" → ") { "${modeIcon(it.route.mode)} ${modeWord(it.route.mode)}" }

/**
 * The list of practical ways to travel a leg, built from real timetables, each with several
 * departure times; the chosen one shows every step with its times, operator and how to get
 * to and from the stations.
 */
@Composable
fun TravelOptions(vm: TripViewModel, leg: th.trip.planner.model.Leg, onChange: (th.trip.planner.model.Leg) -> Unit, from: Place, to: Place, fromStay: Lodging?, toStay: Lodging?, date: LocalDate?) {
    val thai = LocalThai.current
    val ends = rememberEnds(vm, from, to, fromStay, toStay)
    val day = date ?: LocalDate.now()
    val prefer = leg.prefer?.let { p -> TravelMode.entries.firstOrNull { it.name == p } }
    fun points(e: Pair<End, End>) = Itinerary.Point(e.first.lat, e.first.lon, from.island, from.islandGroup) to Itinerary.Point(e.second.lat, e.second.lon, to.island, to.islandGroup)
    val options by produceState<List<Itinerary.Option>?>(null, ends, day) {
        val e = ends ?: return@produceState
        val (pa, pb) = points(e)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Itinerary.options(pa, pb, day, vm.repo.hubs, vm.repo.routes) }
    }
    // Ways built around the kind of travel the user prefers, connected all the way.
    val preferred by produceState<List<Itinerary.Option>?>(null, ends, day, prefer) {
        val e = ends ?: return@produceState
        if (prefer == null) { value = null; return@produceState }
        value = null
        val (pa, pb) = points(e)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Itinerary.options(pa, pb, day, vm.repo.hubs, vm.repo.routes, require = prefer) }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("ทางเลือกในการเดินทาง", "Ways to travel"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        val e = ends
        val all = options
        if (e == null || all == null) { Loading(); return@Column }
        val (a, b) = e
        // Kinds of travel that can work here (from the options found).
        val kinds = buildList {
            all.forEach { o -> o.first.rides.forEach { r -> if (r.route.mode !in this) add(r.route.mode) } }
            if (all.any { it.first.rides.isEmpty() }) add(TravelMode.CAR)
        }.sortedBy { listOf(TravelMode.TRAIN, TravelMode.BOAT, TravelMode.BUS, TravelMode.VAN, TravelMode.FLIGHT, TravelMode.CAR).indexOf(it) }
        val many = all.size > 3 && kinds.size > 1
        if (many) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFFFF6E5)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Line(
                    if (leg.prefer == null) tr("มีหลายทางเลือก อยากเดินทางแบบไหนเป็นหลัก?", "There are many ways. How would you mainly like to travel?")
                    else tr("เดินทางหลักโดย", "Main way to travel"),
                    Color.Black, bold = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kinds.forEach { m ->
                        androidx.compose.material3.FilterChip(selected = prefer == m, onClick = { onChange(leg.copy(prefer = m.name)) }, label = { Text(modeLabel(m, thai)) })
                    }
                    androidx.compose.material3.FilterChip(selected = leg.prefer == "ALL", onClick = { onChange(leg.copy(prefer = "ALL")) }, label = { Text(tr("ดูทุกแบบ", "Show all")) })
                }
                if (leg.prefer == null) Line(tr("แอปจะหาเส้นทางที่ใช้วิธีนี้เป็นหลัก แล้วต่อรถ เรือ หรือรถไฟฟ้าให้ครบจนถึงที่หมาย", "The app will build ways around it and connect every step to your destination."), size = 12)
            }
            if (leg.prefer == null) return@Column
        }
        val list = when {
            !many || prefer == null -> all
            else -> preferred ?: run { Loading(); return@Column }
        }
        if (many && prefer != null && list.isEmpty()) Line(tr("ไม่พบเส้นทางที่ใช้${modeLabel(prefer, thai)}แล้วต่อได้ครบ ลองเลือกแบบอื่น หรือดูทุกแบบ", "No complete connection using ${modeLabel(prefer, thai)}; try another kind or show all."), Coral)
        if (all.isEmpty()) Line(tr("ยังไม่มีตารางเวลารถ เรือ รถไฟ หรือเครื่องบินที่ต่อกันได้สำหรับช่วงนี้ในแอป ดูทางเลือกอื่นด้านล่าง", "No connecting timetables for this leg in the app yet; see other ways below."), Coral)
        list.forEach { o ->
            val selected = leg.plan == o.signature || (leg.plan == null && o.first.rides.isEmpty() && leg.mode == TravelMode.CAR)
            androidx.compose.material3.Card(
                Modifier.fillMaxWidth().clickable { if (!selected) onChange(leg.copy(mode = o.mainMode, plan = o.signature, depart = o.first.depart)) },
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = if (selected) Color(0xFFE3F4EF) else Color(0xFFF6F8FB)),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = selected, onClick = { onChange(leg.copy(mode = o.mainMode, plan = o.signature, depart = o.first.depart)) })
                        Text(optionTitle(o), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                    val j0 = o.first
                    if (j0.rides.isEmpty()) Line(tr("ประมาณ ", "About ") + durationText(j0.minutes) + " · " + kmText(roadKm(a.lat, a.lon, b.lat, b.lon)))
                    else {
                        Line(tr("ใช้เวลาประมาณ ", "Takes about ") + durationText(o.journeys.minOf { it.minutes }) + tr(" รวมรอต่อรถ", " incl. connections"))
                        Line(tr("รอบที่ออกได้: ", "Departures: ") + o.journeys.joinToString(", ") { Itinerary.formatTime(it.depart) + "→" + Itinerary.formatTime(it.arrive) + (Itinerary.dayOffset(it.arrive).takeIf { d -> d > 0 }?.let { d -> tr(" (+$d วัน)", " (+$d day)") } ?: "") }, size = 12)
                    }
                    if (selected) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        if (j0.rides.isEmpty()) CarDetails(vm, a, b)
                        else {
                            val chosen = o.journeys.firstOrNull { it.depart == leg.depart } ?: j0
                            if (o.journeys.size > 1) {
                                Line(tr("เลือกรอบเดินทาง", "Pick a departure"), bold = true)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    o.journeys.forEach { j ->
                                        androidx.compose.material3.FilterChip(
                                            selected = j === chosen,
                                            onClick = { onChange(leg.copy(mode = o.mainMode, plan = o.signature, depart = j.depart)) },
                                            label = { Text(Itinerary.formatTime(j.depart) + " → " + timeText(j.arrive), fontSize = 12.sp) },
                                        )
                                    }
                                }
                            }
                            JourneySteps(vm, chosen, a, b)
                        }
                    }
                }
            }
        }
        if (list.any { it.first.rides.isNotEmpty() }) Text(
            tr("ตารางเวลารวบรวมจากเว็บไซต์ผู้ให้บริการและอาจเปลี่ยนได้ ควรตรวจสอบหรือจองกับผู้ให้บริการก่อนเดินทาง · เวลาไปสถานีเป็นการประมาณ",
                "Timetables are collected from operators' sites and can change; check or book with the operator before you go. Times to stations are estimates."),
            fontSize = 11.sp, color = Color.Gray,
        )
        OtherWays(vm, leg, onChange, from, to, fromStay, toStay, date, all)
    }
}

@Composable
private fun JourneySteps(vm: TripViewModel, j: Itinerary.Journey, a: End, b: End) {
    j.steps.forEach { s ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (s) {
                is Itinerary.Step.Ground -> GroundStep(vm, s, a, b)
                is Itinerary.Step.Ride -> RideStep(vm, s)
            }
        }
    }
}

/** Getting between the start/end and a station, or between two stations: every local way we know. */
@Composable
private fun GroundStep(vm: TripViewModel, s: Itinerary.Step.Ground, a: End, b: End) {
    val thai = LocalThai.current
    val ctx = LocalContext.current
    val fromName = s.from?.name?.get(thai) ?: a.label
    val toName = s.to?.name?.get(thai) ?: b.label
    val fa = s.from?.let { End(it.name.get(thai), it.lat, it.lon, false) } ?: a
    val tb = s.to?.let { End(it.name.get(thai), it.lat, it.lon, false) } ?: b
    val road = s.km * 1.3
    Line("${timeText(s.start)} – ${timeText(s.end)}", Teal, bold = true, size = 12)
    if (s.walk) { Line(tr("🚶 เดินจาก $fromName ไป $toName (${kmText(road)})", "🚶 Walk from $fromName to $toName (${kmText(road)})"), Color.Black); return }
    Line("📍 $fromName → $toName", Color.Black, bold = true)
    val (lo, hi) = Fares.rideRange(road)
    Line(tr("🚕 แท็กซี่/เรียกรถ ระยะทางราว ${kmText(road)} · ประมาณ ${durationText(s.end - s.start)} · ราว $lo–$hi บาท", "🚕 Taxi or ride: about ${kmText(road)} · ${durationText(s.end - s.start)} · about $lo–$hi baht"))
    // Bangkok rail (BTS, MRT, Airport Rail Link, Red lines) when both ends are near a station.
    val rail = remember(fa, tb) { if (road > 1.5) RailPlanner.plan(vm.repo.rail, fa.lat, fa.lon, tb.lat, tb.lon) else null }
    if (rail != null) RailPlanView(rail, fa.label, tb.label)
    // Curated local transport at the stations, airports and piers.
    s.to?.let { h -> vm.repo.access[h.id]?.takeIf { it.isNotEmpty() }?.let { AccessList(tr("วิธีไป ${h.name.get(thai)}", "Getting to ${h.name.get(thai)}"), it) } }
    s.from?.let { h -> vm.repo.access[h.id]?.takeIf { it.isNotEmpty() }?.let { AccessList(tr("ออกจาก ${h.name.get(thai)} ไปต่อ", "Leaving ${h.name.get(thai)}"), it) } }
    if (road <= 40) {
        var show by remember { mutableStateOf(false) }
        TextButton(onClick = { show = !show }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text((if (show) "▾ " else "▸ ") + tr("ค้นหาสายรถเมล์ / รถสองแถว / เรือที่ผ่านทั้งสองจุด", "Find bus / songthaew / boat lines passing both points"), fontSize = 12.sp)
        }
        if (show) CityTransit(vm, fa, tb, TravelMode.BUS)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RIDE_APPS.forEach { (name, pkg) -> TextButton(onClick = { openApp(ctx, pkg) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(name, fontSize = 12.sp) } }
        TextButton(onClick = { open(ctx, transitUrl(fa, tb)) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(tr("🚌 เส้นทางรถสาธารณะ (Google Maps)", "🚌 Public transport (Google Maps)"), fontSize = 12.sp) }
    }
}

private fun accessKind(k: String): String = when (k) {
    "BTS" -> tr("🚝 รถไฟฟ้า BTS", "🚝 BTS Skytrain")
    "MRT" -> tr("🚇 รถไฟฟ้า MRT", "🚇 MRT")
    "ARL" -> tr("🚆 แอร์พอร์ต เรล ลิงก์", "🚆 Airport Rail Link")
    "SRT_RED" -> tr("🚆 รถไฟฟ้าสายสีแดง", "🚆 SRT Red Line")
    "GOLD" -> tr("🚝 รถไฟฟ้าสายสีทอง", "🚝 Gold Line")
    "MONORAIL" -> tr("🚝 รถไฟฟ้าโมโนเรล", "🚝 Monorail")
    "BRT" -> tr("🚌 รถโดยสารด่วนพิเศษ BRT", "🚌 BRT bus")
    "BUS" -> tr("🚌 รถเมล์", "🚌 City bus")
    "AC_BUS" -> tr("🚌 รถเมล์ปรับอากาศ", "🚌 Air-con bus")
    "AIRPORT_BUS" -> tr("🚌 รถบัสสนามบิน", "🚌 Airport bus")
    "VAN" -> tr("🚐 รถตู้", "🚐 Minivan")
    "SONGTHAEW" -> tr("🛻 รถสองแถว", "🛻 Songthaew")
    "SHUTTLE" -> tr("🚐 รถรับส่ง", "🚐 Shuttle")
    "BOAT" -> tr("⛴️ เรือ", "⛴️ Boat")
    "TAXI" -> tr("🚕 แท็กซี่", "🚕 Taxi")
    "WALK" -> tr("🚶 เดิน", "🚶 Walk")
    else -> k
}

private fun parseColor(hex: String?): Color? = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

@Composable
private fun ColorDot(c: Color?) {
    if (c == null) return
    Box(Modifier.size(10.dp).clip(CircleShape).background(c))
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun AccessList(title: String, items: List<HubAccess>) {
    var all by remember { mutableStateOf(false) }
    Line(title, Color.Black, bold = true)
    (if (all) items else items.take(4)).forEach { AccessItem(it) }
    if (items.size > 4) TextButton(onClick = { all = !all }, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(if (all) tr("ย่อ", "Show less") else tr("ดูอีก ${items.size - 4} วิธี", "${items.size - 4} more ways"), fontSize = 12.sp)
    }
}

@Composable
private fun AccessItem(x: HubAccess) {
    val thai = LocalThai.current
    Column(Modifier.padding(start = 6.dp, top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(parseColor(x.color))
            Text(
                accessKind(x.kind) + (x.ref?.let { tr(" สาย $it", " no. $it") } ?: "") + (x.line?.let { " · " + it.get(thai) } ?: ""),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        x.from?.let { Line(tr("ไป/มาจาก: ", "To/from: ") + it.get(thai), size = 12) }
        x.board?.let { Line(tr("จุดขึ้น: ", "Where to board: ") + it.get(thai), size = 12) }
        listOfNotNull(
            x.minutes?.let { tr("ใช้เวลาราว ", "about ") + durationText(it) },
            x.everyMin?.let { tr("ทุก $it นาที", "every $it min") },
            x.hours?.let { tr("เวลา $it", "runs $it") },
            x.fare?.get(thai),
        ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Line(it, size = 12) }
        x.note?.let { Line(it.get(thai), Color.Gray, size = 12) }
    }
}

@Composable
private fun RailPlanView(p: RailPlan, fromName: String, toName: String) {
    val thai = LocalThai.current
    Line(tr("🚆 ไปด้วยรถไฟฟ้า ประมาณ ${durationText(p.minutes)}", "🚆 By rail, about ${durationText(p.minutes)}"), Color.Black, bold = true)
    Line(tr("🚶 เดิน ${kmText(p.walkInKm * 1.2)} จาก $fromName ไปสถานี ${p.rides[0].board.name.get(thai)}", "🚶 Walk ${kmText(p.walkInKm * 1.2)} from $fromName to ${p.rides[0].board.name.get(thai)} station"), size = 12)
    p.rides.forEachIndexed { i, r ->
        if (i > 0) Line(tr("🔁 เปลี่ยนสายที่สถานี ${r.board.name.get(thai)}", "🔁 Change at ${r.board.name.get(thai)}"), size = 12)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
            ColorDot(parseColor(r.line.color))
            Text(r.line.name.get(thai), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Line(tr("   ขึ้นที่ ${r.board.name.get(thai)} → ลงที่ ${r.alight.name.get(thai)} (${r.stops} สถานี)", "   ${r.board.name.get(thai)} → ${r.alight.name.get(thai)} (${r.stops} stops)"), size = 12)
    }
    Line(tr("🚶 เดิน ${kmText(p.walkOutKm * 1.2)} ไป $toName", "🚶 Walk ${kmText(p.walkOutKm * 1.2)} to $toName"), size = 12)
}

/** Times of this run at each stop of the route (minutes after midnight), when known. */
private fun runTimes(s: Itinerary.Step.Ride): List<Int?>? {
    val r = s.route
    val day = s.start - Itinerary.dayOffset(s.start) * 1440
    r.trips.forEach { t ->
        val times = t.times.map { Itinerary.parseTime(it) }
        if (times.getOrNull(s.boardIdx) == day && (s.code == null || t.code == null || t.code == s.code)) {
            return times.map { it?.let { m -> m + (s.start - day) } }
        }
    }
    val mins = r.headway?.minutes ?: return null
    if (mins.size != r.stops.size) return null
    return mins.map { s.start + it - mins[s.boardIdx] }
}

@Composable
private fun RideStep(vm: TripViewModel, s: Itinerary.Step.Ride) {
    val thai = LocalThai.current
    val ctx = LocalContext.current
    val r = s.route
    Line("${timeText(s.start)} – ${timeText(s.end)}", Teal, bold = true, size = 12)
    Line("${modeIcon(r.mode)} ${r.operator.get(thai)} · ${r.name.get(thai)}" + (s.code?.let { " ($it)" } ?: ""), Color.Black, bold = true)
    Line(tr("ขึ้นที่ ${s.board.name.get(thai)} → ลงที่ ${s.alight.name.get(thai)}", "Board at ${s.board.name.get(thai)} → get off at ${s.alight.name.get(thai)}"))
    Line(tr("ไปถึงก่อนออกอย่างน้อย ${Itinerary.buffer(r.mode)} นาที", "Be there at least ${Itinerary.buffer(r.mode)} min before"), Color.Gray, size = 12)
    if (r.mode == TravelMode.FLIGHT) {
        val code = r.id.split('_').getOrNull(1)
        val airlines = vm.repo.flights.airlines
        val al = airlines.firstOrNull { it.code.equals(code, true) }
            ?: airlines.firstOrNull { r.operator.en.contains(it.name, true) || it.name.contains(r.operator.en, true) }
        if (al != null) {
            Line(tr("สายการบิน: ${al.name}", "Airline: ${al.name}"), Color.Black)
            val carry = al.carryOnNote?.get(thai) ?: al.carryOnKg?.let { tr("ถือขึ้นเครื่อง $it กก.", "Carry-on $it kg") }
            carry?.let { Line("🎒 $it", size = 12) }
            al.checkedNote?.let { Line("🧳 ${it.get(thai)}", size = 12) }
            Line(tr("น้ำหนักกระเป๋าเป็นเงื่อนไขทั่วไปของตั๋วราคาประหยัด ตรวจตั๋วของคุณอีกครั้ง", "Baggage is the usual allowance on the cheapest fares; check your ticket."), Color.Gray, size = 11)
        }
    }
    r.fare?.let { Line("💵 " + it.get(thai)) }
    if (r.stops.size > 2) {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text((if (open) "▾ " else "▸ ") + tr("จุดจอดรับ-ส่งของเที่ยวนี้ (${r.stops.size} จุด)", "Stops on this run (${r.stops.size})"), fontSize = 12.sp)
        }
        if (open) {
            val times = runTimes(s)
            r.stops.forEachIndexed { i, id ->
                val name = vm.repo.hubById[id]?.name?.get(thai) ?: id
                val t = times?.getOrNull(i)
                val mark = when (i) { s.boardIdx -> tr(" ← ขึ้นที่นี่", " ← board here"); s.alightIdx -> tr(" ← ลงที่นี่", " ← get off here"); else -> "" }
                val inRide = i in s.boardIdx..s.alightIdx
                Line("• " + (t?.let { timeText(it) + " " } ?: "") + name + mark, if (inRide) Color.Black else Color.Gray, bold = i == s.boardIdx || i == s.alightIdx, size = 12)
            }
            if (s.boardIdx > 0 || s.alightIdx < r.stops.size - 1) Line(tr("ขึ้นหรือลงที่จุดอื่นในรายการได้ ถ้าสะดวกกว่า", "You can board or get off at another stop on the list if it suits you better."), Color.Gray, size = 11)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        r.bookUrl?.let { u -> TextButton(onClick = { open(ctx, u) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(tr("🎫 จอง / ดูรายละเอียด", "🎫 Book / details"), fontSize = 12.sp) } }
        s.board.phone?.let { ph -> TextButton(onClick = { dial(ctx, ph) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("📞 $ph", fontSize = 12.sp) } }
    }
    r.source?.let { src ->
        val host = runCatching { Uri.parse(src).host?.removePrefix("www.") }.getOrNull() ?: src
        Text(tr("ข้อมูลเวลา: $host", "Times from: $host") + (r.checked?.let { tr(" (ตรวจ $it)", " (checked $it)") } ?: ""), fontSize = 11.sp, color = Color.Gray)
    }
}

/**
 * Ways that are possible but have no timetable in the app yet (only modes that can really work
 * here: no train without stations nearby, no boat between two places on the same land).
 */
@Composable
private fun OtherWays(vm: TripViewModel, leg: th.trip.planner.model.Leg, onChange: (th.trip.planner.model.Leg) -> Unit, from: Place, to: Place, fromStay: Lodging?, toStay: Lodging?, date: LocalDate?, scheduled: List<Itinerary.Option>) {
    val thai = LocalThai.current
    val hubs = vm.repo.hubs
    fun land(h: Hub) = h.islandGroup
    fun near(p: Place, t: HubType, km: Double) = hubs.any { it.type == t && land(it) == p.islandGroup && TripLogic.distanceKm(p.lat, p.lon, it.lat, it.lon) <= km }
    val sameLand = from.islandGroup == to.islandGroup && !(from.island && from.islandGroup == null) && !(to.island && to.islandGroup == null)
    val dist = TripLogic.distanceKm(from.lat, from.lon, to.lat, to.lon)
    val have = scheduled.map { it.mainMode }.toSet()
    val modes = buildList {
        if (dist > 150 && near(from, HubType.AIRPORT, 160.0) && near(to, HubType.AIRPORT, 160.0)) add(TravelMode.FLIGHT)
        if (sameLand && dist > 60 && near(from, HubType.TRAIN, 40.0) && near(to, HubType.TRAIN, 40.0)) add(TravelMode.TRAIN)
        if (sameLand && from.islandGroup == null && near(from, HubType.BUS, 60.0) && near(to, HubType.BUS, 60.0)) add(TravelMode.BUS)
        if (sameLand && from.islandGroup == null && dist in 30.0..450.0) add(TravelMode.VAN)
        if (!sameLand && (near(from, HubType.PIER, 60.0) || near(to, HubType.PIER, 60.0))) add(TravelMode.BOAT)
        if (sameLand && scheduled.none { it.first.rides.isEmpty() }) add(TravelMode.CAR)
    }.filter { it !in have }
    if (modes.isEmpty()) return
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(leg.plan == null && leg.mode != null && leg.mode in modes) }
    TextButton(onClick = { open = !open }) {
        Text((if (open) "▾ " else "▸ ") + tr("ทางเลือกอื่น (ยังไม่มีตารางเวลาในแอป)", "Other ways (no timetable in the app yet)"), fontSize = 13.sp)
    }
    if (!open) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        modes.forEach { m ->
            androidx.compose.material3.FilterChip(selected = leg.plan == null && leg.mode == m, onClick = { onChange(leg.copy(mode = m, plan = null, depart = null)) }, label = { Text(modeLabel(m, thai)) })
        }
    }
    if (leg.plan == null && leg.mode != null && leg.mode in modes) LegDetails(vm, leg.mode, from, to, fromStay, toStay, date)
}

/** One-line-per-ride summary of the chosen option (for the trip summary). */
@Composable
fun ChosenPlanSummary(vm: TripViewModel, leg: th.trip.planner.model.Leg, from: Place, to: Place, fromStay: Lodging?, toStay: Lodging?, date: LocalDate?) {
    val thai = LocalThai.current
    val ctx = LocalContext.current
    val ends = rememberEnds(vm, from, to, fromStay, toStay)
    val day = date ?: LocalDate.now()
    val journey by produceState<Itinerary.Journey?>(null, ends, day, leg.plan, leg.depart) {
        val e = ends ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            Itinerary.options(
                Itinerary.Point(e.first.lat, e.first.lon, from.island, from.islandGroup),
                Itinerary.Point(e.second.lat, e.second.lon, to.island, to.islandGroup),
                day, vm.repo.hubs, vm.repo.routes,
            ).firstOrNull { it.signature == leg.plan }?.let { o -> o.journeys.firstOrNull { it.depart == leg.depart } ?: o.first }
        }
    }
    val j = journey ?: return
    j.rides.forEach { r ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${Itinerary.formatTime(r.start)} ${modeIcon(r.route.mode)} ${r.board.name.get(thai)} → ${r.alight.name.get(thai)} ${timeText(r.end)}\n" +
                    r.route.operator.get(thai) + (r.code?.let { " · $it" } ?: ""),
                fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            r.route.bookUrl?.let { u -> TextButton(onClick = { open(ctx, u) }) { Text(tr("จอง", "Book")) } }
        }
    }
    if (j.rides.isEmpty()) Text(tr("เรียกรถ / ขับรถ ประมาณ ", "Ride or drive, about ") + durationText(j.minutes), fontSize = 13.sp)
}
