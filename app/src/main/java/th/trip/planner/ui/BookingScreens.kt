@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package th.trip.planner.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import th.trip.planner.TripViewModel
import th.trip.planner.data.Poi
import th.trip.planner.logic.TripLogic
import th.trip.planner.model.Hub
import th.trip.planner.model.HubType
import th.trip.planner.model.Leg
import th.trip.planner.model.Lodging
import th.trip.planner.model.Place
import th.trip.planner.model.TravelMode
import java.net.URLEncoder
import java.time.LocalDate

fun open(ctx: Context, url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
fun dial(ctx: Context, phone: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' }))) }
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
fun mapsSearch(query: String, lat: Double, lon: Double) = "https://www.google.com/maps/search/?api=1&query=${enc("$query near $lat,$lon")}"
fun mapsDirections(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) =
    "https://www.google.com/maps/dir/?api=1&origin=$fromLat,$fromLon&destination=$toLat,$toLon&travelmode=driving"

// ---------------------------------------------------------------- Step 2: stays

@Composable
fun LodgingScreen(vm: TripViewModel, nav: NavHostController) {
    val thai = LocalThai.current
    var picking by remember { mutableStateOf<Place?>(null) }
    Page(
        tr("2. เลือกที่พัก", "2. Choose where to stay"), nav, step = 2,
        bottom = { NextButton(tr("ต่อไป: การเดินทาง", "Next: transport")) { vm.ensureLegs(); nav.navigate(Routes.TRANSPORT) } },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    tr("ดูราคาและห้องว่างจริงได้ที่เว็บจองที่พัก แล้วบันทึกที่พักที่เลือกไว้ในแอป", "Check live prices and availability on booking sites, then save your choice here."),
                    fontSize = 14.sp, color = Color.DarkGray,
                )
            }
            items(vm.trip.stops, key = { it.placeId }) { s ->
                val p = vm.repo.placeById[s.placeId] ?: return@items
                val d = TripLogic.dates(s)
                val lodging = vm.trip.lodgings[p.id] ?: Lodging()
                val ctx = LocalContext.current
                val checkIn = d.firstOrNull()
                val checkOut = d.lastOrNull()?.plusDays(1)
                val city = "${p.name.en}, ${p.province.en}"
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(p.name.get(thai), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        if (checkIn != null) Text(tr("เช็กอิน ${fmt(checkIn, true)} · เช็กเอาต์ ${fmt(checkOut!!, true)}", "Check-in ${fmt(checkIn, false)} · Check-out ${fmt(checkOut!!, false)}"), fontSize = 13.sp, color = Color.Gray)
                        if (d.size >= 2 && TripLogic.daysBetween(d.first(), d.last()).any { it !in d })
                            Text(tr("วันไม่ต่อเนื่อง อาจต้องจองแยกเป็นช่วงๆ", "Days aren't consecutive; you may need separate bookings."), fontSize = 12.sp, color = Coral)
                        VSpace()
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                open(ctx, "https://www.booking.com/searchresults.html?ss=${enc(city)}" + (if (checkIn != null) "&checkin=$checkIn&checkout=$checkOut" else ""))
                            }) { Text("Booking.com") }
                            OutlinedButton(onClick = {
                                open(ctx, "https://www.agoda.com/search?textToSearch=${enc(city)}" + (if (checkIn != null) "&checkIn=$checkIn&checkOut=$checkOut" else ""))
                            }) { Text("Agoda") }
                            OutlinedButton(onClick = { open(ctx, mapsSearch("hotels", p.lat, p.lon)) }) { Text("Google Maps") }
                            if (vm.repo.placesKey.isNotBlank()) OutlinedButton(onClick = { picking = p }) { Text(tr("ดูรายการที่พักใกล้เคียง", "Browse nearby stays")) }
                        }
                        VSpace()
                        OutlinedTextField(
                            value = lodging.name, onValueChange = { vm.setLodging(p.id, lodging.copy(name = it)) },
                            label = { Text(tr("ที่พักที่เลือก", "Chosen stay")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = lodging.note, onValueChange = { vm.setLodging(p.id, lodging.copy(note = it)) },
                            label = { Text(tr("หมายเหตุ เช่น เลขที่การจอง", "Note, e.g. booking number")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
    picking?.let { p ->
        AlertDialog(
            onDismissRequest = { picking = null },
            confirmButton = { TextButton(onClick = { picking = null }) { Text(tr("ปิด", "Close")) } },
            title = { Text(tr("ที่พักใกล้ ${p.name.th}", "Stays near ${p.name.en}")) },
            text = {
                PoiList(vm, p.lat, p.lon, "lodging", byDistance = false, modifier = Modifier.heightIn(max = 480.dp)) { poi ->
                    vm.setLodging(p.id, Lodging(poi.name, poi.mapsUri ?: "", poi.phone ?: ""))
                    picking = null
                }
            },
        )
    }
}

// ---------------------------------------------------------------- Step 3: transport

private data class End(val label: String, val lat: Double, val lon: Double, val hubs: List<Hub>, val island: Boolean)

@Composable
fun TransportScreen(vm: TripViewModel, nav: NavHostController) {
    val thai = LocalThai.current
    val trip = vm.trip
    val repo = vm.repo
    LaunchedEffect(Unit) { vm.ensureLegs() }
    var hubPicker by remember { mutableStateOf<String?>(null) } // "origin" or "end"
    val inThailand = trip.originCountry.isBlank() || trip.originCountry.trim().lowercase() in setOf("thailand", "ไทย", "ประเทศไทย", "th")

    val startLabel = tr("จุดเริ่มต้น", "Start")
    fun endFor(legIndex: Int, from: Boolean): End? {
        val stops = trip.stops
        val idx = if (from) legIndex - 1 else legIndex
        if (idx < 0) {
            val hub = trip.originHubId?.let { repo.hubById[it] }
            return if (hub != null) End(hub.name.get(thai), hub.lat, hub.lon, listOf(hub), false)
            else End(trip.originCity.ifBlank { startLabel }, 13.75, 100.5, if (inThailand) emptyList() else repo.hubs.filter { it.id in setOf("bkk", "dmk") }, false)
        }
        if (idx >= stops.size) {
            val hub = trip.endHubId?.let { repo.hubById[it] } ?: return null
            return End(hub.name.get(thai), hub.lat, hub.lon, listOf(hub), false)
        }
        val p = repo.placeById[stops[idx].placeId] ?: return null
        return End(p.name.get(thai), p.lat, p.lon, TripLogic.hubsFor(p, repo.hubs), p.island)
    }

    val ready = trip.legs.size == TripLogic.legCount(trip) && trip.legs.all { it.mode != null } && trip.endHubId != null
    Page(
        tr("3. วางแผนการเดินทาง", "3. Plan your transport"), nav, step = 3,
        bottom = { NextButton(tr("สรุปแผนการเดินทาง", "Summarise the plan"), enabled = ready) { nav.navigate(Routes.SUMMARY) } },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(tr("คุณจะเริ่มเดินทางจากที่ไหน?", "Where will you start from?"), fontWeight = FontWeight.Bold)
                        OutlinedTextField(trip.originCity, { v -> vm.update { it.copy(originCity = v) } }, label = { Text(tr("เมือง", "City")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(trip.originCountry, { v -> vm.update { it.copy(originCountry = v) } }, label = { Text(tr("ประเทศ", "Country")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (inThailand) {
                            VSpace()
                            Text(tr("จุดขึ้นรถ/เรือ/เครื่องบินในเมืองนั้น", "Departure point in that city"), fontSize = 13.sp)
                            OutlinedButton(onClick = { hubPicker = "origin" }, modifier = Modifier.fillMaxWidth()) {
                                Text(trip.originHubId?.let { repo.hubById[it]?.name?.get(thai) } ?: tr("เลือกสถานี / สนามบิน / ท่าเรือ", "Choose station / airport / pier"))
                            }
                        } else {
                            Text(tr("เดินทางจากต่างประเทศ: แนะนำบินเข้าสุวรรณภูมิ ดอนเมือง หรือสนามบินนานาชาติใกล้สถานที่แรก", "From abroad: fly into Suvarnabhumi, Don Mueang or an international airport near your first place."), fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                }
            }
            val n = TripLogic.legCount(trip)
            items((0 until n).toList()) { i ->
                val from = endFor(i, true)
                val to = endFor(i, false)
                val leg = trip.legs.getOrNull(i) ?: Leg()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(tr("ช่วงที่ ${i + 1}", "Leg ${i + 1}"), fontSize = 12.sp, color = Teal)
                        Text("${from?.label ?: "?"} → ${to?.label ?: tr("(เลือกจุดหมายสุดท้ายด้านล่าง)", "(choose the end point below)")}", fontWeight = FontWeight.Bold)
                        if (from != null && to != null) {
                            val km = TripLogic.distanceKm(from.lat, from.lon, to.lat, to.lon)
                            val options = if (i == 0 && !inThailand) listOf(TravelMode.FLIGHT, TravelMode.BUS, TravelMode.TRAIN, TravelMode.CAR)
                            else TripLogic.modeOptions(from.lat, from.lon, from.hubs, from.island, to.lat, to.lon, to.hubs, to.island)
                            if (i > 0 || inThailand) Text(tr("ระยะทางตรงราว ${km.toInt()} กม.", "About ${km.toInt()} km in a straight line"), fontSize = 12.sp, color = Color.Gray)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                options.forEach { m -> FilterChip(selected = leg.mode == m, onClick = { vm.setLeg(i, leg.copy(mode = m)) }, label = { Text(modeLabel(m, thai)) }) }
                            }
                            val date = TripLogic.legDate(trip, i)
                            if (date != null) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("วันเดินทาง: ", "Travel day: ") + fmt(date, thai), fontSize = 13.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.setLeg(i, leg.copy(date = date.minusDays(1).toString())) }) { Text("−1") }
                                TextButton(onClick = { vm.setLeg(i, leg.copy(date = date.plusDays(1).toString())) }) { Text("+1") }
                            }
                            leg.mode?.let { m -> HubHints(m, from, to) }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(tr("หลังเที่ยวที่สุดท้าย คุณจะเดินทางไปที่ใด?", "After the last place, where will you go?"), fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { hubPicker = "end" }, modifier = Modifier.fillMaxWidth()) {
                            Text(trip.endHubId?.let { repo.hubById[it]?.name?.get(thai) } ?: tr("เลือกสนามบิน / สถานีขนส่ง / สถานีรถไฟ", "Choose airport / bus terminal / station"))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    hubPicker?.let { which ->
        val near = if (which == "end") trip.stops.lastOrNull()?.let { repo.placeById[it.placeId] } else null
        val list = if (which == "end") repo.hubs.filter { it.type != HubType.PIER } else repo.hubs
        HubPickerDialog(list, near, onDismiss = { hubPicker = null }) { hub ->
            vm.update { if (which == "end") it.copy(endHubId = hub.id) else it.copy(originHubId = hub.id) }
            hubPicker = null
        }
    }
}

@Composable
private fun HubHints(mode: TravelMode, from: End, to: End) {
    val thai = LocalThai.current
    val type = when (mode) {
        TravelMode.FLIGHT -> HubType.AIRPORT; TravelMode.TRAIN -> HubType.TRAIN
        TravelMode.BUS -> HubType.BUS; TravelMode.BOAT -> HubType.PIER; TravelMode.CAR -> null
    } ?: return
    val a = from.hubs.filter { it.type == type }.take(2)
    val b = to.hubs.filter { it.type == type }.take(2)
    if (a.isEmpty() && b.isEmpty()) return
    Text(
        tr("ขึ้นที่: ", "Board at: ") + (a.joinToString(" / ") { it.name.get(thai) }.ifBlank { "-" }) + "\n" +
            tr("ลงที่: ", "Get off at: ") + (b.joinToString(" / ") { it.name.get(thai) }.ifBlank { "-" }),
        fontSize = 12.sp, color = Color.DarkGray,
    )
}

@Composable
private fun HubPickerDialog(hubs: List<Hub>, near: Place?, onDismiss: () -> Unit, onPick: (Hub) -> Unit) {
    val thai = LocalThai.current
    var q by remember { mutableStateOf("") }
    val sorted = if (near != null) hubs.sortedBy { TripLogic.distanceKm(near.lat, near.lon, it.lat, it.lon) } else hubs.sortedBy { it.province.th }
    val shown = sorted.filter { q.isBlank() || it.name.th.contains(q, true) || it.name.en.contains(q, true) || it.province.th.contains(q, true) || it.province.en.contains(q, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("ปิด", "Close")) } },
        title = { Text(tr("เลือกจุดเดินทาง", "Choose a transport point")) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, label = { Text(tr("ค้นหาจังหวัดหรือชื่อ", "Search province or name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.id }) { h ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(h) }.padding(vertical = 8.dp)) {
                            Text(h.name.get(thai), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("${hubTypeLabel(h.type, thai)} · ${h.province.get(thai)}" + (near?.let { " · ${TripLogic.distanceKm(it.lat, it.lon, h.lat, h.lon).toInt()} km" } ?: ""), fontSize = 12.sp, color = Color.Gray)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
    )
}

// ---------------------------------------------------------------- Summary (steps 3–5)

/** How to check or book one leg: a link where one exists, otherwise who to call. */
data class BookingHelp(val label: String, val url: String?, val phone: String?)

fun bookingHelp(mode: TravelMode, fromName: String, toName: String, date: LocalDate?, fromHub: Hub?, toHub: Hub?, fromLatLon: Pair<Double, Double>, toLatLon: Pair<Double, Double>, thai: Boolean): List<BookingHelp> = when (mode) {
    TravelMode.FLIGHT -> listOf(
        BookingHelp(if (thai) "ดูเที่ยวบินและเวลาถึง (Google Flights)" else "See flights & arrival times (Google Flights)",
            "https://www.google.com/travel/flights?q=" + enc("Flights from $fromName to $toName" + (date?.let { " on $it" } ?: "")), null),
        BookingHelp(if (thai) "ศูนย์บริการข้อมูลท่าอากาศยาน (ทอท.)" else "Airports of Thailand call centre", null, "1722"),
    )
    TravelMode.TRAIN -> listOf(
        BookingHelp(if (thai) "จองตั๋วรถไฟ D-Ticket (การรถไฟฯ)" else "Book train tickets (SRT D-Ticket)", "https://dticket.railway.co.th", null),
        BookingHelp(if (thai) "สายด่วนการรถไฟแห่งประเทศไทย" else "State Railway of Thailand hotline", null, "1690"),
    )
    TravelMode.BUS -> listOf(
        BookingHelp(if (thai) "บริษัท ขนส่ง จำกัด (บขส.)" else "Transport Co., Ltd. (BKS)", "https://www.transport.co.th", "1490"),
        BookingHelp(if (thai) "ค้นหาเที่ยวรถและจองผ่าน 12Go" else "Find and book buses on 12Go", "https://12go.asia", null),
    )
    TravelMode.BOAT -> listOfNotNull(
        (toHub ?: fromHub)?.let { h -> BookingHelp(h.name.get(thai), h.website, h.phone) },
        BookingHelp(if (thai) "ค้นหาเรือข้ามฟากและจองผ่าน 12Go" else "Find and book ferries on 12Go", "https://12go.asia", null),
    )
    TravelMode.CAR -> listOf(
        BookingHelp(if (thai) "เส้นทางขับรถ (Google Maps)" else "Driving route (Google Maps)", mapsDirections(fromLatLon.first, fromLatLon.second, toLatLon.first, toLatLon.second), null),
    )
}

@Composable
fun SummaryScreen(vm: TripViewModel, nav: NavHostController) {
    val thai = LocalThai.current
    val trip = vm.trip
    val repo = vm.repo
    val ctx = LocalContext.current
    val originHub = trip.originHubId?.let { repo.hubById[it] }
    val endHub = trip.endHubId?.let { repo.hubById[it] }
    Page(
        tr("สรุปทริป", "Trip summary"), nav, step = 5,
        bottom = {
            val updating = !trip.activated && trip.checkIns.isNotEmpty()
            NextButton(
                when {
                    trip.activated -> tr("ดูความคืบหน้าทริป", "Open trip progress")
                    updating -> tr("✅ ยืนยันการอัปเดตทริป และ Activate", "✅ Confirm trip update & activate")
                    else -> "🚀 Activate travel plan"
                },
            ) {
                if (!trip.activated) vm.activate()
                nav.navigate(Routes.ACTIVE) { popUpTo(Routes.HOME) }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                TimelineDot("🏁", tr("เริ่มต้น", "Start"), listOfNotNull(trip.originCity.ifBlank { null }, trip.originCountry.ifBlank { null }).joinToString(", ") + (originHub?.let { "\n" + it.name.get(thai) } ?: ""))
            }
            val n = TripLogic.legCount(trip)
            for (i in 0 until n) {
                item(key = "leg$i") {
                    val leg = trip.legs.getOrNull(i) ?: Leg()
                    val fromPlace = trip.stops.getOrNull(i - 1)?.let { repo.placeById[it.placeId] }
                    val toPlace = trip.stops.getOrNull(i)?.let { repo.placeById[it.placeId] }
                    val fromName = fromPlace?.province?.en ?: originHub?.province?.en ?: trip.originCity.ifBlank { "Bangkok" }
                    val toName = toPlace?.province?.en ?: endHub?.province?.en ?: ""
                    val fromLL = fromPlace?.let { it.lat to it.lon } ?: originHub?.let { it.lat to it.lon } ?: (13.75 to 100.5)
                    val toLL = toPlace?.let { it.lat to it.lon } ?: endHub?.let { it.lat to it.lon } ?: fromLL
                    val date = TripLogic.legDate(trip, i)
                    val mode = leg.mode
                    Card(Modifier.fillMaxWidth().padding(start = 24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F7FF))) {
                        Column(Modifier.padding(12.dp)) {
                            Text((mode?.let { modeLabel(it, thai) } ?: tr("ยังไม่เลือกวิธีเดินทาง", "No transport chosen")) + (date?.let { " · " + fmt(it, thai) } ?: ""), fontWeight = FontWeight.SemiBold)
                            if (mode != null) bookingHelp(mode, fromName, toName, date, if (i == 0) originHub else null, if (i == n - 1) endHub else null, fromLL, toLL, thai).forEach { h ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("• ${h.label}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    if (h.url != null) TextButton(onClick = { open(ctx, h.url) }) { Text(tr("เปิด", "Open")) }
                                    if (h.phone != null) TextButton(onClick = { dial(ctx, h.phone) }) { Text("📞 ${h.phone}") }
                                }
                            }
                        }
                    }
                }
                if (i < trip.stops.size) item(key = "stop$i") {
                    val s = trip.stops[i]
                    val p = repo.placeById[s.placeId]
                    if (p != null) {
                        val d = TripLogic.dates(s)
                        val lodging = trip.lodgings[p.id]
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("📍 ${i + 1}. ${p.name.get(thai)}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(fmtDates(d, thai), fontSize = 13.sp, color = Color.Gray)
                                if (!lodging?.name.isNullOrBlank()) Text("🏨 ${lodging!!.name}" + (if (lodging.note.isNotBlank()) " · ${lodging.note}" else ""), fontSize = 13.sp)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { nav.navigate(Routes.nearby(i, "food")) }) { Text(tr("🍜 ร้านอาหาร/กาแฟ", "🍜 Food & cafés")) }
                                    OutlinedButton(onClick = { nav.navigate(Routes.nearby(i, "hospital")) }) { Text(tr("🏥 โรงพยาบาล", "🏥 Hospitals")) }
                                    OutlinedButton(onClick = { nav.navigate(Routes.nearby(i, "police")) }) { Text(tr("👮 สถานีตำรวจ", "👮 Police")) }
                                }
                            }
                        }
                        // Gap days after this stop.
                        val gaps = TripLogic.gaps(trip.stops).filter { it.afterStop == i }
                        gaps.forEach { g ->
                            val purpose = trip.gapPurposes[g.dates.first().toString()]
                            Text("☕ ${fmtDates(g.dates, thai)}: ${purpose?.let { purposeLabel(it, thai) } ?: tr("ว่าง", "Free")}", fontSize = 13.sp, color = Color.DarkGray, modifier = Modifier.padding(start = 24.dp, top = 4.dp))
                        }
                    }
                }
            }
            item {
                TimelineDot("🏁", tr("จุดหมายสุดท้าย", "Final destination"), endHub?.name?.get(thai) ?: "-")
                VSpace(12)
                EmergencyCard()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TimelineDot(icon: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(icon, fontSize = 22.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            if (body.isNotBlank()) Text(body, fontSize = 13.sp, color = Color.DarkGray)
        }
    }
}

@Composable
fun EmergencyCard() {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEDEA)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("เบอร์ฉุกเฉิน", "Emergency numbers"), fontWeight = FontWeight.Bold)
            listOf(
                Triple("1669", "เจ็บป่วยฉุกเฉิน", "Medical emergency"),
                Triple("191", "แจ้งเหตุตำรวจ", "Police"),
                Triple("1155", "ตำรวจท่องเที่ยว (พูดภาษาอังกฤษได้)", "Tourist Police (English)"),
                Triple("199", "ดับเพลิง", "Fire"),
                Triple("1784", "สายด่วนนิรภัย ปภ. (ภัยพิบัติ)", "Disaster (DDPM)"),
            ).forEach { (num, th, en) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(th, en), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { dial(ctx, num) }) { Text("📞 $num") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Steps 4–5: food, hospitals, police

@Composable
fun NearbyScreen(vm: TripViewModel, nav: NavHostController, stopIndex: Int, kind: String) {
    val thai = LocalThai.current
    val p = vm.trip.stops.getOrNull(stopIndex)?.let { vm.repo.placeById[it.placeId] } ?: return
    val ctx = LocalContext.current
    val tabs = when (kind) {
        "food" -> listOf("restaurant" to tr("🍜 ร้านอาหาร", "🍜 Restaurants"), "cafe" to tr("☕ ร้านกาแฟ", "☕ Cafés"))
        "hospital" -> listOf("hospital" to tr("🏥 โรงพยาบาล", "🏥 Hospitals"))
        else -> listOf("police" to tr("👮 สถานีตำรวจ", "👮 Police stations"))
    }
    var tab by remember(kind) { mutableStateOf(0) }
    val (type, _) = tabs[tab.coerceIn(tabs.indices)]
    Page(p.name.get(thai), nav) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            if (tabs.size > 1) TabRow(selectedTabIndex = tab) { tabs.forEachIndexed { i, (_, label) -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) } }
            if (kind != "food") { VSpace(); EmergencyCard() }
            VSpace()
            OutlinedButton(onClick = {
                val q = when (type) { "restaurant" -> "restaurants"; "cafe" -> "cafes"; "hospital" -> "hospital"; else -> "police station" }
                open(ctx, mapsSearch(q, p.lat, p.lon))
            }, modifier = Modifier.fillMaxWidth()) { Text(tr("เปิดใน Google Maps", "Open in Google Maps")) }
            if (vm.repo.placesKey.isBlank()) {
                Text(
                    tr("ใส่ Google Places API key ในหน้าตั้งค่า เพื่อแสดงรายการพร้อมรูป คะแนน และเบอร์โทรในแอป", "Add a Google Places API key in Settings to list places with photos, ratings and phone numbers here."),
                    fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                PoiList(vm, p.lat, p.lon, type, byDistance = kind != "food", modifier = Modifier.weight(1f), onPick = null)
            }
        }
    }
}

@Composable
fun PoiList(vm: TripViewModel, lat: Double, lon: Double, type: String, byDistance: Boolean, modifier: Modifier = Modifier, onPick: ((Poi) -> Unit)?) {
    val thai = LocalThai.current
    val ctx = LocalContext.current
    val result = produceState<Result<List<Poi>>?>(null, lat, lon, type) {
        value = vm.remote.nearby(lat, lon, type, vm.repo.placesKey, thai, byDistance)
    }.value
    when {
        result == null -> CircularProgressIndicator(Modifier.padding(24.dp))
        result.isFailure -> Text(tr("โหลดรายการไม่ได้: ", "Couldn't load: ") + (result.exceptionOrNull()?.message ?: ""), color = Coral, fontSize = 13.sp)
        else -> LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(result.getOrThrow()) { poi ->
                Card(Modifier.fillMaxWidth().clickable { if (onPick != null) onPick(poi) else poi.mapsUri?.let { open(ctx, it) } }) {
                    Row(Modifier.padding(10.dp)) {
                        if (poi.photoUrl != null) Column(Modifier.width(96.dp)) {
                            AsyncImage(poi.photoUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)))
                            poi.photoCredit?.let { Text("📷 $it", fontSize = 9.sp, color = Color.Gray, maxLines = 1) }
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(poi.name, fontWeight = FontWeight.SemiBold)
                            val meta = listOfNotNull(
                                poi.rating?.let { "⭐ $it" + (poi.ratingCount?.let { c -> " ($c)" } ?: "") },
                                poi.distanceKm?.let { String.format(java.util.Locale.US, "%.1f km", it) },
                                poi.openNow?.let { if (it) tr("เปิดอยู่", "Open now") else tr("ปิดอยู่", "Closed") },
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, fontSize = 12.sp, color = Color.DarkGray)
                            Text(poi.address, fontSize = 12.sp, color = Color.Gray, maxLines = 2)
                            poi.phone?.let { ph -> TextButton(onClick = { dial(ctx, ph) }) { Text("📞 $ph") } }
                        }
                    }
                }
            }
            item { Text(tr("ข้อมูลจาก Google", "Data from Google"), fontSize = 11.sp, color = Color.Gray) }
        }
    }
}
