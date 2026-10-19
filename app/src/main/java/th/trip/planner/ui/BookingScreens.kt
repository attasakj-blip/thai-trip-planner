@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package th.trip.planner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import th.trip.planner.logic.Health
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
                    tr("กด Booking.com หรือ Agoda เพื่อดูราคาและจอง เมื่อกลับมาที่แอป แอปจะถามและบันทึกข้อมูลที่พักไว้กับสถานที่นั้นให้ ถ้าจองไว้ทางอื่นแล้วก็กรอกข้อมูลเองได้", "Tap Booking.com or Agoda to see prices and book. When you come back, the app asks for the stay details and saves them under that place. Booked another way? Enter the details yourself."),
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
                                vm.startBooking(p.id, "booking.com")
                                open(ctx, "https://www.booking.com/searchresults.html?ss=${enc(city)}" + (if (checkIn != null) "&checkin=$checkIn&checkout=$checkOut" else ""))
                            }) { Text("Booking.com") }
                            OutlinedButton(onClick = {
                                vm.startBooking(p.id, "agoda")
                                open(ctx, "https://www.agoda.com/search?textToSearch=${enc(city)}" + (if (checkIn != null) "&checkIn=$checkIn&checkOut=$checkOut" else ""))
                            }) { Text("Agoda") }
                            OutlinedButton(onClick = { open(ctx, mapsSearch("hotels", p.lat, p.lon)) }) { Text("Google Maps") }
                            OutlinedButton(onClick = { picking = p }) { Text(tr("ดูรายการที่พักใกล้เคียง", "Browse nearby stays")) }
                        }
                        VSpace()
                        if (lodging.isEmpty) {
                            Text(tr("ยังไม่ได้บันทึกที่พัก", "No stay saved yet"), fontSize = 13.sp, color = Color.Gray)
                        } else {
                            StaySummary(lodging)
                        }
                        VSpace(4)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.Button(onClick = { vm.stayForm = TripViewModel.StayForm(p.id, lodging, afterBooking = false) }) {
                                Text(if (lodging.isEmpty) tr("✏️ กรอกข้อมูลที่พักเอง", "✏️ Enter my own stay") else tr("✏️ แก้ไขข้อมูลที่พัก", "✏️ Edit stay details"))
                            }
                            if (!lodging.isEmpty) TextButton(onClick = { vm.setLodging(p.id, Lodging()) }) { Text(tr("ลบ", "Remove")) }
                        }
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
                PoiList(vm, p.lat, p.lon, "lodging", modifier = Modifier.heightIn(max = 480.dp)) { poi ->
                    vm.setLodging(p.id, Lodging(name = poi.name, url = poi.website ?: mapsPoint(poi), phone = poi.phone ?: "", address = poi.address, source = "osm", lat = poi.lat, lon = poi.lon))
                    picking = null
                }
            },
        )
    }
}

fun sourceLabel(source: String) = when (source) {
    "booking.com" -> "Booking.com"
    "agoda" -> "Agoda"
    "osm" -> "OpenStreetMap"
    else -> ""
}

/** The saved stay: name, address (opens the map), phone (dials), booking number. */
@Composable
fun StaySummary(l: Lodging) {
    val ctx = LocalContext.current
    Column {
        if (l.name.isNotBlank()) Text("🏨 ${l.name}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        if (l.address.isNotBlank()) Text("📍 ${l.address}", fontSize = 13.sp, color = Teal,
            modifier = Modifier.clickable { open(ctx, "https://www.google.com/maps/search/?api=1&query=${enc(listOf(l.name, l.address).filter { it.isNotBlank() }.joinToString(", "))}") })
        if (l.phone.isNotBlank()) Text("📞 ${l.phone}", fontSize = 13.sp, color = Teal, modifier = Modifier.clickable { dial(ctx, l.phone) })
        if (l.bookingRef.isNotBlank()) Text(tr("🔖 หมายเลขการจอง: ${l.bookingRef}", "🔖 Booking number: ${l.bookingRef}"), fontSize = 13.sp)
        if (l.note.isNotBlank()) Text("📝 ${l.note}", fontSize = 13.sp, color = Color.DarkGray)
        val src = sourceLabel(l.source)
        if (src.isNotBlank()) Text(tr("จองผ่าน $src", "Booked via $src"), fontSize = 12.sp, color = Color.Gray)
        if (l.url.startsWith("http")) TextButton(onClick = { open(ctx, l.url) }, contentPadding = PaddingValues(0.dp)) { Text(tr("เปิดลิงก์ที่พัก", "Open stay link")) }
    }
}

