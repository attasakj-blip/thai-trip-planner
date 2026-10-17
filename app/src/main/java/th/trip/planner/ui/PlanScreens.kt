@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package th.trip.planner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import th.trip.planner.TripViewModel
import th.trip.planner.logic.ScheduleIssue
import th.trip.planner.logic.TripLogic
import th.trip.planner.model.GapPurpose
import th.trip.planner.model.Place
import th.trip.planner.model.Region
import java.time.LocalDate
import java.time.YearMonth

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(vm: TripViewModel, nav: NavHostController) {
    val thai = LocalThai.current
    val trip = vm.trip
    var confirmNew by remember { mutableStateOf(false) }
    Page(APP_NAME, nav, showSettings = true) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("วางแผนเที่ยวไทยทีละ 1 ทริป", "Plan one Thailand trip at a time"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                LanguageToggle(vm)
            }
            VSpace()
            Text(
                tr("เลือกสถานที่ กำหนดวันในปฏิทิน เรียงลำดับ ตรวจคำเตือนภัย เลือกที่พักและการเดินทาง แล้วกดเริ่มทริปเพื่อติดตามการเดินทาง",
                    "Pick places, set calendar days, choose the order, check hazard warnings, choose stays and transport, then activate the plan to follow your trip."),
                color = Color.DarkGray,
            )
            VSpace(8)
            OutlinedButton(onClick = { nav.navigate(Routes.MY_TRIPS) }, modifier = Modifier.fillMaxWidth()) { Text(tr("🗂 ทริปของฉัน (อัลบั้มและสไลด์โชว์)", "🗂 My trips (albums & slideshows)")) }
            VSpace(16)
            when {
                trip.finished -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Text(tr("🎉 ทริปล่าสุดเสร็จสมบูรณ์แล้ว", "🎉 Your last trip is complete"), Modifier.padding(16.dp))
                    }
                    VSpace(12)
                    NextButton(tr("เริ่มวางแผนทริปใหม่", "Plan a new trip")) { vm.newTrip(); nav.navigate(Routes.CATALOG) }
                }
                trip.stops.isEmpty() -> NextButton(tr("เริ่มวางแผนทริป", "Start planning")) { nav.navigate(Routes.CATALOG) }
                else -> {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text(tr("ทริปของคุณ", "Your trip"), fontWeight = FontWeight.Bold)
                            trip.stops.forEachIndexed { i, s ->
                                val p = vm.repo.placeById[s.placeId] ?: return@forEachIndexed
                                Text("${i + 1}. ${p.name.get(thai)}  ${fmtDates(TripLogic.dates(s), thai)}", fontSize = 14.sp)
                            }
                        }
                    }
                    VSpace(12)
                    if (trip.activated) NextButton(tr("ดูความคืบหน้าทริป", "Open trip progress")) { nav.navigate(Routes.ACTIVE) }
                    else {
                        NextButton(tr("วางแผนต่อ", "Continue planning")) { nav.navigate(Routes.CATALOG) }
                        if (trip.legs.any { it.mode != null }) {
                            VSpace()
                            OutlinedButton(onClick = { nav.navigate(Routes.SUMMARY) }, modifier = Modifier.fillMaxWidth()) { Text(tr("ดูสรุปทริป", "View trip summary")) }
                        }
                    }
                    VSpace()
                    TextButton(onClick = { confirmNew = true }) { Text(tr("ลบทริปนี้และเริ่มใหม่", "Delete this trip and start over"), color = Coral) }
                }
            }
        }
    }
    if (confirmNew) AlertDialog(
        onDismissRequest = { confirmNew = false },
        title = { Text(tr("เริ่มทริปใหม่?", "Start a new trip?")) },
        text = { Text(tr("ข้อมูลทริปปัจจุบันจะถูกลบ", "The current trip will be deleted.")) },
        confirmButton = { TextButton(onClick = { vm.newTrip(); confirmNew = false }) { Text(tr("ลบและเริ่มใหม่", "Delete")) } },
        dismissButton = { TextButton(onClick = { confirmNew = false }) { Text(tr("ยกเลิก", "Cancel")) } },
    )
}

// ---------------------------------------------------------------- Step 1a: place library

