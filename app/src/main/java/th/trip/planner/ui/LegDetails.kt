@file:OptIn(ExperimentalLayoutApi::class)

package th.trip.planner.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/**
 * Everything the traveller needs to decide on and use one way of travelling between two
 * places: where to board and get off, lines and changes, airlines and baggage, times,
 * distances to the place and the stay, rough costs and where to book.
 */
@Composable
fun LegDetails(vm: TripViewModel, mode: TravelMode, from: Place, to: Place, fromStay: Lodging?, toStay: Lodging?, date: LocalDate?) {
    val thai = LocalThai.current
    // Where the leg really starts and ends: the stays, when we know (or can find) where they are.
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
                    if (al.website.isNotBlank()) TextButton(onClick = { open(ctx, al.website) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(tr("เว็บไซต์", "Website"), fontSize = 12.sp) }
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