/**
 * Form for the user's own stay details. Shown from the stay step, when the user comes back
 * from Booking.com / Agoda, or when a booking confirmation is shared into the app.
 */
@Composable
fun StayFormDialog(vm: TripViewModel) {
    val form = vm.stayForm ?: return
    val thai = LocalThai.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var placeId by remember(form) { mutableStateOf(form.placeId) }
    var l by remember(form) { mutableStateOf(form.prefill) }
    var pasteMsg by remember(form) { mutableStateOf<String?>(null) }
    val place = vm.repo.placeById[placeId]
    val src = sourceLabel(l.source)
    AlertDialog(
        onDismissRequest = { },
        confirmButton = {
            androidx.compose.material3.Button(onClick = { vm.closeStayForm(placeId, l) }) { Text(tr("บันทึก", "Save")) }
        },
        dismissButton = {
            TextButton(onClick = { vm.closeStayForm(null, null) }) {
                Text(if (form.afterBooking) tr("ยังไม่ได้จอง", "Not booked") else tr("ยกเลิก", "Cancel"))
            }
        },
        title = { Text(if (form.afterBooking && src.isNotBlank()) tr("จองที่พักผ่าน $src สำเร็จไหม?", "Did your $src booking go through?") else tr("ข้อมูลที่พัก", "Stay details")) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (form.afterBooking) Text(
                    tr("ถ้าจองแล้ว ใส่ข้อมูลที่พักไว้ที่นี่ แอปจะบันทึกเป็นที่พักของ ${place?.name?.th ?: ""}",
                        "If you booked, add the stay here and it will be saved for ${place?.name?.en ?: ""}."),
                    fontSize = 13.sp,
                )
                if (vm.trip.stops.size > 1) {
                    Text(tr("ที่พักสำหรับ", "Stay for"), fontSize = 12.sp, color = Color.Gray)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        vm.trip.stops.forEach { s ->
                            val stopPlace = vm.repo.placeById[s.placeId] ?: return@forEach
                            FilterChip(selected = s.placeId == placeId, onClick = { placeId = s.placeId }, label = { Text(stopPlace.name.get(thai), fontSize = 12.sp) })
                        }
                    }
                } else if (place != null) Text(tr("ที่พักสำหรับ ${place.name.th}", "Stay for ${place.name.en}"), fontSize = 12.sp, color = Color.Gray)
                OutlinedButton(onClick = {
                    val text = clipboard.getText()?.text.orEmpty()
                    if (text.isBlank()) pasteMsg = tr("ยังไม่ได้คัดลอกข้อความ", "Nothing copied yet")
                    else {
                        val filled = vm.fillFrom(l, text)
                        pasteMsg = if (filled == l) tr("ไม่พบข้อมูลที่พักในข้อความ กรอกเองได้เลย", "No stay details found in the text; type them in below.") else tr("เติมข้อมูลจากข้อความแล้ว ตรวจดูอีกครั้ง", "Filled in from the text; please check it.")
                        l = filled
                    }
                }) { Text(tr("📋 วางข้อความยืนยันการจอง", "📋 Paste booking confirmation")) }
                Text(
                    tr("คัดลอกข้อความหรือลิงก์จากอีเมลหรือแอปที่จองไว้แล้วกดปุ่มนี้ หรือกด \"แชร์\" ในแอปจองแล้วเลือก Thai Trip Planner",
                        "Copy the text or link from the confirmation email or booking app and tap this, or tap \"Share\" in the booking app and pick Thai Trip Planner."),
                    fontSize = 12.sp, color = Color.Gray,
                )
                pasteMsg?.let { Text(it, fontSize = 12.sp, color = Coral) }
                OutlinedTextField(l.name, { l = l.copy(name = it) }, label = { Text(tr("ชื่อที่พัก", "Stay name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(l.address, { l = l.copy(address = it) }, label = { Text(tr("ที่ตั้ง / ที่อยู่", "Location / address")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(l.phone, { l = l.copy(phone = it) }, label = { Text(tr("เบอร์โทรติดต่อ", "Contact phone")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone))
                OutlinedTextField(l.bookingRef, { l = l.copy(bookingRef = it) }, label = { Text(tr("หมายเลขการจอง / อ้างอิง", "Booking / reference number")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(l.note, { l = l.copy(note = it) }, label = { Text(tr("หมายเหตุ", "Note")) }, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

// ---------------------------------------------------------------- Step 3: transport

@Composable
fun TransportScreen(vm: TripViewModel, nav: NavHostController) {
    val thai = LocalThai.current
    val trip = vm.trip
    val repo = vm.repo
    LaunchedEffect(Unit) { vm.ensureLegs() }
    val n = TripLogic.legCount(trip)
    val ready = trip.legs.size == n && trip.legs.all { it.mode != null }
    Page(
        tr("3. วางแผนการเดินทาง", "3. Plan your transport"), nav, step = 3,
        bottom = { NextButton(tr("สรุปแผนการเดินทาง", "Summarise the plan"), enabled = ready) { nav.navigate(Routes.SUMMARY) } },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    if (n == 0) tr("ทริปนี้มีสถานที่เดียว จึงไม่มีช่วงเดินทางระหว่างสถานที่", "This trip has one place, so there is no travel between places.")
                    else tr("เลือกทางเลือกในการเดินทางของแต่ละช่วง แอปรวมเส้นทางที่ต่อกันได้จริงจากตารางเวลารถ รถไฟ เรือ และเครื่องบิน พร้อมรอบเวลา จุดขึ้น-ลง และวิธีไปสถานี",
                        "Pick a way to travel each leg. The app joins real timetables of buses, trains, boats and flights, with departure times, where to board and how to reach the stations."),
                    fontSize = 14.sp, color = Color.DarkGray,
                )
            }
            items((0 until n).toList()) { i ->
                val from = repo.placeById[trip.stops[i].placeId]
                val to = repo.placeById[trip.stops[i + 1].placeId]
                val leg = trip.legs.getOrNull(i) ?: Leg()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(tr("ช่วงที่ ${i + 1}", "Leg ${i + 1}"), fontSize = 12.sp, color = Teal)
                        Text("${from?.name?.get(thai) ?: "?"} → ${to?.name?.get(thai) ?: "?"}", fontWeight = FontWeight.Bold)
                        if (from != null && to != null) {
                            val km = TripLogic.distanceKm(from.lat, from.lon, to.lat, to.lon)
                            Text(tr("ระยะทางตรงราว ${km.toInt()} กม.", "About ${km.toInt()} km in a straight line"), fontSize = 12.sp, color = Color.Gray)
                            val date = TripLogic.legDate(trip, i)
                            if (date != null) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("วันเดินทาง: ", "Travel day: ") + fmt(date, thai), fontSize = 13.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.setLeg(i, leg.copy(date = date.minusDays(1).toString())) }) { Text("−1") }
                                TextButton(onClick = { vm.setLeg(i, leg.copy(date = date.plusDays(1).toString())) }) { Text("+1") }
                            }
                            TravelOptions(vm, leg, { vm.setLeg(i, it) }, from, to, trip.lodgings[from.id], trip.lodgings[to.id], date)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------------------------------------------------------------- Summary (steps 3–5)

/** How to check or book one leg: a link where one exists, otherwise who to call. */
data class BookingHelp(val label: String, val url: String?, val phone: String?)

fun bookingHelp(mode: TravelMode, fromName: String, toName: String, date: LocalDate?, fromHub: Hub?, toHub: Hub?, fromLatLon: Pair<Double, Double>, toLatLon: Pair<Double, Double>, thai: Boolean): List<BookingHelp> = when (mode) {
    TravelMode.FLIGHT -> listOf(
        BookingHelp(tr("ดูเที่ยวบินและเวลาถึง (Google Flights)", "See flights & arrival times (Google Flights)"),
            "https://www.google.com/travel/flights?q=" + enc("Flights from $fromName to $toName" + (date?.let { " on $it" } ?: "")), null),
        BookingHelp(tr("ศูนย์บริการข้อมูลท่าอากาศยาน (ทอท.)", "Airports of Thailand call centre"), null, "1722"),
    )
    TravelMode.TRAIN -> listOf(
        BookingHelp(tr("จองตั๋วรถไฟ D-Ticket (การรถไฟฯ)", "Book train tickets (SRT D-Ticket)"), "https://dticket.railway.co.th", null),
        BookingHelp(tr("สายด่วนการรถไฟแห่งประเทศไทย", "State Railway of Thailand hotline"), null, "1690"),
    )
    TravelMode.BUS -> listOf(
        BookingHelp(tr("บริษัท ขนส่ง จำกัด (บขส.)", "Transport Co., Ltd. (BKS)"), "https://www.transport.co.th", "1490"),
        BookingHelp(tr("ค้นหาเที่ยวรถและจองผ่าน 12Go", "Find and book buses on 12Go"), "https://12go.asia", null),
    )
    TravelMode.BOAT -> listOfNotNull(
        (toHub ?: fromHub)?.let { h -> BookingHelp(h.name.get(thai), h.website, h.phone) },
        BookingHelp(tr("ค้นหาเรือข้ามฟากและจองผ่าน 12Go", "Find and book ferries on 12Go"), "https://12go.asia", null),
    )
    TravelMode.CAR -> listOf(
        BookingHelp(tr("เส้นทางขับรถ (Google Maps)", "Driving route (Google Maps)"), mapsDirections(fromLatLon.first, fromLatLon.second, toLatLon.first, toLatLon.second), null),
    )
    TravelMode.VAN -> listOf(
        BookingHelp(tr("ค้นหารอบรถตู้และจองผ่าน 12Go", "Find and book minivans on 12Go"), "https://12go.asia", null),
        BookingHelp(tr("บริษัท ขนส่ง จำกัด (บขส.)", "Transport Co., Ltd. (BKS)"), "https://www.transport.co.th", "1490"),
    )
}

@Composable
fun SummaryScreen(vm: TripViewModel, nav: NavHostController) {
    val thai = LocalThai.current
    val trip = vm.trip
    val repo = vm.repo
    val ctx = LocalContext.current
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
            for (i in trip.stops.indices) {
                if (i > 0) item(key = "leg${i - 1}") {
                    val leg = trip.legs.getOrNull(i - 1) ?: Leg()
                    val fromPlace = repo.placeById[trip.stops[i - 1].placeId]
                    val toPlace = repo.placeById[trip.stops[i].placeId]
                    val fromName = fromPlace?.province?.en ?: ""
                    val toName = toPlace?.province?.en ?: ""
                    val fromLL = fromPlace?.let { it.lat to it.lon } ?: (13.75 to 100.5)
                    val toLL = toPlace?.let { it.lat to it.lon } ?: fromLL
                    val date = TripLogic.legDate(trip, i - 1)
                    val mode = leg.mode
                    Card(Modifier.fillMaxWidth().padding(start = 24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F7FF))) {
                        Column(Modifier.padding(12.dp)) {
                            Text((mode?.let { modeLabel(it, thai) } ?: tr("ยังไม่เลือกวิธีเดินทาง", "No transport chosen")) + (date?.let { " · " + fmt(it, thai) } ?: ""), fontWeight = FontWeight.SemiBold)
                            if (leg.plan != null && fromPlace != null && toPlace != null) ChosenPlanSummary(vm, leg, fromPlace, toPlace, trip.lodgings[fromPlace.id], trip.lodgings[toPlace.id], date)
                            else if (mode != null && fromPlace != null && toPlace != null) LegDetails(vm, mode, fromPlace, toPlace, trip.lodgings[fromPlace.id], trip.lodgings[toPlace.id], date)
                            if (leg.plan == null && mode != null) bookingHelp(mode, fromName, toName, date, fromPlace?.let { TripLogic.hubsFor(it, repo.hubs).firstOrNull { h -> h.type == HubType.PIER } }, toPlace?.let { TripLogic.hubsFor(it, repo.hubs).firstOrNull { h -> h.type == HubType.PIER } }, fromLL, toLL, thai).forEach { h ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("• ${h.label}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    if (h.url != null) TextButton(onClick = { open(ctx, h.url) }) { Text(tr("เปิด", "Open")) }
                                    if (h.phone != null) TextButton(onClick = { dial(ctx, h.phone) }) { Text("📞 ${h.phone}") }
                                }
                            }
                        }
                    }
                }
                item(key = "stop$i") {
                    val s = trip.stops[i]
                    val p = repo.placeById[s.placeId]
                    if (p != null) {
                        val d = TripLogic.dates(s)
                        val lodging = trip.lodgings[p.id]
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("📍 ${i + 1}. ${p.name.get(thai)}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(fmtDates(d, thai), fontSize = 13.sp, color = Color.Gray)
                                if (lodging != null && !lodging.isEmpty) StaySummary(lodging)
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
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

fun findPhoneUrl(name: String) = "https://www.google.com/search?q=" + enc("$name เบอร์โทร")
fun streetView(lat: Double, lon: Double) = "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=$lat,$lon"

/**
 * Emergency numbers. With a location it also lists the nearest police station, hospital with
 * an emergency room and fire station, whose own phones usually get help faster than the hotlines.
 */
@Composable
fun EmergencyCard(vm: TripViewModel? = null, lat: Double? = null, lon: Double? = null, placeName: String? = null, placeId: String? = null) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEDEA)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (vm != null && lat != null && lon != null) {
                LocalEmergency(vm, lat, lon, placeName, placeId)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(tr("สายด่วนทั่วประเทศ", "National hotlines"), fontWeight = FontWeight.Bold)
            } else 
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
        "fire" -> listOf("fire" to tr("🚒 สถานีดับเพลิง", "🚒 Fire stations"))
        else -> listOf("police" to tr("👮 สถานีตำรวจ", "👮 Police stations"))
    }
    var tab by remember(kind) { mutableStateOf(0) }
    val (type, _) = tabs[tab.coerceIn(tabs.indices)]
    Page(p.name.get(thai), nav) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            if (tabs.size > 1) TabRow(selectedTabIndex = tab) { tabs.forEachIndexed { i, (_, label) -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) } }
            if (kind != "food") { VSpace(); HotlineCard(kind) }
            VSpace()
            OutlinedButton(onClick = {
                val q = when (type) { "restaurant" -> "restaurants"; "cafe" -> "cafes"; "hospital" -> "hospital"; "fire" -> "fire station"; else -> "police station" }
                open(ctx, mapsSearch(q, p.lat, p.lon))
            }, modifier = Modifier.fillMaxWidth()) { Text(tr("เปิดใน Google Maps", "Open in Google Maps")) }
            PoiList(vm, p.lat, p.lon, type, modifier = Modifier.weight(1f), onPick = null, placeId = p.id)
        }
    }
}

/** National hotline(s) for one kind of emergency service, so each screen shows only its own. */
fun hotlines(kind: String): List<Triple<String, String, String>> = when (kind) {
    "hospital" -> listOf(Triple("1669", "เจ็บป่วยฉุกเฉิน", "Medical emergency"))
    "fire" -> listOf(Triple("199", "ดับเพลิง", "Fire"), Triple("1784", "สายด่วนนิรภัย ปภ. (ภัยพิบัติ)", "Disaster (DDPM)"))
    else -> listOf(Triple("191", "แจ้งเหตุตำรวจ", "Police"), Triple("1155", "ตำรวจท่องเที่ยว (พูดภาษาอังกฤษได้)", "Tourist Police (English)"))
}

@Composable
fun HotlineCard(kind: String) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEDEA)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            hotlines(kind).forEach { (num, th, en) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("สายด่วน", "Hotline") + " · " + tr(th, en), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { dial(ctx, num) }) { Text("📞 $num") }
                }
            }
        }
    }
}

/** What each highlight key means, in the menu language. */
@Composable
fun highlightLabel(key: String, brand: String?): String? = when (key) {
    "late" -> tr("🌙 เปิดดึก", "🌙 Open late")
    "early" -> tr("🌅 เปิดเช้า", "🌅 Opens early")
    "veg" -> tr("🥦 มีเมนูมังสวิรัติ/เจ", "🥦 Vegetarian options")
    "halal" -> tr("☪️ ฮาลาล", "☪️ Halal")
    "view" -> tr("🏞️ วิวสวย/ริมน้ำ", "🏞️ Views or waterside")
    "garden" -> tr("🌳 บรรยากาศสวน", "🌳 Garden setting")
    "buffet" -> tr("🍽️ บุฟเฟ่ต์", "🍽️ Buffet")
    "outdoor" -> tr("☀️ มีที่นั่งกลางแจ้ง", "☀️ Outdoor seating")
    "aircon" -> tr("❄️ ห้องแอร์", "❄️ Air-conditioned")
    "wifi" -> tr("📶 มี Wi-Fi", "📶 Wi-Fi")
    "delivery" -> tr("🛵 มีบริการส่ง", "🛵 Delivery")
    "takeaway" -> tr("🥡 สั่งกลับบ้านได้", "🥡 Takeaway")
    "wheelchair" -> tr("♿ รถเข็นเข้าได้", "♿ Wheelchair access")
    "chain" -> if (brand != null) tr("🏷️ ร้านเครือ $brand", "🏷️ Part of $brand") else null
    else -> null
}

/** An example photo of the kind of food or drink served, clearly marked as an example. */
@Composable
fun DishPhoto(poi: Poi) {
    val ctx = LocalContext.current
    val url = poi.dishPhoto ?: return
    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp))) {
        AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Text(
            tr("ภาพตัวอย่าง: ${poi.dishName ?: ""}", "Example: ${poi.dishName ?: ""}"), fontSize = 11.sp, color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    if (poi.dishPage != null) Text(tr("ภาพตัวอย่างเมนูประเภทนี้ ไม่ใช่ภาพของร้าน · Wikimedia Commons", "Example of this kind of food, not of this place · Wikimedia Commons"),
        fontSize = 10.sp, color = Color.Gray, modifier = Modifier.clickable { open(ctx, poi.dishPage) })
}

/** Photo of the place under its name, with the Wikimedia Commons credit when there is one. */
@Composable
fun PoiPhoto(poi: Poi) {
    val ctx = LocalContext.current
    val url = poi.logo ?: return
    // Logos (svg/png renders) look better whole; photos fill the frame.
    val isLogo = Regex("(?i)logo|seal|emblem|ตรา|โลโก้").containsMatchIn(url)
    AsyncImage(url, null, contentScale = if (isLogo) ContentScale.Fit else ContentScale.Crop,
        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth().height(if (isLogo) 72.dp else 160.dp).clip(RoundedCornerShape(8.dp)))
    if (poi.photoPage != null) Text(tr("ภาพจาก Wikimedia Commons", "Photo: Wikimedia Commons"), fontSize = 10.sp, color = Color.Gray,
        modifier = Modifier.clickable { open(ctx, poi.photoPage) })
}

/** Google Maps link to an exact spot, labelled with the place name. */
fun mapsPoint(poi: Poi) = "https://www.google.com/maps/search/?api=1&query=" + enc("${poi.name} ${poi.lat},${poi.lon}")

@Composable
fun PoiList(vm: TripViewModel, lat: Double, lon: Double, type: String, modifier: Modifier = Modifier, placeId: String? = null, onPick: ((Poi) -> Unit)?) {
    val thai = LocalThai.current
    val ctx = LocalContext.current
    var attempt by remember { mutableStateOf(0) }
    var showAll by remember(type) { mutableStateOf(false) }
    val emergencyType = type == "hospital" || type == "police" || type == "fire"
    val result = produceState<Result<List<Poi>>?>(null, lat, lon, type, attempt, placeId) {
        value = null
        value = if (type == "lodging") vm.remote.nearby(lat, lon, type, thai)
        else vm.remote.poisFor(placeId, lat, lon, type, thai).map { list ->
            when (type) {
                // Proper hospitals first (nearest first), then health centres and clinics.
                "hospital" -> list.sortedWith(compareBy<Poi>({ it.health != Health.Kind.HOSPITAL }, { it.distanceKm }))
                "police" -> list.sortedWith(compareBy<Poi>({ !vm.remote.isPoliceStation(it.name) }, { it.distanceKm }))
                else -> list
            }
        }
    }.value
    when {
        result == null -> Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(tr("กำลังค้นหาจากแผนที่ อาจใช้เวลาสักครู่", "Searching the map, this can take a moment"), fontSize = 13.sp, color = Color.Gray)
        }
        result.isFailure -> Column {
            Text(tr("โหลดรายการไม่ได้ ตรวจสอบอินเทอร์เน็ตแล้วลองใหม่", "Couldn't load the list. Check your internet and try again."), color = Coral, fontSize = 13.sp)
            TextButton(onClick = { attempt++ }) { Text(tr("ลองใหม่", "Try again")) }
        }
        result.getOrThrow().isEmpty() -> Text(tr("ยังไม่พบข้อมูลในแผนที่ OpenStreetMap ลองเปิดใน Google Maps แทน", "Nothing found on OpenStreetMap here yet; try Google Maps instead."), fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
        else -> LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            val all = result.getOrThrow()
            // Hospitals: the 5 best first, the rest (health centres, clinics) on request.
            val shown = if (type == "hospital" && !showAll) {
                val main = all.filter { it.health == Health.Kind.HOSPITAL }.take(5)
                if (main.size >= 3) main else all.take(5)
            } else all
            items(shown) { poi ->
                Card(Modifier.fillMaxWidth().clickable { if (onPick != null) onPick(poi) else open(ctx, mapsPoint(poi)) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(poi.name, fontWeight = FontWeight.SemiBold)
                        PoiPhoto(poi)
                        if (poi.logo == null) DishPhoto(poi)
                        if (poi.highlights.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                            poi.highlights.forEach { h -> highlightLabel(h, poi.brand)?.let { Text(it, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFFFF3E0)).padding(horizontal = 8.dp, vertical = 2.dp)) } }
                        }
                        val kindLabel = when (poi.health) {
                            Health.Kind.HOSPITAL -> when (poi.owner) {
                                Health.Owner.PUBLIC -> tr("🏥 โรงพยาบาลรัฐ", "🏥 Public hospital")
                                Health.Owner.PRIVATE -> tr("🏥 โรงพยาบาลเอกชน", "🏥 Private hospital")
                                null -> tr("🏥 โรงพยาบาล", "🏥 Hospital")
                            }
                            Health.Kind.HEALTH_CENTRE -> tr("⛑️ สถานีอนามัย / รพ.สต.", "⛑️ Health centre")
                            Health.Kind.CLINIC -> tr("🩺 คลินิก", "🩺 Clinic")
                            null -> null
                        }
                        val meta = listOfNotNull(
                            String.format(java.util.Locale.US, "%.1f km", poi.distanceKm),
                            kindLabel,
                            poi.beds?.let { tr("$it เตียง", "$it beds") },
                            poi.cuisine,
                            if (poi.emergency) tr("🚑 มีห้องฉุกเฉิน", "🚑 Emergency room") else null,
                        ).joinToString(" · ")
                        Text(meta, fontSize = 12.sp, color = Color.DarkGray)
                        poi.hours?.let { Text("🕒 $it", fontSize = 12.sp, color = Color.Gray, maxLines = 2) }
                        if (poi.address.isNotBlank()) Text(poi.address, fontSize = 12.sp, color = Color.Gray, maxLines = 2)
                        poi.phone?.let { ph ->
                            Text("📞 $ph", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0),
                                modifier = Modifier.padding(top = 4.dp).clickable { dial(ctx, ph) })
                        }
                        if (poi.phone == null && emergencyType) {
                            val num = hotlines(type).first().first
                            Text(tr("📞 ไม่มีเบอร์ตรงในข้อมูลเปิด กดโทรสายด่วน $num", "📞 No direct number in open data; tap to call hotline $num"),
                                fontSize = 13.sp, color = Color(0xFF1565C0), modifier = Modifier.padding(top = 4.dp).clickable { dial(ctx, num) })
                        }
                        FlowRow {
                            if (poi.phone == null && emergencyType) TextButton(onClick = { open(ctx, findPhoneUrl(poi.name)) }) { Text(tr("🔎 ค้นเบอร์เพิ่มเติม", "🔎 Search number"), fontSize = 12.sp) }
                            poi.website?.let { w -> TextButton(onClick = { open(ctx, w) }) { Text(tr("🌐 เว็บไซต์", "🌐 Website")) } }
                            TextButton(onClick = { open(ctx, "https://www.google.com/maps/dir/?api=1&destination=${poi.lat},${poi.lon}") }) { Text(tr("🧭 นำทาง", "🧭 Directions")) }
                        }
                        if (type == "restaurant" || type == "cafe") {
                            Text(tr("⭐ ดูคะแนนรีวิว:", "⭐ See review scores:"), fontSize = 12.sp, color = Color.DarkGray)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistChip(onClick = { open(ctx, mapsPoint(poi)) }, label = { Text("Google", fontSize = 12.sp) })
                                AssistChip(onClick = { open(ctx, "https://www.wongnai.com/search?q=" + enc(poi.name)) }, label = { Text("Wongnai", fontSize = 12.sp) })
                                AssistChip(onClick = { open(ctx, "https://www.tripadvisor.com/Search?q=" + enc(poi.name)) }, label = { Text("Tripadvisor", fontSize = 12.sp) })
                            }
                        }
                    }
                }
            }
            item {
                if (shown.size < all.size) TextButton(onClick = { showAll = true }) { Text(tr("ดูเพิ่ม: สถานีอนามัยและคลินิก (${all.size - shown.size})", "More: health centres and clinics (${all.size - shown.size})")) }
                Text(tr("ข้อมูล © ผู้ร่วมพัฒนา OpenStreetMap และ Overture Maps Foundation", "Data © OpenStreetMap contributors and Overture Maps Foundation"), fontSize = 11.sp, color = Color.Gray)
                if (type == "hospital") Text(tr("แสดงเฉพาะโรงพยาบาล สถานีอนามัย และคลินิกที่มีอาคารบนแผนที่ ไม่รวมร้านนวด สปา คลินิกความงามและทันตกรรม โลโก้จาก Wikimedia Commons", "Only hospitals, health centres and clinics with a mapped building; massage, spa, beauty and dental shops are left out. Logos from Wikimedia Commons."), fontSize = 11.sp, color = Color.Gray)
                if (type == "restaurant" || type == "cafe") Text(tr("คะแนนรีวิวดูได้จากปุ่ม Google / Wongnai / Tripadvisor ของแต่ละร้าน", "Review scores open from each place's Google / Wongnai / Tripadvisor button"), fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun LocalEmergency(vm: TripViewModel, lat: Double, lon: Double, placeName: String?, placeId: String?) {
    val thai = LocalThai.current
    val ctx = LocalContext.current
    Text(
        if (placeName != null) tr("เบอร์หน่วยงานใกล้ $placeName", "Local services near $placeName") else tr("เบอร์หน่วยงานใกล้คุณ", "Local services near you"),
        fontWeight = FontWeight.Bold,
    )
    Text(tr("โทรหาหน่วยงานในพื้นที่โดยตรงมักได้รับความช่วยเหลือเร็วกว่า", "Calling the local office directly is often faster"), fontSize = 12.sp, color = Color.DarkGray)
    listOf(
        "police" to tr("👮 สถานีตำรวจใกล้สุด", "👮 Nearest police station"),
        "hospital" to tr("🏥 โรงพยาบาลใกล้สุด", "🏥 Nearest hospital"),
        "fire" to tr("🚒 สถานีดับเพลิงใกล้สุด", "🚒 Nearest fire station"),
    ).forEach { (type, label) ->
        val result = produceState<Result<List<Poi>>?>(null, lat, lon, type, placeId) { value = vm.remote.poisFor(placeId, lat, lon, type, thai) }.value
        val best = result?.getOrNull()?.let { list ->
            when (type) {
                "police" -> list.firstOrNull { vm.remote.isPoliceStation(it.name) } ?: list.firstOrNull()
                "hospital" -> list.filter { it.health == Health.Kind.HOSPITAL }.minByOrNull { it.distanceKm } ?: list.firstOrNull()
                else -> list.firstOrNull()
            }
        }
        Column(Modifier.padding(top = 6.dp)) {
            Text(label, fontSize = 12.sp, color = Color.DarkGray)
            when {
                result == null -> Text(tr("กำลังค้นหา…", "Searching…"), fontSize = 13.sp, color = Color.Gray)
                best == null -> Text(tr("ไม่พบในแผนที่ ใช้สายด่วนด้านล่าง", "Not found on the map; use the hotlines below"), fontSize = 13.sp, color = Color.Gray)
                else -> {
                    Text("${best.name} · " + String.format(java.util.Locale.US, "%.1f km", best.distanceKm), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow {
                        if (best.phone != null) TextButton(onClick = { dial(ctx, best.phone) }) { Text("📞 ${best.phone}") }
                        else hotlines(type).first().let { (num, _, _) -> TextButton(onClick = { dial(ctx, num) }) { Text(tr("📞 สายด่วน $num", "📞 Hotline $num")) } }
                        TextButton(onClick = { open(ctx, "https://www.google.com/maps/dir/?api=1&destination=${best.lat},${best.lon}") }) { Text(tr("🧭 นำทาง", "🧭 Directions")) }
                    }
                }
            }
        }
    }
}