private fun regionLabel(r: Region, thai: Boolean) = when (r) {
    Region.BANGKOK -> tr("กรุงเทพฯ", "Bangkok")
    Region.CENTRAL -> tr("ภาคกลาง", "Central")
    Region.NORTH -> tr("ภาคเหนือ", "North")
    Region.NORTHEAST -> tr("ภาคอีสาน", "Northeast")
    Region.EAST -> tr("ภาคตะวันออก", "East")
    Region.WEST -> tr("ภาคตะวันตก", "West")
    Region.SOUTH_ANDAMAN -> tr("ใต้ฝั่งอันดามัน", "South (Andaman)")
    Region.SOUTH_GULF -> tr("ใต้ฝั่งอ่าวไทย", "South (Gulf)")
}

@Composable
fun CatalogScreen(vm: TripViewModel, nav: NavHostController) {
    val thai = LocalThai.current
    var region by remember { mutableStateOf<Region?>(null) }
    val list = vm.repo.places.filter { region == null || it.region == region }
    val count = vm.trip.stops.size
    Page(
        tr("1. เลือกสถานที่ท่องเที่ยว", "1. Choose places"), nav, step = 1,
        bottom = { NextButton(tr("เลือกแล้ว $count แห่ง · ต่อไป: วันและลำดับ", "$count selected · Next: days & order"), enabled = count > 0) { nav.navigate(Routes.SCHEDULE) } },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = region == null, onClick = { region = null }, label = { Text(tr("ทั้งหมด", "All")) }) }
                    items(Region.entries.toList()) { r -> FilterChip(selected = region == r, onClick = { region = r }, label = { Text(regionLabel(r, thai)) }) }
                }
            }
            items(list, key = { it.id }) { p -> PlaceCard(vm, p, nav) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun PlaceCard(vm: TripViewModel, p: Place, nav: NavHostController) {
    val thai = LocalThai.current
    val photos = rememberPhotos(vm, p)
    val selected = vm.isSelected(p.id)
    Card(
        Modifier.fillMaxWidth().clickable { nav.navigate(Routes.place(p.id)) },
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.White),
    ) {
        Column(Modifier.padding(12.dp)) {
            CreditedPhoto(photos?.firstOrNull())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(p.name.get(thai), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("${p.province.get(thai)} · ${regionLabel(p.region, thai)}", fontSize = 13.sp, color = Color.Gray)
                }
                Checkbox(checked = selected, onCheckedChange = { vm.toggle(p.id) })
            }
            if (p.highlights.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    p.highlights.forEach { h -> Badge("🎉 ${h.name.get(thai)} · ${h.months.joinToString("/") { monthShort(it, thai) }}") }
                }
            }
        }
    }
}

@Composable
fun PlaceDetailScreen(vm: TripViewModel, nav: NavHostController, id: String) {
    val thai = LocalThai.current
    val p = vm.repo.placeById[id] ?: return
    val photos = rememberPhotos(vm, p).orEmpty()
    val uri = LocalUriHandler.current
    val selected = vm.isSelected(p.id)
    Page(
        p.name.get(thai), nav,
        bottom = {
            NextButton(if (selected) tr("✓ อยู่ในทริปแล้ว (แตะเพื่อเอาออก)", "✓ In your trip (tap to remove)") else tr("เพิ่มในทริป", "Add to trip")) { vm.toggle(p.id) }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (photos.isEmpty()) CreditedPhoto(null)
            else {
                val pager = rememberPagerState { photos.size }
                HorizontalPager(pager, pageSpacing = 8.dp) { i ->
                    Column {
                        CreditedPhoto(photos[i])
                        Text(
                            tr("ภาพจาก Wikimedia Commons · แตะเพื่อดูต้นฉบับ", "From Wikimedia Commons · tap for source"),
                            fontSize = 11.sp, color = Teal,
                            modifier = Modifier.clickable { uri.openUri(photos[i].sourcePage) }.padding(top = 4.dp),
                        )
                    }
                }
                if (photos.size > 1) Text("${pager.currentPage + 1}/${photos.size}", fontSize = 12.sp, color = Color.Gray)
            }
            VSpace(12)
            Text("${p.province.get(thai)}", color = Color.Gray)
            VSpace()
            Text(p.description.get(thai))
            if (p.highlights.isNotEmpty()) {
                SectionTitle(tr("ช่วงเวลาที่แนะนำ", "Best time to visit"))
                p.highlights.forEach { h ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("🎉 ${h.name.get(thai)} (${h.months.joinToString(", ") { monthShort(it, thai) }})", fontWeight = FontWeight.Bold)
                            Text(h.reason.get(thai), fontSize = 14.sp)
                        }
                    }
                }
            }
            if (p.closures.isNotEmpty()) {
                SectionTitle(tr("ข้อควรรู้", "Good to know"))
                p.closures.forEach { c -> Text("⚠️ ${c.note.get(thai)}", fontSize = 14.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------- Step 1b: days and order

@Composable
fun ScheduleScreen(vm: TripViewModel, nav: NavHostController) {
    val thai = LocalThai.current
    val trip = vm.trip
    var editing by remember { mutableStateOf<Int?>(null) }
    var pendingGaps by remember { mutableStateOf<List<th.trip.planner.logic.Gap>>(emptyList()) }
    val issues = TripLogic.validate(trip.stops)

    fun proceed() {
        val unanswered = TripLogic.gaps(trip.stops).filter { g -> g.dates.any { it.toString() !in vm.trip.gapPurposes } }
        if (unanswered.isEmpty()) nav.navigate(Routes.WARNINGS) else pendingGaps = unanswered
    }

    Page(
        tr("1. วันและลำดับการเที่ยว", "1. Days & visiting order"), nav, step = 1,
        bottom = { NextButton(tr("ต่อไป: ตรวจคำเตือน", "Next: check warnings"), enabled = trip.stops.isNotEmpty() && issues.isEmpty()) { proceed() } },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    tr("เรียงลำดับด้วยลูกศร แล้วเลือกวันในปฏิทินให้แต่ละที่ (ไม่จำเป็นต้องติดกัน)", "Use the arrows to set the order, then pick calendar days for each place (they don't have to be consecutive)."),
                    color = Color.DarkGray, fontSize = 14.sp,
                )
            }
            itemsIndexed(trip.stops, key = { _, s -> s.placeId }) { i, s ->
                val p = vm.repo.placeById[s.placeId] ?: return@itemsIndexed
                val d = TripLogic.dates(s)
                val stopIssues = issues.filter { (it is ScheduleIssue.NoDates && it.stopIndex == i) || (it is ScheduleIssue.Overlap && (it.first == i || it.second == i)) }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Teal), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(p.name.get(thai), fontWeight = FontWeight.Bold)
                            Text(
                                if (d.isEmpty()) tr("ยังไม่ได้เลือกวัน", "No days yet") else tr("${d.size} วัน: ", "${d.size} day(s): ") + fmtDates(d, thai),
                                fontSize = 13.sp, color = if (d.isEmpty()) Coral else Color.DarkGray,
                            )
                            stopIssues.forEach { iss ->
                                if (iss is ScheduleIssue.Overlap) Text(
                                    tr("วันที่ทับซ้อนหรือสลับกับลำดับถัดไป ปรับวันหรือลำดับ", "Days overlap with or come after the next stop. Adjust days or order."),
                                    fontSize = 12.sp, color = Coral,
                                )
                            }
                            TextButton(onClick = { editing = i }) { Text(tr("📅 เลือกวัน", "📅 Pick days")) }
                        }
                        Column {
                            IconButton(onClick = { vm.move(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, tr("เลื่อนขึ้น", "Move up")) }
                            IconButton(onClick = { vm.move(i, 1) }, enabled = i < trip.stops.size - 1) { Icon(Icons.Filled.KeyboardArrowDown, tr("เลื่อนลง", "Move down")) }
                        }
                    }
                }
            }
            item {
                Text(tr("ลำดับ: ", "Order: ") + trip.stops.mapIndexed { i, _ -> "${i + 1}" }.joinToString(" → "), fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    editing?.let { i ->
        val others = trip.stops.flatMapIndexed { j, s -> if (j == i) emptyList() else TripLogic.dates(s).map { it to j + 1 } }.toMap()
        CalendarDialog(
            initial = TripLogic.dates(trip.stops[i]).toSet(),
            otherStops = others,
            onDismiss = { editing = null },
            onDone = { vm.setDates(i, it); editing = null },
        )
    }

    pendingGaps.firstOrNull()?.let { gap ->
        val before = vm.repo.placeById[trip.stops[gap.afterStop].placeId]?.name?.get(thai) ?: ""
        val after = vm.repo.placeById[trip.stops[gap.beforeStop].placeId]?.name?.get(thai) ?: ""
        AlertDialog(
            onDismissRequest = { pendingGaps = emptyList() },
            title = { Text(tr("มีช่วงวันที่ไม่ได้เที่ยว", "Some days have no sightseeing")) },
            text = {
                Column {
                    Text(
                        tr("วันที่ ${fmtDates(gap.dates, true)} " + (if (gap.afterStop == gap.beforeStop) "อยู่ระหว่างการเที่ยว$before" else "อยู่ระหว่าง$before กับ$after") + " คุณจะใช้ช่วงนี้ทำอะไรครับ?",
                            "${fmtDates(gap.dates, false)} " + (if (gap.afterStop == gap.beforeStop) "falls within your days at $before" else "falls between $before and $after") + ". What will you do then?"),
                    )
                    VSpace()
                    GapPurpose.entries.forEach { p ->
                        OutlinedButton(onClick = {
                            vm.setGapPurpose(gap.dates, p)
                            val rest = pendingGaps.drop(1)
                            pendingGaps = rest
                            if (rest.isEmpty()) nav.navigate(Routes.WARNINGS)
                        }, modifier = Modifier.fillMaxWidth()) { Text(purposeLabel(p, thai)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pendingGaps = emptyList() }) { Text(tr("แก้ไขวันที่แทน", "Edit the days instead")) } },
        )
    }
}

/** Month calendar where any set of days can be toggled. Days used by other stops show their number. */
@Composable
fun CalendarDialog(initial: Set<LocalDate>, otherStops: Map<LocalDate, Int>, onDismiss: () -> Unit, onDone: (Set<LocalDate>) -> Unit) {
    val thai = LocalThai.current
    val selected = remember { mutableStateListOf<LocalDate>().apply { addAll(initial) } }
    var month by remember { mutableStateOf(YearMonth.from(initial.minOrNull() ?: otherStops.keys.maxOrNull() ?: LocalDate.now())) }
    val today = LocalDate.now()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = { onDone(selected.toSet()) }) { Text(tr("ตกลง (${selected.size} วัน)", "Done (${selected.size} days)")) } },
        dismissButton = { TextButton(onClick = { selected.clear() }) { Text(tr("ล้าง", "Clear")) } },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                    Text(monthYear(month.atDay(1), thai), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { month = month.plusMonths(1) }) { Text("›") }
                }
                val heads = if (thai) listOf("อา", "จ", "อ", "พ", "พฤ", "ศ", "ส")
                    else if (I18n.lang == "en") listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
                    else (0..6).map { java.time.DayOfWeek.SUNDAY.plus(it.toLong()).getDisplayName(java.time.format.TextStyle.NARROW, I18n.current.locale) }
                Row { heads.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = Color.Gray) } }
                val first = month.atDay(1)
                val offset = first.dayOfWeek.value % 7 // Sunday = 0
                val cells = offset + month.lengthOfMonth()
                for (week in 0 until (cells + 6) / 7) {
                    Row {
                        for (dow in 0 until 7) {
                            val dayNum = week * 7 + dow - offset + 1
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                                if (dayNum in 1..month.lengthOfMonth()) {
                                    val d = month.atDay(dayNum)
                                    val isSel = d in selected
                                    val other = otherStops[d]
                                    val past = d < today
                                    Box(
                                        Modifier.fillMaxSize().clip(CircleShape)
                                            .background(if (isSel) Teal else if (other != null) Color(0xFFFFE8D1) else Color.Transparent)
                                            .border(if (d == today) 1.dp else 0.dp, if (d == today) Teal else Color.Transparent, CircleShape)
                                            .clickable(enabled = !past) { if (isSel) selected.remove(d) else selected.add(d) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("$dayNum", fontSize = 13.sp, color = if (isSel) Color.White else if (past) Color.LightGray else Color.Black)
                                        if (other != null && !isSel) Text("$other", fontSize = 8.sp, color = Coral, modifier = Modifier.align(Alignment.BottomCenter))
                                    }
                                }
                            }
                        }
                    }
                }
                VSpace()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFFFE8D1)))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("วันที่ใช้กับสถานที่อื่น (ตัวเลข = ลำดับ)", "Days used by other places (number = order)"), fontSize = 11.sp, color = Color.Gray)
                }
            }
        },
    )
}
